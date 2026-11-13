package dev.fughetta.dshcompose.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * @guide 03-engine-hosts @guideZh
 *
 * # 本机引擎与远程主机
 *
 * DSH 可以运行在这台手机上（本机引擎），也可以运行在你的电脑或服务器上，由手机远程连接。对话、工作区和插件都属于各自的主机。
 *
 * ## 本机还是远程
 *
 * 第一次打开 App 时，在「在哪里运行引擎？」里选择：
 *
 * - **在此手机运行**：安装内置运行环境。初始化会联网下载引擎依赖并占用本机存储，可以一边安装一边继续配置。手机内存不足 5 GiB 时，页面会建议用远程引擎。
 * - **连接远程引擎**：连接电脑或服务器上的 DSH，不在手机上安装。
 *
 * 接着依次设置外观、查看手机访问权限、配置模型服务商；服务商可以点**稍后配置**以后再加。
 *
 * ## 切换主机
 *
 * 对话列表底部显示当前主机的名称和状态，例如「本机 · 运行中」或某个 compose 服务的「已连接」。
 *
 * - 点名称打开**切换主机**：列出**本机**和已配对的 compose
 *   服务，当前主机已选中，点另一项即切换过去。设备凭据保存后，再次选择服务即可连接。本机还没装运行环境时，点**本机**会打开**引擎**页去安装。
 * - 点**Compose 服务**进入下方的服务列表；还没有主机时从这里添加。
 * - 点名称左边的圆形图标打开**引擎**页。
 *
 * ## 本机引擎
 *
 * `{{path:engine}}` · [打开](dsh://open/engine)
 *
 * 底部的**引擎**页分为**引擎**、**日志**、**诊断**几栏。**引擎**一栏里：
 *
 * - 显示状态、已安装和内置的版本，以及可用空间。
 * - **安装内置运行环境**、**启动引擎**、**停止引擎**、**重启引擎**。离开页面后安装继续，解包过程无法暂停。
 * - **直接执行**：只能在引擎停止时修改，是否可用由当前运行环境决定。
 *
 * ## 日志与诊断
 *
 * **日志**是引擎的实时输出，打开**跟随新日志**会自动滚到最新。
 *
 * **诊断**里点**采集诊断**生成一份有限长度的报告。自动打码不能保证隐私：先通读全文，勾选**我已人工检查此报告**，才能**分享已检查的报告**、**保存已检查的报告**，或**将已检查的报告提供给本机引擎**。
 *
 * ## 重置运行环境
 *
 * 在**引擎**一栏下方。先选择保留哪些内容：
 *
 * - **保留界面设置与技能**
 * - **保留插件及其设置**
 *
 * 点**重置运行环境**后，引擎停止，运行环境和全部对话历史都会删除，之后需要重新安装运行环境。重置无法撤销，需要的对话先导出（见
 * [对话](01-chat.md)）。如果恢复保留数据失败，会出现**丢弃保留数据并重新安装**。
 *
 * ## 卸载本机运行环境
 *
 * **卸载本机运行环境**会停止引擎，永久删除全部本机对话、工作区、插件、设置与技能，且不会重新安装。之后想再用本机引擎，回到同一页点**安装内置运行环境**。
 *
 * ## 远程主机
 *
 * `{{path:settings,navigation_compose_services}}`
 *
 * 服务端独立部署本项目的 compose profile，使用 Node.js 24.9 或更新版本运行引擎，使用 npm 安装依赖。 部署、启动和更新由服务端管理。远程地址使用
 * HTTPS；本机回环与 Tailscale 地址可使用 HTTP。列表显示各主机的连接状态。
 *
 * 1. 在部署端运行 `node compose.mjs pair --home /path/to/dsh-home`，生成五分钟内有效的 `xxxx-xxxx` 一次性配对码。
 * 2. 在远程主机页点右上角 **+**，填写名称、主机地址和配对码。Tailscale 地址可直接输入主机名或 IP，无需填写协议前缀。
 * 3. 点**保存并连接**。设备凭据在手机上加密保存；模型服务凭据留在服务端。
 *
 * **重新配对**需要新的配对码。**撤销并移除**先要求服务端撤销设备授权，再删除本机记录；服务端不可达时会显示失败。此时可选择**仅移除本机记录**并确认，清除保存的凭据和缓存会话（含待发送内容）；服务端授权仍保留。服务由部署端继续管理，移除
 * App 连接不会停止服务进程。
 *
 * @guideEn
 *
 * # Engine and remote hosts
 *
 * DSH can run on this phone (the local engine) or on your computer or server, with the phone
 * connecting to it remotely. Conversations, workspaces and plugins belong to the host they were
 * made on.
 *
 * ## This phone or remote
 *
 * The first time you open the app, choose under "Where should the engine run?":
 *
 * - **Run on this phone**: installs the runtime and downloads engine dependencies. It uses local
 *   storage, and you can keep setting up while it installs. On a phone with less than 5 GiB of
 *   memory, the page recommends a remote engine.
 * - **Connect to a remote engine**: connects to DSH on a computer or server; nothing is installed
 *   on the phone.
 *
 * Next you set the appearance, review phone access and configure a model provider; for the provider
 * you can tap **Set up later** and add one afterwards.
 *
 * ## Switching hosts
 *
 * The bottom of the conversation list shows the current host's name and state, such as "This phone
 * · Running" or "Connected" for a compose service.
 *
 * - Tap the name to open **Switch host**: it lists **This phone** and your paired compose services,
 *   with the current one selected; tap another to switch to it. Saved device credentials authorize
 *   subsequent connections. If this phone has no runtime installed yet, tapping **This phone**
 *   opens the **Engine** page to install it.
 * - **Compose services** opens the service list below; add your first host from there.
 * - The round icon left of the name opens the **Engine** page.
 *
 * ## Local engine
 *
 * `{{path:engine}}` · [Open](dsh://open/engine)
 *
 * The **Engine** tab at the bottom has **Engine**, **Logs** and **Diagnostics** sections. Under
 * **Engine**:
 *
 * - Status, the installed and bundled versions, and free storage.
 * - **Install bundled runtime**, **Start engine**, **Stop engine**, **Restart engine**.
 *   Installation continues when you leave the page; unpacking cannot be paused.
 * - **Direct execution**: can only be changed while the engine is stopped; availability depends on
 *   the current runtime.
 *
 * ## Logs and diagnostics
 *
 * **Logs** is the engine's live output; turn on **Follow new log entries** to keep it scrolled to
 * the latest.
 *
 * Under **Diagnostics**, tap **Collect diagnostics** for a bounded report. Automatic masking cannot
 * guarantee privacy: read it all, tick **I have manually reviewed this report**, and only then can
 * you **Share reviewed report**, **Save reviewed report** or **Make reviewed report available to
 * local engine**.
 *
 * ## Reset runtime
 *
 * At the bottom of the **Engine** section. First choose what to keep:
 *
 * - **Keep interface settings and skills**
 * - **Keep plugins and their settings**
 *
 * **Reset runtime** stops the engine and deletes its runtime and all conversation history; the
 * runtime then has to be installed again. A reset cannot be undone, so export any conversation you
 * need first (see [Chat](01-chat.md)). If restoring the kept data fails, **Discard retained data
 * and reinstall** appears.
 *
 * ## Uninstall local runtime
 *
 * **Uninstall local runtime** stops the engine and permanently deletes all local conversations,
 * workspaces, plugins, settings and skills, without reinstalling. To use the local engine again,
 * tap **Install bundled runtime** on the same page.
 *
 * ## Remote hosts
 *
 * `{{path:settings,navigation_compose_services}}`
 *
 * Deploy this project's compose profile independently. The engine requires Node.js 24.9 or later;
 * npm installs its dependencies. Manage installation, startup and updates on the service host.
 * Remote addresses use HTTPS; loopback and Tailscale addresses may use HTTP. Each host shows its
 * connection status.
 *
 * 1. On the service host, run `node compose.mjs pair --home /path/to/dsh-home` to issue a one-use
 *    `xxxx-xxxx` code valid for five minutes.
 * 2. In **Remote hosts**, tap **+** in the top bar and enter a name, host address and pairing code.
 *    Tailscale hostnames and IP addresses need no protocol prefix.
 * 3. Tap **Save and connect**. Device credentials are encrypted on the phone; model credentials
 *    stay on the service.
 *
 * **Pair again** requires a new code. **Revoke and remove** first revokes the device on the
 * service, then removes the local record. For an unreachable service, **Remove local record**
 * requires confirmation and clears the saved credential and cached conversations, including queued
 * messages; the server-side authorization remains active. Removing the connection leaves the
 * independently managed service running.
 *
 * @guide 11-faq @guideZh
 *
 * # 常见问题
 *
 * ## 发不出消息，或一直没有回复
 *
 * `{{path:engine}}` · [打开](dsh://open/engine)
 *
 * - **本机**：在**引擎**页查看状态和**日志**。没装运行环境、引擎已停止、启动失败是不同情况，按页面提示处理。
 * - **Compose 服务**：在 [Compose 服务](03-engine-hosts.md)页检查连接状态，授权失效时使用新配对码重新配对。
 * - 模型没配好时，**选择模型**里没有可用模型，先在[模型服务商](02-models.md)里添加服务商和凭据。
 *
 * 反馈问题时，在**诊断**里点**采集诊断**，通读后勾选**我已人工检查此报告**再分享或保存。自动打码不能保证隐私。
 *
 * 重置和卸载会删除本机数据，排查时不必先用它们。
 *
 * ## 切到后台后对话中断，或收不到通知
 *
 * 对话页顶部出现**让对话在后台也能跑完**或**后台联网被系统限制**时，点**去放行**。在[手机操控](04-phone-control.md)的**授权**里可以开启**不受电池优化限制**，部分厂商还有**厂商后台运行设置**、**自启动设置**。各品牌限制不同，做完这些仍可能被系统结束。
 *
 * 通知还要在[通知与实时活动](10-settings.md)里打开**对话通知**，并在系统设置里允许 dsh-compose 通知。远程主机的通知只在 App 运行且连着那台主机时提供。
 *
 * ## 引擎反复启动失败
 *
 * 在**引擎**页查看**日志**，或在**诊断**中采集并检查报告。修复配置后手动重启引擎。见 [本机引擎与远程主机](03-engine-hosts.md)。
 *
 * ## 手机操控用不了
 *
 * 在**手机操控**的**授权**里逐项检查，尤其是**无障碍服务**；手机操控桥随本机引擎启动，连着远程主机时不能操控这台手机。见 [手机操控](04-phone-control.md)。
 *
 * ## 插件装好了，界面里看不到
 *
 * 先确认插件已启用，需要时重启引擎。插件的原生界面需要主机带有 dsh-compose 桥接：本机引擎与本项目独立部署的 compose profile
 * 都自带。也可能在**原生插件控件**里关掉了某个位置。见 [插件](08-plugins.md)。
 *
 * ## 离线时历史比主机上少
 *
 * 没有连接时界面读的是本地镜像，每个对话只保留最近 5000 条记录，避免数据库无限增长。要看更早的历史请连接主机，主机上的内容没有删除。
 *
 * ## 删掉主机后，离线列表里它的对话也没了
 *
 * 这是预期行为：撤销并移除 compose 服务时会同时删除它的凭据和本地缓存的对话（标题、历史、待发送写入）。重新添加主机并连上后，列表会重新下载。
 *
 * ## 手册里的入口和屏幕上不一样
 *
 * 在系统设置的应用信息里查看 App 版本，再对照 [目录](../README.md) 里的适用版本。界面以 App 为准。
 *
 * @guideEn
 *
 * # Troubleshooting
 *
 * ## Messages don't send, or no reply arrives
 *
 * `{{path:engine}}` · [Open](dsh://open/engine)
 *
 * - **This phone**: check the status and **Logs** on the **Engine** page. No runtime installed,
 *   engine stopped and failed start are different cases; follow the page.
 * - **Compose services**: check connection status on the [Compose services](03-engine-hosts.md)
 *   page; pair again with a new code if device authorization has expired.
 * - If no model is set up, **Choose model** has nothing to pick; add a provider and credential
 *   under [Model providers](02-models.md) first.
 *
 * To report a problem, tap **Collect diagnostics** under **Diagnostics**, read it through, tick **I
 * have manually reviewed this report** and then share or save it. Automatic redaction does not
 * guarantee privacy.
 *
 * Resetting and uninstalling delete local data; you don't need them to troubleshoot.
 *
 * ## Conversations stop in the background, or notifications don't arrive
 *
 * When the chat shows **Let chats finish in the background** or **Background network access is
 * restricted**, tap **Allow access**. Under **Access** in [Phone control](04-phone-control.md) you
 * can turn on **Allow background operation without battery optimization**; some manufacturers also
 * have **Manufacturer background settings** and **Autostart settings**. Brands differ, and the
 * system may still stop the app.
 *
 * For notifications, also turn on **Conversation notifications** in
 * [Notifications and live activity](10-settings.md) and allow dsh-compose notifications in Android
 * settings. Remote host notifications arrive only while the app is running and connected to that
 * host.
 *
 * ## The engine keeps failing to start
 *
 * Open **Logs** on the **Engine** page, or collect and review a report under **Diagnostics**.
 * Correct the configuration and restart the engine manually. See
 * [Engine and remote hosts](03-engine-hosts.md).
 *
 * ## Phone control doesn't work
 *
 * Check each item under **Access** in **Phone controls**, especially **Accessibility service**. The
 * phone control bridge starts with the local engine; while connected to a remote host, it cannot
 * control this phone. See [Phone control](04-phone-control.md).
 *
 * ## A plugin is installed but doesn't show up
 *
 * Make sure it is enabled, and restart the engine if needed. Native plugin interfaces need the
 * dsh-compose bridge on the host: both the local engine and the independently deployed compose
 * profile include it. A location may also be switched off in **Native plugin controls**. See
 * [Plugins](08-plugins.md).
 *
 * ## Offline history stops earlier than the host's history
 *
 * While offline the app reads a mirrored copy, and the mirror keeps the newest 5000 records per
 * conversation so the database does not grow without bound. Connect to the host to read further
 * back; nothing on the host is deleted.
 *
 * ## Removing a host also removed its conversations from the offline list
 *
 * That is intended: revoking and removing a compose service deletes its credentials and the local
 * copy of its conversations (titles, history, queued writes). Add the host again and the list is
 * downloaded once you connect.
 *
 * ## The guide doesn't match the screen
 *
 * Check the app version in the system app info, and compare it with the version in the
 * [index](../README.md). The app is authoritative.
 */
@Serializable internal data object Engine : NavKey
