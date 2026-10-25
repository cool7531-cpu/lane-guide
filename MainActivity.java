package kr.lane.guide;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.content.ActivityNotFoundException;
import org.json.JSONObject;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    static final String BASE = "https://cool7531-cpu.github.io/lane-guide/";
    private WebView web;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingOrigin;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (hasLocation()) { cb.invoke(origin, true, false); }
                else { pendingGeo = cb; pendingOrigin = origin; askPermissions(); }
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        askPermissions();
        web.loadUrl(urlFor(getIntent()));
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && i.getData() != null) web.loadUrl(urlFor(i));
    }

    /** lanegd://keys?tmap=..&its=.. 로 열리면 키를 해시로 넘겨 앱이 저장하게 함 */
    private String urlFor(Intent i) {
        Uri d = i == null ? null : i.getData();
        if (d != null && "lanegd".equals(d.getScheme())) {
            String t = d.getQueryParameter("tmap"), s = d.getQueryParameter("its");
            StringBuilder h = new StringBuilder();
            if (t != null) h.append("tmap=").append(Uri.encode(t));
            if (s != null) { if (h.length() > 0) h.append('&'); h.append("its=").append(Uri.encode(s)); }
            if (h.length() > 0) return BASE + "#" + h;
        }
        return BASE;
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (!hasLocation()) { need.add(Manifest.permission.ACCESS_FINE_LOCATION); need.add(Manifest.permission.ACCESS_COARSE_LOCATION); }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            need.add("android.permission.POST_NOTIFICATIONS");
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (pendingGeo != null) { pendingGeo.invoke(pendingOrigin, hasLocation(), false); pendingGeo = null; }
    }

    static final int REQ_VOICE = 42;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE) return;
        String text = "";
        if (res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) text = r.get(0);
        }
        web.evaluateJavascript("window.onVoiceResult&&window.onVoiceResult(" + JSONObject.quote(text) + ")", null);
    }

    @Override
    public void onBackPressed() { moveTaskToBack(true); }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    class Bridge {
        @JavascriptInterface public boolean isNative() { return true; }

        @JavascriptInterface public void startGuide(String json) {
            GuideService.pending = json;
            Intent i = new Intent(MainActivity.this, GuideService.class).setAction(GuideService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }

        @JavascriptInterface public void voiceSearch() {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "목적지를 말하세요");
                try { startActivityForResult(i, REQ_VOICE); }
                catch (ActivityNotFoundException e) {
                    web.evaluateJavascript("window.onVoiceResult&&window.onVoiceResult(null)", null);
                }
            });
        }

        @JavascriptInterface public void startFree(String json) {
            GuideService.pending = json;
            Intent i = new Intent(MainActivity.this, GuideService.class).setAction(GuideService.ACTION_FREE);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }

        @JavascriptInterface public void stopGuide() {
            Intent i = new Intent(MainActivity.this, GuideService.class).setAction(GuideService.ACTION_STOP);
            startService(i);
        }
    }
}
