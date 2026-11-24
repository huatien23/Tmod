package com.termux.app.activities;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

/** Local HTML, SVG and Markdown preview for the Tmod editor. */
public final class TmodPreviewActivity extends AppCompatActivity {
    public static final String EXTRA_TITLE = "com.huatiencode.preview.TITLE";
    public static final String EXTRA_CONTENT = "com.huatiencode.preview.CONTENT";
    public static final String EXTRA_BASE_URL = "com.huatiencode.preview.BASE_URL";
    public static final String EXTRA_MARKDOWN = "com.huatiencode.preview.MARKDOWN";
    private WebView mWebView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(getIntent().getStringExtra(EXTRA_TITLE));
        mWebView = new WebView(this);
        setContentView(mWebView);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        mWebView.setWebViewClient(new WebViewClient());
        String content = getIntent().getStringExtra(EXTRA_CONTENT);
        if (content == null) content = "";
        if (getIntent().getBooleanExtra(EXTRA_MARKDOWN, false)) content = markdown(content);
        mWebView.loadDataWithBaseURL(getIntent().getStringExtra(EXTRA_BASE_URL), content, "text/html", "UTF-8", null);
    }

    @Override public void onBackPressed() {
        if (mWebView.canGoBack()) mWebView.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (mWebView != null) mWebView.destroy();
        super.onDestroy();
    }

    private static String markdown(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<style>body{background:#1e1e1e;color:#ddd;font:16px sans-serif;padding:18px;line-height:1.55}"
            + "pre{white-space:pre-wrap;font-family:monospace}</style><pre>" + escaped + "</pre>";
    }
}
