package com.example.pitpitch;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/** Framework-only runner: no dependency on names or Kotlin helpers removed by R8. */
public final class ReleaseSmokeInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            check((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0,
                "Must verify the actual optimized Release");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(new File(context.getFilesDir(), "datastore/pit_pitch.preferences_pb"))) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) hash.append(String.format("%02x", b));
            check(hash.toString().equals(arguments.getString("expectedPreferencesSha256")),
                "User preferences changed during cover installation");
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath("pitpitch.db").getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                try (Cursor rows = db.rawQuery("SELECT COUNT(*) FROM matches WHERE season >= 2020", null)) {
                    check(rows.moveToFirst() && rows.getInt(0) > 10000, "Archived fixtures missing");
                }
                try (Cursor rows = db.rawQuery("SELECT assetPath FROM localAssets", null)) {
                    int count = 0;
                    while (rows.moveToNext()) {
                        try (InputStream input = context.getAssets().open(rows.getString(0))) {
                            check(input.read() >= 0, "Missing asset binding");
                        }
                        count++;
                    }
                    check(count >= 700, "Incomplete resource catalog");
                }
            }
            result.putString("stream", "\nOK (1 release data verification)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL: " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
