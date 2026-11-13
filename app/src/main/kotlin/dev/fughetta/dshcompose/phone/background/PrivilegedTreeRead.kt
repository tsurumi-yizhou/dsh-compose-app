// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's
// permission. See THIRD_PARTY_NOTICES.md.
// Local changes: blocking, one owner per task (the bridge serialises calls on one worker
// thread); a UiAutomation control-tree read rendered to the screen text format, so text-only
// models can use 不打扰模式 too; IME policy HIDE; JPEG capture with bounded first-frame readiness;
// display
// sized to the phone's aspect (short side 720). No commands, paths or display ids are accepted
// from callers.
package dev.fughetta.dshcompose.phone.background

import android.app.UiAutomation
import android.graphics.Rect
import android.text.InputType
import android.util.SparseArray
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.fughetta.dshcompose.domain.phone.BackgroundTree
import dev.fughetta.dshcompose.domain.phone.SensitiveWords
import dev.fughetta.dshcompose.phone.background.PrivilegedDisplayCore.Fault

internal class TreeRead(val targetId: Int = 0) {
    val elements = ArrayList<BgNode>()
    var visited = 0
    var complete = true
    var hasSecrets = false
    var target: AccessibilityNodeInfo? = null
}

internal fun PrivilegedDisplayCore.readTree(displayId: Int, pkg: String): TreeRead {
    val vd = display ?: throw Fault("displayMissing")
    val conn = TreeConnection(context, pkg)
    try {
        val automation =
            try {
                conn.open(vd.display)
            } catch (_: Throwable) {
                throw Fault("treeUnavailable")
            }
        return readTree(automation, displayId, pkg)
    } finally {
        conn.close()
    }
}

internal fun PrivilegedDisplayCore.readTree(
    automation: UiAutomation,
    displayId: Int,
    pkg: String,
    targetId: Int = 0,
): TreeRead {
    runCatching { invoke(automation, "clearCache") }
    @Suppress("UNCHECKED_CAST")
    val all =
        invoke(automation, "getWindowsOnAllDisplays") as? SparseArray<List<AccessibilityWindowInfo>>
            ?: throw Fault("treeUnavailable")
    val windows =
        all.get(displayId).orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    val roots = ArrayList<AccessibilityNodeInfo?>()
    try {
        for (window in windows) roots.add(window.root)
        val selected =
            BackgroundTree.selectApplicationWindow(
                roots.map { it?.packageName?.toString() },
                pkg,
            )
                ?: throw Fault(
                    if (roots.firstOrNull() != null) "displayTargetChanged" else "treeUnavailable",
                )
        val tree = TreeRead(targetId)
        tree.complete = selected.complete
        try {
            walk(roots[selected.index]!!, tree, 0, 600)
            assignRegions(tree.elements)
            return tree
        } catch (e: Throwable) {
            throw e
        }
    } finally {}
}

internal fun PrivilegedDisplayCore.walk(
    node: AccessibilityNodeInfo?,
    tree: TreeRead,
    indent: Int,
    budget: Int,
) {
    if (node == null) return
    if (tree.visited >= budget) {
        tree.complete = false
        return
    }
    tree.visited++
    if (node.isVisibleToUser && isSecret(node)) tree.hasSecrets = true
    val actionable =
        node.isClickable ||
            node.isLongClickable ||
            node.isEditable ||
            node.isCheckable ||
            node.isScrollable
    val text = bestText(node)
    val emit = actionable || (text.isNotEmpty() && node.isVisibleToUser)
    var childIndent = indent
    if (emit && node.isVisibleToUser) {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        tree.elements.add(toElement(node, text, rect, indent))
        // Retain the exact node assigned this screen id, not whichever editor has focus.
        if (tree.elements.size == tree.targetId) tree.target = node
        if (node.isScrollable) childIndent = indent + 1
    }
    for (i in 0 until node.childCount) {
        if (tree.visited >= budget) {
            tree.complete = false
            break
        }
        val child = node.getChild(i)
        if (child == null) tree.complete = false
        walk(child, tree, childIndent, budget)
    }
}

internal fun PrivilegedDisplayCore.toElement(
    node: AccessibilityNodeInfo,
    text: String,
    rect: Rect,
    indent: Int,
): BgNode {
    val cls = node.className?.toString().orEmpty()
    val password = node.isPassword || secretInput(node)
    val hint = node.hintText?.toString().orEmpty()
    val description = node.contentDescription?.toString().orEmpty()
    val secret = isSecret(node)
    val role = role(node, cls)
    val filled = !node.isShowingHintText && !node.text.isNullOrBlank()
    val label = BackgroundTree.label(text, description.ifBlank { hint }, filled, password, secret)
    val states = ArrayList<String>()
    // l10n:keep privileged screen protocol
    if (node.isFocused) states.add("已聚焦")
    // l10n:keep privileged screen protocol
    if (node.isSelected) states.add("已选")
    // l10n:keep privileged screen protocol
    if (node.isCheckable)
        states.add(if (node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE) "已勾选" else "未勾选")
    // l10n:keep privileged screen protocol
    if (!node.isEnabled) states.add("不可用")
    // l10n:keep privileged screen protocol
    if (node.isScrollable) states.add("可滚动")
    // l10n:keep privileged screen protocol
    if (password) states.add("密码·不可代填")
    var flags = 0
    if (node.isPassword) flags = flags or Proto.FLAG_PASSWORD
    if (secret) flags = flags or Proto.FLAG_SECRET
    if (node.isEditable) flags = flags or Proto.FLAG_EDITABLE
    if (node.isClickable) flags = flags or Proto.FLAG_CLICKABLE
    if (node.isLongClickable) flags = flags or Proto.FLAG_LONGCLICK
    if (node.isScrollable) flags = flags or Proto.FLAG_SCROLLABLE
    if (node.isFocused) flags = flags or Proto.FLAG_FOCUSED
    val viewId = node.viewIdResourceName
    if (SensitiveWords.confirmHit(label, viewId) != null) flags = flags or Proto.FLAG_SENSITIVE
    return BgNode(
        role,
        label,
        states,
        flags,
        indent,
        rect,
        node.packageName?.toString(),
        viewId,
    )
}

internal fun PrivilegedDisplayCore.role(node: AccessibilityNodeInfo, cls: String): String =
    when {
        // l10n:keep privileged screen protocol
        node.isEditable -> "输入框"
        // l10n:keep privileged screen protocol
        cls.endsWith("Switch") || cls.contains("Switch") -> "开关"
        // l10n:keep privileged screen protocol
        node.isCheckable -> "勾选框"
        // l10n:keep privileged screen protocol
        cls.contains("Button") || node.isClickable && node.text != null -> "按钮"
        // l10n:keep privileged screen protocol
        cls.contains("ImageView") && node.isClickable -> "图片"
        // l10n:keep privileged screen protocol
        cls.contains("RecyclerView") || cls.contains("ListView") -> "列表"
        // l10n:keep privileged screen protocol
        node.isClickable -> "按钮"
        // l10n:keep privileged screen protocol
        else -> "文本"
    }

internal fun PrivilegedDisplayCore.isSecret(node: AccessibilityNodeInfo): Boolean =
    node.isPassword ||
        secretInput(node) ||
        SensitiveWords.secretHit(
            node.hintText?.toString(),
            node.contentDescription?.toString(),
            // Resource ids classify inputs: a sendOtp button requests a code, not a secret
            // value.
            // Keep hint/description masking for non-editable secret displays.
            if (node.isEditable) node.viewIdResourceName else null,
        ) != null

internal fun PrivilegedDisplayCore.secretInput(node: AccessibilityNodeInfo): Boolean {
    val klass = node.inputType and InputType.TYPE_MASK_CLASS
    val variation = node.inputType and InputType.TYPE_MASK_VARIATION
    return (klass == InputType.TYPE_CLASS_TEXT &&
        variation in
            setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )) ||
        (klass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
}

internal fun PrivilegedDisplayCore.bestText(node: AccessibilityNodeInfo): String {
    node.text
        ?.toString()
        ?.takeIf { it.isNotBlank() }
        ?.let {
            return it
        }
    node.contentDescription
        ?.toString()
        ?.takeIf { it.isNotBlank() }
        ?.let {
            return it
        }
    if (node.isEditable)
        node.hintText
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }
    return ""
}

internal fun PrivilegedDisplayCore.assignRegions(out: List<BgNode>) {
    if (out.isEmpty()) return
    val top = height / 3
    val bottom = height * 2 / 3
    for (e in out) {
        val cy = (e.bounds.top + e.bounds.bottom) / 2
        e.region =
            when {
                cy < top -> 0
                cy < bottom -> 1
                else -> 2
            }
    }
}

// ---- actions -----------------------------------------------------------
