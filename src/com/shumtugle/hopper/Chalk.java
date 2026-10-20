package com.shumtugle.hopper;

/**
 * The hopscotch: every level as a chalked square, numbers climbing up the screen in the old
 * pattern of single and paired squares, without end. Squares played are lit; the next one
 * waits, bright; a few beyond it are drawn faintly; the rest is black.
 *
 * Plain state and arithmetic, so the bench can run it: where each square lies, where the strip
 * has been scrolled to, which square a finger hit. Lengths are in screen widths, y upwards from
 * the middle of the screen.
 */
final class Chalk {
    /** Squares in each row, repeating: one, one, one, two, one, two. */
    private static final int[] PATTERN = {1, 1, 1, 2, 1, 2};
    private static final int PER_BLOCK = 8;
    static final float ROW = 0.25f, CELL = 0.21f, GAP = 0.025f;
    /** How many locked squares are drawn beyond the next one. */
    static final int AHEAD = 6;

    boolean open;
    /** Seconds since the strip was opened. */
    float age;
    /** The row that sits two rows above the bottom band; fractional while dragging. */
    float scroll;
    int reached, current;
    /** One character a level: '2' where the watcher played it, anything else otherwise. */
    String marks = "";
    private float aspect = 2f;

    /** Opens the strip with the level being played two rows up from the bottom. */
    void open(int reached, int current, String marks, float aspect) {
        this.reached = reached;
        this.current = current;
        this.marks = marks;
        this.aspect = aspect;
        open = true;
        age = 0f;
        scroll = clamp(rowOf(Math.min(current, reached)) - 2f);
    }

    void close() {
        open = false;
    }

    /** A drag of the finger by dy screen widths, upwards positive: the squares follow it. */
    void drag(float dy) {
        scroll = clamp(scroll - dy / ROW);
    }

    private float clamp(float s) {
        return Math.max(-0.4f, Math.min(rowOf(reached + AHEAD), s));
    }

    /** The bottom edge of the band the strip is laid in. */
    float base() {
        return -aspect * 0.5f + 0.28f;
    }

    static int rowOf(int index) {
        int row = index / PER_BLOCK * PATTERN.length, rem = index % PER_BLOCK;
        for (int k = 0; ; k++) {
            if (rem < PATTERN[k]) return row + k;
            rem -= PATTERN[k];
        }
    }

    static int firstOf(int row) {
        int n = row / PATTERN.length * PER_BLOCK;
        for (int k = 0; k < row % PATTERN.length; k++) n += PATTERN[k];
        return n;
    }

    static int width(int row) {
        return PATTERN[row % PATTERN.length];
    }

    float rowY(int row) {
        return base() + (row - scroll + 2f) * ROW;
    }

    static float cellX(int row, int k) {
        if (width(row) == 1) return 0f;
        return (k == 0 ? -1f : 1f) * (CELL + GAP) * 0.5f;
    }

    /** The lowest and highest row worth drawing. */
    int firstRow() {
        return Math.max(0, (int) Math.floor(scroll - 3f));
    }

    int lastRow() {
        return Math.min(rowOf(reached + AHEAD), (int) Math.ceil(scroll + aspect / ROW + 1f));
    }

    boolean byWatcher(int index) {
        return index < marks.length() && marks.charAt(index) == '2';
    }

    /** The level whose square a finger at (x, y) is on; -1 for a locked square, -2 for none at all. */
    int hit(float x, float y) {
        for (int row = firstRow(); row <= lastRow(); row++) {
            float cy = rowY(row);
            if (Math.abs(y - cy) > CELL * 0.5f) continue;
            for (int k = 0; k < width(row); k++) {
                if (Math.abs(x - cellX(row, k)) > CELL * 0.5f) continue;
                int index = firstOf(row) + k;
                return index <= reached ? index : -1;
            }
        }
        return -2;
    }
}
