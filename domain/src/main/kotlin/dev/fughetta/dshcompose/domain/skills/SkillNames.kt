package dev.fughetta.dshcompose.domain.skills

fun validSkillName(name: String) =
    name.isNotEmpty() && name.none { it.isWhitespace() || it.isISOControl() }
