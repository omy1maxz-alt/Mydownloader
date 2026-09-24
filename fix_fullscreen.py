import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# The system UI visibility listener is trying to enforce immersive sticky, but the logic seems flawed or incomplete,
# or simply touching the screen triggers the listener, which natively exits fullscreen.
# Actually, the problem is likely that touching the screen natively fires `onHideCustomView()` from the WebView itself
# if it's not truly using immersive sticky, or some code explicitly exits it.

# Wait, let's look at `window.decorView.setOnSystemUiVisibilityChangeListener`.
# If `visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0`, it re-applies the flags.
# BUT what if the system triggers `onHideCustomView`?
# Let's inspect where `webView.webChromeClient?.onHideCustomView()` is explicitly called manually in MainActivity.

old_onBackPressed = """        } else if (currentTabIndex in tabs.indices && tabs[currentTabIndex].historyStack.size > 1) {"""

new_onBackPressed = """        } else if (fullscreenView != null) {
            webView.webChromeClient?.onHideCustomView()
        } else if (currentTabIndex in tabs.indices && tabs[currentTabIndex].historyStack.size > 1) {"""

content = content.replace(old_onBackPressed, new_onBackPressed)


old_showcustomview = """                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
                    window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                        if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0 && fullscreenView != null) {
                            window.decorView.systemUiVisibility = (
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                            )
                        }
                    }"""

new_showcustomview = """                    // Use modern WindowInsetsControllerCompat if possible to prevent brittle deprecated flag behavior
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
                        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    } else {
                        window.decorView.systemUiVisibility = (
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                        )
                    }

                    // DO NOT attach a setOnSystemUiVisibilityChangeListener that forces state changes,
                    // as tapping the screen triggers system UI visibility changes which can confuse the WebView.
                    window.decorView.setOnSystemUiVisibilityChangeListener(null)
"""

content = content.replace(old_showcustomview, new_showcustomview)


with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Done")
