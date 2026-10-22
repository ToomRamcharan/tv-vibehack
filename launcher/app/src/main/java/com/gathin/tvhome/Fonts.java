package com.gathin.tvhome;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Outfit is a variable font whose default weight is 100 (Thin). On Android 9 the XML
 * {@code android:fontVariationSettings} is dropped when the typeface comes from {@code fontFamily},
 * so every label rendered Thin. Re-applying the weight in code after inflation works.
 */
final class Fonts {

    private Fonts() {}

    /** Re-applies each TextView's weight under {@code root}; unset ones get Regular (bold → 700). */
    static void applyWeights(View root) {
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) applyWeights(g.getChildAt(i));
        } else if (root instanceof TextView) {
            TextView t = (TextView) root;
            String settings = t.getFontVariationSettings();
            if (settings == null || settings.isEmpty()) {
                boolean bold = t.getTypeface() != null && t.getTypeface().isBold();
                settings = bold ? "'wght' 700" : "'wght' 400";
            }
            t.setFontVariationSettings(null);
            t.setFontVariationSettings(settings);
        }
    }
}
