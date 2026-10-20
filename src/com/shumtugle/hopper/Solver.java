package com.shumtugle.hopper;

import java.util.ArrayDeque;
import java.util.HashMap;

/**
 * Finds the fewest hops that win a level, by trying every hop from every position, nearest
 * first. The maze maker uses it to tell an easy level from a hard one.
 *
 * Turning is free, so at each landing the drop may hop to any of the eight cells round it or leap
 * two cells in a straight line; a spring allows only its throw, two cells its own way. The rules are the world's: the
 * drop lands on the highest island in the column no higher than its floor (a throw, one floor
 * higher); glass sinks once left; a hot plate needs a bead; heat burns a leap over it; a mirage
 * holds nothing; a floor overhead stops a leap or a throw.
 */
final class Solver {
    private Solver() {}

    private static final int[][] MOVES = {
        {0, -1}, {1, 0}, {0, 1}, {-1, 0}, {1, -1}, {1, 1}, {-1, 1}, {-1, -1},
        {0, -2}, {2, 0}, {0, 2}, {-2, 0}};

    // a position packed in a long: cell, shards taken, beads taken, glass gone, plates cooled, bead held
    private static final int CELL = 9, SHARDS = 6, BEADS = 4, GLASS = 24, HOT = 10;
    private static final int AT_SHARDS = CELL, AT_BEADS = AT_SHARDS + SHARDS, AT_GLASS = AT_BEADS + BEADS,
            AT_HOT = AT_GLASS + GLASS, AT_HELD = AT_HOT + HOT;

    private static Level l;
    private static int[] glassBit, hotBit;

    /** The fewest hops that win, or -1 if there is no way or the search gave up after most positions. */
    static synchronized int hops(Level level, int most) {
        int[] p = search(level, most, false);
        return p == null ? -1 : p[0];
    }

    /**
     * The fewest hops that win, as moves: cells across and along for each hop in turn, so
     * {dx0, dz0, dx1, dz1, ...}; null if there is no way within most positions.
     */
    static synchronized int[] path(Level level, int most) {
        int[] p = search(level, most, true);
        if (p == null) return null;
        int[] moves = new int[p.length - 1];
        System.arraycopy(p, 1, moves, 0, moves.length);
        return moves;
    }

    /** The search itself: the hop count first, then the moves if asked for. */
    private static int[] search(Level level, int most, boolean keep) {
        l = level;
        int n = l.type.length;
        int[] shardBit = new int[n], beadBit = new int[n];
        glassBit = new int[n];
        hotBit = new int[n];
        int shards = 0, beads = 0, glass = 0, hot = 0;
        for (int i = 0; i < n; i++) {
            shardBit[i] = beadBit[i] = glassBit[i] = hotBit[i] = -1;
            if (l.shard[i]) shardBit[i] = shards++;
            if (l.bead[i]) beadBit[i] = beads++;
            if (l.type[i] == Level.GLASS) glassBit[i] = glass++;
            if (l.type[i] == Level.HOT) hotBit[i] = hot++;
        }
        if (n > (1 << CELL) || shards > SHARDS || beads > BEADS || glass > GLASS || hot > HOT) return null;
        long allShards = (1L << shards) - 1;
        // an act of a shifting level has no gate: there the win is all its shards
        boolean gate = false;
        for (int i = 0; i < n; i++) if (l.type[i] == Level.GATE) gate = true;
        int plane = l.cols * l.rows;

        HashMap<Long, Integer> seen = new HashMap<Long, Integer>();
        // for each position, the one it came from and the move that brought it
        HashMap<Long, long[]> from = keep ? new HashMap<Long, long[]>() : null;
        ArrayDeque<Long> queue = new ArrayDeque<Long>();
        long start = l.index(l.startX, l.startZ, l.startF);
        seen.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            if (seen.size() > most) return null;
            long s = queue.poll();
            int depth = seen.get(s);
            int p = (int) (s & ((1 << CELL) - 1));
            int pf = p / plane, px = p % plane % l.cols, pz = p % plane / l.cols;
            boolean spring = kind(s, px, pz, pf) == Level.SPRING;
            for (int[] m : MOVES) {
                int dx = m[0], dz = m[1];
                boolean leap = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (spring && (!leap || m != MOVES[8 + l.aim[p]])) continue;
                int tx = px + dx, tz = pz + dz, mx = px + dx / 2, mz = pz + dz / 2;
                if (leap) {
                    // open air is needed over the start and the middle, and over the end of a leap
                    if (solid(s, px, pz, pf + 1) || solid(s, mx, mz, pf + 1)) continue;
                    if (!spring && solid(s, tx, tz, pf + 1)) continue;
                    int under = landing(s, mx, mz, pf);
                    if (under >= 0 && kind(s, mx, mz, under) == Level.HOT) continue;
                }
                int tf = landing(s, tx, tz, spring ? pf + 1 : pf);
                if (tf < 0) continue;
                int t = l.index(tx, tz, tf);
                int there = kind(s, tx, tz, tf);
                long ns = s;
                // leaving glass sinks it
                if (glassBit[p] >= 0) ns |= 1L << (AT_GLASS + glassBit[p]);
                boolean held = (ns >>> AT_HELD & 1) != 0;
                if (there == Level.HOT) {
                    if (!held) continue;
                    held = false;
                    ns |= 1L << (AT_HOT + hotBit[t]);
                }
                if (beadBit[t] >= 0 && !held && (ns >>> (AT_BEADS + beadBit[t]) & 1) == 0) {
                    held = true;
                    ns |= 1L << (AT_BEADS + beadBit[t]);
                }
                if (shardBit[t] >= 0) ns |= 1L << (AT_SHARDS + shardBit[t]);
                ns = held ? ns | 1L << AT_HELD : ns & ~(1L << AT_HELD);
                ns = (ns & ~((1L << CELL) - 1)) | t;
                boolean all = ((ns >>> AT_SHARDS) & allShards) == allShards;
                if (all && (there == Level.GATE || !gate)) {
                    if (!keep) return new int[] {depth + 1};
                    from.put(ns, new long[] {s, dx, dz});
                    return walkBack(from, ns, start, depth + 1);
                }
                if (seen.containsKey(ns)) continue;
                seen.put(ns, depth + 1);
                if (keep) from.put(ns, new long[] {s, dx, dz});
                queue.add(ns);
            }
        }
        return null;
    }

    /** Follows the positions back from the win to the start, writing the moves in order. */
    private static int[] walkBack(HashMap<Long, long[]> from, long end, long start, int hops) {
        int[] out = new int[1 + hops * 2];
        out[0] = hops;
        long k = end;
        for (int i = hops - 1; i >= 0 && k != start; i--) {
            long[] f = from.get(k);
            out[1 + i * 2] = (int) f[1];
            out[2 + i * 2] = (int) f[2];
            k = f[0];
        }
        return out;
    }

    /** The floor the drop lands on in a column, falling from floor most; -1 for the liquid. */
    private static int landing(long s, int x, int z, int most) {
        for (int f = Math.min(most, l.floors - 1); f >= 0; f--) if (solid(s, x, z, f)) return f;
        return -1;
    }

    private static boolean solid(long s, int x, int z, int f) {
        return kind(s, x, z, f) != Level.LIQUID;
    }

    /** What the rules see at a cell in this position: sunk glass and mirages hold nothing, cooled plates are stone. */
    private static int kind(long s, int x, int z, int f) {
        if (x < 0 || z < 0 || x >= l.cols || z >= l.rows || f < 0 || f >= l.floors) return Level.LIQUID;
        int i = l.index(x, z, f);
        int t = l.type[i];
        if (t == Level.MIRAGE) return Level.LIQUID;
        if (t == Level.GLASS && (s >>> (AT_GLASS + glassBit[i]) & 1) != 0) return Level.LIQUID;
        if (t == Level.HOT && (s >>> (AT_HOT + hotBit[i]) & 1) != 0) return Level.STONE;
        return t;
    }
}
