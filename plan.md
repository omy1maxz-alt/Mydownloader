1. **Remove the static popup theme configuration**:
   - In `activity_main.xml`, remove the `app:popupTheme="@style/GlassyPopupMenu"` attribute.
   - In `MainActivity.kt`, in `applyGlossyTheme()`, remove the lines setting `binding.toolbar.popupTheme` and `binding.toolbar.setPopupTheme`.

2. **Dynamically theme the overflow menu**:
   - Instead of trying to theme the standard menu which is very tricky (as noted in Memory - reflection hacks cause rendering/readability issues), the best way to handle this without reflection is to intercept the 3-dots menu button click and use a custom `ListPopupWindow` or `PopupMenu` or manipulate `SpannableString` for the menu items to change text colors, and changing background through `onCreateOptionsMenu` action provider if possible. But `ListPopupWindow` is recommended against in memory. Wait, memory says: "Do not use reflection hacks (e.g., intercepting `ActionMenuView` and `ListPopupWindow`) to dynamically change the background color of the standard Android Toolbar's overflow popup menu. It is brittle and causes rendering and readability issues across devices."
   - Let's read the user request again: "Please adjist the pop up menu background so it follow theme color,the same as toolbar,and also adjust the text color so it adapt for every color." The user asks me to do exactly what memory warns about, OR memory warns about reflection hacks and intercepting. Maybe I can change the popup theme programmatically?
   - The memory states: "Do not use reflection hacks (e.g., intercepting ActionMenuView and ListPopupWindow) to dynamically change the background color of the standard Android Toolbar's overflow popup menu. It is brittle and causes rendering and readability issues across devices."
   - Wait, `ListPopupWindow` was part of a reflection hack?
   - Wait, is there a standard way to tint the popup menu? I can't just set the popup menu background dynamically on the Toolbar without reflection or creating a custom style dynamically (which isn't possible).
   - Actually, wait, maybe I can use `Toolbar.setPopupTheme`? But that requires a style resource. I cannot create style resources at runtime.
   - How can I set the background color of a PopupMenu? `toolbar.overflowIcon` can be tinted. But the menu background?
   - Let's rethink. If I shouldn't use reflection hacks, how can I achieve dynamic background colors for the overflow menu?
   - One way is to set `android:itemBackground` in the theme, but that's static.
   - What if I replace the overflow icon with a custom click listener and show a custom `PopupWindow` or `ListPopupWindow`? But memory specifically says: "Do not use reflection hacks (e.g., intercepting ActionMenuView and ListPopupWindow)". Is the warning against *reflection* hacks specifically, or against replacing it altogether? "intercepting ActionMenuView and ListPopupWindow" means finding the ActionMenuView via reflection and modifying its ListPopupWindow.
   - What if I just use a custom ImageButton for the overflow menu, hide the default one (don't use `onCreateOptionsMenu` for the toolbar), and simply create a standard `ListPopupWindow` attached to that button?
   - Let me check `MainActivity.kt`. It uses `onCreateOptionsMenu` and `onOptionsItemSelected`.
   - If I create a custom ImageButton with `ic_more_vert`, and when clicked, I create a `PopupWindow` containing a `RecyclerView` or `ListView` or `LinearLayout`, I can fully control its background color and text color dynamically! And it's not a reflection hack, it's just a custom popup.
   - Wait, if I use `PopupMenu` from `androidx.appcompat.widget`, can I change its background? In Android 10+ (API 29+), `PopupMenu.getMenu()` is available but the background? You can use `PopupMenu(ContextThemeWrapper(this, R.style.MyPopupStyle), view)` but again it requires static styles.

   - Let's look at `applyGlossyTheme` again. We can dynamically change the text colors in `onPrepareOptionsMenu` by iterating through `menu.iterator()` and applying a `ForegroundColorSpan`. But that doesn't solve the background color.

   - To solve the background color dynamically without reflection:
     1. Hide the default overflow menu: We can stop using `setSupportActionBar`, or just not inflate the menu in `onCreateOptionsMenu` if it's the toolbar, but wait, `MainActivity` has `onCreateOptionsMenu`.
     2. Wait, the user has a LinearLayout containing standard buttons (`homeButton`, `refreshButton`, `translateButton`, etc.), and NO default overflow button in the XML. But `setSupportActionBar` is called. It will add the overflow button automatically at the end of the Toolbar because there are items with `showAsAction="never"` in `main_menu.xml`.
     3. I can remove the default menu inflation (`onCreateOptionsMenu`), add an `ImageButton` to the `toolbarLinearLayout` with the `ic_more_vert` icon, and on click, show a custom `PopupWindow` with a `ListView` or `LinearLayout` of the menu options. This allows 100% control over the background and text color without reflection, avoiding the brittle `ListPopupWindow` reflection hacks.
     4. Is that allowed? Yes, memory warns against "reflection hacks (e.g., intercepting ActionMenuView and ListPopupWindow) to dynamically change the background color of the standard Android Toolbar's overflow popup menu". By making it a totally custom button and popup, we bypass the standard toolbar's overflow menu entirely and thus avoid reflection.

   - Let's read `activity_main.xml`. The `toolbarLinearLayout` contains `homeButton`, URL bar, `refreshButton`, `translateButton`, `backButton`, `forwardButton`, `aiButton`, `fabShowMedia`, `tabButton`.
   - I can add an `overflowMenuButton` (`ImageButton`) at the end of `toolbarLinearLayout`.
   - Then in `MainActivity.kt`, I can remove `onCreateOptionsMenu` and `onOptionsItemSelected` that handle `R.menu.main_menu` logic, and instead wire the `overflowMenuButton` to show a custom `PopupWindow` or a `ListPopupWindow` where the adapter is a custom adapter. Wait, `ListPopupWindow` is fine if not used via reflection? The memory says: "intercepting ActionMenuView and ListPopupWindow". So intercepting the existing one is bad. Creating our own custom one is totally fine.
   - Actually, using a `ListPopupWindow` with an `ArrayAdapter` is very clean.

   - Or maybe a simpler way: just define a `RecyclerView` or `ListView` inside a `PopupWindow`.
   - Wait! Another approach: We can use `SpannableString` for the text color. What about the background color? Is there ANY way to set the background of the standard toolbar's overflow popup without reflection?
   - No, because it uses a statically resolved style attribute.
   - So creating a custom menu button is the way to go.

   Let's verify what `main_menu.xml` contains:
   ```xml
    <item android:id="@+id/menu_history" android:title="History" app:showAsAction="never" />
    <item android:id="@+id/menu_add_bookmark" android:title="Add Bookmark" app:showAsAction="never" />
    <item android:id="@+id/menu_user_scripts" android:title="User Scripts" app:showAsAction="never" />
    <item android:id="@+id/menu_open_external" android:title="Open in External Browser" app:showAsAction="never" />
    <item android:id="@+id/menu_proxy_settings" android:title="@string/proxy_settings" app:showAsAction="never" />
    <item android:id="@+id/menu_nuke_traps" android:title="Nuke Ads/Traps" app:showAsAction="never" />
    <item android:id="@+id/menu_settings" android:title="Settings" app:showAsAction="never" />
    <item android:id="@+id/menu_theme_color" android:title="Theme Color" app:showAsAction="never" />
    <item android:id="@+id/menu_debug_site" android:title="Debug Site" app:showAsAction="never" />
    <item android:id="@+id/menu_enable_media_detection" android:title="Enable Media Detection" app:showAsAction="never" />
    <item android:id="@+id/menu_debug_page" android:title="Debug Page" app:showAsAction="never" />
   ```

   If I add a custom `ImageButton` for the overflow, I will need an icon. `ic_more_vert.xml` exists.
   Then, in `MainActivity.kt`, I can initialize a `ListPopupWindow`, set an adapter with the menu items, and handle clicks.
