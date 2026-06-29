<div align="center">
  <a href="https://github.com/Dszsu/Transparent_screenshot/blob/main/README.md">中文</a> | 
  <a href="https://github.com/Dszsu/Transparent_screenshot/blob/main/README_en.md">English</a>
</div>

---

# 🖼️ Transparent Screenshot

*A LSPosed module that provides comprehensive screenshot, screen recording and screen casting protection for apps.*

[![Android](https://img.shields.io/badge/Android-10%2B-brightgreen)]()
[![API](https://img.shields.io/badge/Xposed%20API-101-blue)]()
[![License](https://img.shields.io/badge/License-MIT-yellow)]()

---

## ✨ Core Features

### App‑Level Features

| Feature | Description |
|---------|-------------|
| **Enable default window hiding** | Prevents system screenshots and recordings via the `setSkipScreenshot` flag. |
| **Remove shadow mask** | Removes window shadow effects. |
| **Block touch and focus** | Sets `FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCHABLE \| FLAG_NOT_TOUCH_MODAL` – window becomes unclickable. **(⚠️ Note: the window will not respond to any touch or focus events.)** |
| **Bypass focus check** | Sets only `FLAG_NOT_FOCUSABLE` – touch events remain intact, but the input method (keyboard) will not pop up. **(⚠️ Note: keyboard input will not appear.)** |
| **Hide recent tasks** | Removes the app card from the recent tasks list. |
| **Modify window title to** | Disguises the window title as a system recorder component; supports global brand selection or custom title. |
| **Allow screenshot to wallpaper layer** | Shows wallpaper instead of black when the window is fully transparent (works with some apps only). |

### System‑Level Hiding

System‑level hiding injects into the system framework to intercept window rendering at the system service level. It can hide both the window itself and the shadows/borders of its parent Task. This feature must be manually enabled in Settings, and configured by tapping **System Framework** on the main screen.

> ⚠️ May cause slight lag; enable only when necessary.

---

## 📖 Usage

1. Ensure your device is **rooted** and has **LSPosed** framework installed.
2. Install this module.
3. Open LSPosed manager, enable the module.
4. Select the **target apps** (scope) that you want the module to affect.
5. Tap a target app to open its configuration page, and enable the desired features.
6. **Force stop** the target app and restart it – the settings will take effect.

### Global Title Setting

In the top‑right menu of the main screen → **Settings**, you can choose a default window title brand. When an app’s title mode is set to **"Global"**, it will automatically follow this setting.

### System‑Level Hiding Setup

1. Go to **Settings** → turn on **System‑level hiding**.
2. The system will request to add `system` to the module scope.
3. Return to the main screen and tap the **System Framework** entry.
4. In the system‑hide screen, select the apps you wish to hide (search supported).
5. Enabled apps are automatically sorted to the top of the list.
6. Use the top‑right menu to toggle showing/hiding system apps.

---

## 🔬 Technical Notes

### How `SkipScreenshot` Works

`SkipScreenshot` is a flag in the system window and rendering pipeline that marks a window or surface to be **skipped** during screenshot, recording, or casting operations.

**When this flag is active:**
- The app UI remains visible and interactive on the local device.
- The window content **will not be included** in system screenshots, recordings, or casts.

### SystemUI Enhancement – Oplus Floating Menu Hiding

When **SystemUI menu hiding (Oplus)** is enabled in **Settings**, the module hooks into `FlexibleMenuManager` in the SystemUI process. On OPPO/OnePlus devices, when the floating window menu appears, it automatically applies `setSkipScreenshot` to the menu’s Surface, hiding it from screenshots/recordings.

This feature is only available on OPPO/OnePlus devices running ColorOS 15/16.

### How Window Title Modification Works

When the window title matches certain well‑known system recorder component strings, the system skips compositing that window during screen capture, effectively hiding it.

### How System‑Level Hiding Works

System‑level hiding operates directly on the window management mechanism of the system service (`system_server`). It sets the `setSkipScreenshot` flag on the target window’s Surface at creation time, and also applies the same flag to the window’s parent Task container, thereby hiding both the window content and its shadow/border.

---

## ⚠️ Cautions

> **This module deeply intervenes in the system window management mechanism. Please ensure you understand the risks and possess basic knowledge of window systems before use. Any system UI abnormalities, feature failures, or other unforeseen issues caused by improper configuration are the user's own responsibility.**

---

## 🔒 Permissions

This module requires the following permissions:

- `QUERY_ALL_PACKAGES` – to retrieve the list of installed apps.
- Xposed framework privileges – to hook into target app windows.

---

## 📄 Disclaimer

This tool is intended for **security research** and **personal learning** only.  
Do not use it for infringing upon others’ privacy or copyright.  
The user assumes all responsibility for any consequences arising from the use of this module.

---

## 🙏 Acknowledgements

Core functionality inspired by the following excellent projects:

| Project | Description |
|---------|-------------|
| [SpoofTitle](https://github.com/ZhongYeZi-meow/SpoofTitle) | Window title spoofing logic |
| [-HideScreen-](https://github.com/zjkkzk/-HideScreen) | Anti‑screenshot fixes |

---

**Transparent Screenshot** © 2025 · Made with ❤️
