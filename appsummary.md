# Project Summary: MyDownloader Browser

## 1. Core Concept

**MyDownloader** is a feature-rich, custom web browser for Android built on the native `WebView` engine. It is designed to be a powerful, all-in-one tool for browsing, media consumption, and content downloading, with advanced features for ad-blocking and user customization.

---

## 2. Key Features

### Browsing & UI
* **Multi-Tab System:** A complete tabbing system allowing users to create, switch between, and close multiple tabs. Session is saved and restored on app restart.
* **Custom Homepage:** A start page that displays user-saved bookmarks in a grid for quick access.
* **Professional History Screen:** A dedicated activity to view browsing history, with options to delete individual items or clear all history.
* **Link Context Menu:** Long-pressing on any link opens a context menu with options to "Open in new tab," "Open in background tab," "Open in Custom Tab," and "Copy link URL."

### Content Downloading
* **Media Sniffing:** Automatically detects video and subtitle (`.mp4`, `.mkv`, `.vtt`, etc.) files from web pages and makes them available for download via a toolbar button.
* **General File Downloader:** Uses a `DownloadListener` to handle direct downloads of any file type (e.g., `.apk`, `.zip`) from file hosting sites like MediaFire.
* **Smart Naming:** Automatically generates descriptive filenames for downloaded media based on the source domain and timestamp.

### Ad & Pop-up Blocking
* **Comprehensive Ad-Blocker:** Blocks ads and pop-ups using multiple methods:
    * Blocking requests to known ad domains.
    * An intelligent redirect blocker that gives the user a choice to proceed or block.
    * A `window.open` blocker to prevent JavaScript pop-up windows.
* **User-Managed Lists:**
    * **Blocklist:** Users can add any domain to a persistent blocklist.
    * **Whitelist:** Users can add sites to a whitelist to disable all blocking features for that domain.
* **Configurable Settings:** A full settings menu allows users to enable or disable the ad blocker, redirect blocker, and pop-up blocker individually. Users can also choose to show or hide the "Pop-up blocked" notification.

### Advanced Functionality
* **User Scripts (Greasemonkey-style):** A complete system for adding, managing, and running custom JavaScript on specified websites.
    * Scripts can be targeted at specific URLs or set to run on all websites (`*`).
    * Includes a full UI for adding, editing, enabling/disabling, and deleting scripts.
* **Chrome Custom Tabs Integration:** Intelligently detects problematic websites (like `perchance.org`) and automatically opens them in a Chrome Custom Tab to ensure 100% compatibility while keeping the user in the app.
* **Background Media Player:** If the user is playing a video and backgrounds the app, a foreground service is started to continue playback with a media notification and controls.
* **Developer/Debug Menu:** A special menu for power-users that includes options to change the browser's User-Agent, clear cookies, and manage the whitelist.

---

## 3. Technical Architecture
* **Language:** 100% **Kotlin**.
* **Core Engine:** Android's native `WebView` (Chromium-based).
* **UI:** Android Views with **ViewBinding**.
* **Database:** **Room Persistence Library** for storing bookmarks and user scripts.
* **Data Storage:** `SharedPreferences` for storing user settings, tabs, history, and blocklists/whitelists.
* **Dependencies:**
    * `androidx.browser:browser` for Chrome Custom Tabs.
    * `com.google.code.gson` for serializing complex objects (like the tab list) for storage.

---

## 4. Project Status
* **Current State:** Stable, feature-complete base version.
* **Next Steps:** Potential improvements include enhancing the User Script manager, adding favicon support to history/bookmarks, and further refining the UI.
