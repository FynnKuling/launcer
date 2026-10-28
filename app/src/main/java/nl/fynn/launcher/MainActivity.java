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
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
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
 * Rustige, Apple TV-achtige launcher met precies vier apps.
 *
 * Bediening:
 *  - links / rechts + OK : app openen
 *  - OK lang indrukken   : app-info (of Menu / Info-knop)
 *  - omhoog              : tandwiel voor de Android-instellingen
 *  - Terug               : doet niets
 *
 * Andere apps? Pas de lijst APPS hieronder aan.
 */
public class MainActivity extends Activity {

    /** Een vaste plek op het startscherm. */
    private static final class Spec {
        final String pkg;
        final String label;
        final int accent;

        Spec(String pkg, String label, int accent) {
            this.pkg = pkg;
            this.label = label;
            this.accent = accent;
        }
    }

    private static final Spec[] APPS = {
            new Spec("com.google.android.youtube.tv", "YouTube", 0xFFE62117),
            new Spec("nl.rtl.videoland.v2", "Videoland", 0xFFD6154B),
            new Spec("nl.uitzendinggemist", "NPO Start", 0xFFFF6A13),
            new Spec("com.spotify.tv.android", "Spotify", 0xFF1DB954),
    };

    /** Een app die echt geinstalleerd is. */
    private static final class Item {
        final Spec spec;
        final Intent intent;
        final Drawable banner; // mag null zijn
        final Drawable icon;

        Item(Spec spec, Intent intent, Drawable banner, Drawable icon) {
            this.spec = spec;
            this.intent = intent;
            this.banner = banner;
            this.icon = icon;
        }
    }

    private static final int BASE_COLOR = 0xFF090A10;

    private GlowView glow;
    private LinearLayout row;
    private TextView nameView;
    private TextView greetingView;
    private final List<Item> items = new ArrayList<Item>();
    private final List<View> tiles = new ArrayList<View>();
    private int currentAccent = 0xFF3A4A8F;
    private ValueAnimator accentAnimator;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        rebuildTiles(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        greetingView.setText(greeting());
        // Alleen opnieuw opbouwen als er iets veranderd is (app geinstalleerd of verwijderd).
        if (!sameAppsAsShown(loadItems())) {
            rebuildTiles(false);
        } else if (getCurrentFocus() == null && !tiles.isEmpty()) {
            tiles.get(0).requestFocus();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home-knop terwijl de launcher al open is: terug naar de eerste tegel.
        if (!tiles.isEmpty()) {
            tiles.get(0).requestFocus();
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
            if (f != null && f.getTag() instanceof Item) {
                openAppInfo((Item) f.getTag());
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
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(64), dp(40), dp(64), 0);

        greetingView = new TextView(this);
        greetingView.setTextColor(Color.argb(230, 255, 255, 255));
        greetingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        greetingView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        greetingView.setText(greeting());
        top.addView(greetingView, new LinearLayout.LayoutParams(
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

        top.addView(clockBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        top.addView(buildSettingsButton());

        root.addView(top, new FrameLayout.LayoutParams(
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

        nameView = new TextView(this);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        nameView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameView.setGravity(Gravity.CENTER);
        nameView.setShadowLayer(dp(6), 0, dp(2), Color.argb(140, 0, 0, 0));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(40);
        center.addView(nameView, nameLp);

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
                v.setAlpha(hasFocus ? 1f : 0.7f);
                v.animate().scaleX(hasFocus ? 1.15f : 1f).scaleY(hasFocus ? 1.15f : 1f)
                        .setDuration(160).start();
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
            setAccent(0xFF3A4A8F);
            return;
        }

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int tileW = Math.round(screenW * 0.20f);
        int tileH = Math.round(tileW * 0.62f);
        int gap = Math.round(screenW * 0.03f);

        for (int i = 0; i < items.size(); i++) {
            View tile = buildTile(items.get(i), tileW, tileH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            lp.leftMargin = i == 0 ? 0 : gap;
            row.addView(tile, lp);
            tiles.add(tile);

            if (animateIn) {
                tile.setAlpha(0f);
                tile.setTranslationY(dp(28));
                tile.animate().alpha(1f).translationY(0f)
                        .setStartDelay(120L + i * 80L).setDuration(380)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        }

        tiles.get(0).requestFocus();
        showFocused(items.get(0), false);
    }

    private View buildTile(final Item item, final int w, final int h) {
        final float radius = h * 0.13f;

        FrameLayout tile = new FrameLayout(this);
        tile.setTag(item);
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

        // Kaartkleur: een donkere variant van de merkkleur.
        GradientDrawable card = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{shade(item.spec.accent, 0.55f), shade(item.spec.accent, 0.28f)});
        tile.setBackground(card);

        if (item.banner != null) {
            ImageView banner = new ImageView(this);
            banner.setImageDrawable(item.banner);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(banner, new FrameLayout.LayoutParams(
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

            tile.addView(inner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // Glans over de bovenste helft, voor een glazen look.
        View gloss = new View(this);
        gloss.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255)}));
        tile.addView(gloss, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(h * 0.5f), Gravity.TOP));

        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? 1.14f : 1f;
                v.animate().scaleX(s).scaleY(s)
                        .translationZ(hasFocus ? dp(22) : 0f)
                        .setStartDelay(0)
                        .setDuration(200)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                if (hasFocus) {
                    showFocused(item, true);
                }
            }
        });
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(item);
            }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                openAppInfo(item);
                return true;
            }
        });
        return tile;
    }

    private void showFocused(Item item, boolean animate) {
        nameView.setText(item.spec.label);
        if (animate) {
            nameView.setAlpha(0f);
            nameView.animate().alpha(1f).setDuration(220).start();
        } else {
            nameView.setAlpha(1f);
        }
        setAccent(item.spec.accent);
    }

    /** Laat de gloed op de achtergrond soepel naar de kleur van de gekozen app gaan. */
    private void setAccent(int target) {
        if (accentAnimator != null) {
            accentAnimator.cancel();
        }
        accentAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), currentAccent, target);
        accentAnimator.setDuration(500);
        accentAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                currentAccent = (Integer) a.getAnimatedValue();
                glow.setAccent(currentAccent);
            }
        });
        accentAnimator.start();
    }

    // ------------------------------------------------------------------ data

    private List<Item> loadItems() {
        PackageManager pm = getPackageManager();
        List<Item> found = new ArrayList<Item>();
        for (Spec spec : APPS) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(spec.pkg);
            if (launch == null) {
                launch = pm.getLaunchIntentForPackage(spec.pkg);
            }
            if (launch == null) {
                continue; // niet geinstalleerd
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

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
                    icon = pm.getApplicationIcon(spec.pkg);
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // laten we zoals het is
            }
            if (icon == null && banner == null) {
                continue;
            }
            found.add(new Item(spec, launch, banner, icon));
        }
        return found;
    }

    private boolean sameAppsAsShown(List<Item> fresh) {
        if (fresh.size() != items.size()) {
            return false;
        }
        for (int i = 0; i < fresh.size(); i++) {
            if (!fresh.get(i).spec.pkg.equals(items.get(i).spec.pkg)) {
                return false;
            }
        }
        return true;
    }

    // --------------------------------------------------------------- acties

    private void launch(Item item) {
        try {
            startActivity(item.intent);
        } catch (ActivityNotFoundException | SecurityException ex) {
            toast("Kan " + item.spec.label + " niet openen");
        }
    }

    private void openAppInfo(Item item) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + item.spec.pkg));
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

    /** Donkere achtergrond met twee zachte kleurgloeden in de kleur van de gekozen app. */
    private static class GlowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int accent = 0xFF3A4A8F;

        GlowView(Context context) {
            super(context);
        }

        void setAccent(int accent) {
            this.accent = accent;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) {
                return;
            }
            canvas.drawColor(BASE_COLOR);

            int clear = accent & 0x00FFFFFF;
            int strong = (accent & 0x00FFFFFF) | 0x66000000;
            int soft = (accent & 0x00FFFFFF) | 0x3A000000;

            paint.setShader(new RadialGradient(w * 0.22f, h * 0.18f, w * 0.75f,
                    new int[]{strong, clear}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);

            paint.setShader(new RadialGradient(w * 0.85f, h * 1.0f, w * 0.65f,
                    new int[]{soft, clear}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
    }
}
