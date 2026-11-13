package dev.fughetta.dshcompose.domain.links

import dev.fughetta.dshcompose.domain.images.ReplyImageSource
import dev.fughetta.dshcompose.domain.images.replyImageSource
import org.junit.Assert.*
import org.junit.Test

class ReplyLinksTest {
    @Test
    fun fileLinksAndImagesShareStrictSingleDecodingAndHostPaths() {
        assertEquals("folder+a/%2F图.md", workspaceLinkPath("<folder+a/%252F%E5%9B%BE.md#part>"))
        assertEquals("/work/report.md", workspaceLinkPath("../report.md?download", "/work/src"))
        assertEquals("relative/../report.md", workspaceLinkPath("../report.md", "relative"))
        assertEquals("/tmp/a b.md", workspaceLinkPath("file://localhost/tmp/a%20b.md"))
        assertEquals("C:\\docs\\a b.md", workspaceLinkPath("C:\\docs\\a%20b.md#part"))
        assertEquals("C:\\", workspaceParentPath("C:\\a.md"))
        assertEquals("/", workspaceParentPath("/a.md"))
        assertEquals("dir", workspaceParentPath("dir/a.md"))
        for (source in
            listOf(
                "%FF.md",
                "%00.md",
                "file://other/tmp/a.md",
                "file://user@localhost/a.md",
                "file://localhost:80/a.md",
                "//host/a.md",
                "javascript:alert(1)",
                "%68ttps:evil",
                "#part",
                "?query",
            )) {
            assertNull(source, workspaceLinkPath(source))
            assertEquals(source, ReplyImageSource.Unsupported, replyImageSource(source))
        }
        assertEquals(
            "图+a.png",
            (replyImageSource("%E5%9B%BE+a.png") as ReplyImageSource.Workspace).path,
        )
    }

    @Test
    fun websitesRemainCandidatesWhileExplicitExternalSchemesAreAllowlisted() {
        assertEquals(
            ReplyLinkTarget.Workspace("example.com/report", "https://example.com/report?q=1#top"),
            replyLinkTarget("example.com/report?q=1#top"),
        )
        assertEquals(ReplyLinkTarget.Workspace("source.kt"), replyLinkTarget("source.kt"))
        assertEquals(ReplyLinkTarget.Workspace("./example.com"), replyLinkTarget("./example.com"))
        assertEquals("https://example.dev", bareDomainLink("example.dev"))
        assertNull(bareDomainLink("report.unknown"))
        assertEquals(
            ReplyLinkTarget.External("mailto:a@example.com"),
            replyLinkTarget("mailto:a@example.com"),
        )
        assertEquals(
            ReplyLinkTarget.External("https://example.com/x"),
            replyLinkTarget("<https://example.com/x>"),
        )
        for (source in
            listOf(
                "intent:launch",
                "data:text/html,hello",
                "file://remote/x",
                "https://",
                "https://example.com/\nfoo",
            )) assertEquals(source, ReplyLinkTarget.Unsupported, replyLinkTarget(source))
    }

    @Test
    fun pageLinksNameExactlyOnePageAndNothingElse() {
        assertEquals(ReplyLinkTarget.Page("models"), replyLinkTarget("dsh://open/models"))
        assertEquals("phone-unlocks", pageLinkId("<DSH://Open/phone-unlocks/>"))
        for (source in
            listOf(
                "dsh://open/",
                "dsh://open/a/b",
                "dsh://open/models?x=1",
                "dsh://open/models#top",
                "dsh://user@open/models",
                "dsh://open:1/models",
                "dsh://forum/models",
                "https://open/models",
            )) assertNull(source, pageLinkId(source))
        assertEquals(ReplyLinkTarget.Unsupported, replyLinkTarget("dsh://open/a/b"))
    }
}
