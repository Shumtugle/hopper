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
 * A new level begins in the black, as if the phone were dead; after a win, the world stands
 * in it, and a tap sends it under first. A tap strikes the nothing, a ring goes out over the
 * liquid, and the level rises out of it; only then the drop falls in.
 * A level tried again after a fall rises at once, without waiting for the tap.
 *
 * A shifting level comes in acts. Each act lasts a number of landings; three before its end the
 * ground trembles, and then everything but the island under the drop goes down into the liquid
 * and the next act rises in its place, grown from that island. The drop bounces on the spot the
 * while. Shards left behind move on to the next act; only the last one has the gate.
 *
 * In a level with an order the shards hum when the drop is near, each at its own note and its
 * own beat, the lowest slowest. A shard taken out of turn turns its island into a funnel; the
 * drop goes down it, and the stage pours it into a level already played.
 *
 * Over a mirage stands a mirror, always turned to the drop, that shows it through a shimmer.
 * Near one, the island ahead answers each landing with a soft note; a mirage does not.
 *
 * After the plateau some levels bring rivals: lilac drops, each playing its own copy of the
 * level, racing the drop to the gate. One that gets there first loses the level for the drop.
 *
 * The watcher can play a level instead: it is handed the solver's moves and, at each landing,
 * orders the next hop exactly as a finger would (a turn, a step, a leap, a slant). A swipe takes
 * the drop back from it.
 */
final class World {
    // states
    static final int FALL_IN = 0, PLAY = 1, DROWN = 2, BURN = 3, WIN = 4, DARK = 5, RISE = 6, FUNNEL = 7,
            BEATEN = 8;

    // events for sound and touch feedback
    static final int EV_LAND = 1, EV_LAND_GLASS = 2, EV_LAND_SPRING = 3, EV_SHARD = 4, EV_BEAD = 5,
            EV_COOL = 6, EV_OPEN = 7, EV_WIN = 8, EV_DROWN = 9, EV_BURN = 10, EV_SINK = 11, EV_TURN = 12,
            EV_QUEUE = 13, EV_LEAP = 14, EV_BONK = 15, EV_WAKE = 16, EV_TREMOR = 17, EV_SHIFT = 18,
            EV_RISEN = 19, EV_FUNNEL = 20, EV_ECHO = 21, EV_BEATEN = 22, EV_RIVAL_READY = 23;
    /** A shard humming: EV_HUM + rank * 16 + loudness, 1 to 15. */
    static final int EV_HUM = 1000;
    /** A rival landing near the drop: EV_RIVAL + loudness, 1 to 15. */
    static final int EV_RIVAL = 2000;

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
    /** The board in play: the level, or in a shifting level the act now standing. */
    Level level;
    final int cols, rows, floors;
    int[] type;
    boolean[] shard, bead;
    /** How far each island has gone under, 0 to 1; a sunk island holds nothing. */
    float[] sink;
    /** Short squash of an island just landed on, 1 down to 0. */
    float[] dent;

    // a shifting level: which act stands, its landings so far and how many it allows
    int act, acts = 1;
    private int actHops, budget = Integer.MAX_VALUE, tremorFrom = -1, actLeft;
    /** How hard the ground trembles before a shift, 0 to 1. */
    float tremor;
    /** 0 while the ground holds; 1 while the old act goes down; 2 while the new one comes up. */
    int shift;
    private float shiftAge;
    /** How far the eye has left the drop to watch a shift from above, 0 to 1. */
    float lift;
    /** The shift in oxide film the current act wears, eased; every act has its own colour. */
    float film;
    /** Seconds since the last act rose; the stage shows the act's number for a while after. */
    float actAge = 99f;
    /** The island that has turned into a funnel, or -1. */
    int funnel = -1;
    /** When each shard last hummed, in seconds of play. */
    private float[] hummed;
    /** The island the drop stands on through a shift; it alone stays up. */
    int keep = -1;
    /** Set by the stage: grow the next act on a thread of its own instead of in the frame. */
    boolean async;
    private volatile Level nextAct;
    private volatile int[] nextWay;
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
    /** The watcher's moves, dx and dz for each hop in turn, or null while a finger plays. */
    int[] pilot;
    int pilotAt;
    /** The lilac drops racing the drop to the gate, or null; none in a rival's own copy. */
    final Rival[] rivals;
    /** In a rival's own copy of the level: the rival, asked at every landing what to do. */
    Rival brain;
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
        this(index, dark, false);
    }

    /** A level; ghost is a rival's own copy of it, which has no rivals of its own. */
    World(int index, boolean dark, boolean ghost) {
        this.index = index;
        int racers = ghost || index < Level.drawn() ? 0 : Maze.rivals(index);
        rivals = racers == 0 ? null : new Rival[racers];
        for (int k = 0; k < racers; k++) rivals[k] = new Rival(index, k, Rival.threads);
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
        actLeft = shardsLeft;
        if (index >= Level.drawn() && Maze.staged(index)) {
            acts = Maze.acts(index);
            shardsTotal = 0;
            for (int k : Maze.actShards(index)) shardsTotal += k;
            shardsLeft = shardsTotal;
            budget = Math.max(4, level.hops) + 4;
        }
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

    /** How far below its place island i, on floor f, still is while the level rises or shifts. */
    float under(int f, int i) {
        if (i == keep) return 0f;
        return (1f - rise) * (3f + top(f));
    }

    /** How far below its place something on floor f is, standing on no island in particular. */
    float under(int f) {
        return under(f, -2);
    }

    /** Starts the drop falling in from above at once, without the level's rise. */
    void dropIn() {
        state = FALL_IN;
        stateAge = 0f;
        rise = 1f;
        hopAge = 0f;
    }

    /** A rival has landed; heard if it is near the drop. */
    void rivalLanded(float rx, float ry, float rz) {
        float dx = rx - x, dz = rz - z, dy = (ry - y) / Level.FLOOR;
        float d = (float) Math.sqrt(dx * dx + dz * dz + dy * dy);
        if (d > 4f) return;
        emit(EV_RIVAL + Math.max(1, Math.min(15, Math.round(15f * (1f - d / 4f)))));
    }

    /** A rival has all its shards and makes for the gate. */
    void rivalReady() {
        emit(EV_RIVAL_READY);
    }

    /** Hands the drop to the watcher, which will play these moves from the first landing on. */
    void fly(int[] moves) {
        pilot = moves;
        pilotAt = 0;
    }

    /** A swipe up: one more step for the next hop, at most two. */
    void forward() {
        pilot = null;
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
        pilot = null;
        if (state != PLAY && state != FALL_IN) return;
        queued = 1;
        slant = side;
        emit(EV_QUEUE);
    }

    /** A sideways swipe: turn by a quarter, -1 left or +1 right; 2 turns round. */
    void turn(int quarters) {
        pilot = null;
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
        if (pilot != null) return;
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
        if (shift == 1) {
            // the eye goes up to watch; the old act goes down; once the next one is grown, it comes up
            shiftAge += dt;
            lift = smooth(0f, 0.8f, shiftAge);
            rise = 1f - smooth(0.4f, 1.4f, shiftAge);
            version++;
            if (shiftAge >= 1.5f && nextAct != null) swapAct();
        } else if (shift == 2) {
            shiftAge += dt;
            rise = smooth(0.1f, 1.7f, shiftAge);
            lift = 1f - smooth(2.1f, 2.9f, shiftAge);
            version++;
            if (shiftAge >= 2.9f) {
                rise = 1f;
                lift = 0f;
                shift = 0;
                keep = -1;
                mapAge = 0f;
                actAge = 0f;
                emit(EV_RISEN);
            }
        }
        actAge += dt;
        // the new act's colour seeps in as it rises
        float tint = actFilm(act);
        film += (tint - film) * Math.min(1f, dt * 1.6f);
        if (tremorFrom >= 0 && shift == 0) {
            float t = (actHops - tremorFrom + (state == PLAY ? hopPhase() : 0f)) / 3f;
            tremor = Math.min(1f, Math.max(0f, t)) * 0.6f + 0.4f;
            version++;
            // the black surface shivers round the islands
            if (((int) (time * 9f)) != ((int) ((time - dt) * 9f))) {
                double a = time * 7.123;
                float u = (float) Math.abs(Math.sin(a) * 43758.5453 % 1.0);
                float v = (float) Math.abs(Math.sin(a * 1.3) * 24634.6345 % 1.0);
                liquid.press(0.3f + u * 0.4f, 0.3f + v * 0.4f, 1f, 0.25f * tremor);
            }
        } else if (shift == 0) {
            tremor = 0f;
        }
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
            case FUNNEL:
                // the island narrows into a funnel and the drop goes down through it
                y -= dt * (0.15f + stateAge * 0.9f);
                version++;
                break;
        }
        aim(aim);
        if (!looked) {
            System.arraycopy(aim, 0, look, 0, 3);
            looked = true;
        }
        float k = Math.min(1f, dt * 7f);
        for (int j = 0; j < 3; j++) look[j] += (aim[j] - look[j]) * k;

        if (level.order != null && (state == PLAY || state == FALL_IN)) hum();

        if (rivals != null && (state == RISE || state == FALL_IN || state == PLAY)) {
            for (Rival r : rivals) {
                r.step(dt, this);
                if (r.won && (state == PLAY || state == FALL_IN)) {
                    // a rival has reached the gate first: the level is lost
                    state = BEATEN;
                    stateAge = 0f;
                    pilot = null;
                    emit(EV_BEATEN);
                }
            }
        }

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
        if (state == BEATEN) return stateAge > 1.8f;
        if (state == FUNNEL) return stateAge > 2.2f;
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
        if (shard[i] && level.order != null && level.order[i] != shardsTotal - shardsLeft) {
            // out of turn: the island becomes a funnel
            state = FUNNEL;
            stateAge = 0f;
            funnel = i;
            pilot = null;
            emit(EV_FUNNEL);
            return;
        }
        if (shard[i]) {
            shard[i] = false;
            shardsLeft--;
            actLeft--;
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
        echo();
        state = PLAY;
        countLanding();
        takeOff();
    }

    private void takeOff() {
        int t = typeAt(cellX, cellZ, cellF);
        if (shift != 0) {
            // through a shift the drop bounces on the spot, and keeps what it was asked for
            toX = cellX;
            toZ = cellZ;
            toF = cellF;
            leap = false;
            fromY = toY = top(cellF);
            hopTime = SPOT_TIME;
            hopHigh = SPOT_HIGH;
            hopAge = 0f;
            return;
        }
        if (brain != null) brain.decide(this);
        if (pilot != null) order(t);
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

    /**
     * In a shifting level, every landing counts against the act. The ground starts to tremble
     * three landings before the act runs out, or at once when its shards are all taken; three
     * landings after that, it shifts.
     */
    private void countLanding() {
        if (acts < 2 || act >= acts - 1 || shift != 0) return;
        actHops++;
        if (tremorFrom < 0 && (actLeft <= 0 || actHops >= budget - 3)) tremorFrom = actHops;
        if (tremorFrom < 0) return;
        if (actHops >= tremorFrom + 3) {
            shift = 1;
            shiftAge = 0f;
            keep = level.index(cellX, cellZ, cellF);
            tremorFrom = -1;
            splash(cellX, cellZ, 3f, 2f);
            emit(EV_SHIFT);
            growNextAct();
        } else {
            emit(EV_TREMOR);
        }
    }

    /** Grows the next act from the drop's island, on a thread when the stage asks for it. */
    private void growNextAct() {
        nextAct = null;
        nextWay = null;
        final int k = act + 1, x0 = cellX, z0 = cellZ, f0 = cellF, carried = Math.max(0, actLeft);
        final boolean watched = pilot != null;
        Runnable job = new Runnable() {
            @Override
            public void run() {
                Level l = Maze.act(index, k, x0, z0, f0, carried, cols, rows, floors);
                if (watched) nextWay = Solver.path(l, 600000);
                nextAct = l;
            }
        };
        if (async) {
            Thread t = new Thread(job, "act");
            t.setDaemon(true);
            t.start();
        } else {
            job.run();
        }
    }

    /**
     * Near a mirage, the island ahead answers every landing. A mirage keeps silent, as the
     * liquid does: it holds nothing, and now it sounds so.
     */
    private void echo() {
        int ax = cellX + DX[facing], az = cellZ + DZ[facing];
        if (typeAt(ax, az, cellF) == Level.LIQUID) return;
        for (int f = Math.max(0, cellF - 1); f <= Math.min(floors - 1, cellF + 1); f++)
            for (int cz = Math.max(0, cellZ - 2); cz <= Math.min(rows - 1, cellZ + 2); cz++)
                for (int cx = Math.max(0, cellX - 2); cx <= Math.min(cols - 1, cellX + 2); cx++) {
                    int i = level.index(cx, cz, f);
                    if (type[i] == Level.MIRAGE && sink[i] <= 0f) {
                        emit(EV_ECHO);
                        return;
                    }
                }
    }

    /** How long between hums of the shard of this rank: the lowest note slowest. */
    static float humPeriod(int rank) {
        return Math.max(0.45f, 1.5f - rank * 0.3f);
    }

    /** Shards near the drop hum, each at its beat; the nearer, the louder. */
    private void hum() {
        if (hummed == null) hummed = new float[type.length];
        for (int i = 0; i < type.length; i++) {
            if (!shard[i] || level.order[i] < 0) continue;
            int plane = cols * rows, f = i / plane, cz = i % plane / cols, cx = i % plane % cols;
            float dx = cx - x, dz = cz - z, dy = (top(f) - y) / Level.FLOOR;
            float d = (float) Math.sqrt(dx * dx + dz * dz + dy * dy);
            if (d > 2.6f) continue;
            int rank = level.order[i];
            if (time - hummed[i] < humPeriod(rank)) continue;
            hummed[i] = time;
            int loud = Math.max(1, Math.min(15, Math.round(15f * (1f - d / 2.6f))));
            emit(EV_HUM + rank * 16 + loud);
        }
    }

    /** The film shift of act k: each act wears a colour of its own. */
    static float actFilm(int k) {
        return k == 0 ? 0f : k == 1 ? 170f : 330f;
    }

    /** Landings left before the ground shifts, while it trembles; -1 when it does not. */
    int tremorLeft() {
        return tremorFrom < 0 || shift != 0 ? -1 : Math.max(0, tremorFrom + 3 - actHops);
    }

    /** The old act is down: the new one takes its place, still under the liquid, and starts to rise. */
    private void swapAct() {
        level = nextAct;
        type = level.type.clone();
        shard = level.shard.clone();
        bead = level.bead.clone();
        sink = new float[type.length];
        dent = new float[type.length];
        act++;
        actHops = 0;
        actLeft = level.shards();
        budget = act >= acts - 1 ? Integer.MAX_VALUE : Math.max(4, level.hops) + 4;
        if (pilot != null) {
            pilot = nextWay;
            pilotAt = 0;
        }
        tremor = 0f;
        shift = 2;
        shiftAge = 0f;
        version++;
    }

    /** The watcher's order for the hop about to start: the same turn and swipes a finger would give. */
    private void order(int t) {
        if (pilotAt * 2 + 1 >= pilot.length) return;
        int dx = pilot[pilotAt * 2], dz = pilot[pilotAt * 2 + 1];
        pilotAt++;
        // a spring throws by itself; its move is in the list only to keep count
        if (t == Level.SPRING) return;
        if (Math.abs(dx) == 2 || Math.abs(dz) == 2 || dx == 0 || dz == 0) {
            int k = Math.max(Math.abs(dx), Math.abs(dz));
            for (int f = 0; f < 4; f++)
                if (DX[f] * k == dx && DZ[f] * k == dz) facing = f;
            queued = k;
            slant = 0;
            return;
        }
        // a corner: of the two ways to slant into it, the one that turns the head less
        int best = -1, bestSide = 0, bestTurn = 9;
        for (int f = 0; f < 4; f++)
            for (int side = -1; side <= 1; side += 2) {
                int r = (f + 1) % 4;
                if (DX[f] + DX[r] * side != dx || DZ[f] + DZ[r] * side != dz) continue;
                int turn = Math.min((f - facing + 4) % 4, (facing - f + 4) % 4);
                if (turn < bestTurn) {
                    bestTurn = turn;
                    best = f;
                    bestSide = side;
                }
            }
        if (best >= 0) {
            facing = best;
            queued = 1;
            slant = bestSide;
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

    /** A ring on the liquid round a point of the world, wherever it is; nothing past the grid. */
    void ripple(float px, float pz, float radius, float depth) {
        float u = (px - gridX0) / gridSize, v = (pz - gridZ0) / gridSize;
        if (u < 0.02f || v < 0.02f || u > 0.98f || v > 0.98f) return;
        liquid.press(u, v, radius * liquid.cols / gridSize, depth);
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
        if (tremor > 0f) {
            // the drop feels the ground tremble
            eye[0] += (float) Math.sin(time * 43f) * 0.012f * tremor;
            eye[1] += (float) Math.sin(time * 57f + 1f) * 0.016f * tremor;
        }
        if (lift > 0f) {
            // through a shift the eye leaves the drop, rises behind it and looks down on the whole board
            float k = lift * lift * (3f - 2f * lift);
            float cx = (cols - 1) * 0.5f, cz = (rows - 1) * 0.5f;
            float reach = Math.max(cols, rows);
            float ux = cx - fx * reach * 0.6f, uy = top(floors - 1) + reach * 0.45f + 1.6f, uz = cz - fz * reach * 0.6f;
            eye[0] += (ux - eye[0]) * k;
            eye[1] += (uy - eye[1]) * k;
            eye[2] += (uz - eye[2]) * k;
            at[0] += (cx - at[0]) * k;
            at[1] += (top(floors - 1) * 0.4f - at[1]) * k;
            at[2] += (cz - at[2]) * k;
        }
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
