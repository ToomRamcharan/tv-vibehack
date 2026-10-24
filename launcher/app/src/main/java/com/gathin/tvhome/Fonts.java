package com.gathin.tvhome;

import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Outfit is a variable font whose default weight is 100 (Thin). On Android 9 the XML
 * {@code android:fontVariationSettings} is dropped when the typeface comes from {@code fontFamily},
 * so every label rendered Thin. Re-applying the weight in code after inflation works.
 */
final class Fonts {

    /** Weight settings → Outfit typeface with that weight baked in (Android 10+). */
    private static final Map<String, Typeface> OUTFIT = new HashMap<>();

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

    /**
     * Gives an Outfit TextView a weight such as {@code "'wght' 500"}, cheaply. setFontVariationSettings
     * rebuilds the variable font on every call (~13 ms on the TCL TV), which made every new app tile
     * slow and froze the first scroll to a new row; here the weighted font is built once and shared.
     */
    static void setOutfitWeight(TextView t, String settings) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Typeface tf = OUTFIT.get(settings);
            if (tf == null) {
                try {
                    Font font = new Font.Builder(t.getResources(), R.font.outfit)
                            .setFontVariationSettings(settings).build();
                    // System fallback keeps app names in scripts Outfit lacks (Hindi, Chinese…) readable.
                    tf = new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build())
                            .setSystemFallback("sans-serif").build();
                    OUTFIT.put(settings, tf);
                } catch (IOException ignored) {}
            }
            if (tf != null) {
                t.setTypeface(tf);
                return;
            }
        }
        t.setFontVariationSettings(null);
        t.setFontVariationSettings(settings);
    }
}
