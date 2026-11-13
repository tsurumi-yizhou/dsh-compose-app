package dev.fughetta.dshcompose.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * @guide 10-settings @guideZh
 *
 * # 设置
 *
 * 点底部的**设置**打开。每一行进入一个子页，修改的多是当前所连主机上的设置。
 *
 * `{{path:settings}}` · [打开](dsh://open/settings)
 *
 * ## 设置一览
 *
 * 可通过顶部的搜索按钮查找功能。设置首页各行按用途分组：
 *
 * - **通用**：App 语言、通知与实时活动。
 * - **主机**：本机引擎的安装与运行、远程主机配对与连接。
 * - **AI**：模型服务商（含账户与订阅、API 和默认模型）、新对话的默认权限、个人偏好、Agent 预设。个人偏好属于本机引擎；其余条目按页面标注的主机管理。
 * - **能力**：此手机的操控授权、当前主机的插件安装与配置、图像与视频生成设置。
 * - **显示**：App 外观、快捷入口、插件在 App 中的显示开关。
 * - **高级**：高级引擎配置，供查看和编辑专用设置页面之外的底层字段。
 * - **工具与内容**：终端、定时任务、插件操作面板、生成作品。这些入口用于操作和浏览，不是设置开关。
 * - **帮助与关于**：使用帮助、关于；开源许可位于关于页面内。
 *
 * ## 权限
 *
 * `{{path:settings,permissions_title}}`
 *
 * 修改作用于所选主机。权限以整页打开：从设置进入时修改新对话的默认权限；从对话操作进入时，还会显示该对话的权限，两者分开设置。可选：
 *
 * - **仅可查看**：只看文件，不做修改。
 * - **工作区内修改**：可改工作区内的文件，越界先问你。
 * - **自动审查**：由模型逐次审查，拿不准再问你；可能误判。
 * - **完全权限**：不再确认，直接执行。选它前要确认**开启完全权限？**。
 *
 * 当前用的是**自定义**权限时，选预设会替换它。引擎不允许修改默认权限时，这里只读。对话里也可以从**对话操作**的**权限**进入。
 *
 * ## Agent 预设
 *
 * `{{path:settings,agent_presets}}` · [打开](dsh://open/agent-presets)
 *
 * 列出主机上的预设，可以**查看
 * YAML**、**设为默认**、**复制**、**编辑**。编辑时可改**名称**、**说明**、**系统提示词**、**工具**、**技能**和**上下文模式**，其余配置保留主机提供的原文。点**新建预设**，或**交给创作模式新建**，让
 * AI 在一个创作对话里帮你写。自定义组合包提供的预设可以**移除预设组合包**，已有对话保留。
 *
 * ## 定时任务
 *
 * `{{path:settings,navigation_schedules}}` · [打开](dsh://open/schedules)
 *
 * 让引擎按时把一段提示发给 AI。点**创建任务**，填写任务名称和**提示内容**，选重复方式（**固定间隔**、**每天**、**工作日**、**Cron
 * 表达式**等）、**时区**和**投递目标**，下方会预览**接下来的触发时间**，点**保存**。
 *
 * 列表可以按**全部**、**启用中**、**已暂停或结束**筛选；每个任务可以**编辑**、**立即运行**、**暂停**、**恢复**、**删除任务**，并查看**投递历史**。经部分远程连接只能查看，不能管理。
 *
 * ## 引擎配置
 *
 * `{{path:settings,navigation_engine_configuration}}` · [打开](dsh://open/engine-settings)
 *
 * 以紧凑列表逐行列出引擎设置：首页是命名空间和插件分组，点一行进入；组内每个字段一行，开关在行尾直接切换，其余字段点行尾的**编辑**修改。**使用继承值**恢复默认，改完点**保存**。凭据只由引擎保存，可以**修改凭据**或**清除**。结果尚未确认时，先放弃草稿并刷新。
 *
 * ## 终端
 *
 * `{{path:settings,navigation_terminal}}`
 *
 * 在所选主机上打开 Shell。点**新建终端**，可以**粘贴**、调出**键盘**、**重命名**；离开页面进程会保留，回来点**重新连接／接管输入**。**关闭终端**会结束 Shell
 * 及其子进程。远端程序要写手机剪贴板或打开链接时会先问你。对话里也可以从**对话操作**的**终端**进入。
 *
 * ## 通知与实时活动
 *
 * `{{path:settings,navigation_notifications}}` · [打开](dsh://open/notifications)
 *
 * 打开**对话通知**，按需选择**一轮完成**、**出错或被停下**、**等待确认**、**向你提问**。系统关了通知时，点**允许通知**或**打开通知设置**。**实时活动**在状态栏和系统提供的实时活动区域显示当前任务、等待中的请求和手机操控。
 *
 * 通知预览会隐藏可识别的凭据，并遵循系统锁屏隐私设置；手机操控确认不受对话通知开关影响。远程主机的通知只在 App 运行且连着那台主机时提供。
 *
 * ## 语言
 *
 * - **语言**：**跟随系统**、中文或 English。
 *
 * @guideEn
 *
 * # Settings
 *
 * Tap **Settings** at the bottom. Each row opens a page; most settings apply to the host you are
 * connected to.
 *
 * `{{path:settings}}` · [Open](dsh://open/settings)
 *
 * ## At a glance
 *
 * Use the search button at the top to find features. Settings rows are grouped by purpose:
 *
 * - **General**: app language, notifications and live activity.
 * - **Hosts**: local engine installation and operation, remote host pairing and connections.
 * - **AI**: model providers (accounts, subscriptions, APIs and default models), default
 *   permissions, personal instructions and Agent presets. Personal instructions belong to the local
 *   engine; other entries identify their host scope.
 * - **Capabilities**: access to this phone, plugin installation and configuration, and image/video
 *   generation settings.
 * - **Display**: app appearance, quick panel entries and plugin display controls.
 * - **Advanced**: engine fields beyond the dedicated settings pages.
 * - **Tools and content**: terminals, scheduled tasks, plugin interfaces and generated works. These
 *   open tools and content rather than setting switches.
 * - **Help and about**: help and about; open-source licenses are inside About.
 *
 * ## Permissions
 *
 * `{{path:settings,permissions_title}}`
 *
 * Changes apply to the selected host. Permissions open as a full page: with a conversation open,
 * the upper part sets that conversation's permission and the lower part the default for new
 * conversations, each on its own. The choices:
 *
 * - **Read only**: view files without changing them.
 * - **Workspace write**: edit workspace files; ask before going beyond.
 * - **Auto review**: the model reviews each action and asks when unsure; it may get it wrong.
 * - **Full access**: run without asking. You confirm **Turn on Full access?** first.
 *
 * When **Custom** permissions are in use, choosing a preset replaces them. If the engine does not
 * allow changing the default, it is read only here. In a conversation, you can also get here from
 * **Permissions** under **Conversation actions**.
 *
 * ## Agent presets
 *
 * `{{path:settings,agent_presets}}` · [Open](dsh://open/agent-presets)
 *
 * Lists the host's presets; you can **View YAML**, **Use by default**, **Duplicate** or **Edit**.
 * Editing covers **Name**, **Description**, **System prompt**, **Tools**, **Skills** and **Context
 * modes**; other entries are kept as the host provides them. Tap **Create preset**, or **Create in
 * creator mode** to have the AI write it with you in a creator conversation. A preset from a custom
 * bundle can be removed with **Remove preset bundle**; existing conversations stay.
 *
 * ## Scheduled tasks
 *
 * `{{path:settings,navigation_schedules}}` · [Open](dsh://open/schedules)
 *
 * Have the engine send a prompt to the AI on a schedule. Tap **Create task**, fill in the task name
 * and **Prompt**, choose how it repeats (**Interval**, **Daily**, **Weekdays**, **Cron** and so
 * on), the **Time zone** and the **Delivery destination**; **Next occurrences** previews the
 * schedule. Tap **Save**.
 *
 * Filter the list by **All**, **Active** or **Paused or ended**. Each task can be edited with
 * **Edit**, started with **Run now**, paused with **Pause**, restarted with **Resume** or removed
 * with **Delete task**, and has its **Delivery history**. Some remote connections can only view
 * tasks.
 *
 * ## Engine configuration
 *
 * `{{path:settings,navigation_engine_configuration}}` · [Open](dsh://open/engine-settings)
 *
 * Engine settings are compact rows: the first page lists namespace and plugin groups, and tapping a
 * row drills in. Inside, each field is a row — switches toggle at its trailing edge, and other
 * fields open with **Edit** there. **Use inherited value** goes back to the default; tap **Save**
 * when done. Credentials are stored only by the engine; use **Change credential** or **Clear**. If
 * a result is unconfirmed, discard the draft and refresh first.
 *
 * ## Terminal
 *
 * `{{path:settings,navigation_terminal}}`
 *
 * Opens a shell on the selected host. Tap **New terminal**; you can **Paste**, bring up the
 * **Keyboard** and **Rename** it. Leaving the page keeps the process; come back and tap **Reconnect
 * / take input**. **Close terminal** ends the shell and its child processes. You are asked before a
 * remote program writes to the phone clipboard or opens a link. In a conversation, you can also get
 * here from **Terminal** under **Conversation actions**.
 *
 * ## Notifications and live activity
 *
 * `{{path:settings,navigation_notifications}}` · [Open](dsh://open/notifications)
 *
 * Turn on **Conversation notifications** and choose **When a turn completes**, **When a turn fails
 * or stops**, **When approval is needed** and **When an answer is needed**. If Android blocked
 * notifications, tap **Allow notifications** or **Open notification settings**. **Live activities**
 * shows current work, pending requests and phone control in the status bar and the system's live
 * activity area.
 *
 * Notification previews hide recognized credentials and follow Android's lock screen privacy; phone
 * control confirmations do not depend on the conversation notification switch. Remote host
 * notifications arrive only while the app is running and connected to that host.
 *
 * ## Language
 *
 * - **Language**: **Follow system**, 中文 or English.
 */
@Serializable internal data object Settings : NavKey

@Serializable internal data object LanguageSettings : NavKey
