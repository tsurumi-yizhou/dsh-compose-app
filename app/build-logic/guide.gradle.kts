import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

buildscript {
    repositories {
        google()
        mavenCentral()
    }

    dependencies { classpath("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0") }
}

@CacheableTask
abstract class GenerateGuide : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pageSources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringResources: ConfigurableFileCollection

    @get:Input abstract val appVersion: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val files = UserGuide.render(pageSources.files, stringResources.files, appVersion.get())
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val skill = File(output, "skills/dsh-compose-guide").apply { mkdirs() }
        val manifest = buildJsonObject {
            put("name", "dsh-compose-guide")
            put(
                "files",
                buildJsonObject {
                    files.forEach { (path, content) ->
                        val flat = path.replace('/', '-')
                        File(skill, flat).writeText(content)
                        put(flat, path)
                    }
                },
            )
        }
        File(skill, "manifest.json").writeText(manifest.toString() + "\n")
    }
}

/** Reads the opt-in guide blocks on navigation declarations, not general Kotlin API docs. */
object UserGuide {
    private data class Topic(val id: String, val owner: String, val content: Map<String, String>)

    private val locales = listOf("zh", "en")
    private val declaration =
        Regex(
            """/\*\*((?:(?!\*/)[\s\S])*)\*/\s*@Serializable\s+internal data (?:object|class) (\w+)""",
        )
    private val section = Regex("""@guide ([a-z0-9-]+)\b""")
    private val localeTag = Regex("""@guide(Zh|En)\b""")
    private val pathToken = Regex("""\{\{path:([A-Za-z0-9_,]+)}}""")

    fun render(sources: Set<File>, resources: Set<File>, version: String): Map<String, String> {
        val source = sources.sortedBy { it.name }.joinToString("\n") { it.readText() }
        // Read the same page registrations that the app publishes to its module registry.
        val dispatcher = source
        val routes = linkedMapOf<String, String>()
        Regex("""page\(([^)]*)\)\s*\{\s*(\w+)""").findAll(dispatcher).forEach { branch ->
            Regex(""""([a-z0-9-]+)"""").findAll(branch.groupValues[1]).forEach {
                check(routes.put(it.groupValues[1], branch.groupValues[2]) == null) {
                    "Duplicate page link ${it.groupValues[1]}"
                }
            }
        }
        check(routes.isNotEmpty()) { "Guide needs the page navigation declarations" }
        val topics =
            declaration
                .findAll(source)
                .flatMap { match ->
                    val kdoc =
                        match.groupValues[1].lines().joinToString("\n") {
                            it.trimStart().removePrefix("*").removePrefix(" ")
                        }
                    val starts = section.findAll(kdoc).toList()
                    starts.mapIndexed { index, start ->
                        val block =
                            kdoc.substring(
                                start.range.last + 1,
                                starts.getOrNull(index + 1)?.range?.first ?: kdoc.length,
                            )
                        val tags = localeTag.findAll(block).toList()
                        check(
                            tags.map { it.groupValues[1].lowercase() }.sorted() == locales.sorted(),
                        ) {
                            "${start.groupValues[1]} needs exactly one @guideZh and @guideEn block"
                        }
                        Topic(
                            start.groupValues[1],
                            match.groupValues[2],
                            tags
                                .mapIndexed { i, tag ->
                                    tag.groupValues[1].lowercase() to
                                        block
                                            .substring(
                                                tag.range.last + 1,
                                                tags.getOrNull(i + 1)?.range?.first ?: block.length,
                                            )
                                            .trim()
                                }
                                .toMap(),
                        )
                    }
                }
                .toList()
                .sortedWith(compareBy({ routes.values.indexOf(it.owner) }, { it.id }))
        check(topics.isNotEmpty()) { "No page KDoc guide blocks" }
        check(topics.map { it.id }.distinct().size == topics.size) { "Duplicate guide topic" }
        topics.forEach {
            check(it.owner in routes.values) { "${it.owner} has no navigation entry" }
        }
        val files = linkedMapOf<String, String>()
        for (locale in locales) {
            val labels = strings(resources, locale)
            topics.forEach { topic ->
                val body =
                    pathToken.replace(topic.content.getValue(locale)) { token ->
                        token.groupValues[1].split(',').joinToString(" › ") { key ->
                            requireNotNull(labels[key]) {
                                "${topic.id}: missing $locale string $key"
                            }
                        }
                    }
                check(body.startsWith("# ") && "{{" !in body) {
                    "${topic.id}: invalid $locale guide content"
                }
                files["$locale/${topic.id}.md"] = body + "\n"
            }
        }
        files["README.md"] = buildString {
            appendLine("# dsh-compose")
            appendLine("> dsh-compose $version")
            appendLine()
            for (locale in locales) {
                appendLine(if (locale == "zh") "## 中文" else "## English")
                topics.forEach { topic ->
                    val title =
                        files
                            .getValue("$locale/${topic.id}.md")
                            .lineSequence()
                            .first()
                            .removePrefix("# ")
                    val route = routes.entries.first { it.value == topic.owner }.key
                    appendLine(
                        "- [$title]($locale/${topic.id}.md) · [${if (locale == "zh") "打开" else "Open"}](dsh://open/$route)",
                    )
                }
                appendLine()
            }
        }
        files["SKILL.md"] = skill
        for ((name, content) in files) {
            Regex("""\]\(([^)\s]+)\)""").findAll(content).forEach { link ->
                val target = link.groupValues[1]
                when {
                    target.startsWith("dsh://") ->
                        check(target.removePrefix("dsh://open/") in routes) {
                            "$name: unknown page link $target"
                        }
                    !target.contains(":") && !target.startsWith("#") -> {
                        val resolved = File(name).parentFile?.resolve(target) ?: File(target)
                        check(resolved.normalize().invariantSeparatorsPath in files) {
                            "$name: missing guide page $target"
                        }
                    }
                }
            }
        }
        return files
    }

    private fun strings(resources: Set<File>, locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val directory = if (locale == "zh") "values-zh" else "values"
        return buildMap {
            resources
                .filter { it.parentFile.name == directory }
                .sortedBy { it.name }
                .forEach { file ->
                    val nodes =
                        factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
                    for (index in 0 until nodes.length) {
                        val node = nodes.item(index)
                        val name = node.attributes.getNamedItem("name").nodeValue
                        putIfAbsent(
                            name,
                            node.textContent.replace("\\'", "'").replace("\\\"", "\""),
                        )
                    }
                }
        }
    }

    private val skill =
        """
---
# dsh-compose:managed — the dsh-compose app keeps this skill up to date; delete this line to keep your own edits.
name: dsh-compose-guide
description: dsh-compose 手机 App 自带的使用说明（中文 / English）：功能在哪、怎么用、每个设置的作用、删除或重置会丢什么。用户问 dsh-compose App 本身怎么用时先加载它，比如「悬浮条怎么调透明度」「怎么接入自己的模型」「本机环境能卸载吗」「界面字体怎么换」「宠物不见了」。The dsh-compose app's own user guide — where each feature is, what each setting does, what an action deletes.
whenToUse: The user asks how to use the dsh-compose app (手机 App) itself. Not for changing the app's layout (dsh-compose-ui) or writing a plugin (dsh-compose-plugin).
---

# dsh-compose 使用说明

说明文件都在本技能目录里：`README.md`、`zh/`、`en/`，按需读取。

1. 先读 `README.md`：适用版本和每页的内容。再只读和问题相关的一两页：用户说中文读 `zh/`，其他语言读 `en/`，用用户的语言回答。
2. 只照说明回答。入口路径和按钮名照页面原文写；说明里没有的，直说说明里没有写到，不猜、不编，也不拿网页版或其他 App 的做法顶替。
3. 先给结论，再给入口和步骤，越短越好。
4. 入口行后面带 `dsh://open/…` 链接的，回答里原样附上，用户点按即可直达。只用说明里出现过的链接。
5. 涉及删除、重置、卸载、授予权限、放宽确认的，先讲清后果：删什么、留什么、能否撤销。
6. 只读不改：不替用户改设置、不执行命令、不操作手机、不安装或卸载任何东西。用户让你直接改，告诉他在哪里改。
7. 用户的 App 版本（引擎页「更新」一栏）和 README 的适用版本不同，或描述和界面对不上时，说明界面可能已经更新，以 App 为准。
"""
            .trimStart()
}

val generateGuide =
    tasks.register<GenerateGuide>("generateGuide") {
        group = "build"
        description = "Generate the bundled bilingual guide from navigation and page KDoc."
        pageSources.from(
            fileTree("src/main/kotlin/dev/fughetta/dshcompose/ui") {
                include("AppShell.kt", "BuiltInNavigation.kt", "*Destination.kt")
            },
        )
        stringResources.from(
            fileTree("src/main/res") { include("values/*.xml", "values-zh/*.xml") },
        )
        appVersion.set(providers.gradleProperty("appVersion"))
        outputDirectory.set(rootProject.layout.buildDirectory.dir("generated/guideAssets"))
    }

@DisableCachingByDefault(because = "Checks navigation, KDoc and localized labels together")
abstract class VerifyGuide : DefaultTask() {
    @TaskAction
    fun verify() {
        val source = temporaryDir.resolve("Pages.kt")
        source.parentFile.mkdirs()
        source.writeText(
            """
            /**
             * @guide settings
             * @guideZh
             * # 设置
             * `{{path:settings}}` [打开](dsh://open/settings)
             * @guideEn
             * # Settings
             * `{{path:settings}}` [Open](dsh://open/settings)
             */
            @Serializable internal data object Settings : NavKey
            val pages = listOf(page("settings", "general") { Settings })
            """
                .trimIndent(),
        )
        val resources =
            listOf("values" to "Settings", "values-zh" to "设置")
                .map { (locale, label) ->
                    temporaryDir.resolve("$locale/strings.xml").apply {
                        parentFile.mkdirs()
                        writeText(
                            "<resources><string name=\"settings\">$label</string></resources>",
                        )
                    }
                }
                .toSet()
        val rendered = UserGuide.render(setOf(source), resources, "1.0.0")
        check("`设置`" in rendered.getValue("zh/settings.md"))
        check("`Settings`" in rendered.getValue("en/settings.md"))
        check("[Settings](en/settings.md)" in rendered.getValue("README.md"))
        source.writeText(source.readText().replace("dsh://open/settings", "dsh://open/missing"))
        check(
            runCatching { UserGuide.render(setOf(source), resources, "1.0.0") }
                .exceptionOrNull()
                ?.message
                ?.contains("unknown page link") == true,
        )
        println(
            "Guide generated both languages from page KDoc and navigation, and rejected a broken page link",
        )
    }
}

tasks.register<VerifyGuide>("verifyGuide") { group = "verification" }
