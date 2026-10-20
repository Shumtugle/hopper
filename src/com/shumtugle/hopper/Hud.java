package com.shumtugle.hopper;

/**
 * Flat shapes drawn over the scene: a small map in the top left corner that turns with the drop, so "up" on it is
 * always "ahead"; the watcher's eye beside it; a row of pips for the shards; a veil for falling in
 * and out of a level; and the hopscotch of all levels.
 *
 * Six floats a vertex: clip x, clip y, r, g, b, a. Lengths are measured in screen widths.
 */
final class Hud {
    static final int FLOATS = 6;

    private final float[] out;
    private int v;
    private float aspect;

    Hud(int maxVerts) {
        out = new float[maxVerts * FLOATS];
    }

    float[] data() {
        return out;
    }

    static int capacity(World w) {
        return Math.max((w.type.length * 3 + 140) * 6, 6000);
    }

    /** Set by the stage before each build: the watcher is working out the way. */
    boolean thinking;
    /** Read by the stage after each build: whether the eye is there to be tapped. */
    boolean eyeShown;
    /** Seconds, for the eye's blinking and the new square's pulse. */
    float clock;

    /** The centre and size of the watcher's eye, in screen widths: the top right corner, level with the map. */
    static float eyeX() {
        return 0.5f - 0.05f - EYE_R * 1.42f;
    }

    static float eyeY(float aspect) {
        return aspect * 0.5f - 0.05f - EYE_R * 1.42f - 0.02f;
    }

    static final float EYE_R = 0.07f;

    /** Builds this frame's shapes; returns the vertex count. aspect is screen height over width. */
    int build(World w, float aspect) {
        this.aspect = aspect;
        v = 0;
        map(w);
        watcher(w);
        pips(w);
        veil(w);
        return v;
    }

    /**
     * The watcher: an eye in a stepped frame of bismuth, set on its corner. It comes and goes
     * with the map; tapped, it plays the level. While it plays it stays, and its frame glows.
     */
    private void watcher(World w) {
        eyeShown = false;
        if (w.state == World.DARK || w.state == World.RISE || w.shift != 0) return;
        boolean playing = w.pilot != null;
        float fade = playing || thinking ? 1f
                : (w.state == World.PLAY || w.state == World.FALL_IN ? 1f : 0f) * (1f - World.smooth(2.2f, 2.9f, w.mapAge));
        if (fade <= 0.01f) return;
        eyeShown = fade > 0.4f && !playing;
        float x = eyeX(), y = eyeY(aspect), r = EYE_R;
        float d = 0.7071f;
        // the frame: three steps, gold, rose and sea-green, the colours of the oxide
        float glow = playing ? 0.75f + 0.25f * (float) Math.sin(clock * 3f) : 0.8f;
        square(x, y, r * 0.98f, d, d, 0.02f, 0.02f, 0.03f, 0.8f * fade);
        frameAround(x, y, r, r * 0.13f, d, d, 0.84f, 0.67f, 0.25f, glow * fade);
        frameAround(x, y, r * 0.8f, r * 0.11f, d, d, 0.77f, 0.28f, 0.59f, glow * fade);
        frameAround(x, y, r * 0.62f, r * 0.1f, d, d, 0.2f, 0.59f, 0.63f, glow * fade);
        // a blink every few seconds; thinking, it narrows and the iris runs through the colours
        float phase = clock % 4.3f, open = phase < 0.18f ? Math.abs(phase / 0.09f - 1f) : 1f;
        if (thinking) open = Math.min(open, 0.55f);
        float ex = r * 0.4f, ey = r * 0.24f * open;
        oval(x, y, ex, ey, 0.93f, 0.94f, 0.97f, fade);
        if (open < 0.12f) return;
        // the pupil looks where the next hop will land
        float px = 0f, py = 0f;
        if (w.state == World.PLAY) {
            float dx = w.nextX() - w.cellX, dz = w.nextZ() - w.cellZ;
            float cy = (float) Math.cos(w.yaw), sy = (float) Math.sin(w.yaw);
            float ux = dx * cy + dz * sy, uy = dx * sy - dz * cy, l = (float) Math.sqrt(ux * ux + uy * uy);
            if (l > 0f) {
                px = ux / l * r * 0.12f;
                py = uy / l * r * 0.06f;
            }
        }
        if (thinking) {
            px = (float) Math.cos(clock * 5f) * r * 0.1f;
            py = (float) Math.sin(clock * 5f) * r * 0.05f;
        }
        float[] iris = spectrum(thinking ? (clock * 0.6f) % 0.9f : 0.62f);
        float ir = r * 0.17f;
        oval(x + px, y + py, ir, Math.min(ir, ey * 0.95f), iris[0] * 0.8f, iris[1] * 0.8f, iris[2] * 0.9f + 0.1f, fade);
        oval(x + px, y + py, ir * 0.48f, Math.min(ir * 0.48f, ey * 0.6f), 0.02f, 0.02f, 0.03f, fade);
        oval(x + px - ir * 0.3f, y + py + ir * 0.3f * open, ir * 0.16f, ir * 0.16f * open, 1f, 1f, 1f, 0.9f * fade);
    }

    /** A filled ellipse. */
    private void oval(float x, float y, float rx, float ry, float r, float g, float b, float a) {
        for (int k = 0; k < 20; k++) {
            double a0 = k * Math.PI / 10, a1 = (k + 1) * Math.PI / 10;
            tri(x, y, x + (float) Math.cos(a0) * rx, y + (float) Math.sin(a0) * ry,
                    x + (float) Math.cos(a1) * rx, y + (float) Math.sin(a1) * ry, r, g, b, a);
        }
    }

    // the hopscotch's numbers, for the stage to write over the squares
    int labels;
    final float[] labelX = new float[64], labelY = new float[64], labelA = new float[64];
    final int[] labelN = new int[64];

    /** Builds the hopscotch over a darkened scene; the numbers go to the label arrays. */
    int buildChalk(Chalk ch, float aspect) {
        this.aspect = aspect;
        v = 0;
        labels = 0;
        float in = World.smooth(0f, 0.35f, ch.age);
        float hw = 0.5f, hh = aspect * 0.5f;
        quad(-hw, -hh, hw, -hh, hw, hh, -hw, hh, 0f, 0f, 0f, 0.9f * in);
        float h = Chalk.CELL * 0.5f;
        for (int row = ch.firstRow(); row <= ch.lastRow(); row++) {
            float y = ch.rowY(row);
            for (int k = 0; k < Chalk.width(row); k++) {
                int index = Chalk.firstOf(row) + k;
                if (index > ch.reached + Chalk.AHEAD) continue;
                float x = Chalk.cellX(row, k);
                // squares far up the strip fade into the black
                float far = 1f - World.smooth(hh - 0.25f, hh + 0.05f, y);
                float a = in * far;
                if (a <= 0.01f) continue;
                if (index > ch.reached) {
                    // locked: a faint outline, fainter the further it lies
                    float dim = 0.32f * (1f - (index - ch.reached - 1) / (float) Chalk.AHEAD);
                    frameAround(x, y, h, 0.006f, 1f, 0f, 0.6f, 0.6f, 0.65f, dim * a);
                    continue;
                }
                square(x, y, h, 1f, 0f, 0.03f, 0.03f, 0.045f, 0.9f * a);
                if (index == ch.reached) {
                    float pulse = 0.65f + 0.35f * (float) Math.sin(clock * 3.2f);
                    frameAround(x, y, h, 0.012f, 1f, 0f, 1f, 1f, 1f, pulse * a);
                } else {
                    // played: chalk in the colour of the oxide, a different tint for every square
                    float[] col = spectrum((index * 0.137f) % 0.9f);
                    frameAround(x, y, h, 0.009f, 1f, 0f, 0.45f + col[0] * 0.45f, 0.45f + col[1] * 0.45f,
                            0.48f + col[2] * 0.45f, 0.85f * a);
                }
                if (ch.byWatcher(index)) {
                    // the watcher's mark in the corner: it played this one
                    float mx = x + h * 0.62f, my = y + h * 0.62f;
                    oval(mx, my, h * 0.2f, h * 0.12f, 0.93f, 0.94f, 0.97f, 0.8f * a);
                    oval(mx, my, h * 0.07f, h * 0.07f, 0.1f, 0.12f, 0.2f, 0.9f * a);
                }
                if (index == ch.current) {
                    // where the drop is now: a small bead of quicksilver
                    oval(x - h * 0.62f, y - h * 0.62f, h * 0.09f, h * 0.09f, 0.85f, 0.88f, 0.95f, 0.9f * a);
                }
                if (labels < labelN.length) {
                    labelX[labels] = x;
                    labelY[labels] = y;
                    labelN[labels] = index + 1;
                    labelA[labels] = a * (index == ch.reached ? 1f : 0.85f);
                    labels++;
                }
            }
        }
        return v;
    }

    private void map(World w) {
        // the map sits in the top left corner and turns about the middle of the level,
        // so it stays inside its disc wherever the drop goes
        float lx = (w.cols - 1) * 0.5f, lz = (w.rows - 1) * 0.5f;
        float reach = (float) Math.sqrt(lx * lx + lz * lz) + 0.75f;
        float plate = 0.17f;
        float c = plate / reach;
        float mx = -0.5f + 0.035f + plate, my = aspect * 0.5f - 0.05f - plate;
        float cy = (float) Math.cos(w.yaw), sy = (float) Math.sin(w.yaw);
        // the map comes up after a turn, a tap or a mirror, and at the start, then fades away
        if (w.state == World.DARK || w.state == World.RISE || w.shift != 0) return;
        float fade = (w.state == World.PLAY || w.state == World.FALL_IN ? 1f : 0.4f)
                * (w.pilot != null ? 1f : 1f - World.smooth(2.2f, 2.9f, w.mapAge));
        if (fade <= 0.01f) return;
        // a dark disc under the map, so it reads over a bright island as well as over the black
        for (int k = 0; k < 24; k++) {
            double a0 = k * Math.PI / 12, a1 = (k + 1) * Math.PI / 12;
            tri(mx, my, mx + (float) Math.cos(a0) * plate, my + (float) Math.sin(a0) * plate,
                    mx + (float) Math.cos(a1) * plate, my + (float) Math.sin(a1) * plate, 0f, 0f, 0.01f, 0.5f * fade);
        }
        // floors below are drawn small and dim, the drop's own floor in full,
        // and floors above as bare outlines: a ceiling seen from under it
        int here = Math.max(0, w.state == World.PLAY ? w.cellF : w.toF);
        for (int f = 0; f < w.floors; f++) {
            int seen = 0;
            for (int i = 0; i < w.level.index(0, 0, f); i++) if (w.level.shard[i]) seen++;
            for (int z = 0; z < w.rows; z++)
                for (int x = 0; x < w.cols; x++) {
                    int i = w.level.index(x, z, f);
                    int t = w.type[i];
                    boolean isShard = w.level.shard[i];
                    // a mirage is nothing to the map: the map cannot be fooled
                    if (t == Level.LIQUID || t == Level.MIRAGE || w.sink[i] >= 0.5f) {
                        if (isShard) seen++;
                        continue;
                    }
                    float dx = x - lx, dz = z - lz;
                    float px = (dx * cy + dz * sy) * c + mx, py = (dx * sy - dz * cy) * c + my;
                    float r, g, b, a;
                    switch (t) {
                        case Level.GLASS: r = 0.55f; g = 0.75f; b = 0.85f; a = 0.42f; break;
                        case Level.SPRING: r = 0.3f; g = 0.62f; b = 0.95f; a = 0.6f; break;
                        case Level.HOT: r = 0.95f; g = 0.28f; b = 0.12f; a = 0.75f; break;
                        case Level.GATE:
                            if (w.open) { r = 1f; g = 1f; b = 1f; a = 0.9f; }
                            else { r = 0.6f; g = 0.6f; b = 0.62f; a = 0.5f; }
                            break;
                        default: r = 0.62f; g = 0.55f; b = 0.47f; a = 0.5f;
                    }
                    float k = f < here ? 0.4f : 1f;
                    if (f > here) frameAround(px, py, c * 0.46f, c * 0.06f, cy, sy, r, g, b, 0.55f * fade);
                    else square(px, py, c * (f < here ? 0.28f : 0.4f), cy, sy, r * k + 0.05f, g * k + 0.05f, b * k + 0.05f, a * fade);
                    if (isShard) {
                        if (w.shard[i]) {
                            float hue = w.shardsTotal > 1 ? 0.9f * seen / (w.shardsTotal - 1) : 0.3f;
                            float[] col = spectrum(hue);
                            square(px, py, c * 0.17f, cy, sy, col[0], col[1], col[2], (f == here ? 1f : 0.55f) * fade);
                        }
                        seen++;
                    }
                    if (w.bead[i]) square(px, py, c * 0.14f, 1f, 0f, 0.9f, 0.92f, 0.98f, (f == here ? 1f : 0.55f) * fade);
                    if (t == Level.SPRING && f <= here) {
                        // the spring's arrow, turned with the map
                        int aim = w.level.aim[i];
                        float wx = aim == 1 ? 1f : aim == 3 ? -1f : 0f, wz = aim == 0 ? -1f : aim == 2 ? 1f : 0f;
                        float ux = wx * cy + wz * sy, uy = wx * sy - wz * cy, vx = -uy, vy = ux, k2 = c * 0.3f;
                        tri(px + ux * k2, py + uy * k2, px - ux * k2 * 0.5f + vx * k2 * 0.7f, py - uy * k2 * 0.5f + vy * k2 * 0.7f,
                                px - ux * k2 * 0.5f - vx * k2 * 0.7f, py - uy * k2 * 0.5f - vy * k2 * 0.7f, 0.85f, 0.95f, 1f, 0.95f * fade);
                    }
                }
        }
        // where the next hop lands; red when a floor overhead will stop it
        if (w.state == World.PLAY) {
            int nx = w.nextX(), nz = w.nextZ();
            float dx = nx - lx, dz = nz - lz;
            float px = (dx * cy + dz * sy) * c + mx, py = (dx * sy - dz * cy) * c + my;
            boolean stay = nx == w.cellX && nz == w.cellZ;
            if (w.nextBlocked()) frameAround(px, py, c * 0.5f, c * 0.06f, cy, sy, 1f, 0.3f, 0.2f, 0.9f * fade);
            else frameAround(px, py, c * 0.5f, c * 0.06f, cy, sy, 1f, 1f, 1f, (stay ? 0.25f : 0.8f) * fade);
        }
        // which floor the drop is on: a short stack of bars beside the disc
        for (int f = 0; f < w.floors && w.floors > 1; f++) {
            float bx = mx + plate + 0.025f, by = my - plate * 0.5f + f * 0.03f;
            boolean on = f == here;
            quad(bx - 0.012f, by - 0.006f, bx + 0.012f, by - 0.006f, bx + 0.012f, by + 0.006f, bx - 0.012f, by + 0.006f,
                    1f, 1f, 1f, (on ? 0.9f : 0.25f) * fade);
        }
        // the drop itself: a small arrow pointing ahead
        float s = c * 0.32f;
        float ddx = w.x - lx, ddz = w.z - lz;
        float ax = (ddx * cy + ddz * sy) * c + mx, ay = (ddx * sy - ddz * cy) * c + my;
        tri(ax, ay + s, ax - s * 0.75f, ay - s * 0.7f, ax + s * 0.75f, ay - s * 0.7f, 0.95f, 0.97f, 1f, 0.95f * fade);
    }

    private void pips(World w) {
        if (w.state == World.DARK || w.state == World.RISE) return;
        int left = w.tremorLeft();
        if (left >= 0) {
            // the count before the ground shifts: three diamonds, one going out at every landing
            float y = aspect * 0.5f - 0.2f, pulse = 0.6f + 0.4f * (float) Math.sin(w.time * 9f);
            for (int k = 0; k < 3; k++) {
                boolean lit = k < left;
                square(-0.06f + k * 0.06f, y, lit ? 0.016f : 0.01f, 0.7071f, 0.7071f,
                        1f, lit ? 0.55f : 0.3f, lit ? 0.25f : 0.3f, lit ? pulse : 0.35f);
            }
        }
        int n = w.shardsTotal;
        float step = 0.045f, size = 0.011f;
        float y = aspect * 0.5f - 0.13f;
        float x0 = -(n - 1) * step * 0.5f - (w.beads > 0 ? step * 0.5f : 0f);
        for (int k = 0; k < n; k++) {
            boolean taken = k >= w.shardsLeft;
            float[] col = spectrum(n > 1 ? 0.9f * k / (n - 1) : 0.3f);
            square(x0 + k * step, y, taken ? size * 0.6f : size, 0.7071f, 0.7071f,
                    taken ? 0.35f : col[0], taken ? 0.35f : col[1], taken ? 0.38f : col[2], taken ? 0.5f : 0.95f);
        }
        if (w.beads > 0) square(x0 + n * step + 0.01f, y, size * 1.1f, 1f, 0f, 0.92f, 0.94f, 1f, 0.95f);
    }

    private void veil(World w) {
        float a = 0f, r = 0f, g = 0f, b = 0f;
        switch (w.state) {
            case World.DARK:
                a = 1f;
                break;
            case World.RISE:
                // the black lifts as the ring goes out over the liquid
                a = 1f - World.smooth(0f, 1.3f, w.stateAge);
                break;
            case World.DROWN:
                a = Math.min(1f, w.stateAge / 1.2f);
                break;
            case World.BURN: {
                float k = Math.min(1f, w.stateAge / 1.1f);
                r = 0.9f * (1f - k);
                g = 0.25f * (1f - k);
                b = 0.08f * (1f - k);
                a = Math.min(1f, 0.55f + k);
                break;
            }
            case World.FUNNEL:
                a = World.smooth(0.45f, 1.4f, w.stateAge);
                break;
            case World.WIN: {
                // up into white, then the white slowly goes out to the black the next level starts in
                float dusk = 1f - World.smooth(1.3f, 2.9f, w.stateAge);
                r = g = b = dusk;
                a = World.smooth(0f, 1.2f, w.stateAge);
                break;
            }
            default:
        }
        if (a <= 0f) return;
        float hw = 0.5f, hh = aspect * 0.5f;
        quad(-hw, -hh, hw, -hh, hw, hh, -hw, hh, r, g, b, a);
    }

    // ---- shapes ----

    /** A square of half-size h at (x, y), turned so its sides follow (c, s). */
    private void square(float x, float y, float h, float c, float s, float r, float g, float b, float a) {
        float ux = c * h, uy = s * h, wx = -s * h, wy = c * h;
        quad(x - ux - wx, y - uy - wy, x + ux - wx, y + uy - wy, x + ux + wx, y + uy + wy, x - ux + wx, y - uy + wy,
                r, g, b, a);
    }

    /** An outline of half-size h, line width l, turned like square(). */
    private void frameAround(float x, float y, float h, float l, float c, float s, float r, float g, float b, float a) {
        for (int k = 0; k < 4; k++) {
            float ang = k * (float) (Math.PI / 2);
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            // this side's outward direction and its run, both turned with the map
            float ox = ca * c - sa * s, oy = ca * s + sa * c;
            float tx = -oy, ty = ox;
            float mx = x + ox * (h - l * 0.5f), my = y + oy * (h - l * 0.5f);
            float hl = l * 0.5f;
            quad(mx - tx * h - ox * hl, my - ty * h - oy * hl, mx + tx * h - ox * hl, my + ty * h - oy * hl,
                    mx + tx * h + ox * hl, my + ty * h + oy * hl, mx - tx * h + ox * hl, my - ty * h + oy * hl,
                    r, g, b, a);
        }
    }

    private void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                      float r, float g, float b, float a) {
        tri(x0, y0, x1, y1, x2, y2, r, g, b, a);
        tri(x0, y0, x2, y2, x3, y3, r, g, b, a);
    }

    private void tri(float x0, float y0, float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        if ((v + 3) * FLOATS > out.length) return;
        put(x0, y0, r, g, b, a);
        put(x1, y1, r, g, b, a);
        put(x2, y2, r, g, b, a);
    }

    private void put(float x, float y, float r, float g, float b, float a) {
        int o = v * FLOATS;
        out[o] = x * 2f;
        out[o + 1] = y * 2f / aspect;
        out[o + 2] = r;
        out[o + 3] = g;
        out[o + 4] = b;
        out[o + 5] = a;
        v++;
    }

    /** The same rainbow the shards glow in, red to violet. */
    static float[] spectrum(float h) {
        float x = h * 0.8f;
        float[] o = new float[3];
        float[] off = {0f, 4f, 2f};
        for (int k = 0; k < 3; k++) {
            float m = (x * 6f + off[k]) % 6f;
            float c = Math.max(0f, Math.min(1f, Math.abs(m - 3f) - 1f));
            o[k] = c * c * (3f - 2f * c);
        }
        return o;
    }
}
