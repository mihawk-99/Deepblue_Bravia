package com.deepblue.shell;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The whole home screen, drawn on one canvas at the display's refresh rate.
 * Line-for-line port of launcher/prototype/index.html: same fractions of W/H, same drawing order.
 */
class MenuView extends View {
    static class App {
        String label, pkg;
        Drawable art;
        Intent intent;
        int launches;
    }

    interface Host {
        void launch(App app);
        void play(String sound);
        boolean hasVideo();
        /** Seconds into the intro clip, or -1 once it has handed over to the loop. */
        float introVideoSeconds();
        Drawable art();
    }

    static final int PAGE = 8;  // items per menu page, like the reference menu
    // Held keys auto-repeat fast; restarting the tick that fast stacks into a whine, so throttle moves and sounds.
    static final long REPEAT_MS = 110, SOUND_MS = 150, PAGE_REPEAT_MS = 300;
    static final int[] CYAN = {0xFF7CF0FF, 0xFF5FE3FB, 0xFF49D2F6, 0xFF6AEAFC};
    static final float TAU = (float) (Math.PI * 2);

    private final Host host;
    private final Typeface heavyFace, uiMedium, uiSemi;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Matrix m = new Matrix();

    private List<App> apps = new ArrayList<>();
    private int sel;
    private float pos, pop = 1, intro, confirmT = -1;
    private boolean launched;
    private long last, lastMoveAt, lastSoundAt;
    private int W, H;

    // procedural scenery (built lazily, only when there is no video)
    private Shader water;
    private BitmapShader caustic1, caustic2;
    private LinearGradient causticFade;
    private final Paint layerPaint = new Paint();
    private float causticH;

    private static final int SPRITE = 256, SPRITE_R = 100;  // splash sprite size and the blob radius inside it
    private Bitmap[] splashSprites;
    private final Paint spritePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.RectF spriteRect = new android.graphics.RectF();

    private static double seed = 7;
    private final float[][] bubbles = new float[10][], flecks = new float[7][], splash = new float[11][], rings = new float[26][];

    private String day = "", time = "", date = "";
    private long clockAt;

    MenuView(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        heavyFace = Typeface.createFromAsset(ctx.getAssets(), "fonts/Kanit-ExtraBoldItalic.ttf");
        uiMedium = Typeface.createFromAsset(ctx.getAssets(), "fonts/BarlowCondensed-MediumItalic.ttf");
        uiSemi = Typeface.createFromAsset(ctx.getAssets(), "fonts/BarlowCondensed-SemiBoldItalic.ttf");
        // same seeded sequence and order as the prototype
        for (int i = 0; i < bubbles.length; i++) bubbles[i] = new float[]{.3f + rnd() * .7f, rnd(), 3 + rnd() * 6, .01f + rnd() * .02f, rnd() * TAU};
        for (int i = 0; i < flecks.length; i++) flecks[i] = new float[]{.02f + rnd() * .3f, rnd(), .006f + rnd() * .012f, rnd() * 360, .006f + rnd() * .012f, (int) (rnd() * 3)};
        for (int i = 0; i < splash.length; i++) splash[i] = new float[]{rnd(), .45f + rnd() * .7f, 120 + rnd() * 260, .4f + rnd() * .8f, rnd() * TAU, rnd() * TAU, rnd() < .55f ? 1 : 0};
        for (int i = 0; i < rings.length; i++) rings[i] = new float[]{rnd(), .2f + rnd() * .9f, 6 + rnd() * 26, .5f + rnd(), rnd() < .4f ? 1 : 0};
        layerPaint.setAlpha((int) (.55f * 255));
        layerPaint.setBlendMode(BlendMode.PLUS);
        buildSplashSprites();  // up front: building them on the first page flip caused a one-off hitch
    }

    // ---- small math helpers (same as the prototype) ----
    private static float rnd() { seed = (seed * 16807) % 2147483647; return (float) (seed / 2147483647); }
    private static float jit(int i, int k) { double h = Math.sin(i * 12.9898 + k * 78.233) * 43758.5453; return (float) ((h - Math.floor(h)) * 2 - 1); }
    private static float ease(float t) { float c1 = 1.70158f, c3 = c1 + 1; return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2); }
    private static float clamp01(float v) { return Math.max(0, Math.min(1, v)); }
    private static float sin(double v) { return (float) Math.sin(v); }
    private int pageOf(int i) { return i / PAGE; }
    private int pages() { return apps.isEmpty() ? 0 : pageOf(apps.size() - 1) + 1; }
    private float slotY(int i) { return H * .37f + i * H * .056f; }
    private float slotX(int idx) { return W * .49f + jit(idx, 1) * W * .025f; }

    // ---- state from the activity ----
    void setApps(List<App> list) {
        String keep = apps.isEmpty() ? null : apps.get(sel).pkg;
        apps = list;
        sel = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).pkg.equals(keep)) sel = i;
        pos = sel - pageOf(sel) * PAGE;
        invalidate();
    }

    /** Back on the home screen (after an app, or on Home): replay the entrance. */
    void enter(boolean toTop) {
        confirmT = -1;
        launched = false;
        intro = host.hasVideo() ? 1 : 0;  // the video brings its own flash + splash
        pop = 0;
        if (toTop) { sel = 0; pos = 0; }
        last = 0;
        invalidate();
    }

    boolean busy() { return confirmT >= 0; }

    void move(int d, boolean repeat) {
        long now = SystemClock.uptimeMillis();
        if (busy() || apps.isEmpty() || (repeat && now - lastMoveAt < REPEAT_MS)) return;
        lastMoveAt = now;
        int n = apps.size(), next = (sel + d + n) % n;
        boolean flip = pageOf(next) != pageOf(sel);
        if (flip) { intro = .38f; pos = next - pageOf(next) * PAGE; }  // new page: splash it in
        if (flip || now - lastSoundAt >= SOUND_MS) { host.play(flip ? "page" : "move"); lastSoundAt = now; }
        sel = next;
        pop = 0;
        invalidate();
    }

    /** Left/right jump a whole page (wrapping), keeping the cursor's slot where the new page has one. */
    void flipPage(int d, boolean repeat) {
        long now = SystemClock.uptimeMillis();
        int pages = pages();
        if (busy() || pages < 2 || (repeat && now - lastMoveAt < PAGE_REPEAT_MS)) return;
        lastMoveAt = lastSoundAt = now;
        int slot = sel - pageOf(sel) * PAGE, np = (pageOf(sel) + d + pages) % pages;
        sel = np * PAGE + Math.min(slot, Math.min(PAGE, apps.size() - np * PAGE) - 1);
        intro = .38f;
        pos = sel - np * PAGE;
        pop = 0;
        host.play("page");
        invalidate();
    }

    void confirm() {
        if (busy() || apps.isEmpty()) return;
        confirmT = 0;
        host.play("confirm");
        invalidate();
    }

    // ---- frame ----
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w;
        H = h;
        water = null;  // rebuilt for the new size on next use
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) { last = 0; invalidate(); }
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min(.1f, (now - last) / 1000f);
        last = now;
        float t = (now % 3600000L) / 1000f;
        if (!apps.isEmpty()) pos += (sel - pageOf(sel) * PAGE - pos) * Math.min(1, dt * 20);
        pop = Math.min(1, pop + dt * 9);
        intro = Math.min(1, intro + dt / .9f);
        if (confirmT >= 0) {
            confirmT += dt;
            if (confirmT > .45f && !launched) { launched = true; host.launch(apps.get(sel)); }
        }
        tickClock();
        boolean video = host.hasVideo();
        float vs = video ? host.introVideoSeconds() : -1;
        float introE = ease(clamp01((intro - .15f) / .85f)) * (vs < 0 ? 1 : ease(clamp01((vs - .17f) / .6f)));

        if (!video) { drawWater(c, t, dt); drawSlab(c, dt, introE); }
        drawInfo(c, introE);
        if (!apps.isEmpty()) {
            drawCard(c, introE);
            drawHints(c);
            drawMenuItems(c, introE, t);
            drawWash(c);
            drawSelected(c, introE, t);
        }
        drawBlob(c, t);
        drawSplash(c);
        if (vs == 0) c.drawColor(0xFFFDFDFD);  // video not on screen yet: hold the white flash the clip opens with
        if (hasWindowFocus() || confirmT >= 0) postInvalidateOnAnimation();  // vsync-paced: 60 fps on the TV, idle when hidden
    }

    // ---- drawing helpers ----
    private void fill(int color, int alpha) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        if (alpha < 255) p.setAlpha(Color.alpha(color) * alpha / 255);
    }

    private void poly(Canvas c, float... xy) {
        path.rewind();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        path.close();
        c.drawPath(path, p);
    }

    private void tri(Canvas c, float cx, float cy, float s, char dir) {
        switch (dir) {
            case 'u': poly(c, cx, cy - s, cx + s, cy + .8f * s, cx - s, cy + .8f * s); break;
            case 'd': poly(c, cx, cy + s, cx + s, cy - .8f * s, cx - s, cy - .8f * s); break;
            case 'l': poly(c, cx - s, cy, cx + .8f * s, cy - s, cx + .8f * s, cy + s); break;
            default: poly(c, cx + s, cy, cx - .8f * s, cy - s, cx - .8f * s, cy + s);
        }
    }

    private void heavy(Canvas c, String s, float x, float y, float size, int color, Paint.Align align, int alpha) {
        tp.reset();
        tp.setAntiAlias(true);
        tp.setTypeface(heavyFace);
        tp.setTextSize(size);
        tp.setTextAlign(align);
        tp.setLetterSpacing(-0.04f);
        tp.setTextSkewX(-0.08f);  // extra lean on top of the italic
        tp.setColor(color);
        tp.setAlpha(alpha);
        c.drawText(s, x, y, tp);
    }

    private float measure(String s, float size) {
        tp.reset();
        tp.setTypeface(heavyFace);
        tp.setTextSize(size);
        tp.setLetterSpacing(-0.04f);
        return tp.measureText(s);
    }

    private void ui(Typeface face, float size, Paint.Align align, int color) {
        tp.reset();
        tp.setAntiAlias(true);
        tp.setTypeface(face);
        tp.setTextSize(size);
        tp.setTextAlign(align);
        tp.setColor(color);
    }

    private void blob(float cx, float cy, float r, float a, float b, float t) {
        path.rewind();
        for (int k = 0; k <= 48; k++) {
            float ang = k / 48f * TAU, rr = r * (1 + .12f * sin(ang * 3 + a + t * 6) + .06f * sin(ang * 7 + b - t * 9));
            float x = cx + (float) Math.cos(ang) * rr, y = cy + (float) Math.sin(ang) * rr;
            if (k == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
    }

    // ---- procedural water: diagonal light, caustic network near the surface, bubbles ----
    private void buildWater() {
        water = new LinearGradient(W * .85f, -H * .1f, W * .25f, H * 1.05f,
                new int[]{0xFF52E2FF, 0xFF2FB4FB, 0xFF1B79EE, 0xFF1146DC, 0xFF0A26B8}, new float[]{0, .22f, .45f, .7f, 1}, Shader.TileMode.CLAMP);
        causticH = (float) Math.floor(H * .62f);  // whole pixels: a fractional edge row escapes the fade mask as a bright line
        Bitmap tex = causticTexture(256);
        caustic1 = new BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        caustic2 = new BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        causticFade = new LinearGradient(0, 0, 0, causticH, new int[]{0xFF000000, 0x8C000000, 0x00000000}, new float[]{0, .45f, 1}, Shader.TileMode.CLAMP);
    }

    /** Dave Hoskins' tileable water caustic, evaluated once into a texture (shared formula with the prototype). */
    static Bitmap causticTexture(int size) {
        int[] px = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double pxv = (double) x / size * Math.PI * 2 - 250, pyv = (double) y / size * Math.PI * 2 - 250, ix = pxv, iy = pyv, col = 1;
                for (int n = 0; n < 5; n++) {
                    double tt = 10 * (1 - 3.5 / (n + 1));
                    double nx = pxv + Math.cos(tt - ix) + Math.sin(tt + iy), ny = pyv + Math.sin(tt - iy) + Math.cos(tt + ix);
                    ix = nx;
                    iy = ny;
                    col += 1 / Math.hypot(pxv / (Math.sin(ix + tt) / .005), pyv / (Math.cos(iy + tt) / .005));
                }
                col = 1.17 - Math.pow(col / 5, 1.4);
                double v = Math.min(1, Math.pow(Math.abs(col), 8));
                px[y * size + x] = ((int) (v * 255) << 24) | (210 << 16) | (250 << 8) | 255;
            }
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
    }

    private void drawWater(Canvas c, float t, float dt) {
        if (water == null) buildWater();
        p.reset();
        p.setShader(water);
        c.drawRect(0, 0, W, H, p);
        // two scrolling copies of the caustic texture, faded out below the surface, added onto the water
        c.saveLayer(0, 0, W, causticH, layerPaint);
        p.reset();
        m.setScale(5.2f, 2.2f);
        m.postTranslate(18 * t, -6 * t);
        caustic1.setLocalMatrix(m);
        p.setShader(caustic1);
        c.drawRect(0, 0, W, causticH, p);
        m.setScale(-4.4f, 2f);
        m.postTranslate(-12 * t + 180, 4 * t + 80);
        caustic2.setLocalMatrix(m);
        p.setShader(caustic2);
        p.setBlendMode(BlendMode.PLUS);
        c.drawRect(0, 0, W, causticH, p);
        p.reset();
        p.setShader(causticFade);
        p.setBlendMode(BlendMode.DST_IN);
        c.drawRect(0, 0, W, causticH, p);
        c.restore();
        fill(0x7396E1FF, 255);
        for (float[] b : bubbles) {
            b[1] -= b[3] * dt;
            if (b[1] < -.03f) b[1] = 1.03f;
            c.drawCircle(b[0] * W + sin(t * 1.1f + b[4]) * 8, b[1] * H, b[2], p);
        }
    }

    // ---- left: white slab (or the user's art) + flecks ----
    private void drawSlab(Canvas c, float dt, float introE) {
        fill(Color.WHITE, 255);
        poly(c, 0, 0, W * .25f, 0, W * .19f, H * .42f, W * .23f, H * .47f, W * .11f, H, 0, H);
        fill(0xFF3FD0FF, 255);
        poly(c, W * .25f, 0, W * .262f, 0, W * .2f, H * .43f, W * .19f, H * .42f);
        fill(0xFF1E7CFF, 255);
        poly(c, W * .23f, H * .47f, W * .245f, H * .48f, W * .13f, H, W * .11f, H);
        Drawable art = host.art();
        if (art != null && art.getIntrinsicHeight() > 0) {  // art slot: fit to screen height, centred over the slab
            float h = H * 1.02f, w = h * art.getIntrinsicWidth() / art.getIntrinsicHeight(), x = W * .2f - w / 2 - (1 - introE) * W * .05f;
            art.setBounds((int) x, (int) (H - h), (int) (x + w), H);
            art.setAlpha((int) (255 * introE));
            art.draw(c);
        }
        int[] cols = {0xD9FF5ABE, 0xE6FFFFFF, 0xB39646FF};
        for (float[] s : flecks) {
            s[1] -= s[4] * dt;
            if (s[1] < -.05f) s[1] = 1.05f;
            s[3] += dt * 15;
            float z = s[2] * H;
            c.save();
            c.translate(s[0] * W, s[1] * H);
            c.rotate(s[3]);
            fill(cols[(int) s[5]], 255);
            poly(c, -z, 0, z, -z * .3f, z * .2f, z * .4f);
            c.restore();
        }
    }

    // ---- giant vertical weekday + clock box ----
    private void drawInfo(Canvas c, float introE) {
        float big = W * .16f, daySize = Math.min(big, big * H * .95f / Math.max(1, measure(day, big)));
        c.save();
        c.translate(W * .118f - (1 - introE) * W * .15f, H * .985f);
        c.rotate(-90);
        heavy(c, day, H * .05f, -daySize * .62f, daySize, 0xFFA9A9A9, Paint.Align.LEFT, 255);
        heavy(c, day, 0, 0, daySize, 0xFF0B0B12, Paint.Align.LEFT, 255);
        c.restore();
        float l = W * .125f, tp0 = H * .05f, r = W * .245f, b = H * .14f;
        fill(Color.WHITE, 255);
        c.drawRect(l, tp0, r, b, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(0xFF333333);
        c.drawRect(l, tp0, r, b, p);
        ui(uiMedium, H * .05f, Paint.Align.LEFT, 0xFF111111);
        c.drawText(time, l + W * .01f, tp0 + H * .055f, tp);
        ui(uiSemi, H * .016f, Paint.Align.LEFT, 0xFF111111);
        c.drawText(date, l + W * .01f, tp0 + H * .078f, tp);
    }

    // ---- preview card: the app's own banner (or icon) in a tilted white frame with a pink offset ----
    private void drawCard(Canvas c, float introE) {
        Drawable d = apps.get(sel).art;
        if (d == null) return;
        float e = ease(pop), cw = W * .25f, ch = cw * 9 / 16, cx = W * .8f + (1 - e) * W * .05f + (1 - introE) * W * .3f, cy = H * .4f;
        int alpha = (int) (255 * introE * Math.min(1, pop * 3));
        c.save();
        c.translate(cx, cy);
        c.rotate(-6);
        fill(0xFFFF6FD0, alpha);
        c.drawRect(-cw / 2 + 5, -ch / 2 + 5, cw / 2 + 19, ch / 2 + 19, p);
        fill(Color.WHITE, alpha);
        c.drawRect(-cw / 2 - 7, -ch / 2 - 7, cw / 2 + 7, ch / 2 + 7, p);
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw > 0 && ih > 0 && (float) iw / ih > 1.4f) d.setBounds((int) (-cw / 2), (int) (-ch / 2), (int) (cw / 2), (int) (ch / 2));
        else { int s = (int) (ch * .72f); d.setBounds(-s / 2, -s / 2, s / 2, s / 2); }
        d.setAlpha(alpha);
        d.draw(c);
        c.restore();
    }

    // ---- bottom-right command + button hints ----
    private void drawHints(Canvas c) {
        String hint = apps.get(sel).pkg.equals(Home.SETTINGS_PKG) ? "Open Settings" : "Launch App";
        ui(uiSemi, H * .043f, Paint.Align.RIGHT, Color.WHITE);
        c.drawText(hint, W * .965f, H * .89f, tp);
        float lx = W * .965f - tp.measureText(hint);
        ui(uiSemi, H * .015f, Paint.Align.LEFT, Color.WHITE);
        c.drawText("Command", lx, H * .91f, tp);
        fill(Color.WHITE, 255);
        p.setStrokeWidth(1.5f);
        c.drawLine(lx + tp.measureText("Command "), H * .906f, W, H * .906f, p);
        float x = W * .965f;
        if (pages() > 1) x = button(c, x, H * .962f, "Page", "lr") - W * .012f;
        x = button(c, x, H * .962f, "Select", "ud");
        button(c, x - W * .012f, H * .962f, "Confirm", "OK");
    }

    private float button(Canvas c, float right, float y, String word, String glyph) {
        ui(uiSemi, H * .036f, Paint.Align.RIGHT, Color.WHITE);
        c.drawText(word, right, y, tp);
        float r = H * .016f, cx = right - tp.measureText(word) - r - H * .006f, cy = y - H * .012f;
        fill(Color.WHITE, 255);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        c.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.FILL);
        if (glyph.equals("ud")) { tri(c, cx, cy - r * .38f, r * .3f, 'u'); tri(c, cx, cy + r * .38f, r * .3f, 'd'); }
        else if (glyph.equals("lr")) { tri(c, cx - r * .38f, cy, r * .3f, 'l'); tri(c, cx + r * .38f, cy, r * .3f, 'r'); }
        else { ui(uiSemi, r * .95f, Paint.Align.CENTER, Color.WHITE); c.drawText(glyph, cx, cy + r * .35f, tp); }
        return cx - r;
    }

    // ---- the menu ----
    private void drawMenuItems(Canvas c, float introE, float t) {
        int first = pageOf(sel) * PAGE, count = Math.min(PAGE, apps.size() - first), s = sel - first, alpha = (int) (255 * introE);
        c.save();
        c.translate(sin(t * .9f) * 4 - (1 - introE) * W * .25f, sin(t * 1.3f) * 3);  // idle sway + intro slide
        for (int i = count - 1; i >= 0; i--) {  // bottom first so higher items overlap lower ones
            if (i == s) continue;
            int idx = first + i;
            float d = i - pos;
            float off = d < 0 ? -H * .018f * Math.min(1, -d) : H * .03f * Math.min(1, d);  // neighbours make room
            String label = apps.get(idx).label;
            float size = H * (.078f + .008f * jit(idx, 3)), w = measure(label, size);
            if (w > W * .3f) size *= W * .3f / w;
            c.save();
            c.translate(slotX(idx), slotY(i) + off);
            c.rotate(-14 + jit(idx, 2) * 4);  // reference lines tilt -9..-21 degrees
            heavy(c, label, 0, 0, size, CYAN[idx % 4], Paint.Align.CENTER, alpha);
            c.restore();
        }
        int pages = pages();
        if (pages > 1) {
            String label = (pageOf(sel) + 1) + " / " + pages;
            ui(uiSemi, H * .024f, Paint.Align.CENTER, Color.WHITE);
            tp.setAlpha((int) (.85f * alpha));
            float px = W * .49f, py = slotY(count) + H * .045f, hw = tp.measureText(label) / 2 + H * .022f;
            c.drawText(label, px, py, tp);
            fill(Color.WHITE, (int) (.85f * alpha));
            tri(c, px - hw, py - H * .008f, H * .008f, 'l');
            tri(c, px + hw, py - H * .008f, H * .008f, 'r');
        }
        c.restore();
    }

    private void drawSelected(Canvas c, float introE, float t) {
        int first = pageOf(sel) * PAGE, alpha = (int) (255 * introE);
        String label = apps.get(sel).label;
        float e = ease(pop), k1 = confirmT < 0 ? 0 : clamp01(confirmT / .12f);
        float size = H * .1f, w = measure(label, size);
        if (w > W * .34f) { size *= W * .34f / w; w = W * .34f; }
        int boil = (int) Math.floor(t * 8);  // hand-drawn jitter, ~8 redraws a second
        float jx = jit(boil, 4) * 2.5f, jy = jit(boil, 5) * 2;
        c.save();
        c.translate(slotX(sel) + sin(t * .9f) * 4 - (1 - introE) * W * .25f - W * .06f * ease(k1), slotY(sel - first) + sin(t * 1.3f) * 3);
        c.rotate(-15);
        float sc = (.8f + .2f * e) * (1 + .45f * ease(k1));
        c.scale(sc, sc);
        if (k1 < .5f) {
            // short white wedge from just left of the word up to a point past its end, pink fringe below
            float sweep = Math.min(1, pop * 1.6f), tx = -w * .62f, ty = size * .12f;
            float tipx = tx + (w * .68f - tx) * sweep + jx, tipy = ty + (-size * 1.05f - ty) * sweep + jy;
            float cox = tx + (w * .5f - tx) * sweep + jx, coy = ty + (-size * .1f - ty) * sweep + jy;
            fill(0xFFFF6FD0, alpha);
            poly(c, tx + 5, ty + 6, tipx + 5, tipy + 6, cox + 5, coy + 6);
            fill(Color.WHITE, alpha);
            poly(c, tx, ty, tipx, tipy, cox, coy);
            heavy(c, label, -size * .045f + jx, size * .035f, size, 0xFF0B0B12, Paint.Align.CENTER, alpha);  // black offset shadow
            heavy(c, label, jx * .5f, 0, size, 0xFFEC0F2A, Paint.Align.CENTER, alpha);                      // red face
        } else {
            heavy(c, label, 0, 0, size, 0xFF0B0B12, Paint.Align.CENTER, alpha);  // confirm: label goes solid black
        }
        c.restore();
    }

    // ---- confirm: blue wash, then a dark ink blob swallows the screen ----
    private void drawWash(Canvas c) {
        if (confirmT < 0) return;
        c.drawColor(Color.argb((int) (.7f * 255 * clamp01(confirmT / .12f)), 10, 59, 216));
    }

    private void drawBlob(Canvas c, float t) {
        float k = confirmT < 0 ? 0 : clamp01((confirmT - .1f) / .3f);
        if (k <= 0) return;
        blob(W * .2f, H * .38f, k * k * W * 1.3f, 0, 1, t);
        fill(0xFF0B1D5A, 255);
        c.drawPath(path, p);
    }

    // ---- entrance / page splash: white flash, cel-shaded blobs with darker rims, bubble rings ----
    /**
     * Each splash blob's outline never changes, only its size, position and fade, so it is drawn once into
     * a sprite and scaled per frame. Re-tessellating 11 big outlined paths every frame dropped ~25% of frames.
     */
    private void buildSplashSprites() {
        splashSprites = new Bitmap[splash.length];
        for (int i = 0; i < splash.length; i++) {
            float[] s = splash[i];
            Bitmap b = Bitmap.createBitmap(SPRITE, SPRITE, Bitmap.Config.ARGB_8888);
            Canvas sc = new Canvas(b);
            blob(SPRITE / 2f, SPRITE / 2f, SPRITE_R, s[4], s[5], 0);
            fill(s[6] > 0 ? 0xFFC8F5FF : 0xFF8FE2FF, 255);
            sc.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            p.setColor(0xFF4FB8F5);
            sc.drawPath(path, p);
            splashSprites[i] = b;
        }
    }

    private void drawSplash(Canvas c) {
        if (intro >= 1) return;
        int a = (int) (255 * Math.pow(1 - intro, 1.4));
        spritePaint.setAlpha(a);
        for (int i = 0; i < splash.length; i++) {
            float[] s = splash[i];
            float r = s[2] * (.5f + intro * 1.6f), half = r / SPRITE_R * SPRITE / 2, cx = s[0] * W, cy = (s[1] - intro * s[3] * .8f) * H;
            spriteRect.set(cx - half, cy - half, cx + half, cy + half);
            c.drawBitmap(splashSprites[i], null, spriteRect, spritePaint);
        }
        for (float[] r : rings) {
            fill(0xFFD8F8FF, a);
            if (r[4] == 0) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); }
            c.drawCircle(r[0] * W, (r[1] - intro * r[3]) * H, r[2], p);
        }
        float flash = clamp01(1 - intro / .3f);
        if (flash > 0) c.drawColor(Color.argb((int) (255 * flash), 253, 253, 253));
    }

    private void tickClock() {
        long ms = System.currentTimeMillis();
        if (ms - clockAt < 1000) return;
        clockAt = ms;
        Date d = new Date(ms);
        day = new SimpleDateFormat("EEEE", Locale.ENGLISH).format(d).toUpperCase(Locale.ROOT);
        time = new SimpleDateFormat(DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm", Locale.getDefault()).format(d);
        date = new SimpleDateFormat("EEE MMM d", Locale.ENGLISH).format(d).toLowerCase(Locale.ROOT);
    }
}
