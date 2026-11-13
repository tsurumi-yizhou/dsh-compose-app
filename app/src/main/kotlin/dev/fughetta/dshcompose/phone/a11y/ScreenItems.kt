package dev.fughetta.dshcompose.phone.a11y

import dev.fughetta.dshcompose.domain.phone.SensitiveWords
import dev.fughetta.dshcompose.phone.a11y.ScreenFormat.Item

internal fun ScreenFormat.flatten(
    node: UiNode,
    window: UiWindow,
    parentClass: String?,
): List<Item> =
    when {
        node.editable -> listOf(input(node, window))
        isList(node) -> listOf(list(node, window))
        actionable(node) -> action(node, window, parentClass)
        else -> {
            val own = clean(node.text) ?: clean(node.desc)
            val children = node.children.flatMap { flatten(it, window, node.className) }
            if (own == null) children
            else
                listOf(text(node, window, own, parentClass)) +
                    // l10n:keep model screen format
                    children.filterNot {
                        it.kind == ElementKind.ITEM &&
                            it.subs.isEmpty() &&
                            it.role == "文本" &&
                            it.label == own
                    }
        }
    }

internal fun ScreenFormat.text(
    node: UiNode,
    window: UiWindow,
    label: String,
    parentClass: String?,
): Item {
    val role =
        when {
            // l10n:keep model screen format
            node.heading -> "标题"
            // l10n:keep model screen format
            role(node, true, parentClass) == "标签" ->
                "标签" // e.g. the selected bottom tab, often not clickable
            // l10n:keep model screen format
            node.text.isNullOrBlank() && isImage(node) -> "图片"
            // l10n:keep model screen format
            else -> "文本"
        }
    // l10n:keep model screen format
    val states = if (node.selected) listOf("已选") else emptyList()
    return Item(
        node,
        window,
        ElementKind.ITEM,
        role,
        label,
        states,
        null,
        emptyList(),
        null,
        false,
    )
}

internal fun ScreenFormat.input(node: UiNode, window: UiWindow): Item {
    val hint = clean(node.hint) ?: if (node.showingHint) clean(node.text) else null
    val secretWord = SensitiveWords.secretHit(hint, node.desc, node.viewId)
    val name = clean(node.desc) ?: hint ?: idTail(node.viewId) ?: ""
    val secret = secretWord != null && !node.password
    val role =
        when {
            // l10n:keep model screen format
            node.password -> "输入框(密码，不可代填)"
            // l10n:keep model screen format
            secret -> "输入框(${secretLabel(secretWord)}，不可代填)"
            // l10n:keep model screen format
            else -> "输入框"
        }
    val content = if (node.password || secret || node.showingHint) null else clean(node.text)
    val states = mutableListOf<String>()
    // l10n:keep model screen format
    if (content == null && !node.password && !secret) states += "空"
    // l10n:keep model screen format
    if (node.focused) states += "已聚焦"
    // l10n:keep model screen format
    if (!node.enabled) states += "不可用"
    return Item(
        node,
        window,
        ElementKind.INPUT,
        role,
        name,
        states,
        content,
        emptyList(),
        null,
        secret,
    )
}

internal fun ScreenFormat.list(node: UiNode, window: UiWindow): Item {
    val subs = node.children.flatMap { flatten(it, window, node.className) }
    val horizontal =
        node.scroll and (Scroll.LEFT or Scroll.RIGHT) != 0 ||
            node.className.contains("Horizontal") ||
            node.className.contains("ViewPager") ||
            (node.children.size >= 2 &&
                node.children[0].bounds.rowOverlap(node.children[1].bounds) > 0.5 &&
                node.children[1].bounds.left >= node.children[0].bounds.right - 2)
    val forward = node.scroll and Scroll.FORWARD != 0
    val backward = node.scroll and Scroll.BACKWARD != 0
    val states =
        if (horizontal) {
            val right = node.scroll and Scroll.RIGHT != 0 || forward
            val left = node.scroll and Scroll.LEFT != 0 || backward
            // l10n:keep model screen format
            listOfNotNull(
                when {
                    left && right -> "可左右滑"
                    right -> "可右滑"
                    left -> "可左滑"
                    else -> null
                },
            )
        } else {
            val down = node.scroll and Scroll.DOWN != 0 || forward
            val up = node.scroll and Scroll.UP != 0 || backward
            // l10n:keep model screen format
            listOfNotNull(
                when {
                    up && down -> "可上下滑"
                    down -> "可下滑"
                    up -> "可上滑"
                    else -> null
                },
            )
        }
    val label = clean(node.desc) ?: ""
    // l10n:keep model screen format
    return Item(
        node,
        window,
        ElementKind.LIST,
        "列表",
        label,
        states,
        null,
        subs,
        null,
        false,
        horizontal,
    )
}

internal fun ScreenFormat.action(node: UiNode, window: UiWindow, parentClass: String?): List<Item> {
    val texts = mutableListOf<String>()
    val subs = mutableListOf<Item>()
    fun gather(n: UiNode) {
        for (c in n.children) when {
            c.editable -> subs += input(c, window)
            isList(c) -> subs += list(c, window)
            actionable(c) -> subs += action(c, window, n.className)
            else -> {
                (clean(c.text) ?: clean(c.desc))?.let { texts += it }
                gather(c)
            }
        }
    }
    gather(node)

    // A row with one non-clickable switch / checkbox inside reads as that switch (Settings
    // rows).
    var checkNode: UiNode? = if (node.checkable) node else null
    var checkRole: String? = null
    val checks = subs.filter {
        it.kind == ElementKind.ITEM && it.node.checkable && !it.node.clickable && it.subs.isEmpty()
    }
    if (checks.size == 1) {
        val check = checks[0]
        subs.remove(check)
        if (checkNode == null) checkNode = check.node
        checkRole = check.role
        if (check.label.isNotEmpty() && check.label !in texts) texts += check.label
    }

    val own = clean(node.text) ?: clean(node.desc)
    val merged = texts.distinct().filter { it != own }
    val readable = own ?: merged.joinToString(" · ").ifEmpty { null } ?: clean(node.hint)
    if (readable == null && subs.isNotEmpty() && checkNode == null)
        return subs // a wrapper: its controls speak for it
    val label = readable ?: idTail(node.viewId)

    var role = role(node, own != null, parentClass)
    // l10n:keep model screen format
    if (checkRole != null && (role == "" || role == "按钮" || role == "勾选框")) role = checkRole
    // l10n:keep model screen format
    if (label == null && role.isEmpty()) role = "按钮"
    val states = mutableListOf<String>()
    val check = checkNode
    when {
        check == null -> {}
        // l10n:keep model screen format
        role == "开关" -> states += if (check.checked) "开" else "关"
        // l10n:keep model screen format
        role == "勾选框" || role == "单选" -> states += if (check.checked) "✓" else "空"
        // l10n:keep model screen format
        check.checked -> states += "已勾选"
    }
    // l10n:keep model screen format
    if (node.selected) states += "已选"
    // l10n:keep model screen format
    if (role == "滑块" && node.rangeCurrent != null) {
        val max = node.rangeMax
        states +=
            if (max != null && max > 0f && max <= 1000f && max != 100f)
                "${fmt(node.rangeCurrent)}/${fmt(max)}"
            else "${fmt(node.rangeCurrent)}"
    }
    clean(node.stateDesc)?.let { sd ->
        // l10n:keep model screen format
        if (
            role != "开关" &&
                role != "勾选框" &&
                role != "单选" &&
                sd.length <= 10 &&
                sd != label &&
                sd !in states
        )
            states += sd
    }
    // l10n:keep model screen format
    if (!node.enabled) states += "不可用"
    val item =
        Item(
            node,
            window,
            ElementKind.ITEM,
            role,
            label ?: "",
            states,
            null,
            subs,
            check?.checked,
            false,
        )
    return listOf(item)
}

internal fun ScreenFormat.role(node: UiNode, ownText: Boolean, parentClass: String?): String {
    val cls = node.className.substringAfterLast('.')
    val hint = node.roleHint
    return when {
        // l10n:keep model screen format
        cls.contains("Switch") || cls == "ToggleButton" || hint == "switch" || hint == "开关" -> "开关"
        // l10n:keep model screen format
        cls == "CheckBox" || cls == "CheckedTextView" || hint == "checkbox" || hint == "复选框" ->
            "勾选框"
        // l10n:keep model screen format
        cls == "RadioButton" || hint == "radio" || hint == "radiobutton" || hint == "单选按钮" -> "单选"
        // l10n:keep model screen format
        cls == "SeekBar" ||
            cls == "RatingBar" ||
            cls.contains("Slider") ||
            hint == "slider" ||
            hint == "滑块" -> "滑块"
        // l10n:keep model screen format
        cls == "Spinner" || hint == "combobox" || hint == "popupbutton" || hint == "下拉列表" -> "下拉框"
        // l10n:keep model screen format
        hint == "link" || hint == "链接" -> "链接"
        // l10n:keep model screen format
        cls.contains("TabView") ||
            cls.endsWith("\$Tab") ||
            cls == "Tab" ||
            hint == "tab" ||
            hint == "标签" ||
            cls.contains("NavigationItemView") ||
            cls.contains("NavigationBarItemView") ||
            // l10n:keep model screen format
            (parentClass != null &&
                (parentClass.contains("TabWidget") ||
                    parentClass.contains("TabLayout") ||
                    parentClass.contains("BottomNavigation"))) -> "标签"
        // l10n:keep model screen format
        node.checkable -> "勾选框"
        // l10n:keep model screen format
        cls == "Button" ||
            cls == "ImageButton" ||
            cls.endsWith("Button") ||
            hint == "button" ||
            hint == "按钮" -> "按钮"
        // l10n:keep model screen format
        isImage(node) -> "按钮"
        // l10n:keep model screen format
        ownText && node.children.isEmpty() -> "按钮"
        else -> ""
    }
}
