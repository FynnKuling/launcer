package nl.fynn.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimale Android TV launcher: een klok en een raster met je apps.
 * Geen aanbevelingen, geen widgets, geen advertenties.
 *
 * Bediening:
 *  - pijltjes + OK        : app openen
 *  - OK lang indrukken    : app-info (daar kun je ook verwijderen)
 *  - Menu / Info-knop     : app-info van de geselecteerde app
 *  - Terug                : doet niets (je blijft op het startscherm)
 */
public class MainActivity extends Activity {

    private static final int COLUMNS = 6;

    /** Pakketten die je NIET in het raster wilt zien. Voeg hier eigen pakketnamen toe. */
    private static final Set<String> HIDDEN = new HashSet<String>();
    static {
        HIDDEN.add("com.android.tv.settings");   // we tonen een eigen "Instellingen"-tegel
        HIDDEN.add("com.android.settings");
        // HIDDEN.add("com.qstar.apps.boxtools");
    }

    private static class Entry {
        String pkg;       // null bij de Instellingen-tegel
        String label;
        Drawable icon;
        Intent intent;
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    private GridView grid;
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Opnieuw inlezen: pakt nieuw geinstalleerde en verwijderde apps mee.
        loadApps();
        adapter.notifyDataSetChanged();
        grid.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home-knop terwijl de launcher al open is: terug naar de eerste app.
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && grid != null) {
            grid.setSelection(0);
        }
    }

    @Override
    public void onBackPressed() {
        // Een launcher sluit je niet af.
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            int pos = grid.getSelectedItemPosition();
            if (pos >= 0 && pos < entries.size()) {
                openAppInfo(entries.get(pos));
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(56), dp(32), dp(56), dp(24));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#0B1220"), Color.parseColor("#1B2540")});
        root.setBackground(bg);

        // Klok + datum
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextColor(Color.parseColor("#B8C2DB"));
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);

        root.addView(time, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.bottomMargin = dp(24);
        root.addView(date, dateLp);

        // App-raster
        grid = new GridView(this);
        grid.setNumColumns(COLUMNS);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(focusSelector());
        grid.setDrawSelectorOnTop(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));

        adapter = new AppAdapter();
        grid.setAdapter(adapter);

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launch(entries.get(position));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                openAppInfo(entries.get(position));
                return true;
            }
        });

        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private Drawable focusSelector() {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.parseColor("#33FFFFFF"));
        focused.setStroke(dp(3), Color.WHITE);
        focused.setCornerRadius(dp(14));

        StateListDrawable sel = new StateListDrawable();
        sel.addState(new int[]{android.R.attr.state_focused}, focused);
        sel.addState(new int[]{android.R.attr.state_pressed}, focused);
        sel.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return sel;
    }

    // --------------------------------------------------------------- Data

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<Entry> found = new ArrayList<Entry>();
        Set<String> seen = new HashSet<String>();

        // Eerst de TV-versie van een app (leanback), daarna de gewone launcher-ingang.
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            List<ResolveInfo> infos = pm.queryIntentActivities(query, 0);
            for (ResolveInfo ri : infos) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || HIDDEN.contains(pkg) || !seen.add(pkg)) {
                    continue;
                }
                Entry e = new Entry();
                e.pkg = pkg;
                CharSequence label = ri.loadLabel(pm);
                e.label = label == null ? pkg : label.toString();
                e.icon = ri.loadIcon(pm);
                e.intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(category)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                found.add(e);
            }
        }

        final Collator collator = Collator.getInstance();
        Collections.sort(found, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return collator.compare(a.label, b.label);
            }
        });

        // Vaste Instellingen-tegel achteraan, zodat je er altijd bij kunt.
        Intent settings = new Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Entry s = new Entry();
        s.pkg = null;
        s.label = "Instellingen";
        s.intent = settings;
        Drawable icon = null;
        ResolveInfo ri = pm.resolveActivity(settings, 0);
        if (ri != null) {
            icon = ri.loadIcon(pm);
        }
        if (icon == null) {
            icon = getResources().getDrawable(android.R.drawable.ic_menu_preferences);
        }
        s.icon = icon;
        found.add(s);

        entries.clear();
        entries.addAll(found);
    }

    // ------------------------------------------------------------- Acties

    private void launch(Entry e) {
        try {
            startActivity(e.intent);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, "Kan " + e.label + " niet openen", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppInfo(Entry e) {
        if (e.pkg == null) {
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, "App-info is niet beschikbaar op deze box",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ Adapter

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout cell;
            ImageView icon;
            TextView label;

            if (convertView == null) {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(8), dp(12), dp(8), dp(12));
                cell.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(132)));

                icon = new ImageView(MainActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));

                label = new TextView(MainActivity.this);
                label.setTextColor(Color.WHITE);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                cell.addView(label, lp);
            } else {
                cell = (LinearLayout) convertView;
                icon = (ImageView) cell.getChildAt(0);
                label = (TextView) cell.getChildAt(1);
            }

            Entry e = entries.get(position);
            icon.setImageDrawable(e.icon);
            label.setText(e.label);
            return cell;
        }
    }
}
