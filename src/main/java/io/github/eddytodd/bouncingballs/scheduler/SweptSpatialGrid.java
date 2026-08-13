package io.github.eddytodd.bouncingballs.scheduler;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.Arrays;

/**
 * Conservative current-position grid queried with swept per-axis motion bounds.
 *
 * <p>The grid is rebuilt only at scheduler synchronization points: construction and immediately after a physical
 * event batch, when every body position represents the same simulation time. For an owner horizon {@code h}, a
 * candidate can collide only if its current center lies inside the owner's current center expanded by both radii,
 * the owner's maximum per-axis displacement through {@code h}, and the maximum possible candidate displacement in
 * the system through {@code h}. Bodies outside that rectangle are therefore safe to omit before exact TOI.</p>
 *
 * <p>The spatial bins index current centers, not current overlap. Fast and accelerated bodies remain safe because the
 * query rectangle is swept forward conservatively. Non-finite state, overflow, or an unavailable finite horizon
 * disables spatial rejection and returns the complete canonical candidate set.</p>
 */
final class SweptSpatialGrid {
    private static final int TARGET_BODIES_PER_CELL = 4;
    private static final double SLACK_MULTIPLIER = 16.0;

    private Ball[] bodies = new Ball[0];
    private Bounds bounds;
    private int columns;
    private int rows;
    private double cellWidth;
    private double cellHeight;
    private int[] heads = new int[0];
    private int[] next = new int[0];
    private double maxRadius;
    private double maxAbsVx;
    private double maxAbsVy;
    private double maxAbsAx;
    private double maxAbsAy;
    private boolean usable;

    void rebuild(Ball[] bodies, Bounds bounds, SimulationStats stats) {
        this.bodies = bodies;
        this.bounds = bounds;
        stats.cadqSpatialGridRebuilds++;
        usable = false;

        if (bodies.length == 0) {
            heads = new int[0];
            next = new int[0];
            return;
        }

        double width = bounds.maxX() - bounds.minX();
        double height = bounds.maxY() - bounds.minY();
        double area = width * height;
        if (!(width > 0) || !(height > 0) || !Double.isFinite(width) || !Double.isFinite(height)
                || !(area > 0) || !Double.isFinite(area)) {
            return;
        }

        maxRadius = maxAbsVx = maxAbsVy = maxAbsAx = maxAbsAy = 0;
        for (Ball body : bodies) {
            if (!finite(body)) return;
            if (body.position.x < bounds.minX() || body.position.x > bounds.maxX()
                    || body.position.y < bounds.minY() || body.position.y > bounds.maxY()) {
                return;
            }
            maxRadius = Math.max(maxRadius, body.radius);
            maxAbsVx = Math.max(maxAbsVx, Math.abs(body.velocity.x));
            maxAbsVy = Math.max(maxAbsVy, Math.abs(body.velocity.y));
            maxAbsAx = Math.max(maxAbsAx, Math.abs(body.acceleration.x));
            maxAbsAy = Math.max(maxAbsAy, Math.abs(body.acceleration.y));
        }

        int targetCells = Math.max(1, (bodies.length + TARGET_BODIES_PER_CELL - 1) / TARGET_BODIES_PER_CELL);
        double idealCell = Math.sqrt(area / targetCells);
        if (!(idealCell > 0) || !Double.isFinite(idealCell)) return;

        double columnCount = Math.ceil(width / idealCell);
        double rowCount = Math.ceil(height / idealCell);
        if (!(columnCount >= 1) || !(rowCount >= 1)
                || columnCount > Integer.MAX_VALUE || rowCount > Integer.MAX_VALUE) {
            return;
        }

        columns = Math.max(1, (int) columnCount);
        rows = Math.max(1, (int) rowCount);
        long cellCount = (long) columns * rows;
        if (cellCount <= 0 || cellCount > Integer.MAX_VALUE) return;

        cellWidth = width / columns;
        cellHeight = height / rows;
        if (!(cellWidth > 0) || !(cellHeight > 0) || !Double.isFinite(cellWidth) || !Double.isFinite(cellHeight)) {
            return;
        }

        heads = new int[(int) cellCount];
        Arrays.fill(heads, -1);
        next = new int[bodies.length];
        Arrays.fill(next, -1);

        for (int slot = 0; slot < bodies.length; slot++) {
            Ball body = bodies[slot];
            int cell = cellY(body.position.y) * columns + cellX(body.position.x);
            next[slot] = heads[cell];
            heads[cell] = slot;
        }

        stats.cadqSpatialMaxGridCells = Math.max(stats.cadqSpatialMaxGridCells, cellCount);
        usable = true;
    }

    int queryCanonicalCandidates(
            int ownerSlot,
            double horizon,
            NumericalPolicy policy,
            SimulationStats stats,
            int[] out) {
        int possible = Math.max(0, bodies.length - ownerSlot - 1);
        stats.cadqSpatialQueries++;
        if (possible == 0) return 0;
        if (!usable || !Double.isFinite(horizon) || horizon < 0 || out.length < bodies.length) {
            return fallback(ownerSlot, stats, out);
        }

        Ball owner = bodies[ownerSlot];
        double h2 = horizon * horizon;
        if (!Double.isFinite(h2)) return fallback(ownerSlot, stats, out);

        double reachX = owner.radius + maxRadius
                + (Math.abs(owner.velocity.x) + maxAbsVx) * horizon
                + 0.5 * (Math.abs(owner.acceleration.x) + maxAbsAx) * h2;
        double reachY = owner.radius + maxRadius
                + (Math.abs(owner.velocity.y) + maxAbsVy) * horizon
                + 0.5 * (Math.abs(owner.acceleration.y) + maxAbsAy) * h2;
        if (!Double.isFinite(reachX) || !Double.isFinite(reachY)) return fallback(ownerSlot, stats, out);

        double scale = Math.max(
                Math.max(Math.abs(owner.position.x), Math.abs(owner.position.y)),
                Math.max(reachX, reachY));
        double slack = SLACK_MULTIPLIER * policy.tolerance(scale);
        reachX += slack;
        reachY += slack;
        if (!Double.isFinite(reachX) || !Double.isFinite(reachY)) return fallback(ownerSlot, stats, out);

        double minX = owner.position.x - reachX;
        double maxX = owner.position.x + reachX;
        double minY = owner.position.y - reachY;
        double maxY = owner.position.y + reachY;
        if (!Double.isFinite(minX) || !Double.isFinite(maxX) || !Double.isFinite(minY) || !Double.isFinite(maxY)) {
            return fallback(ownerSlot, stats, out);
        }

        if (maxX < bounds.minX() || minX > bounds.maxX() || maxY < bounds.minY() || minY > bounds.maxY()) {
            stats.cadqSpatialPairsExcluded += possible;
            return 0;
        }

        int minCellX = cellX(Math.max(minX, bounds.minX()));
        int maxCellX = cellX(Math.min(maxX, bounds.maxX()));
        int minCellY = cellY(Math.max(minY, bounds.minY()));
        int maxCellY = cellY(Math.min(maxY, bounds.maxY()));
        stats.cadqSpatialCellVisits += (long) (maxCellX - minCellX + 1) * (maxCellY - minCellY + 1);

        int count = 0;
        for (int y = minCellY; y <= maxCellY; y++) {
            int row = y * columns;
            for (int x = minCellX; x <= maxCellX; x++) {
                for (int slot = heads[row + x]; slot >= 0; slot = next[slot]) {
                    if (slot <= ownerSlot) continue;
                    Ball candidate = bodies[slot];
                    if (candidate.position.x < minX || candidate.position.x > maxX
                            || candidate.position.y < minY || candidate.position.y > maxY) {
                        continue;
                    }
                    out[count++] = slot;
                }
            }
        }

        Arrays.sort(out, 0, count);
        stats.cadqSpatialCandidates += count;
        stats.cadqSpatialPairsExcluded += possible - count;
        return count;
    }

    private int fallback(int ownerSlot, SimulationStats stats, int[] out) {
        stats.cadqSpatialFallbacks++;
        int count = 0;
        for (int slot = ownerSlot + 1; slot < bodies.length; slot++) out[count++] = slot;
        stats.cadqSpatialCandidates += count;
        return count;
    }

    private int cellX(double x) {
        int cell = (int) ((x - bounds.minX()) / cellWidth);
        if (cell < 0) return 0;
        return Math.min(cell, columns - 1);
    }

    private int cellY(double y) {
        int cell = (int) ((y - bounds.minY()) / cellHeight);
        if (cell < 0) return 0;
        return Math.min(cell, rows - 1);
    }

    private static boolean finite(Ball body) {
        return Double.isFinite(body.radius)
                && Double.isFinite(body.position.x)
                && Double.isFinite(body.position.y)
                && Double.isFinite(body.velocity.x)
                && Double.isFinite(body.velocity.y)
                && Double.isFinite(body.acceleration.x)
                && Double.isFinite(body.acceleration.y);
    }
}
