package dev.fughetta.dshcompose.data.uiconfig

import org.junit.Assert.assertEquals
import org.junit.Test

class UiConfigDataSourceTest {
    @Test
    fun dshHomeComesFromTheSkillPath() {
        assertEquals(
            "/home/a/.dsh",
            dshHomeFromSkillPath("/home/a/.dsh/skills/dsh-compose-ui/SKILL.md"),
        )
        assertEquals("/srv/dsh", dshHomeFromSkillPath("/srv/dsh/skills/dsh-compose-ui/"))
        assertEquals(null, dshHomeFromSkillPath("/home/a/.dsh/skills/other/SKILL.md"))
        assertEquals(null, dshHomeFromSkillPath("/skills/dsh-compose-ui/SKILL.md"))
        assertEquals(null, dshHomeFromSkillPath("SKILL.md"))
    }
}
