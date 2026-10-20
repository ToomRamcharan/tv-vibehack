package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.text.TextUtils;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App tiles + HDMI/Jio input tiles.
 * First {@link MainActivity#COLUMNS} positions are the pinned dock. Its order is saved on the TV
 * (long-press OK on a tile to pin or remove it); R.array.dock_apps is the default before any change.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);
        boolean onTileVerticalKey(int position, int keyCode);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        final Intent launchIntent;
        final boolean isInput;
        int ambient;

        /** Regular application tile. */
        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label        = label;
            this.component    = component;
            this.pkg          = component.getPackageName();
            this.banner       = banner;
            this.icon         = icon;
            this.launchIntent = null;
            this.isInput      = false;
        }

        /** TV Input tile (HDMI 1 / Jio TV, HDMI 2, AV). */
        Entry(String label, String pkgKey, Drawable banner, Drawable icon, Intent launchIntent, int ambientColor) {
            this.label        = label;
            this.component    = null;
            this.pkg          = pkgKey;
            this.banner       = banner;
            this.icon         = icon;
            this.launchIntent = launchIntent;
            this.isInput      = true;
            this.ambient      = ambientColor;
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    private static final String PREFS = "dock";
    private static final String KEY_DOCK = "dock_apps";
    private static final String KEY_HIDDEN = "hidden_apps";

    private final List<String> favourites = new ArrayList<>();
    private final Set<String>  hidden;
    private final List<Entry>  items = new ArrayList<>();
    /** Tiles the viewer hid from the long-press menu, saved on the TV. */
    private final Set<String>  userHidden = new HashSet<>();
    private final List<Entry>  hiddenItems = new ArrayList<>();
    private RecyclerView recycler;

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx        = ctx;
        this.listener   = listener;
        this.dockSize   = dockSize;
        // Device-protected storage: the launcher is directBootAware and may start before unlock.
        String saved = prefs().getString(KEY_DOCK, null);
        favourites.addAll(saved != null
                ? Arrays.asList(TextUtils.split(saved, ","))
                : Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps)));
        userHidden.addAll(prefs().getStringSet(KEY_HIDDEN, Collections.emptySet()));
        this.hidden     = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);

        List<Entry> fresh = new ArrayList<>();

        // ── 1. Regular apps ──────────────────────────────────────────────────
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon  = ri.loadIcon(pm);
            String   label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
        }

        // ── 2. TV Inputs (HDMI 1 / Jio TV, HDMI 2, AV) ──────────────────────
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // Sort: HDMI first, then others
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI;
                boolean hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(ctx))
                             .compareToIgnoreCase(String.valueOf(b.loadLabel(ctx)));
            });

            int hdmiIdx = 1;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                boolean isHdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                int state = tim.getInputState(info.getId());
                boolean isConnected = (state == TvInputManager.INPUT_STATE_CONNECTED);

                Intent switchIntent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(info.getId()),
                                "vnd.android.cursor.item/channel")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                Drawable fallbackIcon;
                try {
                    fallbackIcon = ctx.getDrawable(R.drawable.ic_input);
                } catch (Exception ex) {
                    fallbackIcon = ctx.getDrawable(android.R.drawable.ic_media_play);
                }

                if (isHdmi) {
                    String pkgKey = "hdmi_" + hdmiIdx;
                    if (hdmiIdx == 2) {
                        // Slot 0 in dock: HDMI 2 with connected Jio TV Set Top Box
                        String title    = "HDMI 2";
                        String sub      = isConnected ? "Jio TV" : "Set Top Box";
                        String fullLabel = "HDMI 2 · Jio TV";
                        Drawable banner = createHdmiBanner(ctx, title, sub, isConnected, 0xFF0D1B2A, 0xFF1B263B);
                        fresh.add(new Entry(fullLabel, pkgKey, banner, fallbackIcon, switchIntent, 0xFF0077EE));
                    } else {
                        // HDMI 1 (or other HDMI)
                        String title    = "HDMI " + hdmiIdx;
                        String sub      = isConnected ? "Connected" : "No Device";
                        String fullLabel = "HDMI " + hdmiIdx;
                        Drawable banner = createHdmiBanner(ctx, title, sub, isConnected, 0xFF141414, 0xFF222222);
                        fresh.add(new Entry(fullLabel, pkgKey, banner, fallbackIcon, switchIntent, 0xFF555555));
                    }
                    hdmiIdx++;
                } else {
                    // AV Input
                    String fullLabel = "AV Input";
                    Drawable banner = createHdmiBanner(ctx, "AV", isConnected ? "Connected" : "Composite", isConnected, 0xFF141414, 0xFF222222);
                    fresh.add(new Entry(fullLabel, "av_input", banner, fallbackIcon, switchIntent, 0xFF555555));
                }
            }
        }

        // ── 2b. NLRC PenDrive Viewer (Offline Media Part 1, 2, 3) ──────────
        Intent penDriveIntent = new Intent(ctx, PenDriveActivity.class);
        penDriveIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Drawable fallbackPlayIcon;
        try {
            fallbackPlayIcon = ctx.getDrawable(android.R.drawable.ic_media_play);
        } catch (Exception ex) {
            fallbackPlayIcon = null;
        }
        Drawable mediaBanner = createMediaTileBanner(ctx);
        fresh.add(new Entry("NLRC PenDrive Viewer", "nlrc_pendrive",
                mediaBanner, fallbackPlayIcon, penDriveIntent, 0xFFFFB300));

        // ── 3. Sort: dock favourites first, in saved order; the rest alphabetically ─
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg);
            int fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });

        items.clear();
        hiddenItems.clear();
        for (Entry e : fresh) (userHidden.contains(e.pkg) ? hiddenItems : items).add(e);
        notifyDataSetChanged();
    }

    /**
     * Creates a sleek, high-definition rectangular banner for HDMI inputs matching
     * the visual weight of Netflix and JioHotstar app cards.
     */
    private static Drawable createHdmiBanner(Context ctx, String mainTitle, String subTitle, boolean isConnected, int colStart, int colEnd) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int w = (int) (180 * d);
        int h = (int) (101 * d);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        // 1. Rich dark gradient card background
        Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBg.setShader(new LinearGradient(0, 0, w, h, colStart, colEnd, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, pBg);

        // 2. Translucent top specular sheen
        Paint pSpec = new Paint(Paint.ANTI_ALIAS_FLAG);
        pSpec.setShader(new LinearGradient(0, 0, 0, h * 0.55f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.55f, pSpec);

        // 3. HDMI / TV Hardware Vector Icon in top-left
        try {
            Drawable ic = ctx.getDrawable(R.drawable.ic_input);
            if (ic != null) {
                int icSize = (int) (24 * d);
                int icX    = (int) (16 * d);
                int icY    = (int) (16 * d);
                ic.setBounds(icX, icY, icX + icSize, icY + icSize);
                ic.setTint(0xE6FFFFFF);
                ic.draw(c);
            }
        } catch (Exception ignored) {}

        // 4. Status Dot in top-right (Green = Connected, Dim Gray = Standby)
        Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        pDot.setColor(isConnected ? 0xFF10B981 : 0x55FFFFFF);
        c.drawCircle(w - (18 * d), 22 * d, 4f * d, pDot);

        // 5. Main Title ("HDMI 1")
        Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
        pTitle.setColor(Color.WHITE);
        pTitle.setTextSize(17f * d);
        pTitle.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pTitle.setShadowLayer(4f * d, 0, 2f * d, 0xAA000000);
        c.drawText(mainTitle, 16 * d, h - (32 * d), pTitle);

        // 6. Subtitle ("Jio TV")
        Paint pSub = new Paint(Paint.ANTI_ALIAS_FLAG);
        pSub.setColor(0xFFCBD5E1); // Clean silver
        pSub.setTextSize(12f * d);
        pSub.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText(subTitle, 16 * d, h - (14 * d), pSub);

        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    /**
     * Creates a sleek card banner for PenDrive / Local Media Viewer.
     */
    private static Drawable createMediaTileBanner(Context ctx) {
        try {
            Drawable customBanner = ctx.getDrawable(R.drawable.banner_pendrive_viewer);
            if (customBanner != null) return customBanner;
        } catch (Exception ignored) {}

        float d = ctx.getResources().getDisplayMetrics().density;
        int w = (int) (180 * d);
        int h = (int) (101 * d);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        // 1. Draw base dark gradient card background
        Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBg.setShader(new LinearGradient(0, 0, w, h, 0xFF2A1B08, 0xFF0E1626, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, pBg);

        // 2. Heavy dark gradient scrim on bottom for readability
        Paint pScrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        pScrim.setShader(new LinearGradient(0, h * 0.35f, 0, h, 0x00000000, 0xF0000000, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.35f, w, h, pScrim);

        // 3. Gold Pill Badge in Top-Right
        Paint pBadge = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBadge.setColor(0xEEB8860B);
        RectF rBadge = new RectF(w - (68 * d), 10 * d, w - (12 * d), 26 * d);
        c.drawRoundRect(rBadge, 8 * d, 8 * d, pBadge);

        Paint pBadgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBadgeText.setColor(Color.WHITE);
        pBadgeText.setTextSize(9f * d);
        pBadgeText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText("3 Parts", w - (62 * d), 21 * d, pBadgeText);

        // 4. Main Title
        Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
        pTitle.setColor(Color.WHITE);
        pTitle.setTextSize(15f * d);
        pTitle.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pTitle.setShadowLayer(4f * d, 0, 2f * d, 0xDD000000);
        c.drawText("PenDrive Viewer", 14 * d, h - (26 * d), pTitle);

        // 5. Subtitle (Telugu)
        Paint pSub = new Paint(Paint.ANTI_ALIAS_FLAG);
        pSub.setColor(0xFFFFD700); // Bright gold
        pSub.setTextSize(11f * d);
        pSub.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pSub.setShadowLayer(3f * d, 0, 1f * d, 0xDD000000);
        c.drawText("Personal Videos · Part 1, 2, 3", 14 * d, h - (10 * d), pSub);

        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);
        h.name.setAlpha(0f);
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);

        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }

        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            showTileMenu(e, h.getBindingAdapterPosition());
            return true;
        });
    }

    private SharedPreferences prefs() {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView rv) { recycler = rv; }

    /** Long-press menu: move within / pin to / remove from the dock, plus App info for real apps. */
    private void showTileMenu(Entry e, int position) {
        boolean inDock = position >= 0 && position < dockSize;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (inDock && position > 0) {
            labels.add(ctx.getString(R.string.move_left));
            actions.add(() -> move(position, position - 1));
        }
        if (inDock && position < Math.min(dockSize, items.size()) - 1) {
            labels.add(ctx.getString(R.string.move_right));
            actions.add(() -> move(position, position + 1));
        }
        labels.add(ctx.getString(inDock ? R.string.remove_from_dock : R.string.pin_to_dock));
        actions.add(() -> { if (inDock) unpin(e); else pin(e); });
        labels.add(ctx.getString(R.string.hide_app));
        actions.add(() -> hide(e, inDock));
        if (!hiddenItems.isEmpty()) {
            labels.add(ctx.getString(R.string.hidden_apps) + " (" + hiddenItems.size() + ")");
            actions.add(this::showHiddenMenu);
        }
        if (!e.isInput) {
            labels.add(ctx.getString(R.string.app_info));
            actions.add(() -> {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + e.pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { ctx.startActivity(i); } catch (ActivityNotFoundException ignored) {}
            });
        }
        new AlertDialog.Builder(ctx)
                .setTitle(e.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Removes the tile from the screen (and the dock); it can be shown again from "Hidden apps". */
    private void hide(Entry e, boolean inDock) {
        userHidden.add(e.pkg);
        prefs().edit().putStringSet(KEY_HIDDEN, new HashSet<>(userHidden)).apply();
        if (inDock) {
            List<String> dock = dockPackages();
            dock.remove(e.pkg);
            saveDock(dock);
        } else {
            reload();
        }
        Toast.makeText(ctx, ctx.getString(R.string.app_hidden, e.label), Toast.LENGTH_SHORT).show();
    }

    /** Lists hidden tiles; picking one shows it again in the grid. */
    private void showHiddenMenu() {
        List<Entry> snapshot = new ArrayList<>(hiddenItems);
        String[] names = new String[snapshot.size()];
        for (int i = 0; i < names.length; i++) names[i] = snapshot.get(i).label;
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.hidden_apps)
                .setItems(names, (d, which) -> {
                    Entry e = snapshot.get(which);
                    userHidden.remove(e.pkg);
                    prefs().edit().putStringSet(KEY_HIDDEN, new HashSet<>(userHidden)).apply();
                    reload();
                    Toast.makeText(ctx, ctx.getString(R.string.app_shown, e.label), Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /** Swaps two dock slots and keeps focus on the moved tile so it can be moved again. */
    private void move(int from, int to) {
        List<String> dock = dockPackages();
        Collections.swap(dock, from, to);
        saveDock(dock);
        if (recycler != null) recycler.post(() -> {
            RecyclerView.ViewHolder vh = recycler.findViewHolderForAdapterPosition(to);
            if (vh instanceof Holder) ((Holder) vh).card.requestFocus();
        });
    }

    /** Puts the tile in the first dock slot; the last dock tile moves back to the grid. */
    private void pin(Entry e) {
        List<String> dock = dockPackages();
        dock.add(0, e.pkg);
        saveDock(dock.subList(0, Math.min(dockSize, dock.size())));
        Toast.makeText(ctx, ctx.getString(R.string.pinned_to_dock, e.label), Toast.LENGTH_SHORT).show();
    }

    /** Drops the tile from the dock; the first grid app takes the freed slot. */
    private void unpin(Entry e) {
        List<String> dock = dockPackages();
        dock.remove(e.pkg);
        for (int i = dockSize; i < items.size(); i++) {
            String pkg = items.get(i).pkg;
            if (!pkg.equals(e.pkg)) { dock.add(pkg); break; }
        }
        saveDock(dock);
        Toast.makeText(ctx, ctx.getString(R.string.removed_from_dock, e.label), Toast.LENGTH_SHORT).show();
    }

    private List<String> dockPackages() {
        List<String> dock = new ArrayList<>();
        for (int i = 0; i < Math.min(dockSize, items.size()); i++) dock.add(items.get(i).pkg);
        return dock;
    }

    private void saveDock(List<String> dock) {
        favourites.clear();
        favourites.addAll(dock);
        prefs().edit().putString(KEY_DOCK, TextUtils.join(",", dock)).apply();
        reload();
    }

    private void launch(Entry e) {
        try {
            if (("com.netflix.ninja".equals(e.pkg) || "com.amazon.amazonvideo.livingroom".equals(e.pkg))
                    && HdmiHelper.isAvailable(ctx)) {
                // User requirement: Clicking Netflix or Prime Video opens HDMI 2 (Jio TV)
                HdmiHelper.switchToHdmi2(ctx, true);
                return;
            }
            if (e.launchIntent != null) {
                // Switch directly to HDMI 2 (Jio TV) or selected input
                ctx.startActivity(e.launchIntent);
            } else {
                // Regular app launch
                Intent i = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(e.component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                ctx.startActivity(i);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        Drawable d = live.getConstantState() != null
                ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);
                if (score > bestScore) { bestScore = score; best = p; }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {
            int avg = Color.rgb((int)(r / (n * n)), (int)(g / (n * n)), (int)(b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View      card;
        final ImageView banner;
        final ImageView icon;
        final TextView  label;
        final TextView  name;
        private boolean held;

        Holder(View v, TileListener listener) {
            super(v);
            card   = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon   = v.findViewById(R.id.icon);
            label  = v.findViewById(R.id.label);
            name   = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius  = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    // Holding OK repeats key-down; open the tile menu once and swallow the release
                    // so it doesn't also launch the app.
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
                        if (!held) { held = true; view.performLongClick(); }
                        return true;
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP && held) { held = false; return true; }
                    return false;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION
                        && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
