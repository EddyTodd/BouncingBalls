#!/usr/bin/env python3
import csv
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "cadq-lower-bound-probe.csv"
with open(path, newline="") as handle:
    rows = list(csv.DictReader(handle))
if not rows:
    raise SystemExit("mechanism harness produced no rows")

baseline = {}
for row in rows:
    if int(row["k"]) == 0:
        baseline[(row["workload"], int(row["requestedBalls"]), int(row["seed"]))] = row

def iv(row, key):
    return int(row[key])

print("aggregate by body count and k")
print("balls,k,pair_reduction_pct,quadratic_reduction_pct,quartic_reduction_pct,bounds_per_saved_query,probe_tighten_rate_pct")
for balls in sorted({int(row["requestedBalls"]) for row in rows}):
    ks = sorted({int(row["k"]) for row in rows if int(row["k"]) > 0})
    for k in ks:
        selected = [row for row in rows if int(row["requestedBalls"]) == balls and int(row["k"]) == k]
        base_rows = [baseline[(row["workload"], balls, int(row["seed"]))] for row in selected]
        bp = sum(iv(row, "pairQueries") for row in base_rows)
        cp = sum(iv(row, "pairQueries") for row in selected)
        bq = sum(iv(row, "quadraticPairQueries") for row in base_rows)
        cq = sum(iv(row, "quadraticPairQueries") for row in selected)
        b4 = sum(iv(row, "quarticPairQueries") for row in base_rows)
        c4 = sum(iv(row, "quarticPairQueries") for row in selected)
        bounds = sum(iv(row, "lowerBoundEvaluations") for row in selected)
        probes = sum(iv(row, "probeExactQueries") for row in selected)
        tightens = sum(iv(row, "probeHorizonTightens") for row in selected)
        saved = bp - cp
        pair_reduction = 100.0 * saved / bp if bp else 0.0
        quad_reduction = 100.0 * (bq - cq) / bq if bq else 0.0
        quartic_reduction = 100.0 * (b4 - c4) / b4 if b4 else 0.0
        bounds_per_saved = bounds / saved if saved > 0 else float("inf")
        tighten_rate = 100.0 * tightens / probes if probes else 0.0
        print(f"{balls},{k},{pair_reduction:.6f},{quad_reduction:.6f},{quartic_reduction:.6f},{bounds_per_saved:.3f},{tighten_rate:.3f}")

print("\n300-body workload detail")
print("workload,k,pair_reduction_pct,quartic_reduction_pct")
for workload in sorted({row["workload"] for row in rows}):
    for k in (1, 2, 4, 8):
        selected = [row for row in rows if row["workload"] == workload and int(row["requestedBalls"]) == 300 and int(row["k"]) == k]
        base_rows = [baseline[(workload, 300, int(row["seed"]))] for row in selected]
        bp = sum(iv(row, "pairQueries") for row in base_rows)
        cp = sum(iv(row, "pairQueries") for row in selected)
        b4 = sum(iv(row, "quarticPairQueries") for row in base_rows)
        c4 = sum(iv(row, "quarticPairQueries") for row in selected)
        pair_reduction = 100.0 * (bp - cp) / bp if bp else 0.0
        quartic_reduction = 100.0 * (b4 - c4) / b4 if b4 else 0.0
        print(f"{workload},{k},{pair_reduction:.6f},{quartic_reduction:.6f}")
