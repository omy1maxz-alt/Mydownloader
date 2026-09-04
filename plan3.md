So, `main_menu.xml` has those elements. We will manually replicate them in the code.
Actually, Android has `PopupMenu`. If we use a standard `android.widget.PopupMenu` and pass a ContextThemeWrapper, we can't change it *after* it's been inflated unless we recreate the wrapper.
However, we want the text colors to change based on the luminance of the theme color (which changes immediately without restarting).

Let's use `ListPopupWindow` connected to an `ImageButton`.
`ListPopupWindow` allows us to set the background drawable directly, and we can pass an adapter.
Is `ListPopupWindow` forbidden by Memory? Let's check Memory again.
Memory: "Do not use reflection hacks (e.g., intercepting ActionMenuView and ListPopupWindow) to dynamically change the background color of the standard Android Toolbar's overflow popup menu. It is brittle and causes rendering and readability issues across devices."

Ah, it explicitly says "intercepting ActionMenuView and ListPopupWindow ... of the standard Android Toolbar's overflow popup menu". This implies the issue is *intercepting* the standard one via reflection. If we create a custom one from scratch using `ListPopupWindow`, it's not a reflection hack at all, it's a standard public API.

So I will:
1. Update `activity_main.xml`:
   - Remove `app:popupTheme="@style/GlassyPopupMenu"` from Toolbar.
   - Add `<ImageButton android:id="@+id/overflowMenuButton" ...>` at the end of the `toolbarLinearLayout`.
2. Update `MainActivity.kt`:
   - Delete `onCreateOptionsMenu` and `onOptionsItemSelected`.
   - In `setupToolbarNavButtons()`, add `binding.overflowMenuButton.setOnClickListener { showCustomOverflowMenu(it) }`
   - Define a class/struct for Menu Items. We can actually parse the menu from XML or just hardcode the 11 items. Wait, we have localized strings like `@string/proxy_settings`, so hardcoding resources is best.
   ```kotlin
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
   ```
   - In `showCustomOverflowMenu`, create a `ListPopupWindow`.
   - Set the background drawable based on `glossy_theme_color` without transparency. Wait, standard theme color has transparency (e.g. `#A0000000`). If we set this on the popup background, it'll have the exact same color/transparency as the toolbar.
   - Calculate luminance to set text color to Black or White.
   ```kotlin
   val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
   val hexColor = prefs.getString("glossy_theme_color", "#A0000000") ?: "#A0000000"
   val bgColor = android.graphics.Color.parseColor(hexColor)
   val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(bgColor)
   val textColor = if (luminance > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE
   ```
   - We need an adapter:
   ```kotlin
   val adapter = object : ArrayAdapter<MenuItemCustom>(this, android.R.layout.simple_list_item_1, menuItems) {
       override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
           val view = super.getView(position, convertView, parent) as TextView
           view.text = getItem(position)?.title
           view.setTextColor(textColor)
           return view
       }
   }
   ```
   - `listPopupWindow.setAdapter(adapter)`
   - `listPopupWindow.anchorView = anchorView`
   - `listPopupWindow.setOnItemClickListener { _, _, position, _ -> ... }` (Copy the `when (item.itemId)` logic here).
3. Update `applyGlossyTheme()`:
   - Remove `binding.toolbar.popupTheme = ...`
4. Update `styles.xml`:
   - Remove `GlassyPopupMenu` if it's unused.

Let's double check if I missed any logic in `onOptionsItemSelected` inside `MainActivity.kt`.
I will create a tool to extract `onOptionsItemSelected`.
