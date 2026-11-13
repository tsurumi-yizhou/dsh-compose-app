package dev.fughetta.dshcompose.domain.phone

/**
 * The person-editable part of the rules: apps the agent may neither read nor operate, apps where
 * every write is confirmed, how much is asked, and the hard rules the person lifted ([unlocks]).
 * Defaults ship with the APK.
 */
data class PhonePolicies(
    val denyPackages: List<String> = SafetyRules.DEFAULT_DENY,
    val confirmEveryWritePackages: List<String> = SafetyRules.DEFAULT_CONFIRM_EVERY_WRITE,
    val confirmMode: ConfirmMode = ConfirmMode.ASK,
    val unlocks: PhoneUnlocks = PhoneUnlocks(),
) {
    fun toMap(): Map<String, Any?> =
        linkedMapOf(
            "denyPackages" to denyPackages,
            "confirmEveryWritePackages" to confirmEveryWritePackages,
            "confirmMode" to confirmMode.wire,
            "unlocks" to unlocks.toMap(),
        )

    companion object {
        /**
         * Reads `setPolicies` arguments / the stored JSON; a missing entry keeps [base]'s value.
         */
        fun fromMap(map: Map<*, *>?, base: PhonePolicies = PhonePolicies()): PhonePolicies {
            fun list(key: String, keep: List<String>): List<String> {
                val raw = map?.get(key) as? List<*> ?: return keep
                return raw.mapNotNull {
                        (it as? String)?.trim()?.takeIf { s -> PACKAGE.matches(s) }
                    }
                    .distinct()
            }
            return PhonePolicies(
                list("denyPackages", base.denyPackages),
                list("confirmEveryWritePackages", base.confirmEveryWritePackages),
                ConfirmMode.fromWire(map?.get("confirmMode") as? String) ?: base.confirmMode,
                PhoneUnlocks.fromMap(map?.get("unlocks") as? Map<*, *>, base.unlocks),
            )
        }

        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    }
}

/**
 * 解除限制: hard rules the person lifted one by one, at their own risk, behind a confirmation page in
 * the app. Each is off by default, and anything missing or unreadable reads as off: a value that
 * isn't a boolean keeps [base]'s, and stored policies are read on top of the defaults. Changed only
 * by a `setPolicies` that names it (the app sends nothing else with it).
 */
data class PhoneUnlocks(
    /** System settings, permission controllers, installers, OEM security centres, SystemUI. */
    val systemPages: Boolean = false,
    /** Password, verification-code and payment-code inputs. Typed values never reach a log. */
    val secretFields: Boolean = false,
    /** DSH Compose's own screens (not its overlay, which the screen reader never sees). */
    val ownApp: Boolean = false,
) {
    val any: Boolean
        get() = systemPages || secretFields || ownApp

    fun toMap(): Map<String, Any?> =
        linkedMapOf(
            "systemPages" to systemPages,
            "secretFields" to secretFields,
            "ownApp" to ownApp,
        )

    companion object {
        fun fromMap(map: Map<*, *>?, base: PhoneUnlocks = PhoneUnlocks()): PhoneUnlocks {
            fun flag(key: String, keep: Boolean) = map?.get(key) as? Boolean ?: keep
            return PhoneUnlocks(
                flag("systemPages", base.systemPages),
                flag("secretFields", base.secretFields),
                flag("ownApp", base.ownApp),
            )
        }
    }
}

/**
 * How much the person is asked. Whatever the mode, the hard refusals still apply (own app, deny
 * list, system pages, password / code fields) unless the person lifted them ([PhoneUnlocks]): those
 * are where one wrong tap costs money or an account, and what prompt injection aims at.
 */
enum class ConfirmMode(val wire: String) {
    /** 每次都询问 (default): first step in each app, sensitive words, every-write apps, tap_xy. */
    ASK("ask"),

    /** 本任务免确认: the first question of a task offers to waive the rest of that task. */
    TASK("task"),

    /** 始终免确认: nothing is asked. */
    ALWAYS("always");

    companion object {
        fun fromWire(value: String?): ConfirmMode? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Fixed safety rules that are not the overlay's own UI. Native and not configurable: the defence
 * against prompt injection is here, not in AGENTS.md (a web page saying "transfer money" can fool
 * the model, not this code). Public so the screen reader can mark `⚠` with the same word list.
 */
object SafetyRules {
    const val OWN_PACKAGE = "dev.fughetta.dshcompose"

    /**
     * The word that makes tapping [label] sensitive, or null. Delegates to [SensitiveWords] (shared
     * with the screen reader's `⚠` marks) so what is marked is exactly what gets confirmed.
     */
    fun sensitiveWord(label: String?, viewId: String? = null): String? =
        SensitiveWords.confirmHit(label, viewId)

    /**
     * An input the agent must never fill unless the person allowed it
     * ([PhoneUnlocks.secretFields]).
     */
    fun isSecret(label: String?, viewId: String? = null): Boolean =
        SensitiveWords.secretHit(label, null, viewId) != null

    /**
     * A password / code input: refused while locked, its typed value masked everywhere once
     * allowed.
     */
    fun isSecretTarget(t: TargetInfo?): Boolean =
        t != null &&
            (t.isPassword || t.isSecretField || (t.isEditable && isSecret(t.label, t.viewId)))

    /** What a typed secret shows as in the log, the status line and a confirmation. */
    const val MASK = "••••"

    /**
     * System pages where the agent may read but never act: settings (accessibility, device admin,
     * developer options live there), permission controllers, package installers, OEM security
     * centres, and SystemUI (quick settings, the notification shade with our own confirm buttons,
     * the lock screen). The contract gives us packages, not pages, so the whole app counts.
     */
    val SYSTEM_PACKAGES =
        setOf(
            "com.android.settings",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.android.settings.intelligence",
            "com.miui.securitycenter",
            "com.miui.securityadd",
            "com.miui.securitycore",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.coloros.securitypermission",
            "com.oplus.securitypermission",
            "com.vivo.permissionmanager",
            "com.iqoo.secure",
            "com.huawei.systemmanager",
            "com.hihonor.systemmanager",
            "com.huawei.security",
            "com.hihonor.security",
            "com.samsung.android.lool",
            "com.samsung.android.sm",
            "com.samsung.accessibility",
            "com.google.android.marvin.talkback",
            "com.android.keychain",
            "com.android.vpndialogs",
            "com.android.certinstaller",
        )

    fun isSystemPackage(pkg: String?): Boolean = pkg != null && pkg in SYSTEM_PACKAGES

    /**
     * Banks, brokers, payment: neither read nor write by default. Wrong guesses just never match.
     */
    val DEFAULT_DENY =
        listOf(
            // payment
            "com.eg.android.AlipayGphone",
            "com.unionpay",
            "com.chinatelecom.bestpayclient",
            "com.jd.jrapp",
            "cn.gov.pbc.dcep",
            "com.paypal.android.p2pmobile",
            "com.mybank.android.phone",
            "com.webank.wemoney",
            // banks
            "com.icbc",
            "com.chinamworld.main",
            "com.android.bankabc",
            "com.chinamworld.bocmbci",
            "com.bankcomm.Bankcomm",
            "cmb.pb",
            "com.cmbchina.ccd.pluto.cmbActivity",
            "com.yitong.mbank.psbc",
            "cn.com.spdb.mobilebank.per",
            "cn.com.cmbc.newmbank",
            "com.ecitic.bank.mobile",
            "com.cebbank.mobile.cemb",
            "com.pingan.paces.ccms",
            "com.cgbchina.xpt",
            "com.bankofbeijing.mobilebanking",
            // brokers
            "com.hexin.plat.android",
            "com.eastmoney.android.berlin",
            "com.lphtsccft",
            "com.xueqiu.android",
            "cn.futu.trader",
            "com.tigerbrokers.stock",
        )

    /** WeChat: readable, every write confirmed. */
    val DEFAULT_CONFIRM_EVERY_WRITE = listOf("com.tencent.mm")

    fun quoteText(text: String, max: Int = 60): String {
        val t = text.replace('\n', ' ').trim()
        return if (t.length <= max) t else t.take(max) + "…"
    }
}

/** The kinds of writes the gate distinguishes. */
enum class WriteKind {
    TAP,
    LONG_TAP,
    TYPE,
    TAP_XY,
    SCROLL,
    KEY,
}
