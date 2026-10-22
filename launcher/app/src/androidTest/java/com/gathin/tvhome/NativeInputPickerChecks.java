package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;

/** Component-access tests without a new test library or a vendor dependency. */
public class NativeInputPickerChecks extends Instrumentation {
    public static class Public extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {}
    }
    public static class Private extends Public {}
    public static class Disabled extends Public {}
    public static class Protected extends Public {}

    private Context configured(String action, String component) {
        Context target = getTargetContext();
        Resources original = target.getResources();
        Resources configured = new Resources(original.getAssets(), original.getDisplayMetrics(),
                original.getConfiguration()) {
            @Override public String getString(int id) {
                if (id == R.string.native_input_picker_action) return action;
                if (id == R.string.native_input_picker_receiver) return component;
                return super.getString(id);
            }
        };
        return new ContextWrapper(target) {
            @Override public Resources getResources() { return configured; }
        };
    }

    private String receiver(Class<?> type) {
        return new ComponentName(getContext().getPackageName(), type.getName()).flattenToString();
    }

    private void unavailable(String action, String component) {
        if (NativeInputPicker.available(configured(action, component)))
            throw new AssertionError("Unexpected accessible receiver: " + component);
    }

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            unavailable("", receiver(Public.class));
            unavailable("test.inputs", "");
            unavailable("test.inputs", "malformed");
            unavailable("test.inputs", "missing.package/.Receiver");
            unavailable("test.inputs", receiver(Private.class));
            unavailable("test.inputs", receiver(Disabled.class));
            unavailable("test.inputs", receiver(Protected.class));
            Context valid = configured("test.inputs", receiver(Public.class));
            if (!NativeInputPicker.available(valid) || !NativeInputPicker.show(valid))
                throw new AssertionError("Accessible exported receiver rejected");
            result.putString("stream", "PASS: empty/malformed/missing/disabled/private/protected/exported receivers\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
