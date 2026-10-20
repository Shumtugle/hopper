package com.shumtugle.hopper;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * The black drop of the hundredth level: the liquid itself, stood up. It bounces by the same
 * rules as the drop, hop, slant, leap and a spring's throw, and every landing it hops the way
 * that brings it nearest to the drop. It keeps off glass and hot plates, so the board it is
 * fought on stays whole, and it never goes into the liquid.
 *
 * Landing on the drop's island, it strikes. Landed on from a floor above, it is beaten.
 * When the drop is out of its reach, up on a floor no throw of a spring can bring it to, it
 * comes as near as it can underneath: that is the moment to come down on it.
 */
final class Boss {
    static final float RADIUS = 0.21f;
    /** How often it bounces on the spot instead of hopping, and how fast it plays. */
    private static final float HESITATE = 0.22f, LOOK_UP = 0.45f, STARE = 0.85f, PACE = 0.85f;
    private static final float HOP_TIME = 0.72f, LEAP_TIME = 1.0f, SPOT_TIME = 0.62f, THROW_TIME = 1.2f;
    private static final float HOP_HIGH = 0.62f, LEAP_HIGH = 1.15f, SPOT_HIGH = 0.48f, FALL_TIME = 1.3f;
    /** Its tower stands far out beyond the board's far side; it leaps from the top. */
    static final float TOWER_Z = -10.5f, TOWER_TOP = 7.6f, TOWER_LEAP = 2.6f;

    static float towerX(World w) {
        return (w.cols - 1) * 0.5f;
    }
    private static final int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};

    /** The island it stands on, or last stood on. */
    int cx, cz, cf;
    /** Where the hop in progress lands. */
    int tx, tz, tf;
    float x, y, z;
    private float age, time = FALL_TIME, high, fromY, toY;
    /** Where its leap in starts: the top of its tower. */
    private float fromX, fromZ;
    /** Falling in from above; out of play while the world is cut. */
    boolean falling = true, away;
    /** It stands right under the drop, looking up. */
    boolean staring;
    private final Random rnd;
    private static int made;

    Boss(World w, int seed) {
        // every try its own fight
        rnd = new Random(seed * 2654435761L + 11L + 7919L * made++);
        place(w);
    }

    /** Drops in from above onto the island farthest from the drop, on the ground floor if it can. */
    void place(World w) {
        int[] far = farthest(w);
        cx = tx = far[0];
        cz = tz = far[1];
        cf = tf = far[2];
        // it comes down from the top of its tower in one long leap
        fromX = towerX(w);
        fromZ = TOWER_Z;
        fromY = TOWER_TOP + RADIUS;
        x = fromX;
        z = fromZ;
        toY = World.top(cf);
        y = fromY;
        age = 0f;
        time = TOWER_LEAP;
        high = 2.5f;
        falling = true;
        away = false;
        staring = false;
    }

    /** Plays one step of time; tells the world when it lands. */
    void step(World w, float dt) {
        if (away) return;
        age += dt * PACE;
        float t = Math.min(1f, age / time);
        if (falling) {
            x = fromX + (cx - fromX) * t;
            z = fromZ + (cz - fromZ) * t;
            y = fromY + (toY - fromY) * t + 4f * high * t * (1f - t);
        } else {
            x = cx + (tx - cx) * t;
            z = cz + (tz - cz) * t;
            y = fromY + (toY - fromY) * t + 4f * high * t * (1f - t);
        }
        if (t >= 1f) {
            falling = false;
            cx = tx;
            cz = tz;
            cf = tf;
            x = cx;
            z = cz;
            y = World.top(cf);
            w.bossLanded(this);
            if (!away) takeOff(w);
        }
    }

    /**
     * Whether it is there, on this island or just over it, where a drop landing on the island
     * would meet it: within the island's column and not yet high in its bounce.
     */
    boolean at(int x0, int z0, int f0) {
        if (away || falling && age / time < 0.85f) return false;
        return Math.abs(x - x0) < 0.6f && Math.abs(z - z0) < 0.6f && y - World.top(f0) < 0.9f && y >= World.top(f0) - 0.05f;
    }

    private void takeOff(World w) {
        // with the drop up above it, it stops and looks up more often, as if working out the way;
        // right under the drop it all but stands and stares: that is the moment to come down on it
        boolean under = w.cellF > cf && within(w);
        float doubt = under ? STARE : w.cellF > cf ? LOOK_UP : HESITATE;
        int[] m = rnd.nextFloat() < doubt ? null : next(w);
        // standing and staring up: its rim burns brighter, and that is the moment to come down
        staring = under && m == null;
        int kind;
        if (m == null) {
            tx = cx;
            tz = cz;
            tf = cf;
            kind = World.STILL;
        } else {
            tx = m[0];
            tz = m[1];
            tf = m[2];
            kind = m[3];
        }
        fromY = World.top(cf);
        toY = World.top(tf);
        time = kind == World.STILL ? SPOT_TIME : kind == World.THROW ? THROW_TIME : kind == World.LEAP ? LEAP_TIME : HOP_TIME;
        high = (kind == World.STILL ? SPOT_HIGH : kind == World.HOP ? HOP_HIGH : LEAP_HIGH) + Math.max(0f, toY - fromY);
        age = 0f;
    }

    /**
     * Whether the drop, from the island it stands on, could come straight down on it: one hop or
     * one leap that lands on its island. Only there it stops to stare, so the window is a real one.
     */
    private boolean within(World w) {
        int x0 = w.cellX, z0 = w.cellZ, f = w.cellF;
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                if (cx != x0 + dx || cz != z0 + dz) continue;
                boolean leap = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (leap && dx != 0 && dz != 0) return false;
                if (leap && (w.ceiling(x0, z0, f) || w.ceiling(x0 + dx / 2, z0 + dz / 2, f) || w.ceiling(cx, cz, f))) return false;
                return w.landing(cx, cz, f) == cf;
            }
        return false;
    }

    /**
     * The first hop of the shortest way to the drop's island; if there is none, of the way to
     * the island nearest the drop it can reach. Null to stay where it is.
     */
    private int[] next(World w) {
        int plane = w.cols * w.rows, n = plane * w.floors;
        int[] from = new int[n], kind = new int[n];
        java.util.Arrays.fill(from, -2);
        int start = w.level.index(cx, cz, cf);
        from[start] = -1;
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        queue.add(start);
        int goal = w.level.index(w.cellX, w.cellZ, Math.max(0, w.cellF));
        int best = start;
        float bestD = dist(w, cx, cz, cf);
        int[] out = new int[4];
        while (!queue.isEmpty()) {
            int s = queue.poll();
            if (s == goal) {
                best = s;
                break;
            }
            int f = s / plane, z = s % plane / w.cols, x = s % plane % w.cols;
            float d = dist(w, x, z, f);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
            for (int k = 0; k < 13; k++) {
                if (!move(w, x, z, f, k, out)) continue;
                int t = w.level.index(out[0], out[1], out[2]);
                if (from[t] != -2) continue;
                from[t] = s;
                kind[t] = out[3];
                queue.add(t);
            }
        }
        if (best == start) return null;
        int s = best;
        while (from[s] != start) s = from[s];
        int f = s / plane, z = s % plane / w.cols, x = s % plane % w.cols;
        return new int[] {x, z, f, kind[s]};
    }

    private static float dist(World w, int x, int z, int f) {
        float dx = x - w.cellX, dz = z - w.cellZ, df = (f - w.cellF) * 1.5f;
        return dx * dx + dz * dz + df * df;
    }

    /**
     * Move k from (x, z, f): eight hops, four leaps; from a spring only its throw (k == 12).
     * Writes the landing and the kind of hop; false if the move is not one it would make.
     */
    private static boolean move(World w, int x, int z, int f, int k, int[] out) {
        boolean spring = w.typeAt(x, z, f) == Level.SPRING;
        int dx, dz, most = f, kind;
        if (spring) {
            if (k != 12) return false;
            int aim = w.level.aim[w.level.index(x, z, f)];
            dx = DX[aim] * 2;
            dz = DZ[aim] * 2;
            most = f + 1;
            kind = World.THROW;
            if (w.ceiling(x, z, f) || w.ceiling(x + DX[aim], z + DZ[aim], f)) return false;
        } else if (k < 8) {
            dx = k < 4 ? DX[k] : DX[k - 4] + DX[(k - 3) % 4];
            dz = k < 4 ? DZ[k] : DZ[k - 4] + DZ[(k - 3) % 4];
            kind = World.HOP;
        } else if (k < 12) {
            dx = DX[k - 8] * 2;
            dz = DZ[k - 8] * 2;
            kind = World.LEAP;
            int mx = x + DX[k - 8], mz = z + DZ[k - 8];
            if (w.ceiling(x, z, f) || w.ceiling(mx, mz, f) || w.ceiling(x + dx, z + dz, f)) return false;
            int under = w.landing(mx, mz, f);
            if (under >= 0 && w.typeAt(mx, mz, under) == Level.HOT) return false;
        } else {
            return false;
        }
        int tx = x + dx, tz = z + dz, tf = w.landing(tx, tz, most);
        if (tf < 0) return false;
        int t = w.typeAt(tx, tz, tf);
        if (t == Level.GLASS || t == Level.HOT || t == Level.GATE) return false;
        out[0] = tx;
        out[1] = tz;
        out[2] = tf;
        out[3] = kind;
        return true;
    }

    /**
     * The watcher's play against it, worked out at each landing of the drop: up onto a ledge
     * over the ground, wait there, and come down on it once it stands below in reach. Returns
     * the next move as cells across and along; 0, 0 bounces on the spot.
     */
    static int[] watch(World w) {
        int x = w.cellX, z = w.cellZ, f = w.cellF;
        Boss b = w.boss;
        if (f < 0) return new int[] {0, 0};
        if (f >= 1 && b != null && !b.away && !b.falling) {
            for (int[] d : downs(w, x, z, f))
                if (b.cx == x + d[0] && b.cz == z + d[1] && b.cf == d[2] && (b.staring || b.tx == b.cx && b.tz == b.cz))
                    return new int[] {d[0], d[1]};
        }
        if (f >= 1 && b != null && !b.away && !b.falling) {
            // it is coming up, by a spring or already on this floor: get down, away from it
            boolean climbing = w.typeAt(b.cx, b.cz, b.cf) == Level.SPRING || b.tf > b.cf || b.cf == f;
            if (climbing) {
                int[] best = null;
                float far = -1f;
                for (int[] d : downs(w, x, z, f)) {
                    float dx = x + d[0] - b.cx, dz = z + d[1] - b.cz, gap = dx * dx + dz * dz;
                    if (gap > far && gap > 2.1f) {
                        far = gap;
                        best = d;
                    }
                }
                if (best != null) return new int[] {best[0], best[1]};
            }
        }
        if (f >= 1 && !downs(w, x, z, f).isEmpty()) return new int[] {0, 0};
        // the way to the nearest ledge
        int plane = w.cols * w.rows, n = plane * w.floors;
        int[] from = new int[n];
        java.util.Arrays.fill(from, -2);
        int start = w.level.index(x, z, f);
        from[start] = -1;
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        queue.add(start);
        int[] out = new int[4];
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int sf = s / plane, sz = s % plane / w.cols, sx = s % plane % w.cols;
            if (s != start && sf >= 1 && !downs(w, sx, sz, sf).isEmpty()) {
                int c = s;
                while (from[c] != start) c = from[c];
                return new int[] {c % plane % w.cols - x, c % plane / w.cols - z};
            }
            for (int k = 0; k < 13; k++) {
                if (!move(w, sx, sz, sf, k, out)) continue;
                int q = w.level.index(out[0], out[1], out[2]);
                if (from[q] != -2) continue;
                from[q] = s;
                queue.add(q);
            }
        }
        return new int[] {0, 0};
    }

    /** Ways down from a ledge: hops and leaps that land on a lower floor, on stone or a spring. */
    private static java.util.ArrayList<int[]> downs(World w, int x, int z, int f) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<int[]>();
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                if (dx == 0 && dz == 0) continue;
                boolean leap = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (leap && dx != 0 && dz != 0) continue;
                if (leap && (w.ceiling(x, z, f) || w.ceiling(x + dx / 2, z + dz / 2, f) || w.ceiling(x + dx, z + dz, f))) continue;
                int lf = w.landing(x + dx, z + dz, f);
                if (lf < 0 || lf >= f) continue;
                int t = w.typeAt(x + dx, z + dz, lf);
                if (t == Level.STONE || t == Level.SPRING) out.add(new int[] {dx, dz, lf});
            }
        return out;
    }

    /**
     * Whether the drop, from (x, z, f), can get onto a floor above the ground and from there
     * hop or leap down onto a plain island below, one the black drop can stand on.
     */
    static boolean canComeDown(World w, int x0, int z0, int f0) {
        int plane = w.cols * w.rows, n = plane * w.floors;
        boolean[] seen = new boolean[n];
        int start = w.level.index(x0, z0, f0);
        seen[start] = true;
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        queue.add(start);
        int[] out = new int[4];
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int f = s / plane, z = s % plane / w.cols, x = s % plane % w.cols;
            for (int k = 0; k < 13; k++) {
                if (!move(w, x, z, f, k, out)) continue;
                if (f >= 1 && k < 12 && out[2] < f && w.typeAt(out[0], out[1], out[2]) == Level.STONE) return true;
                int q = w.level.index(out[0], out[1], out[2]);
                if (seen[q]) continue;
                seen[q] = true;
                queue.add(q);
            }
        }
        return false;
    }

    /** Whether from (x, z, f) its hops can bring it to the drop's island. */
    private static boolean reaches(World w, int x0, int z0, int f0) {
        int plane = w.cols * w.rows, n = plane * w.floors;
        boolean[] seen = new boolean[n];
        int start = w.level.index(x0, z0, f0), goal = w.level.index(w.cellX, w.cellZ, Math.max(0, w.cellF));
        seen[start] = true;
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        queue.add(start);
        int[] out = new int[4];
        while (!queue.isEmpty()) {
            int s = queue.poll();
            if (s == goal) return true;
            int f = s / plane, z = s % plane / w.cols, x = s % plane % w.cols;
            for (int k = 0; k < 13; k++) {
                if (!move(w, x, z, f, k, out)) continue;
                int q = w.level.index(out[0], out[1], out[2]);
                if (seen[q]) continue;
                seen[q] = true;
                queue.add(q);
            }
        }
        return false;
    }

    /**
     * Where it drops in: a plain island of the ground floor as far from the drop as can be, so
     * the fight starts with the board between them; above the ground only if there is none.
     */
    private int[] farthest(World w) {
        int best = -1;
        float bestScore = -1f;
        for (int f = 0; f < w.floors && best < 0; f++)
            for (int z = 0; z < w.rows; z++)
                for (int x = 0; x < w.cols; x++) {
                    if (w.typeAt(x, z, f) != Level.STONE || w.landing(x, z, w.floors - 1) != f) continue;
                    float dx = x - w.cellX, dz = z - w.cellZ;
                    // from there it must be able to get to the drop, or the fight would never come
                    float score = dx * dx + dz * dz + rnd.nextFloat() * 2f + (reaches(w, x, z, f) ? 1000f : 0f);
                    if (score > bestScore) {
                        bestScore = score;
                        best = w.level.index(x, z, f);
                    }
                }
        int plane = w.cols * w.rows;
        if (best < 0) return new int[] {w.cellX, w.cellZ, Math.max(0, w.cellF)};
        return new int[] {best % plane % w.cols, best % plane / w.cols, best / plane};
    }
}
