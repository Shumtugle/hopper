package com.shumtugle.hopper;

/**
 * The levels. The first ones are drawn by hand as text; every level after them is grown by
 * {@link Maze} from its own number, so there is always a next one.
 *
 * A level is a stack of floors over the black liquid. The ground floor stands in the liquid;
 * the floors above float, one over another, so the drop can bounce under a floor, climb onto
 * it and look down from it. Each floor is drawn as rows of text, row 0 the far side; the drop
 * starts facing it.
 *
 *   .  nothing                #  plain island
 *   o  glass: sinks once the drop leaves it
 *   ^ > v <  spring: throws the drop two cells its own way (the arrow) and one floor up
 *   x  hot plate: burns the drop unless it carries a bead; its heat burns anything leaping over it
 *   *  island with a shard    b  island with a silver bead
 *   m  a mirage: looks like an island and holds nothing; it shows the drop its own reflection
 *   E  the gate, open once every shard is taken
 *   S  where the drop falls in
 *
 * The drop falls until something holds it: a hop off the edge of a floor lands on whatever lies
 * below in that column, or in the liquid. A floor right overhead is a ceiling: under it the drop
 * can still hop, but not leap or be thrown.
 */
final class Level {
    static final int LIQUID = 0, STONE = 1, GLASS = 2, SPRING = 3, HOT = 4, GATE = 5, MIRAGE = 6;
    /** Height of one floor over the one below, in cells. */
    static final float FLOOR = 1.5f;

    final int cols, rows, floors;
    /** Cell (x, z) on floor f is at index (f * rows + z) * cols + x. */
    final int[] type;
    final boolean[] shard, bead;
    /** For a spring, the way it throws: 0 north (row 0's side), 1 east, 2 south, 3 west. */
    final int[] aim;
    final int startX, startZ, startF;
    final String hint;

    /** A level with one floor, drawn as its rows. */
    Level(String hint, String... map) {
        this(hint, new String[][] {map});
    }

    /** A level with several floors, the ground floor first. */
    static Level stack(String hint, String[]... floors) {
        return new Level(hint, floors);
    }

    Level(String hint, String[][] floors) {
        this.hint = hint;
        this.floors = floors.length;
        int r = 0, c = 0;
        for (String[] f : floors) {
            r = Math.max(r, f.length);
            for (String row : f) c = Math.max(c, row.length());
        }
        rows = r;
        cols = c;
        type = new int[cols * rows * this.floors];
        shard = new boolean[type.length];
        bead = new boolean[type.length];
        aim = new int[type.length];
        int sx = 0, sz = 0, sf = 0;
        for (int f = 0; f < this.floors; f++)
            for (int z = 0; z < floors[f].length; z++)
                for (int x = 0; x < floors[f][z].length(); x++) {
                    int i = index(x, z, f);
                    switch (floors[f][z].charAt(x)) {
                        case '#': type[i] = STONE; break;
                        case 'o': type[i] = GLASS; break;
                        case '^': type[i] = SPRING; aim[i] = 0; break;
                        case '>': type[i] = SPRING; aim[i] = 1; break;
                        case 'v': type[i] = SPRING; aim[i] = 2; break;
                        case '<': type[i] = SPRING; aim[i] = 3; break;
                        case 'x': type[i] = HOT; break;
                        case '*': type[i] = STONE; shard[i] = true; break;
                        case 'b': type[i] = STONE; bead[i] = true; break;
                        case 'm': type[i] = MIRAGE; break;
                        case 'E': type[i] = GATE; break;
                        case 'S': type[i] = STONE; sx = x; sz = z; sf = f; break;
                        default: type[i] = LIQUID;
                    }
                }
        startX = sx;
        startZ = sz;
        startF = sf;
    }

    int index(int x, int z, int f) {
        return (f * rows + z) * cols + x;
    }

    int shards() {
        int n = 0;
        for (boolean s : shard) if (s) n++;
        return n;
    }

    /** How many levels are drawn by hand; the rest come from the maze maker. */
    static int drawn() {
        return MAPS.length;
    }

    private static final java.util.HashMap<Integer, Level> GROWN = new java.util.HashMap<Integer, Level>();

    static Level get(int n) {
        if (n < 0) n = 0;
        if (n < MAPS.length) return MAPS[n];
        synchronized (GROWN) {
            Level l = GROWN.get(n);
            if (l == null) {
                l = Maze.make(n);
                GROWN.put(n, l);
            }
            return l;
        }
    }

    /** Grows level n on a thread of its own, so it is ready by the time it is wanted. */
    static void prepare(final int n) {
        if (n < MAPS.length) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                get(n);
            }
        }, "maze");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static final Level[] MAPS = {
        new Level("swipe up to hop forward",
            ".E.",
            ".#.",
            ".*.",
            ".#.",
            ".*.",
            ".S."),
        new Level("swipe sideways to turn",
            "E##..",
            "...#.",
            "*#.*.",
            ".#.#.",
            ".##S."),
        new Level("a long swipe up leaps over a cell",
            "..E..",
            "..#..",
            ".....",
            "*.#.*",
            ".....",
            "..S.."),
        new Level("glass sinks behind you",
            "#oE..",
            "o.o.*",
            "*oooo",
            "..o..",
            "..S.."),
        new Level("a spring throws you two cells and a floor up",
            new String[][] {{
                ".....",
                ".....",
                "..^..",
                ".*#..",
                "..S.."}, {
                "*#E..",
                ".....",
                ".....",
                ".##..",
                "....."}}),
        new Level("a bead cools a hot plate",
            "..E..",
            "..x..",
            ".#*#b",
            ".#...",
            ".#S.."),
        new Level("swipe up and aside to cut a corner",
            "E.o*o",
            "x...o",
            "^.#o#",
            "..b..",
            "*o^.S"),
        new Level("under a floor you can hop, not leap",
            new String[][] {{
                "*#.#E",
                "#...#",
                "#.>.#",
                "#...#",
                "##S##"}, {
                ".....",
                "#####",
                "#...#",
                "#.#.#",
                "....."}}),
        new Level("a mirage drops you to whatever lies below",
            new String[][] {{
                ".#E..",
                ".....",
                ".....",
                ".^...",
                ".S..."}, {
                ".m#m.",
                ".#*#.",
                ".....",
                ".....",
                "....."}}),
        new Level("",
            "*o#.E.b",
            "o...x.o",
            "#.^o*.^",
            "x.....o",
            "b#o^.o*",
            "...#S.."),
    };
}
