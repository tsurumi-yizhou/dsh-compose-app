package dev.fughetta.dshcompose.domain.features

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A module owns its contributions and all work started during its activation. */
class HostModule<T>(
    val id: String,
    val dependencies: Set<String> = emptySet(),
    val contributions: List<T>,
    val activate: (ModuleLifetime) -> Unit = {},
)

enum class ModuleStatus {
    Active,
    Disabled,
    MissingDependency,
    DependencyCycle,
    DependencyInactive,
    ActivationFailed,
    CleanupFailed,
}

data class ModuleSnapshot<T>(
    val states: Map<String, ModuleStatus> = emptyMap(),
    val contributions: List<T> = emptyList(),
)

/** Resources are released in reverse registration order, after cancelling owned jobs. */
class ModuleLifetime internal constructor(parent: CoroutineScope) : AutoCloseable {
    val scope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val disposers = mutableListOf<() -> Unit>()
    private var closed = false

    fun onDispose(dispose: () -> Unit) {
        check(!closed) { "module/closed" }
        disposers.add(dispose)
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        var failure: Throwable? = null
        disposers.asReversed().forEach {
            try {
                it()
            } catch (error: Exception) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        disposers.clear()
        failure?.let { throw it }
    }
}

/**
 * Called on the host's UI dispatcher. Definitions are replaced as a unit, so a removed or changed
 * dependency retires its dependents before it is replaced. Configuration stays with the
 * definition's owner; enabled choices stay with the host. The registry owns only active resources
 * and entries.
 */
class ModuleRegistry<T>(private val parent: CoroutineScope) : AutoCloseable {
    private data class Running<T>(val definition: HostModule<T>, val lifetime: ModuleLifetime)

    private var definitions = linkedMapOf<String, HostModule<T>>()
    private val publishers = linkedMapOf<String, List<HostModule<T>>>()
    private val active = linkedMapOf<String, Running<T>>()
    private var disabled = emptySet<String>()
    private var closed = false
    private val mutable = MutableStateFlow(ModuleSnapshot<T>())
    val state = mutable.asStateFlow()

    fun replace(
        modules: List<HostModule<T>>,
        disabled: Set<String> =
            this.disabled.intersect(publishers["default"].orEmpty().map { it.id }.toSet()),
    ) = publish("default", modules, disabled)

    /** A publisher can replace its own modules but cannot take over another publisher's ids. */
    fun publish(owner: String, modules: List<HostModule<T>>, disabled: Set<String> = emptySet()) {
        check(!closed) { "module/closed" }
        require(owner.isNotBlank()) { "module/invalid-owner" }
        val next = LinkedHashMap(publishers).apply { put(owner, modules.toList()) }
        val definitions = next.values.flatten()
        require(definitions.map { it.id }.distinct().size == definitions.size) {
            "module/duplicate-id"
        }
        require(definitions.all { it.id.isNotBlank() }) { "module/invalid-id" }
        val owned = publishers[owner].orEmpty().map { it.id }.toSet() + modules.map { it.id }
        require(disabled.all { it in owned }) { "module/not-owned" }
        this.disabled = (this.disabled - owned) + disabled
        publishers.clear()
        publishers.putAll(next)
        this.definitions = definitions.associateByTo(linkedMapOf()) { it.id }
        reconcile()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        check(!closed) { "module/closed" }
        require(id in definitions) { "module/not-found" }
        disabled = if (enabled) disabled - id else disabled + id
        reconcile()
    }

    fun scope(id: String): CoroutineScope? = active[id]?.lifetime?.scope

    private fun reconcile() {
        val states = linkedMapOf<String, ModuleStatus>()
        val visiting = linkedSetOf<String>()
        val order = mutableListOf<String>()
        fun resolve(id: String): ModuleStatus {
            states[id]?.let {
                return it
            }
            if (!visiting.add(id)) return ModuleStatus.DependencyCycle
            val module = definitions.getValue(id)
            val status =
                when {
                    id in disabled -> ModuleStatus.Disabled
                    module.dependencies.any { it !in definitions } -> ModuleStatus.MissingDependency
                    else -> {
                        val dependencies = module.dependencies.map(::resolve)
                        when {
                            ModuleStatus.DependencyCycle in dependencies ->
                                ModuleStatus.DependencyCycle
                            dependencies.any { it != ModuleStatus.Active } ->
                                ModuleStatus.DependencyInactive
                            else -> ModuleStatus.Active
                        }
                    }
                }
            visiting.remove(id)
            states[id] = status
            if (status == ModuleStatus.Active) order.add(id)
            return status
        }
        definitions.keys.forEach(::resolve)
        val retained = mutableSetOf<String>()
        order.forEach { id ->
            val definition = definitions.getValue(id)
            if (
                active[id]?.definition === definition &&
                    definition.dependencies.all { it in retained }
            )
                retained.add(id)
        }
        active.keys
            .toList()
            .asReversed()
            .filter { it !in retained }
            .forEach { id ->
                val retiring = active.remove(id)!!
                try {
                    retiring.lifetime.close()
                } catch (_: Exception) {
                    if (id in definitions) states[id] = ModuleStatus.CleanupFailed
                }
            }
        order.forEach { id ->
            if (id in retained || states[id] == ModuleStatus.CleanupFailed) return@forEach
            val definition = definitions.getValue(id)
            if (definition.dependencies.any { it !in active }) {
                states[id] = ModuleStatus.DependencyInactive
                return@forEach
            }
            val lifetime = ModuleLifetime(parent)
            try {
                definition.activate(lifetime)
                active[id] = Running(definition, lifetime)
            } catch (_: Exception) {
                states[id] = ModuleStatus.ActivationFailed
                try {
                    lifetime.close()
                } catch (_: Exception) {
                    states[id] = ModuleStatus.CleanupFailed
                }
            }
        }
        mutable.value =
            ModuleSnapshot(
                states.toMap(),
                order.flatMap { active[it]?.definition?.contributions.orEmpty() },
            )
    }

    override fun close() {
        if (closed) return
        closed = true
        publishers.clear()
        definitions.clear()
        reconcile()
    }
}
