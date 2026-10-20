package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dock editor, reachable with the remote's MENU key (or a long press on the Settings pill).
 * OK on a pinned tile removes it; OK on an app in the lower section appends it to the dock.
 * Every change is written straight to DockStore, so Back can't discard anything.
 */
public class DockEditorActivity extends Activity {

    private int columns;
    private float density;
    private LinearLayout dockRow;
    private LinearLayout appRows;
    private List<String> dock;
    private List<AppAdapter.Entry> scanned;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dock_editor);

        density = getResources().getDisplayMetrics().density;
        columns = Math.max(1, getResources().getInteger(R.integer.columns));

        dockRow = findViewById(R.id.dockRow);
        appRows = findViewById(R.id.appRows);

        // Same band the home screen draws around its dock row, so the mental model carries over.
        GradientDrawable band = new GradientDrawable();
        band.setColor(0xFF141416);
        band.setStroke((int) density, 0x1FFFFFFF);
        band.setCornerRadius(26 * density);
        dockRow.setBackground(band);
        int pad = (int) (14 * density);
        dockRow.setPadding(pad, pad, pad, pad);

        scanned = AppAdapter.scanAll(this);
        rebuild();

        dockRow.post(() -> {
            if (dockRow.getChildCount() > 0)
                dockRow.getChildAt(0).findViewById(R.id.card).requestFocus();
        });
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void rebuild() {
        dock = DockStore.load(this);
        Collections.sort(scanned, (a, b) -> a.label.compareToIgnoreCase(b.label));
        Set<String> pinned = new HashSet<>(dock);

        // ── Pinned slots ─────────────────────────────────────────────────────
        dockRow.removeAllViews();
        for (int i = 0; i < columns; i++) {
            final int slot = i;
            String key = i < dock.size() ? dock.get(i) : null;
            AppAdapter.Entry e = key != null ? findByKey(key) : null;
            View tile;
            if (key == null) {
                tile = makeTile(AppAdapter.Entry.empty(), null);
            } else if (e != null) {
                tile = makeTile(e, v -> removeSlot(slot));
            } else {
                // Pinned key no longer resolves (app uninstalled / input missing):
                // keep the slot visible and clickable so it can be purged by hand.
                tile = makeTile(AppAdapter.Entry.empty(), v -> removeSlot(slot));
                ((TextView) tile.findViewById(R.id.name)).setText(key);
            }
            dockRow.addView(tile);
        }

        // ── Everything not pinned, alphabetical, columns per row ─────────────
        appRows.removeAllViews();
        List<AppAdapter.Entry> rest = new ArrayList<>();
        for (AppAdapter.Entry e : scanned) {
            if (!e.isEmptySlot && !pinned.contains(e.pkg)) rest.add(e);
        }
        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                appRows.addView(row);
            }
            final AppAdapter.Entry e = rest.get(i);
            View tile = makeTile(e, v -> pin(e));
            row.addView(tile);
        }
    }

    private AppAdapter.Entry findByKey(String key) {
        for (AppAdapter.Entry e : scanned) {
            if (e.pkg.equals(key)) return e;
        }
        return null;
    }

    private void pin(AppAdapter.Entry e) {
        if (dock.size() >= columns) {
            Toast.makeText(this, R.string.dock_full, Toast.LENGTH_SHORT).show();
            return;
        }
        dock.add(e.pkg);
        DockStore.save(this, dock);
        int newSlot = dock.size() - 1;
        rebuild();
        focusDockSlot(newSlot);
    }

    private void removeSlot(int slot) {
        dock.remove(slot);
        DockStore.save(this, dock);
        rebuild();
        focusDockSlot(Math.min(slot, dock.size() - 1));
    }

    private void focusDockSlot(int slot) {
        final int target = Math.max(0, slot);
        dockRow.post(() -> {
            if (target >= dockRow.getChildCount()) return;
            dockRow.getChildAt(target).findViewById(R.id.card).requestFocus();
        });
    }

    /** Builds one tile from item_app.xml. The name under it shows on focus. */
    private View makeTile(AppAdapter.Entry e, View.OnClickListener onClick) {
        View v = getLayoutInflater().inflate(R.layout.item_app, null, false);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (190 * density), ViewGroup.LayoutParams.WRAP_CONTENT));

        View card = v.findViewById(R.id.card);
        TextView name = v.findViewById(R.id.name);

        if (e.isEmptySlot) {
            name.setText(R.string.empty_slot);
            ImageView banner = v.findViewById(R.id.banner);
            banner.setImageDrawable(AppAdapter.createEmptySlotBanner(this));
            banner.setVisibility(View.VISIBLE);
            v.findViewById(R.id.icon).setVisibility(View.GONE);
            v.findViewById(R.id.label).setVisibility(View.GONE);
        } else if (e.banner != null) {
            name.setText(e.label);
            ((ImageView) v.findViewById(R.id.banner)).setImageDrawable(e.banner);
            v.findViewById(R.id.icon).setVisibility(View.GONE);
            v.findViewById(R.id.label).setVisibility(View.GONE);
        } else {
            name.setText(e.label);
            ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(e.icon);
            v.findViewById(R.id.banner).setVisibility(View.GONE);
        }

        // Click belongs on the card: that is the view that takes focus, and DPAD_CENTER
        // raises performClick on the focused view only. A listener on the outer FrameLayout
        // never fired from a key press.
        if (onClick != null) card.setOnClickListener(onClick);
        card.setTag("tile:" + e.pkg);

        card.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                return onClick == null;
            }
            return false;
        });

        card.setOnFocusChangeListener((view, focused) -> {
            float s = focused ? AppAdapter.FOCUS_SCALE : 1f;
            view.animate().scaleX(s).scaleY(s).setDuration(focused ? 200 : 160).start();
            name.animate().alpha(focused ? 1f : 0f)
                    .translationY(focused ? 4 * density : -6 * density)
                    .setDuration(160).start();
        });
        return v;
    }
}
