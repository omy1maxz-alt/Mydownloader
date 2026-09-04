Let's see if we can use a custom popup window for the menu.
1. In `activity_main.xml`, add:
```xml
                <ImageButton
                    android:id="@+id/overflowMenuButton"
                    style="?attr/borderlessButtonStyle"
                    android:layout_width="40dp"
                    android:layout_height="40dp"
                    android:contentDescription="More options"
                    android:src="@drawable/ic_more_vert" />
```
inside `toolbarLinearLayout`, after `tabButton`.

2. Create a simple `list_item_menu.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<TextView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@android:id/text1"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:padding="16dp"
    android:textSize="16sp"
    android:singleLine="true"
    android:ellipsize="marquee" />
```

3. In `MainActivity.kt`:
- Remove `onCreateOptionsMenu` and `onOptionsItemSelected` (but keep the logic inside a new handler function).
- Keep `setSupportActionBar(binding.toolbar)`? Maybe we can keep it, but remove `onCreateOptionsMenu` so the system overflow icon doesn't show. Wait, the toolbar still handles titles etc. Yes, keeping it is fine, just delete `onCreateOptionsMenu`.
- Add a new function `showCustomOverflowMenu(anchorView: View)`.
- Use a `ListPopupWindow`.
- For background, set `listPopupWindow.setBackgroundDrawable(ColorDrawable(themeColor))`
- For text colors, use a custom adapter that calculates luminance and sets the text color of the `TextView`.
- The items will be hardcoded in an enum or list of pairs.
