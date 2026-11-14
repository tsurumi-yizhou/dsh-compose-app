package dev.fughetta.dshcompose.runtime

import dev.fughetta.dshcompose.engine.runtime.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * API 26 configuration fds rely on the Java child launcher closing inherited fds. Every production
 * source set (the app, its UI module and the client packages) is checked here.
 *
 * This is a source guard for explicit launch APIs, not whole-program analysis: dependency binaries,
 * reflection and C preprocessor aliases need separate review.
 */
class ProcessLaunchGuardTest {
    private data class Token(val text: String, val offset: Int)

    /** Comments/literal text cannot launch; interpolated expressions can. */
    private class Code(private val source: String, private val language: String) {
        val tokens = mutableListOf<Token>()
        private var at = 0
        private val interpolates = language == "kt"
        private val nestedComments = interpolates

        fun read(): List<Token> {
            code(false)
            return tokens
        }

        private fun code(stopAtBrace: Boolean) {
            while (at < source.length) {
                val start = at
                val c = source[at]
                when {
                    c.isWhitespace() -> at++
                    source.startsWith("//", at) -> {
                        at = source.indexOf('\n', at).let { if (it < 0) source.length else it }
                    }
                    source.startsWith("/*", at) -> {
                        at += 2
                        var depth = 1
                        while (at < source.length && depth > 0) {
                            when {
                                nestedComments && source.startsWith("/*", at) -> {
                                    depth++
                                    at += 2
                                }
                                source.startsWith("*/", at) -> {
                                    depth--
                                    at += 2
                                }
                                else -> at++
                            }
                        }
                        check(depth == 0) { "Unterminated comment at $start" }
                    }
                    language in setOf("c", "cc", "cpp", "cxx", "h", "hpp") &&
                        source.startsWith("R\"", at) -> {
                        val open = source.indexOf('(', at + 2)
                        check(open >= 0) { "Unterminated raw string at $start" }
                        val end = ")" + source.substring(at + 2, open) + "\""
                        val close = source.indexOf(end, open + 1)
                        check(close >= 0) { "Unterminated raw string at $start" }
                        at = close + end.length
                    }
                    c == '\'' || c == '"' -> string()
                    language == "kt" && c == '`' -> {
                        val end = source.indexOf('`', at + 1)
                        check(end >= 0) { "Unterminated identifier at $start" }
                        tokens += Token(source.substring(at + 1, end), start)
                        at = end + 1
                    }
                    c == '}' && stopAtBrace -> {
                        at++
                        return
                    }
                    c == '{' -> {
                        tokens += Token("{", at++)
                        code(true)
                        tokens += Token("}", at - 1)
                    }
                    c.isLetterOrDigit() || c == '_' || c == '$' -> {
                        at++
                        while (
                            at < source.length &&
                                (source[at].isLetterOrDigit() || source[at] in "_$")
                        ) at++
                        tokens += Token(source.substring(start, at), start)
                    }
                    else -> {
                        tokens += Token(c.toString(), at)
                        at++
                    }
                }
            }
            check(!stopAtBrace) { "Unterminated code block" }
        }

        private fun string() {
            val start = at
            val quote = source[at]
            val triple = source.startsWith(quote.toString().repeat(3), at)
            val delimiter = quote.toString().repeat(if (triple) 3 else 1)
            at += delimiter.length
            while (at < source.length) {
                when {
                    source.startsWith(delimiter, at) -> {
                        at += delimiter.length
                        return
                    }
                    !(language == "kt" && triple) && source[at] == '\\' -> at += 2
                    interpolates && source.startsWith("${'$'}{", at) -> {
                        at += 2
                        tokens += Token("{", at - 1)
                        code(true)
                        tokens += Token("}", at - 1)
                    }
                    else -> at++
                }
            }
            error("Unterminated string at $start")
        }
    }

    private val nativeNames =
        setOf(
            "ProcessBuilder",
            "exec",
            "fork",
            "vfork",
            "posix_spawn",
            "posix_spawnp",
            "execl",
            "execle",
            "execlp",
            "execv",
            "execve",
            "execvp",
            "execvpe",
            "fexecve",
            "execveat",
        )
    private val cLanguages = setOf("c", "cc", "cpp", "cxx", "h", "hpp")
    private val nativeLanguages = cLanguages + setOf("kt", "java")
    private val allowedNative =
        setOf(
            "data/engine/src/main/kotlin/dev/fughetta/dshcompose/engine/runtime/EngineProcess.kt",
            // Engine installation starts the packaged PRoot through Java ProcessBuilder too.
            "data/engine/src/main/kotlin/dev/fughetta/dshcompose/engine/runtime/EngineSetup.kt",
            // The explicit root channel's `su -c <fixed command>` (argv from RootCommands): also a
            // Java ProcessBuilder child, which closes inherited fds like EngineProcess's launches.
            "feature/phone/src/main/kotlin/dev/fughetta/dshcompose/phone/background/RootProcess.kt",
        )

    private fun nativeCalls(source: String, language: String): List<Token> {
        val t = Code(source, language).read()
        val imports =
            t.indices
                .filter { t[it].text == "import" }
                .map { i ->
                    // Consume the qualified name and optional Kotlin alias, not the
                    // rest of the line: legal Kotlin statements can follow a semicolon.
                    var end = i + 1
                    if (t.getOrNull(end)?.text == "static") end++
                    check(end < t.size) { "Incomplete import" }
                    end++
                    while (t.getOrNull(end)?.text == "." && end + 1 < t.size) end += 2
                    if (t.getOrNull(end)?.text == "as") {
                        check(end + 1 < t.size) { "Incomplete import alias" }
                        end += 2
                    }
                    i until end
                }
        val aliases =
            t.indices
                .mapNotNull { i ->
                    if (t[i].text == "ProcessBuilder" && t.getOrNull(i + 1)?.text == "as")
                        t.getOrNull(i + 2)?.text
                    else null
                }
                .toSet()
        val names =
            nativeNames +
                aliases +
                if (language in cLanguages) setOf("system", "popen") else emptySet()
        return t.indices
            .filter { i ->
                val name = t[i].text
                val previous = t.getOrNull(i - 1)?.text
                val imported = imports.any { i in it }
                // Type aliases and parameters can start a builder elsewhere without
                // spelling its constructor. A new non-import type use needs review.
                val builderReference = name in aliases + "ProcessBuilder" && !imported
                val callableReference =
                    name in names &&
                        !imported &&
                        (previous == ":" || (language in cLanguages && previous == "&"))
                val call =
                    name in names &&
                        t.getOrNull(i + 1)?.text == "(" &&
                        previous !in setOf("fun", "class", "interface") &&
                        // A preceding type denotes a Java/C declaration, not an invocation.
                        !(previous != null &&
                            previous.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) &&
                            previous !in setOf("new", "return", "throw", "await", "else"))
                builderReference || callableReference || call
            }
            .map { t[it] }
    }

    private fun repository(): File =
        generateSequence(File(System.getProperty("user.dir")!!).canonicalFile) { it.parentFile }
            .firstOrNull {
                File(it, "settings.gradle.kts").isFile &&
                    File(it, "app").isDirectory &&
                    File(it, "data/protocol").isDirectory
            } ?: error("Run this source guard inside the repository")

    private fun files(directory: File): List<File> =
        directory
            .walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".git") }
            .filter { it.isFile }
            .toList()

    private fun location(path: String, source: String, token: Token) =
        "$path:${1 + source.take(token.offset).count { it == '\n' }}: ${token.text}"

    @Test
    fun nativeLaunchesOnlyUseTheReviewedJavaOwner() {
        val root = repository()
        // JVM tests start host tools (java, sh, mkfifo) on the build machine; they never ship.
        val excludedSourceSets = setOf("test", "testFixtures", "androidTest")
        val roots =
            listOf(
                File(root, "app/src/main"),
                File(root, "core"),
                File(root, "feature"),
                File(root, "data/engine/src/main"),
            )
        val sourceFiles =
            roots.flatMap(::files).filter {
                it.extension in nativeLanguages &&
                    it.toPath().none { part -> part.toString() in excludedSourceSets }
            }
        assertTrue("The Android source tree must be present", sourceFiles.isNotEmpty())
        for (allowed in allowedNative) assertTrue(
            "The reviewed launcher must be present: $allowed",
            File(root, allowed).isFile,
        )
        val violations = sourceFiles.flatMap { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val source = file.readText()
            nativeCalls(source, file.extension)
                .filter { path !in allowedNative }
                .map { location(path, source, it) }
        }
        assertEquals(
            "New launch sites may inherit API 26 configuration fds; use a reviewed Java owner.\n" +
                violations.joinToString("\n"),
            emptyList<String>(),
            violations,
        )
    }

    @Test
    fun nativeChecksCallsInsteadOfExecutePrefixesOrLiteralText() {
        val source =
            """
            // Runtime.getRuntime().exec("not a call")
            /* ProcessBuilder("text") */
            val description = "fork() and ProcessBuilder()"
            executor.execute { worker.execute(task) }
            env.system().apps()
            fun exec(command: String) = command
            """
                .trimIndent()
        assertTrue(nativeCalls(source, "kt").isEmpty())
        assertTrue(
            nativeCalls("pid_t fork(void); int execve(const char *, char **, char **);", "c")
                .isEmpty(),
        )
    }

    @Test
    fun nativeCallsIncludeAliasesQualifiedNamesAndAllPosixExecVariants() {
        assertEquals(
            2,
            nativeCalls(
                    "new java.lang.ProcessBuilder(args); Runtime.getRuntime().exec(args);",
                    "java",
                )
                .size,
        )
        assertEquals(
            1,
            nativeCalls("import java.lang.ProcessBuilder as PB\nval p = PB(args)", "kt").size,
        )
        for (name in nativeNames - setOf("ProcessBuilder", "exec") + setOf("system", "popen")) {
            assertEquals(
                name,
                listOf(name),
                nativeCalls("auto result = ::$name(args);", "cpp").map { it.text },
            )
        }
    }

    @Test
    fun builderTypeAliasesAndParametersCannotHideAnOutsideStart() {
        val alias =
            "typealias Launcher = java.lang.ProcessBuilder; fun launch() = Launcher(\"true\").start()"
        assertEquals(listOf("ProcessBuilder"), nativeCalls(alias, "kt").map { it.text })
        assertEquals(
            1,
            nativeCalls("fun launch(builder: ProcessBuilder) = builder.start()", "kt").size,
        )
        assertEquals(
            1,
            nativeCalls(
                    "import java.lang.ProcessBuilder as PB\nfun launch(builder: PB) = builder.start()",
                    "kt",
                )
                .size,
        )
        assertTrue(
            nativeCalls("import java.lang.ProcessBuilder\nval label = \"ProcessBuilder\"", "kt")
                .isEmpty(),
        )
    }

    @Test
    fun nativeCallableReferencesCannotSaveASeparateLaunchEntryPoint() {
        assertEquals(1, nativeCalls("val launch = Runtime.getRuntime()::exec", "kt").size)
        assertEquals(1, nativeCalls("val launch = ProcessBuilder::start", "kt").size)
        assertEquals(1, nativeCalls("var launch = Runtime.getRuntime()::exec;", "java").size)
        assertEquals(1, nativeCalls("auto launch = &posix_spawn; launch(args);", "cpp").size)
    }

    @Test
    fun anImportCannotHideAnotherStatementOnTheSameLine() {
        val source =
            "import java.lang.ProcessBuilder; typealias Launcher = ProcessBuilder\nfun launch() = Launcher(\"true\").start()"
        assertEquals(listOf("ProcessBuilder"), nativeCalls(source, "kt").map { it.text })
        assertEquals(
            1,
            nativeCalls(
                    "import java.lang.ProcessBuilder as PB; typealias Launcher = PB\nfun launch() = Launcher(\"true\").start()",
                    "kt",
                )
                .size,
        )
        assertTrue(
            nativeCalls(
                    "import /* ; not a delimiter */ java.lang.ProcessBuilder\nval name = \"text\"",
                    "kt",
                )
                .isEmpty(),
        )
        assertTrue(nativeCalls("import java.lang.\nProcessBuilder;", "java").isEmpty())
    }

    @Test
    fun interpolationContainsExecutableCodeButRawLiteralTextDoesNot() {
        assertEquals(
            1,
            nativeCalls("val value = \"${'$'}{Runtime.getRuntime().exec(args)}\"", "kt").size,
        )
        assertTrue(nativeCalls("auto text = R\"tag(fork() and execve())tag\";", "cpp").isEmpty())
    }
}
