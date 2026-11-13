package dev.fughetta.dshcompose.data.workspace

import dev.fughetta.dshcompose.domain.references.activeReferenceQuery
import dev.fughetta.dshcompose.domain.references.formatFileMention
import dev.fughetta.dshcompose.protocol.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReferencesRepositoryTest {
    @Test
    fun sessionReferencesKeepTheHostsMentionAndFileMentionsFollowItsGrammar() = runBlocking {
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            assertEquals("sessionReferenceResolver/candidates", input.string("method"))
            assertEquals(
                parseObject("""{"agentId":"current","query":"登录"}"""),
                input["args"],
            )
            respond(
                """{"id":${input["id"]},"result":[{"sessionId":"other","label":"Latest title","displayTitle":"登录任务","mention":"@[登录任务](dsh-session:Im90aGVyIg)","sameWorkspace":true,"cwd":"/project"},{"sessionId":"bare","label":"no mention"},"junk"]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            val candidate =
                EngineWorkspaceFilesRepository(client).sessionReferences("current", "登录").single()
            assertEquals("登录任务", candidate.title)
            assertEquals("@[登录任务](dsh-session:Im90aGVyIg)", candidate.mention)
            assertTrue(candidate.sameWorkspace)
            assertEquals("/project", candidate.cwd)
        }
        assertEquals("@src/file.kt", formatFileMention("src/file.kt", false))
        assertEquals("@\"src/new file.kt\"", formatFileMention("src/new file.kt", false))
        assertEquals("@\"new folder/", formatFileMention("new folder", true))
        assertNull(formatFileMention("bad\nname", false))
        assertNull(formatFileMention("bad\"name", true))
    }

    @Test
    fun activeReferenceQueryTracksTheCaretWithoutTreatingEmailsOrCompletedMentionsAsQueries() {
        val text = "earlier @old\nread @\"src/new file tail"
        val caret = text.indexOf(" tail")
        val query = activeReferenceQuery(text, caret)!!
        assertEquals("@\"src/new file", text.substring(query.start, query.end))
        assertEquals("src/new file", query.query)
        assertTrue(query.pathOnly)
        assertTrue(query.quoted)
        assertEquals("@\"file.kt\"", formatFileMention("file.kt", false, query.quoted))
        assertNull(activeReferenceQuery("mail@example.com", 16))
        assertNull(activeReferenceQuery("@\"file.kt\"", 10))
        val canonical = "@[task](dsh-session:InMxIg)"
        assertNull(activeReferenceQuery(canonical, canonical.length))
        assertEquals("", activeReferenceQuery("read @", 6)!!.query)
        assertFalse(activeReferenceQuery("@task", 5)!!.pathOnly)
        assertNull(activeReferenceQuery("@old\nplain", 10))
    }
}
