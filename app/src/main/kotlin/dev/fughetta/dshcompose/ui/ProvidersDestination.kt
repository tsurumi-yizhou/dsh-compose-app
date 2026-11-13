package dev.fughetta.dshcompose.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * @guide 02-models @guideZh
 *
 * # 模型与服务商
 *
 * 用 DeepSeek 账户，或接入 OpenAI、Anthropic 兼容的服务。服务商和凭据都保存在当前主机的引擎里，App 不保存或读取密钥原文；连接远程主机时，管理的是那台主机的服务商。
 *
 * ## 切换模型和思考深度
 *
 * 点输入框下方显示当前模型的按钮，打开**选择模型**：先选服务商下的模型，模型支持时再选思考深度（**关闭思考**、**低**、**中**、**高**等），点**确认**。所选模型用于后续的模型请求。
 *
 * ## 新建对话模型
 *
 * `{{path:settings,providers}}` · [打开](dsh://open/providers)
 *
 * 页面顶部的**新建对话模型**设置新对话用哪个模型，不改变当前对话的模型：
 *
 * - **沿用上次选择**：对话里切换模型后，新对话沿用这次的选择。
 * - **固定模型**：点**选择模型**挑一个固定的模型和思考深度，对话里的临时切换不会覆盖它。
 *
 * 保存过的模型或思考深度不再可用时，会提示重新选择。
 *
 * ## 新增服务商
 *
 * 1. 在模型服务商页点**新增服务商**。
 * 2. 填写**路由 ID**（以小写字母开头，可用小写字母、数字和单个连字符）和**显示名称**。
 * 3. **API 协议**选 **OpenAI Chat Completions**、**OpenAI Responses** 或 **Anthropic
 *    Messages**，按服务方的说明选。
 * 4. 填写**接口根地址**（HTTP(S) 地址）。
 * 5. **凭据引用**用于标识保存的密钥，可使用自动生成的值。
 * 6. 填写 API Key 后点击**获取模型并保存**，自动获取模型列表。地址未变时可留空密钥以使用已保存的密钥。
 *
 * ## 凭据
 *
 * 在服务商卡片上点**配置凭据**，填写 **API key**
 * 后确认。修改会在后续模型请求中生效。**移除凭据**会从引擎中删除这份凭据，共用同一个引用的服务商都可能无法继续调用，服务商配置本身会保留。由引擎管理的凭据不能在这里修改。
 *
 * ## 模型参数
 *
 * 编辑服务商时点一个模型，打开**模型参数**：
 *
 * - **模型显示名称**
 * - **上下文长度**、**最大输出 token 数**：留空使用服务商默认值；可以写 131072、128K 或 1.5M。
 * - **支持图片输入**：模型能看图时打开。
 * - **提供思考深度选项**：打开后勾选服务商实际支持的深度，它们会出现在模型选择里。
 *
 * 点**确认**只更新草稿，保存服务商档案后才会应用到引擎。
 *
 * ## 编辑与移除
 *
 * **编辑服务商**修改上述内容；**放弃修改并刷新**丢掉未保存的修改。**移除服务商**删除服务商配置，已保存的凭据会保留。使用引擎提供的模型目录的服务商只能修改名称和凭据引用。
 *
 * ## DeepSeek 账户
 *
 * `{{path:settings,navigation_deepseek_account}}` · [打开](dsh://open/account)
 *
 * 点**登录**，在浏览器完成登录后即可直接使用 DeepSeek 账户，无需 API
 * 密钥。登录通过此手机上的引擎完成，凭据由引擎保管。登录后可以查看**充值余额**、**赠送余额**，点**充值**或**用量**。
 *
 * **退出登录**会退出本机引擎中的账户，已配置的 API 密钥会保留；账户任务还在运行时，退出可能中止它们。想改用 API 密钥，点**管理服务商与 API 密钥**。
 *
 * @guideEn
 *
 * # Models and providers
 *
 * Use a DeepSeek account, or connect any OpenAI- or Anthropic-compatible service. Providers and
 * credentials are stored in the current host's engine; the app never saves or reads a key's text.
 * When connected to a remote host, you manage that host's providers.
 *
 * ## Switching models and thinking effort
 *
 * Tap the button under the input box that shows the current model to open **Choose model**: pick a
 * model under a provider, then a thinking level if the model offers one (**Thinking off**, **Low**,
 * **Medium**, **High** and so on), and tap **Confirm**. The selected model is used for subsequent
 * model requests.
 *
 * ## New conversation model
 *
 * `{{path:settings,providers}}` · [Open](dsh://open/providers)
 *
 * **New conversation model** at the top sets the model for new conversations without changing the
 * current one:
 *
 * - **Last selection**: after you switch models in a conversation, new conversations reuse that
 *   choice.
 * - **Fixed model**: tap **Choose model** to pick a fixed model and thinking level; switching
 *   inside a conversation does not overwrite it.
 *
 * If the saved model or thinking level is no longer available, you are asked to choose again.
 *
 * ## Adding a provider
 *
 * 1. On the Model providers page, tap **Add provider**.
 * 2. Fill in a **Route ID** (starts with a lowercase letter; lowercase letters, digits and single
 *    hyphens) and a **Display name**.
 * 3. Choose the **API protocol**: **OpenAI Chat Completions**, **OpenAI Responses** or **Anthropic
 *    Messages**, as the service documents.
 * 4. Fill in the **Base URL** (an HTTP(S) address).
 * 5. **Credential reference** identifies the saved key; use the automatically generated value.
 * 6. Enter the API key and tap **Fetch models and save**. The model list is fetched automatically.
 *    Leave the key empty to reuse the saved key for an unchanged endpoint.
 *
 * ## Credentials
 *
 * On a provider card, tap **Configure credential**, enter the **API key** and confirm. Changes
 * apply to later model requests. **Remove credential** deletes it from the engine; every provider
 * using the same reference may stop working, while the provider configurations stay. Credentials
 * managed by the engine cannot be changed here.
 *
 * ## Model parameters
 *
 * While editing a provider, tap a model to open **Model parameters**:
 *
 * - **Model display name**
 * - **Context window** and **Maximum output tokens**: leave blank for the provider default; write
 *   131072, 128K or 1.5M.
 * - **Accepts images**: turn on if the model can see images.
 * - **Offers reasoning efforts**: tick the efforts the provider actually supports; they appear in
 *   the model picker.
 *
 * **Confirm** only updates the draft; it reaches the engine when you save the provider profile.
 *
 * ## Editing and removing
 *
 * **Edit provider** changes the fields above; **Discard edits and refresh** drops unsaved changes.
 * **Remove provider** deletes the provider configuration and keeps its saved credential. For a
 * provider whose model catalog comes from the engine, only the name and credential reference can be
 * changed.
 *
 * ## DeepSeek account
 *
 * `{{path:settings,navigation_deepseek_account}}` · [Open](dsh://open/account)
 *
 * Tap **Sign in** and finish in your browser to use your DeepSeek account without an API key.
 * Sign-in uses the engine on this phone, and the credentials stay in the engine. Once signed in,
 * you see the **Recharge balance** and **Bonus balance**, and can tap **Top up** or **Usage**.
 *
 * **Sign out** signs the local engine out of the account; configured API keys remain. If account
 * tasks are still running, signing out may stop them. To use an API key instead, tap **Manage
 * providers and API keys**.
 */
@Serializable internal data object Providers : NavKey
