package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;

/** Scaling/profile checks by default; hardware checks require an explicit low-volume opt-in. */
public class MstarSpeakerChecks extends Instrumentation {
    private boolean hardware;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        hardware = arguments != null && "true".equals(arguments.getString("hardware"));
        start();
    }

    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private Context profile(String fingerprint, int maximum) {
        Context target = getTargetContext();
        Resources original = target.getResources();
        Resources resources = new Resources(original.getAssets(), original.getDisplayMetrics(), original.getConfiguration()) {
            @Override public boolean getBoolean(int id) {
                return id == R.bool.mstar_speaker_sync_enabled || super.getBoolean(id);
            }
            @Override public String[] getStringArray(int id) {
                return id == R.array.mstar_speaker_sync_fingerprints ? new String[]{fingerprint} : super.getStringArray(id);
            }
            @Override public int getInteger(int id) {
                return id == R.integer.mstar_speaker_sync_media_max ? maximum : super.getInteger(id);
            }
        };
        return new ContextWrapper(target) {
            @Override public Resources getResources() { return resources; }
        };
    }

    private void checkHardware() throws Exception {
        AudioManager audio = (AudioManager) getTargetContext().getSystemService(Context.AUDIO_SERVICE);
        int media = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (!"MiTV4X".equals(Build.MODEL) || Build.VERSION.SDK_INT != 23
                || audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) != 50
                || media < 3 || media > 5 || audio.isStreamMute(AudioManager.STREAM_MUSIC))
            throw new AssertionError("Hardware check requires MiTV4X/API23, unmuted media at 3..5/50; got "
                    + Build.MODEL + "/API" + Build.VERSION.SDK_INT + ", media=" + media + "/"
                    + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) + ", muted="
                    + audio.isStreamMute(AudioManager.STREAM_MUSIC));
        Class<?> tvType = Class.forName("com.mstar.android.tvapi.common.TvManager");
        Object tv = tvType.getMethod("getInstance").invoke(null);
        Object nativeAudio = tvType.getMethod("getAudioManager").invoke(tv);
        Class<?> sourceType = Class.forName("com.mstar.android.tvapi.common.vo.EnumAudioVolumeSourceType");
        Object speaker = sourceType.getField("E_VOL_SOURCE_SPEAKER_OUT").get(null);
        java.lang.reflect.Method read = nativeAudio.getClass().getMethod("getAudioVolume", sourceType);
        java.lang.reflect.Method write = nativeAudio.getClass().getMethod("setAudioVolume", sourceType, byte.class);
        int saved = ((Number) read.invoke(nativeAudio, speaker)).intValue();
        if (saved < 0 || saved > 10) throw new AssertionError("Refusing to restore a hardware value above 10");
        int lowered = media * 2 - 2;
        boolean mutedByTest = false;
        try {
            write.invoke(nativeAudio, speaker, (byte) lowered);
            MstarSpeakerSync.sync(profile("not-this-firmware", 50));
            equal(lowered, ((Number) read.invoke(nativeAudio, speaker)).intValue());
            MstarSpeakerSync.sync(profile(Build.FINGERPRINT, 49));
            equal(lowered, ((Number) read.invoke(nativeAudio, speaker)).intValue());
            Context enabled = profile(Build.FINGERPRINT, 50);
            MstarSpeakerReceiver receiver = new MstarSpeakerReceiver();
            receiver.onReceive(enabled, new Intent("android.media.VOLUME_CHANGED_ACTION")
                    .putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_SYSTEM));
            equal(lowered, ((Number) read.invoke(nativeAudio, speaker)).intValue());
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            mutedByTest = true;
            MstarSpeakerSync.sync(enabled);
            equal(lowered, ((Number) read.invoke(nativeAudio, speaker)).intValue());
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            mutedByTest = false;
            receiver.onReceive(enabled, new Intent("android.media.VOLUME_CHANGED_ACTION")
                    .putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC));
            equal(media * 2, ((Number) read.invoke(nativeAudio, speaker)).intValue());
        } finally {
            if (mutedByTest) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            write.invoke(nativeAudio, speaker, (byte) saved);
        }
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            equal(0, MstarSpeakerSync.speakerLevel(0, 50));
            equal(6, MstarSpeakerSync.speakerLevel(3, 50));
            equal(50, MstarSpeakerSync.speakerLevel(25, 50));
            equal(100, MstarSpeakerSync.speakerLevel(50, 50)); // Arithmetic only; no hardware write.
            equal(-1, MstarSpeakerSync.speakerLevel(-1, 50));
            equal(-1, MstarSpeakerSync.speakerLevel(51, 50));
            equal(-1, MstarSpeakerSync.speakerLevel(1, 0));
            equal(-1, MstarSpeakerSync.speakerLevel(1, 101));
            if (MstarSpeakerSync.enabled(getTargetContext()) || MstarSpeakerSync.enabled(profile("not-this-firmware", 50)))
                throw new AssertionError("Default or unrelated firmware enabled");
            if (hardware) checkHardware();
            result.putString("stream", "PASS: scaling/profile guards" + (hardware ? ", native low-volume sync/mute/events" : "") + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
