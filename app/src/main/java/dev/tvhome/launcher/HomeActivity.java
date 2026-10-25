package dev.tvhome.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * Apple TV style home screen. No dependencies, no blur, no video, no network polling.
 * Layout: header (greeting, clock, date, pills) / big focused name / dock band / app grid.
 */
public class HomeActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int HEADER_DP = 170;
    private static final int BIGNAME_DP = 60;
    private static final int DEFAULT_GLOW = 0xFF4A6FA5;

    private float density;
    private int screenW, screenH, cols, pad, gap, bandPad, tileW, tileH, nameRoom;
    private SharedPreferences prefs;
    private Typeface thin, light;

    private FrameLayout root, content;
    private ScrollView scroll;
    private LinearLayout column, dockBand, gridBox, dropdown;
    private TextView bigName, greeting, pillInputs, pillWifi, pillSettings;
    private View highlight, glow;

    private boolean ddOpen = false;
    private boolean firstFocus = true;
    private String sig = "";
    private final List<Tile> dockTiles = new ArrayList<>();
    private final List<Tile> gridTiles = new ArrayList<>();

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        light = Typeface.create("sans-serif-light", Typeface.NORMAL);

        String[] dockCfg = getResources().getStringArray(R.array.dock_apps);
        cols = Math.max(4, Math.min(7, dockCfg.length));
        pad = dp(48);
        gap = dp(18);
        bandPad = dp(16);
        nameRoom = dp(26);
        tileW = (screenW - 2 * pad - 2 * bandPad - (cols - 1) * gap) / cols;
        tileH = tileW * 9 / 16;

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        buildSkeleton();
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGreeting();
        updateWifi();
        String now = computeSig();
        if (!now.equals(sig)) {
            sig = now;
            rebuild();
            afterLayout(this::focusLast);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (ddOpen) closeDropdown(false);
        focusDock();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (ddOpen) {
            closeDropdown(true);
            return;
        }
        focusDock();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            launchApp(new Intent(Settings.ACTION_SETTINGS));
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---------------------------------------------------------------- skeleton

    private void buildSkeleton() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        content = new FrameLayout(this);
        scroll.addView(content, new FrameLayout.LayoutParams(MATCH, WRAP));

        // ambient glow sits under everything else
        glow = new View(this);
        glow.setAlpha(0f);
        content.addView(glow, new FrameLayout.LayoutParams(dp(480), dp(360)));

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, 0, pad, dp(40));
        content.addView(column, new FrameLayout.LayoutParams(MATCH, WRAP));

        // header: greeting / clock / date on the left, pills on the right
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(0, dp(30), 0, 0);
        column.addView(header, new LinearLayout.LayoutParams(MATCH, dp(HEADER_DP)));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        header.addView(left, new LinearLayout.LayoutParams(0, MATCH, 1f));

        greeting = text(22, light, 0xCCFFFFFF);
        left.addView(greeting, new LinearLayout.LayoutParams(WRAP, WRAP));

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68);
        clock.setTypeface(thin);
        clock.setTextColor(Color.WHITE);
        clock.setIncludeFontPadding(false);
        left.addView(clock, new LinearLayout.LayoutParams(WRAP, WRAP));

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        date.setTypeface(light);
        date.setTextColor(0x99FFFFFF);
        left.addView(date, new LinearLayout.LayoutParams(WRAP, WRAP));

        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(pillRow, new LinearLayout.LayoutParams(WRAP, WRAP));

        pillInputs = pill("Inputs");
        pillWifi = pill("Wi-Fi");
        pillSettings = pill("Settings");
        for (TextView p : new TextView[]{pillInputs, pillWifi, pillSettings}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.leftMargin = dp(12);
            pillRow.addView(p, lp);
        }
        pillInputs.setOnClickListener(v -> openDropdown());
        pillWifi.setOnClickListener(v -> launchApp(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        pillSettings.setOnClickListener(v -> launchApp(new Intent(Settings.ACTION_SETTINGS)));

        // spacer so the dock sits at the bottom of the first screen
        int bandH = bandPad * 2 + tileH + nameRoom;
        int spacer = Math.max(dp(8), screenH - dp(HEADER_DP) - dp(BIGNAME_DP) - bandH - dp(28));
        column.addView(new View(this), new LinearLayout.LayoutParams(MATCH, spacer));

        bigName = text(40, light, Color.WHITE);
        bigName.setGravity(Gravity.BOTTOM);
        bigName.setSingleLine(true);
        bigName.setPadding(bandPad, 0, 0, dp(6));
        column.addView(bigName, new LinearLayout.LayoutParams(MATCH, dp(BIGNAME_DP)));

        dockBand = new LinearLayout(this);
        dockBand.setOrientation(LinearLayout.HORIZONTAL);
        dockBand.setPadding(bandPad, bandPad, bandPad, bandPad);
        dockBand.setBackground(rounded(0x1AFFFFFF, dp(26)));
        column.addView(dockBand, new LinearLayout.LayoutParams(MATCH, WRAP));

        gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        gridBox.setPadding(bandPad, dp(28), bandPad, 0);
        column.addView(gridBox, new LinearLayout.LayoutParams(MATCH, WRAP));

        // the single glass highlight sits on top and springs between tiles
        highlight = new View(this);
        GradientDrawable glass = rounded(0x26FFFFFF, dp(14));
        glass.setStroke(dp(2), 0x99FFFFFF);
        highlight.setBackground(glass);
        highlight.setAlpha(0f);
        content.addView(highlight, new FrameLayout.LayoutParams(1, 1));

        // inputs dropdown overlay
        dropdown = new LinearLayout(this);
        dropdown.setOrientation(LinearLayout.VERTICAL);
        dropdown.setPadding(dp(8), dp(8), dp(8), dp(8));
        dropdown.setBackground(rounded(0xF01C1C1E, dp(18)));
        dropdown.setVisibility(View.GONE);
        root.addView(dropdown, new FrameLayout.LayoutParams(dp(260), WRAP));
    }

    private TextView text(float sp, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private TextView pill(String label) {
        TextView t = text(15, light, Color.WHITE);
        t.setText(label);
        t.setPadding(dp(20), dp(10), dp(20), dp(10));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, rounded(0x73FFFFFF, dp(22)));
        sl.addState(new int[]{}, rounded(0x29FFFFFF, dp(22)));
        t.setBackground(sl);
        t.setId(View.generateViewId());
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener((v, has) -> onPillFocus(has));
        return t;
    }

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening");
    }

    private void updateWifi() {
        String label = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            String s = wi == null ? null : wi.getSSID();
            if (s != null && !s.contains("unknown") && !s.equals("0x") && s.length() > 0) {
                label = s.replace("\"", "");
            }
        } catch (Exception ignored) {
        }
        pillWifi.setText(label);
    }

    // ---------------------------------------------------------------- tiles

    private class Tile extends FrameLayout {
        final String key;
        final String label;
        final boolean dock;
        final TextView nameView;
        int glowColor = DEFAULT_GLOW;

        Tile(String key, String label, Drawable art, boolean isIcon, String glyph, boolean dock, final Runnable action) {
            super(HomeActivity.this);
            this.key = key;
            this.label = label;
            this.dock = dock;
            setId(View.generateViewId());
            setFocusable(true);
            setClickable(true);
            setPivotX(tileW / 2f);
            setPivotY(tileH / 2f);

            ImageView img = new ImageView(HomeActivity.this);
            img.setBackgroundColor(0xFF1C1C1E);
            img.setScaleType(isIcon ? ImageView.ScaleType.CENTER_INSIDE : ImageView.ScaleType.CENTER_CROP);
            if (art != null) img.setImageDrawable(art);
            img.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
                }
            });
            img.setClipToOutline(true);
            addView(img, new FrameLayout.LayoutParams(tileW, tileH));

            if (art == null) {
                TextView g = text(14, light, 0xCCFFFFFF);
                g.setText(glyph);
                g.setGravity(Gravity.CENTER);
                addView(g, new FrameLayout.LayoutParams(tileW, tileH));
            } else {
                glowColor = sample(art);
            }

            nameView = text(14, light, Color.WHITE);
            nameView.setText(label);
            nameView.setGravity(Gravity.CENTER);
            nameView.setSingleLine(true);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nameView.setAlpha(0f);
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(tileW, nameRoom);
            nlp.topMargin = tileH;
            addView(nameView, nlp);

            setOnClickListener(v -> action.run());
            setOnFocusChangeListener((v, has) -> onTileFocus(Tile.this, has));
        }
    }

    private int sample(Drawable art) {
        try {
            Drawable.ConstantState cs = art.getConstantState();
            if (cs == null) return DEFAULT_GLOW;
            Drawable d = cs.newDrawable().mutate();
            int s = 16;
            Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            d.setBounds(0, 0, s, s);
            d.draw(cv);
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < s; y++) {
                for (int x = 0; x < s; x++) {
                    int p = bmp.getPixel(x, y);
                    if (Color.alpha(p) < 64) continue;
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    n++;
                }
            }
            bmp.recycle();
            if (n == 0) return DEFAULT_GLOW;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
            hsv[1] = Math.max(hsv[1], 0.45f);
            hsv[2] = Math.max(hsv[2], 0.8f);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return DEFAULT_GLOW;
        }
    }

    private void onPillFocus(boolean has) {
        if (!has) return;
        highlight.animate().alpha(0f).setDuration(120).start();
        glow.animate().alpha(0f).setDuration(200).start();
        bigName.setText("");
        scroll.smoothScrollTo(0, 0);
    }

    private void onTileFocus(Tile t, boolean has) {
        float s = has ? 1.08f : 1f;
        t.animate().scaleX(s).scaleY(s).setDuration(150).start();
        t.nameView.animate().alpha(has ? 1f : 0f).setDuration(150).start();
        if (!has) return;
        moveHighlight(t);
        SharedPreferences.Editor ed = prefs.edit().putString("last", t.key);
        if (t.dock) {
            bigName.setText(t.label);
            moveGlow(t);
            scroll.smoothScrollTo(0, 0);
            ed.putString("lastDock", t.key);
        } else {
            bigName.setText("");
            glow.animate().alpha(0f).setDuration(200).start();
        }
        ed.apply();
    }

    private float[] offsetInContent(View v) {
        float x = 0, y = 0;
        while (v != content && v.getParent() instanceof View) {
            x += v.getLeft();
            y += v.getTop();
            v = (View) v.getParent();
        }
        return new float[]{x, y};
    }

    private void moveHighlight(Tile t) {
        float[] o = offsetInContent(t);
        int hp = dp(10);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) highlight.getLayoutParams();
        if (lp.width != tileW + 2 * hp || lp.height != tileH + 2 * hp) {
            lp.width = tileW + 2 * hp;
            lp.height = tileH + 2 * hp;
            highlight.setLayoutParams(lp);
        }
        float tx = o[0] - hp;
        float ty = o[1] - hp;
        if (firstFocus) {
            firstFocus = false;
            highlight.setTranslationX(tx);
            highlight.setTranslationY(ty);
            highlight.setAlpha(1f);
        } else {
            highlight.animate().alpha(1f).translationX(tx).translationY(ty)
                    .setDuration(300).setInterpolator(new OvershootInterpolator(1.2f)).start();
        }
    }

    private void moveGlow(Tile t) {
        float[] o = offsetInContent(t);
        float cx = o[0] + tileW / 2f;
        float bandTop = column.getTop() + dockBand.getTop();
        int rgb = t.glowColor & 0x00FFFFFF;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(180));
        g.setColors(new int[]{0x70000000 | rgb, rgb});
        glow.setBackground(g);
        glow.animate().alpha(1f).translationX(cx - dp(240)).translationY(bandTop - dp(30) - dp(180))
                .setDuration(300).start();
    }

    // ---------------------------------------------------------------- build

    private Intent leanbackIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
    }

    private String computeSig() {
        StringBuilder sb = new StringBuilder();
        List<ResolveInfo> ris = getPackageManager().queryIntentActivities(leanbackIntent(), 0);
        for (ResolveInfo ri : ris) {
            sb.append(ri.activityInfo.packageName).append('/').append(ri.activityInfo.name).append(';');
        }
        return sb.toString();
    }

    private String knownLabel(String pkg) {
        switch (pkg) {
            case "com.google.android.youtube.tv":
                return "YouTube";
            case "com.amazon.amazonvideo.livingroom":
                return "Prime Video";
            case "com.apple.atve.androidtv.appletv":
                return "Apple TV";
            case "com.netflix.ninja":
                return "Netflix";
            default:
                return pkg;
        }
    }

    private Drawable[] artFor(ActivityInfo ai, PackageManager pm) {
        Drawable art = ai.loadBanner(pm);
        if (art == null && ai.applicationInfo != null) art = ai.applicationInfo.loadBanner(pm);
        if (art != null) return new Drawable[]{art, null};
        return new Drawable[]{ai.loadIcon(pm), new ColorDrawable(0)}; // second slot non-null = icon fallback
    }

    private void rebuild() {
        firstFocus = true;
        final PackageManager pm = getPackageManager();
        dockBand.removeAllViews();
        gridBox.removeAllViews();
        dockTiles.clear();
        gridTiles.clear();

        String[] cfg = getResources().getStringArray(R.array.dock_apps);
        List<String> dockPkgs = new ArrayList<>();
        for (int i = 0; i < cols && i < cfg.length; i++) {
            String e = cfg[i].trim();
            Tile t;
            if (e.equals("special:inputs")) {
                t = new Tile(e, "Inputs", null, false, "HDMI / Live TV", true, this::openDropdown);
            } else {
                dockPkgs.add(e);
                final Intent li = pm.getLeanbackLaunchIntentForPackage(e);
                ActivityInfo ai = li == null ? null : li.resolveActivityInfo(pm, 0);
                if (ai != null) {
                    Drawable[] a = artFor(ai, pm);
                    t = new Tile(e, String.valueOf(ai.loadLabel(pm)), a[0], a[1] != null, "", true,
                            () -> launchApp(li));
                } else {
                    final String pkg = e;
                    String name = knownLabel(e);
                    t = new Tile(e, name, null, false, name + "\nnot installed", true, () -> openStore(pkg));
                }
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH + nameRoom);
            if (i < cols - 1) lp.rightMargin = gap;
            dockBand.addView(t, lp);
            dockTiles.add(t);
        }

        List<ResolveInfo> ris = pm.queryIntentActivities(leanbackIntent(), 0);
        Collections.sort(ris, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        LinearLayout rowLayout = null;
        int idx = 0;
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || dockPkgs.contains(pkg)) continue;
            if (idx % cols == 0) {
                rowLayout = new LinearLayout(this);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH, WRAP);
                rlp.bottomMargin = dp(10);
                gridBox.addView(rowLayout, rlp);
            }
            final Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Drawable[] a = artFor(ri.activityInfo, pm);
            Tile t = new Tile(pkg + "/" + ri.activityInfo.name, String.valueOf(ri.loadLabel(pm)),
                    a[0], a[1] != null, "", false, () -> launchApp(launch));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH + nameRoom);
            if (idx % cols < cols - 1) lp.rightMargin = gap;
            rowLayout.addView(t, lp);
            gridTiles.add(t);
            idx++;
        }
        link();
    }

    /** Deterministic D-pad: every direction is set explicitly, edges point back at themselves. */
    private void link() {
        TextView[] pills = {pillInputs, pillWifi, pillSettings};
        int pn = pills.length;
        int dn = dockTiles.size();
        int gn = gridTiles.size();

        for (int i = 0; i < pn; i++) {
            View p = pills[i];
            p.setNextFocusLeftId(i > 0 ? pills[i - 1].getId() : p.getId());
            p.setNextFocusRightId(i < pn - 1 ? pills[i + 1].getId() : p.getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(dn > 0 ? dockTiles.get(Math.min(dn - 1, i * (dn - 1) / (pn - 1))).getId() : p.getId());
        }
        for (int j = 0; j < dn; j++) {
            Tile t = dockTiles.get(j);
            t.setNextFocusLeftId(j > 0 ? dockTiles.get(j - 1).getId() : t.getId());
            t.setNextFocusRightId(j < dn - 1 ? dockTiles.get(j + 1).getId() : t.getId());
            t.setNextFocusUpId(pills[Math.min(pn - 1, j * (pn - 1) / Math.max(1, dn - 1))].getId());
            t.setNextFocusDownId(gn > 0 ? gridTiles.get(Math.min(j, gn - 1)).getId() : t.getId());
        }
        for (int i = 0; i < gn; i++) {
            Tile t = gridTiles.get(i);
            int r = i / cols;
            int c = i % cols;
            if (r == 0) {
                t.setNextFocusUpId(dn > 0 ? dockTiles.get(Math.min(c, dn - 1)).getId() : t.getId());
            } else {
                t.setNextFocusUpId(gridTiles.get(i - cols).getId());
            }
            if (i + cols < gn) {
                t.setNextFocusDownId(gridTiles.get(i + cols).getId());
            } else if ((r + 1) * cols < gn) {
                t.setNextFocusDownId(gridTiles.get(gn - 1).getId());
            } else {
                t.setNextFocusDownId(t.getId());
            }
            t.setNextFocusLeftId(c > 0 ? gridTiles.get(i - 1).getId() : t.getId());
            t.setNextFocusRightId(c < cols - 1 && i + 1 < gn ? gridTiles.get(i + 1).getId() : t.getId());
        }
    }

    // ---------------------------------------------------------------- focus helpers

    private void afterLayout(final Runnable r) {
        final ViewTreeObserver vto = root.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewTreeObserver o = root.getViewTreeObserver();
                if (o.isAlive()) o.removeOnGlobalLayoutListener(this);
                r.run();
            }
        });
    }

    private Tile findTile(String key) {
        if (key == null) return null;
        for (Tile t : dockTiles) if (t.key.equals(key)) return t;
        for (Tile t : gridTiles) if (t.key.equals(key)) return t;
        return null;
    }

    private void focusLast() {
        Tile t = findTile(prefs.getString("last", null));
        if (t == null) t = findTile(prefs.getString("lastDock", null));
        if (t == null && !dockTiles.isEmpty()) t = dockTiles.get(0);
        if (t != null) t.requestFocus();
    }

    private void focusDock() {
        Tile t = findTile(prefs.getString("lastDock", null));
        if (t == null && !dockTiles.isEmpty()) t = dockTiles.get(0);
        if (t != null) t.requestFocus();
    }

    // ---------------------------------------------------------------- actions

    private void launchApp(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void openStore(String pkg) {
        launchApp(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
    }

    // ---------------------------------------------------------------- inputs dropdown

    private TextView dropdownItem(String label) {
        TextView t = text(18, light, Color.WHITE);
        t.setText(label);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, rounded(0x59FFFFFF, dp(12)));
        sl.addState(new int[]{}, new ColorDrawable(0));
        t.setBackground(sl);
        t.setId(View.generateViewId());
        t.setFocusable(true);
        t.setClickable(true);
        return t;
    }

    private void openDropdown() {
        scroll.scrollTo(0, 0);
        dropdown.removeAllViews();
        List<TextView> items = new ArrayList<>();

        List<TvInputInfo> inputs = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim != null) inputs.addAll(tim.getTvInputList());
        } catch (Exception ignored) {
        }
        Collections.sort(inputs, (a, b) -> Integer.compare(a.getType(), b.getType()));

        for (final TvInputInfo info : inputs) {
            boolean tuner = info.getType() == TvInputInfo.TYPE_TUNER;
            if (!tuner && !info.isPassthroughInput()) continue;
            CharSequence l = null;
            try {
                l = info.loadCustomLabel(this);
            } catch (Exception ignored) {
            }
            if (l == null || l.length() == 0) l = info.loadLabel(this);
            TextView item = dropdownItem(String.valueOf(l));
            item.setOnClickListener(v -> {
                closeDropdown(false);
                switchInput(info);
            });
            items.add(item);
        }
        if (items.isEmpty()) items.add(dropdownItem("No inputs found"));

        for (int i = 0; i < items.size(); i++) {
            TextView it = items.get(i);
            it.setNextFocusLeftId(it.getId());
            it.setNextFocusRightId(it.getId());
            if (i == 0) it.setNextFocusUpId(it.getId());
            if (i == items.size() - 1) it.setNextFocusDownId(it.getId());
            dropdown.addView(it, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        int[] loc = new int[2];
        pillInputs.getLocationInWindow(loc);
        dropdown.setX(Math.min(loc[0], screenW - dp(260) - pad));
        dropdown.setY(loc[1] + pillInputs.getHeight() + dp(8));
        dropdown.setVisibility(View.VISIBLE);
        ddOpen = true;
        items.get(0).requestFocus();
    }

    private void closeDropdown(boolean refocus) {
        dropdown.setVisibility(View.GONE);
        ddOpen = false;
        if (refocus) pillInputs.requestFocus();
    }

    private void switchInput(TvInputInfo info) {
        Intent i;
        if (info.isPassthroughInput()) {
            i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()));
        } else {
            i = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
        }
        launchApp(i);
    }
}
