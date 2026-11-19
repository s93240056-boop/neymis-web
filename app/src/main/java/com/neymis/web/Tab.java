package com.neymis.web;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.URLEncoder;

/** Одна вкладка: свой WebView, своя история, своё состояние страницы. */
final class Tab {

    interface Callback {
        void onTitleChanged(Tab tab, String title);

        void onUrlChanged(Tab tab, String url);

        void onProgress(Tab tab, int progress);

        void onPageFinished(Tab tab);

        void onDownload(String url, String userAgent, String disposition, String mimeType);

        void onFileChooser(ValueCallback<Uri[]> callback);

        void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback);

        void onHideCustomView();
    }

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    final WebView view;
    final boolean incognito;
    final long id;

    String title = "Новая вкладка";
    String url = "";

    private final Callback callback;
    private boolean erudaEnabled;

    Tab(Context context, boolean incognito, boolean desktop, boolean eruda, Callback callback) {
        this.incognito = incognito;
        this.callback = callback;
        this.id = System.currentTimeMillis() + (long) (Math.random() * 1000);
        this.erudaEnabled = eruda;

        view = new WebView(context);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(incognito ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_DEFAULT);
        applyDesktop(desktop);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView webView, String address, Bitmap favicon) {
                url = address;
                callback.onUrlChanged(Tab.this, address);
            }

            @Override
            public void onPageFinished(WebView webView, String address) {
                url = address;
                callback.onUrlChanged(Tab.this, address);
                callback.onPageFinished(Tab.this);
                injectEruda();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, String address) {
                return address.startsWith("tel:") || address.startsWith("mailto:")
                        || address.startsWith("sms:");
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView webView, int progress) {
                callback.onProgress(Tab.this, progress);
            }

            @Override
            public void onReceivedTitle(WebView webView, String received) {
                title = received == null || received.isEmpty() ? url : received;
                callback.onTitleChanged(Tab.this, title);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                callback.onFileChooser(filePathCallback);
                return true;
            }

            @Override
            public void onShowCustomView(View customView, CustomViewCallback customViewCallback) {
                callback.onShowCustomView(customView, customViewCallback);
            }

            @Override
            public void onHideCustomView() {
                callback.onHideCustomView();
            }
        });

        view.setDownloadListener((address, userAgent, disposition, mimeType, length) ->
                callback.onDownload(address, userAgent, disposition, mimeType));
    }

    void load(String address) {
        String normalized = normalize(address);
        if (normalized == null) return;
        url = normalized;
        view.loadUrl(normalized);
    }

    void reload() {
        view.reload();
    }

    void stop() {
        view.stopLoading();
    }

    void setEruda(boolean enabled) {
        erudaEnabled = enabled;
        if (enabled) injectEruda();
    }

    void applyDesktop(boolean desktop) {
        WebSettings settings = view.getSettings();
        settings.setUserAgentString(desktop ? DESKTOP_UA : null);
        settings.setUseWideViewPort(desktop);
        settings.setLoadWithOverviewMode(desktop);
    }

    private void injectEruda() {
        if (!erudaEnabled) return;
        String script = "(function(){"
                + "if(window.eruda){return;}"
                + "var s=document.createElement('script');"
                + "s.src='https://cdn.jsdelivr.net/npm/eruda';"
                + "s.onload=function(){try{eruda.init()}catch(e){}};"
                + "(document.head||document.documentElement).appendChild(s);"
                + "})();";
        try {
            view.evaluateJavascript(script, null);
        } catch (Throwable ignored) {
        }
    }

    void destroy() {
        if (incognito) {
            try {
                view.clearCache(true);
                CookieManager.getInstance().removeAllCookies(null);
            } catch (Throwable ignored) {
            }
        }
        try {
            view.destroy();
        } catch (Throwable ignored) {
        }
    }

    static String normalize(String input) {
        if (input == null) return null;
        String text = input.trim();
        if (text.isEmpty()) return null;
        String lower = text.toLowerCase();
        if (lower.startsWith("file:///") || lower.startsWith("data:")
                || lower.startsWith("about:")) return text;
        if (lower.startsWith("http://") || lower.startsWith("https://")) return text;
        if (text.contains(".") && !text.contains(" ")) return "https://" + text;
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            return "https://www.google.com/search?q=" + text.replace(" ", "+");
        }
    }
}
