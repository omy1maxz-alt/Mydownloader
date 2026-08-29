1. **Add `decodeBase64Iframe` method to JavascriptInterfaces**:
   - In `MainActivity.kt`, add a new method `@JavascriptInterface fun onBase64IframeFound(base64Str: String)` inside `MediaStateInterface`.
   - This method will decode the string using `android.util.Base64`, extract the `src` URL using Regex, and then navigate the `webView` to the extracted URL on the UI thread (`runOnUiThread { webView.loadUrl(extractedUrl) }`).

2. **Update `injectAdvancedMediaDetector` JS script**:
   - Add a function to scan for `<select class="mirror">` or similar elements containing Base64 iframe strings.
   - If found, it reads the `.value` of the selected option and sends it back to Android via `window.AndroidMediaState.onBase64IframeFound(value)`.

3. **Verify the logic**:
   - Check if `MediaStateInterface` needs any explicit access to `webView`. It holds a reference to `activity` so it can call `activity.binding.webView.loadUrl(url)` or `activity.runOnUiThread`.

4. **Complete pre-commit steps**:
   - Test compilation, execute test suite, get review, and finalize.
