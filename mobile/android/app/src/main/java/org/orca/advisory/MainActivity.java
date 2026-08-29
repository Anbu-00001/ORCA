package org.orca.advisory;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

/**
 * ORCA's Android shell: one Activity, one WebView, no framework.
 *
 * <p>WHAT THIS CLASS IS FOR. The whole advisory client already exists in
 * web/ -- verdicts, evidence panel, Douglas ruler, Tamil rendering, the 3D
 * view, and the offline store in web/offline.js. This file does not
 * reimplement any of it and must never start to. It answers exactly one
 * question: how do those files get a real web origin on a phone?
 *
 * <p>THE ORIGIN PROBLEM, which is the only hard part. The obvious approach
 * is {@code webView.loadUrl("file:///android_asset/index.html")}. It does
 * not work for ORCA:
 *
 * <ul>
 *   <li>{@code file://} has an <em>opaque</em> origin. localStorage is
 *       unreliable across it and is not shared the way a real origin's is
 *       -- and web/offline.js keeps the entire cached advisory in
 *       localStorage.</li>
 *   <li>Service workers are unavailable on {@code file://} at all. web/sw.js
 *       would simply never register.</li>
 * </ul>
 *
 * <p>So a file:// build would launch, look completely correct, and have
 * silently lost both halves of the offline story -- which is the only
 * reason this APK exists. {@link WebViewAssetLoader} fixes it by serving
 * the same bundled assets over {@code https://appassets.androidplatform.net/},
 * an origin WebView treats as a secure context.
 *
 * <p>WHY THE APK IS MORE OFFLINE THAN THE PWA. In the browser, the service
 * worker is what makes the shell survive losing the network. Here the
 * shell is <em>inside the APK</em>, served from local assets on every
 * launch, so it cannot fail to load no matter what the radio is doing.
 * That matters because WebView is documented to sometimes drop service
 * worker registrations across app restarts; ORCA's critical path no
 * longer depends on one. The service worker still loads and still does
 * its runtime caching of GET /bundle, but it is now an optimisation
 * rather than the foundation.
 */
public class MainActivity extends Activity {

    /**
     * Where the app looks for a live backend to refresh its advisory.
     *
     * <p>10.0.2.2 is the host loopback as seen from an emulator. On a real
     * handset the useful setup is {@code adb reverse tcp:8000 tcp:8000},
     * which makes the phone's own 127.0.0.1:8000 the developer's laptop --
     * see mobile/README.md.
     *
     * <p>If nothing answers here, that is not an error. The app falls back
     * to the seed bundle shipped in assets and then to whatever it has in
     * localStorage, and web/offline.js labels the age of what it shows.
     */
    private static final String API_BASE = "http://127.0.0.1:8000";

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        // web/offline.js stores the whole advisory here. Without this the
        // app runs and quietly cannot remember anything between launches.
        settings.setDomStorageEnabled(true);
        // The 3D ocean view is WebGL inside a canvas.
        settings.setMediaPlaybackRequiresUserGesture(false);
        // The page is already responsive and was laid out for a 412px
        // phone; letting WebView apply its own desktop viewport heuristics
        // would undo that.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });

        // A SERVICE WORKER'S OWN FETCHES DO NOT GO THROUGH WebViewClient.
        //
        // Without this block, registration failed on the device with
        // "An unknown error occurred when fetching the script" -- because
        // the request for sw.js is issued by the service worker machinery,
        // which consults ServiceWorkerController, not the WebViewClient
        // above. The asset loader has to be wired into BOTH or the shell
        // loads perfectly and the worker never installs.
        //
        // ORCA does not depend on this: the shell is inside the APK, so
        // the app is offline-capable with or without a worker. What the
        // worker adds here is runtime caching of GET /bundle, so an
        // advisory fetched in harbour survives even if localStorage is
        // cleared. Fixing it is worth doing; relying on it is not, since
        // WebView is documented to drop registrations across restarts.
        ServiceWorkerController swController = ServiceWorkerController.getInstance();
        swController.getServiceWorkerWebSettings().setAllowContentAccess(true);
        swController.setServiceWorkerClient(new ServiceWorkerClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });

        // Console output is forwarded to logcat rather than dropped.
        // web/offline.js and web/index.html warn (never swallow) on every
        // fallback -- CLAUDE.md rule 2 -- and those warnings are how you
        // find out on a real device whether the advisory being shown came
        // from the network or from the cache.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                android.util.Log.i("ORCA", msg.message() + " @" + msg.lineNumber());
                return true;
            }
        });

        applySystemBarInsets(webView);

        if (savedInstanceState == null) {
            webView.loadUrl(
                    "https://appassets.androidplatform.net/assets/index.html"
                            + "?api=" + android.net.Uri.encode(API_BASE)
                            // Tells web/index.html to look for the seed
                            // advisory shipped alongside it in assets, so
                            // the very first launch on a phone that has
                            // never had signal still answers.
                            + "&seed=./bundle.json");
        }
    }

    /**
     * Keeps the page clear of the status bar and the navigation bar.
     *
     * <p>targetSdk 35 makes an app edge-to-edge by default on Android 15,
     * so without this the WebView draws UNDER both system bars. Measured
     * on an OPPO CPH2591: the ORCA wordmark sat behind the clock and the
     * battery icon, and "Play a Tamil example" sat behind the navigation
     * bar. That is not cosmetic on this product -- a control a fisherman
     * cannot reliably tap is a control that is not there.
     *
     * <p>Padding the WebView rather than disabling edge-to-edge keeps the
     * theme's dark bars (which match the app's own header) while giving
     * the page its full usable area.
     */
    private void applySystemBarInsets(final View view) {
        // fitsSystemWindows lets the PLATFORM turn the system-bar insets
        // into padding on this view. A hand-rolled
        // setOnApplyWindowInsetsListener was tried first and never fired
        // on the device -- the wordmark stayed behind the clock. The
        // built-in mechanism is both shorter and the one Android actually
        // guarantees to run (CLAUDE.md rule 7).
        view.setFitsSystemWindows(true);
        view.setBackgroundColor(Color.parseColor("#071c26"));
        view.requestApplyInsets();
    }

    /** Back navigates the page before it leaves the app. */
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        webView.restoreState(savedInstanceState);
    }
}
