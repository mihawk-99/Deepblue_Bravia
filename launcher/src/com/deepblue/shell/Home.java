package com.deepblue.shell;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.ImageDecoder;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Home screen activity: app list, remote keys, sounds and the optional media slots.
 * Slots live in the app's external files dir (/sdcard/Android/data/com.deepblue.shell/files):
 *   art.intro.mp4 + art.loop.mp4  full-screen video background (replaces the procedural water)
 *   art.webp / art.png            image or animated image on the left, over the procedural water
 */
public class Home extends Activity implements MenuView.Host {
    static final String SETTINGS_PKG = "com.android.tv.settings";
    private static final String TAG = "DeepBlue";

    private MenuView view;
    private SharedPreferences prefs;
    private SoundPool sounds;
    private final Map<String, Integer> soundIds = new HashMap<>();
    private Drawable art;
    private boolean resumed;

    // video slot: the intro plays once, the loop sits underneath and takes over when the intro ends
    private File introFile, loopFile;
    private boolean video;
    private TextureView introTex, loopTex;
    private Surface introSurface, loopSurface;
    private MediaPlayer intro, loop;
    private boolean introReady, introDone, loopReady, introShown;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("launches", MODE_PRIVATE);
        File dir = getExternalFilesDir(null);  // created on first call, so the slot folder exists for adb push
        introFile = new File(dir, "art.intro.mp4");
        loopFile = new File(dir, "art.loop.mp4");
        video = introFile.isFile() && loopFile.isFile();
        art = loadArt(dir);

        sounds = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        soundIds.put("move", sounds.load(this, R.raw.move, 1));
        soundIds.put("confirm", sounds.load(this, R.raw.confirm, 1));
        soundIds.put("splash", sounds.load(this, R.raw.splash, 1));
        soundIds.put("page", sounds.load(this, R.raw.page, 1));

        FrameLayout root = new FrameLayout(this);
        if (video) {
            loopTex = texture(true);
            introTex = texture(false);
            root.addView(loopTex);
            root.addView(introTex);
        }
        view = new MenuView(this, this);
        root.addView(view);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        view.setApps(loadApps());
        enter(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        stopVideo();  // free the hardware decoder for whatever app opens next
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (resumed) enter(true);  // Home pressed while already home: back to the top, replay the entrance
    }

    private void enter(boolean toTop) {
        view.enter(toTop);
        stopVideo();
        startVideo();
        if (!video) play("splash");  // with video, the splash sound waits for the clip's first frame
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopVideo();
        sounds.release();
    }

    @Override
    public void onBackPressed() {
        // home screen: nothing to go back to
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        boolean repeat = e.getRepeatCount() > 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: view.move(-1, repeat); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: view.move(1, repeat); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: view.flipPage(-1, repeat); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: view.flipPage(1, repeat); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!repeat) view.confirm();
                return true;
            default: return super.onKeyDown(code, e);
        }
    }

    // ---- apps ----
    private List<MenuView.App> loadApps() {
        PackageManager pm = getPackageManager();
        List<MenuView.App> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;  // one entry per app
            MenuView.App a = new MenuView.App();
            a.pkg = pkg;
            a.label = ri.loadLabel(pm).toString().toUpperCase(Locale.ROOT);
            a.intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable banner = ri.activityInfo.loadBanner(pm);
            a.art = (banner != null ? banner : ri.loadIcon(pm)).mutate();
            a.launches = prefs.getInt(pkg, 0);
            list.add(a);
        }
        // most used first, then A-Z
        list.sort((x, y) -> x.launches != y.launches ? y.launches - x.launches : x.label.compareTo(y.label));
        MenuView.App sys = new MenuView.App();  // last entry, like SYSTEM in the reference menu
        sys.pkg = SETTINGS_PKG;
        sys.label = "SYSTEM";
        sys.intent = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            Drawable d = pm.getApplicationBanner(SETTINGS_PKG);
            sys.art = (d != null ? d : pm.getApplicationIcon(SETTINGS_PKG)).mutate();
        } catch (PackageManager.NameNotFoundException ignored) {
            // no TV settings package: the entry still opens Settings, just without a preview
        }
        list.add(sys);
        return list;
    }

    @Override
    public void launch(MenuView.App a) {
        prefs.edit().putInt(a.pkg, a.launches + 1).apply();
        try {
            startActivity(a.intent);
        } catch (RuntimeException e) {  // uninstalled meanwhile, disabled, or no permission
            Log.w(TAG, "launch failed: " + a.pkg, e);
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
            enter(false);
        }
    }

    @Override
    public void play(String name) {
        Integer id = soundIds.get(name);
        if (id != null) sounds.play(id, 1, 1, 1, 0, 1);
    }

    // ---- art slot ----
    private static Drawable loadArt(File dir) {
        for (String name : new String[]{"art.webp", "art.png"}) {
            File f = new File(dir, name);
            if (!f.isFile()) continue;
            try {
                Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f));
                if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
                return d;
            } catch (Exception e) {
                Log.w(TAG, "art slot unreadable: " + f, e);
            }
        }
        return null;
    }

    @Override
    public Drawable art() { return art; }

    // ---- video slot ----
    @Override
    public boolean hasVideo() { return video; }

    @Override
    public float introVideoSeconds() {
        if (!video || introDone) return -1;
        if (!introShown) return 0;  // decoder still starting: the view stays white instead of showing the last visit's frame
        return intro != null ? intro.getCurrentPosition() / 1000f : -1;
    }

    private TextureView texture(boolean isLoop) {
        TextureView tv = new TextureView(this);
        tv.setSurfaceTextureListener(new Layer(isLoop));
        return tv;
    }

    /** Watches one video layer's surface. */
    private final class Layer implements TextureView.SurfaceTextureListener {
        private final boolean isLoop;

        Layer(boolean isLoop) { this.isLoop = isLoop; }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            if (isLoop) loopSurface = new Surface(st); else introSurface = new Surface(st);
            startVideo();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            stopVideo();
            if (isLoop) loopSurface = null; else introSurface = null;
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
            if (!isLoop && introReady && !introShown) { introShown = true; play("splash"); }
            // the loop has drawn its first frame under the finished intro: now hide the intro layer
            if (isLoop && introDone && introTex.getVisibility() == View.VISIBLE) introTex.setVisibility(View.INVISIBLE);
        }
    }

    private void startVideo() {
        if (!video || !resumed || introSurface == null || loopSurface == null || intro != null) return;
        introReady = introDone = loopReady = introShown = false;
        introTex.setVisibility(View.VISIBLE);
        intro = player(introFile, introSurface, false);
        loop = player(loopFile, loopSurface, true);
        if (intro == null || loop == null) { videoFailed(); return; }
        intro.setOnPreparedListener(mp -> { introReady = true; mp.start(); });
        intro.setOnCompletionListener(mp -> { introDone = true; if (loopReady) loop.start(); });
        loop.setOnPreparedListener(mp -> { loopReady = true; if (introDone) mp.start(); });
        intro.prepareAsync();
        loop.prepareAsync();
    }

    private MediaPlayer player(File f, Surface s, boolean looping) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(f.getPath());
        } catch (Exception e) {
            Log.w(TAG, "video slot unreadable: " + f, e);
            mp.release();
            return null;
        }
        mp.setSurface(s);
        mp.setVolume(0, 0);
        mp.setLooping(looping);
        mp.setOnErrorListener((p, what, extra) -> { Log.w(TAG, "video error " + what + "/" + extra + " in " + f); videoFailed(); return true; });
        return mp;
    }

    /** A clip that won't play falls back to the procedural water instead of a black screen. */
    private void videoFailed() {
        stopVideo();
        video = false;
        if (introTex != null) { introTex.setVisibility(View.GONE); loopTex.setVisibility(View.GONE); }
        view.enter(false);
    }

    private void stopVideo() {
        if (intro != null) intro.release();
        if (loop != null) loop.release();
        intro = loop = null;
    }
}
