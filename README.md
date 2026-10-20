# Hopper

<img src="docs/icon.png" width="96" align="right" alt="">

A puzzle for Android, seen from inside a drop of quicksilver. The drop never
stops bouncing; all you choose is where the next bounce goes. Below lies a black
liquid, and over it stand hopper crystals of bismuth: stepped, hollow, iridescent
islands, stacked into floors you can hop under, climb onto and fall from.

Each level begins in total black. A tap strikes the nothing, a ring runs out
over the liquid, and the level rises out of it.

## In the level

- **Shards** glow in the pits of the islands. Take them all and the gate opens:
  a turning ring with a column of light over it.
- **Glass** holds while you bounce on it, and sinks the moment you leave.
- **Springs** throw you two cells their own way, along the glowing arrow, and a
  floor up. They are the only way up.
- **Floors** float one over another. A hop off the edge lands on whatever lies
  below, or in the liquid. Under a floor you can still hop, but not leap or be
  thrown: the floor is a ceiling.
- **Hot plates** burn, and so does their heat if you leap over them, unless you
  carry a silver **bead**: it cools one plate.
- **Mirages** look like islands and hold nothing. The map does not show them,
  and on every mirage a reflection of the drop bounces in step with you.

Some levels **shift**. They come in acts: after a number of landings the ground
trembles for three bounces, then everything but the island under you goes down
into the liquid and a new act rises around you. Shards you did not reach move on
to the next act; the gate waits in the last one.

The first ten levels are drawn by hand. Every level after them is grown from
its own number: a walk through the floors that obeys every rule becomes the way
through, so each level can be won, and a solver picks the grown candidate whose
shortest way fits the difficulty. Difficulty rises one step every three levels,
each step bringing one new thing, until it reaches a ceiling; every twentieth
level is a step easier, every thirtieth two. There is always a next level.

## The watcher and the hopscotch

Beside the map sits the watcher: an eye in a stepped frame of bismuth. Tap it
and it works out the way, the level rises again, and the watcher plays it,
turning and hopping exactly as a finger would. Swipe, and the drop is yours
again.

After the watcher's game comes the hopscotch: every level as a chalked square,
the numbers climbing up the screen in pairs and singles. Any level played can
be played again, or the next one taken. Squares the watcher played carry its
mark. The system back button opens the hopscotch at any time; pressed there, it
leaves the game. Each level grows from its own number, so a level chosen again
is the very same level.

## Controls

- **Swipe up** — the next bounce goes one cell ahead.
- **Long swipe up** — a leap over a cell.
- **Swipe up and to a side** — one cell across a corner.
- **Swipe sideways** — turn by a quarter, at once.
- **Swipe down** — turn round.
- **Tap** — forget the order and look at the map.
- **Tap the eye** beside the map — the watcher plays the level.
- **Back** — the hopscotch of all levels.

The map in the corner turns with you: up is always ahead. It shows the floor you
are on in full, the floors below small and dim, the floors above as outlines,
and the cell your next bounce will land on.

## Building

No IDE and no build system. The build tools of the Android SDK, a JDK and a
platform jar, API 34 or newer, are all it takes:

```sh
export ANDROID_JAR=/path/to/android.jar
export KEYSTORE=/path/to/your.keystore KEY_ALIAS=yourkey KS_PASS=yourpassword
./build.sh
```

The signed package is `out/hopper.apk`. It runs on Android 8.0 and newer with
OpenGL ES 3.0. Everything seen and heard is made by the code: the islands, the
liquid's waves, the light, the bells and knocks.

## License

0BSD. Do what you like with it.
