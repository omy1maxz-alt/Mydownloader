import sys

# Ah! The pull request I just opened didn't edit any file?
# Because `git status` shows all files are STAGED, but I created a new branch inside my environment before submitting, maybe the tool expects me NOT to have git staged?
# Wait! In the previous prompt (where I restored `fix_all.py` and ran `git restore --staged fix_all.py plan_script.py`), I might have left all the Kotlin changes staged but NOT committed!
# Let me check if my changes to MainActivity.kt to add the `fullscreenDownloadButton` are actually inside `MainActivity.kt`.

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# Make sure the fullscreenDownloadButton logic is indeed there.
old_show = """                    val decorView = window.decorView as android.view.ViewGroup
                    decorView.addView(fullscreenView)



                    // Use modern WindowInsetsControllerCompat if possible to prevent brittle deprecated flag behavior"""

new_show = """                    val decorView = window.decorView as android.view.ViewGroup
                    decorView.addView(fullscreenView)

                    // Add floating buttons back for WebMedia Fullscreen
                    fullscreenDownloadButton = android.widget.ImageView(this@MainActivity).apply {
                        setImageResource(android.R.drawable.ic_menu_save)
                        setBackgroundResource(R.drawable.rounded_background)
                        setPadding(20, 20, 20, 20)
                        setOnClickListener {
                            showMediaListDialog()
                        }
                    }
                    val btnParams = android.widget.FrameLayout.LayoutParams(140, 140).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = 150
                        marginEnd = 50
                    }
                    decorView.addView(fullscreenDownloadButton, btnParams)

                    // Use modern WindowInsetsControllerCompat if possible to prevent brittle deprecated flag behavior"""

if old_show in content:
    content = content.replace(old_show, new_show)

old_hide = """                    val decorView = window.decorView as android.view.ViewGroup
                    decorView.removeView(fullscreenView)
                    fullscreenView = null

                    window.decorView.setOnSystemUiVisibilityChangeListener(null)"""

new_hide = """                    val decorView = window.decorView as android.view.ViewGroup
                    decorView.removeView(fullscreenView)
                    fullscreenView = null

                    if (fullscreenDownloadButton != null) {
                        decorView.removeView(fullscreenDownloadButton)
                        fullscreenDownloadButton = null
                    }

                    window.decorView.setOnSystemUiVisibilityChangeListener(null)"""

if old_hide in content:
    content = content.replace(old_hide, new_hide)


with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)


print("Reapplied fullscreenDownloadButton!")
