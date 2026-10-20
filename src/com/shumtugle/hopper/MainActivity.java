package com.shumtugle.hopper;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public final class MainActivity extends Activity {
    private Stage stage;
    private Vibrator vibrator;
    private final Sound sound = new Sound();
    private SharedPreferences prefs;

    // D minor pentatonic, the same bells as the crystal's
    private static final float[] SCALE = {293.66f, 349.23f, 392.0f, 440.0f, 523.25f, 587.33f, 698.46f, 783.99f};

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Crash.install(getApplicationContext());
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        prefs = getSharedPreferences("game", MODE_PRIVATE);
        String report = Crash.pending(this);
        if (report != null) {
            Crash.show(this, report, new Runnable() {
                @Override
                public void run() {
                    startGame();
                    stage.resumeClock();
                    stage.onResume();
                    sound.start();
                    hideBars();
                }
            });
            return;
        }
        startGame();
    }

    private void startGame() {
        int level = Math.max(0, prefs.getInt("level", 0));
        // every level is grown from its number, so the number is all that needs keeping
        int reached = Math.max(level, prefs.getInt("reached", level));
        stage = new Stage(this, level, reached, prefs.getString("marks", ""));
        setContentView(stage);
        // a low string under everything, the same one that hums under the crystal
        sound.setCrystal(true, 2f, 0f, 0.4f, 0f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (stage == null) return;
        hideBars();
        stage.resumeClock();
        stage.onResume();
        sound.start();
    }

    @Override
    protected void onPause() {
        if (stage != null) {
            stage.onPause();
            onLevel(stage.level());
            sound.stop();
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideBars();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** The level now being played; written down so the game comes back to it. */
    void onLevel(int level) {
        prefs.edit().putInt("level", level).apply();
    }

    /**
     * A level won: the next one is open from now on. Who won it is written down, one character
     * a level, '1' for a finger and '2' for the watcher; a finger's win is never overwritten.
     * Returns the marks as they now stand.
     */
    String onSolved(int level, boolean watcher) {
        StringBuilder m = new StringBuilder(prefs.getString("marks", ""));
        while (m.length() <= level) m.append('0');
        if (!watcher || m.charAt(level) != '1') m.setCharAt(level, watcher ? '2' : '1');
        int reached = Math.max(prefs.getInt("reached", 0), level + 1);
        prefs.edit().putString("marks", m.toString()).putInt("reached", reached).apply();
        return m.toString();
    }

    /** The watcher has been called: a bell, high and soft. */
    void onSummon() {
        sound.strike(587.33f, 0.3f, 1.4f);
        sound.strikeLater(880f, 0.2f, 1.2f, 0.18f);
    }

    @Override
    public void onBackPressed() {
        if (stage == null) {
            super.onBackPressed();
            return;
        }
        stage.back();
    }

    /** Leaves the game; safe to call from the render thread. */
    void quit() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
    }

    /** Called on the render thread for every event the world raised this frame. */
    void onWorldEvent(int event, World w) {
        switch (event) {
            case World.EV_LAND:
                // a knock on bismuth, its pitch nudged a little by where it lands
                sound.knock(940f + 30f * ((w.cellX * 3 + w.cellZ * 5) % 3), 0.32f, 0.03f);
                sound.knock(310f, 0.22f, 0.025f);
                tick();
                break;
            case World.EV_LAND_GLASS:
                sound.knock(2720f, 0.22f, 0.02f);
                sound.knock(1600f, 0.12f, 0.03f);
                tick();
                break;
            case World.EV_LAND_SPRING:
                sound.thud(140f, 52f, 0.5f, 0.22f);
                sound.knock(430f, 0.25f, 0.03f);
                thump(false);
                break;
            case World.EV_SHARD: {
                // each shard one step higher up the scale
                int k = w.shardsTotal - w.shardsLeft - 1;
                sound.strike(SCALE[Math.min(SCALE.length - 1, k)], 0.6f, 1.2f);
                break;
            }
            case World.EV_BEAD:
                sound.pluck(392f, 0.45f);
                sound.pluck(587.33f, 0.3f);
                break;
            case World.EV_COOL:
                sound.strike(220f, 0.5f, 1.6f);
                thump(false);
                break;
            case World.EV_OPEN:
                sound.strikeLater(293.66f, 0.5f, 1.8f, 0.25f);
                sound.strikeLater(440f, 0.45f, 1.8f, 0.45f);
                sound.strikeLater(587.33f, 0.4f, 2f, 0.65f);
                break;
            case World.EV_WIN:
                for (int i = 0; i < 5; i++) sound.strikeLater(SCALE[i + 2], 0.45f, 1.6f, i * 0.12f);
                break;
            case World.EV_DROWN:
                sound.thud(110f, 43f, 0.8f, 0.45f);
                sound.strike(73.42f, 0.7f, 2.2f);
                thump(true);
                break;
            case World.EV_BURN:
                sound.strike(77.78f, 0.9f, 1.4f);
                sound.strike(82.41f, 0.7f, 1.4f);
                thump(true);
                break;
            case World.EV_SINK:
                sound.strikeLater(110f, 0.35f, 1.2f, 0.4f);
                break;
            case World.EV_LEAP:
                sound.knock(430f, 0.25f, 0.025f);
                sound.knock(310f, 0.2f, 0.03f);
                break;
            case World.EV_QUEUE:
                // the order is heard: a tick, higher for a leap, aside for a slanted hop
                sound.knock(w.queued > 1 ? 1600f : w.slant != 0 ? 1220f : 955f, 0.12f, 0.015f);
                break;
            case World.EV_WAKE:
                // a strike on nothing: a low thud, then the bell under the water
                sound.thud(95f, 40f, 0.8f, 0.6f);
                sound.strike(73.42f, 0.6f, 2.4f);
                sound.strikeLater(293.66f, 0.3f, 1.6f, 0.9f);
                thump(true);
                break;
            case World.EV_BONK:
                // the drop meets a wall too high to climb
                sound.knock(180f, 0.4f, 0.04f);
                sound.knock(260f, 0.2f, 0.03f);
                thump(false);
                break;
            case World.EV_TURN:
                sound.knock(560f, 0.1f, 0.012f);
                break;
            default:
        }
    }

    private void tick() {
        Vibrator v = vibrator;
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        else v.vibrate(VibrationEffect.createOneShot(6, 60));
    }

    private void thump(boolean strong) {
        Vibrator v = vibrator;
        if (v == null || !v.hasVibrator()) return;
        if (v.hasAmplitudeControl()) {
            long[] t = strong ? new long[] {0, 18, 26, 60} : new long[] {0, 12, 30};
            int[] a = strong ? new int[] {0, 255, 0, 80} : new int[] {0, 160, 35};
            v.vibrate(VibrationEffect.createWaveform(t, a, -1));
        } else {
            v.vibrate(VibrationEffect.createOneShot(strong ? 30 : 14, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
