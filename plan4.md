1. **Remove `onCreateOptionsMenu` and `onOptionsItemSelected`** entirely from `MainActivity.kt`.
2. **Remove `app:popupTheme="@style/GlassyPopupMenu"`** from `activity_main.xml`.
3. **Remove `setPopupTheme` from `applyGlossyTheme`**.
4. **Add `ImageButton`** for overflow in `activity_main.xml`.
5. **Implement `showCustomOverflowMenu(anchor: View)`** using `androidx.appcompat.widget.ListPopupWindow` (or `android.widget.ListPopupWindow` which requires API 11). `androidx.appcompat.widget.ListPopupWindow` is best.

Let's make sure the background color applies to the popup. `ListPopupWindow.setBackgroundDrawable(ColorDrawable(bgColor))` will set the background.

```kotlin
    private fun showCustomOverflowMenu(anchor: View) {
        val listPopupWindow = androidx.appcompat.widget.ListPopupWindow(this, null, androidx.appcompat.R.attr.listPopupWindowStyle)
        listPopupWindow.anchorView = anchor

        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val hexColor = prefs.getString("glossy_theme_color", "#A0000000") ?: "#A0000000"
        val bgColor = try { android.graphics.Color.parseColor(hexColor) } catch (e: Exception) { android.graphics.Color.BLACK }

        // Strip alpha from background to make it solid as per previous instructions, but wait, the user says:
        // "Please adjist the pop up menu background so it follow theme color,the same as toolbar,and also adjust the text color so it adapt for every color."
        // So the background SHOULD be EXACTLY the same as the toolbar (which means we might keep the alpha).
        // Wait, the memory states: "Avoid transparent or 'glassy' backgrounds for the Toolbar overflow menu... as they cause text readability issues... Ensure android:popupBackground does not override it to transparent".
        // But if the user says "follow theme color,the same as toolbar", and the toolbar has `#A0000000`. Does this mean the popup should have `#A0000000`? Yes, BUT if we adjust the text color to adapt for every color, readability is no longer an issue!
        // We will just use `bgColor` exactly as it is for the background of the popup menu.
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

        // Measure width to match content
        var maxWidth = 0
        val measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        for (i in 0 until adapter.count) {
            val view = adapter.getView(i, null, android.widget.FrameLayout(this@MainActivity))
            view.measure(measureSpec, measureSpec)
            if (view.measuredWidth > maxWidth) {
                maxWidth = view.measuredWidth
            }
        }
        listPopupWindow.setContentWidth(maxWidth + 40) // Add some padding

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
```
This is perfect and fully fulfills the user request.
I'll create the set_plan now.
