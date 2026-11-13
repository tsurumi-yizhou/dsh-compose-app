package dev.fughetta.dshcompose.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * @guide 01-chat @guideZh
 *
 * # 对话
 *
 * 向 AI 提问，发图片和文件，AI 工作时随时补充要求或停下。对话属于当前主机：换了主机，列表里就是那台主机的对话。
 *
 * ## 对话列表
 *
 * `{{path:conversations}}` · [打开](dsh://open/conversations)
 *
 * 底部的**对话**页列出当前主机的对话，运行中的对话右侧有加载标记。点右下角的**新对话**开始；发出第一条消息时才建立对话。
 *
 * - **搜索对话**：按标题、目录或消息正文搜索。主机没有正文搜索时只匹配标题和目录。归档的对话点**恢复后打开**。
 * - **刷新**：重新读取列表。
 * - 右上角菜单里的**工作区**和**选择目录**：见下文。
 * - **归档对话**：切换显示已归档的对话。
 *
 * ## 工作区
 *
 * `{{path:conversations,workspaces}}` · [打开](dsh://open/workspaces)
 *
 * 工作区是主机上的一个目录，对话在其中读写文件。点**添加工作区**，或在对话列表菜单里点**选择目录**，在主机的目录里新建对话。点**全部对话**回到完整列表。
 *
 * 工作区可以**置顶工作区**、**重命名工作区**、设**分组**、**上移**或**下移**；**移除工作区**只移除登记，目录和对话都会保留。工作区里的对话可以**置顶对话**、**归档对话**或**恢复对话**；归档时可以选**归档时同时停止活动**。
 *
 * ## 发消息
 *
 * 在**发送消息给 DeepSeek**里写好内容，点**发送**。输入框下方：
 *
 * - **添加附件**：选择手机上的文件或图片。当前模型不能看图时，图片会作为文件发给 AI。
 * - `@`：打开**插入引用**，引用工作区的**文件**或其他**对话**。
 * - 模型按钮：显示当前模型和思考深度，没选时显示**选择模型**，见 [模型与服务商](02-models.md)。
 *
 * 按 Enter 发送（屏幕键盘和实体键盘一样）；接了实体键盘时按 Ctrl+Enter 换行，输入法正在组字时回车仍交给输入法。
 *
 * 输入 `/` 会列出当前主机可用的指令。
 *
 * ## AI 工作时补充或停下
 *
 * AI 生成时点**停止**结束这一轮。这时发出的消息会进入**待处理消息**，显示在输入框上方：可以**用于当前轮次**，让 AI
 * 下一步就看到；也可以**编辑排队文本**、**移除排队消息**，或**撤回并恢复草稿**改完再发。主机是否已收到不确定时，点**重试原请求**，不会重复发送。
 *
 * ## 回答提问、处理授权
 *
 * 提问卡：选一个答案，或在**填写回答**里自己写，再点**提交回答**。
 *
 * 授权卡显示「允许某个工具执行操作？」：看清命令、文件和原因，再选**仅允许本次**或**拒绝**。想少问几次，在对话操作的**权限**里换档，见 [设置](10-settings.md)。
 *
 * ## 目标
 *
 * 对话设了长期目标时，输入框上方会显示**目标**和进度。点开可以**编辑目标**、**暂停**、**恢复**或**清除目标**。
 *
 * ## 消息操作
 *
 * 长按一条消息打开菜单：**复制**、**分享**、**引用**、**从此回复分叉**，以及在新对话中编辑重发或重新回答。分叉、编辑重发和重新回答都在新对话里继续，原对话不变；切过去后可以点返回原对话的按钮回去。
 *
 * 往上翻点**加载更早的消息**；离开底部后点**回到最新**。
 *
 * ## 离线使用
 *
 * 没有连接时，App 仍会显示各主机上次同步的对话列表和历史；打开对话读的是本地副本，**加载更早的消息**会在已镜像的内容里翻页。
 *
 * - 离线时输入的纯文本消息会作为待发送消息留在输入框，重连后自动送达。附件、斜杠命令和媒体生成需要连接。
 * - 离线重命名对话会先在本地生效并标记**待同步**，重连后自动重放；主机拒绝的重命名不会再重试。
 * - 连接后 App 会自行刷新列表，并在后台继续同步（周期任务，回到前台时也会刷新一次），让通知和列表保持最新。
 *
 * ## 对话操作
 *
 * 点聊天页右上角的**对话操作**：
 *
 * - **交付文件**：本轮生成的文件，可以预览、**保存**、**分享**、**复制路径**。
 * - **折叠已完成轮次**：只展开最新一轮。
 * - **代理与团队**：查看**子代理**、**团队成员**、**任务看板**和**团队消息**。
 * - **终端**、**插件面板**、**定时任务**、**权限**：见 [设置](10-settings.md) 和 [插件](08-plugins.md)。
 * - 导出日志：引擎把这个对话、子对话及附件打包成 ZIP，可以**分享 ZIP**或**保存到下载目录**。
 * - **技能**：这个对话可用的技能。
 * - **后台任务**：AI 启动的后台命令，可以看输出、**复制输出**、**保存输出**或**停止**。
 * - **对话文件**：浏览工作区文件，**保存文件到设备**。
 * - **从当前目录新建对话**、**重命名对话**、**派生对话**、**删除对话**。删除以主机的结果为准，无法撤销。
 *
 * @guideEn
 *
 * # Chat
 *
 * Ask the AI, send pictures and files, and add instructions or stop it while it works.
 * Conversations belong to the current host: switch hosts and the list shows that host's
 * conversations.
 *
 * ## Conversation list
 *
 * `{{path:conversations}}` · [Open](dsh://open/conversations)
 *
 * The **Conversations** tab lists the current host's conversations; running ones show a spinner.
 * Tap **New conversation** at the bottom right; a conversation is created when you send its first
 * message.
 *
 * - **Search conversations**: search titles, folders and message text. Hosts without message search
 *   match titles and folders only. For archived conversations, tap **Restore before opening**.
 * - **Refresh**: reload the list.
 * - **Workspaces** and **Choose folder** in the top-right menu: see below.
 * - **Archived conversations**: toggles showing archived conversations.
 *
 * ## Workspaces
 *
 * `{{path:conversations,workspaces}}` · [Open](dsh://open/workspaces)
 *
 * A workspace is a folder on the host where a conversation reads and writes files. Tap **Add
 * workspace**, or **Choose folder** in the list menu to start a conversation in a host folder.
 * **All conversations** returns to the full list.
 *
 * Workspaces can be pinned with **Pin workspace**, renamed with **Rename workspace**, put in a
 * **Group**, or moved with **Move up** and **Move down**; **Remove workspace** removes only the
 * registration and keeps the folder and conversations. Conversations in a workspace can be pinned
 * with **Pin conversation**, archived with **Archive conversation** or brought back with **Restore
 * conversation**; when archiving you can choose **Stop activity while archiving**.
 *
 * ## Sending messages
 *
 * Write in **Message DeepSeek** and tap **Send**. Below the input box:
 *
 * - **Attach files**: pick files or pictures from the phone. If the current model cannot see
 *   images, pictures are sent as files.
 * - `@`: opens **Insert a reference** to reference workspace **Files** or other **Conversations**.
 * - The model button shows the current model and thinking level, or **Choose model** when none is
 *   set; see [Models and providers](02-models.md).
 *
 * Enter sends, on the on-screen keyboard and on a hardware one alike; with a hardware keyboard,
 * Ctrl+Enter starts a new line, and an IME's Enter while composing stays with the IME.
 *
 * Type `/` to list the commands the current host offers.
 *
 * ## Adding instructions or stopping
 *
 * While the AI generates, tap **Stop** to end the turn. Messages you send meanwhile go to **Pending
 * messages** above the input box: **Use in current turn** lets the AI see it at its next step; you
 * can also **Edit queued text**, **Remove queued message**, or **Withdraw and restore** to edit and
 * resend. If it is unclear whether the host received a message, **Retry original request** resends
 * it without duplicating.
 *
 * ## Questions and approvals
 *
 * Question card: pick an answer or write your own in **Your answer**, then tap **Submit answer**.
 *
 * An approval card asks "Allow a tool?": check the command, files and reason, then choose **Allow
 * once** or **Reject**. To be asked less often, change the level under **Permissions** in
 * conversation actions; see [Settings](10-settings.md).
 *
 * ## Goals
 *
 * When a conversation has a long-running goal, the **Goal** and its progress show above the input
 * box. Open it to **Edit goal**, **Pause**, **Resume** or **Clear goal**.
 *
 * ## Message actions
 *
 * Long-press a message for **Copy**, **Share**, **Quote**, **Branch from this reply**, **Edit and
 * resend in a new conversation** and **Answer again in a new conversation**. These continue in a
 * new conversation and leave the original unchanged; tap **Return to original** to go back.
 *
 * Scroll up and tap **Load older messages**; away from the bottom, tap **Back to latest**.
 *
 * ## Offline use
 *
 * Without a connection the app still shows the last synced conversation list and history for each
 * host; opening a conversation reads the local copy and **Load older messages** pages through what
 * has been mirrored.
 *
 * - A plain text message sent while offline waits in the composer as a pending message and is
 *   delivered automatically after reconnecting. Attachments, slash commands and media generation
 *   need a connection.
 * - Renaming a conversation offline applies locally with a **Waiting to sync** marker; the rename
 *   is replayed after reconnecting. A rename the host rejects is not retried.
 * - After connecting, the app refreshes the list on its own, and keeps syncing in the background (a
 *   periodic job, plus a refresh when the app returns to the foreground) so notifications and the
 *   list stay current.
 *
 * ## Conversation actions
 *
 * Tap **Conversation actions** at the top right of the chat:
 *
 * - **Deliverables**: files produced in this turn; preview, **Save**, **Share** or **Copy path**.
 * - **Fold completed turns**: keep only the latest turn expanded.
 * - **Agents and team**: view **Subagents**, **Team members**, the **Task board** and **Team
 *   messages**.
 * - **Terminal**, **Plugin panels**, **Scheduled tasks**, **Permissions**: see
 *   [Settings](10-settings.md) and [Plugins](08-plugins.md).
 * - **Export conversation log**: the engine packs this conversation, its child conversations and
 *   attachments into a ZIP; **Share ZIP** or **Save to Downloads**.
 * - **Skills**: the skills available in this conversation.
 * - **Background tasks**: background commands the AI started; view output, **Copy output**, **Save
 *   output** or **Stop**.
 * - **Conversation files**: browse workspace files and **Save file to device**.
 * - **New conversation from this folder**, **Rename conversation**, **Branch conversation**,
 *   **Delete conversation**. Deletion follows the host's result and cannot be undone.
 */
@Serializable internal data object Conversations : NavKey
