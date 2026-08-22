package top.lempet.pichome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
            if (fullscreenView != null) {
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
            fullscreenView = view;
            fullscreenCallback = callback;
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
        String script = "(function(){"
                + "document.documentElement.classList.add('pichome-android-app');"
                + "var mediaCss='"
                + ".Details-Iframe{inset:0!important;width:100%!important;height:100%!important;padding:12px 0 126px!important;align-items:center!important;justify-content:center!important;background:rgba(12,13,16,.62)!important;backdrop-filter:blur(8px) saturate(120%);-webkit-backdrop-filter:blur(8px) saturate(120%);}"
                + ".Details-Iframe .Details-Iframe-wrapper{position:relative!important;flex:0 0 auto!important;width:var(--pichome-dialog-w,100%)!important;height:var(--pichome-dialog-h,min(70dvh,620px))!important;max-width:100%!important;max-height:calc(100% - 138px)!important;border-radius:0!important;overflow:hidden!important;box-shadow:0 18px 54px rgba(0,0,0,.42)!important;transition:width .18s ease-out,height .18s ease-out!important;}"
                + ".Details-Iframe .Details-Iframe-wrapper>iframe{position:absolute!important;inset:0!important;display:block!important;width:100%!important;height:100%!important;border-radius:0!important;opacity:1!important;transition:opacity .18s ease-out!important;}"
                + ".pichome-detail-nav{position:fixed;left:50%;bottom:max(12px,env(safe-area-inset-bottom));z-index:10002;display:flex;gap:18px;transform:translateX(-50%);pointer-events:auto;}"
                + ".pichome-detail-nav[hidden]{display:none!important;}"
                + ".pichome-detail-nav button{display:flex;align-items:center;justify-content:center;width:144px;max-width:calc((100vw - 42px)/2);height:96px;margin:0;padding:0;border:1px solid rgba(255,255,255,.22);border-radius:22px;background:rgba(38,39,43,.88);color:#fff;font:600 32px/1 system-ui,sans-serif;box-shadow:0 8px 24px rgba(0,0,0,.3);backdrop-filter:blur(16px) saturate(150%);-webkit-backdrop-filter:blur(16px) saturate(150%);touch-action:none;-webkit-tap-highlight-color:transparent;}"
                + ".pichome-detail-nav button:active:not(:disabled){transform:scale(.94);background:rgba(64,65,70,.96);}"
                + ".pichome-detail-nav button:disabled{opacity:.3;}"
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
                + "function trackFrameDuration(video){if(!video||typeof video.requestVideoFrameCallback!=='function'||video.dataset.pichomeFrameTracker)return;video.dataset.pichomeFrameTracker='1';var previous=-1;function sample(now,metadata){var mediaTime=metadata&&metadata.mediaTime;if(isFinite(mediaTime)&&previous>=0){var duration=mediaTime-previous;if(duration>=1/240&&duration<=1)video.dataset.pichomeFrameDuration=String(duration);}if(isFinite(mediaTime))previous=mediaTime;video.requestVideoFrameCallback(sample);}video.requestVideoFrameCallback(sample);}"
                + "function detailFrameRate(frame,video){var measured=parseFloat(video.dataset.pichomeFrameDuration);if(isFinite(measured)&&measured>0)return 1/measured;var rate=parseFrameRate(video.getAttribute('data-fps')||video.dataset.fps||video.dataset.frameRate);if(rate)return rate;try{var vm=detailVm(frame),data=vm&&vm.showdata&&vm.showdata[vm.showdataindex]&&vm.showdata[vm.showdataindex].data;rate=parseFrameRate(data&&(data.fps||data.frame_rate||data.framerate));}catch(ignore){}return rate||30;}"
                + "function updateFrameControls(frame){var overlay=frame&&frame.closest&&frame.closest('.Details-Iframe'),nav=overlay&&overlay.querySelector('.pichome-detail-nav');if(!nav)return;var video=currentDetailVideo(frame);nav.hidden=!video;nav.querySelectorAll('button').forEach(function(button){button.disabled=!video||video.readyState<1;});if(video){trackFrameDuration(video);if(!video.dataset.pichomeFrameControlsReady){video.dataset.pichomeFrameControlsReady='1';video.addEventListener('loadedmetadata',function(){updateFrameControls(frame);});video.addEventListener('durationchange',function(){updateFrameControls(frame);});}}}"
                + "function keepFrameUiHidden(video){if(!video||video.dataset.pichomeFrameMode!=='1')return;var doc=video.ownerDocument,root=video.closest&&video.closest('.xgplayer-skin-default');if(doc&&doc.documentElement)doc.documentElement.classList.add('pichome-frame-stepping-doc');if(root)root.classList.add('pichome-frame-stepping');video.controls=false;}"
                + "function showPlayerUi(video){if(!video)return;var doc=video.ownerDocument,root=video.closest&&video.closest('.xgplayer-skin-default'),win=doc&&doc.defaultView;delete video.dataset.pichomeFrameMode;if(doc&&doc.documentElement)doc.documentElement.classList.remove('pichome-frame-stepping-doc');if(root){root.classList.remove('pichome-frame-stepping');root.classList.remove('xgplayer-inactive');}try{var player=win&&win.player;if(player&&typeof player.emit==='function')player.emit('focus');}catch(ignore){}}"
                + "function installFrameUiTap(video){var root=video&&video.closest&&video.closest('.xgplayer-skin-default');if(!root||root.dataset.pichomeFrameUiTap)return;root.dataset.pichomeFrameUiTap='1';root.addEventListener('pointerdown',function(event){if(video.dataset.pichomeFrameMode!=='1'||event.button!==undefined&&event.button!==0)return;showPlayerUi(video);},true);}"
                + "function enterFrameMode(video){var doc=video.ownerDocument,win=doc&&doc.defaultView;installFrameUiTap(video);video.dataset.pichomeFrameMode='1';video.autoplay=false;keepFrameUiHidden(video);video.pause();keepFrameUiHidden(video);if(win)win.__pichomeAndroidAutoplayPlaying=true;return video.closest&&video.closest('.xgplayer-skin-default');}"
                + "function clampedSeekTime(video,target){var duration=video.duration;if(isFinite(duration))target=Math.min(duration,target);target=Math.max(0,target);try{var ranges=video.seekable,current=video.currentTime;for(var i=0;i<ranges.length;i++){var start=ranges.start(i),end=ranges.end(i);if(current>=start-.05&&current<=end+.05)return Math.max(start,Math.min(end,target));}}catch(ignore){}return target;}"
                + "function seekOneFrame(frame,video,direction){var fps=detailFrameRate(frame,video),step=1/fps,base=parseFloat(video.dataset.pichomeFrameTarget);if(!isFinite(base)||Math.abs(base-video.currentTime)>Math.max(.5,step*4))base=video.currentTime;var target=clampedSeekTime(video,base+direction*step);video.dataset.pichomeFrameTarget=String(target);video.currentTime=target;}"
                + "function stepCurrentVideo(frame,direction){var video=currentDetailVideo(frame);if(!video||video.readyState<1||video.dataset.pichomeFrameAdvancing==='1')return false;try{enterFrameMode(video);if(direction>0&&typeof video.requestVideoFrameCallback==='function'){var token=String((parseInt(video.dataset.pichomeFrameToken)||0)+1);video.dataset.pichomeFrameToken=token;video.dataset.pichomeFrameAdvancing='1';var muted=video.muted;video.muted=true;video.requestVideoFrameCallback(function(now,metadata){if(video.dataset.pichomeFrameToken!==token)return;video.pause();video.muted=muted;delete video.dataset.pichomeFrameAdvancing;if(metadata&&isFinite(metadata.mediaTime)){video.dataset.pichomeFrameTarget=String(metadata.mediaTime);video.dataset.pichomeFrameDuration=video.dataset.pichomeFrameDuration||String(1/detailFrameRate(frame,video));}});var promise=video.play();if(promise&&promise.catch)promise.catch(function(){video.muted=muted;delete video.dataset.pichomeFrameAdvancing;seekOneFrame(frame,video,1);});}else{seekOneFrame(frame,video,direction);}return true;}catch(ignore){return false;}}"
                + "function stopContinuousFrame(){var state=window.__pichomeFrameHold;if(!state)return;window.__pichomeFrameHold=null;if(state.raf)cancelAnimationFrame(state.raf);var video=state.video;try{keepFrameUiHidden(video);video.pause();keepFrameUiHidden(video);video.muted=state.muted;video.playbackRate=state.rate;delete video.dataset.pichomeFrameHolding;delete video.dataset.pichomeFrameAdvancing;video.dataset.pichomeFrameTarget=String(video.currentTime);}catch(ignore){}}"
                + "function startContinuousFrame(frame,direction){var video=currentDetailVideo(frame);if(!video||video.readyState<1)return false;stopContinuousFrame();enterFrameMode(video);video.dataset.pichomeFrameToken=String((parseInt(video.dataset.pichomeFrameToken)||0)+1);video.dataset.pichomeFrameHolding='1';var state={video:video,direction:direction,muted:video.muted,rate:video.playbackRate,raf:0};window.__pichomeFrameHold=state;video.muted=true;video.playbackRate=1;if(direction>0){var promise=video.play();if(promise&&promise.catch)promise.catch(function(){if(window.__pichomeFrameHold!==state)return;var origin=video.currentTime,started=performance.now(),last=0;function forward(now){if(window.__pichomeFrameHold!==state)return;if(now-last>=1000/30){last=now;video.currentTime=clampedSeekTime(video,origin+(now-started)/1000);}state.raf=requestAnimationFrame(forward);}state.raf=requestAnimationFrame(forward);});}else{video.pause();var origin=video.currentTime,started=performance.now(),last=0;function reverse(now){if(window.__pichomeFrameHold!==state)return;if(now-last>=1000/30){last=now;video.currentTime=clampedSeekTime(video,origin-(now-started)/1000);}state.raf=requestAnimationFrame(reverse);}state.raf=requestAnimationFrame(reverse);}return true;}"
                + "function ensureFrameControls(frame){"
                + "var overlay=frame&&frame.closest&&frame.closest('.Details-Iframe');if(!overlay)return;var nav=overlay.querySelector('.pichome-detail-nav');"
                + "if(!nav){nav=document.createElement('div');nav.className='pichome-detail-nav';nav.hidden=true;nav.setAttribute('role','group');nav.setAttribute('aria-label','逐帧预览');"
                + "[-1,1].forEach(function(direction){var button=document.createElement('button');button.type='button';button.textContent=direction<0?'←':'→';button.setAttribute('aria-label',direction<0?'后退一帧':'前进一帧');button.title=direction<0?'后退一帧':'前进一帧';"
                + "var holdTimer=0,holding=false,lastPointerUp=0;button.addEventListener('pointerdown',function(event){if(button.disabled||event.button!==undefined&&event.button!==0)return;event.preventDefault();event.stopPropagation();holding=false;try{button.setPointerCapture(event.pointerId);}catch(ignore){}clearTimeout(holdTimer);holdTimer=setTimeout(function(){holding=true;startContinuousFrame(frame,direction);},280);});"
                + "button.addEventListener('pointerup',function(event){event.preventDefault();event.stopPropagation();clearTimeout(holdTimer);lastPointerUp=Date.now();if(holding){stopContinuousFrame();holding=false;}else if(!button.disabled){stepCurrentVideo(frame,direction);}try{button.releasePointerCapture(event.pointerId);}catch(ignore){}});"
                + "function cancelHold(event){clearTimeout(holdTimer);if(holding){stopContinuousFrame();holding=false;}if(event)event.stopPropagation();}button.addEventListener('pointercancel',cancelHold);button.addEventListener('lostpointercapture',cancelHold);button.addEventListener('click',function(event){event.preventDefault();event.stopPropagation();if(event.detail===0&&Date.now()-lastPointerUp>500&&!button.disabled)stepCurrentVideo(frame,direction);});nav.appendChild(button);});"
                + "overlay.appendChild(nav);}updateFrameControls(frame);"
                + "}"
                + "function fitDetailDialog(frame){"
                + "if(!frame||!frame.closest)return;var wrapper=frame.closest('.Details-Iframe-wrapper');if(!wrapper)return;"
                + "var vm=detailVm(frame),data=null;if(vm){vm.full=false;if(!frame.dataset.pichomeAndroidWindowed){"
                + "try{vm.MainFulloverlay=function(){vm.full=false;};frame.dataset.pichomeAndroidWindowed='1';}catch(ignore){}}"
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
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        configureWindow();
        applyPhysicalEdgeGestureMode();
        root.requestApplyInsets();
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
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
        if (fullscreenView == null && root != null) {
            applyPhysicalEdgeGestureMode();
            if (android.os.Build.VERSION.SDK_INT >= 29 && root != null) {
                root.post(this::updateSystemGestureExclusion);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreenView == null && root != null) {
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
