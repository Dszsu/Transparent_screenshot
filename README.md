# 透明截图 (Transparent Screenshot)

一个基于 LSPosed 框架的 Xposed 模块，核心功能是将应用窗口的标题伪装成系统录屏组件，从而绕过系统录屏检测，同时提供多种窗口属性修改选项。

---

## 灵感来源

本模块的核心功能实现受到了以下优秀项目的启发：

- [SpoofTitle](https://github.com/ZhongYeZi-meow/SpoofTitle) – 伪装窗口标题模块 (v1.1)
- [-HideScreen-](https://github.com/zjkkzk/-HideScreen-) – 通用防截屏模块（问题修复灵感）

---

## 核心功能

| 功能             | 说明                                                                |
|----------------|-------------------------------------------------------------------|
| **启用默认窗口隐藏方案** | 通过设置 `setSkipScreenshot` 标志阻止系统截图和录屏                              |
| **去除阴影遮罩**     | 移除窗口的阴影效果                                                         |
| **绕过焦点检查**     | 设置 MagicFlags 让悬浮窗伪装成系统录屏小窗，绕过录屏检测（*注意：会导致无法唤出输入法*）               |
| **隐藏最近任务界面**   | 从最近任务列表中移除应用卡片                                                    |
| **修改窗口标题为**    | 将窗口标题伪装为系统录屏组件，支持全局品牌选择（OPPO/一加/真我、小米/红米、三星、华为EMUI、Vivo、魅族）或自定义标题 |
| **允许截屏至壁纸层**   | 窗口完全透明时不再显示黑色，而是显示壁纸（仅部分应用生效）                                     |

---

## 使用方法

1. 确保设备已 **Root** 并安装了 **LSPosed** 框架
2. 安装本模块
3. 打开 LSPosed 管理器，启用本模块
4. 进入模块界面，**勾选需要生效的目标应用**（作用域）
5. 在模块界面中点击目标应用，进入配置页，开启所需功能
6. **强制停止目标应用**后重新启动，配置即可生效

### 全局标题设置

在模块主界面右上角菜单 → **设置**，可以选择默认的窗口标题品牌。应用配置中选择 **“全局”** 模式时，将自动跟随此设置。

### WebView 支持

模块会自动为使用 WebView 的应用处理标题修改：当 WebView 渲染窗口时，会继承宿主应用的标题配置（若宿主应用设置了窗口标题），无需单独配置
WebView。

---

## 技术说明

### SkipScreenshot 说明

`SkipScreenshot` 是系统窗口与渲染流程中的一种标识，用于指示某个窗口或界面在进行截图、录屏或屏幕投射时应被
**跳过**，不参与最终画面的合成。

**当该标识生效时：**

- 应用界面在设备本地仍然可见、可交互
- 系统截图、录屏或投射结果中 **不会包含** 该界面内容

### 修改窗口标题说明

当窗口标题为特定字符串时，系统在截屏和录屏中会跳过合成该窗口，从而达到隐藏效果。

---

## 注意事项

> **请勿将本模块作用于以下目标：**
> - 系统界面（如 System UI）
> - 系统服务或系统框架（`android` / `system_server` 等）

**将模块作用于系统界面或系统框架，可能会导致以下严重后果：**

- 系统截图、录屏功能异常
- 界面无法显示或持续黑屏
- 系统不稳定、重启或卡在启动界面

✅ 本模块**仅设计用于第三方应用**，请谨慎选择作用域。  
❌ 不保证在所有 Android 版本、ROM 或厂商系统中均可生效。

---

## 权限说明

本模块需要以下权限：

- `QUERY_ALL_PACKAGES` – 用于获取已安装应用列表
- Xposed 框架权限 – 用于 Hook 目标应用窗口

---

## 免责声明

本工具仅供 **安全研究** 和 **个人学习** 使用。  
请勿用于侵犯他人隐私或版权的用途。  
使用本模块产生的任何后果由用户自行承担。

---

## 致谢

- [SpoofTitle](https://github.com/ZhongYeZi-meow/SpoofTitle) – 窗口标题伪装逻辑
- [-HideScreen-](https://github.com/zjkkzk/-HideScreen-) – 防截屏功能参考与问题修复

---

# Transparent Screenshot

A LSPosed module that disguises window titles as system screen recorder components, allowing apps to
bypass screen recording detection. It also provides various window attribute modifications.

---

## Inspiration

Core functionality inspired by these excellent projects:

- [SpoofTitle](https://github.com/ZhongYeZi-meow/SpoofTitle) – Window title spoofing (v1.1)
- [-HideScreen-](https://github.com/zjkkzk/-HideScreen-) – Universal anti-screenshot module (fix
  inspiration)

---

## Features

| Feature                                 | Description                                                                                                                                                                          |
|-----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Enable default window hiding**        | Sets `setSkipScreenshot` flag to prevent system screenshots and recording                                                                                                            |
| **Remove shadow mask**                  | Removes window shadow effects                                                                                                                                                        |
| **Bypass focus check**                  | Sets MagicFlags to disguise floating windows as system recorder mini-windows, bypassing recording detection (*Note: prevents keyboard from appearing*)                               |
| **Hide recent tasks**                   | Removes the app card from the recent tasks list                                                                                                                                      |
| **Modify window title to**              | Disguises window title as a system screen recorder component. Supports global brand selection (OPPO/OnePlus/Realme, Xiaomi/Redmi, Samsung, Huawei EMUI, Vivo, Meizu) or custom title |
| **Allow screenshot to wallpaper layer** | When the window is fully transparent, shows the wallpaper instead of black (effective only on some apps)                                                                             |

---

## Usage

1. Make sure your device is **rooted** and has **LSPosed** framework installed
2. Install this module
3. Open LSPosed manager, enable the module
4. Enter the module interface, **select the target apps** (scope)
5. Click a target app to enter its configuration page, enable desired features
6. **Force stop** the target app, then restart it – the settings will take effect

### Global title setting

Menu in the top right corner → **Settings**. You can choose the default window title brand. When an
app configures its title mode as **"Global"**, it will automatically follow this setting.

### WebView support

The module automatically handles title modification for apps using WebView: when a WebView renders a
window, it inherits the host app's title configuration (if the host app has set a window title). No
separate WebView configuration is needed.

---

## Technical Notes

### About SkipScreenshot

`SkipScreenshot` is a flag in the system window and rendering pipeline that marks a window or
surface to be **skipped** during screenshot, screen recording, or casting operations.

**When this flag is active:**

- The app UI remains visible and interactive on the device
- The system screenshot, recording, or casting result **will not include** that window's content

### About modifying window title

When the window title matches certain well‑known system recorder components, the system will skip
compositing that window during screen capture, effectively hiding it.

---

## Warning

> **Do NOT apply this module to:**
> - System UI
> - System services or frameworks (`android`, `system_server`, etc.)

**Applying the module to system UI or system frameworks may cause serious issues such as:**

- Abnormal screenshots/recording behavior
- Blank screens or display failure
- System instability, reboots, or boot loops

✅ This module is **designed for third‑party apps only**. Choose the scope carefully.  
❌ Not guaranteed to work on all Android versions, ROMs, or vendor systems.

---

## Permissions

This module requires the following permissions:

- `QUERY_ALL_PACKAGES` – to retrieve the list of installed apps
- Xposed framework privileges – to hook into target app windows

---

## Disclaimer

This tool is intended for **security research** and **personal learning** only.  
Do not use it for infringing upon others’ privacy or copyright.  
The user assumes all responsibility for any consequences arising from the use of this module.

---

## Acknowledgements

- [SpoofTitle](https://github.com/ZhongYeZi-meow/SpoofTitle) – window title spoofing logic
- [-HideScreen-](https://github.com/zjkkzk/-HideScreen-) – anti‑screenshot reference and fixes