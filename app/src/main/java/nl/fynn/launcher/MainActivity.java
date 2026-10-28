package nl.fynn.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Rustige, Apple TV-achtige launcher met een vaste set apps.
 *
 * Bediening:
 *  - links / rechts + OK : app openen
 *  - OK lang indrukken   : app-info (of Menu / Info-knop)
 *  - omhoog              : tandwiel voor de Android-instellingen
 *  - Terug               : doet niets
 *
 * Andere apps? Pas de lijst APPS hieronder aan. Per app staan een of meer
 * pakketnamen; de eerste die op de box staat wordt gebruikt.
 */
public class MainActivity extends Activity {

    /** Een vaste plek op het startscherm. */
    private static final class Spec {
        final String label;
        final String tagline;
        final int accent;
        final String[] pkgs;

        Spec(String label, String tagline, int accent, String... pkgs) {
            this.label = label;
            this.tagline = tagline;
            this.accent = accent;
            this.pkgs = pkgs;
        }
    }

    /** Volgorde van de apps op het startscherm. */
    private static final Spec[] APPS = {
            new Spec("Netflix", "Series en films", 0xFFB81D24,
                    "com.netflix.mediaclient", "com.netflix.ninja"),
            new Spec("YouTube", "Video's en muziek", 0xFFEF2B2B,
                    "com.google.android.youtube.tv"),
            new Spec("Videoland", "Series, films en meer", 0xFFD6154B,
                    "nl.rtl.videoland.v2"),
            new Spec("NPO Start", "Live en gemist", 0xFFFF6A13,
                    "nl.uitzendinggemist"),
            new Spec("Spotify", "Muziek en podcasts", 0xFF1DB954,
                    "com.spotify.tv.android"),
    };

    /** Een app die echt geinstalleerd is. */
    private static final class Item {
        final Spec spec;
        final String pkg;
        final Intent intent;
        final Drawable banner; // mag null zijn
        final Drawable icon;

        Item(Spec spec, String pkg, Intent intent, Drawable banner, Drawable icon) {
            this.spec = spec;
            this.pkg = pkg;
            this.intent = intent;
            this.banner = banner;
            this.icon = icon;
        }
    }

    /** De onderdelen van een tegel, zodat we ze los kunnen animeren. */
    private static final class TileViews {
        Item item;
        View cell;     // wrapper voor de in- en uitvlieg-animaties
        View tile;     // de tegel zelf (focus-animaties)
        View parallax; // inhoud die iets meebeweegt
        View shine;    // lichtreflex
        View rim;      // dun randje bij focus
    }

    /** Wrapper zonder tussenlaag, zodat schaduwen niet worden afgeknipt tijdens alpha-animaties. */
    private static class Cell extends FrameLayout {
        Cell(Context context) {
            super(context);
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }
    }

    private static final int BASE_COLOR = 0xFF090A10;

    private final Interpolator smooth = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private GlowView glow;
    private LinearLayout topBar;
    private LinearLayout row;
    private LinearLayout captionBox;
    private TextView nameView;
    private TextView taglineView;
    private TextView greetingView;
    private final List<Item> items = new ArrayList<Item>();
    private final List<TileViews> tiles = new ArrayList<TileViews>();
    private int currentAccent = 0xFF3A4A8F;
    private ValueAnimator accentAnimator;
    private boolean launching;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        rebuildTiles(true);

        topBar.setAlpha(0f);
        topBar.setTranslationY(-dp(16));
        topBar.animate().alpha(1f).translationY(0f)
                .setStartDelay(150).setDuration(600).setInterpolator(smooth).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        greetingView.setText(greeting());
        glow.startDrift();
        launching = false;

        // Alleen opnieuw opbouwen als er iets veranderd is (app geinstalleerd of verwijderd).
        if (!sameAppsAsShown(loadItems())) {
            rebuildTiles(false);
        } else {
            resetCells();
            if (getCurrentFocus() == null && !tiles.isEmpty()) {
                tiles.get(0).tile.requestFocus();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        glow.stopDrift();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home-knop terwijl de launcher al open is: terug naar de eerste tegel.
        if (!tiles.isEmpty()) {
            tiles.get(0).tile.requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        // Een launcher sluit je niet af.
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof TileViews) {
                openAppInfo(((TileViews) f.getTag()).item);
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        glow = new GlowView(this);
        root.addView(glow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Bovenbalk: groet links, klok + tandwiel rechts.
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(64), dp(40), dp(64), 0);

        greetingView = new TextView(this);
        greetingView.setTextColor(Color.argb(230, 255, 255, 255));
        greetingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        greetingView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        greetingView.setText(greeting());
        topBar.addView(greetingView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END);

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setGravity(Gravity.END);
        clockBox.addView(time, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextColor(Color.argb(170, 255, 255, 255));
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        date.setGravity(Gravity.END);
        clockBox.addView(date, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        topBar.addView(clockBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topBar.addView(buildSettingsButton());

        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // Midden: de rij met tegels en daaronder de naam van de gekozen app.
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.setClipChildren(false);
        center.setClipToPadding(false);

        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        center.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        captionBox = new LinearLayout(this);
        captionBox.setOrientation(LinearLayout.VERTICAL);
        captionBox.setGravity(Gravity.CENTER_HORIZONTAL);

        nameView = new TextView(this);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        nameView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameView.setGravity(Gravity.CENTER);
        nameView.setShadowLayer(dp(8), 0, dp(2), Color.argb(150, 0, 0, 0));
        captionBox.addView(nameView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        taglineView = new TextView(this);
        taglineView.setTextColor(Color.argb(150, 255, 255, 255));
        taglineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        taglineView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        taglineView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.topMargin = dp(4);
        captionBox.addView(taglineView, tagLp);

        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.topMargin = dp(44);
        center.addView(captionBox, capLp);

        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        centerLp.topMargin = dp(24);
        root.addView(center, centerLp);

        setContentView(root);
    }

    private View buildSettingsButton() {
        ImageView gear = new ImageView(this);
        gear.setImageResource(android.R.drawable.ic_menu_preferences);
        gear.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        gear.setAlpha(0.7f);
        gear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        gear.setPadding(dp(10), dp(10), dp(10), dp(10));
        gear.setFocusable(true);
        gear.setClickable(true);

        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        gear.setBackground(ring);

        gear.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                ring.setColor(hasFocus ? Color.argb(70, 255, 255, 255) : Color.TRANSPARENT);
                float s = hasFocus ? 1.18f : 1f;
                v.animate().alpha(hasFocus ? 1f : 0.7f).scaleX(s).scaleY(s)
                        .rotation(hasFocus ? 45f : 0f)
                        .setStartDelay(0).setDuration(300).setInterpolator(smooth).start();
            }
        });
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException ex) {
                    toast("Instellingen zijn niet beschikbaar");
                }
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
        lp.leftMargin = dp(24);
        gear.setLayoutParams(lp);
        return gear;
    }

    // ---------------------------------------------------------------- tegels

    private void rebuildTiles(boolean animateIn) {
        items.clear();
        items.addAll(loadItems());
        tiles.clear();
        row.removeAllViews();

        if (items.isEmpty()) {
            nameView.setText("Geen apps gevonden");
            taglineView.setText("Installeer eerst een app via de Play Store");
            setAccent(0xFF3A4A8F);
            return;
        }

        // Tegels krimpen automatisch als er meer apps zijn.
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int n = items.size();
        float gapFrac = 0.025f;
        float frac = Math.min(0.20f, (0.92f - (n - 1) * gapFrac) / n);
        int tileW = Math.round(screenW * frac);
        int tileH = Math.round(tileW * 0.62f);
        int gap = Math.round(screenW * gapFrac);

        for (int i = 0; i < n; i++) {
            TileViews tv = buildTile(items.get(i), tileW, tileH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            lp.leftMargin = i == 0 ? 0 : gap;
            row.addView(tv.cell, lp);
            tiles.add(tv);

            if (animateIn) {
                tv.cell.setAlpha(0f);
                tv.cell.setTranslationY(dp(46));
                tv.cell.setScaleX(0.9f);
                tv.cell.setScaleY(0.9f);
                tv.cell.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                        .setStartDelay(220L + i * 90L).setDuration(650)
                        .setInterpolator(new OvershootInterpolator(0.9f)).start();
            }
        }

        tiles.get(0).tile.requestFocus();
        showFocused(items.get(0), animateIn);
    }

    private TileViews buildTile(final Item item, final int w, final int h) {
        final TileViews tv = new TileViews();
        tv.item = item;
        final float radius = h * 0.16f;

        Cell cell = new Cell(this);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);

        FrameLayout tile = new FrameLayout(this);
        tile.setTag(tv);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        tile.setElevation(dp(6));
        if (Build.VERSION.SDK_INT >= 28) {
            // Schaduw in de kleur van de app, in plaats van grijs.
            tile.setOutlineSpotShadowColor(item.spec.accent);
            tile.setOutlineAmbientShadowColor(item.spec.accent);
        }

        // Kaartkleur: een donkere variant van de merkkleur.
        tile.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{shade(item.spec.accent, 0.55f), shade(item.spec.accent, 0.26f)}));

        // Inhoud in een eigen laag, die bij focus iets inzoomt (parallax-gevoel).
        FrameLayout parallax = new FrameLayout(this);
        if (item.banner != null) {
            ImageView banner = new ImageView(this);
            banner.setImageDrawable(item.banner);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            parallax.addView(banner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(item.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            inner.addView(icon, new LinearLayout.LayoutParams(
                    Math.round(h * 0.46f), Math.round(h * 0.46f)));

            TextView label = new TextView(this);
            label.setText(item.spec.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(8);
            inner.addView(label, llp);

            parallax.addView(inner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        tile.addView(parallax, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Glans over de bovenste helft, voor een glazen look.
        View gloss = new View(this);
        gloss.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255)}));
        tile.addView(gloss, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(h * 0.5f), Gravity.TOP));

        // Lichtreflex: een schuine lichte baan die bij focus over de tegel glijdt.
        View shine = new View(this);
        shine.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.argb(0, 255, 255, 255), Color.argb(70, 255, 255, 255),
                        Color.argb(0, 255, 255, 255)}));
        shine.setRotation(16f);
        shine.setAlpha(0f);
        FrameLayout.LayoutParams shineLp = new FrameLayout.LayoutParams(
                Math.round(w * 0.45f), Math.round(h * 1.9f),
                Gravity.LEFT | Gravity.CENTER_VERTICAL);
        tile.addView(shine, shineLp);

        // Dun randje dat bij focus verschijnt.
        View rim = new View(this);
        GradientDrawable rimBg = new GradientDrawable();
        rimBg.setColor(Color.TRANSPARENT);
        rimBg.setCornerRadius(radius);
        rimBg.setStroke(Math.max(2, dp(2)), Color.argb(120, 255, 255, 255));
        rim.setBackground(rimBg);
        rim.setAlpha(0f);
        tile.addView(rim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cell.addView(tile, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tv.cell = cell;
        tv.tile = tile;
        tv.parallax = parallax;
        tv.shine = shine;
        tv.rim = rim;

        final int shineTravel = w;
        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? 1.14f : 1f;
                tv.tile.animate().scaleX(s).scaleY(s)
                        .translationZ(hasFocus ? dp(26) : 0f)
                        .setStartDelay(0).setDuration(320).setInterpolator(smooth).start();
                float inner = hasFocus ? 1.09f : 1f;
                tv.parallax.animate().scaleX(inner).scaleY(inner)
                        .setStartDelay(0).setDuration(520).setInterpolator(smooth).start();
                tv.rim.animate().alpha(hasFocus ? 1f : 0f)
                        .setStartDelay(0).setDuration(260).start();

                tv.shine.animate().cancel();
                if (hasFocus) {
                    tv.shine.setTranslationX(-shineTravel * 0.6f);
                    tv.shine.setAlpha(1f);
                    tv.shine.animate().translationX(shineTravel * 1.05f)
                            .setStartDelay(120).setDuration(780)
                            .setInterpolator(new AccelerateDecelerateInterpolator())
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    tv.shine.setAlpha(0f);
                                }
                            }).start();
                } else {
                    tv.shine.setAlpha(0f);
                }

                if (hasFocus) {
                    showFocused(item, true);
                }
            }
        });
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(tv);
            }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                openAppInfo(item);
                return true;
            }
        });
        return tv;
    }

    private void showFocused(Item item, boolean animate) {
        nameView.setText(item.spec.label);
        taglineView.setText(item.spec.tagline);
        captionBox.animate().cancel();
        if (animate) {
            captionBox.setAlpha(0f);
            captionBox.setTranslationY(dp(12));
            captionBox.animate().alpha(1f).translationY(0f)
                    .setStartDelay(60).setDuration(340).setInterpolator(smooth).start();
        } else {
            captionBox.setAlpha(1f);
            captionBox.setTranslationY(0f);
        }
        setAccent(item.spec.accent);
    }

    /** Laat de gloed op de achtergrond soepel naar de kleur van de gekozen app gaan. */
    private void setAccent(int target) {
        if (accentAnimator != null) {
            accentAnimator.cancel();
        }
        accentAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), currentAccent, target);
        accentAnimator.setDuration(650);
        accentAnimator.setInterpolator(smooth);
        accentAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                currentAccent = (Integer) a.getAnimatedValue();
                glow.setAccent(currentAccent);
            }
        });
        accentAnimator.start();
    }

    /** Zet alle tegels weer in de rusttoestand (na terugkomen uit een app). */
    private void resetCells() {
        for (TileViews tv : tiles) {
            tv.cell.animate().cancel();
            tv.cell.setAlpha(1f);
            tv.cell.setScaleX(1f);
            tv.cell.setScaleY(1f);
            tv.cell.setTranslationY(0f);
        }
        captionBox.animate().cancel();
        captionBox.setAlpha(1f);
        captionBox.setTranslationY(0f);
    }

    // ------------------------------------------------------------------ data

    private List<Item> loadItems() {
        PackageManager pm = getPackageManager();
        List<Item> found = new ArrayList<Item>();
        for (Spec spec : APPS) {
            for (String pkg : spec.pkgs) {
                Item item = loadItem(pm, spec, pkg);
                if (item != null) {
                    found.add(item);
                    break;
                }
            }
        }
        return found;
    }

    private Item loadItem(PackageManager pm, Spec spec, String pkg) {
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) {
            launch = pm.getLaunchIntentForPackage(pkg);
        }
        if (launch == null) {
            return null; // niet geinstalleerd
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        Drawable banner = null;
        Drawable icon = null;
        try {
            ComponentName cn = launch.getComponent();
            if (cn != null) {
                ActivityInfo ai = pm.getActivityInfo(cn, 0);
                banner = ai.loadBanner(pm);
                if (banner == null) {
                    banner = ai.applicationInfo.loadBanner(pm);
                }
                icon = ai.loadIcon(pm);
            }
            if (icon == null) {
                icon = pm.getApplicationIcon(pkg);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // laten we zoals het is
        }
        if (icon == null && banner == null) {
            return null;
        }
        return new Item(spec, pkg, launch, banner, icon);
    }

    private boolean sameAppsAsShown(List<Item> fresh) {
        if (fresh.size() != items.size()) {
            return false;
        }
        for (int i = 0; i < fresh.size(); i++) {
            if (!fresh.get(i).pkg.equals(items.get(i).pkg)) {
                return false;
            }
        }
        return true;
    }

    // --------------------------------------------------------------- acties

    private void launch(final TileViews chosen) {
        if (launching) {
            return;
        }
        launching = true;

        // De gekozen tegel zoomt naar je toe, de rest en de tekst vervagen.
        for (TileViews other : tiles) {
            if (other != chosen) {
                other.cell.animate().alpha(0f).setStartDelay(0).setDuration(200).start();
            }
        }
        captionBox.animate().alpha(0f).setStartDelay(0).setDuration(160).start();
        chosen.cell.animate().scaleX(1.25f).scaleY(1.25f).alpha(0.0f)
                .setStartDelay(60).setDuration(240).setInterpolator(smooth).start();

        row.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(chosen.item.intent);
                } catch (ActivityNotFoundException | SecurityException ex) {
                    toast("Kan " + chosen.item.spec.label + " niet openen");
                    launching = false;
                    resetCells();
                }
            }
        }, 230);
    }

    private void openAppInfo(Item item) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + item.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException ex) {
            toast("App-info is niet beschikbaar op deze box");
        }
    }

    // ---------------------------------------------------------------- helpers

    private String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 6) {
            return "Goedenacht";
        } else if (hour < 12) {
            return "Goedemorgen";
        } else if (hour < 18) {
            return "Goedemiddag";
        }
        return "Goedenavond";
    }

    private static int shade(int color, float factor) {
        return Color.rgb(
                Math.round(Color.red(color) * factor),
                Math.round(Color.green(color) * factor),
                Math.round(Color.blue(color) * factor));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------- achtergrond

    /**
     * Donkere achtergrond met twee zachte kleurgloeden in de kleur van de gekozen app.
     * De gloeden drijven heel langzaam rond, zodat het scherm nooit helemaal stilstaat.
     */
    private static class GlowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader glowA;
        private Shader glowB;
        private PorterDuffColorFilter tint;
        private float phase;
        private ValueAnimator drift;

        GlowView(Context context) {
            super(context);
            setAccent(0xFF3A4A8F);
        }

        void setAccent(int accent) {
            tint = new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN);
            invalidate();
        }

        void startDrift() {
            if (drift != null) {
                return;
            }
            drift = ValueAnimator.ofFloat(0f, 1f);
            drift.setDuration(22000);
            drift.setRepeatCount(ValueAnimator.INFINITE);
            drift.setInterpolator(new LinearInterpolator());
            drift.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    phase = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            drift.start();
        }

        void stopDrift() {
            if (drift != null) {
                drift.cancel();
                drift = null;
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // Wit -> doorzichtig; de kleur komt later via het kleurfilter.
            int[] colors = {0xFFFFFFFF, 0x00FFFFFF};
            glowA = new RadialGradient(0, 0, Math.max(1, w * 0.75f),
                    colors, null, Shader.TileMode.CLAMP);
            glowB = new RadialGradient(0, 0, Math.max(1, w * 0.65f),
                    colors, null, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0 || glowA == null) {
                return;
            }
            canvas.drawColor(BASE_COLOR);

            double angle = phase * 2 * Math.PI;
            float sin = (float) Math.sin(angle);
            float cos = (float) Math.cos(angle);

            paint.setColorFilter(tint);

            float ax = w * (0.22f + 0.05f * sin);
            float ay = h * (0.18f + 0.04f * cos);
            paint.setShader(glowA);
            paint.setAlpha(0x70);
            canvas.save();
            canvas.translate(ax, ay);
            canvas.drawRect(-ax, -ay, w - ax, h - ay, paint);
            canvas.restore();

            float bx = w * (0.85f - 0.05f * sin);
            float by = h * (1.0f - 0.04f * cos);
            paint.setShader(glowB);
            paint.setAlpha(0x44);
            canvas.save();
            canvas.translate(bx, by);
            canvas.drawRect(-bx, -by, w - bx, h - by, paint);
            canvas.restore();

            paint.setShader(null);
            paint.setColorFilter(null);
        }
    }
}
