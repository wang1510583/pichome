package top.lempet.pichome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SafeBrowsingResponse;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://pichome.lempet.top:5575/";
    private static final String APP_ACCESS_TAG = BuildConfig.PICHOME_APP_ACCESS_TAG;
    private static final int FILE_CHOOSER_REQUEST = 1201;
    private static final int SYSTEM_BAR_COLOR = Color.rgb(48, 49, 53);
    private static final String PICHOME_HOST = "pichome.lempet.top";

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progressBar;
    private View statusBarScrim;
    private View navigationBarScrim;
    private ValueCallback<Uri[]> pendingFileCallback;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int requestedOrientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean customVideoFullscreen;
    private int requestedOrientationBeforeCustomFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private Object backInvokedCallback;
    private MediaDiskCache mediaDiskCache;
    private AlertDialog cacheDialog;
    private String appUserAgent = "PichomeAndroid";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        buildInterface();
        configureWebView();

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(HOME_URL);
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setStatusBarColor(SYSTEM_BAR_COLOR);
        window.setNavigationBarColor(SYSTEM_BAR_COLOR);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.setNavigationBarDividerColor(SYSTEM_BAR_COLOR);
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true);
        }
    }

    /**
     * Keep the status bar visible, but make the navigation bar transient. Android only lets an
     * app reserve both complete physical side edges while the navigation bar is hidden in sticky
     * immersive mode; otherwise the OS clamps each side's exclusion height.
     */
    private void applyPhysicalEdgeGestureMode() {
        Window window = getWindow();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.statusBars());
                    controller.hide(WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
            } else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }
        } catch (RuntimeException ignored) {
            // Some vendor Android builds reject system-bar changes before the window is attached.
            // The next focus/resume callback retries after attachment; never crash the whole app.
        }
    }

    private void buildInterface() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(248, 249, 250));
        root.setClipToPadding(false);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(248, 249, 250));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
        );
        progressParams.gravity = Gravity.TOP;
        root.addView(progressBar, progressParams);

        statusBarScrim = new View(this);
        statusBarScrim.setBackgroundColor(SYSTEM_BAR_COLOR);
        FrameLayout.LayoutParams statusBarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
        );
        statusBarParams.gravity = Gravity.TOP;
        root.addView(statusBarScrim, statusBarParams);

        navigationBarScrim = new View(this);
        navigationBarScrim.setBackgroundColor(SYSTEM_BAR_COLOR);
        FrameLayout.LayoutParams navigationBarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
        );
        navigationBarParams.gravity = Gravity.BOTTOM;
        root.addView(navigationBarScrim, navigationBarParams);
        setContentView(root);
        root.post(this::applyPhysicalEdgeGestureMode);
        installSystemBarInsets();
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            installSystemGestureExclusion();
        }
    }

    @android.annotation.TargetApi(29)
    private void installSystemGestureExclusion() {
        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) ->
                updateSystemGestureExclusion()
        );
        root.post(this::updateSystemGestureExclusion);
    }

    @android.annotation.TargetApi(29)
    private void updateSystemGestureExclusion() {
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return;
        int edgeWidth = dp(40);
        int exclusionTop = 0;
        int exclusionBottom = height;
        Rect leftEdge = new Rect(0, exclusionTop, edgeWidth, exclusionBottom);
        Rect rightEdge = new Rect(width - edgeWidth, exclusionTop, width, exclusionBottom);
        root.setSystemGestureExclusionRects(Arrays.asList(leftEdge, rightEdge));
    }

    private void installSystemBackHandler() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerSystemBackHandler();
        }
    }

    @android.annotation.TargetApi(33)
    private void registerSystemBackHandler() {
        android.window.OnBackInvokedCallback callback = this::handleBackAction;
        backInvokedCallback = callback;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
        );
    }

    private void installSystemBarInsets() {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (fullscreenView != null || customVideoFullscreen) {
                view.setPadding(0, 0, 0, 0);
                return insets;
            }
            int left;
            int top;
            int right;
            int bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safeInsets = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                left = safeInsets.left;
                top = safeInsets.top;
                right = safeInsets.right;
                bottom = safeInsets.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            FrameLayout.LayoutParams statusBarParams =
                    (FrameLayout.LayoutParams) statusBarScrim.getLayoutParams();
            if (statusBarParams.height != top) {
                statusBarParams.height = top;
                statusBarScrim.setLayoutParams(statusBarParams);
            }
            statusBarScrim.setTranslationY(-top);
            FrameLayout.LayoutParams navigationBarParams =
                    (FrameLayout.LayoutParams) navigationBarScrim.getLayoutParams();
            if (navigationBarParams.height != bottom) {
                navigationBarParams.height = bottom;
                navigationBarScrim.setLayoutParams(navigationBarParams);
            }
            navigationBarScrim.setTranslationY(bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // Cached media is served by an app-private loopback server. No remote HTTP content is
        // introduced; allowing mixed content here lets the HTTPS page play 127.0.0.1 media.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        appUserAgent = settings.getUserAgentString()
                + " PichomeAndroid/" + BuildConfig.VERSION_NAME;
        if (!APP_ACCESS_TAG.isEmpty()) {
            appUserAgent += " " + APP_ACCESS_TAG;
        }
        settings.setUserAgentString(appUserAgent);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        try {
            mediaDiskCache = new MediaDiskCache(getApplicationContext(), PICHOME_HOST);
        } catch (Throwable error) {
            Log.e("PichomeCache", "Cache initialization failed; using WebView cache only", error);
            mediaDiskCache = null;
        }
        // Keep the settings entry available even if cache initialization must degrade gracefully.
        webView.addJavascriptInterface(new CacheJavascriptBridge(), "PichomeCache");

        webView.setWebViewClient(new PichomeWebViewClient());
        webView.setWebChromeClient(new PichomeWebChromeClient());
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                Toast.makeText(this, "当前版本未启用下载功能", Toast.LENGTH_SHORT).show()
        );
    }

    private final class PichomeWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view,
                WebResourceRequest request
        ) {
            try {
                WebResourceResponse cached = mediaDiskCache == null
                        ? null
                        : mediaDiskCache.intercept(request, view.getSettings().getUserAgentString());
                return cached != null ? cached : super.shouldInterceptRequest(view, request);
            } catch (Throwable error) {
                Log.e("PichomeCache", "Cache request failed; falling back to WebView", error);
                return super.shouldInterceptRequest(view, request);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && host != null && (host.equals("lempet.top") || host.endsWith(".lempet.top"))) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException error) {
                Toast.makeText(MainActivity.this, "无法打开此链接", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            progressBar.setVisibility(View.GONE);
            injectAppCustomizations(view);
            view.postDelayed(() -> injectAppCustomizations(view), 800);
            view.postDelayed(() -> injectAppCustomizations(view), 2_000);
        }

        @Override
        public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceError error
        ) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "页面加载失败，请检查网络后重试", Toast.LENGTH_LONG).show();
            }
        }

        @Override
        @android.annotation.TargetApi(27)
        public void onSafeBrowsingHit(
                WebView view,
                WebResourceRequest request,
                int threatType,
                SafeBrowsingResponse callback
        ) {
            callback.backToSafety(true);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Toast.makeText(MainActivity.this, "页面进程已恢复", Toast.LENGTH_SHORT).show();
            recreate();
            return true;
        }
    }

    private final class PichomeWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onShowFileChooser(
                WebView view,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams
        ) {
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(null);
            }
            pendingFileCallback = filePathCallback;
            Intent chooserIntent;
            try {
                chooserIntent = fileChooserParams.createIntent();
            } catch (RuntimeException error) {
                chooserIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                chooserIntent.addCategory(Intent.CATEGORY_OPENABLE);
                chooserIntent.setType("*/*");
                chooserIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST);
                return true;
            } catch (ActivityNotFoundException error) {
                pendingFileCallback = null;
                Toast.makeText(MainActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden();
                return;
            }
            if (customVideoFullscreen) {
                setCustomVideoFullscreen(false);
            }
            fullscreenView = view;
            fullscreenCallback = callback;
            requestedOrientationBeforeFullscreen = getRequestedOrientation();
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            webView.setVisibility(View.GONE);
            root.setPadding(0, 0, 0, 0);
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                getWindow().getInsetsController().hide(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars()
                );
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }
        }

        @Override
        public void onHideCustomView() {
            hideFullscreenVideo();
        }
    }

    /**
     * APK-only presentation rules. The website itself is not modified.
     * A MutationObserver keeps download actions hidden after Vue rerenders menus.
     */
    private void injectAppCustomizations(WebView view) {
        String script = new String("(function(){")
                + "document.documentElement.classList.add('pichome-android-app');"
                + "var mediaCss='"
                + ".Details-Iframe{inset:0!important;width:100%!important;height:100%!important;padding:12px 0 126px!important;align-items:center!important;justify-content:center!important;background:rgba(12,13,16,.62)!important;backdrop-filter:blur(8px) saturate(120%);-webkit-backdrop-filter:blur(8px) saturate(120%);}"
                + ".Details-Iframe.pichome-has-frame-controls{padding-bottom:calc(210px + env(safe-area-inset-bottom))!important;}"
                + ".Details-Iframe .Details-Iframe-wrapper{position:relative!important;flex:0 0 auto!important;width:var(--pichome-dialog-w,100%)!important;height:var(--pichome-dialog-h,min(70dvh,620px))!important;max-width:100%!important;max-height:calc(100% - 138px)!important;border-radius:0!important;overflow:hidden!important;box-shadow:0 18px 54px rgba(0,0,0,.42)!important;transition:width .18s ease-out,height .18s ease-out!important;}"
                + ".Details-Iframe.pichome-has-frame-controls .Details-Iframe-wrapper{max-height:calc(100% - 222px - env(safe-area-inset-bottom))!important;}"
                + ".Details-Iframe .Details-Iframe-wrapper>iframe{position:absolute!important;inset:0!important;display:block!important;width:100%!important;height:100%!important;border-radius:0!important;opacity:1!important;transition:opacity .18s ease-out!important;}"
                + ".pichome-detail-nav{position:fixed;left:50%;bottom:max(12px,env(safe-area-inset-bottom));z-index:10002;display:flex;gap:18px;transform:translateX(-50%);pointer-events:auto;}"
                + ".pichome-detail-nav[hidden]{display:none!important;}"
                + ".pichome-detail-nav button{display:flex;align-items:center;justify-content:center;width:144px;max-width:calc((100vw - 42px)/2);height:96px;margin:0;padding:0;border:1px solid rgba(255,255,255,.22);border-radius:22px;background:rgba(38,39,43,.88);color:#fff;font:600 32px/1 system-ui,sans-serif;box-shadow:0 8px 24px rgba(0,0,0,.3);backdrop-filter:blur(16px) saturate(150%);-webkit-backdrop-filter:blur(16px) saturate(150%);touch-action:none;-webkit-tap-highlight-color:transparent;}"
                + ".pichome-detail-nav button:active:not(:disabled){transform:scale(.94);background:rgba(64,65,70,.96);}"
                + ".pichome-detail-nav button:disabled{opacity:.3;}"
                + ".pichome-frame-scrub-pad{position:fixed;left:42px;right:42px;bottom:calc(max(12px,env(safe-area-inset-bottom)) + 108px);z-index:10003;height:72px;touch-action:none;-webkit-tap-highlight-color:transparent;}"
                + ".pichome-frame-scrub-pad[hidden]{display:none!important;}"
                + ".pichome-frame-scrub-pad.pichome-frame-scrubbing{cursor:ew-resize;}"
                + ".pichome-frame-scrub-indicator{position:absolute;left:50%;top:50%;z-index:1;display:flex;align-items:center;justify-content:center;min-width:126px;height:38px;padding:0 14px;border:1px solid rgba(255,255,255,.2);border-radius:19px;transform:translate(-50%,-50%);background:rgba(28,29,33,.86);color:#fff;font:600 14px/1 system-ui,sans-serif;box-shadow:0 6px 18px rgba(0,0,0,.3);pointer-events:none;white-space:nowrap;backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);}"
                + ".pichome-frame-scrub-indicator[hidden]{display:none!important;}"
                + ".pichome-video-timeline{position:fixed;left:0;right:0;bottom:calc(max(12px,env(safe-area-inset-bottom)) + 188px);z-index:10004;display:flex;align-items:center;gap:10px;height:52px;padding:0 12px;border-top:1px solid rgba(255,255,255,.16);border-bottom:1px solid rgba(255,255,255,.16);border-radius:0;box-sizing:border-box;background:rgba(38,39,43,.86);box-shadow:0 8px 24px rgba(0,0,0,.28);backdrop-filter:blur(16px) saturate(150%);-webkit-backdrop-filter:blur(16px) saturate(150%);pointer-events:auto;}"
                + ".pichome-video-timeline[hidden]{display:none!important;}"
                + ".pichome-video-timeline time{flex:0 0 auto;min-width:43px;color:rgba(255,255,255,.86);font:600 12px/1 system-ui,sans-serif;font-variant-numeric:tabular-nums;letter-spacing:.01em;}"
                + ".pichome-video-timeline .pichome-video-time-current{text-align:right;color:#fff;}"
                + ".pichome-video-timeline input{--pichome-progress:0%;display:block;flex:1 1 auto;min-width:0;height:32px;margin:0;padding:0;-webkit-appearance:none;appearance:none;background:transparent;touch-action:none;-webkit-tap-highlight-color:transparent;}"
                + ".pichome-video-timeline input::-webkit-slider-runnable-track{height:4px;border:0;border-radius:2px;background:linear-gradient(to right,#fff 0,#fff var(--pichome-progress),rgba(255,255,255,.28) var(--pichome-progress),rgba(255,255,255,.28) 100%);}"
                + ".pichome-video-timeline input::-webkit-slider-thumb{width:20px;height:20px;margin-top:-8px;border:2px solid rgba(255,255,255,.9);border-radius:50%;-webkit-appearance:none;appearance:none;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.38);transition:transform .1s ease-out;}"
                + ".pichome-video-timeline input:active::-webkit-slider-thumb{transform:scale(1.14);}"
                + ".pichome-video-timeline input:disabled{opacity:.35;}"
                + ".pichome-video-commandbar{position:fixed;left:0;right:0;bottom:calc(max(12px,env(safe-area-inset-bottom)) + 248px);z-index:10005;display:flex;align-items:center;justify-content:center;gap:8px;height:52px;padding:4px 12px;box-sizing:border-box;pointer-events:none;}"
                + ".pichome-video-commandbar[hidden]{display:none!important;}"
                + ".pichome-video-commandbar button{display:flex;flex:1 1 0;align-items:center;justify-content:center;max-width:72px;min-width:0;height:44px;margin:0;padding:0 8px;border:1px solid rgba(255,255,255,.2);border-radius:14px;background:rgba(38,39,43,.88);color:#fff;font:600 14px/1 system-ui,sans-serif;font-variant-numeric:tabular-nums;box-shadow:0 6px 18px rgba(0,0,0,.28);backdrop-filter:blur(14px) saturate(150%);-webkit-backdrop-filter:blur(14px) saturate(150%);touch-action:manipulation;-webkit-tap-highlight-color:transparent;pointer-events:auto;}"
                + ".pichome-video-commandbar button:active:not(:disabled){transform:scale(.94);background:rgba(64,65,70,.96);}"
                + ".pichome-video-commandbar button:disabled{opacity:.35;}"
                + ".pichome-video-commandbar button[data-pichome-action=play]{font-size:0;}"
                + ".pichome-video-commandbar button[data-pichome-action=play]::before{content:\"\";display:block;width:22px;height:22px;background:currentColor;clip-path:polygon(23% 10%,23% 90%,88% 50%);}"
                + ".pichome-video-commandbar button[data-pichome-action=play][aria-label=\"暂停视频\"]::before{width:20px;height:20px;background:linear-gradient(to right,currentColor 0 36%,transparent 36% 64%,currentColor 64% 100%);clip-path:none;}"
                + "html.pichome-custom-video-fullscreen-root,html.pichome-custom-video-fullscreen-root body{overflow:hidden!important;overscroll-behavior:none!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen{position:fixed!important;inset:0!important;z-index:2147482500!important;width:100vw!important;height:100dvh!important;padding:0!important;align-items:stretch!important;justify-content:stretch!important;background:#000!important;backdrop-filter:none!important;-webkit-backdrop-filter:none!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .Details-Iframe-wrapper{position:absolute!important;inset:0!important;display:block!important;width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;border-radius:0!important;box-shadow:none!important;transition:none!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .Details-Iframe-wrapper>iframe{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;border-radius:0!important;transition:none!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-frame-scrub-pad{left:0!important;right:0!important;top:0!important;bottom:72px!important;height:auto!important;z-index:10003!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-frame-scrub-indicator{display:none!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-timeline{left:0!important;right:0!important;bottom:0!important;z-index:10006!important;height:24px!important;padding:0 max(6px,env(safe-area-inset-right)) 0 max(6px,env(safe-area-inset-left))!important;gap:5px!important;border-width:0!important;border-radius:0!important;opacity:.5!important;background:linear-gradient(to top,rgba(10,10,12,.9),rgba(25,25,28,.72))!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-timeline time{min-width:22px!important;font-size:6px!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-timeline input{height:24px!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-timeline input::-webkit-slider-runnable-track{height:2px!important;border-radius:1px!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-timeline input::-webkit-slider-thumb{width:10px!important;height:10px!important;margin-top:-4px!important;border-width:1px!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-commandbar{left:50%!important;right:auto!important;bottom:26px!important;z-index:10007!important;width:92px!important;height:44px!important;padding:0!important;justify-content:center!important;gap:4px!important;transform:translateX(-50%)!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-commandbar button{position:relative!important;flex:0 0 44px!important;width:44px!important;max-width:44px!important;height:44px!important;padding:0!important;border:0!important;border-radius:0!important;opacity:.5!important;background:transparent!important;box-shadow:none!important;backdrop-filter:none!important;-webkit-backdrop-filter:none!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-commandbar button[data-pichome-action=volume],.Details-Iframe.pichome-custom-video-fullscreen .pichome-video-commandbar button[data-pichome-action=speed]{display:none!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-commandbar button::after{content:\"\";position:absolute;left:50%;top:50%;z-index:0;width:30px;height:20px;border:1px solid rgba(255,255,255,.2);border-radius:6px;transform:translate(-50%,-50%);box-sizing:border-box;background:rgba(28,29,33,.9);box-shadow:0 3px 9px rgba(0,0,0,.28);}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-commandbar button[data-pichome-action=play]::before{position:relative;z-index:1;width:11px;height:11px;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-commandbar button[data-pichome-action=play][aria-label=\"暂停视频\"]::before{width:10px;height:10px;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-commandbar button[data-pichome-action=fullscreen]{font-size:0!important;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-video-commandbar button[data-pichome-action=fullscreen]::before{content:\"退出\";position:relative;z-index:1;font:600 7px/1 system-ui,sans-serif;}"
                + ".Details-Iframe.pichome-custom-video-fullscreen .pichome-detail-nav{display:none!important;}"
                + "html.pichome-custom-video-fullscreen-doc .detail-popup{--apk-header-h:0px;--apk-footer-h:0px;--apk-album-h:0px;padding:0!important;background:#000!important;}"
                + "html.pichome-custom-video-fullscreen-doc .detail-popup .header-top,html.pichome-custom-video-fullscreen-doc .detail-popup .footer-tabbar,html.pichome-custom-video-fullscreen-doc .detail-popup .album-imglist{display:none!important;}"
                + "@media(prefers-reduced-motion:reduce){.Details-Iframe .Details-Iframe-wrapper,.Details-Iframe .Details-Iframe-wrapper>iframe{transition:none!important;}}"
                + "html,body,#dzzoffice{min-height:100%!important;}"
                + ".detail-popup{--apk-header-h:50px;--apk-footer-h:60px;--apk-album-h:0px;height:100dvh!important;max-height:100%!important;padding:var(--apk-header-h) 0 calc(var(--apk-footer-h) + var(--apk-album-h))!important;box-sizing:border-box!important;}"
                + ".detail-popup.NotFooter{--apk-footer-h:0px;}"
                + ".detail-popup.is-padding{--apk-album-h:60px;}"
                + ".detail-popup.full{--apk-header-h:0px;--apk-footer-h:0px;--apk-album-h:0px;padding:0!important;}"
                + ".detail-popup .detail-popup-translate{height:100%!important;min-height:0!important;padding-top:0!important;}"
                + ".detail-popup .detail-popup-content,.detail-popup .detail-popup-img,.detail-popup .img-box,.detail-popup .pinch-zoom-container{height:100%!important;min-height:0!important;}"
                + ".detail-popup .image-viewer__img{width:auto!important;height:auto!important;max-width:100%!important;max-height:100%!important;object-fit:contain!important;}"
                + ".detail-popup .video-box{display:flex!important;align-items:center!important;justify-content:center!important;width:100%!important;height:100%!important;min-height:0!important;max-height:100%!important;}"
                + ".detail-popup .video-box>iframe,.detail-popup .video-box video{display:block!important;width:100%!important;height:100%!important;max-width:100%!important;max-height:100%!important;object-fit:contain!important;}"
                + "html.pichome-frame-stepping-doc .xgplayer-start,html.pichome-frame-stepping-doc .xgplayer-prompt,html.pichome-frame-stepping-doc .xgplayer-replay{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;}"
                + "html.pichome-frame-stepping-doc .xgplayer-controls,html.pichome-frame-stepping-doc .xgplayer-controls-list,html.pichome-frame-stepping-doc .xgplayer-play,html.pichome-frame-stepping-doc .xgplayer-play-img,html.pichome-frame-stepping-doc .xgplayer-progress,html.pichome-frame-stepping-doc .xgplayer-time,html.pichome-frame-stepping-doc .xgplayer-volume,html.pichome-frame-stepping-doc .xgplayer-playbackrate{visibility:hidden!important;opacity:0!important;pointer-events:none!important;}"
                + "html.pichome-frame-stepping-doc video::-webkit-media-controls,html.pichome-frame-stepping-doc video::-webkit-media-controls-enclosure{display:none!important;visibility:hidden!important;opacity:0!important;}"
                + "html.pichome-custom-video-controls-doc .xgplayer-start,html.pichome-custom-video-controls-doc .xgplayer-prompt,html.pichome-custom-video-controls-doc .xgplayer-replay,html.pichome-custom-video-controls-doc .xgplayer-controls,html.pichome-custom-video-controls-doc .xgplayer-controls-list{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;}"
                + "html.pichome-custom-video-controls-doc video::-webkit-media-controls,html.pichome-custom-video-controls-doc video::-webkit-media-controls-enclosure{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;}"
                + "@media(max-width:768px){html.pichome-android-app .mobile-sidebar-toggle{display:none!important;}}"
                + "';"
                + "function nativeMediaUrlIdentity(raw,doc){try{var parsed=new URL(raw,doc&&doc.baseURI||location.href),rid=parsed.searchParams.get('pichome_rid');if(rid&&rid.length<=256)return 'rid:'+rid+':video';}catch(ignore){}return '';}"
                + "function nativeMediaIdentity(media,doc){try{var rid=media.getAttribute&&media.getAttribute('data-rid'),owner=!rid&&media.closest&&media.closest('[data-rid]');if(!rid&&owner)rid=owner.getAttribute('data-rid');if(!rid&&media.tagName==='IMG'){var max=media.parentElement&&media.parentElement.querySelector('.image-viewer__img.max[data-rid]');rid=max&&max.getAttribute('data-rid');}if(rid)return 'rid:'+rid+':'+media.tagName.toLowerCase();var actual=media.currentSrc||media.getAttribute&&media.getAttribute('src')||'';var urlIdentity=nativeMediaUrlIdentity(actual,doc);if(urlIdentity)return urlIdentity;var win=doc&&doc.defaultView;while(win&&win.frameElement){var frame=win.frameElement,ownerWin=frame.ownerDocument&&frame.ownerDocument.defaultView,detailFrame=ownerWin&&ownerWin.frameElement;try{if(detailFrame){var vm=detailVm(detailFrame),data=vm&&vm.showdata&&vm.showdata[vm.showdataindex]&&vm.showdata[vm.showdataindex].data;if(data&&data.rid)return 'rid:'+data.rid+':video';}}catch(ignore){}var raw=frame.getAttribute('src')||frame.src||'';try{var parsed=new URL(raw,frame.ownerDocument.baseURI),stableRid=parsed.searchParams.get('pichome_rid'),mod=parsed.searchParams.get('mod'),path=parsed.searchParams.get('path');if(stableRid)return 'rid:'+stableRid+':video';if(mod==='xgplayer'&&path)return 'xgplayer:'+path;}catch(ignore){}win=ownerWin;}return '';}catch(ignore){return '';}}"
                + "function installMediaSourceInterceptor(doc){try{var win=doc&&doc.defaultView,bridge=win&&win.PichomeCache||window.PichomeCache,proto=win&&win.HTMLMediaElement&&win.HTMLMediaElement.prototype;if(!bridge||!proto||win.__pichomeMediaSourceInterceptor)return;var descriptor=Object.getOwnPropertyDescriptor(proto,'src');if(!descriptor||!descriptor.get||!descriptor.set||descriptor.configurable===false)return;Object.defineProperty(proto,'src',{configurable:descriptor.configurable,enumerable:descriptor.enumerable,get:descriptor.get,set:function(value){var raw=value==null?'':String(value),original='';try{original=new URL(raw,doc.baseURI).href;}catch(ignore){original=raw;}if(!original||original.indexOf('http://127.0.0.1:')===0||original.indexOf('https://pichome.lempet.top:5575/')!==0)return descriptor.set.call(this,value);var identity=nativeMediaUrlIdentity(original,doc);if(!identity)return descriptor.set.call(this,value);var requestKey=identity+'|'+original,local='';this.dataset.pichomeOriginalMediaUrl=original;try{local=bridge.getCachedMediaUrl(original,identity)||'';}catch(ignore){}if(local){this.dataset.pichomeUsingLocalCache='1';this.dataset.pichomeCacheReady='1';return descriptor.set.call(this,local);}descriptor.set.call(this,value);if(this.dataset.pichomeCacheRequested!==requestKey){this.dataset.pichomeCacheRequested=requestKey;try{bridge.cacheMedia(original,identity);}catch(ignore){}}}});win.__pichomeMediaSourceInterceptor=true;}catch(ignore){}}"
                + "function installNativeMediaCache(doc){try{"
                + "if(!doc||!doc.querySelectorAll||!isCurrentDetailDocument(doc))return;var win=doc.defaultView,bridge=win&&win.PichomeCache||window.PichomeCache;if(!bridge)return;"
                + "doc.querySelectorAll('video,.detail-popup-content.center .image-viewer__img.max,.detail-popup .image-viewer__img.max').forEach(function(media){try{"
                + "var actual='';if(media.tagName==='VIDEO'){actual=media.currentSrc||media.getAttribute('src')||'';if(!actual){var source=media.querySelector('source[src]');actual=source&&(source.currentSrc||source.src||source.getAttribute('src'))||'';}}else actual=media.currentSrc||media.src||media.getAttribute('src')||'';"
                + "var isLocal=actual.indexOf('http://127.0.0.1:')===0,original=media.dataset.pichomeOriginalMediaUrl||'';if(actual&&!isLocal&&actual!==original){try{original=new URL(actual,doc.baseURI).href;}catch(ignore){original=actual;}media.dataset.pichomeOriginalMediaUrl=original;delete media.dataset.pichomeCacheRequested;}"
                + "if(!original||original.indexOf('https://pichome.lempet.top:5575/')!==0)return;var identity=nativeMediaIdentity(media,doc),requestKey=identity+'|'+original,local='';try{local=bridge.getCachedMediaUrl(original,identity)||'';}catch(ignore){}"
                + "if(local){if(actual===local)return;if(media.dataset.pichomeCacheRequested===requestKey){media.dataset.pichomeCacheReady='1';return;}media.dataset.pichomeUsingLocalCache='1';if(media.tagName==='VIDEO'){media.src=local;media.load();}else media.src=local;return;}"
                + "if(media.dataset.pichomeCacheRequested!==requestKey){media.dataset.pichomeCacheRequested=requestKey;try{bridge.cacheMedia(original,identity);}catch(ignore){}}"
                + "}catch(ignore){}});"
                + "}catch(ignore){}}"
                + "function installMediaCss(doc){"
                + "if(!doc)return;installMediaSourceInterceptor(doc);tryAutoplay(doc);installNativeMediaCache(doc);if(doc.getElementById('pichome-android-media-css'))return;"
                + "var style=doc.createElement('style');style.id='pichome-android-media-css';style.textContent=mediaCss;"
                + "(doc.head||doc.documentElement).appendChild(style);"
                + "try{var win=doc.defaultView;if(win&&doc!==document&&!win.__pichomeAndroidDocObserver){"
                + "win.__pichomeAndroidDocObserver=new win.MutationObserver(function(records){var structural=records.some(function(record){return record.type==='childList'&&record.addedNodes&&record.addedNodes.length;});if(structural){tryAutoplay(doc);installFrameCss(doc);hideDownloads(doc);try{fitDetailDialog(win.frameElement);}catch(ignore){}}clearTimeout(win.__pichomeAndroidCacheScanTimer);win.__pichomeAndroidCacheScanTimer=setTimeout(function(){installNativeMediaCache(doc);},160);});"
                + "win.__pichomeAndroidDocObserver.observe(doc.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});}}catch(ignore){}"
                + "}"
                + "function installFrameCss(root){"
                + "var scope=root&&root.querySelectorAll?root:document;"
                + "scope.querySelectorAll('iframe').forEach(function(frame){"
                + "lockDetailToFramePath(frame);function apply(){try{lockDetailToFramePath(frame);tryAutoplay(frame.contentDocument);installNativeMediaCache(frame.contentDocument);installMediaCss(frame.contentDocument);installFrameCss(frame.contentDocument);hideDownloads(frame.contentDocument);installDetailFrame(frame);}catch(ignore){}}"
                + "if(!frame.dataset.pichomeAndroidAdaptive){frame.dataset.pichomeAndroidAdaptive='1';frame.addEventListener('load',apply);}"
                + "apply();"
                + "});"
                + "}"
                + "function detailVm(frame){try{var doc=frame.contentDocument,root=doc&&doc.querySelector('#dzzoffice');"
                + "var app=root&&root.__vue_app__;if(app&&app._instance&&app._instance.proxy)return app._instance.proxy;"
                + "var popup=doc&&doc.querySelector('#DetailPopup'),component=popup&&popup.__vueParentComponent;return component&&(component.proxy||component.ctx)||null;}catch(ignore){return null;}}"
                + "function lockDetailToFramePath(frame){try{if(!frame.closest||!frame.closest('.Details-Iframe-wrapper'))return;var source=frame.getAttribute('src')||'',marker='#path=',position=source.indexOf(marker);if(position<0)return;var path=source.slice(position+marker.length).split('&')[0];if(!path)return;try{path=decodeURIComponent(path);}catch(ignore){}sessionStorage.setItem('imgs',path);sessionStorage.setItem('selectindex','0');sessionStorage.setItem('detailpath',path);frame.contentWindow.sessionStorage.setItem('imgs',path);frame.contentWindow.sessionStorage.setItem('selectindex','0');frame.contentWindow.sessionStorage.setItem('detailpath',path);}catch(ignore){}}"
                + "function isCurrentDetailDocument(doc){try{var win=doc&&doc.defaultView;while(win&&win.frameElement){var host=win.frameElement,content=host.closest&&host.closest('.detail-popup-content');if(content)return content.classList.contains('center');win=host.ownerDocument&&host.ownerDocument.defaultView;}return true;}catch(ignore){return true;}}"
                + "function tryAutoplay(doc){"
                + "if(!doc||!doc.documentElement)return;if(!isCurrentDetailDocument(doc)){doc.querySelectorAll('video').forEach(function(video){try{video.pause();video.autoplay=false;}catch(ignore){}});return;}var win=doc.defaultView;if(!win||win.__pichomeAndroidAutoplayScheduled)return;"
                + "win.__pichomeAndroidAutoplayScheduled=true;function start(){if(win.__pichomeAndroidAutoplayPlaying)return;"
                + "try{var player=win.player;if(player&&typeof player.play==='function'){var result=player.play();if(result&&result.catch)result.catch(function(){});}}catch(ignore){}"
                + "doc.querySelectorAll('video').forEach(function(video){if(!video.dataset.pichomeAndroidAutoplay){video.dataset.pichomeAndroidAutoplay='1';video.autoplay=true;video.playsInline=true;"
                + "video.addEventListener('playing',function(){win.__pichomeAndroidAutoplayPlaying=true;},{once:true});video.addEventListener('canplay',start,{once:true});}"
                + "if(!win.__pichomeAndroidAutoplayPlaying&&video.paused){try{var promise=video.play();if(promise&&promise.catch)promise.catch(function(){});}catch(ignore){}}});}"
                + "[0,80,240,600,1200].forEach(function(delay){setTimeout(start,delay);});"
                + "}"
                + "function findDetailVideo(doc){try{if(!doc||!doc.querySelectorAll)return null;var videos=doc.querySelectorAll('video'),fallback=videos.length?videos[0]:null;for(var i=0;i<videos.length;i++){if(videos[i].readyState>=1)return videos[i];}var frames=doc.querySelectorAll('iframe');for(var j=0;j<frames.length;j++){var nested=findDetailVideo(frames[j].contentDocument);if(nested)return nested;}return fallback;}catch(ignore){return null;}}"
                + "function currentDetailVideo(frame){try{var doc=frame&&frame.contentDocument;if(!doc)return null;var scope=doc.querySelector('.detail-popup-content.center')||doc.querySelector('#DetailPopup')||doc;return findDetailVideo(scope);}catch(ignore){return null;}}"
                + "function parseFrameRate(value){if(value===undefined||value===null)return 0;var text=String(value),parts=text.split('/'),rate=parts.length===2?parseFloat(parts[0])/parseFloat(parts[1]):parseFloat(text);return isFinite(rate)&&rate>=1&&rate<=240?rate:0;}"
                + "function resetFramePreviewCache(video){var cache=video&&video.__pichomeFrameCache;if(cache&&cache.overlay&&cache.overlay.parentNode)try{cache.overlay.parentNode.removeChild(cache.overlay);}catch(ignore){}if(video){video.__pichomeFrameCache=null;cancelFramePreviewCommit(video,true);}}"
                + "function framePreviewCache(video){if(!video||video.videoWidth<1||video.videoHeight<1)return null;var width=video.videoWidth,height=video.videoHeight,cache=video.__pichomeFrameCache;if(cache&&cache.disabled)return null;if(cache&&cache.sourceWidth===width&&cache.sourceHeight===height)return cache;if(cache&&cache.overlay&&cache.overlay.parentNode)try{cache.overlay.parentNode.removeChild(cache.overlay);}catch(ignore){}var scale=Math.min(1,192/Math.max(width,height)),cellWidth=Math.max(2,Math.round(width*scale)),cellHeight=Math.max(2,Math.round(height*scale)),canvas=video.ownerDocument.createElement('canvas');canvas.width=cellWidth*12;canvas.height=cellHeight*8;var context=null;try{context=canvas.getContext('2d',{alpha:false});}catch(ignore){}if(!context)return null;cache={sourceWidth:width,sourceHeight:height,cellWidth:cellWidth,cellHeight:cellHeight,columns:12,capacity:96,next:0,entries:[],lastTime:-1,canvas:canvas,context:context,overlay:null,visible:false,previewTime:NaN,disabled:false};video.__pichomeFrameCache=cache;return cache;}"
                + "function captureFramePreview(video,mediaTime,presentedFrames){if(!isFinite(mediaTime)||video.dataset.pichomeFrameMode==='1'||video.seeking)return;var cache=framePreviewCache(video);if(!cache||cache.disabled||mediaTime<=cache.lastTime+1/480)return;var slot=cache.next,sourceX=(slot%cache.columns)*cache.cellWidth,sourceY=Math.floor(slot/cache.columns)*cache.cellHeight;try{cache.context.drawImage(video,0,0,cache.sourceWidth,cache.sourceHeight,sourceX,sourceY,cache.cellWidth,cache.cellHeight);}catch(ignore){cache.disabled=true;return;}if(cache.entries.length>=cache.capacity)cache.entries.shift();cache.entries.push({time:mediaTime,slot:slot,presentedFrames:isFinite(presentedFrames)?presentedFrames:NaN});cache.next=(slot+1)%cache.capacity;cache.lastTime=mediaTime;}"
                + "function ensureFramePreviewOverlay(video,cache,backingWidth,backingHeight){var overlay=cache.overlay;if(!overlay){overlay=video.ownerDocument.createElement('canvas');overlay.className='pichome-frame-preview-canvas';overlay.style.cssText='position:fixed;z-index:2147483000;display:none;pointer-events:none;background:#000;transform-origin:center center;';(video.ownerDocument.body||video.ownerDocument.documentElement).appendChild(overlay);cache.overlay=overlay;}backingWidth=Math.max(2,Math.round(backingWidth||cache.cellWidth));backingHeight=Math.max(2,Math.round(backingHeight||cache.cellHeight));if(overlay.width!==backingWidth)overlay.width=backingWidth;if(overlay.height!==backingHeight)overlay.height=backingHeight;var rect=video.getBoundingClientRect(),ratio=cache.sourceWidth/cache.sourceHeight,boxRatio=rect.width/Math.max(1,rect.height),left=rect.left,top=rect.top,width=rect.width,height=rect.height;if(boxRatio>ratio){width=height*ratio;left+=(rect.width-width)/2;}else{height=width/ratio;top+=(rect.height-height)/2;}overlay.style.left=left+'px';overlay.style.top=top+'px';overlay.style.width=width+'px';overlay.style.height=height+'px';overlay.style.transform=video.classList.contains('pichome-mirror-preview-target')?'scaleX(-1)':'';return overlay;}"
                + "function cachedAdjacentFrame(video,direction,frame){var cache=video&&video.__pichomeFrameCache;if(!cache||cache.disabled||!cache.entries.length)return null;var frameDuration=parseFloat(video.dataset.pichomeFrameDuration);if(!(isFinite(frameDuration)&&frameDuration>0))frameDuration=1/Math.max(1,detailFrameRate(frame,video));var base=parseFloat(video.dataset.pichomeFrameTarget),current=video.currentTime;if(cache.visible&&isFinite(cache.previewTime))base=cache.previewTime;else if(!isFinite(base)||Math.abs(base-current)>Math.max(.5,frameDuration*4))base=current;var candidate=null,candidateIndex=-1,baseEntry=null,baseIndex=-1,epsilon=1/1000,nearest=Infinity;if(direction<0){for(var i=cache.entries.length-1;i>=0;i--){if(cache.entries[i].time<base-epsilon){candidate=cache.entries[i];candidateIndex=i;break;}}}else{for(var j=0;j<cache.entries.length;j++){if(cache.entries[j].time>base+epsilon){candidate=cache.entries[j];candidateIndex=j;break;}}}if(!candidate||Math.abs(candidate.time-base)>frameDuration*1.7+epsilon)return null;for(var k=0;k<cache.entries.length;k++){var distance=Math.abs(cache.entries[k].time-base);if(distance<nearest){nearest=distance;baseEntry=cache.entries[k];baseIndex=k;}}var tolerance=frameDuration*.75+epsilon;if(baseEntry&&nearest<=tolerance&&isFinite(baseEntry.presentedFrames)&&isFinite(candidate.presentedFrames)){if(direction<0&&(baseIndex!==candidateIndex+1||baseEntry.presentedFrames-candidate.presentedFrames!==1))return null;if(direction>0&&(candidateIndex!==baseIndex+1||candidate.presentedFrames-baseEntry.presentedFrames!==1))return null;}return candidate;}"
                + "function showCachedAdjacentFrame(video,direction,entry){var cache=video&&video.__pichomeFrameCache;entry=entry||cachedAdjacentFrame(video,direction,null);if(!cache||!entry)return false;try{var overlay=ensureFramePreviewOverlay(video,cache),context=overlay.getContext('2d',{alpha:false}),sourceX=(entry.slot%cache.columns)*cache.cellWidth,sourceY=Math.floor(entry.slot/cache.columns)*cache.cellHeight;context.drawImage(cache.canvas,sourceX,sourceY,cache.cellWidth,cache.cellHeight,0,0,cache.cellWidth,cache.cellHeight);overlay.style.display='block';cache.visible=true;cache.previewTime=entry.time;video.dataset.pichomeFrameTarget=String(entry.time);updateFrameTimeline(video,entry.time);video.pause();return true;}catch(ignore){cache.disabled=true;return false;}}"
                + "function hideFramePreview(video){var cache=video&&video.__pichomeFrameCache;if(!cache)return;if(cache.overlay)cache.overlay.style.display='none';cache.visible=false;cache.previewTime=NaN;cache.previewFps=NaN;cache.previewAtlasSecond=NaN;cache.previewAtlasIndex=-1;}"
                + "function cancelFramePreviewCommit(video,hide){if(!video)return;var state=video.__pichomeFrameCommit;if(state){if(state.timer)clearTimeout(state.timer);if(state.seeked)try{video.removeEventListener('seeked',state.seeked);}catch(ignore){}if(state.callbackId!==undefined&&typeof video.cancelVideoFrameCallback==='function')try{video.cancelVideoFrameCallback(state.callbackId);}catch(ignore){}video.__pichomeFrameCommit=null;}delete video.dataset.pichomeFrameCommitting;if(hide)hideFramePreview(video);}"
                + "function framePreviewDuration(video,cache){var fps=parseFloat(cache&&cache.previewFps);if(isFinite(fps)&&fps>=1)return 1/fps;var measured=parseFloat(video&&video.dataset.pichomeFrameDuration);return isFinite(measured)&&measured>0?measured:1/30;}"
                + "function framePreviewSeekTime(video,cache){if(!cache||!isFinite(cache.previewTime))return video.currentTime;return clampedSeekTime(video,cache.previewTime+framePreviewDuration(video,cache)*.5);}"
                + "function commitFramePreview(video){var cache=video&&video.__pichomeFrameCache;if(!cache||!cache.visible||!isFinite(cache.previewTime))return;cancelFramePreviewCommit(video,false);var target=cache.previewTime,frameDuration=framePreviewDuration(video,cache),seekTime=framePreviewSeekTime(video,cache),state={target:target,seekTime:seekTime,frameDuration:frameDuration,callbackId:undefined,seeked:null,timer:0,corrections:0};video.__pichomeFrameCommit=state;video.dataset.pichomeFrameCommitting='1';function matches(mediaTime){return isFinite(mediaTime)&&Math.abs(mediaTime-target)<=frameDuration*.65+.003;}function finish(matched){if(video.__pichomeFrameCommit!==state)return;cancelFramePreviewCommit(video,matched);video.pause();video.dataset.pichomeFrameTarget=String(target);}function complete(now,metadata){if(video.__pichomeFrameCommit!==state)return;var mediaTime=metadata&&metadata.mediaTime;if(matches(mediaTime)){finish(true);return;}if(isFinite(mediaTime)&&state.corrections<1){state.corrections++;seekTo(clampedSeekTime(video,target+frameDuration*(mediaTime>target?0.2:0.8)));return;}finish(false);}function requestPresentedFrame(){if(video.__pichomeFrameCommit!==state)return;if(typeof video.requestVideoFrameCallback==='function')state.callbackId=video.requestVideoFrameCallback(complete);else requestAnimationFrame(function(){finish(true);});}function seekTo(position){if(video.__pichomeFrameCommit!==state)return;if(state.seeked)try{video.removeEventListener('seeked',state.seeked);}catch(ignore){}state.seekTime=position;state.seeked=function(){if(video.__pichomeFrameCommit!==state)return;try{video.removeEventListener('seeked',state.seeked);}catch(ignore){}state.seeked=null;requestPresentedFrame();};video.addEventListener('seeked',state.seeked);try{video.currentTime=position;}catch(ignore){requestPresentedFrame();}}state.timer=setTimeout(function(){finish(false);},1250);seekTo(seekTime);}"
                + "function trackFrameDuration(video){if(!video||typeof video.requestVideoFrameCallback!=='function'||video.dataset.pichomeFrameTracker)return;video.dataset.pichomeFrameTracker='1';var previousTime=-1,previousFrames=-1,normalBefore=false,samples=[];function sample(now,metadata){var mediaTime=metadata&&metadata.mediaTime,presentedFrames=metadata&&metadata.presentedFrames,normal=isFinite(mediaTime)&&video.dataset.pichomeFrameMode!=='1'&&!video.seeking;if(normal&&normalBefore&&previousTime>=0&&mediaTime>previousTime){var frameCount=isFinite(presentedFrames)&&isFinite(previousFrames)&&presentedFrames>previousFrames?presentedFrames-previousFrames:1,duration=(mediaTime-previousTime)/frameCount;if(duration>=1/240&&duration<=1){samples.push(duration);if(samples.length>12)samples.shift();var sorted=samples.slice().sort(function(a,b){return a-b;}),middle=sorted[Math.floor(sorted.length/2)];video.dataset.pichomeFrameDuration=String(middle);}}if(normal){try{captureFramePreview(video,mediaTime,presentedFrames);}catch(ignore){}video.dataset.pichomeFrameTarget=String(mediaTime);}if(isFinite(mediaTime))updateFrameTimeline(video,mediaTime);normalBefore=normal;if(isFinite(mediaTime))previousTime=mediaTime;if(isFinite(presentedFrames))previousFrames=presentedFrames;video.requestVideoFrameCallback(sample);}video.addEventListener('loadedmetadata',function(){resetFramePreviewCache(video);previousTime=-1;previousFrames=-1;normalBefore=false;samples=[];});video.requestVideoFrameCallback(sample);}"
                + "function detailFrameRate(frame,video){var measured=parseFloat(video.dataset.pichomeFrameDuration);if(isFinite(measured)&&measured>0)return 1/measured;var rate=parseFrameRate(video.getAttribute('data-fps')||video.dataset.fps||video.dataset.frameRate);if(rate)return rate;try{var vm=detailVm(frame),data=vm&&vm.showdata&&vm.showdata[vm.showdataindex]&&vm.showdata[vm.showdataindex].data;rate=parseFrameRate(data&&(data.fps||data.frame_rate||data.framerate));}catch(ignore){}return rate||30;}";
        script += new String("")
                + "function setCustomFullscreenDocuments(frame,enabled){function visit(doc){try{if(!doc||!doc.documentElement)return;doc.documentElement.classList.toggle('pichome-custom-video-fullscreen-doc',!!enabled);doc.querySelectorAll('iframe').forEach(function(child){try{visit(child.contentDocument);}catch(ignore){}});}catch(ignore){}}try{visit(frame&&frame.contentDocument);}catch(ignore){}}"
                + "function syncCustomFullscreenButton(overlay,enabled){try{var button=overlay&&overlay.querySelector('[data-pichome-action=fullscreen]');if(!button)return;button.textContent=enabled?'退出':'全屏';button.setAttribute('aria-label',enabled?'退出全屏':'进入全屏');}catch(ignore){}}"
                + "function notifyCustomVideoFullscreen(enabled){try{if(window.PichomeCache&&typeof window.PichomeCache.setCustomVideoFullscreen==='function')window.PichomeCache.setCustomVideoFullscreen(!!enabled);}catch(ignore){}}"
                + "function refreshCustomVideoFullscreenLayout(state,enabled){if(!state)return;var frame=state.frame,overlay=state.overlay;if(frame){try{var vm=detailVm(frame);if(vm)vm.full=!!enabled;}catch(ignore){}try{fitDetailDialog(frame);}catch(ignore){}try{updateFrameControls(frame);}catch(ignore){}try{var video=currentDetailVideo(frame),cache=video&&video.__pichomeFrameCache;if(cache&&cache.visible&&cache.overlay)ensureFramePreviewOverlay(video,cache,cache.overlay.width,cache.overlay.height);}catch(ignore){}}syncCustomFullscreenButton(overlay,enabled);}"
                + "function exitCustomVideoFullscreen(){var state=window.__pichomeCustomVideoFullscreenState;if(!state)return false;window.__pichomeCustomVideoFullscreenState=null;if(state.observer)try{state.observer.disconnect();}catch(ignore){}try{state.overlay.classList.remove('pichome-custom-video-fullscreen');}catch(ignore){}document.documentElement.classList.remove('pichome-custom-video-fullscreen-root');setCustomFullscreenDocuments(state.frame,false);refreshCustomVideoFullscreenLayout(state,false);notifyCustomVideoFullscreen(false);[80,260,520].forEach(function(delay){setTimeout(function(){refreshCustomVideoFullscreenLayout(state,false);},delay);});return true;}"
                + "function requestCustomVideoFullscreen(frame,event){if(event){event.preventDefault();event.stopImmediatePropagation();}var active=window.__pichomeCustomVideoFullscreenState;if(active){exitCustomVideoFullscreen();return;}var overlay=frame&&frame.closest&&frame.closest('.Details-Iframe'),video=currentDetailVideo(frame);if(!overlay||!video)return;var state={overlay:overlay,frame:frame,video:video,observer:null};window.__pichomeCustomVideoFullscreenState=state;overlay.classList.add('pichome-custom-video-fullscreen');document.documentElement.classList.add('pichome-custom-video-fullscreen-root');setCustomFullscreenDocuments(frame,true);video.playsInline=true;video.controls=false;video.removeAttribute('controls');refreshCustomVideoFullscreenLayout(state,true);try{state.observer=new MutationObserver(function(){if(!document.documentElement.contains(overlay))exitCustomVideoFullscreen();});state.observer.observe(document.documentElement,{childList:true,subtree:true});}catch(ignore){}notifyCustomVideoFullscreen(true);[60,180,420].forEach(function(delay){setTimeout(function(){if(window.__pichomeCustomVideoFullscreenState===state)refreshCustomVideoFullscreenLayout(state,true);},delay);});}"
                + "function frameAtlasVideoIdentity(video){var identity={rid:'',path:''};try{var win=video&&video.ownerDocument&&video.ownerDocument.defaultView;while(win&&win.frameElement){var host=win.frameElement,raw=host.getAttribute('src')||host.src||'';try{var parsed=new URL(raw,host.ownerDocument.baseURI),rid=parsed.searchParams.get('pichome_rid'),path=parsed.searchParams.get('path');if(rid&&!identity.rid)identity.rid=rid;if(path&&!identity.path)identity.path=path;}catch(ignore){}win=host.ownerDocument&&host.ownerDocument.defaultView;}}catch(ignore){}return identity;}"
                + "function frameAtlasDescriptor(frame,video){try{var vm=detailVm(frame),data=vm&&vm.showdata&&vm.showdata[vm.showdataindex]&&vm.showdata[vm.showdataindex].data,identity=frameAtlasVideoIdentity(video),rid=data&&data.rid||identity.rid,path=data&&data.dpath||vm&&vm.path||identity.path||sessionStorage.getItem('detailpath');if(!rid||!path)return null;var url=new URL('index.php',window.location.href);url.searchParams.set('mod','details');url.searchParams.set('op','fileinterface');url.searchParams.set('operation','frameAtlasManifest');url.searchParams.set('profile','mobile-ultra');url.searchParams.set('path',String(path));return{rid:String(rid),path:String(path),manifestUrl:url.href};}catch(ignore){return null;}}"
                + "function frameAtlasBridge(){try{return window.PichomeCache||null;}catch(ignore){return null;}}"
                + "function frameAtlasState(frame,video){if(!video)return null;var descriptor=frameAtlasDescriptor(frame,video);if(!descriptor)return null;var signature=descriptor.rid+'|'+descriptor.path,state=video.__pichomeFrameAtlas;if(state&&state.signature===signature){if(!state.manifest&&state.failedAt&&Date.now()-state.failedAt>4000)video.__pichomeFrameAtlas=null;else return state;}state={signature:signature,rid:descriptor.rid,path:descriptor.path,manifestUrl:descriptor.manifestUrl,manifest:null,entries:{},queue:[],active:0,maxActive:2,maxSegments:3,lastError:'',frame:frame,video:video,center:-1};video.__pichomeFrameAtlas=state;fetch(descriptor.manifestUrl,{credentials:'include',cache:'no-store'}).then(function(response){if(!response.ok)throw new Error('manifest '+response.status);return response.json();}).then(function(manifest){if(video.__pichomeFrameAtlas!==state||!manifest||!manifest.success)return;state.manifest=manifest;video.dataset.pichomeAtlasFps=String(manifest.fps||'');state.track=function(){if(video.__pichomeFrameAtlas!==state||!state.manifest)return;var center=Math.floor(Math.max(0,video.currentTime||0));if(center!==state.center)warmFrameAtlas(state,video.currentTime);};video.addEventListener('timeupdate',state.track);video.addEventListener('seeked',state.track);warmFrameAtlas(state,video.currentTime);}).catch(function(error){state.lastError=String(error&&error.message||error);state.failedAt=Date.now();});return state;}"
                + "function frameAtlasSegmentUrl(state,second){var url=new URL(state.manifestUrl);url.searchParams.set('operation','frameAtlas');url.searchParams.set('revision',state.manifest.revision);url.searchParams.set('second',String(second));url.searchParams.set('pichome_atlas_rid',state.rid);url.searchParams.set('pichome_atlas_version',String(state.manifest.version||2));return url.href;}"
                + "function frameAtlasIdentity(state,second){return'atlas:'+state.rid+':'+state.manifest.revision+':'+second+':v'+String(state.manifest.version||2);}"
                + "function frameAtlasTrim(state){var ready=[];Object.keys(state.entries).forEach(function(key){var entry=state.entries[key];if(entry&&entry.status==='ready')ready.push(entry);});ready.sort(function(a,b){return b.lastUse-a.lastUse;});for(var i=state.maxSegments;i<ready.length;i++){delete state.entries[String(ready[i].second)];ready[i].image.onload=null;ready[i].image.onerror=null;ready[i].image.src='';}}"
                + "function drainFrameAtlas(state){if(!state||!state.manifest)return;while(state.active<state.maxActive&&state.queue.length){var entry=state.queue.shift();if(!entry||entry.status!=='queued')continue;entry.status='loading';state.active++;var remote=frameAtlasSegmentUrl(state,entry.second),identity=frameAtlasIdentity(state,entry.second),bridge=frameAtlasBridge(),local='';try{if(bridge)local=bridge.getCachedMediaUrl(remote,identity)||'';}catch(ignore){}var image=new Image(),retried=false,completed=false;entry.image=image;image.decoding='async';image.crossOrigin='anonymous';function finish(ok){if(completed)return;completed=true;state.active=Math.max(0,state.active-1);if(ok){entry.status='ready';entry.lastUse=performance.now();try{if(!local&&bridge)bridge.cacheMedia(remote,identity);}catch(ignore){}frameAtlasTrim(state);}else{entry.status='failed';entry.failedAt=Date.now();state.lastError='segment '+entry.second;}drainFrameAtlas(state);}image.onload=function(){try{var decoded=image.decode&&image.decode();if(decoded&&decoded.then){decoded.then(function(){finish(true);},function(){finish(true);});return;}}catch(ignore){}finish(true);};image.onerror=function(){if(local&&!retried){retried=true;local='';image.src=remote;return;}finish(false);};image.src=local||remote;}}"
                + "function requestFrameAtlasSegment(state,second,priority){if(!state||!state.manifest)return null;second=Math.max(0,Math.min(parseInt(state.manifest.maxSegment,10)||0,parseInt(second,10)||0));var key=String(second),entry=state.entries[key];if(entry){if(entry.status==='ready')entry.lastUse=performance.now();else if(entry.status==='queued'&&priority){var queuedIndex=state.queue.indexOf(entry);if(queuedIndex>=0)state.queue.splice(queuedIndex,1);state.queue.unshift(entry);drainFrameAtlas(state);}else if(entry.status==='failed'&&Date.now()-(entry.failedAt||0)>4000){entry.status='queued';priority?state.queue.unshift(entry):state.queue.push(entry);drainFrameAtlas(state);}return entry;}entry={second:second,status:'queued',lastUse:performance.now(),image:null};state.entries[key]=entry;priority?state.queue.unshift(entry):state.queue.push(entry);drainFrameAtlas(state);return entry;}"
                + "function warmFrameAtlas(state,time){if(!state||!state.manifest)return;var center=Math.max(0,Math.min(state.manifest.maxSegment,Math.floor(Math.max(0,isFinite(time)?time:0)))),offsets=[0,-1,1],allowed={};state.center=center;for(var i=0;i<offsets.length;i++){var allowedSecond=center+offsets[i];if(allowedSecond>=0&&allowedSecond<=state.manifest.maxSegment)allowed[String(allowedSecond)]=true;}Object.keys(state.entries).forEach(function(key){var entry=state.entries[key];if(!entry||allowed[key]||entry.status==='ready')return;if(entry.status==='loading'){entry.image.onload=null;entry.image.onerror=null;entry.image.src='';state.active=Math.max(0,state.active-1);}entry.status='cancelled';delete state.entries[key];});state.queue=state.queue.filter(function(entry){return!!(entry&&allowed[String(entry.second)]&&entry.status==='queued');});for(var j=0;j<offsets.length;j++){var second=center+offsets[j];if(second>=0&&second<=state.manifest.maxSegment)requestFrameAtlasSegment(state,second,j===0);}frameAtlasTrim(state);drainFrameAtlas(state);}"
                + "function prepareFrameAtlas(frame,video){var state=frameAtlasState(frame,video);if(state&&state.manifest)warmFrameAtlas(state,video.currentTime);return state;}"
                + "function showFrameAtlasAt(frame,video,target){var state=frameAtlasState(frame,video);if(!state||!state.manifest||!isFinite(target))return false;target=clampedSeekTime(video,target);var manifest=state.manifest,second=Math.max(0,Math.min(manifest.maxSegment,Math.floor(target))),entry=requestFrameAtlasSegment(state,second,true);warmFrameAtlas(state,target);if(!entry||entry.status!=='ready'||!entry.image||!entry.image.naturalWidth)return false;var fps=Math.max(1,parseFloat(manifest.fps)||30),segmentDuration=Math.min(1,Math.max(0,manifest.duration-second)),frameCount=Math.max(1,Math.min(manifest.framesPerSegment,Math.ceil(segmentDuration*fps))),index=Math.max(0,Math.min(frameCount-1,Math.floor((target-second)*fps+0.0001)));if(video.__pichomeFrameCache&&video.__pichomeFrameCache.disabled)video.__pichomeFrameCache=null;var cache=framePreviewCache(video);if(!cache)return false;try{var overlay=ensureFramePreviewOverlay(video,cache,manifest.cellWidth,manifest.cellHeight),context=overlay.getContext('2d',{alpha:false}),sourceX=(index%manifest.columns)*manifest.cellWidth,sourceY=Math.floor(index/manifest.columns)*manifest.cellHeight;context.drawImage(entry.image,sourceX,sourceY,manifest.cellWidth,manifest.cellHeight,0,0,manifest.cellWidth,manifest.cellHeight);overlay.style.display='block';cache.visible=true;cache.previewTime=Math.min(manifest.duration,second+index/fps);cache.previewFps=fps;cache.previewAtlasSecond=second;cache.previewAtlasIndex=index;entry.lastUse=performance.now();video.dataset.pichomeFrameTarget=String(cache.previewTime);updateFrameTimeline(video,cache.previewTime);video.pause();keepFrameUiHidden(video);return true;}catch(ignore){entry.status='failed';entry.failedAt=Date.now();return false;}}"
                + "function showFrameAtlasAdjacent(frame,video,direction){var state=frameAtlasState(frame,video);if(!state||!state.manifest)return false;var cache=video.__pichomeFrameCache,base=cache&&cache.visible&&isFinite(cache.previewTime)?cache.previewTime:parseFloat(video.dataset.pichomeFrameTarget);if(!isFinite(base)||Math.abs(base-video.currentTime)>.5)base=video.currentTime;var fps=Math.max(1,parseFloat(state.manifest.fps)||30),frameIndex=Math.round(base*fps)+direction,target=frameIndex/fps;if(target<0||target>state.manifest.duration)return false;return showFrameAtlasAt(frame,video,target);}"
                + "function pumpAtlasFrameScrub(state){var atlas=frameAtlasState(state.frame,state.video);if(!atlas)return false;if(!atlas.manifest)return!atlas.failedAt;var fps=Math.max(1,parseFloat(atlas.manifest.fps)||30),originFrame=isFinite(state.originFrame)?state.originFrame:Math.round(state.originTime*fps),targetFrame=Math.max(0,Math.min(Math.floor(atlas.manifest.duration*fps),originFrame+state.desiredDelta)),target=targetFrame/fps;if(state.video.dataset.pichomeFrameAdvancing==='1')cancelFrameAdvance(state.video);if(!showFrameAtlasAt(state.frame,state.video,target)){var waiting=atlas.entries[String(Math.floor(target))];if(waiting&&waiting.status==='failed')return false;setFrameScrubIndicator(state.surface,state.appliedDelta,target,true);return true;}state.appliedDelta=state.desiredDelta;setFrameScrubIndicator(state.surface,state.appliedDelta,target,true);return true;}";
        script += new String("")
                + "function leaveFrameModeForNativeFullscreen(video){if(!video)return;stopContinuousFrame();cancelFrameAdvance(video);cancelFramePreviewCommit(video,false);var cache=video.__pichomeFrameCache,target=cache&&cache.visible&&isFinite(cache.previewTime)?cache.previewTime:NaN;if(isFinite(target)){cancelFramePreviewCommit(video,true);video.dataset.pichomeFrameTarget=String(target);try{video.currentTime=target;}catch(ignore){}}else cancelFramePreviewCommit(video,true);delete video.dataset.pichomeFrameMode;var doc=video.ownerDocument,root=video.closest&&video.closest('.xgplayer-skin-default');if(doc&&doc.documentElement)doc.documentElement.classList.remove('pichome-frame-stepping-doc');if(root){root.classList.remove('pichome-frame-stepping');root.classList.remove('xgplayer-inactive');}}"
                + "function requestNativeVideoFullscreen(frame,event){if(event){event.preventDefault();event.stopImmediatePropagation();}var video=currentDetailVideo(frame);if(!video)return;leaveFrameModeForNativeFullscreen(video);var doc=video.ownerDocument,root=doc&&doc.documentElement,promise=null,restored=false;function restore(){if(restored)return;var active=doc&&(doc.fullscreenElement||doc.webkitFullscreenElement||doc.mozFullScreenElement||doc.msFullscreenElement);if(active)return;restored=true;delete video.dataset.pichomeNativeFullscreen;if(root)root.classList.add('pichome-custom-video-controls-doc');video.controls=false;video.removeAttribute('controls');doc.removeEventListener('fullscreenchange',restore);doc.removeEventListener('webkitfullscreenchange',restore);video.removeEventListener('webkitendfullscreen',restore);}try{var active=doc.fullscreenElement||doc.webkitFullscreenElement||doc.mozFullScreenElement||doc.msFullscreenElement;if(active){var exit=doc.exitFullscreen||doc.webkitExitFullscreen||doc.mozCancelFullScreen||doc.msExitFullscreen;if(exit)promise=exit.call(doc);return;}video.dataset.pichomeNativeFullscreen='1';if(root)root.classList.remove('pichome-custom-video-controls-doc');video.controls=true;video.setAttribute('controls','controls');doc.addEventListener('fullscreenchange',restore);doc.addEventListener('webkitfullscreenchange',restore);video.addEventListener('webkitendfullscreen',restore);var request=video.requestFullscreen||video.webkitRequestFullscreen||video.mozRequestFullScreen||video.msRequestFullscreen;if(request)promise=request.call(video);else if(video.webkitEnterFullscreen)video.webkitEnterFullscreen();else{restore();return;}if(promise&&promise.catch)promise.catch(function(){restore();});}catch(ignore){restore();}}"
                + "function installVideoCommandbar(frame,video,bar){if(!bar)return;if(bar.__pichomeCommandbarVideo===video){if(bar.__pichomeCommandbarUpdate)bar.__pichomeCommandbarUpdate();return;}if(bar.__pichomeCommandbarCleanup)try{bar.__pichomeCommandbarCleanup();}catch(ignore){}var playButton=bar.querySelector('[data-pichome-action=play]'),volumeButton=bar.querySelector('[data-pichome-action=volume]'),speedButton=bar.querySelector('[data-pichome-action=speed]'),fullscreenButton=bar.querySelector('[data-pichome-action=fullscreen]');if(!playButton||!volumeButton||!speedButton||!fullscreenButton)return;var videoDoc=video.ownerDocument,videoRoot=video.closest&&video.closest('.xgplayer-skin-default'),fullscreenHost=bar.closest&&bar.closest('.Details-Iframe')||bar,fullscreenDoc=fullscreenHost.ownerDocument,speeds=[.5,.75,1,1.25,1.5,2];bar.__pichomeCommandbarVideo=video;video.__pichomeCommandbar=bar;if(videoDoc&&videoDoc.documentElement)videoDoc.documentElement.classList.add('pichome-custom-video-controls-doc');video.controls=false;function suppressVideoClick(){if(videoRoot)videoRoot.dataset.pichomeFrameSuppressClickUntil=String(Date.now()+700);}function leaveFrameMode(){stopContinuousFrame();cancelFrameAdvance(video);cancelFramePreviewCommit(video,false);var cache=video.__pichomeFrameCache,target=cache&&cache.visible&&isFinite(cache.previewTime)?cache.previewTime:NaN;if(isFinite(target)){cancelFramePreviewCommit(video,true);video.dataset.pichomeFrameTarget=String(target);try{video.currentTime=target;}catch(ignore){}}else cancelFramePreviewCommit(video,true);delete video.dataset.pichomeFrameMode;if(videoDoc&&videoDoc.documentElement)videoDoc.documentElement.classList.remove('pichome-frame-stepping-doc');if(videoRoot){videoRoot.classList.remove('pichome-frame-stepping');videoRoot.classList.remove('xgplayer-inactive');}video.controls=false;suppressVideoClick();}function update(){if(video.dataset.pichomeNativeFullscreen!=='1')video.controls=false;var silent=video.muted||video.volume<=.001,rate=isFinite(video.playbackRate)&&video.playbackRate>0?video.playbackRate:1,isFullscreen=!!(fullscreenDoc.fullscreenElement||fullscreenDoc.webkitFullscreenElement);playButton.textContent=video.paused?'播放':'暂停';playButton.setAttribute('aria-label',video.paused?'播放视频':'暂停视频');volumeButton.textContent=silent?'声音':'静音';volumeButton.setAttribute('aria-label',silent?'恢复声音':'静音');speedButton.textContent=String(Math.round(rate*100)/100)+'×';speedButton.setAttribute('aria-label','播放速度 '+speedButton.textContent);fullscreenButton.textContent=isFullscreen?'退出':'全屏';fullscreenButton.setAttribute('aria-label',isFullscreen?'退出全屏':'进入全屏');bar.querySelectorAll('button').forEach(function(button){button.disabled=video.readyState<1;});}function playClick(event){event.preventDefault();event.stopPropagation();var shouldPlay=video.paused||video.ended;leaveFrameMode();if(shouldPlay){if(video.ended)try{video.currentTime=0;}catch(ignore){}try{var promise=video.play();if(promise&&promise.catch)promise.catch(function(){});}catch(ignore){}}else video.pause();update();}function volumeClick(event){event.preventDefault();event.stopPropagation();if(video.muted||video.volume<=.001){video.muted=false;if(video.volume<=.001){var saved=parseFloat(video.dataset.pichomeLastVolume);video.volume=isFinite(saved)&&saved>0?Math.min(1,saved):1;}}else{video.dataset.pichomeLastVolume=String(video.volume||1);video.muted=true;}update();}function speedClick(event){event.preventDefault();event.stopPropagation();var current=isFinite(video.playbackRate)?video.playbackRate:1,next=speeds[0];for(var i=0;i<speeds.length;i++){if(speeds[i]>current+.01){next=speeds[i];break;}}video.playbackRate=next;video.defaultPlaybackRate=next;update();}function fullscreenClick(event){event.preventDefault();event.stopPropagation();var active=fullscreenDoc.fullscreenElement||fullscreenDoc.webkitFullscreenElement,promise=null;try{if(active){var exit=fullscreenDoc.exitFullscreen||fullscreenDoc.webkitExitFullscreen;if(exit)promise=exit.call(fullscreenDoc);}else{var request=fullscreenHost.requestFullscreen||fullscreenHost.webkitRequestFullscreen;if(request)promise=request.call(fullscreenHost);else if(video.webkitEnterFullscreen)video.webkitEnterFullscreen();}if(promise&&promise.catch)promise.catch(function(){});}catch(ignore){}setTimeout(update,80);}function sync(){update();}playButton.addEventListener('click',playClick);volumeButton.addEventListener('click',volumeClick);speedButton.addEventListener('click',speedClick);fullscreenButton.addEventListener('click',fullscreenClick);['play','pause','ended','volumechange','ratechange','loadedmetadata'].forEach(function(name){video.addEventListener(name,sync);});fullscreenDoc.addEventListener('fullscreenchange',sync);fullscreenDoc.addEventListener('webkitfullscreenchange',sync);bar.__pichomeCommandbarUpdate=update;bar.__pichomeCommandbarCleanup=function(){playButton.removeEventListener('click',playClick);volumeButton.removeEventListener('click',volumeClick);speedButton.removeEventListener('click',speedClick);fullscreenButton.removeEventListener('click',fullscreenClick);['play','pause','ended','volumechange','ratechange','loadedmetadata'].forEach(function(name){video.removeEventListener(name,sync);});fullscreenDoc.removeEventListener('fullscreenchange',sync);fullscreenDoc.removeEventListener('webkitfullscreenchange',sync);if(videoDoc&&videoDoc.documentElement)videoDoc.documentElement.classList.remove('pichome-custom-video-controls-doc');if(video.__pichomeCommandbar===bar)video.__pichomeCommandbar=null;bar.__pichomeCommandbarVideo=null;bar.__pichomeCommandbarUpdate=null;bar.__pichomeCommandbarCleanup=null;};update();}"
                + "function updateFrameControls(frame){var overlay=frame&&frame.closest&&frame.closest('.Details-Iframe'),nav=overlay&&overlay.querySelector('.pichome-detail-nav'),scrub=overlay&&overlay.querySelector('.pichome-frame-scrub-pad'),timeline=overlay&&overlay.querySelector('.pichome-video-timeline'),commandbar=overlay&&overlay.querySelector('.pichome-video-commandbar');if(!nav)return;var video=currentDetailVideo(frame);overlay.classList.toggle('pichome-has-frame-controls',!!video);nav.hidden=!video;if(scrub)scrub.hidden=!video;if(timeline)timeline.hidden=!video;if(commandbar)commandbar.hidden=!video;nav.querySelectorAll('button').forEach(function(button){button.disabled=!video||video.readyState<1;});if(video){installVideoCommandbar(frame,video,commandbar);installFrameTimeline(frame,video,timeline);installFrameScrub(frame,video);trackFrameDuration(video);prepareFrameAtlas(frame,video);if(!video.dataset.pichomeFrameControlsReady){video.dataset.pichomeFrameControlsReady='1';video.addEventListener('loadedmetadata',function(){updateFrameControls(frame);});video.addEventListener('durationchange',function(){updateFrameControls(frame);});}}else if(commandbar&&commandbar.__pichomeCommandbarCleanup)commandbar.__pichomeCommandbarCleanup();}"
                + "function keepFrameUiHidden(video){if(!video||video.dataset.pichomeFrameMode!=='1')return;var doc=video.ownerDocument,root=video.closest&&video.closest('.xgplayer-skin-default');if(doc&&doc.documentElement)doc.documentElement.classList.add('pichome-frame-stepping-doc');if(root)root.classList.add('pichome-frame-stepping');video.controls=false;}"
                + "function showPlayerUi(video){if(!video)return;commitFramePreview(video);var doc=video.ownerDocument,root=video.closest&&video.closest('.xgplayer-skin-default'),win=doc&&doc.defaultView;delete video.dataset.pichomeFrameMode;if(doc&&doc.documentElement)doc.documentElement.classList.remove('pichome-frame-stepping-doc');if(root){root.classList.remove('pichome-frame-stepping');root.classList.remove('xgplayer-inactive');}try{var player=win&&win.player;if(player&&typeof player.emit==='function')player.emit('focus');}catch(ignore){}}"
                + "function installFrameUiTap(video){var root=video&&video.closest&&video.closest('.xgplayer-skin-default');if(!root||root.dataset.pichomeFrameUiTap)return;root.dataset.pichomeFrameUiTap='1';root.addEventListener('click',function(event){if(video.dataset.pichomeFrameMode!=='1')return;var suppressUntil=parseInt(root.dataset.pichomeFrameSuppressClickUntil||'0',10);if(Date.now()<suppressUntil){event.preventDefault();event.stopImmediatePropagation();return;}if(event.target&&event.target.closest&&event.target.closest('.xgplayer-controls'))return;showPlayerUi(video);},true);}"
                + "function enterFrameMode(video){var doc=video.ownerDocument,win=doc&&doc.defaultView;installFrameUiTap(video);video.dataset.pichomeFrameMode='1';video.autoplay=false;keepFrameUiHidden(video);video.pause();keepFrameUiHidden(video);if(win)win.__pichomeAndroidAutoplayPlaying=true;return video.closest&&video.closest('.xgplayer-skin-default');}"
                + "function clampedSeekTime(video,target){var duration=video.duration;if(isFinite(duration))target=Math.min(duration,target);target=Math.max(0,target);try{var ranges=video.seekable,current=video.currentTime;for(var i=0;i<ranges.length;i++){var start=ranges.start(i),end=ranges.end(i);if(current>=start-.05&&current<=end+.05)return Math.max(start,Math.min(end,target));}}catch(ignore){}return target;}"
                + "function oneFrameTarget(frame,video,direction){var fps=detailFrameRate(frame,video),step=1/fps,base=parseFloat(video.dataset.pichomeFrameTarget),cache=video.__pichomeFrameCache;if(!isFinite(base)||!(cache&&cache.visible)&&Math.abs(base-video.currentTime)>Math.max(.5,step*4))base=video.currentTime;return clampedSeekTime(video,base+direction*step);}"
                + "function seekOneFrame(frame,video,direction){var target=oneFrameTarget(frame,video,direction);video.dataset.pichomeFrameTarget=String(target);video.currentTime=target;return target;}"
                + "function finishFrameAdvance(video,state,mediaTime){if(!state||video.__pichomeFrameAdvance!==state)return;if(state.timer)clearTimeout(state.timer);if(state.seeked){try{video.removeEventListener('seeked',state.seeked);}catch(ignore){}}if(state.callbackId!==undefined&&typeof video.cancelVideoFrameCallback==='function')try{video.cancelVideoFrameCallback(state.callbackId);}catch(ignore){}video.__pichomeFrameAdvance=null;try{video.pause();video.muted=state.muted;}catch(ignore){}delete video.dataset.pichomeFrameAdvancing;if(isFinite(mediaTime))video.dataset.pichomeFrameTarget=String(mediaTime);else if(isFinite(video.currentTime))video.dataset.pichomeFrameTarget=String(video.currentTime);updateFrameTimeline(video,isFinite(mediaTime)?mediaTime:video.currentTime);hideFramePreview(video);}"
                + "function cancelFrameAdvance(video){if(!video)return;var state=video.__pichomeFrameAdvance;if(state){if(state.timer)clearTimeout(state.timer);if(state.seeked)try{video.removeEventListener('seeked',state.seeked);}catch(ignore){}if(state.callbackId!==undefined&&typeof video.cancelVideoFrameCallback==='function')try{video.cancelVideoFrameCallback(state.callbackId);}catch(ignore){}video.__pichomeFrameAdvance=null;try{video.pause();video.muted=state.muted;}catch(ignore){}}video.dataset.pichomeFrameToken=String((parseInt(video.dataset.pichomeFrameToken)||0)+1);delete video.dataset.pichomeFrameAdvancing;if(isFinite(video.currentTime))video.dataset.pichomeFrameTarget=String(video.currentTime);}"
                + "function stepCurrentVideo(frame,direction){var video=currentDetailVideo(frame);if(!video||video.readyState<1||video.dataset.pichomeFrameAdvancing==='1')return false;try{if(video.dataset.pichomeFrameMode!=='1')enterFrameMode(video);else{video.pause();keepFrameUiHidden(video);}if(showFrameAtlasAdjacent(frame,video,direction))return true;var adjacent=cachedAdjacentFrame(video,direction,frame),forcedTarget=NaN;if(adjacent){if(adjacent.time>=1){if(showCachedAdjacentFrame(video,direction,adjacent))return true;}else forcedTarget=adjacent.time;}var cache=video.__pichomeFrameCache,previewActive=!!(cache&&cache.visible),baseTarget=parseFloat(video.dataset.pichomeFrameTarget),logicalBase=previewActive&&isFinite(cache.previewTime)?cache.previewTime:video.currentTime,frameStep=1/Math.max(1,detailFrameRate(frame,video)),naturalForward=direction>0&&!previewActive&&!isFinite(forcedTarget)&&typeof video.requestVideoFrameCallback==='function',target=naturalForward?NaN:isFinite(forcedTarget)?forcedTarget:oneFrameTarget(frame,video,direction);if(direction<0&&isFinite(logicalBase)&&(!isFinite(target)||target>=logicalBase||logicalBase-target>frameStep*2))target=clampedSeekTime(video,logicalBase-frameStep);var token=String((parseInt(video.dataset.pichomeFrameToken)||0)+1),state={token:token,direction:direction,muted:video.muted,startTime:isFinite(baseTarget)?baseTarget:video.currentTime,expectedTarget:target,deadline:performance.now()+700,callbackId:undefined,seeked:null,timer:0};video.dataset.pichomeFrameToken=token;video.dataset.pichomeFrameAdvancing='1';video.__pichomeFrameAdvance=state;function complete(now,metadata){if(video.__pichomeFrameAdvance!==state||video.dataset.pichomeFrameToken!==token)return;var mediaTime=metadata&&metadata.mediaTime;if(isFinite(state.expectedTarget)&&isFinite(mediaTime)){var tolerance=frameStep*.45+.002;if(Math.abs(mediaTime-state.expectedTarget)>tolerance&&now<state.deadline&&typeof video.requestVideoFrameCallback==='function'){state.callbackId=video.requestVideoFrameCallback(complete);return;}}finishFrameAdvance(video,state,isFinite(mediaTime)?mediaTime:video.currentTime);}if(typeof video.requestVideoFrameCallback==='function')state.callbackId=video.requestVideoFrameCallback(complete);else{state.seeked=function(){finishFrameAdvance(video,state,video.currentTime);};video.addEventListener('seeked',state.seeked);}state.timer=setTimeout(function(){if(video.__pichomeFrameAdvance!==state)return;video.pause();if(naturalForward&&Math.abs(video.currentTime-state.startTime)<1/Math.max(1,detailFrameRate(frame,video)*2))seekOneFrame(frame,video,1);finishFrameAdvance(video,state,video.currentTime);},naturalForward?450:900);if(naturalForward){video.muted=true;var promise=video.play();if(promise&&promise.catch)promise.catch(function(){if(video.__pichomeFrameAdvance!==state)return;video.pause();seekOneFrame(frame,video,1);});}else if(Math.abs(target-video.currentTime)<.000001)finishFrameAdvance(video,state,video.currentTime);else{video.dataset.pichomeFrameTarget=String(target);updateFrameTimeline(video,target);video.currentTime=target;}return true;}catch(ignore){cancelFrameAdvance(video);return false;}}"
                + "function stepCurrentVideoAndCommit(frame,direction){var video=currentDetailVideo(frame);if(!video)return false;cancelFramePreviewCommit(video,false);var stepped=stepCurrentVideo(frame,direction);if(!stepped)return false;var cache=video.__pichomeFrameCache;if(cache&&cache.visible&&isFinite(cache.previewTime))commitFramePreview(video);return true;}"
                + "function stopContinuousFrame(){var state=window.__pichomeFrameHold;if(!state)return;window.__pichomeFrameHold=null;if(state.raf)cancelAnimationFrame(state.raf);var video=state.video;try{cancelFrameAdvance(video);keepFrameUiHidden(video);video.pause();keepFrameUiHidden(video);video.muted=state.muted;video.playbackRate=state.rate;delete video.dataset.pichomeFrameHolding;var cache=video.__pichomeFrameCache;if(cache&&cache.visible&&isFinite(cache.previewTime))commitFramePreview(video);else video.dataset.pichomeFrameTarget=String(video.currentTime);}catch(ignore){}}"
                + "function startContinuousFrame(frame,direction){var video=currentDetailVideo(frame);if(!video||video.readyState<1)return false;stopContinuousFrame();cancelFramePreviewCommit(video,false);cancelFrameAdvance(video);var cache=video.__pichomeFrameCache;if(cache&&cache.visible&&isFinite(cache.previewTime)){video.dataset.pichomeFrameTarget=String(cache.previewTime);video.currentTime=cache.previewTime;hideFramePreview(video);}enterFrameMode(video);video.dataset.pichomeFrameToken=String((parseInt(video.dataset.pichomeFrameToken)||0)+1);video.dataset.pichomeFrameHolding='1';var state={video:video,direction:direction,muted:video.muted,rate:video.playbackRate,raf:0};window.__pichomeFrameHold=state;video.muted=true;video.playbackRate=1;if(direction>0){var promise=video.play();if(promise&&promise.catch)promise.catch(function(){if(window.__pichomeFrameHold!==state)return;var origin=video.currentTime,started=performance.now(),last=0;function forward(now){if(window.__pichomeFrameHold!==state)return;if(now-last>=1000/30){last=now;video.currentTime=clampedSeekTime(video,origin+(now-started)/1000);}state.raf=requestAnimationFrame(forward);}state.raf=requestAnimationFrame(forward);});}else{video.pause();var last=0;function reverse(now){if(window.__pichomeFrameHold!==state)return;if(now-last>=1000/30){last=now;if(video.dataset.pichomeFrameAdvancing!=='1'&&!video.seeking)stepCurrentVideo(frame,-1);}state.raf=requestAnimationFrame(reverse);}state.raf=requestAnimationFrame(reverse);}return true;}"
                + "function frameScrubPixelsPerFrame(){var value='';try{value=localStorage.getItem('pichomeFrameScrubPixels')||localStorage.getItem('pichomeDesktopFrameScrubPixels')||'';}catch(ignore){}return value==='14'||value==='3'?14:7;}"
                + "function formatFrameScrubTime(seconds){seconds=Math.max(0,isFinite(seconds)?seconds:0);var minutes=Math.floor(seconds/60),whole=Math.floor(seconds%60),millis=Math.floor((seconds-Math.floor(seconds))*1000);return String(minutes).padStart(2,'0')+':'+String(whole).padStart(2,'0')+'.'+String(millis).padStart(3,'0');}"
                + "function formatVideoTimelineTime(seconds){seconds=Math.max(0,isFinite(seconds)?seconds:0);var whole=Math.floor(seconds),hours=Math.floor(whole/3600),minutes=Math.floor(whole%3600/60),secs=whole%60;return hours?hours+':'+String(minutes).padStart(2,'0')+':'+String(secs).padStart(2,'0'):String(minutes).padStart(2,'0')+':'+String(secs).padStart(2,'0');}"
                + "function updateFrameTimeline(video,time){var timeline=video&&video.__pichomeFrameTimeline;if(timeline&&timeline.__pichomeTimelineUpdate)timeline.__pichomeTimelineUpdate(time);}"
                + "function installFrameTimeline(frame,video,timeline){if(!timeline)return;if(timeline.__pichomeTimelineVideo===video){updateFrameTimeline(video,video.currentTime);return;}if(timeline.__pichomeTimelineCleanup)try{timeline.__pichomeTimelineCleanup();}catch(ignore){}var range=timeline.querySelector('input[type=range]'),currentLabel=timeline.querySelector('.pichome-video-time-current'),durationLabel=timeline.querySelector('.pichome-video-time-duration');if(!range||!currentLabel||!durationLabel)return;timeline.__pichomeTimelineVideo=video;video.__pichomeFrameTimeline=timeline;var dragging=false,prepared=false,pendingTarget=NaN,raf=0,pointerId=null;function totalDuration(){var total=video.duration;return isFinite(total)&&total>0?total:0;}function render(time){var total=totalDuration(),shown=dragging&&isFinite(pendingTarget)?pendingTarget:isFinite(time)?time:video.currentTime;shown=Math.max(0,total?Math.min(total,shown):shown);if(!dragging)range.value=String(total?Math.round(shown/total*1000):0);currentLabel.textContent=formatVideoTimelineTime(shown);durationLabel.textContent=formatVideoTimelineTime(total);var progress=total?Math.max(0,Math.min(100,parseFloat(range.value||'0')/10)):0;range.style.setProperty('--pichome-progress',progress+'%');range.disabled=!(total>0);}function suppressClick(){var root=video.closest&&video.closest('.xgplayer-skin-default');if(root)root.dataset.pichomeFrameSuppressClickUntil=String(Date.now()+700);}function prepare(){if(prepared)return;prepared=true;dragging=true;stopContinuousFrame();cancelFramePreviewCommit(video,true);cancelFrameAdvance(video);enterFrameMode(video);resetFramePreviewCache(video);suppressClick();}function readTarget(){var total=totalDuration(),value=parseFloat(range.value);return total&&isFinite(value)?clampedSeekTime(video,total*Math.max(0,Math.min(1000,value))/1000):NaN;}function applyTarget(){if(raf){cancelAnimationFrame(raf);raf=0;}if(!isFinite(pendingTarget))return NaN;var target=pendingTarget;pendingTarget=NaN;hideFramePreview(video);video.pause();keepFrameUiHidden(video);video.dataset.pichomeFrameTarget=String(target);try{video.currentTime=target;}catch(ignore){}render(target);return target;}function queueTarget(event){if(event)event.stopPropagation();prepare();pendingTarget=readTarget();render(pendingTarget);if(!raf)raf=requestAnimationFrame(applyTarget);}function pointerDown(event){if(event.button!==undefined&&event.button!==0)return;event.stopPropagation();prepare();pointerId=event.pointerId;try{range.setPointerCapture(pointerId);}catch(ignore){}}function finish(event){if(event)event.stopPropagation();if(!prepared){render(video.currentTime);return;}var target=applyTarget();dragging=false;prepared=false;video.pause();keepFrameUiHidden(video);resetFramePreviewCache(video);suppressClick();if(isFinite(target))video.dataset.pichomeFrameTarget=String(target);render(isFinite(target)?target:video.currentTime);if(pointerId!==null)try{range.releasePointerCapture(pointerId);}catch(ignore){}pointerId=null;}function sync(){render(video.currentTime);}range.addEventListener('pointerdown',pointerDown);range.addEventListener('input',queueTarget);range.addEventListener('pointerup',finish);range.addEventListener('pointercancel',finish);range.addEventListener('change',finish);video.addEventListener('timeupdate',sync);video.addEventListener('durationchange',sync);video.addEventListener('loadedmetadata',sync);video.addEventListener('seeked',sync);timeline.__pichomeTimelineUpdate=render;timeline.__pichomeTimelineCleanup=function(){if(raf)cancelAnimationFrame(raf);range.removeEventListener('pointerdown',pointerDown);range.removeEventListener('input',queueTarget);range.removeEventListener('pointerup',finish);range.removeEventListener('pointercancel',finish);range.removeEventListener('change',finish);video.removeEventListener('timeupdate',sync);video.removeEventListener('durationchange',sync);video.removeEventListener('loadedmetadata',sync);video.removeEventListener('seeked',sync);if(video.__pichomeFrameTimeline===timeline)video.__pichomeFrameTimeline=null;timeline.__pichomeTimelineVideo=null;timeline.__pichomeTimelineUpdate=null;};render(video.currentTime);}"
                + "function setFrameScrubIndicator(surface,frameDelta,time,visible){var indicator=surface.querySelector('.pichome-frame-scrub-indicator');if(!indicator){indicator=surface.ownerDocument.createElement('div');indicator.className='pichome-frame-scrub-indicator';indicator.hidden=true;surface.appendChild(indicator);}indicator.hidden=!visible;if(visible)indicator.textContent=(frameDelta>0?'+':'')+frameDelta+' 帧 · '+formatFrameScrubTime(time);}"
                + "function pumpFrameScrub(state){if(!state||state.raf)return;function tick(){state.raf=0;if(state.cancelled)return;var remaining=state.desiredDelta-state.appliedDelta,busy=state.video.dataset.pichomeFrameAdvancing==='1'||state.video.seeking;if(remaining&&state.pointerDown&&pumpAtlasFrameScrub(state)){state.raf=requestAnimationFrame(tick);return;}if(!remaining){if(!state.pointerDown){if(busy){state.raf=requestAnimationFrame(tick);return;}state.surface.classList.remove('pichome-frame-scrubbing');setFrameScrubIndicator(state.surface,state.appliedDelta,state.video.currentTime,false);if(state.finish)state.finish();}return;}if(busy){state.raf=requestAnimationFrame(tick);return;}var direction=remaining>0?1:-1;if(stepCurrentVideo(state.frame,direction)){state.appliedDelta+=direction;var shownTime=parseFloat(state.video.dataset.pichomeFrameTarget);setFrameScrubIndicator(state.surface,state.appliedDelta,isFinite(shownTime)?shownTime:state.video.currentTime,true);}else state.desiredDelta=state.appliedDelta;state.raf=requestAnimationFrame(tick);}state.raf=requestAnimationFrame(tick);}"
                + "function installFrameScrub(frame,video){var overlay=frame&&frame.closest&&frame.closest('.Details-Iframe'),surface=overlay&&overlay.querySelector('.pichome-frame-scrub-pad');if(!surface||surface.__pichomeFrameScrubVideo===video)return;if(surface.__pichomeFrameScrubCleanup)try{surface.__pichomeFrameScrubCleanup();}catch(ignore){}surface.__pichomeFrameScrubVideo=video;var gesture=null,activeScrub=null;"
                + "function touchById(list,id){if(!list)return null;for(var i=0;i<list.length;i++)if(list[i].identifier===id)return list[i];return null;}"
                + "function touchStart(event){if(gesture||video.readyState<1||event.touches.length!==1)return;var touch=event.touches[0];event.preventDefault();event.stopImmediatePropagation();if(activeScrub){activeScrub.cancelled=true;if(activeScrub.raf)cancelAnimationFrame(activeScrub.raf);activeScrub=null;}cancelFramePreviewCommit(video,false);cancelFrameAdvance(video);stopContinuousFrame();enterFrameMode(video);var initialTime=parseFloat(video.dataset.pichomeFrameTarget);if(!isFinite(initialTime))initialTime=video.currentTime;var atlas=prepareFrameAtlas(frame,video),fps=atlas&&atlas.manifest?Math.max(1,parseFloat(atlas.manifest.fps)||30):detailFrameRate(frame,video),originFrame=atlas&&atlas.manifest?Math.round(initialTime*fps):NaN,pixelsPerFrame=frameScrubPixelsPerFrame();gesture={touchId:touch.identifier,startX:touch.clientX,originTime:initialTime,originFrame:originFrame,pixelsPerFrame:pixelsPerFrame,desiredDelta:0,appliedDelta:0,pointerDown:true,cancelled:false,raf:0,video:video,frame:frame,surface:surface,finish:function(){if(activeScrub===this)activeScrub=null;commitFramePreview(video);}};activeScrub=gesture;surface.dataset.pichomeFrameScrubPixels=String(pixelsPerFrame);surface.setAttribute('aria-label','左右滑动逐帧预览，每帧'+pixelsPerFrame+'像素');surface.classList.add('pichome-frame-scrubbing');setFrameScrubIndicator(surface,0,initialTime,true);}"
                + "function touchMove(event){var state=gesture;if(!state||state.cancelled)return;var touch=touchById(event.touches,state.touchId);if(!touch)return;event.preventDefault();event.stopImmediatePropagation();var dx=touch.clientX-state.startX,step=state.pixelsPerFrame||7,frameDelta=dx<0?Math.ceil(dx/step):Math.floor(dx/step);if(frameDelta===state.desiredDelta)return;state.desiredDelta=frameDelta;pumpFrameScrub(state);}"
                + "function finishTouch(event){var state=gesture;if(!state)return;if(event&&event.changedTouches&&!touchById(event.changedTouches,state.touchId)&&touchById(event.touches,state.touchId))return;gesture=null;if(event){event.preventDefault();event.stopImmediatePropagation();}state.pointerDown=false;var advancing=video.__pichomeFrameAdvance;if(advancing){state.appliedDelta-=advancing.direction;cancelFrameAdvance(video);}state.desiredDelta=state.appliedDelta;if(state.raf){cancelAnimationFrame(state.raf);state.raf=0;}var root=video.closest&&video.closest('.xgplayer-skin-default');if(root)root.dataset.pichomeFrameSuppressClickUntil=String(Date.now()+700);video.pause();keepFrameUiHidden(video);pumpFrameScrub(state);}"
                + "surface.addEventListener('touchstart',touchStart,{capture:true,passive:false});surface.addEventListener('touchmove',touchMove,{capture:true,passive:false});surface.addEventListener('touchend',finishTouch,{capture:true,passive:false});surface.addEventListener('touchcancel',finishTouch,{capture:true,passive:false});surface.__pichomeFrameScrubCleanup=function(){surface.removeEventListener('touchstart',touchStart,true);surface.removeEventListener('touchmove',touchMove,true);surface.removeEventListener('touchend',finishTouch,true);surface.removeEventListener('touchcancel',finishTouch,true);if(activeScrub){activeScrub.cancelled=true;if(activeScrub.raf)cancelAnimationFrame(activeScrub.raf);}cancelFrameAdvance(video);cancelFramePreviewCommit(video,true);surface.classList.remove('pichome-frame-scrubbing');setFrameScrubIndicator(surface,0,0,false);};}"
                + "function ensureFrameControls(frame){"
                + "var overlay=frame&&frame.closest&&frame.closest('.Details-Iframe');if(!overlay)return;var nav=overlay.querySelector('.pichome-detail-nav');"
                + "if(!nav){nav=document.createElement('div');nav.className='pichome-detail-nav';nav.hidden=true;nav.setAttribute('role','group');nav.setAttribute('aria-label','逐帧预览');"
                + "[-1,1].forEach(function(direction){var button=document.createElement('button');button.type='button';button.textContent=direction<0?'←':'→';button.setAttribute('aria-label',direction<0?'后退一帧':'前进一帧');button.title=direction<0?'后退一帧':'前进一帧';"
                + "var holdTimer=0,holding=false,lastPointerUp=0;button.addEventListener('pointerdown',function(event){if(button.disabled||event.button!==undefined&&event.button!==0)return;event.preventDefault();event.stopPropagation();holding=false;try{button.setPointerCapture(event.pointerId);}catch(ignore){}clearTimeout(holdTimer);holdTimer=setTimeout(function(){holding=true;startContinuousFrame(frame,direction);},280);});"
                + "button.addEventListener('pointerup',function(event){event.preventDefault();event.stopPropagation();clearTimeout(holdTimer);lastPointerUp=Date.now();if(holding){stopContinuousFrame();holding=false;}else if(!button.disabled){stepCurrentVideoAndCommit(frame,direction);}try{button.releasePointerCapture(event.pointerId);}catch(ignore){}});"
                + "function cancelHold(event){clearTimeout(holdTimer);if(holding){stopContinuousFrame();holding=false;}if(event)event.stopPropagation();}button.addEventListener('pointercancel',cancelHold);button.addEventListener('lostpointercapture',cancelHold);button.addEventListener('click',function(event){event.preventDefault();event.stopPropagation();if(event.detail===0&&Date.now()-lastPointerUp>500&&!button.disabled)stepCurrentVideoAndCommit(frame,direction);});nav.appendChild(button);});"
                + "overlay.appendChild(nav);}var commandbar=overlay.querySelector('.pichome-video-commandbar');if(!commandbar){commandbar=document.createElement('div');commandbar.className='pichome-video-commandbar';commandbar.hidden=true;commandbar.setAttribute('role','group');commandbar.setAttribute('aria-label','视频播放控制');commandbar.innerHTML='<button type=\"button\" data-pichome-action=\"play\" aria-label=\"播放视频\">播放</button><button type=\"button\" data-pichome-action=\"volume\" aria-label=\"静音\">静音</button><button type=\"button\" data-pichome-action=\"speed\" aria-label=\"播放速度 1倍\">1×</button><button type=\"button\" data-pichome-action=\"fullscreen\" aria-label=\"进入全屏\">全屏</button>';var nativeFullscreenButton=commandbar.querySelector('[data-pichome-action=fullscreen]');nativeFullscreenButton.addEventListener('click',function(event){requestNativeVideoFullscreen(frame,event);},true);overlay.appendChild(commandbar);}var timeline=overlay.querySelector('.pichome-video-timeline');if(!timeline){timeline=document.createElement('div');timeline.className='pichome-video-timeline';timeline.hidden=true;timeline.setAttribute('role','group');timeline.setAttribute('aria-label','视频总进度');timeline.innerHTML='<time class=\"pichome-video-time-current\">00:00</time><input type=\"range\" min=\"0\" max=\"1000\" step=\"1\" value=\"0\" aria-label=\"视频总进度\"><time class=\"pichome-video-time-duration\">00:00</time>';overlay.appendChild(timeline);}var scrub=overlay.querySelector('.pichome-frame-scrub-pad');if(!scrub){scrub=document.createElement('div');scrub.className='pichome-frame-scrub-pad';scrub.hidden=true;scrub.setAttribute('role','group');scrub.setAttribute('aria-label','左右滑动逐帧预览');overlay.appendChild(scrub);}updateFrameControls(frame);"
                + "}"
                + "function fitDetailDialog(frame){"
                + "if(!frame||!frame.closest)return;var wrapper=frame.closest('.Details-Iframe-wrapper');if(!wrapper)return;"
                + "var overlay=wrapper.closest('.Details-Iframe'),customFullscreen=!!(overlay&&overlay.classList.contains('pichome-custom-video-fullscreen'));var vm=detailVm(frame),data=null;if(vm){vm.full=customFullscreen;if(!frame.dataset.pichomeAndroidWindowed){"
                + "try{vm.MainFulloverlay=function(){var host=frame.closest&&frame.closest('.Details-Iframe');vm.full=!!(host&&host.classList.contains('pichome-custom-video-fullscreen'));};frame.dataset.pichomeAndroidWindowed='1';}catch(ignore){}}"
                + "data=vm.showdata&&vm.showdata[vm.showdataindex]&&vm.showdata[vm.showdataindex].data;}"
                + "var mediaWidth=data&&parseFloat(data.width),mediaHeight=data&&parseFloat(data.height);"
                + "if(!(mediaWidth>0&&mediaHeight>0)){try{var img=frame.contentDocument.querySelector('.detail-popup-content.center .image-viewer__img.max');"
                + "if(img&&img.naturalWidth&&img.naturalHeight){mediaWidth=img.naturalWidth;mediaHeight=img.naturalHeight;}}catch(ignore){}}"
                + "var ratio=mediaWidth>0&&mediaHeight>0?mediaWidth/mediaHeight:4/3;ratio=Math.max(.18,Math.min(5,ratio));"
                + "var footer=data&&data.footer?60:0;var album=vm&&vm.previews&&vm.previews.data&&vm.previews.data.length?60:0;var chrome=50+footer+album;"
                + "var maxWidth=Math.max(180,window.innerWidth),maxHeight=Math.max(220,window.innerHeight-138);"
                + "var mediaMaxHeight=Math.max(120,maxHeight-chrome),minWidth=Math.min(220,maxWidth);"
                + "var dialogWidth=maxWidth;"
                + "var dialogHeight=Math.min(maxHeight,dialogWidth/ratio+chrome);"
                + "wrapper.style.setProperty('--pichome-dialog-w',Math.round(dialogWidth)+'px');"
                + "wrapper.style.setProperty('--pichome-dialog-h',Math.round(dialogHeight)+'px');"
                + "ensureFrameControls(frame);updateFrameControls(frame);"
                + "}"
                + "function installDetailFrame(frame){"
                + "if(!frame.closest||!frame.closest('.Details-Iframe-wrapper'))return;"
                + "ensureFrameControls(frame);fitDetailDialog(frame);[80,260,700,1400].forEach(function(delay){setTimeout(function(){fitDetailDialog(frame);tryAutoplay(frame.contentDocument);installFrameCss(frame.contentDocument);updateFrameControls(frame);},delay);});"
                + "}"
                + "function hideDownloads(root){"
                + "var scope=root&&root.querySelectorAll?root:document;"
                + "scope.querySelectorAll('a[download],a[href*=\\\"op=download\\\"],"
                + ".el-dropdown-menu__item,button,.el-button,[role=menuitem]').forEach(function(el){"
                + "var text=(el.textContent||'').replace(/\\s+/g,'');"
                + "var title=(el.getAttribute('title')||'').replace(/\\s+/g,'');"
                + "var aria=(el.getAttribute('aria-label')||'').replace(/\\s+/g,'');"
                + "if(text==='下载'||text==='打包下载'||title==='下载'||aria==='下载'||el.hasAttribute('download')"
                + "||(el.getAttribute('href')||'').indexOf('op=download')>=0){"
                + "el.style.setProperty('display','none','important');"
                + "el.setAttribute('aria-hidden','true');"
                + "}"
                + "});"
                + "}"
                + "function installCacheMenuItem(doc){try{"
                + "doc=doc||document;var item=doc.getElementById('pichome-android-cache-menu-item');"
                + "if(!item){var manageLink=doc.querySelector('.el-dropdown-menu a[href*=\"mod=manage\"]');var manageItem=manageLink&&manageLink.closest('li');"
                + "if(!manageItem)return;item=doc.createElement('li');item.id='pichome-android-cache-menu-item';item.className='el-dropdown-menu__item';"
                + "item.setAttribute('role','menuitem');item.setAttribute('tabindex','-1');item.textContent='查看缓存';"
                + "if(manageItem.nextSibling)manageItem.parentNode.insertBefore(item,manageItem.nextSibling);else manageItem.parentNode.appendChild(item);}"
                + "item.style.setProperty('display','flex','important');if(item.dataset.pichomeAndroidCacheBound==='1')return;"
                + "item.dataset.pichomeAndroidCacheBound='1';item.addEventListener('click',function(event){event.preventDefault();"
                + "try{if(window.PichomeCache)window.PichomeCache.openSettings();else alert('缓存服务未初始化，请重启 App');}catch(ignore){alert('无法打开缓存设置，请重启 App');}});"
                + "}catch(ignore){}}"
                + "function installExactDetailOpen(){"
                + "if(window.__pichomeAndroidExactDetailOpen)return;window.__pichomeAndroidExactDetailOpen=true;"
                + "document.addEventListener('dblclick',function(event){try{var card=event.target&&event.target.closest&&event.target.closest('.image-item[data-rid],.imagtable-content[data-rid]');if(!card)return;var office=window.dzzoffice,source=office&&office.WindowThis&&office.WindowThis.ImageDataParam&&office.WindowThis.ImageDataParam.data;if(!source||!source.length)return;var rid=String(card.getAttribute('data-rid')||''),items=Array.prototype.slice.call(source),index=items.findIndex(function(item){return item&&String(item.rid)===rid;});if(index<0||!items[index]||!items[index].dpath)return;var item=items[index],path=String(item.dpath);event.preventDefault();event.stopImmediatePropagation();sessionStorage.setItem('imgs',path);sessionStorage.setItem('selectindex','0');sessionStorage.setItem('detailpath',path);var old=document.querySelector('.Details-Iframe');if(old)old.remove();var overlay=document.createElement('div');overlay.className='Details-Iframe';var wrapper=document.createElement('div');wrapper.className='Details-Iframe-wrapper';var frame=document.createElement('iframe');frame.style.opacity='0';frame.setAttribute('allow','autoplay; fullscreen');frame.src='index.php?mod=details&opentype=current&isadmin=1#path='+path;overlay.addEventListener('click',function(closeEvent){if(closeEvent.target===overlay)overlay.remove();});wrapper.appendChild(frame);overlay.appendChild(wrapper);document.body.appendChild(overlay);lockDetailToFramePath(frame);installFrameCss(overlay);}catch(ignore){}},true);"
                + "}"
                + "function installSidebarGestures(){"
                + "if(window.__pichomeAndroidSidebarGestures)return;"
                + "window.__pichomeAndroidSidebarGestures=true;var gesture=null;"
                + "function sidebarFor(side){return document.querySelector(side==='left'?'#pichome-left-sidebar':'#pichome-right-sidebar');}"
                + "function isOpen(side){var el=sidebarFor(side);return !!(el&&el.classList.contains('mobile-open'));}"
                + "function directionValid(mode,dx){return mode==='open-left'?dx>0:mode==='open-right'?dx<0:mode==='close-left'?dx<0:dx>0;}"
                + "function clearDrag(g){if(!g||!g.sidebar)return;requestAnimationFrame(function(){g.sidebar.style.transition='';g.sidebar.style.transform='';});}"
                + "function touchStart(event){"
                + "if(window.innerWidth>768||event.touches.length!==1)return;"
                + "var touch=event.touches[0],target=event.target,mode='';"
                + "if(isOpen('left')){if(target.closest&&target.closest('#pichome-left-sidebar'))mode='close-left';}"
                + "else if(isOpen('right')){if(target.closest&&target.closest('#pichome-right-sidebar'))mode='close-right';}"
                + "else if(touch.clientX<=40){mode='open-left';}"
                + "else if(touch.clientX>=window.innerWidth-40){mode='open-right';}"
                + "if(!mode)return;var side=mode.indexOf('left')>=0?'left':'right';var sidebar=sidebarFor(side);if(!sidebar)return;"
                + "var now=performance.now();gesture={mode:mode,side:side,sidebar:sidebar,startX:touch.clientX,startY:touch.clientY,startTime:now,lastX:touch.clientX,lastTime:now,active:false};"
                + "}"
                + "function touchMove(event){"
                + "if(!gesture||event.touches.length!==1)return;var touch=event.touches[0];"
                + "var dx=touch.clientX-gesture.startX,dy=touch.clientY-gesture.startY;"
                + "if(!gesture.active){if(Math.abs(dx)<10&&Math.abs(dy)<10)return;"
                + "if(Math.abs(dx)<=Math.abs(dy)*1.15||!directionValid(gesture.mode,dx)){gesture=null;return;}"
                + "gesture.active=true;gesture.sidebar.style.transition='none';}"
                + "event.preventDefault();var width=gesture.sidebar.getBoundingClientRect().width||Math.min(320,window.innerWidth-44);var offset=0;"
                + "if(gesture.mode==='open-left')offset=Math.min(0,-width+Math.max(0,dx));"
                + "else if(gesture.mode==='open-right')offset=Math.max(0,width+Math.min(0,dx));"
                + "else if(gesture.mode==='close-left')offset=Math.max(-width,Math.min(0,dx));"
                + "else offset=Math.min(width,Math.max(0,dx));"
                + "gesture.sidebar.style.transform='translate3d('+offset+'px,0,0)';gesture.lastX=touch.clientX;gesture.lastTime=performance.now();"
                + "}"
                + "function touchEnd(){"
                + "var g=gesture;gesture=null;if(!g||!g.active)return;var dx=g.lastX-g.startX;"
                + "var elapsed=Math.max(1,g.lastTime-(g.startTime||g.lastTime));var velocity=Math.abs(dx)/elapsed;"
                + "var width=g.sidebar.getBoundingClientRect().width||Math.min(320,window.innerWidth-44);"
                + "var commit=Math.abs(dx)>=Math.max(52,width*.22)||velocity>.45;"
                + "if(commit){var opening=g.mode.indexOf('open-')===0;var toggle=document.querySelector('.mobile-sidebar-toggle.'+g.side);"
                + "if(toggle&&((opening&&!isOpen(g.side))||(!opening&&isOpen(g.side))))toggle.click();}"
                + "clearDrag(g);"
                + "}"
                + "function touchCancel(){var g=gesture;gesture=null;clearDrag(g);}"
                + "document.addEventListener('touchstart',touchStart,{passive:true});"
                + "document.addEventListener('touchmove',touchMove,{passive:false});"
                + "document.addEventListener('touchend',touchEnd,{passive:true});"
                + "document.addEventListener('touchcancel',touchCancel,{passive:true});"
                + "}"
                + "function safeInstall(name,fn){try{fn();}catch(error){try{console.error('[PichomeAndroid] '+name,error);}catch(ignore){}}}"
                + "requestNativeVideoFullscreen=requestCustomVideoFullscreen;window.__pichomeExitCustomVideoFullscreen=exitCustomVideoFullscreen;"
                + "if(!window.__pichomeCustomFullscreenResizeBound){window.__pichomeCustomFullscreenResizeBound=true;window.addEventListener('resize',function(){var state=window.__pichomeCustomVideoFullscreenState;if(state)requestAnimationFrame(function(){refreshCustomVideoFullscreenLayout(state,true);});},{passive:true});}"
                + "function installAll(){safeInstall('media',function(){installMediaCss(document);});safeInstall('frames',function(){installFrameCss(document);});safeInstall('downloads',function(){hideDownloads(document);});safeInstall('cache-menu',function(){installCacheMenuItem(document);});safeInstall('detail-open',installExactDetailOpen);safeInstall('sidebars',installSidebarGestures);}"
                + "installAll();[200,700,1600,3200].forEach(function(delay){setTimeout(installAll,delay);});"
                + "if(window.__pichomeAndroidDownloadObserver){window.__pichomeAndroidDownloadObserver.disconnect();}"
                + "window.__pichomeAndroidDownloadObserver=new MutationObserver(function(records){"
                + "records.forEach(function(record){record.addedNodes.forEach(function(node){"
                + "if(node.nodeType===1){safeInstall('new-frames',function(){installFrameCss(node);installFrameCss(node.parentNode);});safeInstall('new-downloads',function(){hideDownloads(node);hideDownloads(node.parentNode);});safeInstall('cache-menu',function(){installCacheMenuItem(document);});}});});"
                + "});"
                + "window.__pichomeAndroidDownloadObserver.observe(document.documentElement,{childList:true,subtree:true});"
                + "})();";
        view.evaluateJavascript(script, null);
    }

    private final class CacheJavascriptBridge {
        @JavascriptInterface
        public void cacheMedia(String url, String cacheIdentity) {
            if (mediaDiskCache == null || url == null) return;
            try {
                mediaDiskCache.prefetch(url, cacheIdentity, appUserAgent);
            } catch (Throwable error) {
                Log.e("PichomeCache", "Unable to prefetch media", error);
            }
        }

        @JavascriptInterface
        public String getCachedMediaUrl(String url, String cacheIdentity) {
            if (mediaDiskCache == null || url == null) return "";
            try {
                return mediaDiskCache.getLocalUrl(url, cacheIdentity);
            } catch (Throwable error) {
                Log.e("PichomeCache", "Unable to resolve cached media", error);
                return "";
            }
        }

        @JavascriptInterface
        public void openSettings() {
            if (mediaDiskCache == null) {
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "缓存服务未初始化，请重新启动 App",
                        Toast.LENGTH_LONG
                ).show());
                return;
            }
            try {
                MediaDiskCache.CacheStats stats = mediaDiskCache.getStats();
                runOnUiThread(() -> showCacheSettingsDialog(stats));
            } catch (Throwable error) {
                Log.e("PichomeCache", "Unable to open cache settings", error);
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "缓存设置暂时不可用，请重新启动 App",
                        Toast.LENGTH_LONG
                ).show());
            }
        }

        @JavascriptInterface
        public void setCustomVideoFullscreen(boolean enabled) {
            runOnUiThread(() -> MainActivity.this.setCustomVideoFullscreen(enabled));
        }
    }

    private void setCustomVideoFullscreen(boolean enabled) {
        if (customVideoFullscreen == enabled) {
            if (enabled) applyCustomFullscreenSystemUi();
            return;
        }
        if (enabled) {
            if (fullscreenView != null) hideFullscreenVideo();
            requestedOrientationBeforeCustomFullscreen = getRequestedOrientation();
            customVideoFullscreen = true;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            if (root != null) root.setPadding(0, 0, 0, 0);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            applyCustomFullscreenSystemUi();
            if (root != null) root.requestApplyInsets();
            return;
        }

        customVideoFullscreen = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        configureWindow();
        applyPhysicalEdgeGestureMode();
        if (root != null) root.requestApplyInsets();
        int restoreOrientation = requestedOrientationBeforeCustomFullscreen;
        requestedOrientationBeforeCustomFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        setRequestedOrientation(restoreOrientation);
    }

    private void applyCustomFullscreenSystemUi() {
        Window window = getWindow();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
            } else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }
        } catch (RuntimeException error) {
            Log.w("PichomeFullscreen", "Unable to apply immersive custom fullscreen", error);
        }
    }

    private void showCacheSettingsDialog(MediaDiskCache.CacheStats stats) {
        if (isFinishing() || isDestroyed()) return;
        if (cacheDialog != null) cacheDialog.dismiss();
        String message = "已缓存：" + formatBytes(stats.totalBytes)
                + " / " + formatBytes(stats.maxBytes)
                + "\n缓存素材：" + stats.itemCount + " 个"
                + (stats.pendingCount > 0
                ? "\n正在缓存：" + stats.pendingCount + " 个（已下载 "
                + formatBytes(stats.pendingBytes) + "）"
                : "")
                + "\n\n诊断：发现 " + stats.discoveredCount
                + " / 启动 " + stats.startedCount
                + " / 完成 " + stats.completedCount
                + " / 失败 " + stats.failedCount
                + " / 本地播放 " + stats.localPlaybackCount
                + "\n最后状态：" + stats.lastDiagnostic
                + "\n\n图片会在预览时自动缓存；视频首次预览时在后台补全。";
        cacheDialog = new AlertDialog.Builder(this)
                .setTitle("素材缓存")
                .setMessage(message)
                .setPositiveButton("设置容量", (dialog, which) -> showCacheCapacityDialog(stats.maxBytes))
                .setNeutralButton("清理缓存", (dialog, which) -> confirmClearCache())
                .setNegativeButton("关闭", null)
                .create();
        cacheDialog.setOnDismissListener(dialog -> cacheDialog = null);
        cacheDialog.show();
    }

    private void showCacheCapacityDialog(long currentBytes) {
        final long gib = 1024L * 1024L * 1024L;
        final long[] values = {
                512L * 1024L * 1024L,
                gib,
                2L * gib,
                4L * gib,
                8L * gib,
                16L * gib
        };
        final String[] labels = {"512 MB", "1 GB", "2 GB", "4 GB", "8 GB", "16 GB"};
        int selected = 0;
        for (int index = 0; index < values.length; index++) {
            if (values[index] == currentBytes) {
                selected = index;
                break;
            }
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("缓存容量上限")
                .setSingleChoiceItems(labels, selected, null)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    int position = dialog.getListView().getCheckedItemPosition();
                    if (position < 0 || position >= values.length || mediaDiskCache == null) return;
                    long selectedBytes = values[position];
                    dialog.dismiss();
                    mediaDiskCache.setMaxCacheBytes(selectedBytes, () -> runOnUiThread(() -> {
                        Toast.makeText(
                                MainActivity.this,
                                "缓存容量已设置为 " + formatBytes(selectedBytes),
                                Toast.LENGTH_SHORT
                        ).show();
                    }));
                }));
        dialog.show();
    }

    private void confirmClearCache() {
        new AlertDialog.Builder(this)
                .setTitle("清理素材缓存？")
                .setMessage("只删除 App 自动缓存的图片和视频，不会删除服务器素材或登录信息。")
                .setPositiveButton("清理", (dialog, which) -> {
                    if (mediaDiskCache == null) return;
                    mediaDiskCache.clearAll(() -> runOnUiThread(() -> {
                        if (webView != null) webView.clearCache(true);
                        Toast.makeText(
                                MainActivity.this,
                                "素材缓存已清理",
                                Toast.LENGTH_SHORT
                        ).show();
                    }));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.CHINA, value >= 100 || unit == 0 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || pendingFileCallback == null) {
            return;
        }
        Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        pendingFileCallback.onReceiveValue(result);
        pendingFileCallback = null;
    }

    @Override
    public void onBackPressed() {
        handleBackAction();
    }

    private void handleBackAction() {
        if (fullscreenView != null) {
            hideFullscreenVideo();
            return;
        }
        if (webView == null) return;
        if (customVideoFullscreen) {
            webView.evaluateJavascript(
                    "(function(){try{return !!(window.__pichomeExitCustomVideoFullscreen&&window.__pichomeExitCustomVideoFullscreen());}catch(ignore){return false;}})();",
                    result -> {
                        if (!"true".equals(result)) setCustomVideoFullscreen(false);
                    }
            );
            return;
        }
        String closeOverlayScript = "(function(){"
                + "var mask=document.querySelector('.mobile-sidebar-mask');"
                + "if(mask){mask.click();return true;}"
                + "var details=document.querySelector('.Details-Iframe');"
                + "if(details){details.remove();return true;}"
                + "return false;"
                + "})();";
        webView.evaluateJavascript(closeOverlayScript, result -> {
            if (!"true".equals(result) && webView.canGoBack()) {
                webView.goBack();
            }
        });
    }

    private void hideFullscreenVideo() {
        if (fullscreenView == null) return;
        root.removeView(fullscreenView);
        fullscreenView = null;
        webView.setVisibility(View.VISIBLE);
        webView.evaluateJavascript(
                "(function(){var docs=[];function collect(doc){if(!doc||docs.indexOf(doc)>=0)return;docs.push(doc);doc.querySelectorAll('iframe').forEach(function(frame){try{collect(frame.contentDocument);}catch(ignore){}});}collect(document);docs.forEach(function(doc){if(doc.documentElement)doc.documentElement.classList.add('pichome-custom-video-controls-doc');doc.querySelectorAll('video').forEach(function(video){delete video.dataset.pichomeNativeFullscreen;video.controls=false;video.removeAttribute('controls');});});})();",
                null
        );
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        configureWindow();
        applyPhysicalEdgeGestureMode();
        root.requestApplyInsets();
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
        int restoreOrientation = requestedOrientationBeforeFullscreen;
        requestedOrientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        setRequestedOrientation(restoreOrientation);
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        if (customVideoFullscreen) {
            applyCustomFullscreenSystemUi();
        } else if (fullscreenView == null && root != null) {
            applyPhysicalEdgeGestureMode();
            if (android.os.Build.VERSION.SDK_INT >= 29 && root != null) {
                root.post(this::updateSystemGestureExclusion);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && customVideoFullscreen) {
            applyCustomFullscreenSystemUi();
        } else if (hasFocus && fullscreenView == null && root != null) {
            applyPhysicalEdgeGestureMode();
            if (android.os.Build.VERSION.SDK_INT >= 29 && root != null) {
                root.post(this::updateSystemGestureExclusion);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && backInvokedCallback != null) {
            unregisterSystemBackHandler();
        }
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        if (cacheDialog != null) {
            cacheDialog.dismiss();
            cacheDialog = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        if (mediaDiskCache != null) {
            mediaDiskCache.shutdown();
            mediaDiskCache = null;
        }
        super.onDestroy();
    }

    @android.annotation.TargetApi(33)
    private void unregisterSystemBackHandler() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                (android.window.OnBackInvokedCallback) backInvokedCallback
        );
        backInvokedCallback = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
