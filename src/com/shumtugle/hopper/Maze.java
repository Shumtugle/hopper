package com.shumtugle.hopper;

import java.util.ArrayList;
import java.util.Random;

/**
 * Grows a level from its number, so the levels never run out and the same number always gives
 * the same level.
 *
 * A level is grown path first: a random walk of hops through a stack of floors, every hop obeying
 * the rules, becomes the way through, so every level can be won. Each hop keeps the air it needs:
 * a leap or a throw keeps the cells over it empty, a drop keeps its column empty down to where it
 * lands. Shards, the bead and its hot plate and glass are laid along the way; then islands that
 * lead nowhere, and mirages, are scattered round it, never into the air the way needs.
 *
 * The climb: difficulty rises one step every three levels, and each step brings one thing in a
 * fixed order until it meets a ceiling, and stays there. After that clamp, not before it, every
 * twentieth level is one step easier and every thirtieth two: a breath, not a setback. Within a
 * step the length of the way still wavers a little either side.
 */
final class Maze {
    private Maze() {}

    private static final int[][] HOPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}, {1, -1}, {1, 1}, {-1, 1}, {-1, -1}};
    private static final int[][] LEAPS = {{0, -2}, {2, 0}, {0, 2}, {-2, 0}};

    /** The highest step: what fits a phone screen and the map in its corner. */
    static final int CEILING = 9;

    // the step at which each thing first appears
    private static final int GLASS = 2, MIRAGES = 3, THIRD_FLOOR = 4, PLATES = 5, SHARDS = 6, WIDE = 7;

    /** The difficulty step of level n (counted from 0), 1 to CEILING. */
    static int step(int n) {
        int k = n - Level.drawn();
        int base = 1 + Math.max(0, k) / 3;
        int step = Math.min(CEILING, base);
        int shown = n + 1;
        if (shown % 30 == 0) step -= 2;
        else if (shown % 20 == 0) step -= 1;
        return Math.max(1, step);
    }

    /** True if level n is one of the scheduled breathers. */
    static boolean breather(int n) {
        int shown = n + 1;
        return n >= Level.drawn() && (shown % 30 == 0 || shown % 20 == 0);
    }

    /** The fewest hops a level of this step should take. */
    static int target(int step) {
        return 8 + Math.round(step * 1.3f);
    }

    /**
     * Grows a few candidates and keeps the one whose shortest win is nearest the target: islands
     * that lead nowhere can still open shortcuts, so a level is judged by solving it, not by how
     * it was grown.
     */
    static Level make(int n) {
        Random r = new Random(seed(n));
        int step = step(n);
        // the way wavers a little round its step, now shorter, now longer
        int want = target(step) + r.nextInt(5) - 2;
        String hint = breather(n) ? "a breather" : "";
        Level best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < 40 && bestGap > 1; attempt++) {
            Level l = grow(r, step, want, hint);
            if (l == null) continue;
            int hops = Solver.hops(l, 40000);
            if (hops < 0) continue;
            int gap = Math.abs(hops - want);
            if (gap < bestGap) {
                bestGap = gap;
                best = l;
            }
        }
        // never needed in practice; a plain line keeps the game going if it ever is
        return best != null ? best : new Level("", "E", "#", "*", "#", "S");
    }

    private static long seed(int n) {
        return n * 0x9E3779B97F4A7C15L + 0x5DEECE66DL;
    }

    // what the grower knows about each cell of the stack
    private static int cols, rows, floors;
    private static char[] cell;
    /** Cells the way needs empty: the air over a leap or a throw, the fall of a drop. */
    private static boolean[] keep;
    /** Columns a leap passes over, on floors at or below the leap: no hot plate may go there. */
    private static boolean[] overLeap;

    private static int at(int x, int z, int f) {
        return (f * rows + z) * cols + x;
    }

    private static boolean inside(int x, int z) {
        return x >= 0 && z >= 0 && x < cols && z < rows;
    }

    private static boolean free(int x, int z, int f) {
        return f >= 0 && f < floors && inside(x, z) && cell[at(x, z, f)] == '.' && !keep[at(x, z, f)];
    }

    private static boolean empty(int x, int z, int f) {
        return f >= floors || !inside(x, z) || cell[at(x, z, f)] == '.';
    }

    private static synchronized Level grow(Random r, int step, int target, String hint) {
        floors = step >= THIRD_FLOOR ? 3 : 2;
        cols = 5 + r.nextInt(2) + (step >= WIDE ? 2 : step >= PLATES ? 1 : 0);
        rows = cols + r.nextInt(3) - 1;
        int want = target + 2 + r.nextInt(4);
        int n = cols * rows * floors;
        cell = new char[n];
        keep = new boolean[n];
        overLeap = new boolean[cols * rows * floors];
        java.util.Arrays.fill(cell, '.');

        ArrayList<Integer> path = new ArrayList<Integer>();
        int x = 1 + r.nextInt(cols - 2), z = rows - 1, f = 0;
        cell[at(x, z, f)] = '#';
        path.add(at(x, z, f));
        int climbs = 0;

        while (path.size() < want) {
            int[] next = null;
            // a throw needs a spring under the drop, so it is chosen only off a plain island
            boolean canThrow = f < floors - 1 && path.size() > 1 && cell[at(x, z, f)] == '#';
            float roll = r.nextFloat();
            float throwChance = canThrow ? (climbs < floors - 1 ? 0.45f : 0.18f) : 0f;
            float dropChance = f > 0 ? 0.22f : 0f;
            float leapChance = 0.14f + step * 0.02f;
            if (roll < throwChance) next = tryThrow(r, x, z, f);
            else if (roll < throwChance + dropChance) next = tryDrop(r, x, z, f);
            else if (roll < throwChance + dropChance + leapChance) next = tryLeap(r, x, z, f);
            if (next == null) next = tryHop(r, x, z, f);
            if (next == null) next = tryDrop(r, x, z, f);
            if (next == null) break;
            if (next[3] == World.THROW) {
                // the spring points the way it throws
                int dx = next[0] - x, dz = next[1] - z;
                cell[at(x, z, f)] = dz < 0 ? '^' : dx > 0 ? '>' : dz > 0 ? 'v' : '<';
                climbs++;
            }
            x = next[0];
            z = next[1];
            f = next[2];
            cell[at(x, z, f)] = '#';
            path.add(at(x, z, f));
        }
        if (path.size() < Math.max(6, want * 2 / 3)) return null;
        int last = path.size() - 1;
        cell[path.get(0)] = 'S';
        cell[path.get(last)] = 'E';

        // a bead and the hot plate it is for, the bead always first along the way
        if (step >= PLATES && last >= 6 && r.nextFloat() < 0.8f) {
            for (int tries = 0; tries < 12; tries++) {
                int hot = 3 + r.nextInt(last - 3);
                int bead = 1 + r.nextInt(hot - 1);
                int hc = path.get(hot), bc = path.get(bead);
                if (cell[hc] != '#' || cell[bc] != '#' || overLeap[hc]) continue;
                cell[hc] = 'x';
                cell[bc] = 'b';
                break;
            }
        }
        // glass on the way: safe, because the way never comes back to a cell
        float glassChance = step >= GLASS ? Math.min(0.4f, 0.15f + step * 0.03f) : 0f;
        for (int i = 0; i < last; i++) {
            int c = path.get(i);
            if (cell[c] == '#' && r.nextFloat() < glassChance) cell[c] = 'o';
        }
        // shards along the way, spread out: never two in a row
        int shards = Math.min(5, 2 + (step >= SHARDS ? 1 + (step - SHARDS) / 2 : step / 3));
        int placed = 0;
        for (int tries = 0; placed < shards && tries < 60; tries++) {
            int k = 1 + r.nextInt(last - 1);
            int c = path.get(k);
            if (cell[c] != '#' && cell[c] != 'o') continue;
            if (cell[path.get(k - 1)] == '*' || cell[path.get(k + 1)] == '*') continue;
            cell[c] = '*';
            placed++;
        }
        if (placed < 2) return null;

        // islands that lead nowhere: walkways on the floors above, dead ends, a few ceilings
        float decoy = Math.min(0.24f, 0.1f + step * 0.015f);
        float mirage = step >= MIRAGES ? Math.min(0.5f, 0.25f + (step - MIRAGES) * 0.05f) : 0f;
        for (int ff = 0; ff < floors; ff++)
            for (int zz = 0; zz < rows; zz++)
                for (int xx = 0; xx < cols; xx++) {
                    int c = at(xx, zz, ff);
                    if (!free(xx, zz, ff)) continue;
                    // an island only where something stands next to it on the same floor
                    if (!neighbour(xx, zz, ff)) continue;
                    if (r.nextFloat() > decoy * (ff == 0 ? 1f : 1.6f)) continue;
                    float k = r.nextFloat();
                    if (k < mirage) cell[c] = 'm';
                    else if (k < mirage + 0.15f && step >= GLASS) cell[c] = 'o';
                    else if (k < mirage + 0.22f && step >= PLATES && !overLeap[c]) cell[c] = 'x';
                    else cell[c] = '#';
                }

        String[][] map = new String[floors][rows];
        for (int ff = 0; ff < floors; ff++)
            for (int zz = 0; zz < rows; zz++) {
                StringBuilder s = new StringBuilder();
                for (int xx = 0; xx < cols; xx++) s.append(cell[at(xx, zz, ff)]);
                map[ff][zz] = s.toString();
            }
        return new Level(hint, map);
    }

    private static boolean neighbour(int x, int z, int f) {
        for (int[] m : HOPS) {
            int nx = x + m[0], nz = z + m[1];
            if (inside(nx, nz) && cell[at(nx, nz, f)] != '.') return true;
        }
        return false;
    }

    /** A hop to a neighbouring cell on the same floor. */
    private static int[] tryHop(Random r, int x, int z, int f) {
        int off = r.nextInt(HOPS.length);
        for (int k = 0; k < HOPS.length; k++) {
            int[] m = HOPS[(k + off) % HOPS.length];
            int tx = x + m[0], tz = z + m[1];
            if (free(tx, tz, f)) return new int[] {tx, tz, f, World.HOP};
        }
        return null;
    }

    /** A hop off the edge of a floor down to a lower one; the column between stays empty. */
    private static int[] tryDrop(Random r, int x, int z, int f) {
        if (f == 0) return null;
        int off = r.nextInt(HOPS.length);
        for (int k = 0; k < HOPS.length; k++) {
            int[] m = HOPS[(k + off) % HOPS.length];
            int tx = x + m[0], tz = z + m[1];
            if (!inside(tx, tz)) continue;
            int g = r.nextInt(f);
            boolean clear = free(tx, tz, g);
            for (int h = g + 1; h <= f && clear; h++) clear = empty(tx, tz, h);
            if (!clear) continue;
            for (int h = g + 1; h <= f; h++) keep[at(tx, tz, h)] = true;
            return new int[] {tx, tz, g, World.HOP};
        }
        return null;
    }

    /** A leap of two cells on the same floor, with open air over its start, middle and end. */
    private static int[] tryLeap(Random r, int x, int z, int f) {
        int off = r.nextInt(LEAPS.length);
        for (int k = 0; k < LEAPS.length; k++) {
            int[] m = LEAPS[(k + off) % LEAPS.length];
            int tx = x + m[0], tz = z + m[1], mx = x + m[0] / 2, mz = z + m[1] / 2;
            if (!free(tx, tz, f)) continue;
            if (!airAbove(x, z, f) || !airAbove(mx, mz, f) || !airAbove(tx, tz, f)) continue;
            reserveAbove(x, z, f);
            reserveAbove(mx, mz, f);
            reserveAbove(tx, tz, f);
            for (int h = 0; h <= f; h++) overLeap[at(mx, mz, h)] = true;
            return new int[] {tx, tz, f, World.LEAP};
        }
        return null;
    }

    /** A spring's throw two cells ahead and one floor up, with open air over its start and middle. */
    private static int[] tryThrow(Random r, int x, int z, int f) {
        int off = r.nextInt(LEAPS.length);
        for (int k = 0; k < LEAPS.length; k++) {
            int[] m = LEAPS[(k + off) % LEAPS.length];
            int tx = x + m[0], tz = z + m[1], mx = x + m[0] / 2, mz = z + m[1] / 2;
            if (!free(tx, tz, f + 1)) continue;
            if (!airAbove(x, z, f) || !airAbove(mx, mz, f)) continue;
            reserveAbove(x, z, f);
            reserveAbove(mx, mz, f);
            for (int h = 0; h <= f; h++) overLeap[at(mx, mz, h)] = true;
            return new int[] {tx, tz, f + 1, World.THROW};
        }
        return null;
    }

    private static boolean airAbove(int x, int z, int f) {
        return empty(x, z, f + 1);
    }

    private static void reserveAbove(int x, int z, int f) {
        if (f + 1 < floors && inside(x, z)) keep[at(x, z, f + 1)] = true;
    }
}
