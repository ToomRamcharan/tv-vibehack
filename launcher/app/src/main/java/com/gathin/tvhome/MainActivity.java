package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView fatherGreeting;
    private TextView festivalText;
    private TextView btnWifi;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    private int lastFocused = 0;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateFatherGreeting();
            updateFestivalGreeting();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        fatherGreeting = findViewById(R.id.fatherGreeting);
        festivalText = findViewById(R.id.festivalText);
        updateFatherGreeting();
        updateFestivalGreeting();
        btnWifi = findViewById(R.id.btnWifi);
        BootReceiver.ensureAccessibilityEnabled(this);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((145 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        // Long press on Settings also opens the dock editor, in case this remote has no MENU key.
        btnSettings.setLongClickable(true);
        btnSettings.setOnLongClickListener(v -> {
            startActivity(new Intent(this, DockEditorActivity.class));
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        adapter.reload();
        grid.scheduleLayoutAnimation();
        updateGreeting();
        updateFatherGreeting();
        updateFestivalGreeting();
        updateWifiLabel();
        BootReceiver.ensureAccessibilityEnabled(this);
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the tile we left from, or the first one.
        grid.post(() -> {
            View focused = getCurrentFocus();
            if (focused != null && grid.findContainingViewHolder(focused) != null) return;
            focusTile(lastFocused);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (grid != null) {
            grid.post(() -> focusTile(lastFocused));
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    /** MENU (or any long press captured by children) key opens the dock editor from the home screen. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(this, DockEditorActivity.class));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            if (adapter.isDockSlotEmpty(position)) {
                hideHero();
            } else {
                showHero(position, r.top - dy);
            }
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** NLRC brand is set statically in layout; this method is kept for lifecycle compatibility. */
    private void updateGreeting() {
        // No-op: NLRC brand name is declared in activity_main.xml — no runtime greeting needed.
    }

    /**
     * Polite home greeting based on time of day:
     * - Morning (5:00 AM – 11:59 AM): శుభోదయం · స్వాగతం! 🙏
     * - Afternoon (12:00 PM – 4:59 PM): శుభ మధ్యాహ్నం · స్వాగతం! 🙏
     * - Evening (5:00 PM – 8:59 PM): శుభ సాయంత్రం · స్వాగతం! 🙏
     * - Night (9:00 PM – 4:59 AM): శుభ రాత్రి · స్వాగతం! 🙏
     */
    private void updateFatherGreeting() {
        if (fatherGreeting == null) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);

        String wish;
        if (hour >= 5 && hour < 12) {
            wish = "శుభోదయం · స్వాగతం! 🙏";
        } else if (hour >= 12 && hour < 17) {
            wish = "శుభ మధ్యాహ్నం · స్వాగతం! 🙏";
        } else if (hour >= 17 && hour < 21) {
            wish = "శుభ సాయంత్రం · స్వాగతం! 🙏";
        } else {
            wish = "శుభ రాత్రి · స్వాగతం! 🙏";
        }
        fatherGreeting.setText(wish);
    }

    /** Updates dynamic Telangana Panchangam, Calendar & Festival wishes based on current date. */
    private void updateFestivalGreeting() {
        if (festivalText == null) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int month = cal.get(java.util.Calendar.MONTH) + 1; // 1-based: 1=Jan, 10=Oct
        int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK);

        String teluguDay;
        switch (dayOfWeek) {
            case java.util.Calendar.SUNDAY:    teluguDay = "ఆదివారం"; break;
            case java.util.Calendar.MONDAY:    teluguDay = "సోమవారం"; break;
            case java.util.Calendar.TUESDAY:   teluguDay = "మంగళవారం"; break;
            case java.util.Calendar.WEDNESDAY: teluguDay = "బుధవారం"; break;
            case java.util.Calendar.THURSDAY:  teluguDay = "గురువారం"; break;
            case java.util.Calendar.FRIDAY:    teluguDay = "శుక్రవారం"; break;
            case java.util.Calendar.SATURDAY:  teluguDay = "శనివారం"; break;
            default: teluguDay = "";
        }

        String greeting;

        // Dynamic Telangana & Indian Festivals + Panchangam Details
        if (month == 10 && day == 19) {
            // Today: Monday 19 October 2026 (Saddula Bathukamma & Navami)
            greeting = "🌸 సద్దుల బతుకమ్మ & మహార్నవమి · ఆశ్వయుజ శుక్ల నవమి · " + teluguDay + " 🌸";
        } else if (month == 10 && day == 20) {
            // Tomorrow: Tuesday 20 October 2026 (Dussehra / Vijayadashami)
            greeting = "🌺 విజయదశమి దసరా పండుగ శుభాకాంక్షలు · ఆశ్వయుజ శుక్ల దశమి · " + teluguDay + " 🌺";
        } else if (month == 10 && day <= 22) {
            greeting = "🌸 విజయదశమి దసరా శుభాకాంక్షలు · ఆశ్వయుజ మాసం · " + teluguDay + " 🌸";
        } else if (month == 11 && day <= 15) {
            greeting = "🪔 దీపావళి లక్ష్మీ పూజ శుభాకాంక్షలు · కార్తీక మాసం · " + teluguDay + " 🪔";
        } else if (month == 1 && day >= 13 && day <= 17) {
            greeting = "🪁 సంక్రాంతి & కనుమ పండుగ శుభాకాంక్షలు · పుష్య మాసం · " + teluguDay + " 🪁";
        } else if ((month == 2 && day >= 24) || (month == 3 && day <= 4)) {
            greeting = "🔱 మహా శివరాత్రి శుభాకాంక్షలు · మాఘ బహుళ చతుర్దశి · " + teluguDay + " 🔱";
        } else if (month == 3 && day >= 22 && day <= 27) {
            greeting = "🎨 హోలీ పండుగ శుభాకాంక్షలు · ఫాల్గుణ పౌర్ణమి · " + teluguDay + " 🎨";
        } else if ((month == 3 && day >= 28) || (month == 4 && day <= 8)) {
            greeting = "🌿 శ్రీ క్రోధి/విశ్వావసు నామ ఉగాది శుభాకాంక్షలు · చైత్ర శుద్ధ పాడ్యమి · " + teluguDay + " 🌿";
        } else if (month == 4 && day >= 14 && day <= 22) {
            greeting = "🏹 శ్రీరామనవమి శుభాకాంక్షలు · చైత్ర శుక్ల నవమి · " + teluguDay + " 🏹";
        } else if (month == 7 || (month == 8 && day <= 10)) {
            greeting = "🌺 తెలంగాణ చారిత్రక బోనాలు పండుగ శుభాకాంక్షలు · ఆషాఢ మాసం · " + teluguDay + " 🌺";
        } else if (month == 8 && day >= 14 && day <= 22) {
            greeting = "🪢 రక్షాబంధన్ & వరలక్ష్మి వ్రత శుభాకాంక్షలు · శ్రావణ మాసం · " + teluguDay + " 🪢";
        } else if ((month == 8 && day >= 25) || (month == 9 && day <= 15)) {
            greeting = "🐘 వినాయక చవితి శుభాకాంక్షలు · భాద్రపద శుక్ల చవితి · " + teluguDay + " 🐘";
        } else if ((month == 12 && day >= 30) || (month == 1 && day <= 3)) {
            greeting = "✨ నూతన సంవత్సర శుభాకాంక్షలు · Happy New Year · " + teluguDay + " ✨";
        } else {
            greeting = "🕉️ శ్రీ సాంబ సదాశివాయ నమః · " + teluguDay + " శుభ దినం · తూం వారి నివాసం 🕉️";
        }

        festivalText.setText(greeting);
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        btnWifi.setText(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
