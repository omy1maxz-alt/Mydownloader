import re

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# 1. Wire the overflowMenuButton in setupToolbarNavButtons
# setupToolbarNavButtons looks like:
#     private fun setupToolbarNavButtons() {
#         binding.homeButton.setOnClickListener { showStartPage() }

wire_code = """        binding.homeButton.setOnClickListener { showStartPage() }
        binding.overflowMenuButton.setOnClickListener { showCustomOverflowMenu(it) }"""

content = re.sub(r'binding\.homeButton\.setOnClickListener \{ showStartPage\(\) \}', wire_code, content)

# 2. Add showCustomOverflowMenu function
custom_menu_func = """
    private fun showCustomOverflowMenu(anchor: View) {
        val listPopupWindow = androidx.appcompat.widget.ListPopupWindow(this, null, androidx.appcompat.R.attr.listPopupWindowStyle)
        listPopupWindow.anchorView = anchor

        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val hexColor = prefs.getString("glossy_theme_color", "#A0000000") ?: "#A0000000"
        val bgColor = try { android.graphics.Color.parseColor(hexColor) } catch (e: Exception) { android.graphics.Color.BLACK }

        listPopupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))

        val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(bgColor)
        val textColor = if (luminance > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE

        data class MenuItemCustom(val id: Int, val title: String)
        val menuItems = listOf(
            MenuItemCustom(R.id.menu_history, "History"),
            MenuItemCustom(R.id.menu_add_bookmark, "Add Bookmark"),
            MenuItemCustom(R.id.menu_user_scripts, "User Scripts"),
            MenuItemCustom(R.id.menu_open_external, "Open in External Browser"),
            MenuItemCustom(R.id.menu_proxy_settings, getString(R.string.proxy_settings)),
            MenuItemCustom(R.id.menu_nuke_traps, "Nuke Ads/Traps"),
            MenuItemCustom(R.id.menu_settings, "Settings"),
            MenuItemCustom(R.id.menu_theme_color, "Theme Color"),
            MenuItemCustom(R.id.menu_debug_site, "Debug Site"),
            MenuItemCustom(R.id.menu_enable_media_detection, "Enable Media Detection"),
            MenuItemCustom(R.id.menu_debug_page, "Debug Page")
        )

        val adapter = object : android.widget.ArrayAdapter<MenuItemCustom>(this, android.R.layout.simple_list_item_1, menuItems) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as android.widget.TextView
                view.text = getItem(position)?.title
                view.setTextColor(textColor)
                return view
            }
        }

        listPopupWindow.setAdapter(adapter)

        var maxWidth = 0
        val measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        for (i in 0 until adapter.count) {
            val view = adapter.getView(i, null, android.widget.FrameLayout(this@MainActivity))
            view.measure(measureSpec, measureSpec)
            if (view.measuredWidth > maxWidth) {
                maxWidth = view.measuredWidth
            }
        }
        val padding = (40 * resources.displayMetrics.density).toInt()
        listPopupWindow.setContentWidth(maxWidth + padding)

        listPopupWindow.setOnItemClickListener { _, _, position, _ ->
            listPopupWindow.dismiss()
            when (menuItems[position].id) {
                R.id.menu_history -> showHistory()
                R.id.menu_add_bookmark -> addCurrentPageToBookmarks()
                R.id.menu_user_scripts -> startActivity(Intent(this, UserScriptManagerActivity::class.java))
                R.id.menu_open_external -> openCurrentPageInExternalBrowser()
                R.id.menu_proxy_settings -> showProxySettingsDialog()
                R.id.menu_nuke_traps -> nukeAdsAndTraps()
                R.id.menu_settings -> showMasterSettingsDialog()
                R.id.menu_theme_color -> showThemeColorPickerDialog()
                R.id.menu_debug_site -> showSiteDebuggingOptions()
                R.id.menu_enable_media_detection -> manualMediaScan()
                R.id.menu_debug_page -> showPageSource()
            }
        }

        listPopupWindow.show()
    }

"""

# Insert the function before `private fun injectTranslateScript` or somewhere safe
content = re.sub(r'(\s*private fun injectTranslateScript\(view: WebView\?\) \{)', r'\n' + custom_menu_func + r'\1', content)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)
