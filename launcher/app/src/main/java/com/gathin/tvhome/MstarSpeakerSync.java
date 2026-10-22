package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

/** Opt-in workaround for a speaker amplifier that does not follow Android media volume. */
final class MstarSpeakerSync {
    private static final String TAG = "MstarSpeakerSync";
    private static final int BUILT_IN_SPEAKER = 0x2;

    private MstarSpeakerSync() {}

    static int speakerLevel(int media, int maximum) {
        if (maximum <= 0 || maximum > 100 || media < 0 || media > maximum) return -1;
        return Math.round(media * 100f / maximum);
    }

    static boolean enabled(Context context) {
        if (!context.getResources().getBoolean(R.bool.mstar_speaker_sync_enabled)) return false;
        for (String fingerprint : context.getResources().getStringArray(R.array.mstar_speaker_sync_fingerprints)) {
            if (!fingerprint.isEmpty() && fingerprint.equals(Build.FINGERPRINT)) return true;
        }
        return false;
    }

    private static boolean builtInSpeaker() throws ReflectiveOperationException {
        int outputs = (Integer) Class.forName("android.media.AudioSystem")
                .getMethod("getDevicesForStream", int.class).invoke(null, AudioManager.STREAM_MUSIC);
        String selected = (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class).invoke(null, "persist.snd.output.dev", "");
        return outputs == BUILT_IN_SPEAKER && "0".equals(selected);
    }

    static synchronized void sync(Context context) {
        if (!enabled(context)) return;
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null || audio.isStreamMute(AudioManager.STREAM_MUSIC) || !builtInSpeaker()) return;
            int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (maximum != context.getResources().getInteger(R.integer.mstar_speaker_sync_media_max)) return;
            int media = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int target = speakerLevel(media, maximum);
            if (target < 0) return;

            Class<?> tvType = Class.forName("com.mstar.android.tvapi.common.TvManager");
            Object tv = tvType.getMethod("getInstance").invoke(null);
            Object nativeAudio = tvType.getMethod("getAudioManager").invoke(tv);
            Class<?> sourceType = Class.forName("com.mstar.android.tvapi.common.vo.EnumAudioVolumeSourceType");
            Object speaker = sourceType.getField("E_VOL_SOURCE_SPEAKER_OUT").get(null);
            // TvAudioManager.getSpeakerVolume() reads a database that may already look correct.
            int actual = ((Number) nativeAudio.getClass().getMethod("getAudioVolume", sourceType)
                    .invoke(nativeAudio, speaker)).intValue();
            if (actual < 0 || actual > 100 || actual == target) return;
            // A user can change volume/route during the vendor calls. Let the next event retry.
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != media
                    || audio.isStreamMute(AudioManager.STREAM_MUSIC) || !builtInSpeaker()) return;
            nativeAudio.getClass().getMethod("setAudioVolume", sourceType, byte.class)
                    .invoke(nativeAudio, speaker, (byte) target);
            Log.i(TAG, "media=" + media + "/" + maximum + ", amplifier=" + actual + "->" + target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.w(TAG, "Vendor speaker synchronization unavailable", error);
        }
    }
}
