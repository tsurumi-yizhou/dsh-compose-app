package dev.fughetta.dshcompose.domain.skills

import dev.fughetta.dshcompose.domain.sessions.SessionsRepository

/** What the skills dialog needs from one host: the catalog and conversation creation. */
data class SkillsPorts(val skills: SkillsRepository, val sessions: SessionsRepository)
