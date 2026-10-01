import re

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'r') as f:
    content = f.read()

replacement = """        val menuItems = listOf(
            // Primary Browsing Group
            MenuItemCustom(R.id.menu_history, "History", R.drawable.ic_public),
            MenuItemCustom(R.id.menu_add_bookmark, "Add Bookmark", R.drawable.ic_add),
            MenuItemCustom(R.id.menu_add_link, "Add Link", R.drawable.ic_download),
            MenuItemCustom(R.id.menu_open_external, "Open in External Browser", R.drawable.ic_public),

            // Tools & Settings Group
            MenuItemCustom(R.id.menu_user_scripts, "User Scripts", R.drawable.ic_translate),
            MenuItemCustom(R.id.menu_proxy_settings, getString(R.string.proxy_settings), R.drawable.ic_public),
            MenuItemCustom(R.id.menu_nuke_traps, "Nuke Ads/Traps", R.drawable.ic_close),

            // App Settings Group
            MenuItemCustom(R.id.menu_settings, "Settings", android.R.drawable.ic_menu_manage),
            MenuItemCustom(R.id.menu_media_detection_settings, "Media Detection Settings", R.drawable.ic_play_arrow),
            MenuItemCustom(R.id.menu_floating_detector_settings, "Floating Detector Settings", R.drawable.ic_play_arrow),
            MenuItemCustom(R.id.menu_theme_color, "Theme Color", android.R.drawable.ic_menu_gallery),
            MenuItemCustom(R.id.menu_toggle_popup_notice, popupNoticeTitle, android.R.drawable.ic_dialog_alert),

            // Developer Tools
            MenuItemCustom(R.id.menu_api_sniffer, "API Network Sniffer", R.drawable.ic_public),
            MenuItemCustom(R.id.menu_debug_site, "Debug Site", android.R.drawable.ic_menu_info_details),
            MenuItemCustom(R.id.menu_debug_page, "Debug Page", android.R.drawable.ic_menu_search)
        )"""

pattern = r'val menuItems = listOf\(.*?\n\s*\)'
match = re.search(pattern, content, re.DOTALL)
if match:
    new_content = content.replace(match.group(0), replacement)
    with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'w') as f:
        f.write(new_content)
    print("Replaced menuItems list.")
else:
    print("Could not find menuItems list.")
