package com.shumtugle.hopper;

import java.util.ArrayList;
import java.util.Random;

/**
 * The world that grows out of the black, one piece for every level won for the first time.
 *
 * It starts as a single hopper crystal. Every next piece grows out of one of the pieces before
 * it, the newer ones likelier, so the world reaches outwards instead of thickening: crystals
 * first, then glades, branches and trees, cubes, houses, whole streets; now and then, without
 * warning, a precious thing: a burning gem, a funnel full of light, a prism.
 *
 * Everything follows from the number of pieces alone, so nothing but that number is ever saved:
 * the same count always grows the same world. Pieces stand on their own plinths, which go down
 * into the liquid like the islands of a level; the ground is the plane y = 0.
 */
final class City {
    static final int CRYSTAL = 0, GLADE = 1, BRANCH = 2, TREE = 3, CUBE = 4, HOUSE = 5, STREET = 6,
            GEM = 7, FUNNEL = 8, PRISM = 9;

    static final class Piece {
        int kind;
        /** Where its base stands: on the ground, or on top of a glade. */
        float x, y, z;
        /** The radius of its footprint on the ground. */
        float r;
        /** Its size, half a width for most kinds. */
        float size;
        /** Its turn about the vertical, for the kinds that turn. */
        float yaw;
        float film;
        /** A steady random number for its details. */
        int seed;
        /** For a street, its direction; for a glade, how many pieces already stand on it. */
        float dx, dz;
        int held;
        /** The glade it stands on, or null for the ground. */
        Piece on;
        /** How tall it stands above its base. */
        float height;
        /** A house's storeys: one at first, towers of several as the world grows older. */
        int tiers = 1;

        boolean precious() {
            return kind >= GEM;
        }
    }

    final ArrayList<Piece> pieces = new ArrayList<Piece>();
    private final Random rnd = new Random(0x5eedc17L);

    /** Grows the world up to this many pieces. */
    City(int count) {
        for (int k = 0; k < count; k++) pieces.add(grow(k));
    }

    int size() {
        return pieces.size();
    }

    /** The kind of the k-th piece: the list widens as the world grows, and a few are precious. */
    private int kindOf(int k) {
        if (k == 0) return CRYSTAL;
        if (k >= 3 && Mesh.hash(k * 97 + 13) < 0.15f) {
            float h = Mesh.hash(k * 31 + 7);
            return h < 0.4f ? GEM : h < 0.7f ? FUNNEL : PRISM;
        }
        float[] weight = {
            3f,
            k >= 4 ? 2f : 0f,
            k >= 7 ? 1.6f : 0f,
            k >= 10 ? 2f : 0f,
            k >= 12 ? 1.4f : 0f,
            k >= 15 ? 3f : 0f,
            k >= 22 ? 1.8f : 0f};
        float sum = 0f;
        for (float w : weight) sum += w;
        float pick = rnd.nextFloat() * sum;
        for (int i = 0; i < weight.length; i++) {
            pick -= weight[i];
            if (pick < 0f) return i;
        }
        return CRYSTAL;
    }

    private Piece grow(int k) {
        Piece p = new Piece();
        p.kind = kindOf(k);
        p.seed = k * 7919 + 17;
        p.yaw = rnd.nextFloat() * 6.2832f;
        switch (p.kind) {
            case CRYSTAL: p.size = 0.3f + 0.35f * rnd.nextFloat(); p.r = p.size * 1.05f; break;
            case GLADE: p.size = 0.6f + 0.35f * rnd.nextFloat(); p.r = p.size; break;
            case BRANCH: p.size = 0.16f; p.r = 0.5f; break;
            case TREE: p.size = 0.9f + 0.5f * rnd.nextFloat(); p.r = 0.38f; break;
            case CUBE: p.size = 0.22f + 0.12f * rnd.nextFloat(); p.r = p.size * 1.5f; break;
            case HOUSE:
                p.size = 0.26f + 0.1f * rnd.nextFloat();
                p.r = p.size * 1.35f;
                // the older the world, the taller its houses may stand
                if (k >= 25) p.tiers = 1 + (int) (rnd.nextFloat() * Math.min(8f, k / 18f));
                break;
            case STREET: p.held = 3 + rnd.nextInt(3); p.size = 0.25f; p.r = p.held * 0.42f; break;
            default: p.size = 0.25f; p.r = 0.36f;
        }
        p.film = k == 0 ? 80f : 60f + ((k * 53) % 240);
        if (k == 0) return p;

        // Like a crystal in a solution, the world grows where it is thickest: of the places
        // that are free next to a piece, it takes the one nearest the middle. So it stays round
        // and close, and reaches outwards only as fast as it fills in.
        boolean small = p.kind == CRYSTAL || p.kind == TREE || p.kind == HOUSE || p.kind == CUBE || p.precious();
        float mx = 0f, mz = 0f, mw = 0f;
        for (Piece q : pieces) {
            if (q.on != null) continue;
            mx += q.x * q.r; mz += q.z * q.r; mw += q.r;
        }
        mx /= mw;
        mz /= mw;
        int n = pieces.size();
        Piece bestOn = null;
        float bestX = 0f, bestZ = 0f, bestScore = Float.MAX_VALUE, bestA = 0f;
        for (int tries = 0; tries < 48; tries++) {
            int pi = rnd.nextInt(n);
            Piece parent = pieces.get(pi);
            if (small && parent.held < (parent.kind == GLADE ? 3 : 1) && rnd.nextFloat() < 0.5f
                    && (parent.kind == GLADE && parent.r > p.r * 1.6f
                        || parent.kind == CRYSTAL && parent.size > p.r * 1.25f && p.kind != HOUSE && p.kind != TREE)) {
                // on a glade, clear of what already stands there; or on top of a big crystal
                float px = parent.x, pz = parent.z;
                if (parent.kind == GLADE) {
                    float a = rnd.nextFloat() * 6.2832f, d = (parent.r - p.r - 0.06f) * (float) Math.sqrt(rnd.nextFloat());
                    px += (float) Math.cos(a) * d;
                    pz += (float) Math.sin(a) * d;
                    if (crowdedOn(parent, px, pz, p.r)) continue;
                }
                // standing on something counts as near: it is how the world grows up
                float score = (float) Math.hypot(px - mx, pz - mz) * 0.7f + rnd.nextFloat() * 0.6f;
                if (score < bestScore) {
                    bestScore = score; bestOn = parent; bestX = px; bestZ = pz;
                }
                continue;
            }
            float a = rnd.nextFloat() * 6.2832f;
            float d = parent.r + p.r + 0.08f + rnd.nextFloat() * 0.12f;
            float px = parent.x + (float) Math.cos(a) * d, pz = parent.z + (float) Math.sin(a) * d;
            if (crowded(px, pz, p.r)) continue;
            float score = (float) Math.hypot(px - mx, pz - mz) + rnd.nextFloat() * 0.6f;
            if (score < bestScore) {
                bestScore = score; bestOn = null; bestX = px; bestZ = pz; bestA = a;
            }
        }
        if (bestScore == Float.MAX_VALUE) {
            // nowhere free: just past the edge, in a direction of its own
            float a = rnd.nextFloat() * 6.2832f, far = reach();
            bestX = mx + (float) Math.cos(a) * (far + p.r + 0.1f);
            bestZ = mz + (float) Math.sin(a) * (far + p.r + 0.1f);
            bestA = a;
        }
        p.x = bestX;
        p.z = bestZ;
        if (bestOn != null) {
            p.on = bestOn;
            p.y = bestOn.y + bestOn.height;
            bestOn.held++;
        }
        if (p.kind == STREET) {
            // a street runs across the way it grew, so it lies along its neighbour
            p.dx = (float) -Math.sin(bestA);
            p.dz = (float) Math.cos(bestA);
        }
        finish(p);
        return p;
    }

    private void finish(Piece p) {
        switch (p.kind) {
            case CRYSTAL: p.height = p.size * 0.95f; break;
            case GLADE: p.height = 0.16f; break;
            case TREE: p.height = p.size + 0.3f; break;
            case HOUSE: p.height = houseHeight(p.size, p.tiers, p.seed); break;
            case PRISM: p.height = 1.2f; break;
            default: p.height = 0.6f;
        }
    }

    private boolean crowded(float x, float z, float r) {
        for (Piece q : pieces) {
            if (q.on != null) continue;
            float dx = q.x - x, dz = q.z - z, lim = q.r + r + 0.08f;
            if (dx * dx + dz * dz < lim * lim) return true;
        }
        return false;
    }

    private boolean crowdedOn(Piece glade, float x, float z, float r) {
        for (Piece q : pieces) {
            if (q.on != glade) continue;
            float dx = q.x - x, dz = q.z - z, lim = q.r + r + 0.05f;
            if (dx * dx + dz * dz < lim * lim) return true;
        }
        return false;
    }

    /** How far the world reaches from its first crystal, footprints included. */
    float reach() {
        float far = 1f;
        for (Piece p : pieces) far = Math.max(far, (float) Math.hypot(p.x, p.z) + p.r);
        return far;
    }

    /** The highest point of the world. */
    float tallest() {
        float top = 1f;
        for (Piece p : pieces) top = Math.max(top, p.y + p.height);
        return top;
    }

    // ---- geometry ----

    /** Vertices one piece can take, at most. */
    static final int PIECE = 128 * 36;

    /** Room for one piece, to build into before it is copied out. */
    private final float[] scratch = new float[PIECE * Mesh.FLOATS];

    /**
     * Pieces from up to to, full grown, in one array exactly as long as they need; ox, oz move
     * the world to where it stands. The vertex count is the length over Mesh.FLOATS.
     */
    float[] build(int from, int to, float ox, float oz) {
        return build(from, to, ox, oz, null);
    }

    /** The same, writing where each piece ends, in vertices, into ends[i - from]. */
    float[] build(int from, int to, float ox, float oz, int[] ends) {
        float[] out = new float[0];
        int floats = 0;
        for (int i = from; i < to; i++) {
            int n = piece(pieces.get(i), scratch, 0, ox, oz, 1f, 0f) * Mesh.FLOATS;
            if (ends != null) ends[i - from] = (floats + n) / Mesh.FLOATS;
            if (floats + n > out.length) {
                float[] more = new float[Math.max(out.length * 2, floats + n)];
                System.arraycopy(out, 0, more, 0, floats);
                out = more;
            }
            System.arraycopy(scratch, 0, out, floats, n);
            floats += n;
        }
        if (floats == out.length) return out;
        float[] exact = new float[floats];
        System.arraycopy(out, 0, exact, 0, floats);
        return exact;
    }

    /**
     * One piece growing: at grown 0 it is a point on the black, at 1 it stands whole. Sink takes
     * it down into the liquid and darkens it.
     */
    int piece(Piece p, float[] out, int v, float ox, float oz, float grown, float sink) {
        int first = v;
        float x = p.x + ox, z = p.z + oz, y = p.y;
        if (p.on == null) v = plinth(out, v, x, z, p.kind == STREET ? p : null, p.r * 0.92f, p.film);
        switch (p.kind) {
            case CRYSTAL: v = hopper(out, v, x, y, z, p.size, p.film, 1f, p.seed); break;
            case GLADE: v = glade(out, v, x, y, z, p); break;
            case BRANCH: v = branch(out, v, x, y, z, p); break;
            case TREE: v = tree(out, v, x, y, z, p); break;
            case CUBE: v = cube(out, v, x, y, z, p); break;
            case HOUSE: v = house(out, v, x, y, z, p.size, p.yaw, p.film, p.seed, p.tiers); break;
            case STREET: v = street(out, v, x, y, z, p); break;
            case GEM: v = gem(out, v, x, y, z, p); break;
            case FUNNEL: v = funnel(out, v, x, y, z, p); break;
            default: v = prism(out, v, x, y, z, p);
        }
        if (grown < 1f || sink > 0f) {
            // grown out of a point on the black; sunk back down into it
            float g = Math.max(0f, grown), down = sink * sink * (p.y + p.height + 2f);
            float dim = (1f - sink) * (1f - sink);
            for (int k = first; k < v; k++) {
                int o = k * Mesh.FLOATS;
                out[o] = x + (out[o] - x) * g;
                out[o + 1] = World.LIQUID_Y + (out[o + 1] - World.LIQUID_Y) * g - down;
                out[o + 2] = z + (out[o + 2] - z) * g;
                if (out[o + 7] < 2f) out[o + 7] *= dim;
            }
        }
        return v;
    }

    /** A stepped column down into the liquid, like the ground floor of a level; a street's is long. */
    private static int plinth(float[] out, int v, float x, float z, Piece street, float r, float film) {
        for (int k = 0; k < 3; k++) {
            float a = r * (1f - k * 0.12f) + k * 0.02f, top = -k * 0.08f, bottom = -1.6f;
            if (street != null) {
                float len = street.held * 0.42f * (1f - k * 0.04f), wide = 0.34f - k * 0.04f;
                float[] f = Mesh.frame(street.dx, 0f, street.dz, x, (top + bottom) * 0.5f, z);
                v = Mesh.boxIn(out, v, f, 0, 0, 0, len, (top - bottom) * 0.5f, wide, film + k * 26f, 0.9f - k * 0.12f);
            } else {
                v = Mesh.box(out, v, x, (top + bottom) * 0.5f, z, a, (top - bottom) * 0.5f, a, film + k * 26f, 0.9f - k * 0.12f);
            }
        }
        return v;
    }

    /**
     * A hopper crystal: a base slab and square rings on it, the outer ring tallest, every inner
     * one lower, so the middle is a stepped pit.
     */
    private static int hopper(float[] out, int v, float x, float y, float z, float s, float film, float shade, int seed) {
        float base = s * 0.18f;
        v = Mesh.box(out, v, x, y + base * 0.5f, z, s, base * 0.5f, s, film, shade);
        // small crystals keep to fewer rings, so a crown of them stays light
        int rings = s < 0.2f ? 2 : 3 + (int) (Mesh.hash(seed) * 3f);
        for (int k = 0; k < rings; k++) {
            float a = s * (1f - k * 0.17f), h = s * (0.75f - k * 0.16f) + 0.02f;
            if (a < 0.05f || h <= 0.01f) break;
            v = Mesh.rim(out, v, x, y + base, z, a, s * 0.1f, h, film + k * 22f, shade * (1.05f - k * 0.08f));
        }
        return v;
    }

    /** A glade: two or three flat terraces, each a little smaller, with a low rim. */
    private static int glade(float[] out, int v, float x, float y, float z, Piece p) {
        int steps = 2 + (int) (Mesh.hash(p.seed) * 2f);
        float each = p.height / steps;
        for (int k = 0; k < steps; k++) {
            float a = p.size * (1f - k * 0.08f);
            v = Mesh.box(out, v, x, y + each * (k + 0.5f), z, a, each * 0.5f, a, p.film + k * 30f, 1f - k * 0.05f);
        }
        return Mesh.rim(out, v, x, y + p.height, z, p.size * (1f - (steps - 1) * 0.08f), 0.03f, 0.03f, p.film + 90f, 1.05f);
    }

    /** A branch: a small crystal and a crooked chain of cubes climbing away from it. */
    private static int branch(float[] out, int v, float x, float y, float z, Piece p) {
        v = hopper(out, v, x, y, z, 0.16f, p.film, 1f, p.seed);
        float a = p.yaw, cx = x, cy = y + 0.12f, cz = z, s = 0.09f;
        int n = 4 + (int) (Mesh.hash(p.seed + 1) * 3f);
        for (int k = 0; k < n; k++) {
            a += (Mesh.hash(p.seed + k * 3) - 0.5f) * 1.2f;
            cx += (float) Math.cos(a) * s * 1.7f;
            cz += (float) Math.sin(a) * s * 1.7f;
            cy += s * 1.3f;
            v = Mesh.spunBox(out, v, cx, cy, cz, s, a, 0.3f * k, p.film + k * 30f, 1f);
            s *= 0.85f;
        }
        return v;
    }

    /** A tree: a thin trunk, two boughs, and a crown of small crystals. */
    private static int tree(float[] out, int v, float x, float y, float z, Piece p) {
        float trunk = p.size;
        v = Mesh.box(out, v, x, y + trunk * 0.5f, z, 0.045f, trunk * 0.5f, 0.045f, p.film, 0.8f);
        for (int k = 0; k < 2; k++) {
            float a = p.yaw + k * 3.1416f, at = trunk * (0.45f + 0.2f * k);
            float[] f = Mesh.frame((float) Math.cos(a), 0f, (float) Math.sin(a), x + (float) Math.cos(a) * 0.14f, y + at, z + (float) Math.sin(a) * 0.14f);
            v = Mesh.boxIn(out, v, f, 0, 0, 0, 0.14f, 0.025f, 0.025f, p.film + 40f, 0.85f);
            v = hopper(out, v, x + (float) Math.cos(a) * 0.3f, y + at, z + (float) Math.sin(a) * 0.3f, 0.09f, p.film + 80f, 1f, p.seed + k);
        }
        int crown = 3 + (int) (Mesh.hash(p.seed + 5) * 3f);
        for (int k = 0; k < crown; k++) {
            float a = p.yaw + k * 6.2832f / crown, d = k == 0 ? 0f : 0.16f;
            v = hopper(out, v, x + (float) Math.cos(a) * d, y + trunk - 0.05f + (k == 0 ? 0.1f : 0f), z + (float) Math.sin(a) * d,
                    0.12f + 0.04f * Mesh.hash(p.seed + k), p.film + 120f + k * 20f, 1.05f, p.seed + 9 + k);
        }
        return v;
    }

    /** A cube of bismuth, sitting square or tipped up onto an edge. */
    private static int cube(float[] out, int v, float x, float y, float z, Piece p) {
        boolean tipped = Mesh.hash(p.seed + 2) < 0.5f;
        float s = p.size, tilt = tipped ? 0.6155f : 0f;
        float cy = y + (tipped ? s * 1.732f : s);
        return Mesh.spunBox(out, v, x, cy, z, s, p.yaw, tilt, p.film, 1f);
    }

    /** How tall a house stands, roof and all. */
    private static float houseHeight(float s, int tiers, int seed) {
        float h = 0f, w = s;
        for (int t = 0; t < tiers; t++) {
            h += w * (1.1f + 0.5f * Mesh.hash(seed + 3 + t));
            w *= 0.87f;
        }
        return h + w * 0.48f;
    }

    /**
     * A house: a body, or a tower of them, each storey set back from the one below, warm lit
     * windows on every storey, and a stepped roof on top.
     */
    private static int house(float[] out, int v, float x, float y, float z, float s, float yaw, float film, int seed, int tiers) {
        float c = (float) Math.cos(yaw), sn = (float) Math.sin(yaw);
        float[] f = Mesh.frame(c, 0f, sn, x, y, z);
        float hue = 0.16f + 0.05f * Mesh.hash(seed + 8), at = 0f, w = s;
        for (int t = 0; t < tiers; t++) {
            float body = w * (1.1f + 0.5f * Mesh.hash(seed + 3 + t));
            v = Mesh.boxIn(out, v, f, 0, at + body * 0.5f, 0, w, body * 0.5f, w * 0.8f, film + t * 40f, 0.9f);
            // two windows on the front and one on each side, warm light of their own
            for (int k = -1; k <= 1; k += 2) {
                v = Mesh.boxIn(out, v, f, w * 0.45f * k, at + body * 0.55f, w * 0.8f + 0.004f, w * 0.17f, w * 0.2f, 0.006f, hue, Mesh.GLOW);
                v = Mesh.boxIn(out, v, f, w * k + 0.004f * k, at + body * 0.55f, 0f, 0.006f, w * 0.2f, w * 0.2f, hue, Mesh.GLOW);
            }
            at += body;
            if (t < tiers - 1) w *= 0.87f;
        }
        for (int k = 0; k < 3; k++) {
            float rw = w * (1.08f - k * 0.3f), d = w * 0.8f * (1.08f - k * 0.3f), h = w * 0.16f;
            v = Mesh.boxIn(out, v, f, 0, at + h * (k + 0.5f), 0, rw, h * 0.5f, d, film + 60f + k * 25f, 1.05f);
        }
        return v;
    }

    /** A street: a row of houses on one long plinth, a lamp between each two. */
    private static int street(float[] out, int v, float x, float y, float z, Piece p) {
        float step = 0.84f;
        for (int k = 0; k < p.held; k++) {
            float along = (k - (p.held - 1) * 0.5f) * step;
            float hx = x + p.dx * along, hz = z + p.dz * along;
            float yaw = (float) Math.atan2(p.dz, p.dx) + (Mesh.hash(p.seed + k) < 0.5f ? 0f : 3.1416f);
            v = house(out, v, hx, y, hz, 0.22f + 0.06f * Mesh.hash(p.seed + k * 5), yaw, p.film + k * 37f, p.seed + k * 11,
                    1 + (Mesh.hash(p.seed + k * 13) < 0.3f ? 1 : 0));
            if (k > 0) {
                float lx = hx - p.dx * step * 0.5f, lz = hz - p.dz * step * 0.5f;
                v = Mesh.box(out, v, lx, y + 0.18f, lz, 0.012f, 0.18f, 0.012f, p.film, 0.8f);
                v = Mesh.box(out, v, lx, y + 0.38f, lz, 0.028f, 0.028f, 0.028f, 0.18f, Mesh.GLOW);
            }
        }
        return v;
    }

    /** A gem: a burning cube of a colour of its own, hung over a small stepped pedestal. */
    private static int gem(float[] out, int v, float x, float y, float z, Piece p) {
        for (int k = 0; k < 3; k++)
            v = Mesh.box(out, v, x, y + 0.05f + k * 0.1f, z, 0.2f - k * 0.05f, 0.05f, 0.2f - k * 0.05f, p.film + k * 30f, 1f);
        float hue = Mesh.hash(p.seed + 4) * 0.9f;
        return Mesh.spunBox(out, v, x, y + 0.62f, z, 0.11f, p.yaw, 0.6155f, hue, Mesh.GLOW);
    }

    /** A funnel: rings widening upwards round a well of white light. */
    private static int funnel(float[] out, int v, float x, float y, float z, Piece p) {
        for (int k = 0; k < 5; k++) {
            float a = 0.12f + k * 0.055f;
            v = Mesh.rim(out, v, x, y + k * 0.1f, z, a, 0.035f, 0.1f, p.film + k * 28f, 1f - k * 0.04f);
        }
        return Mesh.box(out, v, x, y + 0.04f, z, 0.08f, 0.04f, 0.08f, 2f, Mesh.GLOW);
    }

    /** A prism: a tall column of glass with a thread of light inside and a burning cap. */
    private static int prism(float[] out, int v, float x, float y, float z, Piece p) {
        float hue = Mesh.hash(p.seed + 6) * 0.9f;
        v = Mesh.box(out, v, x, y + 0.6f, z, 0.11f, 0.6f, 0.11f, p.film, Mesh.GLASS);
        v = Mesh.box(out, v, x, y + 0.6f, z, 0.012f, 0.56f, 0.012f, hue, Mesh.GLOW);
        return Mesh.spunBox(out, v, x, y + 1.32f, z, 0.07f, p.yaw, 0.6155f, hue, Mesh.GLOW);
    }

    // ---- the eye ----

    /**
     * Where the eye stands to watch the world, and what it looks at: it circles slowly at a
     * distance that takes in the whole world, and while a piece is new it looks its way.
     * ox, oz move the world to where it stands; turn is the circling, in radians.
     */
    void camera(float ox, float oz, float turn, Piece fresh, float[] eye, float[] at) {
        camera(pieces.size(), ox, oz, turn, fresh, eye, at);
    }

    /** The same for the world as it stood with only its first count pieces. */
    void camera(int count, float ox, float oz, float turn, Piece fresh, float[] eye, float[] at) {
        count = Math.max(1, Math.min(count, pieces.size()));
        float mx = 0f, mz = 0f, mw = 0f, top = 1f;
        for (int i = 0; i < count; i++) {
            Piece q = pieces.get(i);
            mx += q.x * q.r; mz += q.z * q.r; mw += q.r;
            top = Math.max(top, q.y + q.height);
        }
        mx /= mw;
        mz /= mw;
        // the body of the world, not its farthest stray street: nine pieces in ten lie within
        float[] far = new float[count];
        for (int i = 0; i < far.length; i++) {
            Piece q = pieces.get(i);
            far[i] = (float) Math.hypot(q.x - mx, q.z - mz) + q.r;
        }
        java.util.Arrays.sort(far);
        float reach = Math.max(0.8f, far[(int) ((far.length - 1) * 0.9f)]);
        float d = Math.max(2.4f, reach * 1.1f + 1.2f);
        float base = 0.9f;
        if (fresh != null) base = (float) Math.atan2(fresh.z - mz, fresh.x - mx);
        float a = base + 0.7f + turn;
        eye[0] = ox + mx + (float) Math.cos(a) * d;
        // low, so the world stands up against the band of light at the horizon
        eye[1] = Math.max(Math.min(top * 0.8f + 0.5f, d * 0.45f), d * 0.32f);
        eye[2] = oz + mz + (float) Math.sin(a) * d;
        float lx = mx, lz = mz;
        if (fresh != null) {
            lx += (fresh.x - mx) * 0.5f;
            lz += (fresh.z - mz) * 0.5f;
        }
        at[0] = ox + lx;
        // a little below the ground, so the world sits in the middle of a tall screen
        at[1] = -0.35f * d / 4f;
        at[2] = oz + lz;
    }

}
