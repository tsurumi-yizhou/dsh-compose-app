// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's
// permission. See THIRD_PARTY_NOTICES.md.
// Local changes: the screen text format instead of JSON; fingerprints re-checked against a fresh
// capture; password fields marked rather than failing the screen; collect, open, numbered
// screenshots, tap_xy; blocking calls with time budgets.

package dev.fughetta.dshcompose.phone.a11y

import dev.fughetta.dshcompose.domain.phone.ActionResult
import dev.fughetta.dshcompose.domain.phone.PhoneAction
import dev.fughetta.dshcompose.phone.a11y.MainScreenDriver.Companion.readBackMismatch
import dev.fughetta.dshcompose.phone.a11y.MainScreenDriver.Resolved

internal fun MainScreenDriver.tap(a: PhoneAction.Tap, gen: Long): ActionResult {
    val res = resolve(a.screenId, a.elementId)
    val r = res as? Resolved ?: return res as ActionResult
    try {
        refuseWrite(r)?.let {
            return it
        }
        if (r.el.kind == ElementKind.LIST) {
            // l10n:keep agent screen result
            return ActionResult(
                false,
                "[${a.elementId}] 是列表本身，不能点。看更多用 scroll，整理全部条目用 collect。",
            )
        }
        val before = clock.count
        val out = actuator.click(r.node, r.el.bounds, r.cap.screen, a.long)
        val what = short(r.el.label.ifEmpty { r.el.role })
        // l10n:keep agent screen result
        if (!out.accepted)
            return ActionResult(
                false,
                "系统没有接受这次${if (a.long) "长按" else "点击"}「$what」。",
                observeQuiet(),
            )
        // l10n:keep agent screen result
        return after(before, r.snap, gen, "已提交${if (a.long) "长按" else "点击"}「$what」。")
    } finally {
        r.cap.recycle()
    }
}

internal fun MainScreenDriver.type(a: PhoneAction.Type, gen: Long): ActionResult {
    val res = resolve(a.screenId, a.elementId)
    val r = res as? Resolved ?: return res as ActionResult
    try {
        refuseWrite(r)?.let {
            return it
        }
        if (r.el.kind != ElementKind.INPUT || !r.node.isEditable) {
            // l10n:keep agent screen result
            return ActionResult(
                false,
                "[${a.elementId}]「${short(r.el.label)}」不是输入框：先点它，等输入框出现再输入。",
            )
        }
        // l10n:keep agent screen result
        if (r.node.isPassword && !lifted.secretFields)
            return ActionResult(false, "这是密码框，DSH 不代填。请用 handoff 让用户自己输入。")
        // l10n:keep agent screen result
        if (a.text.length > 2_000) return ActionResult(false, "一次最多输入 2000 字。")
        val before = clock.count
        if (!actuator.setText(r.node, a.text)) {
            // l10n:keep agent screen result
            return ActionResult(false, "这个输入框不接受代填（系统拒绝了输入）。可以先点一下它再试，或请用户来输入。", observeQuiet())
        }
        val got = readBack(r.node, a.text, gen)
        // l10n:keep agent screen result
        val name = short(r.el.label.ifEmpty { "输入框" })
        if (!sameText(got, a.text)) {
            return ActionResult(
                false,
                readBackMismatch(got, info(r.snap.id, r.el)),
                settledSnapshot(before, gen),
            )
        }
        // l10n:keep agent screen result
        var message = "已在「$name」输入，回读一致。"
        if (a.submit) {
            // l10n:keep agent screen result
            if (actuator.imeEnter(r.node)) message += "已提交键盘上的发送 / 搜索操作。"
            // l10n:keep agent screen result
            else
                return ActionResult(
                    false,
                    "$message 但这个输入框不支持代按回车，没有提交：请点页面上的「发送」或「搜索」按钮。",
                    settledSnapshot(before, gen),
                )
        }
        return after(before, r.snap, gen, message)
    } finally {
        r.cap.recycle()
    }
}

internal fun MainScreenDriver.scroll(a: PhoneAction.Scroll, gen: Long): ActionResult {
    val snap = latest
    val dirWord =
        when (a.direction) {
            // l10n:keep agent screen result
            PhoneAction.Direction.DOWN -> "向下"
            PhoneAction.Direction.UP -> "向上"
            // l10n:keep agent screen result
            PhoneAction.Direction.LEFT -> "向左"
            PhoneAction.Direction.RIGHT -> "向右"
        }
    // The element to scroll: the given one (or its list), else the biggest list on screen.
    var targetId: Int? = a.elementId
    if (targetId != null && snap != null && snap.id == a.screenId) {
        val el = snap.screen.element(targetId)
        if (el != null && el.kind != ElementKind.LIST) targetId = el.listId ?: targetId
    } else if (targetId == null && snap != null && snap.id == a.screenId) {
        targetId =
            snap.screen.elements
                .filter { it.kind == ElementKind.LIST }
                .maxByOrNull { it.bounds.area }
                ?.id
    }
    if (targetId == null) {
        // l10n:keep agent screen result
        if (snap == null || snap.id != a.screenId)
            return ActionResult(false, "屏幕编号 ${a.screenId} 已过期，请先看新屏幕再滚动。", observeQuiet())
        val cap = capture()
        try {
            // l10n:keep agent screen result
            if (cap.ownAppInFront && !lifted.ownApp) return ActionResult(false, "这是 DSH 自己的界面，不操作。")
            // l10n:keep agent screen result
            if (unavailable(cap))
                return ActionResult(
                    false,
                    "这一屏读不到内容（可能受敏感视图策略限制），不盲滑。请 handoff。",
                    toSnapshot(publish(cap, false)),
                )
            // l10n:keep agent screen result
            if (!observation(cap).reusableCoordinates(snap.observation))
                return ActionResult(
                    false,
                    "无法确认当前界面仍与上一屏一致，没有滚动。请先看新屏幕。",
                    toSnapshot(publish(cap, false)),
                )
            val screen = cap.screen
            val before = clock.count
            val out =
                actuator.scroll(
                    null,
                    0,
                    a.direction == PhoneAction.Direction.LEFT ||
                        a.direction == PhoneAction.Direction.RIGHT,
                    Box(
                        screen.left,
                        screen.top + screen.height / 5,
                        screen.right,
                        screen.bottom - screen.height / 6,
                    ),
                    screen,
                    a.direction,
                )
            // l10n:keep agent screen result
            if (!out.accepted) return ActionResult(false, "没能滚动。", observeQuiet())
            // l10n:keep agent screen result
            return after(before, snap, gen, "已提交${dirWord}滑动屏幕。", scrolled = true)
        } finally {
            cap.recycle()
        }
    }
    val res = resolve(a.screenId, targetId)
    val r = res as? Resolved ?: return res as ActionResult
    try {
        // l10n:keep agent screen result
        if ((r.el.packageName == ownPackage || r.snap.ownApp) && !lifted.ownApp)
            return ActionResult(false, "这是 DSH 自己的界面，不操作。")
        val before = clock.count
        val out =
            actuator.scroll(
                r.node,
                r.el.scroll,
                r.el.horizontal,
                r.el.bounds,
                r.cap.screen,
                a.direction,
            )
        // l10n:keep agent screen result
        if (!out.accepted)
            return ActionResult(false, "系统没有接受${dirWord}滚动，无法确认是否到底。", observeQuiet())
        // l10n:keep agent screen result
        return after(
            before,
            r.snap,
            gen,
            "已提交${dirWord}滚动${if (r.el.kind == ElementKind.LIST) "列表 [${r.el.id}]" else ""}。",
            scrolled = true,
        )
    } finally {
        r.cap.recycle()
    }
}

internal fun MainScreenDriver.key(a: PhoneAction.Key, gen: Long): ActionResult {
    val before = clock.count
    // l10n:keep agent screen result
    if (!actuator.global(a.key)) return ActionResult(false, "系统没有执行这个按键。")
    val what =
        when (a.key) {
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.BACK -> "已提交返回键操作。"
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.HOME -> "已提交回到桌面的按键操作。"
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.RECENTS -> "已提交打开最近任务的按键操作。"
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.NOTIFICATIONS -> "已提交打开通知栏的按键操作。"
        }
    return after(before, latest, gen, what)
}

internal fun MainScreenDriver.tapXY(a: PhoneAction.TapXY, gen: Long): ActionResult {
    val snap = latest
    if (snap == null || snap.id != a.screenId) {
        val now = observeQuiet()
        // l10n:keep agent screen result
        return ActionResult(false, "屏幕编号 ${a.screenId} 已过期，坐标对不上了。请重新 screen 加 image 再点。", now)
    }
    // l10n:keep agent screen result
    val geometry =
        snap.image
            ?: return ActionResult(
                false,
                "屏幕 ${a.screenId} 没有截图。坐标点击要先用 screen 加 image 拿一张标号截图。",
            )
    val point =
        geometry.toScreen(a.x, a.y)
            // l10n:keep agent screen result
            ?: return ActionResult(
                false,
                "坐标 (${a.x}, ${a.y}) 不在这张截图里（${geometry.width}×${geometry.height}）。",
            )
    // l10n:keep agent screen result
    if (snap.ownApp && !lifted.ownApp) return ActionResult(false, "这是 DSH 自己的界面，不操作。")
    // l10n:keep agent screen result
    if (snap.protectedPage && !lifted.systemPages)
        return ActionResult(false, "这是系统权限 / 安全相关页面，DSH 不在这里点击。需要的话请用 handoff 交给用户。")
    // l10n:keep agent screen result
    if (snap.hidden || snap.unreadable)
        return ActionResult(false, "这一屏读不到内容（可能受敏感视图策略限制），不盲点。请 handoff。", observeQuiet())
    val cap = capture()
    try {
        // l10n:keep agent screen result
        if (unavailable(cap))
            return ActionResult(
                false,
                "这一屏读不到内容（可能受敏感视图策略限制），不盲点。请 handoff。",
                toSnapshot(publish(cap, false)),
            )
        // l10n:keep agent screen result
        if (!observation(cap).reusableCoordinates(snap.observation))
            return ActionResult(
                false,
                "无法确认当前界面仍与截图一致，没有点击。请重新看屏幕。",
                toSnapshot(publish(cap, false)),
            )
    } finally {
        cap.recycle()
    }
    val before = clock.count
    // l10n:keep agent screen result
    if (!actuator.tap(point.first, point.second))
        return ActionResult(false, "系统没有接受这次点击。", observeQuiet())
    // l10n:keep agent screen result
    return after(before, snap, gen, "已提交点击截图上的 (${a.x}, ${a.y})。")
}

/**
 * Waits for the UI to settle, reads the new screen (`+` marks) and says whether anything changed.
 */
