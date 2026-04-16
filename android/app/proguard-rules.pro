# Keep JavascriptInterface methods — ProGuard would strip @JavascriptInterface annotated
# methods by default since they are called from outside the app (from the WebView JS engine).
-keepclassmembers class com.hotstar.watchparty.MainActivity$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
