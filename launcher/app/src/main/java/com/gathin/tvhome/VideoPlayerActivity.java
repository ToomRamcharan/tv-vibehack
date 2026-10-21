package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fullscreen video player with parent-friendly TV remote navigation.
 * Plays the list handed over by {@link PenDriveActivity}; Up/Down or CH+/CH- step between videos.
 */
public class VideoPlayerActivity extends Activity {

    private VideoView videoView;
    private ProgressBar loadingSpinner;
    private FrameLayout hudContainer;
    private TextView tvVideoTitle;
    private TextView tvVideoSource;
    private TextView tvCenterStatus;
    private TextView tvCurrentTime, tvTotalTime;
    private SeekBar seekBar;

    private List<String> paths = new ArrayList<>();
    private int index = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isHudVisible = true;

    private final Runnable hideHudRunnable = () -> hideHud();
    private final Runnable progressUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);
        Fonts.applyWeights(findViewById(android.R.id.content));

        videoView = findViewById(R.id.videoView);
        loadingSpinner = findViewById(R.id.loadingSpinner);
        hudContainer = findViewById(R.id.hudContainer);
        tvVideoTitle = findViewById(R.id.tvVideoTitle);
        tvVideoSource = findViewById(R.id.tvVideoSource);
        tvCenterStatus = findViewById(R.id.tvCenterStatus);
        tvCurrentTime = findViewById(R.id.tvCurrentTime);
        tvTotalTime = findViewById(R.id.tvTotalTime);
        seekBar = findViewById(R.id.seekBar);

        List<String> given = getIntent().getStringArrayListExtra(PenDriveActivity.EXTRA_PATHS);
        if (given != null) paths = given;
        if (paths.isEmpty()) { finish(); return; }
        index = Math.max(0, Math.min(paths.size() - 1, getIntent().getIntExtra(PenDriveActivity.EXTRA_INDEX, 0)));

        setupVideoListeners();
        playAt(index);

        // Tap root to toggle HUD
        findViewById(R.id.playerRoot).setOnClickListener(v -> toggleHud());
    }

    private void setupVideoListeners() {
        videoView.setOnPreparedListener(mp -> {
            loadingSpinner.setVisibility(View.GONE);
            int duration = videoView.getDuration();
            seekBar.setMax(duration > 0 ? duration : 0);
            tvTotalTime.setText(formatTime(duration));
            videoView.start();
            showCenterToast(getString(R.string.vp_playing));
            scheduleHideHud();
            startProgressUpdates();
        });

        videoView.setOnCompletionListener(mp -> {
            if (index < paths.size() - 1) {
                playAt(index + 1);
            } else {
                Toast.makeText(this, R.string.vp_finished, Toast.LENGTH_LONG).show();
                finish();
            }
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            loadingSpinner.setVisibility(View.GONE);
            Toast.makeText(this, R.string.vp_error, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void playAt(int i) {
        index = Math.max(0, Math.min(paths.size() - 1, i));
        File videoFile = new File(paths.get(index));
        tvVideoTitle.setText(PenDriveActivity.displayName(videoFile));
        tvVideoSource.setText(getString(R.string.vp_position, index + 1, paths.size()));
        seekBar.setProgress(0);
        tvCurrentTime.setText(formatTime(0));

        if (videoFile.exists()) {
            loadingSpinner.setVisibility(View.VISIBLE);
            videoView.stopPlayback();
            videoView.setVideoPath(videoFile.getAbsolutePath());
            showHud();
        } else {
            showCenterToast(getString(R.string.vp_not_found));
        }
    }

    private void updateProgress() {
        if (videoView != null && videoView.isPlaying()) {
            int current = videoView.getCurrentPosition();
            seekBar.setProgress(current);
            tvCurrentTime.setText(formatTime(current));
        }
    }

    static String formatTime(long ms) {
        if (ms <= 0) return "00:00";
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        long hours = minutes / 60;
        minutes = minutes % 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        showHud();

        switch (keyCode) {
            // Play / Pause
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (videoView.isPlaying()) {
                    videoView.pause();
                    showCenterToast(getString(R.string.vp_paused));
                } else {
                    videoView.start();
                    showCenterToast(getString(R.string.vp_playing));
                    scheduleHideHud();
                }
                return true;

            // Fast Forward 10s
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                int posFwd = Math.min(videoView.getDuration(), videoView.getCurrentPosition() + 10000);
                videoView.seekTo(posFwd);
                showCenterToast(getString(R.string.vp_forward));
                return true;

            // Rewind 10s
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                int posRwd = Math.max(0, videoView.getCurrentPosition() - 10000);
                videoView.seekTo(posRwd);
                showCenterToast(getString(R.string.vp_rewind));
                return true;

            // Next / previous video
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (index < paths.size() - 1) playAt(index + 1);
                else showCenterToast(getString(R.string.vp_last));
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (index > 0) playAt(index - 1);
                else showCenterToast(getString(R.string.vp_first));
                return true;

            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    private void showCenterToast(String msg) {
        tvCenterStatus.setText(msg);
        tvCenterStatus.animate().alpha(1f).setDuration(150).withEndAction(() -> {
            handler.postDelayed(() -> tvCenterStatus.animate().alpha(0f).setDuration(300).start(), 1200);
        }).start();
    }

    private void showHud() {
        handler.removeCallbacks(hideHudRunnable);
        hudContainer.animate().alpha(1f).setDuration(180).start();
        isHudVisible = true;
        scheduleHideHud();
    }

    private void hideHud() {
        if (videoView != null && videoView.isPlaying()) {
            hudContainer.animate().alpha(0f).setDuration(400).start();
            isHudVisible = false;
        }
    }

    private void toggleHud() {
        if (isHudVisible) hideHud(); else showHud();
    }

    private void scheduleHideHud() {
        handler.removeCallbacks(hideHudRunnable);
        handler.postDelayed(hideHudRunnable, 3500);
    }

    /** One self-reposting updater at a time; posting again would stack a second loop. */
    private void startProgressUpdates() {
        handler.removeCallbacks(progressUpdateRunnable);
        handler.post(progressUpdateRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startProgressUpdates();   // onPause stopped it; the loop only updates while playing
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
        }
        handler.removeCallbacks(progressUpdateRunnable);
        handler.removeCallbacks(hideHudRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoView != null) {
            videoView.stopPlayback();
        }
        handler.removeCallbacksAndMessages(null);
    }
}
