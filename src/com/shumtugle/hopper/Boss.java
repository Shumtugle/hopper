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
    private static final float HESITATE = 0.22f, LOOK_UP = 0.4f, STARE = 0.8f, PACE = 0.9f;
    private static final float HOP_TIME = 0.72f, LEAP_TIME = 1.0f, SPOT_TIME = 0.62f, THROW_TIME = 1.2f;
    private static final float HOP_HIGH = 0.62f, LEAP_HIGH = 1.15f, SPOT_HIGH = 0.48f, FALL_FROM = 6f, FALL_TIME = 1.3f;
    private static final int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};

    /** The island it stands on, or last stood on. */
    int cx, cz, cf;
    /** Where the hop in progress lands. */
    int tx, tz, tf;
    float x, y, z;
    private float age, time = FALL_TIME, high, fromY, toY;
    /** Falling in from above; out of play while the world is cut. */
    boolean falling = true, away;
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
        x = cx;
        z = cz;
        fromY = World.top(cf) + FALL_FROM;
        toY = World.top(cf);
        y = fromY;
        age = 0f;
        time = FALL_TIME;
        high = 0f;
        falling = true;
        away = false;
    }

    /** Plays one step of time; tells the world when it lands. */
    void step(World w, float dt) {
        if (away) return;
        age += dt * PACE;
        float t = Math.min(1f, age / time);
        if (falling) {
            y = toY + (fromY - toY) * (1f - t * t);
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
        return Math.abs(x - x0) < 0.55f && Math.abs(z - z0) < 0.55f && y - World.top(f0) < 0.75f && y >= World.top(f0) - 0.05f;
    }

    private void takeOff(World w) {
        // with the drop up above it, it stops and looks up more often, as if working out the way;
        // right under the drop it all but stands and stares: that is the moment to come down on it
        boolean under = w.cellF > cf && Math.abs(w.cellX - cx) <= 2 && Math.abs(w.cellZ - cz) <= 2;
        float doubt = under ? STARE : w.cellF > cf ? LOOK_UP : HESITATE;
        int[] m = rnd.nextFloat() < doubt ? null : next(w);
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
