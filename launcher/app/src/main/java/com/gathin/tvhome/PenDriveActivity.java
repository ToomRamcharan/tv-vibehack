package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PenDrive / local media viewer: lists every video found on TV storage and plugged-in USB drives,
 * with real thumbnails and durations. OK plays it; the player can step to the previous/next video.
 */
public class PenDriveActivity extends Activity {

    static final String EXTRA_PATHS = "VIDEO_PATHS";
    static final String EXTRA_INDEX = "VIDEO_INDEX";

    private static final int REQ_STORAGE = 1;
    private static final String[] VIDEO_EXTENSIONS = {".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".3gp", ".ts"};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<File> videos = new ArrayList<>();
    /** Thumbnails capped by memory (~0.75 MB each) for 1 GB TVs; evicted ones are simply read again. */
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final Map<String, String> durations = new HashMap<>();
    private final Set<String> noThumb = new HashSet<>();
    private final Set<String> loading = new HashSet<>();

    private RecyclerView list;
    private TextView status;
    private TextView empty;
    private VideoAdapter adapter;
    private boolean permissionAsked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pendrive_viewer);
        Fonts.applyWeights(findViewById(android.R.id.content));
        list = findViewById(R.id.videoList);
        status = findViewById(R.id.tvUsbStatus);
        empty = findViewById(R.id.emptyText);
        list.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        adapter = new VideoAdapter();
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Android 9 needs the storage permission granted at runtime; without it no file is visible.
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            scan();
        } else {
            showEmpty(R.string.pd_permission);
            // Ask once per visit: onResume runs again when the dialog closes, which would re-ask after "Deny".
            // After "Allow" that same onResume does the scan.
            if (!permissionAsked) {
                permissionAsked = true;
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            }
        }
    }

    private void scan() {
        worker.execute(() -> {
            List<File> found = findVideos();
            main.post(() -> show(found));
        });
    }

    private void show(List<File> found) {
        boolean wasEmpty = videos.isEmpty();
        videos.clear();
        videos.addAll(found);
        adapter.notifyDataSetChanged();
        if (found.isEmpty()) {
            showEmpty(R.string.pd_empty);
            return;
        }
        empty.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        status.setText(getString(R.string.pd_count, found.size()));
        status.setTextColor(0xFF10B981);
        if (wasEmpty || getCurrentFocus() == null) {
            list.post(() -> {
                RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(0);
                if (vh != null) vh.itemView.requestFocus();
            });
        }
    }

    private void showEmpty(int messageRes) {
        list.setVisibility(View.GONE);
        empty.setVisibility(View.VISIBLE);
        empty.setText(messageRes);
        status.setText(R.string.pd_none);
        status.setTextColor(0xFFFBBF24);
    }

    /** Videos in the usual TV folders and at the top of each USB drive (and its Movies folder), by name. */
    static List<File> findVideos() {
        Set<String> seen = new LinkedHashSet<>();
        List<File> found = new ArrayList<>();
        for (File dir : storageLocations()) {
            File[] files = dir.listFiles(f -> f.isFile() && isVideo(f.getName()));
            if (files == null) continue;
            for (File f : files) {
                String key;
                try { key = f.getCanonicalPath(); } catch (IOException e) { key = f.getAbsolutePath(); }
                if (seen.add(key)) found.add(f);
            }
        }
        found.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return found;
    }

    private static List<File> storageLocations() {
        List<File> dirs = new ArrayList<>();
        File internal = Environment.getExternalStorageDirectory();
        dirs.add(new File(internal, "Movies/NLRC"));
        dirs.add(new File(internal, "Movies"));
        dirs.add(new File(internal, "Download"));
        dirs.add(internal);
        File[] mounts = new File("/storage").listFiles();
        if (mounts != null) {
            for (File m : mounts) {
                if (!m.isDirectory() || !isOnUsb(m)) continue;
                dirs.add(m);
                dirs.add(new File(m, "Movies"));
            }
        }
        return dirs;
    }

    private static boolean isVideo(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith(".")) return false;
        for (String ext : VIDEO_EXTENSIONS) if (n.endsWith(ext)) return true;
        return false;
    }

    /** True for paths on a removable drive (/storage/XXXX-XXXX), not internal storage. */
    private static boolean isOnUsb(File f) {
        String p = f.getAbsolutePath();
        return p.startsWith("/storage/") && !p.startsWith("/storage/emulated") && !p.startsWith("/storage/self");
    }

    /** "my_holiday-2019.mp4" → "my holiday 2019". */
    static String displayName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.replaceAll("[_\\-.]+", " ").trim();
    }

    private void openPlayer(int index) {
        ArrayList<String> paths = new ArrayList<>();
        for (File f : videos) paths.add(f.getAbsolutePath());
        startActivity(new Intent(this, VideoPlayerActivity.class)
                .putStringArrayListExtra(EXTRA_PATHS, paths)
                .putExtra(EXTRA_INDEX, index));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    private final class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView title;
            final TextView info;
            String path;

            Holder(View v) {
                super(v);
                thumb = v.findViewById(R.id.videoThumb);
                title = v.findViewById(R.id.videoTitle);
                info = v.findViewById(R.id.videoInfo);
                v.setOnClickListener(x -> {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) openPlayer(pos);
                });
                v.setOnFocusChangeListener((x, focused) -> x.animate()
                        .scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                        .translationZ(focused ? 16f : 0f).setDuration(200).start());
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video, parent, false);
            Fonts.applyWeights(v);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            File f = videos.get(position);
            String path = f.getAbsolutePath();
            h.path = path;
            h.title.setText(displayName(f));
            Bitmap cached = thumbs.get(path);
            bindDetails(h, f, cached, durations.get(path));
            boolean done = durations.containsKey(path) && (cached != null || noThumb.contains(path));
            if (done || !loading.add(path)) return;
            // Thumbnail and duration are slow to read (USB especially), so load them off the main thread.
            worker.execute(() -> {
                Bitmap bmp = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND);
                String dur = readDuration(path);
                main.post(() -> {
                    loading.remove(path);
                    if (bmp != null) thumbs.put(path, bmp); else noThumb.add(path);
                    durations.put(path, dur);
                    if (path.equals(h.path)) bindDetails(h, f, bmp, dur);
                });
            });
        }

        private void bindDetails(Holder h, File f, Bitmap bmp, String dur) {
            if (bmp != null) h.thumb.setImageBitmap(bmp); else h.thumb.setImageResource(R.drawable.bg_thumb_card);
            String source = getString(isOnUsb(f) ? R.string.pd_source_usb : R.string.pd_source_tv);
            h.info.setText(dur == null || dur.isEmpty() ? source : dur + " · " + source);
        }

        @Override
        public int getItemCount() { return videos.size(); }
    }

    private static String readDuration(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return ms == null ? "" : VideoPlayerActivity.formatTime(Long.parseLong(ms));
        } catch (RuntimeException e) {
            return "";
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }
}
