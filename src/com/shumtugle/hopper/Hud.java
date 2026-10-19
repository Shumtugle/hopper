package com.shumtugle.hopper;

/**
 * Flat shapes drawn over the scene: a small map in the top left corner that turns with the drop, so "up" on it is
 * always "ahead"; a row of pips for the shards; a veil for falling in and out of a level.
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
        return (w.type.length * 3 + 80) * 6;
    }

    /** Builds this frame's shapes; returns the vertex count. aspect is screen height over width. */
    int build(World w, float aspect) {
        this.aspect = aspect;
        v = 0;
        map(w);
        pips(w);
        veil(w);
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
        if (w.state == World.DARK || w.state == World.RISE) return;
        float fade = (w.state == World.PLAY || w.state == World.FALL_IN ? 1f : 0.4f)
                * (1f - World.smooth(2.2f, 2.9f, w.mapAge));
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
