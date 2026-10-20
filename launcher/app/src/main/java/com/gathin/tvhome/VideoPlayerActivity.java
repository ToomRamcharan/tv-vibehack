package com.gathin.tvhome;

import android.app.Activity;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Fullscreen video player with parent-friendly TV remote navigation.
 * Allows instant, effortless switching between Part 1, Part 2, and Part 3.
 */
public class VideoPlayerActivity extends Activity {

    private static final String TAG = "VideoPlayerActivity";

    private VideoView videoView;
    private ProgressBar loadingSpinner;
    private FrameLayout hudContainer;
    private TextView tvVideoTitle;
    private TextView tvVideoSource;
    private TextView tvCenterStatus;
    private TextView tvCurrentTime, tvTotalTime;
    private SeekBar seekBar;

    private int currentPart = 1;
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

        videoView = findViewById(R.id.videoView);
        loadingSpinner = findViewById(R.id.loadingSpinner);
        hudContainer = findViewById(R.id.hudContainer);
        tvVideoTitle = findViewById(R.id.tvVideoTitle);
        tvVideoSource = findViewById(R.id.tvVideoSource);
        tvCenterStatus = findViewById(R.id.tvCenterStatus);
        tvCurrentTime = findViewById(R.id.tvCurrentTime);
        tvTotalTime = findViewById(R.id.tvTotalTime);
        seekBar = findViewById(R.id.seekBar);

        currentPart = getIntent().getIntExtra("PART_NUMBER", 1);
        if (currentPart < 1 || currentPart > 3) currentPart = 1;

        setupVideoListeners();
        playPart(currentPart);

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
            showCenterToast("▶ Playing");
            scheduleHideHud();
            handler.post(progressUpdateRunnable);
        });

        videoView.setOnCompletionListener(mp -> {
            if (currentPart < 3) {
                Toast.makeText(this, "Part " + currentPart + " finished. Playing next part...", Toast.LENGTH_LONG).show();
                playPart(currentPart + 1);
            } else {
                Toast.makeText(this, "Media archive playback completed", Toast.LENGTH_LONG).show();
                finish();
            }
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            loadingSpinner.setVisibility(View.GONE);
            Toast.makeText(this, "Cannot play video format", Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void playPart(int partNumber) {
        currentPart = Math.max(1, Math.min(3, partNumber));

        String title = "Media Archive · Part " + currentPart;
        tvVideoTitle.setText(title);
        tvVideoSource.setText("NLRC PenDrive Viewer · Part " + currentPart);

        File videoFile = PenDriveActivity.findPartFile(currentPart);

        if (videoFile != null && videoFile.exists()) {
            loadingSpinner.setVisibility(View.VISIBLE);
            videoView.stopPlayback();
            videoView.setVideoPath(videoFile.getAbsolutePath());
            showHud();
        } else {
            Toast.makeText(this,
                    "Video Part " + currentPart + " not found on TV storage",
                    Toast.LENGTH_LONG).show();
            showCenterToast("Part " + currentPart + " Not Found");
        }
    }

    private void updateProgress() {
        if (videoView != null && videoView.isPlaying()) {
            int current = videoView.getCurrentPosition();
            seekBar.setProgress(current);
            tvCurrentTime.setText(formatTime(current));
        }
    }

    private String formatTime(int ms) {
        if (ms <= 0) return "00:00";
        int totalSeconds = ms / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        int hours = minutes / 60;
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
                    showCenterToast("⏸ Paused");
                } else {
                    videoView.start();
                    showCenterToast("▶ Playing");
                    scheduleHideHud();
                }
                return true;

            // Fast Forward 10s
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                int posFwd = Math.min(videoView.getDuration(), videoView.getCurrentPosition() + 10000);
                videoView.seekTo(posFwd);
                showCenterToast("⏩ +10 Seconds");
                return true;

            // Rewind 10s
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                int posRwd = Math.max(0, videoView.getCurrentPosition() - 10000);
                videoView.seekTo(posRwd);
                showCenterToast("⏪ -10 Seconds");
                return true;

            // Effortless Switching to Next Part
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (currentPart < 3) {
                    Toast.makeText(this, "Switching to Part " + (currentPart + 1) + " 🎬", Toast.LENGTH_SHORT).show();
                    playPart(currentPart + 1);
                } else {
                    Toast.makeText(this, "Already on final part (Part 3)", Toast.LENGTH_SHORT).show();
                }
                return true;

            // Effortless Switching to Previous Part
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (currentPart > 1) {
                    Toast.makeText(this, "Switching to Part " + (currentPart - 1) + " 🎬", Toast.LENGTH_SHORT).show();
                    playPart(currentPart - 1);
                } else {
                    Toast.makeText(this, "Already on first part (Part 1)", Toast.LENGTH_SHORT).show();
                }
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
