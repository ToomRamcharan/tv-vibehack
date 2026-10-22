package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

/** Short system-event callbacks, including while another application is foregrounded. */
public class MstarSpeakerReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if ("android.media.VOLUME_CHANGED_ACTION".equals(action)
                || "android.media.STREAM_MUTE_CHANGED_ACTION".equals(action)
                || "android.media.STREAM_DEVICES_CHANGED_ACTION".equals(action)) {
            if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    != AudioManager.STREAM_MUSIC) return;
        } else if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        MstarSpeakerSync.sync(context);
    }
}
