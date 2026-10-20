package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper to seamlessly switch the TV to HDMI inputs and query physical HDMI ports,
 * filtering out virtual HDMI-CEC child inputs.
 */
public final class HdmiHelper {

    private static final String TAG = "HdmiHelper";

    /**
     * Physical HDMI port information model.
     */
    public static class HdmiPort {
        public final int number;            // 1, 2, 3, 4
        public final String id;             // passthrough input ID
        public final String label;          // "HDMI 1", "HDMI 2", etc.
        public final String connectedDevice;// e.g. "PlayStation 5" or null
        public final int state;             // TvInputManager.INPUT_STATE_CONNECTED, etc.
        public final boolean isConnected;

        public HdmiPort(int number, String id, String label, String connectedDevice, int state, boolean isConnected) {
            this.number = number;
            this.id = id;
            this.label = label;
            this.connectedDevice = connectedDevice;
            this.state = state;
            this.isConnected = isConnected;
        }
    }

    /**
     * Realtek Passthrough Hardware ID for HDMI 2 on Acer V Pro 32 QLED (MOKA R3G).
     */
    public static final String HDMI_2_INPUT_ID =
            "com.realtek.tv.passthrough/.hdmiinput.HDMITvInputService/HW151519744";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static long sLastSwitchTime = 0;

    private HdmiHelper() {}

    /**
     * Checks if a TvInputInfo is an HDMI-CEC child device or virtual input rather than
     * a physical HDMI port.
     */
    public static boolean isChildCecInput(TvInputInfo info) {
        if (info == null) return false;
        if (info.getParentId() != null) return true;
        String id = info.getId();
        if (id == null) return false;
        // Check for CEC virtual input patterns, like HDMI100004 or HDMI followed by 4+ digits
        if (id.matches(".*HDMI\\d{4,}.*")) return true;
        if (id.contains("HDMI1000")) return true;
        return false;
    }

    private static int parsePortNumber(Context ctx, TvInputInfo info) {
        if (info == null) return 0;
        // 1. Try label (e.g. "1", "HDMI 1", etc.)
        try {
            CharSequence label = info.loadLabel(ctx);
            if (label != null) {
                String s = label.toString().trim();
                Matcher m = Pattern.compile("\\b([1-4])\\b").matcher(s);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
        } catch (Exception ignored) {}

        // 2. Try hardware IDs (HW15 -> 1, HW16 -> 2, HW17 -> 3, HW18 -> 4 on TCL/Mtk)
        String id = info.getId();
        if (id != null) {
            if (id.contains("HW15")) return 1;
            if (id.contains("HW16")) return 2;
            if (id.contains("HW17")) return 3;
            if (id.contains("HW18")) return 4;

            Matcher m = Pattern.compile("(?i)HDMI[_-]?([1-4])\\b").matcher(id);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return 0;
    }

    /**
     * Returns the physical HDMI ports on the TV (maximum 4), mapping any connected
     * CEC child devices (like PlayStation 5, Xbox, Apple TV) to their physical parent port.
     */
    public static List<HdmiPort> getHdmiPorts(Context context) {
        List<HdmiPort> result = new ArrayList<>();
        TvInputManager tim = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) {
            return getDefaultHdmiPorts();
        }

        List<TvInputInfo> allInputs;
        try {
            allInputs = tim.getTvInputList();
        } catch (Exception e) {
            allInputs = Collections.emptyList();
        }

        List<TvInputInfo> physicalInputs = new ArrayList<>();
        Map<String, TvInputInfo> cecByParentId = new HashMap<>();
        Map<Integer, TvInputInfo> cecByPortNum = new HashMap<>();

        for (TvInputInfo info : allInputs) {
            if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) {
                continue;
            }
            if (isChildCecInput(info)) {
                if (info.getParentId() != null) {
                    cecByParentId.put(info.getParentId(), info);
                }
                // Try to infer port number from ID (e.g. HDMI100004 -> port 1)
                Matcher m = Pattern.compile("HDMI([1-4])\\d{3,}").matcher(info.getId());
                if (m.find()) {
                    try {
                        int p = Integer.parseInt(m.group(1));
                        cecByPortNum.put(p, info);
                    } catch (Exception ignored) {}
                }
            } else {
                physicalInputs.add(info);
            }
        }

        // Sort physical inputs by port number
        Collections.sort(physicalInputs, (a, b) -> {
            int pa = parsePortNumber(context, a);
            int pb = parsePortNumber(context, b);
            if (pa != pb) return Integer.compare(pa, pb);
            return a.getId().compareTo(b.getId());
        });

        int nextSequentialPort = 1;
        Set<Integer> usedPorts = new HashSet<>();

        for (TvInputInfo phys : physicalInputs) {
            if (result.size() >= 4) break; // Hard physical TV limit: 4 ports

            int portNum = parsePortNumber(context, phys);
            if (portNum < 1 || portNum > 4 || usedPorts.contains(portNum)) {
                while (nextSequentialPort <= 4 && usedPorts.contains(nextSequentialPort)) {
                    nextSequentialPort++;
                }
                if (nextSequentialPort > 4) break;
                portNum = nextSequentialPort;
            }
            usedPorts.add(portNum);

            String id = phys.getId();
            int state = tim.getInputState(id);

            // Check if there is an active CEC device on this port
            TvInputInfo cecChild = cecByParentId.get(id);
            if (cecChild == null) {
                cecChild = cecByPortNum.get(portNum);
            }

            String deviceName = null;
            if (cecChild != null) {
                int childState = tim.getInputState(cecChild.getId());
                if (childState == TvInputManager.INPUT_STATE_CONNECTED) {
                    state = TvInputManager.INPUT_STATE_CONNECTED;
                }
                try {
                    CharSequence childLabel = cecChild.loadLabel(context);
                    if (childLabel != null && childLabel.length() > 0) {
                        String s = childLabel.toString().trim();
                        if (!s.equalsIgnoreCase("HDMI") && !s.equalsIgnoreCase(String.valueOf(portNum))
                                && !s.equalsIgnoreCase("HDMI " + portNum) && !s.equalsIgnoreCase("HDMI" + portNum)) {
                            deviceName = s;
                        }
                    }
                } catch (Exception ignored) {}
            }

            boolean isConnected = (state == TvInputManager.INPUT_STATE_CONNECTED);
            result.add(new HdmiPort(portNum, id, "HDMI " + portNum, deviceName, state, isConnected));
        }

        if (result.isEmpty()) {
            return getDefaultHdmiPorts();
        }

        return result;
    }

    private static List<HdmiPort> getDefaultHdmiPorts() {
        List<HdmiPort> list = new ArrayList<>(4);
        list.add(new HdmiPort(1, "com.tcl.tvinput/.TvPassThroughService/HW15", "HDMI 1", null, TvInputManager.INPUT_STATE_DISCONNECTED, false));
        list.add(new HdmiPort(2, HDMI_2_INPUT_ID, "HDMI 2", null, TvInputManager.INPUT_STATE_DISCONNECTED, false));
        list.add(new HdmiPort(3, "com.tcl.tvinput/.TvPassThroughService/HW17", "HDMI 3", null, TvInputManager.INPUT_STATE_DISCONNECTED, false));
        list.add(new HdmiPort(4, "com.tcl.tvinput/.TvPassThroughService/HW18", "HDMI 4", null, TvInputManager.INPUT_STATE_DISCONNECTED, false));
        return list;
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
                    Toast.makeText(context.getApplicationContext(),
                            "జియో టీవీ కి మారుతోంది... 📺 (Jio TV HDMI 2)",
                            Toast.LENGTH_SHORT).show();
                }
                Intent intent = createHdmi2Intent();
                context.startActivity(intent);
                Log.i(TAG, "Successfully triggered HDMI 2 passthrough intent");
            } catch (Exception e) {
                Log.e(TAG, "Failed to switch to HDMI 2", e);
                try {
                    Toast.makeText(context.getApplicationContext(),
                            "Cannot open HDMI 2", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
    }
}
