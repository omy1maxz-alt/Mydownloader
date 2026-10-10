with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# The error "Uncaught SyntaxError: Unexpected end of input" happens when `evaluateJavascript` string has unescaped characters, or fails to parse properly, OR the script is too large/gets truncated.
# Actually, the best way to inject a script file robustly in Android WebView without parsing issues is to inject a <script> tag pointing to a blob or base64, or just safely wrap it.
# Another common issue is that `evaluateJavascript` fails if the string contains a backslash that gets evaluated as an escape character and truncates the script.
# Wait, `removemark_doubao.js` is a static file, we read it as a String via Charsets.UTF_8.
# If it has backticks ` `, it might cause issues depending on how Android evaluates it. But `evaluateJavascript` evaluates raw strings, not interpolated ones unless it's wrapped.
# Let's wrap it properly, or encode it as Base64 to guarantee NO syntax errors from string evaluation.

injection_code = """    private fun injectDoubaoIntegration(view: WebView?) {
        try {
            val inputStream = assets.open("removemark_doubao.js")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            val scriptBase64 = android.util.Base64.encodeToString(buffer, android.util.Base64.NO_WRAP)

            // Inject via script tag to avoid syntax parsing truncation limits of evaluateJavascript
            val loaderScript = "(function() {" +
                    "if (document.getElementById('doubao-injector')) return;" +
                    "var script = document.createElement('script');" +
                    "script.id = 'doubao-injector';" +
                    "script.type = 'text/javascript';" +
                    "script.innerHTML = decodeURIComponent(escape(window.atob('" + scriptBase64 + "')));" +
                    "document.head.appendChild(script);" +
                    "console.log('[Doubao] Android injected script tag successfully');" +
                    "})();"

            view?.evaluateJavascript(loaderScript, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }"""

# Replace the existing function
content = content.replace("""    private fun injectDoubaoIntegration(view: WebView?) {
        try {
            val inputStream = assets.open("removemark_doubao.js")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            val script = String(buffer, Charsets.UTF_8)
            view?.evaluateJavascript(script, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }""", injection_code)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)
