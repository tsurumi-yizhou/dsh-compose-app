package dev.fughetta.dshcompose.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * @guide 08-plugins @guideZh
 *
 * # 插件
 *
 * 从插件市场为 DSH 添加能力，管理已装插件的开关和卸载。带原生界面的插件会出现在输入框、消息菜单和面板里。插件装在当前连接的主机上。
 *
 * ## 插件页
 *
 * `{{path:settings,navigation_plugins}}` · [打开](dsh://open/plugins)
 *
 * 分**已安装**和**市场**两个页签，右上角可以**刷新**。
 *
 * ## 安装
 *
 * 1. 在**市场**页可以**搜索**、按分类和标签浏览，点插件查看介绍。
 * 2. 在详情里点**安装**，或点**返回**。
 * 3. 也可以点右下角的**安装**，填写**软件包或 Git 地址**和**软件源地址（可选）**，先**查看信息**再安装。
 *
 * 软件包请求运行安装脚本时，会先问**允许软件包构建脚本？**，只批准你信任的软件包后点**确认**。安装结果未知时，先点**恢复安装结果**再重试。需要重启时，页面会提示，点**重启引擎**。
 *
 * ## 开关、配置与删除
 *
 * 在**已安装**页：
 *
 * - **启用**或**禁用**插件；部分修改要重启引擎才生效。
 * - 插件提供设置时，点**配置**打开它的设置。
 * - **删除**从主机上移除插件。
 * - 本地路径安装的插件可以**重新加载本地软件包**，但无法分享；其他插件可以**分享**。
 * - 运行中的扩展列出正在运行的扩展，可以**停止**。
 *
 * ## 插件的原生界面
 *
 * `{{path:settings,navigation_native_plugins}}` · [打开](dsh://open/native-plugins)
 *
 * **启用扩展**控制整个手机端扩展。停用会移除它的入口并取消动作和面板任务；依赖它的扩展也会停用。服务端插件的启用状态和配置仍在**插件**页管理。
 *
 * 带原生界面的插件可以出现在这些位置，在这一页按插件逐个开关：
 *
 * - **输入栏动作**、**输入栏工具**：输入框附近的按钮和工具。点按只会填入输入框或执行动作，不会直接发送。
 * - **输入栏状态**：输入框上方的实时信息。
 * - **消息动作**：长按一条回复后的菜单。
 * - **回合备注**：每轮回复末尾的一小段文字。
 * - **面板**：插件提供的面板，在这一页可以直接打开。
 *
 * 插件第一次读取草稿、剪贴板或所选消息时会先问你，选**允许**后才读取；打开网页链接前也会先确认。
 *
 * 原生界面需要主机带有 dsh-compose 桥接：本机引擎与 独立部署的 compose profile都自带。
 *
 * ## 插件面板
 *
 * `{{path:settings,navigation_plugin_panels}}` · [打开](dsh://open/plugin-inventory)
 *
 * 列出插件在当前主机上提供的面板位置，点一项打开。对话里也可以从**对话操作**的**插件面板**进入。主机尚未报告插件位置时，这里会提示。
 *
 * ## 图像与视频生成
 *
 * `{{path:settings,navigation_media_settings}}`
 *
 * 生图生视频由插件提供，在市场的官方区安装后，在这一页配置服务商和密钥。
 *
 * @guideEn
 *
 * # Plugins
 *
 * Add capabilities to DSH from the plugin market and manage installed plugins. Plugins with a
 * native interface show up in the composer, message menus and panels. Plugins are installed on the
 * host you are connected to.
 *
 * ## Plugins page
 *
 * `{{path:settings,navigation_plugins}}` · [Open](dsh://open/plugins)
 *
 * It has two tabs, **Installed** and **Market**; **Refresh** is at the top right.
 *
 * ## Installing
 *
 * 1. On **Market**, **Search** or browse by category and label, and tap a plugin to see its
 *    description.
 * 2. In the details, tap **Install**, or **Back**.
 * 3. You can also tap **Install** at the bottom right, enter a **Package or Git specification** and
 *    a **Registry URL (optional)**, and **Inspect** it before installing.
 *
 * If packages request installation scripts, you are asked **Allow package build scripts?**; approve
 * only packages you trust and tap **Confirm**. If the installation result is unknown, tap **Recover
 * installation result** before retrying. When a restart is needed, the page says so; tap **Restart
 * engine**.
 *
 * ## Enabling, configuring and removing
 *
 * On **Installed**:
 *
 * - **Enable** or **Disable** a plugin; some changes need an engine restart.
 * - If a plugin has settings, tap **Configure** to open them.
 * - **Remove** deletes the plugin from the host.
 * - A plugin installed from a local path can **Reload local package** but cannot be shared; other
 *   plugins can be shared with **Share**.
 * - Running extensions lists running extensions, which you can **Stop**.
 *
 * ## Native plugin interfaces
 *
 * `{{path:settings,navigation_native_plugins}}` · [Open](dsh://open/native-plugins)
 *
 * **Enable extension** controls the entire phone extension. Disabling it removes its entries and
 * cancels action and panel tasks; extensions depending on it also become inactive. Server plugin
 * activation and configuration remain on the **Plugins** page.
 *
 * Plugins with a native interface can appear in these places, switched on or off per plugin on this
 * page:
 *
 * - **Composer actions** and **Composer tools**: buttons and tools around the input box. Tapping
 *   one only fills the input box or runs an action; it never sends.
 * - **Composer status**: live information above the input box.
 * - **Message actions**: the menu after long-pressing a reply.
 * - **Turn notes**: a short note at the end of each reply.
 * - **Panels**: panels the plugin provides, which you can open from this page.
 *
 * The first time a plugin reads the draft, the clipboard or the selected message, you are asked
 * first and it reads only after **Allow**; opening a web link is confirmed too.
 *
 * Native interfaces need the dsh-compose bridge on the host: both the local engine and the
 * independently deployed compose runtime include it.
 *
 * ## Plugin panels
 *
 * `{{path:settings,navigation_plugin_panels}}` · [Open](dsh://open/plugin-inventory)
 *
 * Lists the panel locations plugins provide on the current host; tap one to open it. In a
 * conversation, you can also get there from **Plugin panels** under **Conversation actions**. If
 * the host has not reported plugin locations yet, the page says so.
 *
 * ## Image and video generation
 *
 * `{{path:settings,navigation_media_settings}}`
 *
 * Image and video generation comes from a plugin: install it from the official section of the
 * market, then configure providers and keys on this page.
 */
@Serializable internal data object PluginManagement : NavKey
