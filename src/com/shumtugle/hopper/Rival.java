package com.shumtugle.hopper;

import java.util.Random;

/**
 * A lilac drop that races the player to the gate, about as well as a person would.
 *
 * It plays the level in a copy of its own, by the same rules and with the same task: every
 * shard, then the gate. Its way is the solver's, but at every landing it may hesitate and bounce
 * on the spot, step aside onto a neighbour and come back, or now and then slip into the liquid.
 * A rival that falls in drops back from above onto the start and runs again. Nothing it does
 * touches the player's level: it is seen, and heard landing nearby, and that is all, until it
 * reaches the gate first.
 *
 * Its moves come from a thread of its own, worked out while the level waits in the dark.
 */
final class Rival {
    /** Chances, at a landing, to bounce on the spot, to step aside and back, to slip into the liquid. */
    static final float HESITATE = 0.45f, STRAY = 0.09f, SLIP = 0.022f;

    private static final int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};
    /** Set by the game: work the way out on a thread. The bench leaves it off and waits. */
    static volatile boolean threads;

    final int index, number;
    /** How fast it plays, a little slower than the world's own beat. */
    final float pace;
    private final Random rnd;
    private volatile int[] way;
    /** Its own copy of the level, or null before it has dropped in. */
    World w;
    /** It has reached the gate; it has all its shards and makes for the gate. */
    boolean won, ready;
    private float wait;
    private final int[] events = new int[32];

    Rival(final int index, int number, boolean async) {
        this.index = index;
        this.number = number;
        pace = 0.8f - 0.05f * number;
        // every try its own: a person does not make the same slips twice in a row
        rnd = new Random(index * 7919L + number * 104729L + 31L * made++);
        // it drops in a moment after the player, the second a moment after the first
        wait = 3.6f + 0.9f * number;
        if (async) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    way = wayFor(index);
                }
            }, "rival");
            t.setDaemon(true);
            t.start();
        } else {
            way = wayFor(index);
        }
    }

    private static int made;
    private static int cachedFor = -1;
    private static int[] cached;

    /** The solver's way through a level, kept for the next try and the second rival. */
    private static synchronized int[] wayFor(int index) {
        if (cachedFor != index) {
            cached = Solver.path(Level.get(index), 600000);
            cachedFor = index;
        }
        return cached;
    }

    /** Plays on while the player's level is under way; main is the player's world. */
    void step(float dt, World main) {
        if (won) return;
        if (w == null) {
            if (way == null) return;
            wait -= dt;
            if (wait > 0f) return;
            begin();
        }
        w.update(dt * pace);
        int n = w.takeEvents(events);
        for (int i = 0; i < n; i++) {
            int e = events[i];
            if (e == World.EV_LAND || e == World.EV_LAND_GLASS || e == World.EV_LAND_SPRING) main.rivalLanded(w.x, w.y, w.z);
            else if (e == World.EV_OPEN) {
                ready = true;
                main.rivalReady();
            }
        }
        if (w.state == World.WIN) {
            won = true;
            return;
        }
        // fallen in or burnt: back from above to the start, to run again
        if (w.finished()) begin();
    }

    private void begin() {
        w = new World(index, false, true);
        w.dropIn();
        w.brain = this;
        w.fly(way.clone());
        ready = false;
    }

    /** Called by its own world at every landing, before the next hop is ordered. */
    void decide(World rw) {
        int[] p = rw.pilot;
        if (p == null || rw.pilotAt * 2 + 1 >= p.length) return;
        int here = rw.typeAt(rw.cellX, rw.cellZ, rw.cellF);
        // a spring throws by itself, and its move must stay where it is in the list
        if (here == Level.SPRING) return;
        float r = rnd.nextFloat();
        if (r < HESITATE) {
            insert(rw, 0, 0);
            return;
        }
        r -= HESITATE;
        if (r < STRAY) {
            // only from a plain island: glass would sink behind it, a hot plate is no place to dither
            if (here != Level.STONE) return;
            // aside onto a plain island and straight back: a step a person takes to look round
            int start = rnd.nextInt(4);
            for (int k = 0; k < 4; k++) {
                int f = (start + k) % 4, x = rw.cellX + DX[f], z = rw.cellZ + DZ[f];
                if (rw.landing(x, z, rw.cellF) != rw.cellF || rw.typeAt(x, z, rw.cellF) != Level.STONE) continue;
                insert(rw, -DX[f], -DZ[f]);
                insert(rw, DX[f], DZ[f]);
                return;
            }
            return;
        }
        r -= STRAY;
        if (r < SLIP) {
            // a slip: off the edge, into the liquid
            int start = rnd.nextInt(4);
            for (int k = 0; k < 4; k++) {
                int f = (start + k) % 4, x = rw.cellX + DX[f], z = rw.cellZ + DZ[f];
                if (rw.landing(x, z, rw.cellF) >= 0) continue;
                insert(rw, DX[f], DZ[f]);
                return;
            }
        }
    }

    /** Puts one move in front of the rest of the way. */
    private static void insert(World rw, int dx, int dz) {
        int[] p = rw.pilot, q = new int[p.length + 2];
        int at = rw.pilotAt * 2;
        System.arraycopy(p, 0, q, 0, at);
        q[at] = dx;
        q[at + 1] = dz;
        System.arraycopy(p, at, q, at + 2, p.length - at);
        rw.pilot = q;
    }

    /** Whether the drop can be seen: dropping in, bouncing, or going under. */
    boolean shown() {
        return w != null && !won && (w.state == World.FALL_IN || w.state == World.PLAY
                || w.state == World.DROWN && w.stateAge < 1f);
    }
}
