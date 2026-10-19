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
 * fixed rate and draws it from the eye of the drop.
 */
final class Stage extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final String TAG = "Hopper";
    private static final float STEP = 1f / 60f;
    /** How far the black surface reaches round the level, in cells. */
    private static final float REACH = 60f;

    // commands from the UI thread
    private static final int FORWARD = 0, LEFT = 1, RIGHT = 2, ROUND = 3, HOLD = 4, UP_LEFT = 5, UP_RIGHT = 6;

    private final MainActivity act;
    private World world;
    private int level;

    private final int[] pending = new int[32];
    private int pendingCount;

    // one finger at a time: where it went down, and whether it has already made a swipe
    private float downX, downY;
    private long downAt;
    private boolean swiped;
    /** The stroke started as a straight swipe up and may still stretch into a leap. */
    private boolean stretching;

    private int sky, liquid, scene, hud, text;
    private int triBuf, liquidBuf, islandBuf, movingBuf, farBuf, hudBuf, quadBuf;
    private int fieldTex, titleTex;
    private int islandVerts, movingVerts, farVerts, islandVersion = -1;
    private float[] islandData, movingData, farData;
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

    // frame timing for the log, so a phone test tells how heavy the shaders are
    private int frames;
    private long frameNanos, statStart;

    Stage(MainActivity act, int level) {
        super(act);
        this.act = act;
        this.level = level;
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
        int w = getWidth();
        if (w == 0) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downAt = e.getEventTime();
                swiped = false;
                stretching = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (swiped) {
                    // a long stroke up is a leap: the same finger, just further
                    if (stretching && -dy > w * 0.3f) {
                        stretching = false;
                        queue(FORWARD);
                    }
                    break;
                }
                // a swipe counts as soon as the finger has gone far enough, not when it lifts
                if (dx * dx + dy * dy > (w * 0.08f) * (w * 0.08f)) {
                    swiped = true;
                    // eight sectors; only the upper slanted ones mean a slanted hop
                    double deg = Math.toDegrees(Math.atan2(-dy, dx));
                    if (deg > 67.5 && deg <= 112.5) {
                        queue(FORWARD);
                        stretching = true;
                    }
                    else if (deg > 22.5 && deg <= 67.5) queue(UP_RIGHT);
                    else if (deg > 112.5 && deg <= 157.5) queue(UP_LEFT);
                    else if (deg > -22.5 && deg <= 22.5) queue(RIGHT);
                    else if (deg > 157.5 || deg <= -157.5) queue(LEFT);
                    else queue(ROUND);
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!swiped && dx * dx + dy * dy < (w * 0.03f) * (w * 0.03f) && e.getEventTime() - downAt < 350)
                    queue(HOLD);
                break;
            }
            default:
        }
        return true;
    }

    private void queue(int cmd) {
        synchronized (pending) {
            if (pendingCount < pending.length) pending[pendingCount++] = cmd;
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
        hud = program("hud.vert", "hud.frag");
        text = program("text.vert", "text.frag");
        int[] ids = new int[7];
        GLES30.glGenBuffers(7, ids, 0);
        triBuf = ids[0];
        liquidBuf = ids[1];
        islandBuf = ids[2];
        movingBuf = ids[3];
        farBuf = ids[4];
        hudBuf = ids[5];
        quadBuf = ids[6];
        upload2(triBuf, new float[] {-1, -1, 3, -1, -1, 3});
        upload2(quadBuf, new float[] {-1, -1, 1, -1, -1, 1, 1, 1});
        int[] tex = new int[2];
        GLES30.glGenTextures(2, tex, 0);
        fieldTex = tex[0];
        titleTex = tex[1];
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
        // the next level grows in the background while this one is played
        Level.prepare(w.index + 1);
        islandData = new float[Mesh.islandCapacity(w) * Mesh.FLOATS];
        movingData = new float[Mesh.dynamicCapacity(w) * Mesh.FLOATS];
        farData = new float[Mesh.distanceCapacity() * Mesh.FLOATS];
        int most = Math.max(islandData.length, Math.max(movingData.length, farData.length));
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
        levelReady = true;
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
                switch (pending[i]) {
                    case FORWARD: w.forward(); break;
                    case LEFT: w.turn(-1); break;
                    case RIGHT: w.turn(1); break;
                    case ROUND: w.turn(2); break;
                    case UP_LEFT: w.slanted(-1); break;
                    case UP_RIGHT: w.slanted(1); break;
                    default: w.hold();
                }
            }
            pendingCount = 0;
        }

        // the world and the liquid run at a fixed rate whatever the display does
        accum += dt;
        int steps = 0;
        while (accum >= STEP && steps < 4) {
            w.update(STEP);
            w.liquid.step();
            accum -= STEP;
            steps++;
        }
        if (steps == 4) accum = 0;

        int n = w.takeEvents(events);
        for (int i = 0; i < n; i++) act.onWorldEvent(events[i], w);

        if (w.finished()) {
            boolean won = w.state == World.WIN;
            if (won) level++;
            act.onLevel(level);
            // a new level waits in the dark for its tap; a level tried again rises at once
            world = new World(level, won);
            prepareLevel();
            w = world;
        }

        w.camera(eye, look);
        Mat.lookAt(view, eye[0], eye[1], eye[2], look[0], look[1], look[2]);
        Mat.mul(mvp, proj, view);

        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        drawSky();
        drawLiquid(w);
        drawScene(w);
        drawHud(w);
        drawTitle(w);

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
        GLES30.glUniform1f(loc(liquid, "uFilm"), 0f);
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
        GLES30.glUniform1f(loc(scene, "uFilm"), 0f);
        drawMesh(islandBuf, islandVerts);
        drawMesh(movingBuf, movingVerts);
        drawMesh(farBuf, farVerts);
    }

    private void drawHud(World w) {
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

    /** The level's number and its hint, for the first seconds of every try. */
    private void drawTitle(World w) {
        float a;
        float t = w.awake - 1.2f;
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
        c.drawText(String.valueOf(w.index + 1), TITLE_W / 2f, 175f, p);
        if (w.level.hint.length() > 0) {
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            p.setTextSize(52f);
            p.setLetterSpacing(0.06f);
            c.drawText(w.level.hint, TITLE_W / 2f, 300f, p);
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
