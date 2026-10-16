package com.memora.gerencial;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Shows the real Torque login page (atacado.fmtorque.com.br). The user signs in there,
 * with location if the panel asks for it; the session cookie it creates is shared with
 * the app's WebView, which then reads the orders. Closes itself once the user is in.
 */
public class TorqueLoginActivity extends AppCompatActivity {

    static final String LOGIN_URL = "https://atacado.fmtorque.com.br/login";
    private static final int REQ_LOCATION = 41;

    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingOrigin;
    private boolean finished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingOrigin = origin;
                    pendingGeo = callback;
                    ActivityCompat.requestPermissions(TorqueLoginActivity.this, new String[]{
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION }, REQ_LOCATION);
                }
            }
        });

        web.loadUrl(LOGIN_URL);
        handler.postDelayed(checkSignedIn, 1500);
    }

    /** The panel is a single-page app: once it leaves /login, the user is signed in. */
    private final Runnable checkSignedIn = new Runnable() {
        @Override
        public void run() {
            if (finished || web == null) return;
            web.evaluateJavascript("location.hostname + location.pathname", value -> {
                String path = value == null ? "" : value.replace("\"", "");
                if (path.startsWith("atacado.fmtorque.com.br/") && !path.startsWith("atacado.fmtorque.com.br/login")) {
                    finished = true;
                    CookieManager.getInstance().flush();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    handler.postDelayed(checkSignedIn, 1000);
                }
            });
        }
    };

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && pendingGeo != null) {
            pendingGeo.invoke(pendingOrigin, hasLocationPermission(), false);
            pendingGeo = null;
            pendingOrigin = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
