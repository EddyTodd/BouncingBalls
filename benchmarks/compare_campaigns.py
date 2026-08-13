#!/usr/bin/env python3
"""Compare two CampaignCli JSONL datasets with matched scheduler ratios.

The script first forms CADQ/GLOBAL timing ratios within each campaign for the same
(workload, requestedBalls, seed, repetition) key. It then compares the candidate
ratios with the baseline ratios. This pairing reduces, but does not eliminate,
hosted-runner/JVM noise. Confidence intervals use a deterministic non-parametric
bootstrap over matched log-ratio changes.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import statistics
from pathlib import Path
from typing import Iterable

CADQ = "COMPUTE_AHEAD_DEPENDENCY_QUEUE"
GLOBAL = "GLOBAL_EVENT_QUEUE"
METRICS = ("totalEngineNanos", "constructionNanos", "advanceNanos")
KEY_FIELDS = ("workload", "requestedBalls", "seed", "repetition")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline", type=Path, help="baseline CampaignCli JSONL")
    parser.add_argument("candidate", type=Path, help="candidate CampaignCli JSONL")
    parser.add_argument("--balls", type=int, default=None, help="optional requested-ball-count filter")
    parser.add_argument("--bootstrap", type=int, default=20_000, help="bootstrap resamples (default: 20000)")
    parser.add_argument("--seed", type=int, default=42, help="bootstrap PRNG seed (default: 42)")
    return parser.parse_args()


def load_campaign(path: Path) -> list[dict]:
    records = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    summaries = [record for record in records if record.get("recordType") == "summary"]
    if not summaries:
        raise ValueError(f"{path}: no summary record")
    summary = summaries[-1]
    if not summary.get("passed"):
        raise ValueError(f"{path}: campaign did not pass correctness gate: {summary}")
    return records


def key(record: dict) -> tuple:
    return tuple(record[field] for field in KEY_FIELDS)


def paired_scheduler_ratios(records: Iterable[dict], metric: str, balls: int | None) -> dict[tuple, float]:
    values: dict[tuple, dict[str, float]] = {}
    for record in records:
        if record.get("recordType") != "trial" or not record.get("success"):
            continue
        if balls is not None and record.get("requestedBalls") != balls:
            continue
        algorithm = record.get("algorithm")
        if algorithm not in (CADQ, GLOBAL):
            continue
        values.setdefault(key(record), {})[algorithm] = float(record[metric])

    ratios: dict[tuple, float] = {}
    for scenario_key, algorithms in values.items():
        if CADQ in algorithms and GLOBAL in algorithms:
            denominator = algorithms[GLOBAL]
            if denominator <= 0:
                raise ValueError(f"non-positive GLOBAL timing for {scenario_key}: {denominator}")
            ratios[scenario_key] = algorithms[CADQ] / denominator
    return ratios


def geometric_mean(values: Iterable[float]) -> float:
    logs = [math.log(value) for value in values]
    return math.exp(statistics.fmean(logs))


def quantile(sorted_values: list[float], q: float) -> float:
    if not sorted_values:
        raise ValueError("empty quantile input")
    position = q * (len(sorted_values) - 1)
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return sorted_values[low]
    weight = position - low
    return sorted_values[low] * (1.0 - weight) + sorted_values[high] * weight


def bootstrap_factor(log_factors: list[float], samples: int, seed: int) -> tuple[float, float]:
    if samples <= 0:
        raise ValueError("--bootstrap must be positive")
    rng = random.Random(seed)
    count = len(log_factors)
    estimates = []
    for _ in range(samples):
        mean_log = statistics.fmean(log_factors[rng.randrange(count)] for _ in range(count))
        estimates.append(math.exp(mean_log))
    estimates.sort()
    return quantile(estimates, 0.025), quantile(estimates, 0.975)


def compare_metric(
    baseline: list[dict],
    candidate: list[dict],
    metric: str,
    balls: int | None,
    bootstrap: int,
    seed: int,
) -> tuple[float, float, float, float, float, int]:
    baseline_ratios = paired_scheduler_ratios(baseline, metric, balls)
    candidate_ratios = paired_scheduler_ratios(candidate, metric, balls)
    matched = sorted(set(baseline_ratios) & set(candidate_ratios))
    if not matched:
        raise ValueError(f"no matched trials for {metric}")

    log_factors = [math.log(candidate_ratios[k] / baseline_ratios[k]) for k in matched]
    factor = math.exp(statistics.fmean(log_factors))
    low, high = bootstrap_factor(log_factors, bootstrap, seed)
    return (
        geometric_mean(baseline_ratios[k] for k in matched),
        geometric_mean(candidate_ratios[k] for k in matched),
        factor,
        low,
        high,
        len(matched),
    )


def format_pct(factor: float) -> str:
    return f"{(factor - 1.0) * 100:+.2f}%"


def main() -> None:
    args = parse_args()
    baseline = load_campaign(args.baseline)
    candidate = load_campaign(args.candidate)

    scope = f"requestedBalls={args.balls}" if args.balls is not None else "all matched ball counts"
    print(f"# Paired campaign comparison ({scope})")
    print()
    print("| Metric | Baseline CADQ/GLOBAL | Candidate CADQ/GLOBAL | Candidate vs baseline | 95% bootstrap factor | Matched trials |")
    print("|---|---:|---:|---:|---:|---:|")

    for offset, metric in enumerate(METRICS):
        baseline_ratio, candidate_ratio, factor, low, high, count = compare_metric(
            baseline,
            candidate,
            metric,
            args.balls,
            args.bootstrap,
            args.seed + offset,
        )
        print(
            f"| `{metric}` | {baseline_ratio:.4f} | {candidate_ratio:.4f} | "
            f"{format_pct(factor)} | {low:.4f}–{high:.4f} | {count} |"
        )

    print()
    print("A factor below 1 favors the candidate. Hosted-runner pairing reduces noise but is not a substitute for cross-machine replication.")


if __name__ == "__main__":
    main()
