package com.officedocs.app;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

// Файл, який інший застосунок передав через «Відкрити за допомогою» або «Поділитися».
@CapacitorPlugin(name = "IncomingFile")
public class IncomingFilePlugin extends Plugin {
    private static JSObject pending;
    private static IncomingFilePlugin instance;

    @Override
    public void load() {
        instance = this;
    }

    static void handle(Context ctx, Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;
        // Щоб той самий intent не відкрився вдруге після повороту екрана.
        intent.setAction(Intent.ACTION_MAIN);
        try {
            JSObject file = new JSObject();
            file.put("name", nameOf(ctx, uri));
            file.put("data", read(ctx, uri));
            pending = file;
            if (instance != null) instance.notifyListeners("file", new JSObject(), false);
        } catch (Exception e) {
            JSObject err = new JSObject();
            err.put("error", String.valueOf(e.getMessage()));
            pending = err;
        }
    }

    private static String nameOf(Context ctx, Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (i >= 0) name = c.getString(i);
                }
            } catch (Exception ignored) {}
        }
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "document" : name;
    }

    private static String read(Context ctx, Uri uri) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        }
    }

    @PluginMethod
    public void takePending(PluginCall call) {
        JSObject res = pending == null ? new JSObject() : pending;
        pending = null;
        call.resolve(res);
    }
}
