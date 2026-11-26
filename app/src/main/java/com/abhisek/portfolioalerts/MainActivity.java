package com.abhisek.portfolioalerts;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView web;
    private boolean firstResume = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        Engine.ensureChannel(this);
        Engine.schedule(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
            return;
        }
        if (web != null) web.evaluateJavascript("window.refresh && window.refresh()", null);
    }

    class Bridge {
        @JavascriptInterface
        public String state() {
            return getSharedPreferences(Engine.PREFS, MODE_PRIVATE).getString("state", "");
        }

        @JavascriptInterface
        public void refresh() {
            new Thread(() -> {
                String s;
                try {
                    s = Engine.run(getApplicationContext(), true).toString();
                } catch (Exception e) {
                    s = "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
                }
                final String js = "window.onState(" + JSONObject.quote(s) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        @JavascriptInterface
        public void open(String url) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
                // No app can open the link.
            }
        }
    }
}
