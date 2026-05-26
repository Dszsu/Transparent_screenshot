<!-- markdownlint-disable -->
<div align="center">

# 🖼️ 透明截图 (Transparent Screenshot)

*A LSPosed module that provides comprehensive screenshot, screen recording and screen casting protection for apps.*

*基于 LSPosed 框架的 Xposed 模块，为应用提供全面的截图、录屏及屏幕投射防护。*

[![Android](https://img.shields.io/badge/Android-10%2B-brightgreen)]()
[![API](https://img.shields.io/badge/Xposed%20API-101-blue)]()
[![License](https://img.shields.io/badge/License-MIT-yellow)]()

</div>

---

## ✨ 核心功能 (Core Features)

### 🛡️ 应用层功能 (App-Level Features)

| 功能 Feature | 说明 Description |
|-------------|------------------|
| **启用默认窗口隐藏方案** <br> Enable default window hiding | 通过 `setSkipScreenshot` 标志阻止系统截图和录屏 <br> Prevents system screenshots and recording via `setSkipScreenshot` flag |
| **去除阴影遮罩** <br> Remove shadow mask | 移除窗口的阴影效果 <br> Removes window shadow effects |
| **绕过焦点检查** <br> Bypass focus check | 设置 MagicFlags 让悬浮窗伪装成系统录屏小窗，绕过录屏检测 (⚠️ 注意：会导致无法唤出输入法) <br> Disguises floating windows as system recorder mini-windows (⚠️ Note: keyboard will not appear) |
| **隐藏最近任务界面** <br> Hide recent tasks | 从最近任务列表中移除应用卡片 <br> Removes app card from recent tasks list |
| **修改窗口标题为** <br> Modify window title to | 将窗口标题伪装为系统录屏组件，支持全局品牌选择或自定义标题 <br> Disguises window title as system recorder component; supports global brand selection or custom title |
| **允许截屏至壁纸层** <br> Allow screenshot to wallpaper layer | 窗口完全透明时显示壁纸而非黑色（仅部分应用生效） <br> Shows wallpaper instead of black when window is fully transparent (some apps only) |

### 🔧 系统层隐藏 (System-Level Hiding)

**系统层隐藏** 通过注入系统框架，在系统服务层级直接拦截窗口的渲染，可同时隐藏窗口及其所属 Task 的阴影/边框。此功能需要在设置中手动开启，并在主界面点击 **系统框架** 进行配置。

**System-level hiding** works by injecting into the system framework to intercept window rendering at the system service level. It can hide both the window and the shadows/borders of its parent Task. This feature must be manually enabled in settings, and configured by tapping **System Framework** on the main screen.

> ⚠️ 目前仅在 OPPO/一加 ColorOS 15/16 上测试通过。开启后可能略有卡顿，请按需使用。  
> ⚠️ Currently only tested on OPPO/OnePlus ColorOS 15/16. May cause slight lag; use only when needed.

---

## 📖 使用方法 (Usage)

1. 确保设备已 **Root** 并安装了 **LSPosed** 框架 / Make sure your device is **rooted** and has **LSPosed** framework installed
2. 安装本模块 / Install this module
3. 打开 LSPosed 管理器，启用本模块 / Open LSPosed manager, enable the module
4. 进入模块界面，**勾选需要生效的目标应用**（作用域） / Enter the module interface, **select the target apps** (scope)
5. 在模块界面中点击目标应用，进入配置页，开启所需功能 / Click a target app to enter its configuration page, enable desired features
6. **强制停止目标应用**后重新启动，配置即可生效 / **Force stop** the target app, then restart it – the settings will take effect

### 🌐 全局标题设置 (Global Title Setting)

在模块主界面右上角菜单 → **设置**，可以选择默认的窗口标题品牌。应用配置中选择 **"全局"** 模式时，将自动跟随此设置。  
Menu in the top right corner → **Settings**. You can choose the default window title brand. When an app configures its title mode as **"Global"**, it will automatically follow this setting.

### 🔌 系统层隐藏设置 (System-Level Hiding Setup)

1. 进入 **设置** → 开启 **系统层隐藏** 开关 / Enter **Settings** → turn on **System-level hiding**
2. 系统将请求添加 `system` 到模块作用域 / The system will request adding `system` to the module scope
3. 返回主界面，点击 **系统框架** 条目 / Return to main screen, tap the **System Framework** entry
4. 在系统层隐藏界面中，选择需要隐藏的应用（支持搜索） / In the system hide screen, select the apps you want to hide (search supported)
5. 已开启隐藏的应用会自动排在列表最上方 / Apps with hiding enabled are automatically sorted to the top
6. 右上角菜单可切换显示/隐藏系统应用 / The top-right menu can toggle showing/hiding system apps

---

## 🔬 技术说明 (Technical Notes)

### SkipScreenshot 原理 (How SkipScreenshot Works)

`SkipScreenshot` 是系统窗口与渲染流程中的标识，用于指示某个窗口或界面在进行截图、录屏或屏幕投射时应被**跳过**，不参与最终画面的合成。  
`SkipScreenshot` is a flag in the system window and rendering pipeline that marks a window or surface to be **skipped** during screenshot, screen recording, or casting operations.

**当该标识生效时 / When this flag is active:**
- 应用界面在设备本地仍然可见、可交互 / The app UI remains visible and interactive on the device
- 系统截图、录屏或投射结果中 **不会包含** 该界面内容 / The system screenshot, recording, or casting result **will not include** that window's content

### 修改窗口标题原理 (How Window Title Modification Works)

当窗口标题为特定字符串时，系统在截屏和录屏中会跳过合成该窗口，从而达到隐藏效果。  
When the window title matches certain well‑known system recorder components, the system will skip compositing that window during screen capture, effectively hiding it.

### 系统层隐藏原理 (How System-Level Hiding Works)

系统层隐藏直接作用于系统服务 (`system_server`) 的窗口管理机制，在目标窗口的 Surface 创建时就为其设置 `setSkipScreenshot` 标志，并将该标志同步应用到窗口所属的 Task 容器，从而同时隐藏窗口内容和阴影边框。  
System-level hiding directly operates on the window management mechanism of the system service (`system_server`). It sets the `setSkipScreenshot` flag for the target window's Surface at creation time, and applies the same flag to the window's parent Task container, thereby hiding both the window content and its shadow/border.

---

## ⚠️ 注意事项 (Cautions)

> **本模块涉及对系统窗口管理机制的深度干预。使用前请确保您了解相关风险，并具备基本的窗口系统知识。因不当配置导致的系统界面异常、功能失效或其他不可预期的问题，由用户自行承担。**  
> **This module deeply intervenes in the system window management mechanism. Please ensure you understand the risks and possess basic knowledge of window systems before use. Any system UI abnormalities, feature failures, or other unforeseen issues caused by improper configuration are the user's own responsibility.**

---

## 🔒 权限说明 (Permissions)

本模块需要以下权限 / This module requires the following permissions:

- `QUERY_ALL_PACKAGES` – 用于获取已安装应用列表 / to retrieve the list of installed apps
- Xposed 框架权限 – 用于 Hook 目标应用窗口 / Xposed framework privileges – to hook into target app windows

---

## 📄 免责声明 (Disclaimer)

本工具仅供 **安全研究** 和 **个人学习** 使用。  
请勿用于侵犯他人隐私或版权的用途。  
使用本模块产生的任何后果由用户自行承担。

This tool is intended for **security research** and **personal learning** only.  
Do not use it for infringing upon others’ privacy or copyright.  
The user assumes all responsibility for any consequences arising from the use of this module.

---

## 🙏 致谢 (Acknowledgements)

本模块的核心功能实现受到了以下优秀项目的启发 / Core functionality inspired by the following excellent projects:

| 项目 Project | 说明 Description |
|-------------|------------------|
| [SpoofTitle](https://github.com/ZhongYeZi-meow/SpoofTitle) | 窗口标题伪装逻辑 / Window title spoofing logic |
| [-HideScreen-](https://github.com/zjkkzk/-HideScreen-) | 防截屏问题修复 / Anti‑screenshot fixes |

---

<div align="center">

**Transparent Screenshot** © 2025 · Made with ❤️

</div>
