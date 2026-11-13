package dev.fughetta.dshcompose.engine.runtime

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Enabled bundles, installed packages and settings layer of the main profile at one moment. */
data class ProfileSnapshot(
    /** `dsh.profile.bundles`, in order. */
    val bundles: List<String>,
    /** `dependencies` (installed third-party packages). */
    val deps: Set<String>,
    /** Installed versions of [deps] that could be read from node_modules. */
    val versions: Map<String, String>,
    /** `cordis.patch.yml` text, or null when the file is absent. */
    val patch: String?,
)

/**
 * What restoring the last good snapshot changes in the current profile: the bundle list goes back
 * to the snapshot's, minus packages uninstalled or upgraded since (an upgraded package can only be
 * turned off, not downgraded); the settings layer goes back when it differs. Nothing is installed
 * or removed.
 */
data class LkgPlan(
    val target: List<String>,
    val disable: List<String>,
    val enable: List<String>,
    /** Enabled packages whose installed version differs from the snapshot's (part of [disable]). */
    val changed: List<String>,
    val patchChanged: Boolean,
    val bundlesChanged: Boolean,
) {
    val isEmpty: Boolean
        get() = !bundlesChanged && !patchChanged

    fun toMap(): Map<String, Any?> =
        mapOf(
            "disable" to disable,
            "enable" to enable,
            "changed" to changed,
            "patchChanged" to patchChanged,
            "empty" to isEmpty,
        )

    companion object {
        fun of(lkg: ProfileSnapshot, current: ProfileSnapshot): LkgPlan {
            fun sameVersion(name: String): Boolean {
                val then = lkg.versions[name]
                val now = current.versions[name]
                return then == null || now == null || then == now
            }
            val target =
                RuntimeProfile.migrateBundles(lkg.bundles)
                    .filter { name ->
                        when {
                            name !in lkg.deps -> true // ships with DSH
                            name !in current.deps -> false // uninstalled since
                            else -> sameVersion(name)
                        }
                    }
                    .distinct()
            return LkgPlan(
                target = target,
                disable = current.bundles.filter { it !in target }.distinct(),
                enable = target.filter { it !in current.bundles },
                changed =
                    lkg.bundles
                        .filter {
                            it in lkg.deps &&
                                it in current.deps &&
                                it in current.bundles &&
                                !sameVersion(it)
                        }
                        .distinct(),
                patchChanged = !samePatch(lkg.patch, current.patch),
                bundlesChanged = target != current.bundles,
            )
        }

        fun samePatch(a: String?, b: String?): Boolean {
            val trivialA = a == null || RuntimeFiles.isTrivialPatch(a)
            val trivialB = b == null || RuntimeFiles.isTrivialPatch(b)
            if (trivialA && trivialB) return true
            return a?.trimEnd() == b?.trimEnd()
        }
    }
}

/**
 * The last good plugin set of profile `web`, kept outside the rootfs in
 * `filesDir/runtime/lkg/lkg.json` and recorded after each healthy two-minute normal run.
 */
object LastKnownGood {
    class Record(val at: Long, val snapshot: ProfileSnapshot)

    fun webDir(rootfs: File): File = File(rootfs, "root/.dsh/profiles/compose")

    /** The main profile as it is now; null before DSH has created it. */
    fun read(rootfs: File): ProfileSnapshot? {
        val dir = webDir(rootfs)
        val pkg = File(dir, "package.json")
        if (!pkg.isFile) return null
        val root = Json.parseToJsonElement(pkg.readText()) as? JsonObject ?: return null
        return parse(root, dir)
    }

    /**
     * What a normal run booted with, fixed when it reports ready: [atLaunch], or on a first boot
     * (DSH creates the profile after the launch read found none) the profile [readNow] returns.
     * Null when it is still missing or can't be read.
     */
    fun bootedAtReady(
        atLaunch: ProfileSnapshot?,
        readNow: () -> ProfileSnapshot?,
    ): ProfileSnapshot? =
        atLaunch
            ?: try {
                readNow()
            } catch (_: Exception) {
                null
            }

    /**
     * A healthy run pins exactly what it booted with, never a later read of the disk: the app may
     * have imported a profile since that nobody has seen run. False, keeping the old pin, without
     * one.
     */
    fun pinBooted(booted: ProfileSnapshot?, save: (ProfileSnapshot) -> Unit): Boolean {
        save(booted ?: return false)
        return true
    }

    private fun parse(json: JsonObject, dir: File): ProfileSnapshot {
        val bundles = json.obj("dsh")?.obj("profile")?.strings("bundles").orEmpty()
        val deps = LinkedHashSet<String>(json.obj("dependencies")?.keys.orEmpty())
        val versions = LinkedHashMap<String, String>()
        for (name in deps) {
            val version =
                try {
                    val pkg =
                        Json.parseToJsonElement(
                            File(dir, "node_modules/$name/package.json").readText(),
                        ) as? JsonObject
                    (pkg?.get("version") as? JsonPrimitive)?.contentOrNull
                } catch (_: Exception) {
                    null
                }
            if (!version.isNullOrEmpty()) versions[name] = version
        }
        val patchFile = File(dir, "cordis.patch.yml")
        val patch = if (patchFile.isFile) patchFile.readText() else null
        return ProfileSnapshot(bundles, deps, versions, patch)
    }

    fun load(file: File): Record? {
        if (!file.isFile) return null
        return try {
            val fileJson = runtimeJson.decodeFromString(LkgFile.serializer(), file.readText())
            Record(
                at = fileJson.at,
                snapshot =
                    ProfileSnapshot(
                        bundles = fileJson.bundles,
                        deps = LinkedHashSet(fileJson.deps),
                        versions = LinkedHashMap(fileJson.versions),
                        patch = fileJson.patch,
                    ),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun save(file: File, snapshot: ProfileSnapshot, at: Long) {
        val stored =
            LkgFile(
                at = at,
                bundles = snapshot.bundles,
                deps = snapshot.deps.toList(),
                versions = snapshot.versions,
                patch = snapshot.patch,
            )
        file.parentFile?.mkdirs()
        RuntimeFiles.writeAtomic(
            file,
            runtimeJson.encodeToString(LkgFile.serializer(), stored) + "\n",
        )
    }

    /**
     * Applies [LkgPlan.of] to the main profile under DSH's `package.json.lock`. The settings layer
     * being replaced is kept as `cordis.patch.yml.bak-<stamp>`.
     */
    fun restore(
        rootfs: File,
        lkg: ProfileSnapshot,
        stamp: String,
        missingConfig: String = "找不到主配置 profiles/compose/package.json", // l10n:keep test default
        busyConfig: String = "插件配置正被别的程序修改，稍后再试", // l10n:keep test default
    ): LkgPlan {
        val dir = webDir(rootfs)
        val pkg = File(dir, "package.json")
        if (!pkg.isFile) throw IllegalStateException(missingConfig)
        return withLock(pkg, busyConfig) {
            val root =
                Json.parseToJsonElement(pkg.readText()) as? JsonObject
                    ?: throw IllegalStateException(missingConfig)
            val plan = LkgPlan.of(lkg, parse(root, dir))
            if (plan.bundlesChanged) {
                val dsh = root.obj("dsh") ?: JsonObject(emptyMap())
                val profile = dsh.obj("profile") ?: JsonObject(emptyMap())
                val bundles = JsonArray(plan.target.map { JsonPrimitive(it) })
                val updated =
                    root.with("dsh", dsh.with("profile", profile.with("bundles", bundles)))
                RuntimeFiles.writeAtomic(
                    pkg,
                    runtimeJson.encodeToString(JsonElement.serializer(), updated) + "\n",
                )
            }
            if (plan.patchChanged) {
                val patchFile = File(dir, "cordis.patch.yml")
                if (patchFile.isFile)
                    patchFile.copyTo(File(dir, "cordis.patch.yml.bak-$stamp"), overwrite = true)
                val text = lkg.patch
                if (text != null) RuntimeFiles.writeAtomic(patchFile, text) else patchFile.delete()
            }
            plan
        }
    }

    /**
     * DSH's writer lock (`dsh-atomic-write`): a sibling `<file>.lock` created exclusively, holding
     * the writer's pid; a lock whose pid no longer exists is taken over.
     */
    private fun <T> withLock(file: File, busyConfig: String, block: () -> T): T {
        val lock = File(file.path + ".lock")
        val deadline = System.currentTimeMillis() + 3000
        while (true) {
            if (lock.createNewFile()) {
                break
            }
            val holder =
                try {
                    lock.readText().trim().toIntOrNull()
                } catch (_: Exception) {
                    null
                }
            if (holder != null && !File("/proc/$holder").exists()) {
                lock.delete()
                continue
            }
            if (System.currentTimeMillis() > deadline) throw IllegalStateException(busyConfig)
            Thread.sleep(50)
        }
        try {
            lock.writeText("${android.os.Process.myPid()}\n")
            return block()
        } finally {
            lock.delete()
        }
    }

    @Serializable
    private data class LkgFile(
        val at: Long = 0,
        val bundles: List<String> = emptyList(),
        val deps: List<String> = emptyList(),
        val versions: Map<String, String> = emptyMap(),
        val patch: String? = null,
    )
}
