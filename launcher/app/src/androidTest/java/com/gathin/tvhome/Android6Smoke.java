package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;

/** Framework-only instrumentation: no additional test/runtime dependency. */
public class Android6Smoke extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runOnMainSync(() -> {
                Context themed = new ContextThemeWrapper(getTargetContext(), R.style.Theme_TvHome);
                LayoutInflater inflater = LayoutInflater.from(themed);
                for (int layout : new int[]{R.layout.activity_main, R.layout.item_app,
                        R.layout.item_input, R.layout.popup_inputs}) {
                    if (inflater.inflate(layout, null) == null) throw new AssertionError("Null layout");
                }
            });
            Activity home = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(home::finish);
            result.putString("stream", "PASS: main, app tile and input layouts inflate; MainActivity starts\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
