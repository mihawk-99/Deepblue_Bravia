package com.deepblue.shell;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Home screen activity: app list, remote keys, sounds and the optional media slots.
 * Themes: "persona" is drawn natively by MenuView; any other is a page, files/web/<theme>.html ("deck" is
 * prototype/deck.html, "miku" prototype/miku.html), shown in a WebView and fed the real app list, the remote's keys and
 * app launching. Back opens the page's menu; in the Persona theme, Back pressed twice switches to the deck theme.
 * Slots live in the app's external files dir (/sdcard/Android/data/com.deepblue.shell/files):
 *   art.intro.mp4 + art.loop.mp4  full-screen video background (replaces the procedural water)
 *   art.webp / art.png            image or animated image on the left, over the procedural water
 */
public class Home extends Activity implements MenuView.Host {
    static final String SETTINGS_PKG = "com.android.tv.settings";
    /** Page 1, in this order; everything else follows by use. */
    private static final List<String> PINNED = List.of(
            "com.google.android.youtube.tv", "com.google.android.youtube.tvmusic", "com.plexapp.android",
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.limelight",
            "com.alphainventor.filemanager", "com.android.vending");
    private static final String TAG = "DeepBlue";

    private MenuView view;
    private SharedPreferences prefs;
    private SoundPool sounds;
    private final Map<String, Integer> soundIds = new HashMap<>();
    private Drawable art;
    private boolean resumed;

    // deck theme: the page, its folder, and the app list it was last given
    private WebView web;
    private File webDir;
    private List<MenuView.App> apps = new ArrayList<>();
    private String appsWritten = "";
    private long backAt;

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
        webDir = new File(dir, "web");
        String asked = getIntent().getStringExtra("theme");  // adb: am start -n com.deepblue.shell/.Home --es theme deck
        if (asked != null) settings().edit().putString("theme", asked).apply();
        String theme = settings().getString("theme", "persona");
        if (theme.matches("[a-z]+") && !theme.equals("persona") && new File(webDir, theme + ".html").isFile()) {
            startPage(theme);
            return;
        }
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
        boolean back = !resumed;
        resumed = true;
        if (web != null) {
            web.onResume();
            web.resumeTimers();
            if (writeApps()) web.reload();                                     // an app was added or removed
            else if (back) web.evaluateJavascript("window.home && home(false)", null);
            return;
        }
        view.setApps(loadApps());
        enter(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (web != null) {  // stop the page's frames, movies and music while another app is in front
            web.evaluateJavascript("window.away && away()", null);
            web.onPause();
            web.pauseTimers();
            return;
        }
        stopVideo();  // free the hardware decoder for whatever app opens next
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String asked = intent.getStringExtra("theme");
        if (asked != null) { switchTheme(asked); return; }
        if (!resumed) return;  // Home pressed while already home: back to the top, replay the entrance
        if (web != null) web.evaluateJavascript("window.home && home(true)", null); else enter(true);
    }

    // ---- themes ----
    private SharedPreferences settings() { return getSharedPreferences("settings", MODE_PRIVATE); }

    private void switchTheme(String name) {
        settings().edit().putString("theme", name).apply();
        recreate();
    }

    private void startPage(String theme) {
        writeApps();
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);                 // the page keeps the deck order and menu choices
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);       // it fetches banners/apps.json from the folder it is in
        ws.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient() {  // the page's console goes to logcat (tag DeepBlue)
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "page: " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");  // only ever loads the local page below
        web.loadUrl("file://" + new File(webDir, theme + ".html").getPath());
        setContentView(web);
    }

    /** What a theme's page may ask of the TV. Called on a WebView thread. */
    private final class Bridge {
        @JavascriptInterface
        public void launch(String pkg) {
            runOnUiThread(() -> { for (MenuView.App a : apps) if (a.pkg.equals(pkg)) { Home.this.launch(a); return; } });
        }

        @JavascriptInterface
        public void theme(String name) { runOnUiThread(() -> switchTheme(name)); }
    }

    /** Gives the page the app list as files next to it: banners/apps.json and one PNG per app. True if it changed. */
    private boolean writeApps() {
        apps = loadApps();
        StringBuilder sig = new StringBuilder(), json = new StringBuilder("[");
        for (MenuView.App a : apps) sig.append(a.pkg).append(' ');
        if (sig.toString().equals(appsWritten)) return false;
        boolean first = appsWritten.isEmpty();
        File banners = new File(webDir, "banners");
        banners.mkdirs();
        for (MenuView.App a : apps) {
            String file = "";
            if (a.art != null) {
                int w = a.art.getIntrinsicWidth(), h = a.art.getIntrinsicHeight();
                if (w <= 0 || h <= 0) { w = 320; h = 180; }
                float fit = Math.min(1f, 640f / w);  // banners are small; cap the odd huge one
                Bitmap bmp = Bitmap.createBitmap(Math.round(w * fit), Math.round(h * fit), Bitmap.Config.ARGB_8888);
                a.art.setBounds(0, 0, bmp.getWidth(), bmp.getHeight());
                a.art.draw(new Canvas(bmp));
                try (FileOutputStream out = new FileOutputStream(new File(banners, a.pkg + ".png"))) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                    file = "banners/" + a.pkg + ".png";
                } catch (Exception e) {
                    Log.w(TAG, "banner not written: " + a.pkg, e);  // the card shows the app's name instead
                }
                bmp.recycle();
            }
            json.append(json.length() > 1 ? "," : "").append("{\"pkg\":\"").append(a.pkg).append("\",\"label\":\"")
                    .append(a.name.replace("\\", "").replace("\"", "")).append("\",\"banner\":\"").append(file).append("\"}");
        }
        try (FileOutputStream out = new FileOutputStream(new File(banners, "apps.json"))) {
            out.write(json.append("]").toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "app list not written", e);
        }
        appsWritten = sig.toString();
        return !first;
    }

    /** Page themes: the remote's keys go to the page as arrow keys, Enter and Escape (its menu key). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (web == null) return super.dispatchKeyEvent(e);
        String key;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: key = "ArrowUp"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: key = "ArrowDown"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: key = "ArrowLeft"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: key = "ArrowRight"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: key = "Enter"; break;
            case KeyEvent.KEYCODE_BACK: key = "Escape"; break;
            default: return super.dispatchKeyEvent(e);
        }
        if (e.getAction() != KeyEvent.ACTION_MULTIPLE) {
            web.evaluateJavascript("window.tvKey && tvKey('" + key + "'," + (e.getAction() == KeyEvent.ACTION_DOWN) + ","
                    + (e.getRepeatCount() > 0) + ")", null);
        }
        return true;
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
        if (web != null) { web.destroy(); return; }
        stopVideo();
        sounds.release();
    }

    @Override
    public void onBackPressed() {
        // home screen: nothing to go back to, so Back twice switches to the deck theme (when its files are on the TV)
        if (!new File(webDir, "deck.html").isFile()) return;
        long now = System.currentTimeMillis();
        if (now - backAt < 2500) { switchTheme("deck"); return; }
        backAt = now;
        Toast.makeText(this, "Press Back again for the Deck theme", Toast.LENGTH_SHORT).show();
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
            a.name = ri.loadLabel(pm).toString();
            a.label = a.name.toUpperCase(Locale.ROOT);
            a.intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable banner = ri.activityInfo.loadBanner(pm);
            a.art = (banner != null ? banner : ri.loadIcon(pm)).mutate();
            a.launches = prefs.getInt(pkg, 0);
            list.add(a);
        }
        // pinned first, then most used, then A-Z
        list.sort((x, y) -> {
            int px = pinRank(x.pkg), py = pinRank(y.pkg);
            if (px != py) return px - py;
            return x.launches != y.launches ? y.launches - x.launches : x.label.compareTo(y.label);
        });
        MenuView.App sys = new MenuView.App();  // last entry, like SYSTEM in the reference menu
        sys.pkg = SETTINGS_PKG;
        sys.label = "SYSTEM";
        sys.name = "System";
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

    private static int pinRank(String pkg) {
        int i = PINNED.indexOf(pkg);
        return i < 0 ? PINNED.size() : i;
    }

    @Override
    public void launch(MenuView.App a) {
        prefs.edit().putInt(a.pkg, a.launches + 1).apply();
        try {
            startActivity(a.intent);
        } catch (RuntimeException e) {  // uninstalled meanwhile, disabled, or no permission
            Log.w(TAG, "launch failed: " + a.pkg, e);
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
            if (web == null) enter(false);
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
