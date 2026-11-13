package dev.fughetta.dshcompose.domain.skills

import dev.fughetta.dshcompose.domain.sessions.search.*
import kotlinx.collections.immutable.*

/** Immutable catalog and its search index for one selected session. */
class SkillCatalog(val skills: PersistentList<SkillInfo>) {
    private val index = skills.map {
        it to foldSessionSearch("${it.name} ${it.description} ${it.whenToUse.orEmpty()}")
    }

    fun filter(query: String, checkpoint: () -> Unit = {}): PersistentList<SkillInfo> {
        val terms = sessionSearchTerms(query)
        return index
            .filterIndexed { position, (_, key) ->
                if (position % 128 == 0) checkpoint()
                terms.all { key.contains(it) }
            }
            .map { it.first }
            .toPersistentList()
    }
}

data class SkillSessionCreation(val sessionId: String, val switchedWorkspace: String?)
