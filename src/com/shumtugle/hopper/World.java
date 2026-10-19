package com.shumtugle.hopper;

/**
 * Everything that happens, in plain Java so a desktop bench can run it.
 *
 * The drop never stops bouncing. A hop always starts the moment the drop lands, so the only
 * choice is what the next hop will be: on the spot, one cell ahead, or a leap of two. Turning
 * costs nothing and happens at once; it only changes where "ahead" is. A spring ignores all
 * that: it throws the drop its own way, and turns the drop to face it.
 *
 * Cell (x, z) of floor f has its top at world (x, f * FLOOR, z); the liquid lies a little below
 * the ground floor. Row 0 is the far side, so "north" is -z. A hop lands on the highest island
 * in the target column that is no higher than the drop's own floor; a spring's throw may land
 * one floor higher. With nothing below, the drop falls into the liquid.
 *
 * A new level begins in total black, as if the phone were dead. A tap strikes the nothing,
 * a ring goes out over the liquid, and the level rises out of it; only then the drop falls in.
 * A level tried again after a fall rises at once, without waiting for the tap.
 */
final class World {
    // states
    static final int FALL_IN = 0, PLAY = 1, DROWN = 2, BURN = 3, WIN = 4, DARK = 5, RISE = 6;

    // events for sound and touch feedback
    static final int EV_LAND = 1, EV_LAND_GLASS = 2, EV_LAND_SPRING = 3, EV_SHARD = 4, EV_BEAD = 5,
            EV_COOL = 6, EV_OPEN = 7, EV_WIN = 8, EV_DROWN = 9, EV_BURN = 10, EV_SINK = 11, EV_TURN = 12,
            EV_QUEUE = 13, EV_LEAP = 14, EV_BONK = 15, EV_WAKE = 16;

    // kinds of hop
    static final int STILL = 0, HOP = 1, LEAP = 2, THROW = 3;

    static final float LIQUID_Y = -0.32f;
    static final float EYE = 0.17f;
    /** The eye sits a little behind and above the drop, so the cells to either side are in view. */
    static final float BACK = 0.32f, LIFT = 0.14f;
    /** Horizontal view angle in degrees; the vertical one follows the shape of the screen. */
    static final float FOV_ACROSS = 64f;

    private static final float HOP_TIME = 0.72f, LEAP_TIME = 1.0f, SPOT_TIME = 0.62f, THROW_TIME = 1.2f;
    private static final float HOP_HIGH = 0.62f, LEAP_HIGH = 1.15f, SPOT_HIGH = 0.48f;
    private static final float FALL_TIME = 1.3f, FALL_FROM = 6f;
    private static final int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};

    final int index;
    final Level level;
    final int cols, rows, floors;
    final int[] type;
    final boolean[] shard, bead;
    /** How far each island has gone under, 0 to 1; a sunk island holds nothing. */
    final float[] sink;
    /** Short squash of an island just landed on, 1 down to 0. */
    final float[] dent;
    /** Bumped whenever islands change for good, so the renderer rebuilds its still geometry. */
    int version;

    int state = FALL_IN;
    float stateAge;
    /** How far the level has risen out of the liquid, 0 to 1. */
    float rise = 1f;
    /** Seconds since the level woke and began to rise. */
    float awake;
    private static final float RISE_TIME = 2.0f;
    /** Seconds since the level began. */
    float time;

    /** The cell the drop last stood on, and the one it is heading for; floor -1 is the liquid. */
    int cellX, cellZ, cellF, toX, toZ, toF;
    int facing;
    /** Heading in radians as seen, easing towards facing. */
    float yaw;
    /** Steps asked for the next hop: 0 on the spot, 1 one cell, 2 a leap. */
    int queued;
    /** For a one-cell hop: -1 slants it to the left, +1 to the right, 0 keeps it straight. */
    int slant;
    /** Seconds since the map was last called up; it shows for a moment and fades. */
    float mapAge = -1.5f;
    private final int[] move = new int[3];

    // the hop in progress
    float hopAge, hopTime, hopHigh, fromY, toY;
    boolean leap;

    int beads;
    int shardsLeft, shardsTotal;
    boolean open;

    /** Where the drop is: the bottom of the drop, not the eye. */
    float x, y, z;
    /** The point the eye looks at, eased so the view never jumps. */
    private final float[] look = new float[3], aim = new float[3];
    private boolean looked;

    final Liquid liquid;
    /** Liquid grid origin and size in world units. */
    final float gridX0, gridZ0, gridSize;

    private final int[] events = new int[32];
    private int eventCount;

    World(int index, boolean dark) {
        this.index = index;
        level = Level.get(index);
        cols = level.cols;
        rows = level.rows;
        floors = level.floors;
        type = level.type.clone();
        shard = level.shard.clone();
        bead = level.bead.clone();
        sink = new float[type.length];
        dent = new float[type.length];
        shardsLeft = shardsTotal = level.shards();
        open = shardsTotal == 0;
        cellX = toX = level.startX;
        cellZ = toZ = level.startZ;
        cellF = toF = level.startF;
        x = cellX;
        z = cellZ;
        y = top(cellF) + FALL_FROM;
        toY = top(cellF);
        hopTime = FALL_TIME;
        float span = Math.max(cols, rows) + 8f;
        gridSize = span;
        gridX0 = (cols - 1) * 0.5f - span * 0.5f;
        gridZ0 = (rows - 1) * 0.5f - span * 0.5f;
        liquid = new Liquid(112, 112);
        state = dark ? DARK : RISE;
        rise = 0f;
    }

    // ---- input, from the render thread ----

    /** How far below its place an island on floor f still is, while the level rises. */
    float under(int f) {
        return (1f - rise) * (3f + top(f));
    }

    /** A swipe up: one more step for the next hop, at most two. */
    void forward() {
        if (state != PLAY && state != FALL_IN) return;
        if (slant != 0) {
            // a straight swipe after a slanted one straightens the hop
            slant = 0;
            queued = 1;
            emit(EV_QUEUE);
        } else if (queued < 2) {
            queued++;
            emit(EV_QUEUE);
        }
    }

    /** A swipe up and to one side: one cell ahead and one across, -1 left or +1 right. No leap slants. */
    void slanted(int side) {
        if (state != PLAY && state != FALL_IN) return;
        queued = 1;
        slant = side;
        emit(EV_QUEUE);
    }

    /** A sideways swipe: turn by a quarter, -1 left or +1 right; 2 turns round. */
    void turn(int quarters) {
        if (state != PLAY && state != FALL_IN) return;
        facing = (facing + quarters + 4) % 4;
        mapAge = 0f;
        emit(EV_TURN);
    }

    /** A tap: forget the steps asked for, and look at the map; in the dark, it wakes the level. */
    void hold() {
        if (state == DARK) {
            state = RISE;
            stateAge = 0f;
            liquid.press(0.5f, 0.5f, 3f, 2.2f);
            emit(EV_WAKE);
            return;
        }
        mapAge = 0f;
        queued = 0;
        slant = 0;
    }

    // ---- the clock ----

    void update(float dt) {
        time += dt;
        stateAge += dt;
        if (state == DARK) {
            mapAge = -1.5f;
            return;
        }
        awake += dt;
        mapAge += dt;
        if (state == RISE) {
            rise = smooth(0.15f, RISE_TIME, stateAge);
            version++;
            mapAge = -1.5f;
            if (stateAge >= RISE_TIME) {
                rise = 1f;
                state = FALL_IN;
                stateAge = 0f;
                hopAge = 0f;
            }
        }
        for (int i = 0; i < sink.length; i++) {
            if (dent[i] > 0f) dent[i] = Math.max(0f, dent[i] - dt * 4f);
            if (sink[i] > 0f && sink[i] < 1f) {
                sink[i] = Math.min(1f, sink[i] + dt * 0.9f);
                version++;
                if (sink[i] > 0.35f && sink[i] - dt * 0.9f <= 0.35f) {
                    int c = i % (cols * rows);
                    splash(c % cols, c / cols, 1.6f, 0.9f);
                }
            }
        }
        // the heading eases round; a full turn is never taken the long way
        float want = facing * (float) (Math.PI / 2);
        float d = want - yaw;
        while (d > Math.PI) d -= (float) (2 * Math.PI);
        while (d < -Math.PI) d += (float) (2 * Math.PI);
        yaw += d * Math.min(1f, dt * 13f);

        switch (state) {
            case RISE:
                break;
            case FALL_IN:
            case PLAY:
                hopAge += dt;
                float t = Math.min(1f, hopAge / hopTime);
                if (state == FALL_IN) {
                    y = top(cellF) + FALL_FROM * (1f - t * t);
                } else {
                    // heat rises: a leap over a hot plate burns as surely as landing on it
                    if (leap && t >= 0.5f && hopAge - dt < hopTime * 0.5f && heatBelow((cellX + toX) / 2, (cellZ + toZ) / 2)) {
                        state = BURN;
                        stateAge = 0f;
                        emit(EV_BURN);
                        break;
                    }
                    x = cellX + (toX - cellX) * t;
                    z = cellZ + (toZ - cellZ) * t;
                    y = fromY + (toY - fromY) * t + 4f * hopHigh * t * (1f - t);
                }
                if (t >= 1f) land();
                break;
            case DROWN:
                y -= dt * 0.9f;
                break;
            case BURN:
                break;
            case WIN:
                y += dt * (1.5f + stateAge * 3f);
                break;
        }
        aim(aim);
        if (!looked) {
            System.arraycopy(aim, 0, look, 0, 3);
            looked = true;
        }
        float k = Math.min(1f, dt * 7f);
        for (int j = 0; j < 3; j++) look[j] += (aim[j] - look[j]) * k;

        // a slow rain of single drops keeps the black surface alive
        if (((int) (time * 0.7f)) != ((int) ((time - dt) * 0.7f))) {
            double a = time * 12.9898;
            float u = (float) (Math.sin(a) * 43758.5453 % 1.0);
            float v = (float) (Math.sin(a * 1.7) * 23421.631 % 1.0);
            liquid.press(Math.abs(u), Math.abs(v), 1.2f, 0.35f);
        }
    }

    /** True once the state has played out and the level should restart or move on. */
    boolean finished() {
        if (state == DROWN) return stateAge > 1.6f;
        if (state == BURN) return stateAge > 1.4f;
        if (state == WIN) return stateAge > 3.1f;
        return false;
    }

    // ---- what holds the drop ----

    /** What the rules see at a cell: sunk glass and mirages hold nothing, so they read as liquid. */
    int typeAt(int cx, int cz, int f) {
        if (cx < 0 || cz < 0 || cx >= cols || cz >= rows || f < 0 || f >= floors) return Level.LIQUID;
        int i = level.index(cx, cz, f);
        return sink[i] > 0f || type[i] == Level.MIRAGE ? Level.LIQUID : type[i];
    }

    /** The floor the drop lands on in a column, falling from floor most; -1 for the liquid. */
    int landing(int cx, int cz, int most) {
        for (int f = Math.min(most, floors - 1); f >= 0; f--)
            if (typeAt(cx, cz, f) != Level.LIQUID) return f;
        return -1;
    }

    /** True if an island hangs right over floor f in this column. */
    boolean ceiling(int cx, int cz, int f) {
        return typeAt(cx, cz, f + 1) != Level.LIQUID;
    }

    /** True if the island the drop would fall onto in this column is a live hot plate. */
    private boolean heatBelow(int cx, int cz) {
        int f = landing(cx, cz, cellF);
        return f >= 0 && typeAt(cx, cz, f) == Level.HOT;
    }

    /** The height of the top of a floor, in world units; floor -1 is the liquid. */
    static float top(int f) {
        return f < 0 ? LIQUID_Y : f * Level.FLOOR;
    }

    /** How high the drop is over the ground beneath its path; a mirage's reflection bobs as much. */
    float lift() {
        if (state == FALL_IN) return Math.min(1.2f, y - top(cellF));
        if (state != PLAY) return 0f;
        float t = hopPhase();
        return Math.max(0f, y - (fromY + (toY - fromY) * t));
    }

    private void land() {
        cellX = toX;
        cellZ = toZ;
        cellF = toF;
        x = cellX;
        z = cellZ;
        y = top(cellF);
        if (cellF < 0) {
            state = DROWN;
            stateAge = 0f;
            splash(cellX, cellZ, 2.2f, 1.6f);
            emit(EV_DROWN);
            return;
        }
        int i = level.index(cellX, cellZ, cellF);
        int t = typeAt(cellX, cellZ, cellF);
        dent[i] = 1f;
        if (t == Level.HOT) {
            if (beads > 0) {
                beads--;
                type[i] = Level.STONE;
                version++;
                emit(EV_COOL);
            } else {
                state = BURN;
                stateAge = 0f;
                emit(EV_BURN);
                return;
            }
        }
        if (bead[i] && beads == 0) {
            bead[i] = false;
            beads = 1;
            emit(EV_BEAD);
        }
        if (shard[i]) {
            shard[i] = false;
            shardsLeft--;
            emit(EV_SHARD);
            if (shardsLeft == 0 && !open) {
                open = true;
                emit(EV_OPEN);
            }
        }
        if (t == Level.GATE && open) {
            state = WIN;
            stateAge = 0f;
            emit(EV_WIN);
            return;
        }
        if (t == Level.SPRING && facing != level.aim[i]) {
            // the spring turns the drop the way it is about to throw it, so the eye goes along
            facing = level.aim[i];
            mapAge = 0f;
        }
        emit(t == Level.GLASS ? EV_LAND_GLASS : t == Level.SPRING ? EV_LAND_SPRING : EV_LAND);
        state = PLAY;
        takeOff();
    }

    private void takeOff() {
        int t = typeAt(cellX, cellZ, cellF);
        plan(move);
        // a spring's throw leaves the order standing for the landing after it
        if (t != Level.SPRING) {
            queued = 0;
            slant = 0;
        }
        int kind = move[2];
        toX = cellX + move[0];
        toZ = cellZ + move[1];
        if (blocked(kind, toX, toZ)) {
            // a floor overhead: the drop strikes it and comes back down where it was
            toX = cellX;
            toZ = cellZ;
            kind = STILL;
            emit(EV_BONK);
        }
        toF = kind == STILL ? cellF : landing(toX, toZ, kind == THROW ? cellF + 1 : cellF);
        boolean still = kind == STILL;
        leap = kind == LEAP || kind == THROW;
        fromY = top(cellF);
        toY = top(toF);
        hopTime = still ? SPOT_TIME : kind == THROW ? THROW_TIME : leap ? LEAP_TIME : HOP_TIME;
        hopHigh = (still ? SPOT_HIGH : leap ? LEAP_HIGH : HOP_HIGH) + Math.max(0f, toY - fromY);
        hopAge = 0f;
        if (leap) emit(EV_LEAP);
        // glass holds while the drop stays, and goes under as soon as it leaves
        if (!still && t == Level.GLASS) {
            sink[level.index(cellX, cellZ, cellF)] = 0.001f;
            version++;
            emit(EV_SINK);
        }
    }

    /** True if a floor overhead stops this kind of hop: a leap or a throw needs open air above. */
    boolean blocked(int kind, int tx, int tz) {
        if (kind != LEAP && kind != THROW) return false;
        int mx = (cellX + tx) / 2, mz = (cellZ + tz) / 2;
        if (ceiling(cellX, cellZ, cellF) || ceiling(mx, mz, cellF)) return true;
        return kind == LEAP && ceiling(tx, tz, cellF);
    }

    /**
     * The next hop if the drop took off now: cells across, cells along, and its kind.
     * A spring always throws two cells its own way.
     */
    private void plan(int[] out) {
        int t = typeAt(cellX, cellZ, cellF);
        int steps = t == Level.SPRING ? 2 : queued;
        int side = t == Level.SPRING || steps != 1 ? 0 : slant;
        int way = t == Level.SPRING ? level.aim[level.index(cellX, cellZ, cellF)] : facing;
        int right = (way + 1) % 4;
        out[0] = DX[way] * steps + DX[right] * side;
        out[1] = DZ[way] * steps + DZ[right] * side;
        out[2] = t == Level.SPRING ? THROW : steps == 2 ? LEAP : steps == 1 ? HOP : STILL;
    }

    /** The cell the next hop would land on if the drop took off now. */
    int nextX() {
        plan(move);
        return cellX + move[0];
    }

    int nextZ() {
        plan(move);
        return cellZ + move[1];
    }

    /** True if the next hop, as ordered now, would strike a floor overhead. */
    boolean nextBlocked() {
        plan(move);
        return blocked(move[2], cellX + move[0], cellZ + move[1]);
    }

    /** Share of the current hop done, 0 to 1. */
    float hopPhase() {
        return Math.min(1f, hopAge / hopTime);
    }

    private void splash(int cx, int cz, float radius, float depth) {
        float u = (cx - gridX0) / gridSize, v = (cz - gridZ0) / gridSize;
        liquid.press(u, v, radius * liquid.cols / gridSize, depth);
    }

    private void emit(int e) {
        if (eventCount < events.length) events[eventCount++] = e;
    }

    /** Copies out the events since the last call and forgets them. */
    int takeEvents(int[] out) {
        int n = Math.min(eventCount, out.length);
        System.arraycopy(events, 0, out, 0, n);
        eventCount = 0;
        return n;
    }

    // ---- what the camera sees ----

    /** Fills eye[3] and at[3], the point the eye looks at. */
    void camera(float[] eye, float[] at) {
        float fx = (float) Math.sin(yaw), fz = (float) -Math.cos(yaw);
        eye[0] = x - fx * BACK;
        eye[1] = y + EYE + LIFT;
        eye[2] = z - fz * BACK;
        if (!looked) aim(look);
        System.arraycopy(look, 0, at, 0, 3);
    }

    /** Where the eye wants to look right now. */
    private void aim(float[] out) {
        float fx = (float) Math.sin(yaw), fz = (float) -Math.cos(yaw);
        // ahead: a point a few cells out, a little below the eye
        float ground = state == PLAY ? fromY + (toY - fromY) * hopPhase() : top(cellF);
        float ax = x + fx * 4f, ay = ground - 0.55f, az = z + fz * 4f;
        float w = 0f;
        if (state == PLAY) {
            float t = hopPhase();
            // on the way down the eye drops to where the drop will land
            w = smooth(0.30f, 0.78f, t) * (toX == cellX && toZ == cellZ ? 0.35f : 0.8f);
        } else if (state == FALL_IN || state == RISE || state == DARK) {
            w = 0.85f;
        } else if (state == DROWN) {
            w = 0.3f;
        }
        out[0] = ax + (toX - ax) * w;
        out[1] = ay + (toY - ay) * w;
        out[2] = az + (toZ - az) * w;
        if (state == FALL_IN || state == RISE || state == DARK) {
            // falling in, the eye looks down at the start but still leans the way ahead
            out[0] = toX + fx * 0.8f;
            out[1] = top(toF);
            out[2] = toZ + fz * 0.8f;
        }
    }

    /** Vertical view angle in degrees for a screen this many times taller than wide. */
    static float fovUp(float aspect) {
        double across = Math.tan(Math.toRadians(FOV_ACROSS) * 0.5);
        return (float) Math.toDegrees(2.0 * Math.atan(across * aspect));
    }

    static float smooth(float a, float b, float t) {
        float k = Math.max(0f, Math.min(1f, (t - a) / (b - a)));
        return k * k * (3f - 2f * k);
    }
}
