package dev.hunk.goland

import com.intellij.driver.client.Remote
import com.intellij.driver.model.LockSemantics
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.closeAndDisableAllBalloonNotifications
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.remote.SwingHierarchyService
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.runner.Starter
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import java.net.URI
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.hunk.goland.service.HunkSessionTransport
import java.time.Duration
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Remote("java.lang.Class")
private interface TraceClassRef {
    fun getName(): String
}

@Remote("java.lang.Object")
private interface TraceObjectRef {
    fun getClass(): TraceClassRef
    override fun toString(): String
}

@Remote("java.awt.Component")
private interface TraceAwtComponentRef {
    fun getParent(): TraceAwtComponentRef?
    fun getClass(): TraceClassRef
}

@Remote("com.intellij.ide.DataManager")
private interface TraceDataManagerRef {
    fun getDataContext(component: TraceAwtComponentRef): TraceDataContextRef
}

@Remote("com.intellij.openapi.actionSystem.DataContext")
private interface TraceDataContextRef {
    fun getData(dataId: String): TraceObjectRef?
}

@Remote("com.intellij.openapi.vcs.history.VcsRevisionNumber")
private interface TraceRevisionNumberRef {
    fun asString(): String
}

@Remote("com.intellij.openapi.vcs.FilePath")
private interface TraceFilePathRef {
    fun getPath(): String
}

@Remote("com.intellij.openapi.vcs.changes.ContentRevision")
private interface TraceContentRevisionRef {
    fun getFile(): TraceFilePathRef
    fun getRevisionNumber(): TraceRevisionNumberRef
}

@Remote("com.intellij.openapi.vcs.changes.Change")
private interface TraceChangeRef {
    fun getBeforeRevision(): TraceContentRevisionRef?
    fun getAfterRevision(): TraceContentRevisionRef?
    fun getClass(): TraceClassRef
}

@Remote("com.intellij.openapi.ListSelection")
private interface TraceListSelectionRef {
    fun getList(): List<TraceObjectRef>
    fun getSelectedIndex(): Int
}

@Remote("com.intellij.diff.requests.DiffRequest")
private interface TraceDiffRequestRef {
    fun getTitle(): String?
    fun getClass(): TraceClassRef
}

@Remote("com.intellij.diff.requests.ContentDiffRequest")
private interface TraceContentDiffRequestRef {
    fun getTitle(): String?
    fun getContents(): List<TraceObjectRef>
    fun getContentTitles(): List<String?>
    fun getClass(): TraceClassRef
}

@Remote("com.intellij.openapi.util.UserDataHolderBase")
private interface TraceUserDataHolderRef {
    fun getUserDataString(): String
}

/**
 * Guards the reported failure against the real KServe worktree and real upstream Hunk.
 *
 * Both known scenarios run so one failure does not hide evidence for the other. The test retains
 * its complete diagnostics and then fails if either exact gutter target did not become writable.
 */
class HunkRealProjectDiagnosticTest {
    private val transport = HunkSessionTransport()
    @Test
    fun `traces compare with master in the real KServe worktree`() {
        val project = requiredPath("hunk.realProject")
        require(Files.isDirectory(project.resolve(".git").normalize()) || Files.exists(project.resolve(".git"))) {
            "$project is not a Git worktree"
        }
        val base = System.getProperty("hunk.realBase", "master")
        val head = System.getProperty(
            "hunk.realHead",
            "upstream/llmisvc/feat/lora-regex-routing",
        )
        val output = requiredPath("hunk.realDiagnosticDir")
        Files.createDirectories(output)
        val evidence = DiagnosticEvidence(output)
        val ux = UxReport.configured()
        val baseRevision = gitRevision(project, base)
        val headRevision = gitRevision(project, head)
        val currentRevision = gitRevision(project, "HEAD")
        require(currentRevision == headRevision) {
            "The real-project regression must run on $head ($headRevision), but HEAD is $currentRevision"
        }
        val expectedDiffTarget = baseRevision
        val outcomes = mutableListOf<ScenarioOutcome>()
        val pluginPath = Path.of(
            System.getProperty("hunk.diagnosticPlugin", System.getProperty("path.to.build.plugin")),
        )
        val existingConfig = System.getProperty("hunk.diagnosticConfig")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)

        val context = Starter.newContext(
            "hunk-real-project-${System.nanoTime()}",
            goLandTestCase(project),
        ).apply {
            existingConfig?.let(::copyExistingConfig)
            preserveSystemDir = true
            PluginConfigurator(this).installPluginFromPath(pluginPath)
            applyVMOptionsPatch {
                addSystemProperty("hunk.diagnostics", "true")
                System.getProperty("hunk.uiToolkit")
                    ?.takeIf(String::isNotBlank)
                    ?.let { addSystemProperty("awt.toolkit.name", it) }
            }
            disableGotItTooltips()
            disableAIAssistantToolwindowActivationOnStart()
        }

        evidence.append("configuration") {
            appendLine("project=$project")
            appendLine("base=$base")
            appendLine("head=$head")
            appendLine("baseRevision=$baseRevision")
            appendLine("headRevision=$headRevision")
            appendLine("expectedDiffTarget=$expectedDiffTarget")
            appendLine("plugin=$pluginPath")
            appendLine("copiedConfig=${existingConfig ?: "<clean Starter config>"}")
            appendLine("ide=${context.ide}")
            appendLine("starterHome=${context.paths.testHome}")
            appendLine("daemonBefore=${daemonSessions()}")
            appendLine(processSnapshot(project))
        }

        try {
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(10.minutes)
                val frame = ideFrame()
                val ui = HunkUiDriver(frame)
                frame.setFocus()
                frame.driver.closeAndDisableAllBalloonNotifications()

                ux.capture(
                    frame,
                    "01-real-project-open",
                    "Real KServe worktree opened",
                    "The installed GoLand build opened the exact user worktree before any comparison was created.",
                )
                captureFrameEvidence(evidence, frame, "01-project-open", null, project)

                if (System.getProperty("hunk.diagnosticWarmupWorkingTree", "false").toBoolean()) {
                    warmUpManagedWorkingTree(frame, ui, evidence, ux, project)
                }

                outcomes += runScenario(
                    frame = frame,
                    ui = ui,
                    evidence = evidence,
                    ux = ux,
                    project = project,
                    base = base,
                    expectedDiffTarget = expectedDiffTarget,
                    scenario = Scenario(
                        id = "modified-file",
                        file = "pkg/controller/v1alpha2/llmisvc/config_loader.go",
                        marker = "LoRAModelRoutingStrategy",
                        line = 100,
                        twoSided = true,
                    ),
                )
                outcomes += runScenario(
                    frame = frame,
                    ui = ui,
                    evidence = evidence,
                    ux = ux,
                    project = project,
                    base = base,
                    expectedDiffTarget = expectedDiffTarget,
                    scenario = Scenario(
                        id = "added-file",
                        file = "pkg/controller/v1alpha2/llmisvc/router_lora.go",
                        marker = "ParseLoRAModelRoutingStrategy",
                        line = 54,
                        twoSided = false,
                    ),
                )
            }
        } catch (error: Throwable) {
            evidence.append("fatal") {
                appendLine(error.stackTraceToString())
                appendLine("daemonAtFailure=${daemonSessions()}")
                appendLine(processSnapshot(project))
            }
            ux.finish("diagnostic failed: ${error::class.simpleName}")
            throw error
        } finally {
            copyStarterLogs(context.paths.testHome, output.resolve("starter"))
            evidence.append("final") {
                appendLine("daemonAfter=${daemonSessions()}")
                appendLine(processSnapshot(project))
            }
        }

        val ideaLog = copiedIdeaLogs(output)
        val revisionActivation = diagnosticLine(
            ideaLog,
            "managed-review-activate",
            expectedDiffTarget,
        )
        val revisionRegistration = diagnosticLine(
            ideaLog,
            "managed-review-session-registered",
            expectedDiffTarget,
        )
        try {
            assertSoftly { softly ->
                softly.assertThat(outcomes)
                    .describedAs("exact real-project gutter scenarios")
                    .allMatch(
                        { outcome -> outcome.error == null },
                        outcomes.joinToString("\n") { outcome -> outcome.failureSummary() },
                    )
                softly.assertThat(ideaLog)
                    .describedAs("structured Hunk lifecycle diagnostics in copied idea.log")
                    .contains(
                        "event=review-target-resolved",
                        "file=pkg/controller/v1alpha2/llmisvc/config_loader.go side=new reviewLine=100",
                        "file=pkg/controller/v1alpha2/llmisvc/router_lora.go side=new reviewLine=54",
                        "event=managed-review-process-started",
                        expectedDiffTarget,
                        "event=managed-review-activate",
                        "event=managed-review-session-registered",
                        "event=matching-review-start-succeeded",
                        "event=comment-target-validation-succeeded",
                        "event=hunk-diagnostic-session-scan",
                        "event=hunk-diagnostic-http-complete",
                        "event=hunk-diagnostic-review-snapshot",
                    )
                    .doesNotContain(
                        "event=managed-review-start-failed",
                        "event=managed-review-registration-failed",
                        "event=matching-review-start-failed",
                    )
                softly.assertThat(revisionActivation)
                    .describedAs("revision review replaces the working-tree process")
                    .contains("replacedPid=")
                    .doesNotContain("replacedPid=none")
                softly.assertThat(revisionRegistration)
                    .describedAs("revision review session registration diagnostics")
                    .contains("pid=", "attempts=", "elapsedMs=", "sessionId=")
            }
            ux.finish("real-project regression passed")
        } catch (error: Throwable) {
            evidence.append("regression-failed") {
                appendLine(error.stackTraceToString())
            }
            ux.finish("real-project regression failed: ${error::class.simpleName}")
            throw error
        }
    }

    private fun warmUpManagedWorkingTree(
        frame: UiComponent,
        ui: HunkUiDriver,
        evidence: DiagnosticEvidence,
        ux: UxReport,
        project: Path,
    ) {
        val file = "pkg/controller/v1alpha2/llmisvc/controller.go"
        frame.driver.openFile(file)
        val editor = frame.codeEditorForFile("controller.go")
        editor.goToPosition(291, 2)
        ui.openThreadWithAutomaticManagedReview(editor)
        awaitCondition("plugin-managed working-tree Hunk process", 30.seconds) {
            processSnapshot(project).contains("args=[diff, --watch]")
        }
        captureFrameEvidence(evidence, frame, "02-working-tree-managed", editor, project)
        ux.capture(
            frame,
            "02-working-tree-managed",
            "Plugin-managed working-tree review",
            "A normal editor action first starts hunk diff --watch in this same GoLand project, matching the reported workflow before Compare with Branch.",
        )
        ui.closeThread(editor)
    }

    private fun runScenario(
        frame: UiComponent,
        ui: HunkUiDriver,
        evidence: DiagnosticEvidence,
        ux: UxReport,
        project: Path,
        base: String,
        expectedDiffTarget: String,
        scenario: Scenario,
    ): ScenarioOutcome {
        evidence.append("${scenario.id}:before-comparison") {
            appendLine("daemon=${daemonSessions()}")
            appendLine(processSnapshot(project))
        }
        ui.openCompareWithBranch(base, project.fileName.toString())
        val changes = ui.changesTreeContaining(scenario.file.substringAfterLast('/'))
        val expanded = changes.collectExpandedPathsAsStrings()
        evidence.append("${scenario.id}:comparison-tree") {
            appendLine(expanded.joinToString("\n"))
        }
        changes.selectRows { rows ->
            rows.filter { row -> row.endsWith(scenario.file.substringAfterLast('/')) }
        }
        awaitCondition("${scenario.id} file selection") {
            changes.collectSelectedPaths().any { path ->
                path.path.lastOrNull()?.endsWith(scenario.file.substringAfterLast('/')) == true
            }
        }
        ui.clickActionButton("Show Diff")
        val editor = if (scenario.twoSided) {
            ui.waitForDiffEditors().second
        } else {
            ui.waitForDiffEditorContaining(scenario.marker)
        }
        editor.goToPosition(scenario.line, 2)
        captureFrameEvidence(evidence, frame, "${scenario.id}:before-action", editor, project)
        ux.capture(
            frame,
            if (scenario.twoSided) "10-modified-before-action" else "20-added-before-action",
            "${scenario.file} before Hunk action",
            "The real Compare with Branch diff immediately before invoking Add Hunk Comment from its line gutter.",
        )

        val actionResult = runCatching {
            ui.openThreadFromDiffGutterMenu(editor, scenario.line, scenario.file)
            awaitCondition("${scenario.id} exact base-to-working-tree Hunk process", 30.seconds) {
                processSnapshot(project).contains("args=[diff, $expectedDiffTarget, --watch]")
            }
        }
        captureFrameEvidence(evidence, frame, "${scenario.id}:after-action", editor, project)
        evidence.append("${scenario.id}:action-result") {
            appendLine("success=${actionResult.isSuccess}")
            actionResult.exceptionOrNull()?.let { appendLine(it.stackTraceToString()) }
            appendLine("daemon=${daemonSessions()}")
            appendLine(processSnapshot(project))
        }
        ux.capture(
            frame,
            if (scenario.twoSided) "11-modified-after-action" else "21-added-after-action",
            "${scenario.file} after Hunk action",
            "The resulting inline review state after the real gutter action and automatic managed-review attempt.",
        )
        return ScenarioOutcome(scenario.id, actionResult.exceptionOrNull())
    }

    private fun captureFrameEvidence(
        evidence: DiagnosticEvidence,
        frame: UiComponent,
        label: String,
        editor: JEditorUiComponent?,
        project: Path,
    ) {
        evidence.append("$label:visible-text") {
            frame.getAllTexts().map { it.text }.distinct().forEach(::appendLine)
        }
        evidence.write("${label.fileSafe()}.swing.xml") {
            runCatching {
                frame.driver.service(SwingHierarchyService::class)
                    .getSwingHierarchyAsDOM(frame.component, false)
            }.getOrElse { "Swing hierarchy unavailable: ${it.stackTraceToString()}" }
        }
        if (editor != null) {
            evidence.append("$label:diff-context") {
                appendLine(traceDiffContext(frame, editor))
            }
        }
        evidence.append("$label:hunk-runtime") {
            appendLine("daemon=${daemonSessions()}")
            appendLine(processSnapshot(project))
        }
    }

    private fun traceDiffContext(frame: UiComponent, editor: JEditorUiComponent): String =
        frame.driver.withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
            buildString {
                val component = cast(editor.component, TraceAwtComponentRef::class)
                appendLine("componentParents:")
                var parent: TraceAwtComponentRef? = component
                repeat(80) {
                    val current = parent ?: return@repeat
                    appendLine("  ${current.getClass().getName()}")
                    parent = current.getParent()
                }
                val context = service(TraceDataManagerRef::class).getDataContext(component)
                DATA_IDS.forEach { id ->
                    val value = runCatching { context.getData(id) }.getOrNull()
                    appendLine("data[$id]=${value?.getClass()?.getName() ?: "<null>"}")
                    when (id) {
                        "vcs.CurrentChange" -> value?.let {
                            appendLine(describeChange(cast(it, TraceChangeRef::class), "  "))
                        }
                        "vcs.ChangesSelection" -> value?.let {
                            val selection = cast(it, TraceListSelectionRef::class)
                            appendLine("  selectedIndex=${selection.getSelectedIndex()}")
                            selection.getList().take(12).forEachIndexed { index, change ->
                                appendLine(describeChange(cast(change, TraceChangeRef::class), "  [$index] "))
                            }
                        }
                        "diff_request" -> value?.let {
                            val request = cast(it, TraceDiffRequestRef::class)
                            appendLine("  title=${request.getTitle()}")
                            runCatching {
                                val contentRequest = cast(it, TraceContentDiffRequestRef::class)
                                appendLine("  contentTitles=${contentRequest.getContentTitles()}")
                                contentRequest.getContents().forEachIndexed { index, content ->
                                    appendLine("  content[$index]=${content.getClass().getName()}")
                                    runCatching {
                                        cast(content, TraceUserDataHolderRef::class).getUserDataString()
                                    }.onSuccess { userData ->
                                        appendLine("  content[$index].userData=$userData")
                                    }.onFailure { error ->
                                        appendLine("  content[$index].userDataError=${error.message}")
                                    }
                                }
                            }.onFailure { error ->
                                appendLine("  contentRequestError=${error.message}")
                            }
                        }
                    }
                }
            }
        }

    private fun describeChange(change: TraceChangeRef, prefix: String): String = buildString {
        append(prefix)
        append(change.getClass().getName())
        append(" before=")
        append(describeRevision(change.getBeforeRevision()))
        append(" after=")
        append(describeRevision(change.getAfterRevision()))
    }

    private fun describeRevision(revision: TraceContentRevisionRef?): String = when (revision) {
        null -> "<null>"
        else -> "${revision.getFile().getPath()}@${revision.getRevisionNumber().asString()}"
    }

    private fun daemonSessions(): String {
        return runCatching {
            val payload = JsonObject().apply { addProperty("action", "list") }
            transport.post(URI.create("http://127.0.0.1:47657/session-api"), payload, Duration.ofSeconds(3)).let {
                val sessions = JsonParser.parseString(it.body()).asJsonObject.getAsJsonArray("sessions")
                val summaries = sessions?.map { value ->
                    val session = value.asJsonObject
                    listOf("sessionId", "pid", "repoRoot", "fileCount", "liveCommentCount", "reviewNoteCount")
                        .joinToString(" ") { name -> "$name=${session.get(name) ?: "none"}" }
                }.orEmpty()
                "status=${it.statusCode()} sessions=$summaries"
            }
        }.getOrElse { "unavailable=${it::class.qualifiedName} (payload omitted)" }
    }

    private fun processSnapshot(project: Path): String = buildString {
        appendLine("hunkProcesses:")
        ProcessHandle.allProcesses().use { processes ->
            processes
                .filter { process ->
                    val command = process.info().command().orElse("")
                    Path.of(command).fileName?.toString() == "hunk"
                }
                .sorted(compareBy(ProcessHandle::pid))
                .forEach { process ->
                    val info = process.info()
                    val cwd = runCatching {
                        Path.of("/proc", process.pid().toString(), "cwd").toRealPath()
                    }.getOrNull()
                    appendLine(
                        "  pid=${process.pid()} parent=${process.parent().map(ProcessHandle::pid).orElse(-1)} " +
                            "cwd=$cwd command=${info.command().orElse("")} " +
                            "args=${info.arguments().map { it.toList() }.orElse(emptyList())}",
                    )
                }
        }
        appendLine("expectedProject=${project.toAbsolutePath().normalize()}")
    }

    private fun copyStarterLogs(source: Path, target: Path) {
        if (!Files.isDirectory(source)) return
        Files.walk(source).use { entries ->
            entries.filter(Files::isRegularFile)
                .filter { path ->
                    val name = path.name
                    name.endsWith(".log") || name.endsWith(".txt") || name.endsWith(".json")
                }
                .forEach { path ->
                    val relative = source.relativize(path)
                    val destination = target.resolve(relative)
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
        }
    }

    private fun copiedIdeaLogs(output: Path): String {
        val starter = output.resolve("starter")
        if (!Files.isDirectory(starter)) return ""
        return Files.walk(starter).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { it.name == "idea.log" }
                .sorted()
                .map(Files::readString)
                .toList()
                .joinToString("\n")
        }
    }

    private fun diagnosticLine(log: String, event: String, input: String): String =
        log.lineSequence().firstOrNull { line ->
            line.contains("event=$event") && line.contains(input)
        }.orEmpty()

    private fun gitRevision(project: Path, revision: String): String {
        val process = ProcessBuilder("git", "-C", project.toString(), "rev-parse", revision)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { "Could not resolve Git revision $revision: $output" }
        return output.lineSequence().first()
    }

    private fun requiredPath(property: String): Path = System.getProperty(property)
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.toAbsolutePath()
        ?.normalize()
        ?: error("Run with -P${property.replace('.', '-')}=/absolute/path")

    private fun String.fileSafe(): String = replace(Regex("[^A-Za-z0-9._-]+"), "-")

    private data class Scenario(
        val id: String,
        val file: String,
        val marker: String,
        val line: Int,
        val twoSided: Boolean,
    )

    private data class ScenarioOutcome(val id: String, val error: Throwable?) {
        fun failureSummary(): String = error?.let { "$id failed: ${it.stackTraceToString()}" }
            ?: "$id passed"
    }

    private companion object {
        val DATA_IDS = listOf(
            "editor",
            "virtualFile",
            "diff_current_editor",
            "diff_current_content",
            "diff_request",
            "diff_frame_viewer",
            "vcs.CurrentChange",
            "vcs.ChangesSelection",
        )
    }
}

private class DiagnosticEvidence(private val output: Path) {
    private val trace = output.resolve("trace.txt")

    init {
        Files.createDirectories(output)
        Files.deleteIfExists(trace)
    }

    @Synchronized
    fun append(section: String, body: StringBuilder.() -> Unit) {
        Files.createDirectories(output)
        val text = buildString {
            appendLine()
            appendLine("===== $section =====")
            body()
        }
        Files.writeString(
            trace,
            text,
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    fun write(name: String, contents: () -> String) {
        Files.createDirectories(output)
        Files.writeString(output.resolve(name), contents(), StandardCharsets.UTF_8)
    }
}
