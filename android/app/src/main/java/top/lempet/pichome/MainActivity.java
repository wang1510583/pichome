package top.lempet.pichome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SafeBrowsingResponse;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.Arrays;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://pichome.lempet.top:5575/";
    private static final String APP_ACCESS_TAG = BuildConfig.PICHOME_APP_ACCESS_TAG;
    private static final int FILE_CHOOSER_REQUEST = 1201;
    private static final int SYSTEM_BAR_COLOR = Color.rgb(48, 49, 53);

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progressBar;
    private View statusBarScrim;
    private View navigationBarScrim;
    private ValueCallback<Uri[]> pendingFileCallback;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private Object backInvokedCallback;
    private float previewSwipeStartX;
    private float previewSwipeStartY;

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
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        String appUserAgent = settings.getUserAgentString() + " PichomeAndroid/1.0";
        if (!APP_ACCESS_TAG.isEmpty()) {
            appUserAgent += " " + APP_ACCESS_TAG;
        }
        settings.setUserAgentString(appUserAgent);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new PichomeWebViewClient());
        webView.setWebChromeClient(new PichomeWebChromeClient());
        webView.setOnTouchListener((view, event) -> handlePreviewSwipe(event));
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                Toast.makeText(this, "当前版本未启用下载功能", Toast.LENGTH_SHORT).show()
        );
    }

    private boolean handlePreviewSwipe(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            previewSwipeStartX = event.getX();
            previewSwipeStartY = event.getY();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float deltaX = event.getX() - previewSwipeStartX;
            float deltaY = event.getY() - previewSwipeStartY;
            if (Math.abs(deltaY) >= dp(64) && Math.abs(deltaY) > Math.abs(deltaX) * 1.25f) {
                int direction = deltaY < 0 ? 1 : -1;
                float normalizedX = previewSwipeStartX / Math.max(1, webView.getWidth());
                float normalizedY = previewSwipeStartY / Math.max(1, webView.getHeight());
                String script = "window.__pichomeAndroidSwitchDetail&&window.__pichomeAndroidSwitchDetail("
                        + direction + "," + normalizedX + "," + normalizedY + ");";
                webView.evaluateJavascript(script, null);
            }
        }
        return false;
    }

    private final class PichomeWebViewClient extends WebViewClient {
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
                + ".Details-Iframe{inset:0!important;width:100%!important;height:100%!important;padding:12px!important;align-items:center!important;justify-content:center!important;background:rgba(12,13,16,.62)!important;backdrop-filter:blur(8px) saturate(120%);-webkit-backdrop-filter:blur(8px) saturate(120%);}"
                + ".Details-Iframe .Details-Iframe-wrapper{flex:0 0 auto!important;width:var(--pichome-dialog-w,calc(100% - 24px))!important;height:var(--pichome-dialog-h,min(70dvh,620px))!important;max-width:calc(100% - 24px)!important;max-height:calc(100% - 24px)!important;border-radius:18px!important;overflow:hidden!important;box-shadow:0 18px 54px rgba(0,0,0,.42)!important;}"
                + ".Details-Iframe .Details-Iframe-wrapper>iframe{display:block!important;width:100%!important;height:100%!important;border-radius:18px!important;}"
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
                + "@media(max-width:768px){html.pichome-android-app .mobile-sidebar-toggle{display:none!important;}}"
                + "';"
                + "function installMediaCss(doc){"
                + "if(!doc||doc.getElementById('pichome-android-media-css'))return;"
                + "var style=doc.createElement('style');style.id='pichome-android-media-css';style.textContent=mediaCss;"
                + "(doc.head||doc.documentElement).appendChild(style);"
                + "try{var win=doc.defaultView;if(win&&doc!==document&&!win.__pichomeAndroidDocObserver){"
                + "win.__pichomeAndroidDocObserver=new win.MutationObserver(function(){installFrameCss(doc);hideDownloads(doc);try{fitDetailDialog(win.frameElement);}catch(ignore){}});"
                + "win.__pichomeAndroidDocObserver.observe(doc.documentElement,{childList:true,subtree:true});}}catch(ignore){}"
                + "}"
                + "function installFrameCss(root){"
                + "var scope=root&&root.querySelectorAll?root:document;"
                + "scope.querySelectorAll('iframe').forEach(function(frame){"
                + "function apply(){try{installMediaCss(frame.contentDocument);installFrameCss(frame.contentDocument);hideDownloads(frame.contentDocument);installDetailFrame(frame);}catch(ignore){}}"
                + "if(!frame.dataset.pichomeAndroidAdaptive){frame.dataset.pichomeAndroidAdaptive='1';frame.addEventListener('load',apply);}"
                + "apply();"
                + "});"
                + "}"
                + "function detailVm(frame){try{var root=frame.contentDocument&&frame.contentDocument.querySelector('#dzzoffice');"
                + "var app=root&&root.__vue_app__;return app&&app._instance&&app._instance.proxy;}catch(ignore){return null;}}"
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
                + "var maxWidth=Math.max(180,window.innerWidth-24),maxHeight=Math.max(220,window.innerHeight-24);"
                + "var mediaMaxHeight=Math.max(120,maxHeight-chrome),minWidth=Math.min(220,maxWidth);"
                + "var dialogWidth=Math.min(maxWidth,Math.max(minWidth,mediaMaxHeight*ratio));"
                + "var dialogHeight=Math.min(maxHeight,dialogWidth/ratio+chrome);"
                + "wrapper.style.setProperty('--pichome-dialog-w',Math.round(dialogWidth)+'px');"
                + "wrapper.style.setProperty('--pichome-dialog-h',Math.round(dialogHeight)+'px');"
                + "}"
                + "function installDetailFrame(frame){"
                + "if(!frame.closest||!frame.closest('.Details-Iframe-wrapper'))return;"
                + "fitDetailDialog(frame);[80,260,700,1400].forEach(function(delay){setTimeout(function(){fitDetailDialog(frame);},delay);});"
                + "}"
                + "window.__pichomeAndroidSwitchDetail=function(delta,normalizedX,normalizedY){"
                + "var wrapper=document.querySelector('.Details-Iframe-wrapper');if(!wrapper)return false;"
                + "var rect=wrapper.getBoundingClientRect(),x=normalizedX*window.innerWidth,y=normalizedY*window.innerHeight;"
                + "if(x<rect.left||x>rect.right||y<rect.top||y>rect.bottom)return false;"
                + "var frame=wrapper.querySelector('iframe'),vm=frame&&detailVm(frame);if(!vm||!vm.paths)return false;"
                + "var next=parseInt(vm.ridIndex)+delta;if(next<0||next>=vm.paths.length)return false;"
                + "vm.full=false;vm.ridIndex=next;Promise.resolve(vm.CommonOrder()).finally(function(){"
                + "[0,120,420,900].forEach(function(delay){setTimeout(function(){fitDetailDialog(frame);},delay);});});return true;"
                + "};"
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
                + "installMediaCss(document);installFrameCss(document);hideDownloads(document);installSidebarGestures();"
                + "if(window.__pichomeAndroidDownloadObserver){window.__pichomeAndroidDownloadObserver.disconnect();}"
                + "window.__pichomeAndroidDownloadObserver=new MutationObserver(function(records){"
                + "records.forEach(function(record){record.addedNodes.forEach(function(node){"
                + "if(node.nodeType===1){installFrameCss(node);installFrameCss(node.parentNode);hideDownloads(node);hideDownloads(node.parentNode);}});});"
                + "});"
                + "window.__pichomeAndroidDownloadObserver.observe(document.documentElement,{childList:true,subtree:true});"
                + "})();";
        view.evaluateJavascript(script, null);
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
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
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
