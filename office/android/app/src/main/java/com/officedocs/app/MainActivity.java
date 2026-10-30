package com.officedocs.app;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(IncomingFilePlugin.class);
        super.onCreate(savedInstanceState);
        IncomingFilePlugin.handle(this, getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        IncomingFilePlugin.handle(this, intent);
    }
}
