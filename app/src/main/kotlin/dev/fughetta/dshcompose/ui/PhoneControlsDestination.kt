package dev.fughetta.dshcompose.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * @guide 04-phone-control @guideZh
 *
 * # 手机操控
 *
 * 让本机引擎通过无障碍服务读取屏幕、启动应用、点击、滑动和输入，在你的手机上完成任务，随时可以暂停或停止。手机操控只在本机引擎可用。
 *
 * ## 开启
 *
 * `{{path:settings,navigation_phone_controls}}` · [打开](dsh://open/phone)
 *
 * 1. 在**概览**里打开**启用手机操控**，阅读说明后点**继续**。手机操控桥随本机引擎启动，开启时会重启正在运行的本机引擎。
 * 2. 点**授权**，逐项开启：**无障碍服务**（在系统无障碍设置里开启
 *    dsh-compose）、**通知**、**显示在其他应用上层**、**不受电池优化限制**。系统阻止开启无障碍时，点**打开应用详情**，在菜单里允许受限设置，再回到无障碍设置。
 * 3. 点**读取当前屏幕**，看看能读到什么。
 *
 * 执行任务时，为完成任务，屏幕内容可能发送给模型服务商。
 *
 * 部分厂商还有**厂商后台运行设置**、**自启动设置**、**后台弹出界面设置**。App 读不到这些开关，检查后勾选**我已检查此系统设置**只是给自己做个记号，不代表系统已授权。
 *
 * **授予安全设置权限**后，系统停止无障碍服务时，dsh-compose 会尝试把它恢复。
 *
 * ## 让 DSH 做事
 *
 * 回到对话，直接说要做的事。任务进行中，**概览**里的**手机任务**显示当前状态，点**停止手机任务**结束。
 *
 * 需要你确认时，屏幕上出现**DSH 请你确认**卡片，可以选**允许一次**、**允许，本任务不再询问**或**拒绝**；30
 * 秒内不选按**拒绝**处理。遇到密码、验证码或系统设置这类需要你亲自完成的步骤，会出现**需要你来操作**，完成后点**继续**。
 *
 * ## 悬浮条
 *
 * 任务进行时，屏幕上出现悬浮条，显示 DSH 正在做的事，可以**暂停**、**继续**、**停止**，也能在插话框里告诉 DSH 换个做法。
 *
 * 在**悬浮条**一栏里调整：
 *
 * - **闲置透明度**：闲置时悬浮条变淡到的程度。
 * - **闲置时折叠悬浮条**
 *
 * ## 确认方式
 *
 * 在**概览**的**确认方式**里选：
 *
 * - **询问后继续**：写入前先问你。
 * - **允许本任务免确认**：确认卡上提供「本任务不再询问」。
 * - **写入操作免确认**：写入动作不再询问，可能发送消息、修改设置或文件；开启前会再确认一次。应用规则和敏感区域限制仍然生效。
 *
 * **任务完成后返回 dsh-compose**：任务结束时尝试回到 App。
 *
 * 安装 APK 时，DSH 会先查询手机工具连接提供的设备，并在每次安装请求中指定目标设备 ID。请核对确认卡上的目标设备名称和
 * ID；安装始终需要单次批准及系统确认，不受免确认设置影响。在多端打开或重启后恢复同一对话，不会替你选择安装目标。当前手机工具由手机本机引擎提供，Compose
 * 服务对话还没有连接客户端手机的安装通道。
 *
 * ## 应用规则
 *
 * 在**应用规则**一栏里，为每个应用选**允许**、**每次确认**或**禁止**。**禁止**会阻止操控该应用；**每次确认**要求该应用内的每次写入单独确认。可以**搜索应用或包名**，或填写**应用包名**后点**添加包名**。放宽规则前会再确认一次。**恢复默认应用规则**会用内置规则替换你的自定义规则。
 *
 * ## 限制
 *
 * 在**限制**一栏。默认禁止操控敏感区域，三项可以分别解除：
 *
 * - **允许系统授权及设置页**：AI 可以修改权限、无障碍及开发者选项，安装、更新或卸载应用。
 * - **允许密码等敏感字段**：AI 可以填写密码、验证码及支付密码。操作记录仍会隐藏输入的敏感值。
 * - **允许操控 dsh-compose 自身**：AI 可以读取 App 内的对话及设置，并修改设置。
 *
 * 解除任一项前会说明风险，需要**按住 1.5 秒开启**，提前松开即取消。手机任务运行期间无法解除这些限制。开启后，AI
 * 可在所选策略允许时无需你确认就执行这些操作，也可能被网页、消息或文件中的内容误导；由此造成的账号、资金、数据或设备损失由你自行承担。建议只在需要时开启，用完关闭。
 *
 * ## 动作记录
 *
 * **动作记录**只在内存里保留最近 300 条动作，不记录屏幕原文或输入内容。
 *
 * ## 特权操控
 *
 * **特权操控**让其他应用位于前台时仍能执行手机动作。选一个通道并完成对应的系统授权：
 *
 * - Shizuku / Sui：先启动 Shizuku 或 Sui，再点**申请 Shizuku / Sui 授权**。
 * - **Root**：点**准备 Root 通道**，阅读说明后在 Root 管理器里授权。只在你主动选择或准备这个通道时才申请 Root。
 * - **无线
 *   ADB**：点**打开开发者选项**打开无线调试，再点**开始配对**，输入六位**配对码**后点**配对**，然后**连接**。只有自动发现找不到服务时才需要填**端口（可选）**。
 *
 * **保持所选通道连接**会在连接断开后自动重连。
 *
 * ## 文件访问
 *
 * 在**授权**页里，**允许本机引擎访问手机文件**会把共享存储挂载到本机引擎的
 * `/sdcard`，本机引擎及其工具将能读取和修改手机共享文件。开启时需要**打开所有文件访问授权**；更改会重启正在运行的本机引擎。
 *
 * @guideEn
 *
 * # Phone control
 *
 * Lets the local engine read the screen, launch apps, tap, swipe and type through the accessibility
 * service, finishing tasks on your phone; you can pause or stop it at any time. Phone control works
 * only with the local engine.
 *
 * ## Turning it on
 *
 * `{{path:settings,navigation_phone_controls}}` · [Open](dsh://open/phone)
 *
 * 1. Under **Overview**, turn on **Enable phone control**, read the disclosure and tap
 *    **Continue**. The phone bridge starts with the local engine, so turning it on restarts a
 *    running local engine.
 * 2. Tap **Access** and grant each item: **Accessibility service** (enable dsh-compose in Android
 *    accessibility settings), **Notifications**, **Display over other apps** and **Allow background
 *    operation without battery optimization**. If Android blocks the accessibility switch, tap
 *    **Open App details**, allow restricted settings from the menu, then go back to accessibility
 *    settings.
 * 3. Tap **Read current screen** to see what can be read.
 *
 * While a task runs, screen content may be sent to the model provider to complete it.
 *
 * Some manufacturers also have **Manufacturer background settings**, **Autostart settings** and
 * **Background popup settings**. The app cannot read these switches; ticking **I have reviewed this
 * system setting** is only your own note, not proof that Android granted anything.
 *
 * After **Grant secure settings permission**, dsh-compose tries to restore its accessibility
 * service when Android stops it.
 *
 * ## Letting DSH do things
 *
 * Go back to the chat and say what you want done. While a task runs, **Phone task** under
 * **Overview** shows its state; tap **Stop phone task** to end it.
 *
 * When DSH needs your approval, a **DSH needs approval** card appears: choose **Allow once**,
 * **Allow for task** or **Decline**; with no choice in 30 seconds it counts as **Decline**. For a
 * step you must do yourself, such as a password, a code or a system setting, **Your turn** appears;
 * tap **Continue** when done.
 *
 * ## Floating controls
 *
 * While a task runs, floating controls show what DSH is doing, with **Pause**, **Continue** and
 * **Stop**, and a box to tell DSH to try another way.
 *
 * Under **Floating controls**:
 *
 * - **Idle opacity**: how faint the controls get when idle.
 * - **Collapse the controls when idle**
 *
 * ## Confirmation policy
 *
 * Under **Confirmation policy** in **Overview**:
 *
 * - **Ask before continuing**: asks before writes.
 * - **Offer to waive confirmation for the task**: the approval card offers to stop asking for the
 *   rest of the task.
 * - **Allow writes without confirmation**: write actions run without asking and may send messages
 *   or change settings or files; you confirm once more before it turns on. App rules and
 *   sensitive-area restrictions still apply.
 *
 * **Return to dsh-compose when the task ends** tries to bring the app back when a task finishes.
 *
 * For APK installation, DSH first queries the devices served by the phone tool connection and
 * specifies a target device ID in every install request. Check the target device name and ID on the
 * confirmation card. Installation always requires approval for that request and Android
 * confirmation, even when other confirmations are waived. Opening the same conversation on another
 * client or restoring it after a restart does not select an installation target. Phone tools
 * currently come from the phone's local engine; compose service conversations do not yet have an
 * installation channel to client phones.
 *
 * ## App rules
 *
 * Under **App rules**, choose **Allow**, **Confirm each write** or **Deny** for each app. **Deny**
 * blocks control of the app; **Confirm each write** requires confirmation for every write inside
 * it. Use **Search app or package**, or enter a **Package name** and tap **Add package**. You
 * confirm once more before loosening a rule. **Restore default app rules** replaces your custom
 * rules with the built-in ones.
 *
 * ## Restrictions
 *
 * Under **Restrictions**. Sensitive areas are blocked by default; each of the three can be lifted
 * separately:
 *
 * - **Allow system permission and settings pages**: AI may change permissions, accessibility and
 *   developer options, and install, update or uninstall apps.
 * - **Allow password and other secret fields**: AI may type passwords, verification codes and
 *   payment codes. The action history still hides the typed values.
 * - **Allow controlling dsh-compose itself**: AI may read conversations and settings inside the app
 *   and change settings.
 *
 * Before lifting one, the risks are explained and you must **Hold 1.5 seconds to enable**;
 * releasing early cancels. They cannot be lifted while a phone task runs. Once enabled, AI can
 * perform these operations without your confirmation when the selected policy allows, and can be
 * misled by content in web pages, messages or files; any loss of accounts, funds, data or devices
 * is your own responsibility. Turn them on only when needed and off afterwards.
 *
 * ## Action history
 *
 * **Action history** keeps the latest 300 actions in memory only, without screen text or typed
 * content.
 *
 * ## Privileged control
 *
 * **Privileged control** keeps phone actions available while another app is in front. Pick a
 * channel and complete its system authorization:
 *
 * - Shizuku / Sui: start Shizuku or Sui, then tap **Request Shizuku / Sui authorization**.
 * - **Root**: tap **Prepare Root channel**, read the warning, then grant it in your root manager.
 *   Root is requested only when you explicitly select or prepare this channel.
 * - **Wireless ADB**: tap **Open Developer options** to turn on wireless debugging, then **Start
 *   pairing**, enter the six-digit **Pairing code**, tap **Pair**, then **Connect**. Fill in **Port
 *   (optional)** only when discovery cannot find the service.
 *
 * **Keep the selected channel connected** reconnects after the channel drops.
 *
 * ## File access
 *
 * On the **Access** page, **Expose phone files to the local engine** mounts shared storage at
 * `/sdcard` in the local engine, so the engine and its tools can read and modify your shared files.
 * It needs **Open all files access**; changing it restarts a running local engine.
 */
@Serializable
internal data class PhoneControls(
    val subpage: dev.fughetta.dshcompose.ui.phone.PhoneSubpage? = null,
) : NavKey
