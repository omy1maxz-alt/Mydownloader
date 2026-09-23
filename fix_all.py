import re

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'r') as f:
    content = f.read()

# Nuke the FABs and the touch listener logic inside onShowCustomView completely!

old_buttons = """                    val downloadButton = com.google.android.material.floatingactionbutton.FloatingActionButton(this@MainActivity).apply {
                        setImageResource(android.R.drawable.ic_menu_save) // A generic download/save icon
                        contentDescription = "Download Video"
                        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#88000000"))
                        imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)

                        val params = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = android.view.Gravity.TOP or android.view.Gravity.END
                            topMargin = 100
                            marginEnd = 100
                        }
                        layoutParams = params

                        setOnClickListener {
                            webView.evaluateJavascript("if (window.AndroidMediaController) window.AndroidMediaController.downloadActiveMedia();", null)
                            Toast.makeText(this@MainActivity, "Requesting active video download...", Toast.LENGTH_SHORT).show()
                        }
                    }
                    decorView.addView(downloadButton)
                    fullscreenDownloadButton = downloadButton

                                        val exitButton = com.google.android.material.floatingactionbutton.FloatingActionButton(this@MainActivity).apply {
                        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                        contentDescription = "Exit Fullscreen"
                        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#88000000"))
                        imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)

                        val params = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = android.view.Gravity.TOP or android.view.Gravity.START
                            topMargin = 100
                            marginStart = 100
                        }
                        layoutParams = params

                        setOnClickListener {
                            isUserExplicitFullscreenExit = true
                            onHideCustomView()
                        }
                    }
                    decorView.addView(exitButton)
                    fullscreenExitButton = exitButton

                    // Auto-hide buttons after 5 seconds
                    val hideRunnable = Runnable {
                        fullscreenDownloadButton?.visibility = View.GONE
                        fullscreenExitButton?.visibility = View.GONE
                    }
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.postDelayed(hideRunnable, 5000)

                    // Show buttons on touch
                    fullscreenView?.setOnTouchListener { _, event ->
                        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                            fullscreenDownloadButton?.visibility = View.VISIBLE
                            fullscreenExitButton?.visibility = View.VISIBLE
                            handler.removeCallbacks(hideRunnable)
                            handler.postDelayed(hideRunnable, 5000)
                        }
                        false // let the video player handle the touch too
                    }"""
content = content.replace(old_buttons, "")

old_hide = """                                override fun onHideCustomView() {
                    android.util.Log.d("WEB_FULLSCREEN_TRACE", "[WEB_FULLSCREEN_TRACE] event=onHideCustomView state=exiting explicit=$isUserExplicitFullscreenExit")
                    if (fullscreenView == null) return

                    // The WebView often calls onHideCustomView() spuriously on rotation, layout changes, or when elements inside the page shift.
                    // If the user hasn't explicitly clicked the exit button (or hardware back), we should ignore this system callback
                    // and keep the fullscreen view attached to the DecorView to prevent the uncommanded exit bug.
                    if (!isUserExplicitFullscreenExit) {
                        android.util.Log.d("WEB_FULLSCREEN_TRACE", "[WEB_FULLSCREEN_TRACE] event=onHideCustomView ignored (not explicit user exit)")
                        // Tell the WebView we handled it, but don't actually remove our views
                        customViewCallback?.onCustomViewHidden()
                        return
                    }

                    isUserExplicitFullscreenExit = false // Reset
                    val decorView = window.decorView as android.view.ViewGroup
                    decorView.removeView(fullscreenView)
                    fullscreenView = null

                    if (fullscreenDownloadButton != null) {
                        decorView.removeView(fullscreenDownloadButton)
                        fullscreenDownloadButton = null
                    }
                    if (fullscreenExitButton != null) {
                        decorView.removeView(fullscreenExitButton)
                        fullscreenExitButton = null
                    }

                    window.decorView.setOnSystemUiVisibilityChangeListener(null)"""
new_hide = """                                override fun onHideCustomView() {
                    android.util.Log.d("WEB_FULLSCREEN_TRACE", "[WEB_FULLSCREEN_TRACE] event=onHideCustomView state=exiting")
                    if (fullscreenView == null) return

                    val decorView = window.decorView as android.view.ViewGroup
                    decorView.removeView(fullscreenView)
                    fullscreenView = null

                    window.decorView.setOnSystemUiVisibilityChangeListener(null)"""
content = content.replace(old_hide, new_hide)

content = content.replace('var isUserExplicitFullscreenExit = false', '')
content = content.replace('isUserExplicitFullscreenExit = true', '')

# Fix Confirm Navigation Settings Dialog missing in MasterSettingsMenu
old_menu = """                    8 -> showPopupBlockerSettingsDialog()
                    9 -> showExportLogsDialog()
                }"""
new_menu = """                    8 -> showPopupBlockerSettingsDialog()
                    9 -> showConfirmNavigationSettingsDialog()
                    10 -> showExportLogsDialog()
                }"""
content = content.replace(old_menu, new_menu)

old_items = 'val items = arrayOf("Content Blocking", "Manage Blocked Sites", "Manage Whitelist", "Backup and Restore", "Background Loading", "View App Logs", "Gemini AI Settings", "Clear Video Cache", "Popup & Redirect Blocker", "View Export Logs")'
new_items = 'val items = arrayOf("Content Blocking", "Manage Blocked Sites", "Manage Whitelist", "Backup and Restore", "Background Loading", "View App Logs", "Gemini AI Settings", "Clear Video Cache", "Popup & Redirect Blocker", "Confirm Navigation", "View Export Logs")'
content = content.replace(old_items, new_items)

dialog_code = """
    private fun showConfirmNavigationSettingsDialog() {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isConfirmNavEnabled = prefs.getBoolean("CONFIRM_NAVIGATION_ENABLED", false)

        val message = "When enabled, the browser will ask for your permission before navigating to a completely different top-level website.\\n\\nThis is highly effective at stopping silent invisible ad redirects, but may be slightly annoying on sites with frequent legitimate outgoing links.\\n\\nCurrent state: " + if (isConfirmNavEnabled) "ON" else "OFF"

        createThemedDialogBuilder(this)
            .setTitle("Confirm Navigation Settings")
            .setMessage(message)
            .setPositiveButton(if (isConfirmNavEnabled) "Disable" else "Enable") { _, _ ->
                prefs.edit().putBoolean("CONFIRM_NAVIGATION_ENABLED", !isConfirmNavEnabled).apply()
                android.widget.Toast.makeText(this, "Confirm Navigation " + if (!isConfirmNavEnabled) "enabled" else "disabled", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
"""

content = content.replace("    private fun showExportLogsDialog() {", dialog_code + "\n    private fun showExportLogsDialog() {")


with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'w') as f:
    f.write(content)
