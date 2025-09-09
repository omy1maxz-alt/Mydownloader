# Mydownloader - Android Web Browser and Media Downloader

## Overview

Mydownloader is a versatile Android application that functions as a web browser with advanced features, including media detection and download capabilities, a tabbed browsing interface, bookmark management, history tracking, and a powerful ad-blocker. It also includes an extensible userscript engine for custom page modifications.

## Core Features

- **Tabbed Browsing**: Manage multiple web pages in a familiar tab-based interface.
- **Media Detection**: Automatically detects video and audio files on web pages, allowing for easy download or background playback.
- **Background Playback**: Continue listening to media from a web page even when the app is in the background, thanks to a foreground service.
- **Download Manager**: A robust download manager for handling downloaded files.
- **Ad & Redirect Blocker**: A configurable ad-blocker to block ads, suspicious redirects, and popups for a cleaner browsing experience.
- **Bookmarks & History**: Save your favorite pages as bookmarks and easily access your browsing history.
- **Userscript Support**: A developing engine to run custom JavaScript on web pages.

## Setup and Build

This project is a standard Android application built with Kotlin and Gradle.

### Requirements

- Android Studio (latest version recommended)
- Android SDK
- Java Development Kit (JDK)

### Building from Source

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/omy1maxz-alt/Mydownloader.git
    ```
2.  **Open in Android Studio:**
    - Open Android Studio.
    - Click on "Open an Existing Project".
    - Navigate to the cloned repository directory and select it.
3.  **Sync Gradle:**
    - Android Studio should automatically sync the Gradle project. If not, you can trigger a manual sync by clicking "Sync Project with Gradle Files" in the toolbar.
4.  **Build the APK:**
    - From the menu bar, select `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
    - The generated APK file will be located in `app/build/outputs/apk/debug/`.

## Usage

After building and installing the APK on an Android device or emulator:
- The app opens to a start page displaying your bookmarks.
- Use the address bar at the top to navigate to any URL or to search on Google.
- The navigation buttons (back, forward, refresh) are located in the toolbar.
- The tab button shows the number of open tabs and allows you to manage them.
- Long-press on a link to open it in a new tab or a background tab.
- When media is detected on a page, a floating action button will appear. Tapping it will show a list of detected media files that you can choose to download or play.
- Access History, Bookmarks, and Settings from the overflow menu (three dots) in the toolbar.
