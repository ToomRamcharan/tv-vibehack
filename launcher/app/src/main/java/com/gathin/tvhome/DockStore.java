package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persists the dock as one ordered CSV of entry keys ("hdmi_2,nlrc_pendrive,youtube,com.hotstar")
 * in SharedPreferences. Until the dock editor has run at least once there is no saved value and
 * res/values/config.xml dock_apps stays the source of truth, so a fresh install keeps its defaults
 * and a user who only ever looks at the editor can Back out without changing anything.
 */
final class DockStore {

    private static final String PREFS = "dock";
    private static final String KEY   = "apps";

    private DockStore() {}

    /** Keys currently pinned to the dock row, in slot order. */
    static List<String> load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String csv = sp.getString(KEY, null);
        List<String> out = new ArrayList<>();
        if (csv != null) {
            for (String s : csv.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
            if (!out.isEmpty()) return out;
        }
        Collections.addAll(out, ctx.getResources().getStringArray(R.array.dock_apps));
        return out;
    }

    static void save(Context ctx, List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (k == null || k.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(k.trim());
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, sb.toString()).apply();
    }

    static boolean hasUserDock(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY);
    }
}
