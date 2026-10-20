package com.shumtugle.hopper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;
import android.view.MotionEvent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The surface: reads swipes on the UI thread, then on the GL thread advances the world at a
 * fixed rate and draws it from the eye of the drop. It also keeps the hopscotch of all levels,
 * and calls the watcher: the solver runs on a thread of its own, and when it has the way, the
 * level starts again with the watcher playing it.
 */
final class Stage extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final String TAG = "Hopper";
    private static final float STEP = 1f / 60f;
    /** How far the black surface reaches round the level, in cells. */
    private static final float REACH = 60f;

    // commands from the UI thread
    private static final int FORWARD = 0, LEFT = 1, RIGHT = 2, ROUND = 3, TAP = 4, UP_LEFT = 5, UP_RIGHT = 6,
            DRAG = 7, BACK = 8, DEV = 9;
    /** The watcher plays at a finger's pace: watching it is watching someone play. */
    private static final float WATCHER_PACE = 1f;

    private final MainActivity act;
    private World world;
    private int level;

    /** Commands waiting for the GL thread: kind, x, y triples, in screen widths from the middle. */
    private final float[] pending = new float[3 * 64];
    private int pendingCount;

    private final Chalk chalk = new Chalk();
    /** A copy of chalk.open for the UI thread, so a finger knows whether it drags the strip. */
    private volatile boolean chalkOpen;
    private int reached;
    private String marks;
    /**
     * The developer's mode: every level open on the hopscotch, which scrolls fast; nothing won
     * is written down, and the world in the dark is as big as the level's number, so any level
     * and any size of world can be looked at without playing up to it. A long press on the
     * hopscotch turns it on and off.
     */
    private boolean dev;
    private static final int DEV_AHEAD = 1000;
    private float lastMoveY;

    // a funnel's detour: the level it poured the drop out of, how often, and where it went last
    private int detourFrom = -1, funnels, funnelsFor = -1, lastDetour = -1;

    // the watcher's way, worked out on its own thread
    private volatile int[] way;
    private volatile int wayFor = -1;
    private volatile boolean thinking;

    // one finger at a time: where it went down, and whether it has already made a swipe
    private float downX, downY;
    private long downAt;
    private boolean swiped;
    /** The stroke started as a straight swipe up and may still stretch into a leap. */
    private boolean stretching;

    private int sky, liquid, scene, mirage, hud, text;
    private int triBuf, liquidBuf, islandBuf, movingBuf, farBuf, hudBuf, quadBuf, mirageBuf, cityBuf, freshBuf;
    private int fieldTex, titleTex, digitTex;
    private int islandVerts, movingVerts, farVerts, mirageVerts, islandVersion = -1;
    private float[] islandData, movingData, farData, mirageData;
    private FloatBuffer meshOut;
    private ByteBuffer fieldOut;
    private Hud hudShapes;
    private FloatBuffer hudOut;
    private int width, height;
    private float aspect = 2f;
    private boolean levelReady;

    private long lastNanos;
    private float accum;
    private final int[] events = new int[32];
    private final float[] proj = new float[16], view = new float[16], mvp = new float[16];
    private final float[] eye = new float[3], look = new float[3];

    // the world that grows one piece for every level won for the first time, shown in the dark
    // before a level: the newest piece grows out of the black; a tap sends it all under
    private City city;
    private int cityVerts, freshVerts;
    private float[] freshData;
    private FloatBuffer freshOut;
    /** The world stands in the dark now; its newest piece is growing; seconds of both. */
    private boolean cityShown, cityGrowing, cityRang;
    private float cityAge, cityLeave = -1f;
    /** Set by a win that opened a new level: the next dark grows a piece. */
    private boolean cityOwed;
    private final float[] mvpCity = new float[16];

    // frame timing for the log, so a phone test tells how heavy the shaders are
    private int frames;
    private long frameNanos, statStart;

    Stage(MainActivity act, int level, int reached, String marks) {
        super(act);
        this.act = act;
        this.level = level;
        this.reached = Math.max(reached, level);
        this.marks = marks;
        dev = act.dev();
        Rival.threads = true;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 24, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    /** The level being played, for saving. */
    int level() {
        return level;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return true;
        // finger positions in screen widths from the middle, upwards positive
        float fx = e.getX() / w - 0.5f, fy = (h * 0.5f - e.getY()) / w;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastMoveY = e.getY();
                downAt = e.getEventTime();
                swiped = false;
                stretching = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (chalkOpen) {
                    // on the hopscotch a finger only drags the strip up and down
                    if (dx * dx + dy * dy > (w * 0.03f) * (w * 0.03f)) swiped = true;
                    if (swiped) queue(DRAG, 0f, (lastMoveY - e.getY()) / w);
                    lastMoveY = e.getY();
                    break;
                }
                if (swiped) {
                    // a long stroke up is a leap: the same finger, just further
                    if (stretching && -dy > w * 0.3f) {
                        stretching = false;
                        queue(FORWARD, 0f, 0f);
                    }
                    break;
                }
                // a swipe counts as soon as the finger has gone far enough, not when it lifts
                if (dx * dx + dy * dy > (w * 0.08f) * (w * 0.08f)) {
                    swiped = true;
                    // eight sectors; only the upper slanted ones mean a slanted hop
                    double deg = Math.toDegrees(Math.atan2(-dy, dx));
                    if (deg > 67.5 && deg <= 112.5) {
                        queue(FORWARD, 0f, 0f);
                        stretching = true;
                    }
                    else if (deg > 22.5 && deg <= 67.5) queue(UP_RIGHT, 0f, 0f);
                    else if (deg > 112.5 && deg <= 157.5) queue(UP_LEFT, 0f, 0f);
                    else if (deg > -22.5 && deg <= 22.5) queue(RIGHT, 0f, 0f);
                    else if (deg > 157.5 || deg <= -157.5) queue(LEFT, 0f, 0f);
                    else queue(ROUND, 0f, 0f);
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                boolean still = !swiped && dx * dx + dy * dy < (w * 0.03f) * (w * 0.03f);
                if (still && e.getEventTime() - downAt < 400) queue(TAP, fx, fy);
                else if (still && chalkOpen && e.getEventTime() - downAt > 1500) queue(DEV, 0f, 0f);
                break;
            }
            default:
        }
        return true;
    }

    /** The system back: opens the hopscotch, or, from the hopscotch, leaves. */
    void back() {
        queue(BACK, 0f, 0f);
    }

    private void queue(int cmd, float x, float y) {
        synchronized (pending) {
            if (pendingCount * 3 + 3 > pending.length) return;
            pending[pendingCount * 3] = cmd;
            pending[pendingCount * 3 + 1] = x;
            pending[pendingCount * 3 + 2] = y;
            pendingCount++;
        }
    }

    /** Called when the activity comes back, so the pause does not count as one long frame. */
    void resumeClock() {
        lastNanos = 0;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        sky = program("sky.vert", "sky.frag");
        liquid = program("liquid.vert", "liquid.frag");
        scene = program("scene.vert", "scene.frag");
        mirage = program("scene.vert", "mirage.frag");
        hud = program("hud.vert", "hud.frag");
        text = program("text.vert", "text.frag");
        int[] ids = new int[10];
        GLES30.glGenBuffers(10, ids, 0);
        triBuf = ids[0];
        liquidBuf = ids[1];
        islandBuf = ids[2];
        movingBuf = ids[3];
        farBuf = ids[4];
        hudBuf = ids[5];
        quadBuf = ids[6];
        mirageBuf = ids[7];
        cityBuf = ids[8];
        freshBuf = ids[9];
        city = null;
        upload2(triBuf, new float[] {-1, -1, 3, -1, -1, 3});
        upload2(quadBuf, new float[] {-1, -1, 1, -1, -1, 1, 1, 1});
        int[] tex = new int[3];
        GLES30.glGenTextures(3, tex, 0);
        fieldTex = tex[0];
        titleTex = tex[1];
        digitTex = tex[2];
        makeDigits();
        levelReady = false;
        lastNanos = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = w;
        height = h;
        aspect = (float) h / w;
        GLES30.glViewport(0, 0, w, h);
        Mat.perspective(proj, World.fovUp(aspect), 1f / aspect, 0.03f, 90f);
        if (world == null) world = new World(level, true);
        levelReady = false;
    }

    /** Sets up everything that belongs to one level: buffers sized for it, the liquid, the title. */
    private void prepareLevel() {
        World w = world;
        // a shifting level grows its next act on a thread of its own, never in the frame
        w.async = true;
        // the next level grows in the background while this one is played
        Level.prepare(w.index + 1);
        islandData = new float[Mesh.islandCapacity(w) * Mesh.FLOATS];
        movingData = new float[Mesh.dynamicCapacity(w) * Mesh.FLOATS];
        farData = new float[Mesh.distanceCapacity() * Mesh.FLOATS];
        mirageData = new float[Mesh.mirageCapacity(w) * Mesh.FLOATS];
        int most = Math.max(Math.max(islandData.length, mirageData.length), Math.max(movingData.length, farData.length));
        if (meshOut == null || meshOut.capacity() < most)
            meshOut = ByteBuffer.allocateDirect(most * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        // the black surface reaches far past the level on every side
        float cx = (w.cols - 1) * 0.5f, cz = (w.rows - 1) * 0.5f;
        upload2(liquidBuf, new float[] {cx - REACH, cz - REACH, cx + REACH, cz - REACH, cx - REACH, cz + REACH, cx + REACH, cz + REACH});
        farVerts = Mesh.distance(w, farData);
        upload(farBuf, farData, farVerts, GLES30.GL_STATIC_DRAW);
        islandVersion = -1;
        hudShapes = new Hud(Hud.capacity(w));
        hudOut = ByteBuffer.allocateDirect(hudShapes.data().length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        Liquid lq = w.liquid;
        fieldOut = ByteBuffer.allocateDirect(lq.texels.length).order(ByteOrder.nativeOrder());
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTex);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, lq.cols, lq.rows, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        linear();
        makeTitle(w);
        prepareCity(w);
        levelReady = true;
    }

    /**
     * The world for the dark before a level: as many pieces as levels won for the first time.
     * If a win just opened a new level, its piece is held back to grow in front of the eye.
     */
    private void prepareCity(World w) {
        boolean grow = cityOwed;
        cityOwed = false;
        int pieces = dev ? level : reached;
        cityShown = w.state == World.DARK && pieces > 0;
        cityGrowing = cityShown && grow;
        cityRang = false;
        cityAge = 0f;
        cityLeave = -1f;
        if (!cityShown) return;
        if (city == null || city.size() != pieces) city = new City(pieces);
        float ox = (w.cols - 1) * 0.5f, oz = (w.rows - 1) * 0.5f;
        float[] built = city.build(0, cityGrowing ? pieces - 1 : pieces, ox, oz);
        cityVerts = built.length / Mesh.FLOATS;
        FloatBuffer f = ByteBuffer.allocateDirect(Math.max(4, built.length * 4)).order(ByteOrder.nativeOrder()).asFloatBuffer();
        f.put(built).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cityBuf);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, Math.max(4, built.length * 4), f, GLES30.GL_STATIC_DRAW);
        if (freshData == null) {
            freshData = new float[City.PIECE * Mesh.FLOATS];
            freshOut = ByteBuffer.allocateDirect(freshData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
    }

    /** The newest piece of the world, the one just earned, as far as it has grown. */
    private City.Piece fresh() {
        return cityGrowing ? city.pieces.get(city.size() - 1) : null;
    }

    /** How far the newest piece has grown out of the black. */
    private float freshGrown() {
        float k = World.smooth(0.9f, 3.6f, cityAge);
        float a = 1f - k;
        return 1f - a * a * a;
    }

    /** The world in the dark: it turns slowly round, grows its newest piece, and goes under at a tap. */
    private void stepCity(World w, float dt) {
        if (!cityShown) return;
        cityAge += dt;
        City.Piece p = fresh();
        if (p != null && !cityRang && cityAge >= 0.9f) {
            // the black parts where the new piece comes up
            cityRang = true;
            float ox = (w.cols - 1) * 0.5f, oz = (w.rows - 1) * 0.5f;
            w.ripple(p.x + ox, p.z + oz, 0.6f, 1.6f);
            act.onCityGrow(p.precious());
        }
        if (cityLeave >= 0f) {
            cityLeave += dt;
            if (cityLeave >= 1.1f) {
                cityShown = false;
                w.hold();
            }
        }
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? STEP : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        if (!levelReady) prepareLevel();
        World w = world;

        synchronized (pending) {
            for (int i = 0; i < pendingCount; i++) {
                int cmd = (int) pending[i * 3];
                float x = pending[i * 3 + 1], y = pending[i * 3 + 2];
                if (chalk.open) {
                    if (cmd == DRAG) chalk.drag(dev ? y * 8f : y);
                    else if (cmd == DEV) toggleDev();
                    else if (cmd == TAP) pick(x, y);
                    else if (cmd == BACK) act.quit();
                    continue;
                }
                switch (cmd) {
                    case FORWARD: w.forward(); break;
                    case LEFT: w.turn(-1); break;
                    case RIGHT: w.turn(1); break;
                    case ROUND: w.turn(2); break;
                    case UP_LEFT: w.slanted(-1); break;
                    case UP_RIGHT: w.slanted(1); break;
                    case BACK: openChalk(); break;
                    case TAP: {
                        float ex = x - Hud.eyeX(), ey = y - Hud.eyeY(aspect);
                        if (hudShapes != null && hudShapes.eyeShown && ex * ex + ey * ey < Hud.EYE_R * Hud.EYE_R * 2.2f) summon();
                        else if (cityShown && w.state == World.DARK) {
                            // the first tap sends the world under; the level wakes once it is gone
                            if (cityLeave < 0f) cityLeave = 0f;
                        } else w.hold();
                        break;
                    }
                    default:
                }
            }
            pendingCount = 0;
        }
        w = world;

        // the watcher has found the way: the level starts again, rising at once, and it plays
        int[] found = way;
        if (found != null && wayFor == level) {
            way = null;
            wayFor = -1;
            world = new World(level, false);
            world.fly(found);
            prepareLevel();
            w = world;
        }

        // the world and the liquid run at a fixed rate whatever the display does; the hopscotch stops them
        if (chalk.open) {
            chalk.age += dt;
        } else {
            accum += dt * (w.pilot != null ? WATCHER_PACE : 1f);
        }
        int steps = 0;
        while (accum >= STEP && steps < 4 && !chalk.open) {
            w.update(STEP);
            w.liquid.step();
            accum -= STEP;
            steps++;
        }
        if (steps == 4) accum = 0;

        int n = w.takeEvents(events);
        for (int i = 0; i < n; i++) {
            act.onWorldEvent(events[i], w);
            // a new act has risen: its number takes the title's place
            if (events[i] == World.EV_RISEN) makeTitle(w);
        }

        if (w.finished() && w.state == World.FUNNEL) {
            // the funnel pours the drop into a level already played; won, it pours it back
            if (funnelsFor != level) {
                funnelsFor = level;
                funnels = 0;
            }
            funnels++;
            int past = Maze.detour(level, funnels, reached, lastDetour);
            if (past >= 0) {
                detourFrom = level;
                lastDetour = past;
                level = past;
            }
            world = new World(level, false);
            prepareLevel();
            w = world;
        } else if (w.finished() && w.state == World.WIN && detourFrom >= 0) {
            marks = solved(level, w.pilot != null);
            level = detourFrom;
            detourFrom = -1;
            saveLevel(level);
            world = new World(level, false);
            prepareLevel();
            w = world;
        } else if (w.finished()) {
            boolean won = w.state == World.WIN;
            boolean watched = won && w.pilot != null;
            if (won) {
                marks = solved(level, watched);
                level++;
                // a level opened for the first time grows the world by a piece
                if (level > reached || dev) cityOwed = true;
                if (!dev) reached = Math.max(reached, level);
            }
            // a detour remembers where it leads back to, should the game be closed half way
            saveLevel(detourFrom >= 0 ? detourFrom : level);
            // a new level waits in the dark for its tap; a level tried again rises at once
            world = new World(level, won);
            prepareLevel();
            w = world;
            // after the watcher's game, the hopscotch: any level played, or the next
            if (watched) openChalk();
        }

        stepCity(w, dt);
        w.camera(eye, look);
        if (cityShown) city.camera((w.cols - 1) * 0.5f, (w.rows - 1) * 0.5f, cityAge * 0.05f, fresh(), eye, look);
        Mat.lookAt(view, eye[0], eye[1], eye[2], look[0], look[1], look[2]);
        Mat.mul(mvp, proj, view);

        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        drawSky();
        drawLiquid(w);
        drawScene(w);
        if (chalk.open) {
            drawChalk();
        } else {
            drawHud(w);
            drawTitle(w);
        }

        frames++;
        frameNanos += System.nanoTime() - now;
        if (statStart == 0) statStart = now;
        if (now - statStart > 5_000_000_000L) {
            Log.i(TAG, String.format("%dx%d %.1f fps, %.2f ms cpu a frame", width, height,
                    frames / ((now - statStart) / 1e9f), frameNanos / 1e6f / frames));
            frames = 0;
            frameNanos = 0;
            statStart = now;
        }
    }

    private void drawSky() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(sky);
        // the camera's own axes, straight out of the view matrix
        GLES30.glUniform3f(loc(sky, "uRight"), view[0], view[4], view[8]);
        GLES30.glUniform3f(loc(sky, "uUp"), view[1], view[5], view[9]);
        GLES30.glUniform3f(loc(sky, "uAhead"), -view[2], -view[6], -view[10]);
        float up = (float) Math.tan(Math.toRadians(World.fovUp(aspect)) * 0.5);
        GLES30.glUniform2f(loc(sky, "uSpan"), up / aspect, up);
        flat(triBuf);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    private void drawLiquid(World w) {
        Liquid lq = w.liquid;
        lq.encode();
        fieldOut.position(0);
        fieldOut.put(lq.texels).position(0);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        GLES30.glUseProgram(liquid);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTex);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, lq.cols, lq.rows,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, fieldOut);
        GLES30.glUniform1i(loc(liquid, "uField"), 0);
        GLES30.glUniformMatrix4fv(loc(liquid, "uMvp"), 1, false, mvp, 0);
        GLES30.glUniform1f(loc(liquid, "uLevel"), World.LIQUID_Y);
        GLES30.glUniform3f(loc(liquid, "uGrid"), w.gridX0, w.gridZ0, w.gridSize);
        GLES30.glUniform3f(loc(liquid, "uEye"), eye[0], eye[1], eye[2]);
        GLES30.glUniform1f(loc(liquid, "uFilm"), w.film);
        flat(liquidBuf);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawScene(World w) {
        if (w.version != islandVersion) {
            islandVersion = w.version;
            islandVerts = Mesh.islands(w, islandData);
            upload(islandBuf, islandData, islandVerts, GLES30.GL_DYNAMIC_DRAW);
        }
        movingVerts = Mesh.moving(w, movingData);
        upload(movingBuf, movingData, movingVerts, GLES30.GL_STREAM_DRAW);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glUseProgram(scene);
        GLES30.glUniformMatrix4fv(loc(scene, "uMvp"), 1, false, mvp, 0);
        GLES30.glUniform3f(loc(scene, "uEye"), eye[0], eye[1], eye[2]);
        GLES30.glUniform1f(loc(scene, "uTime"), w.time);
        GLES30.glUniform1f(loc(scene, "uFilm"), w.film);
        drawMesh(islandBuf, islandVerts);
        drawMesh(movingBuf, movingVerts);
        // the world in the dark is its own horizon; the far hoppers belong to the level
        if (cityShown) drawCity(w);
        else drawMesh(farBuf, farVerts);

        // the mirages' mirrors last, see-through, over everything they stand in front of
        int need = Mesh.mirageCapacity(w) * Mesh.FLOATS;
        if (mirageData.length < need) {
            // an act that rose larger than the first
            mirageData = new float[need];
            if (meshOut.capacity() < need)
                meshOut = ByteBuffer.allocateDirect(need * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        mirageVerts = Mesh.mirages(w, mirageData);
        if (mirageVerts == 0) return;
        upload(mirageBuf, mirageData, mirageVerts, GLES30.GL_STREAM_DRAW);
        GLES30.glUseProgram(mirage);
        GLES30.glUniformMatrix4fv(loc(mirage, "uMvp"), 1, false, mvp, 0);
        GLES30.glUniform3f(loc(mirage, "uEye"), eye[0], eye[1], eye[2]);
        GLES30.glUniform4f(loc(mirage, "uDrop"), w.x, w.y + Mesh.DROP, w.z, Mesh.DROP);
        GLES30.glUniform1f(loc(mirage, "uTime"), w.time);
        GLES30.glDepthMask(false);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE);
        drawMesh(mirageBuf, mirageVerts);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDepthMask(true);
    }

    /** The world in the dark, sinking whole into the liquid once a tap has sent it under. */
    private void drawCity(World w) {
        float sink = cityLeave < 0f ? 0f : World.smooth(0f, 1.0f, cityLeave);
        float down = sink * sink * (city.tallest() + 2.5f);
        System.arraycopy(mvp, 0, mvpCity, 0, 16);
        // the whole world goes down at once: one step down in y, in front of the camera's matrix
        for (int r = 0; r < 4; r++) mvpCity[12 + r] -= down * mvp[4 + r];
        GLES30.glUseProgram(scene);
        GLES30.glUniformMatrix4fv(loc(scene, "uMvp"), 1, false, mvpCity, 0);
        drawMesh(cityBuf, cityVerts);
        City.Piece p = fresh();
        if (p != null && cityAge >= 0.9f) {
            float ox = (w.cols - 1) * 0.5f, oz = (w.rows - 1) * 0.5f;
            freshVerts = city.piece(p, freshData, 0, ox, oz, freshGrown(), 0f);
            freshOut.position(0);
            freshOut.put(freshData, 0, freshVerts * Mesh.FLOATS).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, freshBuf);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, freshVerts * Mesh.FLOATS * 4, freshOut, GLES30.GL_STREAM_DRAW);
            drawMesh(freshBuf, freshVerts);
        }
        GLES30.glUniformMatrix4fv(loc(scene, "uMvp"), 1, false, mvp, 0);
    }

    private void openChalk() {
        chalk.open(dev ? Math.max(reached, level) + DEV_AHEAD : reached, level, marks, aspect);
        chalkOpen = true;
    }

    /** A long press on the hopscotch: the developer's mode on, or off and back to the real game. */
    private void toggleDev() {
        dev = !dev;
        act.setDev(dev);
        if (!dev) {
            level = Math.min(act.savedLevel(), reached);
            detourFrom = -1;
            world = new World(level, true);
            prepareLevel();
        }
        openChalk();
    }

    /** Writes down the level being played, unless in the developer's mode. */
    private void saveLevel(int n) {
        if (!dev) act.onLevel(n);
    }

    /** Writes down a level won, unless in the developer's mode; returns the marks. */
    private String solved(int n, boolean watcher) {
        return dev ? marks : act.onSolved(n, watcher);
    }

    /** A tap on the hopscotch: a square starts its level in the dark; anywhere else goes back. */
    private void pick(float x, float y) {
        int index = chalk.hit(x, y);
        if (index == -1) return;
        chalk.close();
        chalkOpen = false;
        if (index >= 0) detourFrom = -1;
        if (index >= 0 && (index != level || world.state != World.DARK)) {
            level = index;
            saveLevel(level);
            world = new World(level, true);
            prepareLevel();
        }
        lastNanos = 0;
    }

    /** Asks the watcher to play this level: it works out the way on a thread of its own. */
    private void summon() {
        if (thinking || world.pilot != null) return;
        thinking = true;
        act.onSummon();
        final int target = level;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int[] p = Solver.path(Level.get(target), 600000);
                if (p != null) {
                    way = p;
                    wayFor = target;
                }
                thinking = false;
            }
        }, "watcher");
        t.setDaemon(true);
        t.start();
    }

    private void drawHud(World w) {
        hudShapes.thinking = thinking;
        hudShapes.dark = !cityShown ? 1f : cityLeave < 0f ? 0f : World.smooth(0.25f, 1.05f, cityLeave);
        hudShapes.clock = w.time;
        int n = hudShapes.build(w, aspect);
        if (n == 0) return;
        hudOut.position(0);
        hudOut.put(hudShapes.data(), 0, n * Hud.FLOATS).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, hudBuf);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * Hud.FLOATS * 4, hudOut, GLES30.GL_STREAM_DRAW);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        // colour blends, but the screen's own alpha stays whole
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE);
        GLES30.glUseProgram(hud);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, Hud.FLOATS * 4, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, Hud.FLOATS * 4, 8);
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, n);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    /** The hopscotch over the stopped scene, then its numbers. */
    private void drawChalk() {
        hudShapes.clock += 1f / 60f;
        int n = hudShapes.buildChalk(chalk, aspect);
        if (n > 0) {
            hudOut.position(0);
            hudOut.put(hudShapes.data(), 0, n * Hud.FLOATS).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, hudBuf);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * Hud.FLOATS * 4, hudOut, GLES30.GL_STREAM_DRAW);
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE);
            GLES30.glUseProgram(hud);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, Hud.FLOATS * 4, 0);
            GLES30.glEnableVertexAttribArray(1);
            GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, Hud.FLOATS * 4, 8);
            GLES30.glDisableVertexAttribArray(2);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, n);
            GLES30.glDisableVertexAttribArray(1);
        }
        // the numbers, digit by digit from the strip of glyphs
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE);
        GLES30.glUseProgram(text);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, digitTex);
        GLES30.glUniform1i(loc(text, "uText"), 0);
        flat(quadBuf);
        float gh = 0.075f, gw = gh * DIGIT_W / (float) DIGIT_H, step = gw * 0.62f;
        for (int k = 0; k < hudShapes.labels; k++) {
            String s = String.valueOf(hudShapes.labelN[k]);
            float x0 = hudShapes.labelX[k] - step * (s.length() - 1) * 0.5f;
            GLES30.glUniform1f(loc(text, "uAlpha"), hudShapes.labelA[k]);
            for (int c = 0; c < s.length(); c++) {
                int d = s.charAt(c) - '0';
                GLES30.glUniform2f(loc(text, "uSpan"), d * DIGIT_W / (float) DIGITS_W, DIGIT_W / (float) DIGITS_W);
                GLES30.glUniform4f(loc(text, "uRect"), (x0 + c * step) * 2f, hudShapes.labelY[k] * 2f / aspect,
                        gw, gh / aspect);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            }
        }
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private static final int DIGIT_W = 96, DIGIT_H = 128, DIGITS_W = 1024;

    /** The ten digits in a row, in the same face as the level's number. */
    private void makeDigits() {
        Bitmap b = Bitmap.createBitmap(DIGITS_W, DIGIT_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        p.setTextSize(104f);
        Paint.FontMetrics m = p.getFontMetrics();
        for (int d = 0; d < 10; d++)
            c.drawText(String.valueOf(d), d * DIGIT_W + DIGIT_W / 2f, DIGIT_H / 2f - (m.ascent + m.descent) / 2f, p);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, digitTex);
        linear();
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        b.recycle();
    }

    /** The level's number and its hint, for the first seconds of every try. */
    private void drawTitle(World w) {
        float a;
        float t = w.act > 0 ? w.actAge : w.awake - 1.2f;
        if (t < 0f) return;
        if (t < 0.4f) a = t / 0.4f;
        else a = 1f - World.smooth(3.2f, 4.2f, t);
        if (a <= 0f) return;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE);
        GLES30.glUseProgram(text);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, titleTex);
        GLES30.glUniform1i(loc(text, "uText"), 0);
        GLES30.glUniform1f(loc(text, "uAlpha"), a);
        GLES30.glUniform2f(loc(text, "uSpan"), 0f, 1f);
        // 0.84 of the screen wide, the texture's shape kept, a little above the middle
        float hw = 0.84f, hh = hw * (TITLE_H / (float) TITLE_W) / aspect;
        GLES30.glUniform4f(loc(text, "uRect"), 0f, 0.42f, hw, hh);
        flat(quadBuf);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private static final int TITLE_W = 1024, TITLE_H = 360;

    private void makeTitle(World w) {
        Bitmap b = Bitmap.createBitmap(TITLE_W, TITLE_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        p.setTextSize(170f);
        String[] roman = {"I", "II", "III", "IV"};
        String big = w.act > 0 ? roman[Math.min(3, w.act)] : String.valueOf(w.index + 1);
        String hint = w.act > 0 ? "the ground has shifted"
                : detourFrom >= 0 ? "win it, and the funnel pours you back to " + (detourFrom + 1) : w.level.hint;
        c.drawText(big, TITLE_W / 2f, 175f, p);
        if (hint.length() > 0) {
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            p.setTextSize(52f);
            p.setLetterSpacing(0.06f);
            c.drawText(hint, TITLE_W / 2f, 300f, p);
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, titleTex);
        linear();
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        b.recycle();
    }

    // ---- plumbing ----

    private static void linear() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    }

    /** Points attribute 0 at a buffer of 2D points and turns the other attributes off. */
    private static void flat(int buf) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glDisableVertexAttribArray(2);
    }

    private static void upload2(int buf, float[] data) {
        FloatBuffer f = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        f.put(data).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.length * 4, f, GLES30.GL_STREAM_DRAW);
    }

    private void upload(int buf, float[] data, int verts, int usage) {
        int floats = verts * Mesh.FLOATS;
        meshOut.position(0);
        meshOut.put(data, 0, floats).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, Math.max(4, floats * 4), meshOut, usage);
    }

    private static void drawMesh(int buf, int verts) {
        if (verts <= 0) return;
        int stride = Mesh.FLOATS * 4;
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, verts);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glDisableVertexAttribArray(2);
    }

    private static int loc(int prog, String name) {
        return GLES30.glGetUniformLocation(prog, name);
    }

    private int program(String vert, String frag) {
        String common = asset("shaders/common.glsl");
        int v = shader(GLES30.GL_VERTEX_SHADER, asset("shaders/" + vert).replace("#include common", common));
        int f = shader(GLES30.GL_FRAGMENT_SHADER, asset("shaders/" + frag).replace("#include common", common));
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link " + vert + ": " + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("compile: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }

    private String asset(String name) {
        try {
            InputStream in = getContext().getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
