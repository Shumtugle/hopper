package com.shumtugle.hopper;

/**
 * Geometry, built in world space as flat triangles, eight floats a vertex:
 * position xyz, normal xyz, film thickness, shade.
 *
 * The shade slot also says what a face is made of:
 *   up to 2   bismuth, the number its brightness
 *   3         light of its own; the film slot holds the hue (above 1.5: white)
 *   5         quicksilver; with a film of LILAC or more, a rival's lilac
 *   6         hot bismuth, glowing from inside
 *   7         glass
 *   8         a cut face
 *   20 and up the same, on an island the cut passes through: clipped by the cut
 */
final class Mesh {
    static final int FLOATS = 8;
    /** In a quicksilver face's film slot: a rival's lilac, and the brighter lilac of one making for the gate. */
    static final float LILAC = 950f, LILAC_READY = 960f;
    /** The black drop's quicksilver, black with a burning rim. */
    static final float BLACK = 980f, BLACK_STARE = 990f;
    /** A cut face: bismuth sawn through and polished, pale with a pink sheen. */
    static final float FACE = 8f;
    /** Added to a shade: this face belongs to an island the cut passes through, and is clipped by it. */
    static final float CLIPPED = 20f;
    static final float GLOW = 3f, SILVER = 5f, HOT = 6f, GLASS = 7f;

    private static final int BOX = 36;
    /** Vertices one island can take: three steps, a rim of four and a wall or a stud, and their cut faces. */
    private static final int ISLAND = 8 * BOX + 8 * 6;

    private Mesh() {}

    static int islandCapacity(World w) {
        return w.type.length * ISLAND;
    }

    static int dynamicCapacity(World w) {
        return w.type.length * (3 * BOX + SPHERE) + 8 * BOX + 3 * SPHERE;
    }

    /**
     * The islands as they stand; rebuilt whenever one changes for good or is going under.
     * The ground floor rises out of the liquid as stepped columns; the floors above float as
     * slabs with stepped hoppers hanging under them, so they read from below as well as above.
     */
    static int islands(World w, float[] out) {
        int v = 0;
        for (int f = 0; f < w.floors; f++)
            for (int z = 0; z < w.rows; z++)
                for (int x = 0; x < w.cols; x++) {
                    int i = w.level.index(x, z, f);
                    int t = w.type[i];
                    if (t == Level.LIQUID || w.sink[i] >= 1f) continue;
                    int first = v;
                    // every island has its own oxide colour, a little off its neighbours'
                    float jitter = hash(x * 7 + z * 13 + f * 29) * 40f;
                    float h = World.top(f);
                    if (t == Level.GLASS) {
                        float bottom = f == 0 ? -0.9f : h - 0.12f;
                        v = box(out, v, x, (h + bottom) * 0.5f, z, 0.37f, (h - bottom) * 0.5f, 0.37f, 160f + jitter, GLASS);
                        v = rim(out, v, x, h, z, 0.37f, 0.03f, 0.04f, 210f + jitter, GLASS);
                    } else {
                        // a mirage is dressed exactly like a plain island; the mirror standing on it gives it away
                        float film = (t == Level.SPRING ? 520f : t == Level.GATE ? 330f : 250f) + jitter + f * 60f;
                        float shade = t == Level.HOT ? HOT : 1f;
                        // an island taken out of turn turns into a funnel: its steps open upwards and go dark
                        float m = i == w.funnel && w.state == World.FUNNEL ? World.smooth(0f, 0.7f, w.stateAge) : 0f;
                        if (m > 0f) shade *= 1f - 0.85f * m;
                        for (int k = 0; k < 3; k++) {
                            float a, top, bottom;
                            if (f == 0) {
                                a = 0.26f + k * 0.065f;
                                top = h - k * 0.085f;
                                bottom = -1.6f;
                                a += (0.39f - k * 0.07f - a) * m;
                                top += (h - k * 0.1f - top) * m;
                            } else {
                                // hanging steps: the widest at the top, narrowing downwards
                                a = 0.39f - k * 0.07f;
                                top = h - k * 0.1f;
                                bottom = top - 0.1f;
                            }
                            v = box(out, v, x, (top + bottom) * 0.5f, z, a, (top - bottom) * 0.5f, a,
                                    film + k * 26f, t == Level.HOT ? HOT : shade * (1f - k * 0.12f));
                        }
                        float rimSize = f == 0 ? 0.26f + (0.39f - 0.26f) * m : 0.39f;
                        v = rim(out, v, x, h, z, rimSize, 0.045f, 0.05f, film - 40f, t == Level.HOT ? HOT : 1.05f);
                    }
                    if (w.cut && !w.cutAway[i] && w.sink[i] <= 0f) {
                        float c = w.cutX * x + w.cutZ * z + w.cutD;
                        if (c > -0.5f) v = sawn(w, out, first, v);
                    }
                    if (w.rise < 1f) {
                        // the level rising out of the liquid at its start, or an act giving way to the next
                        float up = w.under(f, i);
                        for (int k = first; k < v; k++) out[k * FLOATS + 1] -= up;
                    }
                    if (w.tremor > 0f && i != w.keep) {
                        // before a shift every island shudders, each a little out of step
                        float shake = (float) Math.sin(w.time * 31f + i * 1.7f) * 0.07f * w.tremor;
                        for (int k = first; k < v; k++) out[k * FLOATS + 1] += shake;
                    }
                    if (w.sink[i] > 0f) {
                        // going under: down, darkening to the black of the liquid
                        float s = w.sink[i], drop = s * s * (1.8f + h), dim = (1f - s) * (1f - s);
                        // what the cut sent away tips over the edge as it goes
                        float tip = w.cutAway != null && w.cutAway[i] ? s * s * 1.4f : 0f;
                        for (int k = first; k < v; k++) {
                            int o = k * FLOATS;
                            out[o] += w.cutX * tip;
                            out[o + 1] -= drop;
                            out[o + 2] += w.cutZ * tip;
                            if (out[o + 7] < 2f) out[o + 7] *= dim;
                        }
                    }
                }
        return v;
    }

    /**
     * Things that move every frame: shards, beads, the gate's ring, the springs' studs, and the
     * drop's reflection in every mirage.
     */
    static int moving(World w, float[] out) {
        int v = 0;
        float time = w.time;
        int seen = 0;
        if (w.lift > 0.05f) {
            // seen from above through a shift, the drop itself is there to be found
            v = sphere(out, v, w.x, w.y + DROP, w.z, DROP);
        }
        if (w.boss != null && !w.boss.away) {
            // the black drop
            int first = v;
            v = sphere(out, v, w.boss.x, w.boss.y + Boss.RADIUS, w.boss.z, Boss.RADIUS);
            for (int k = first; k < v; k++) out[k * FLOATS + 6] = w.boss.staring ? BLACK_STARE : BLACK;
        }
        if (w.rivals != null) {
            // the lilac drops racing the drop to the gate; one that has all its shards burns brighter
            for (Rival r : w.rivals) {
                if (!r.shown()) continue;
                int first = v;
                v = sphere(out, v, r.w.x, r.w.y + DROP, r.w.z, DROP);
                for (int k = first; k < v; k++) out[k * FLOATS + 6] = r.ready ? LILAC_READY : LILAC;
            }
        }
        for (int f = 0; f < w.floors; f++)
            for (int z = 0; z < w.rows; z++)
                for (int x = 0; x < w.cols; x++) {
                    int i = w.level.index(x, z, f);
                    if (w.sink[i] > 0f) continue;
                    float base = World.top(f) - w.under(f, i);
                    if (w.type[i] == Level.MIRAGE) continue;
                    if (w.level.shard[i]) {
                        if (w.shard[i]) {
                            float hue = w.shardsTotal > 1 ? 0.9f * seen / (w.shardsTotal - 1) : 0.3f;
                            float y = base + 0.3f + 0.03f * (float) Math.sin(time * 2.1f + i);
                            float size = 0.055f;
                            if (w.level.order != null && w.level.order[i] >= 0) {
                                // in a level with an order each shard beats at its own pace, the lowest note slowest
                                float period = World.humPeriod(w.level.order[i]);
                                float phase = (time % period) / period, beat = Math.max(0f, 1f - phase * 4f);
                                size *= 1f + 0.6f * beat * beat;
                            }
                            // the wrong shard goes out at once, before the funnel takes the island
                            if (i == w.funnel && w.state == World.FUNNEL) {
                                size *= Math.max(0f, 1f - w.stateAge / 0.1f);
                                if (size <= 0f) { seen++; continue; }
                            }
                            v = spunBox(out, v, x, y, z, size, time * 1.3f + i, 0.62f, hue, GLOW);
                        }
                        seen++;
                    }
                    if (w.bead[i]) {
                        float y = base + 0.13f + 0.02f * (float) Math.sin(time * 3f + i);
                        v = sphere(out, v, x, y, z, 0.075f);
                    }
                    if (w.type[i] == Level.SPRING) {
                        float press = w.dent[i];
                        v = box(out, v, x, base + 0.02f - press * 0.05f, z, 0.1f, 0.05f, 0.1f, 470f, 1.1f);
                        // a glowing arrow on the stud: the way this spring throws
                        int aim = w.level.aim[i];
                        float dx = aim == 1 ? 1f : aim == 3 ? -1f : 0f, dz = aim == 0 ? -1f : aim == 2 ? 1f : 0f;
                        float px = -dz, pz = dx, ay = base + 0.085f - press * 0.05f;
                        float tx = x + dx * 0.17f, tz = z + dz * 0.17f;
                        for (int s = -1; s <= 1; s += 2) {
                            float ax = (-dx + s * px) * 0.7071f, az = (-dz + s * pz) * 0.7071f;
                            float[] fr = frame(ax, 0f, az, tx + ax * 0.08f, ay, tz + az * 0.08f);
                            v = boxIn(out, v, fr, 0, 0, 0, 0.09f, 0.018f, 0.022f, 0.62f, GLOW);
                        }
                    }
                    if (w.type[i] == Level.GATE) {
                        float y = base + 0.8f + 0.05f * (float) Math.sin(time * 1.4f);
                        float spin = time * (w.open ? 0.9f : 0.25f);
                        float tag = w.open ? GLOW : 0.55f, film = w.open ? 2f : 300f;
                        float a = 0.27f, b = 0.035f;
                        // a hollow square hanging level above the gate, turning slowly
                        for (int k = 0; k < 4; k++) {
                            float ang = spin + k * (float) (Math.PI / 2);
                            float ox = (float) Math.cos(ang) * (a - b), oz = (float) Math.sin(ang) * (a - b);
                            float[] fr = frame((float) -Math.sin(ang), 0f, (float) Math.cos(ang), x + ox, y, z + oz);
                            v = boxIn(out, v, fr, 0, 0, 0, a, b, b, film, tag);
                        }
                        if (w.open) {
                            // a thin column of light that can be seen from anywhere
                            v = box(out, v, x, base + 4f, z, 0.012f, 3.4f, 0.012f, 2f, GLOW);
                        }
                    }
                }
        return v;
    }

    /**
     * The mirror on every mirage: an upright sheet of quicksilver, always turned to the drop.
     * Its corners carry where they lie across the sheet (-1 to 1) and up it (0 to 1); the
     * mirage shader finds the drop in it and makes it waver.
     */
    static int mirages(World w, float[] out) {
        int v = 0;
        for (int f = 0; f < w.floors; f++)
            for (int z = 0; z < w.rows; z++)
                for (int x = 0; x < w.cols; x++) {
                    int i = w.level.index(x, z, f);
                    if (w.type[i] != Level.MIRAGE || w.sink[i] > 0f) continue;
                    float dx = w.x - x, dz = w.z - z, l = (float) Math.sqrt(dx * dx + dz * dz);
                    if (l < 0.05f) { dx = 0f; dz = 1f; l = 1f; }
                    float nx = dx / l, nz = dz / l, sx = -nz * SHEET_WIDE, sz = nx * SHEET_WIDE;
                    float bottom = World.top(f) - w.under(f, i) + 0.04f, top = bottom + SHEET_HIGH;
                    v = vert(out, v, x - sx, bottom, z - sz, nx, 0f, nz, -1f, 0f);
                    v = vert(out, v, x + sx, bottom, z + sz, nx, 0f, nz, 1f, 0f);
                    v = vert(out, v, x + sx, top, z + sz, nx, 0f, nz, 1f, 1f);
                    v = vert(out, v, x - sx, bottom, z - sz, nx, 0f, nz, -1f, 0f);
                    v = vert(out, v, x + sx, top, z + sz, nx, 0f, nz, 1f, 1f);
                    v = vert(out, v, x - sx, top, z - sz, nx, 0f, nz, -1f, 1f);
                }
        return v;
    }

    static int mirageCapacity(World w) {
        return w.type.length * 6;
    }

    /** Half the width of a mirage's mirror, and its height. */
    private static final float SHEET_WIDE = 0.27f, SHEET_HIGH = 0.95f;

    /** The drop's radius; the mirage shader looks for a ball this size where the drop is. */
    static final float DROP = 0.14f;

    /**
     * An island the cut passes through: every box of it is marked to be clipped by the cut, and
     * where the plane goes through a box, a flat face closes it, so the island shows its steps
     * in section like a sawn stone. The boxes are the island's own, BOX vertices each.
     */
    private static int sawn(World w, float[] out, int first, int v) {
        float nx = w.cutX, nz = w.cutZ, d = w.cutD;
        // the cut line, as a point on it and a direction along it
        float ox = -nx * d, oz = -nz * d, ux = -nz, uz = nx;
        int end = v;
        for (int b = first; b + BOX <= end; b += BOX) {
            float x0 = 1e9f, x1 = -1e9f, y0 = 1e9f, y1 = -1e9f, z0 = 1e9f, z1 = -1e9f;
            for (int k = b; k < b + BOX; k++) {
                int o = k * FLOATS;
                x0 = Math.min(x0, out[o]); x1 = Math.max(x1, out[o]);
                y0 = Math.min(y0, out[o + 1]); y1 = Math.max(y1, out[o + 1]);
                z0 = Math.min(z0, out[o + 2]); z1 = Math.max(z1, out[o + 2]);
                out[o + 7] += CLIPPED;
            }
            // where the line crosses the box's footprint
            float t0 = -1e9f, t1 = 1e9f;
            float[] lo = {x0, z0}, hi = {x1, z1}, org = {ox, oz}, dir = {ux, uz};
            boolean miss = false;
            for (int a = 0; a < 2 && !miss; a++) {
                if (Math.abs(dir[a]) < 1e-6f) {
                    if (org[a] < lo[a] || org[a] > hi[a]) miss = true;
                    continue;
                }
                float ta = (lo[a] - org[a]) / dir[a], tb = (hi[a] - org[a]) / dir[a];
                t0 = Math.max(t0, Math.min(ta, tb));
                t1 = Math.min(t1, Math.max(ta, tb));
            }
            if (miss || t1 <= t0) continue;
            float film = out[b * FLOATS + 6];
            // a hair on the near side of the plane, facing the side that went away
            float ax = ox + ux * t0 - nx * 0.002f, az = oz + uz * t0 - nz * 0.002f;
            float bx = ox + ux * t1 - nx * 0.002f, bz = oz + uz * t1 - nz * 0.002f;
            v = vert(out, v, ax, y0, az, nx, 0f, nz, film, FACE);
            v = vert(out, v, bx, y0, bz, nx, 0f, nz, film, FACE);
            v = vert(out, v, bx, y1, bz, nx, 0f, nz, film, FACE);
            v = vert(out, v, ax, y0, az, nx, 0f, nz, film, FACE);
            v = vert(out, v, bx, y1, bz, nx, 0f, nz, film, FACE);
            v = vert(out, v, ax, y1, az, nx, 0f, nz, film, FACE);
        }
        return v;
    }

    /** Huge hoppers far out in the liquid, so the eye has a horizon to hold on to. */
    static int distance(World w, float[] out) {
        int v = 0;
        float cx = (w.cols - 1) * 0.5f, cz = (w.rows - 1) * 0.5f;
        for (int k = 0; k < 9; k++) {
            float ang = k * 2.399963f + 0.4f;
            float r = 17f + 9f * hash(k * 31);
            float x = cx + (float) Math.cos(ang) * r, z = cz + (float) Math.sin(ang) * r;
            float size = 1.6f + 2.6f * hash(k * 17 + 5);
            float tall = 0.6f + 3.2f * hash(k * 11 + 3);
            float turn = hash(k * 5 + 1) * 1.57f;
            float c = (float) Math.cos(turn), s = (float) Math.sin(turn);
            float film = 200f + 260f * hash(k * 3 + 9);
            for (int j = 0; j < 4; j++) {
                float a = size * (1f - j * 0.2f), top = tall * (1f - j * 0.12f) - j * 0.0f;
                float hy = (top + 2f) * 0.5f;
                float[] f = frame(c, 0f, s, x, top - hy, z);
                v = boxIn(out, v, f, 0, 0, 0, a, hy, a, film + j * 30f, 0.9f - j * 0.12f);
            }
        }
        return v;
    }

    static int distanceCapacity() {
        return 9 * 4 * BOX;
    }

    // ---- shapes ----

    /** A square frame lying flat on y0 around a cell, its outer half-size a, bar width b, height h. */
    static int rim(float[] out, int v, float x, float y0, float z, float a, float b, float h, float film, float shade) {
        float hy = h * 0.5f, cy = y0 + hy;
        v = box(out, v, x, cy, z - (a - b * 0.5f), a, hy, b * 0.5f, film, shade);
        v = box(out, v, x, cy, z + (a - b * 0.5f), a, hy, b * 0.5f, film, shade);
        v = box(out, v, x - (a - b * 0.5f), cy, z, b * 0.5f, hy, a - b, film, shade);
        v = box(out, v, x + (a - b * 0.5f), cy, z, b * 0.5f, hy, a - b, film, shade);
        return v;
    }

    private static final float[] AXES = frame(1f, 0f, 0f, 0f, 0f, 0f);

    /** An axis-aligned box centred at (x, y, z) with half extents hx, hy, hz. */
    static int box(float[] out, int v, float x, float y, float z, float hx, float hy, float hz,
                           float film, float shade) {
        return boxIn(out, v, AXES, x, y, z, hx, hy, hz, film, shade);
    }

    /** A cube turned about y by yaw and tipped by tilt, so it stands on an edge. */
    static int spunBox(float[] out, int v, float x, float y, float z, float h, float yaw, float tilt,
                               float film, float shade) {
        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
        float ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);
        // right tipped up by tilt, then everything turned by yaw
        float[] f = new float[12];
        f[0] = cy * ct; f[1] = st; f[2] = sy * ct;
        f[3] = -cy * st; f[4] = ct; f[5] = -sy * st;
        f[6] = -sy; f[7] = 0f; f[8] = cy;
        f[9] = x; f[10] = y; f[11] = z;
        return boxIn(out, v, f, 0, 0, 0, h, h, h, film, shade);
    }

    /** A frame whose right axis is (rx, ry, rz) in the ground plane, up is +y, origin (ox, oy, oz). */
    static float[] frame(float rx, float ry, float rz, float ox, float oy, float oz) {
        float l = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= l; ry /= l; rz /= l;
        // forward = right x up
        float fx = -rz, fz = rx;
        return new float[] {rx, ry, rz, 0f, 1f, 0f, fx, 0f, fz, ox, oy, oz};
    }

    /** A box centred at (x, y, z) in the frame, with half extents along right, up and forward. */
    static int boxIn(float[] out, int v, float[] f, float x, float y, float z,
                             float hx, float hy, float hz, float film, float shade) {
        float cx = f[9] + f[0] * x + f[3] * y + f[6] * z;
        float cy = f[10] + f[1] * x + f[4] * y + f[7] * z;
        float cz = f[11] + f[2] * x + f[5] * y + f[8] * z;
        float[] half = {hx, hy, hz};
        for (int axis = 0; axis < 3; axis++) {
            int ua = (axis + 1) % 3, va = (axis + 2) % 3;
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                float nx = f[axis * 3] * sgn, ny = f[axis * 3 + 1] * sgn, nz = f[axis * 3 + 2] * sgn;
                float px = cx + nx * half[axis], py = cy + ny * half[axis], pz = cz + nz * half[axis];
                float ux = f[ua * 3] * half[ua], uy = f[ua * 3 + 1] * half[ua], uz = f[ua * 3 + 2] * half[ua];
                float wx = f[va * 3] * half[va], wy = f[va * 3 + 1] * half[va], wz = f[va * 3 + 2] * half[va];
                v = vert(out, v, px - ux - wx, py - uy - wy, pz - uz - wz, nx, ny, nz, film, shade);
                v = vert(out, v, px + ux - wx, py + uy - wy, pz + uz - wz, nx, ny, nz, film, shade);
                v = vert(out, v, px + ux + wx, py + uy + wy, pz + uz + wz, nx, ny, nz, film, shade);
                v = vert(out, v, px - ux - wx, py - uy - wy, pz - uz - wz, nx, ny, nz, film, shade);
                v = vert(out, v, px + ux + wx, py + uy + wy, pz + uz + wz, nx, ny, nz, film, shade);
                v = vert(out, v, px - ux + wx, py - uy + wy, pz - uz + wz, nx, ny, nz, film, shade);
            }
        }
        return v;
    }

    // ---- a ball of quicksilver ----

    private static final float[] BALL = makeBall();
    private static final int SPHERE = BALL.length / 3;

    private static float[] makeBall() {
        // an octahedron split twice and pushed out onto the unit sphere
        float[][] t = {
            {1, 0, 0, 0, 1, 0, 0, 0, 1}, {0, 1, 0, -1, 0, 0, 0, 0, 1},
            {-1, 0, 0, 0, -1, 0, 0, 0, 1}, {0, -1, 0, 1, 0, 0, 0, 0, 1},
            {0, 1, 0, 1, 0, 0, 0, 0, -1}, {-1, 0, 0, 0, 1, 0, 0, 0, -1},
            {0, -1, 0, -1, 0, 0, 0, 0, -1}, {1, 0, 0, 0, -1, 0, 0, 0, -1}};
        for (int pass = 0; pass < 2; pass++) {
            float[][] n = new float[t.length * 4][];
            int k = 0;
            for (float[] a : t) {
                float[] p = a, m01 = mid(p, 0, 3), m12 = mid(p, 3, 6), m20 = mid(p, 6, 0);
                n[k++] = tri(p[0], p[1], p[2], m01[0], m01[1], m01[2], m20[0], m20[1], m20[2]);
                n[k++] = tri(m01[0], m01[1], m01[2], p[3], p[4], p[5], m12[0], m12[1], m12[2]);
                n[k++] = tri(m20[0], m20[1], m20[2], m12[0], m12[1], m12[2], p[6], p[7], p[8]);
                n[k++] = tri(m01[0], m01[1], m01[2], m12[0], m12[1], m12[2], m20[0], m20[1], m20[2]);
            }
            t = n;
        }
        float[] out = new float[t.length * 9];
        for (int i = 0; i < t.length; i++) System.arraycopy(t[i], 0, out, i * 9, 9);
        return out;
    }

    private static float[] mid(float[] p, int a, int b) {
        float x = p[a] + p[b], y = p[a + 1] + p[b + 1], z = p[a + 2] + p[b + 2];
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[] {x / l, y / l, z / l};
    }

    private static float[] tri(float... p) {
        return p;
    }

    static int sphere(float[] out, int v, float x, float y, float z, float r) {
        for (int i = 0; i < SPHERE; i++) {
            float nx = BALL[i * 3], ny = BALL[i * 3 + 1], nz = BALL[i * 3 + 2];
            v = vert(out, v, x + nx * r, y + ny * r, z + nz * r, nx, ny, nz, 120f, SILVER);
        }
        return v;
    }

    static int vert(float[] out, int v, float x, float y, float z,
                            float nx, float ny, float nz, float film, float shade) {
        int o = v * FLOATS;
        out[o] = x; out[o + 1] = y; out[o + 2] = z;
        out[o + 3] = nx; out[o + 4] = ny; out[o + 5] = nz;
        out[o + 6] = film; out[o + 7] = shade;
        return v + 1;
    }

    /** A steady pseudo-random number in [0, 1) for an integer. */
    static float hash(int n) {
        n = (n << 13) ^ n;
        int m = (n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff;
        return m / 2147483648f;
    }
}
