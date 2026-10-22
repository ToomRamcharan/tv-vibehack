package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.util.Log;

/** Optional vendor chooser; the standard TV Input Framework remains the fallback. */
final class NativeInputPicker {
    static final String TILE_ID = "native_inputs";
    private static final String TAG = "NativeInputPicker";

    private NativeInputPicker() {}

    private static Intent resolve(Context context) {
        String action = context.getString(R.string.native_input_picker_action).trim();
        String receiver = context.getString(R.string.native_input_picker_receiver).trim();
        if (action.isEmpty() || receiver.isEmpty()) return null;
        ComponentName component = ComponentName.unflattenFromString(receiver);
        if (component == null) return null;
        try {
            ActivityInfo info = context.getPackageManager().getReceiverInfo(component, 0);
            if (!info.enabled || !info.applicationInfo.enabled) return null;
            boolean sameUid = info.applicationInfo.uid == context.getApplicationInfo().uid;
            if (!sameUid && (!info.exported || (info.permission != null
                    && context.checkSelfPermission(info.permission) != PackageManager.PERMISSION_GRANTED)))
                return null;
            return new Intent(action).setComponent(component);
        } catch (PackageManager.NameNotFoundException | SecurityException error) {
            return null;
        }
    }

    static boolean available(Context context) {
        return resolve(context) != null;
    }

    static boolean show(Context context) {
        Intent picker = resolve(context);
        if (picker == null) return false;
        try {
            context.sendBroadcast(picker);
            return true;
        } catch (SecurityException error) {
            Log.w(TAG, "Native input picker denied; use the standard picker", error);
            return false;
        }
    }
}
