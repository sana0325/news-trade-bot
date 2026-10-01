package com.vektor.signals;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VektorFeedPlugin.class);
        super.onCreate(savedInstanceState);
        // Процес WebView не понижуємо у фоні: інакше Android його морозить або вбиває,
        // і рушій сигналів зупиняється, хоча фонова служба жива.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView wv = getBridge().getWebView();
            if (wv != null) wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
    }
}
