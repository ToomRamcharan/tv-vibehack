package com.gathin.tvhome;

import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/** Some vendor TVs expose a manager object without its backing TIF service. */
final class TvInputCompat {
    private TvInputCompat() {}

    static List<TvInputInfo> list(TvInputManager manager) {
        if (manager == null) return Collections.emptyList();
        try {
            List<TvInputInfo> inputs = manager.getTvInputList();
            return inputs == null ? Collections.emptyList() : inputs;
        } catch (NullPointerException | IllegalStateException | SecurityException error) {
            Log.w("TvInputCompat", "TV input service unavailable", error);
            return Collections.emptyList();
        }
    }
}
