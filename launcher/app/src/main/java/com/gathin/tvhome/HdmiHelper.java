package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Helper to seamlessly switch the TV to HDMI 2 (Jio TV Set Top Box)
 * whenever requested via remote shortcuts (Netflix / Prime Video buttons)
 * or launcher tile clicks.
 */
public final class HdmiHelper {

    private static final String TAG = "HdmiHelper";

    /**
     * Realtek Passthrough Hardware ID for HDMI 2 on Acer V Pro 32 QLED (MOKA R3G).
     */
    public static final String HDMI_2_INPUT_ID =
            "com.realtek.tv.passthrough/.hdmiinput.HDMITvInputService/HW151519744";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static long sLastSwitchTime = 0;

    private HdmiHelper() {}

    /** Passthrough inputs (HDMI first, then AV and the rest, by label), as the Inputs menu and tiles list them. */
    static List<TvInputInfo> passthroughInputs(Context context) {
        List<TvInputInfo> inputs = new ArrayList<>();
        TvInputManager tim = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return inputs;
        for (TvInputInfo info : tim.getTvInputList()) if (info.isPassthroughInput()) inputs.add(info);
        Collections.sort(inputs, (a, b) -> {
            boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
            if (ha != hb) return ha ? -1 : 1;
            return String.valueOf(a.loadLabel(context)).compareToIgnoreCase(String.valueOf(b.loadLabel(context)));
        });
        return inputs;
    }

    /** True only on TVs that have this HDMI 2 input; elsewhere Netflix/Prime must open normally. */
    public static boolean isAvailable(Context context) {
        TvInputManager tim = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        return tim != null && tim.getTvInputInfo(HDMI_2_INPUT_ID) != null;
    }

    /**
     * Creates the explicit passthrough intent to display HDMI 2 directly.
     */
    public static Intent createHdmi2Intent() {
        Uri channelUri = TvContract.buildChannelUriForPassthroughInput(HDMI_2_INPUT_ID);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(channelUri, "vnd.android.cursor.item/channel");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                      | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                      | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }

    /**
     * Switches to HDMI 2 immediately with debouncing and optional Telugu/English toast.
     */
    public static synchronized void switchToHdmi2(Context context, boolean showToast) {
        long now = System.currentTimeMillis();
        // Debounce multiple rapid button presses within 500ms
        if (now - sLastSwitchTime < 500) {
            return;
        }
        sLastSwitchTime = now;

        MAIN_HANDLER.post(() -> {
            try {
                if (showToast) {
                    Toast.makeText(context.getApplicationContext(), R.string.hdmi_switching, Toast.LENGTH_SHORT).show();
                }
                Intent intent = createHdmi2Intent();
                context.startActivity(intent);
                Log.i(TAG, "Successfully triggered HDMI 2 passthrough intent");
            } catch (Exception e) {
                Log.e(TAG, "Failed to switch to HDMI 2", e);
                try {
                    Toast.makeText(context.getApplicationContext(), R.string.hdmi_failed, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
    }
}
