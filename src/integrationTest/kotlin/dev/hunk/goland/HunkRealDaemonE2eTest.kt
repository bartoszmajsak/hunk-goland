package dev.hunk.goland

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pty4j.PtyProcessBuilder
import com.intellij.driver.sdk.closeAndDisableAllBalloonNotifications
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.runner.Starter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.net.URI
import dev.hunk.goland.service.HunkSessionTransport
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** End-to-end proof against an installed, unmodified upstream Hunk binary. */
class HunkRealDaemonE2eTest {
    @Test
    fun `recovers an exact review after the IDE-owned Hunk process and GoLand restart`(
        @TempDir temporaryDirectory: Path,
    ) {
        val project = TestGoProject.create(temporaryDirectory)
        val executable = resolveExecutable(System.getProperty("hunk.executable"))
        require(Files.isExecutable(executable)) { "Missing executable upstream Hunk binary: $executable" }
        val output = Path.of(System.getProperty("hunk.uxReportDir")).toAbsolutePath().normalize()
        val ux = UxReport.configuredSubreport("recovery")
        val configHome = Files.createDirectories(temporaryDirectory.resolve("xdg-config-recovery"))
        Files.createDirectories(output)
        var daemon: RunningDaemon? = null
        var status = "failed"

        try {
            val runningDaemon = startDaemon(executable, output, configHome)
            daemon = runningDaemon
            val port = runningDaemon.port
            val pluginPath = Path.of(System.getProperty("path.to.build.plugin"))
            lateinit var originalSessionId: String

            val ide = Starter.newContext(
                "hunk-recovery-restart-${System.nanoTime()}",
                goLandTestCase(project),
            ).apply {
                PluginConfigurator(this).installPluginFromPath(pluginPath)
                applyVMOptionsPatch {
                    addSystemProperty("hunk.session.port", port)
                    addSystemProperty("hunk.executable", executable.toString())
                    addSystemProperty("hunk.diagnostics", "true")
                    withEnv("XDG_CONFIG_HOME", configHome.toString())
                    hunkTestEnvironment(configHome).forEach { (name, value) -> withEnv(name, value) }
                    System.getProperty("hunk.uiToolkit")
                        ?.takeIf(String::isNotBlank)
                        ?.let { addSystemProperty("awt.toolkit.name", it) }
                }
                disableGotItTooltips()
            }
            ide.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                openFile(TestGoProject.MAIN_FILE)
                val frame = ideFrame()
                val ui = HunkUiDriver(frame)
                frame.setFocus()
                frame.driver.closeAndDisableAllBalloonNotifications()
                val editor = frame.codeEditorForFile("main.go")
                ui.selectLines(editor, TestGoProject.CHANGED_LINE, TestGoProject.UNCHANGED_HUNK_LINE)

                ui.openThreadWithAutomaticManagedReview(editor)
                awaitCondition("first managed reviewer composer", 30.seconds) {
                    frame.button { byAccessibleName("Add comment") }
                        .let { it.present() && it.component.isShowing() }
                }
                originalSessionId = awaitValue("first managed recovery session", 30.seconds) {
                    sessions(port)
                        .singleOrNull { it.textProperty("repoRoot") == project.toString() }
                        ?.textProperty("sessionId")
                }
                ui.submitCommentWithButton(RECOVERY_REVIEW_COMMENT)
                val originalReview = awaitValue("comment checkpoint before restart", 30.seconds) {
                    reviewNotes(port, originalSessionId).takeIf {
                        HunkReviewAssert.containsNote(it, RECOVERY_REVIEW_COMMENT)
                    }
                }.also { HunkReviewAssert.assertThat(it).hasReviewerRange(
                    RECOVERY_REVIEW_COMMENT, TestGoProject.CHANGED_LINE, TestGoProject.UNCHANGED_HUNK_LINE,
                ) }

                post(
                    port,
                    JsonObject().apply {
                        addProperty("action", "comment-add")
                        add("selector", sessionSelector(originalSessionId))
                        addProperty("replyTo", HunkReviewAssert.noteId(originalReview, RECOVERY_REVIEW_COMMENT))
                        addProperty("summary", RECOVERY_AGENT_NOTE)
                        addProperty("author", "restart-agent")
                        addProperty("reveal", false)
                    },
                )
                awaitCondition("agent checkpoint before restart", 20.seconds) {
                    frame.xx { byAccessibleName("Agent response body") }
                        .list()
                        .filter { it.component.isShowing() }
                        .any { it.htmlInIde().contains(RECOVERY_AGENT_NOTE) }
                }
            }

            awaitCondition("first IDE-managed review shutdown", 20.seconds) {
                sessions(port).none { it.textProperty("sessionId") == originalSessionId }
            }

            lateinit var recoveredSessionId: String
            ide.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                openFile(TestGoProject.MAIN_FILE)
                val frame = ideFrame()
                val ui = HunkUiDriver(frame)
                frame.setFocus()
                frame.driver.closeAndDisableAllBalloonNotifications()
                val editor = frame.codeEditorForFile("main.go")
                editor.goToPosition(TestGoProject.CHANGED_LINE, 2)

                ui.openThreadWithAutomaticManagedReview(editor)
                recoveredSessionId = awaitValue("replacement managed recovery session", 30.seconds) {
                    sessions(port)
                        .singleOrNull { session ->
                            session.textProperty("repoRoot") == project.toString() &&
                                session.textProperty("sessionId") != originalSessionId
                        }
                        ?.textProperty("sessionId")
                }
                val recoveredNativeReview = awaitValue(
                    "reviewer comment replay with native provenance",
                    40.seconds,
                ) {
                    reviewNotes(port, recoveredSessionId).takeIf {
                        HunkReviewAssert.containsNote(it, RECOVERY_REVIEW_COMMENT)
                    }
                }
                HunkReviewAssert.assertThat(recoveredNativeReview)
                    .hasReviewerRange(RECOVERY_REVIEW_COMMENT,
                        TestGoProject.CHANGED_LINE, TestGoProject.UNCHANGED_HUNK_LINE)
                    .hasReviewerComment(
                        RECOVERY_REVIEW_COMMENT,
                        "user",
                        TestGoProject.CHANGED_LINE,
                    )
                    .doesNotHaveNote(RECOVERY_AGENT_NOTE)

                awaitCondition("locally recovered agent history in GoLand", 30.seconds) {
                    frame.xx { byAccessibleName("Agent response body") }
                        .list()
                        .filter { it.component.isShowing() }
                        .any { it.htmlInIde().contains(RECOVERY_AGENT_NOTE) }
                }
                awaitCondition("local recovery attribution in GoLand", 20.seconds) {
                    frame.xx { byAccessibleName("Agent response recovered locally") }
                        .list()
                        .any { it.component.isShowing() }
                }
                awaitCondition("recovered reply points to the verified replayed parent") {
                    frame.x { byAccessibleName("Reply to Hunk GoLand test") }
                        .let { it.present() && it.component.isShowing() }
                }
                val reviewerY = ui.messageBodyY(
                    "Comment body",
                    "Restore this reviewer comment",
                )
                val agentY = ui.messageBodyY(
                    "Agent response body",
                    "Keep this external agent response",
                )
                assertThat(reviewerY)
                    .describedAs("recovered reviewer comment remains before its agent response")
                    .isLessThan(agentY)
                ux.capture(
                    frame,
                    "01-recovered-review",
                    "Review recovered after GoLand restart",
                    "The reviewer comment was replayed into a fresh upstream Hunk session with " +
                        "reviewer provenance. The external agent response remains visible as " +
                        "read-only local history, with explicit recovery attribution.",
                )
            }

            Files.writeString(
                output.resolve("real-daemon-recovery-e2e.json"),
                JsonObject().apply {
                    addProperty("status", "passed")
                    addProperty("hunkVersion", hunkVersion(executable))
                    addProperty("originalSessionId", originalSessionId)
                    addProperty("recoveredSessionId", recoveredSessionId)
                    addProperty("reviewerReplayedAs", "user")
                    addProperty("agentHistory", "local-only")
                }.toString(),
                StandardCharsets.UTF_8,
            )
            status = "passed"
        } finally {
            ux.finish(status)
            stopDaemon(daemon)
        }
    }

    @Test
    fun `persists reviewer comments and receives external agent updates through upstream Hunk`(
        @TempDir temporaryDirectory: Path,
    ) {
        val project = TestGoProject.create(temporaryDirectory)
        val executable = resolveExecutable(System.getProperty("hunk.executable"))
        require(Files.isExecutable(executable)) { "Missing executable upstream Hunk binary: $executable" }
        val output = Path.of(System.getProperty("hunk.uxReportDir")).toAbsolutePath().normalize()
        val configHome = Files.createDirectories(temporaryDirectory.resolve("xdg-config"))
        Files.createDirectories(output)
        var daemon: RunningDaemon? = null
        var externalReview: RunningReview? = null

        try {
            val runningDaemon = startDaemon(executable, output, configHome)
            daemon = runningDaemon
            val port = runningDaemon.port
            val requiredActions = listOf("review", "navigate", "comment-add", "comment-rm")
            assertThat(capabilities(port).stringArray("actions"))
                .describedAs("native upstream Hunk session capabilities")
                .containsAll(requiredActions)
            externalReview = startReview(executable, project, port, configHome, output)

            val pluginPath = Path.of(System.getProperty("path.to.build.plugin"))
            Starter.newContext(
                "hunk-real-daemon-${System.nanoTime()}",
                goLandTestCase(project),
            ).apply {
                PluginConfigurator(this).installPluginFromPath(pluginPath)
                applyVMOptionsPatch {
                    addSystemProperty("hunk.session.port", port)
                    addSystemProperty("hunk.executable", executable.toString())
                    addSystemProperty("hunk.diagnostics", "true")
                    withEnv("XDG_CONFIG_HOME", configHome.toString())
                    hunkTestEnvironment(configHome).forEach { (name, value) -> withEnv(name, value) }
                    System.getProperty("hunk.uiToolkit")
                        ?.takeIf(String::isNotBlank)
                        ?.let { addSystemProperty("awt.toolkit.name", it) }
                }
                disableGotItTooltips()
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                openFile(TestGoProject.MAIN_FILE)
                val frame = ideFrame()
                val ui = HunkUiDriver(frame)
                frame.setFocus()
                frame.driver.closeAndDisableAllBalloonNotifications()
                val editor = frame.codeEditorForFile("main.go")
                editor.goToPosition(TestGoProject.CHANGED_LINE, 2)

                ui.openThreadWithAutomaticManagedReview(editor)
                awaitCondition("upstream managed reviewer composer", 30.seconds) {
                    frame.button { byAccessibleName("Add comment") }
                        .let { it.present() && it.component.isShowing() }
                }
                val managedSessionId = awaitValue("separate IDE-managed upstream review", 30.seconds) {
                    sessions(port)
                        .singleOrNull { session ->
                            session.textProperty("repoRoot") == project.toString() &&
                                session.textProperty("sessionId") != externalReview.sessionId
                        }
                        ?.textProperty("sessionId")
                }
                ui.submitCommentWithButton(REVIEW_COMMENT)

                val firstPersisted = awaitValue("first upstream persisted reviewer comment", 30.seconds) {
                    reviewNotes(port, managedSessionId).takeIf {
                        HunkReviewAssert.containsNote(it, REVIEW_COMMENT)
                    }
                }
                ui.waitForSubmission(TestGoProject.CHANGED_LINE)
                HunkReviewAssert.assertThat(firstPersisted)
                    .hasReviewerComment(REVIEW_COMMENT, "user", TestGoProject.CHANGED_LINE)
                ui.awaitCommentAuthor("Hunk GoLand test")

                ui.submitCommentWithButton(WRAPPED_MARKDOWN_COMMENT)
                val sequentiallyPersisted = awaitValue(
                    "sequential wrapped upstream reviewer comment",
                    30.seconds,
                ) {
                    reviewNotes(port, managedSessionId).takeIf {
                        HunkReviewAssert.containsNote(it, REVIEW_COMMENT) &&
                            HunkReviewAssert.containsNote(it, WRAPPED_MARKDOWN_COMMENT)
                    }
                }
                ui.waitForSubmission(TestGoProject.CHANGED_LINE)
                HunkReviewAssert.assertThat(sequentiallyPersisted)
                    .hasReviewerComment(REVIEW_COMMENT, "user", TestGoProject.CHANGED_LINE)
                    .hasReviewerComment(
                        WRAPPED_MARKDOWN_COMMENT,
                        "user",
                        TestGoProject.CHANGED_LINE,
                    )

                ui.closeThread(editor)
                ui.clearSelection(editor)
                ui.selectLines(editor, TestGoProject.CHANGED_LINE, TestGoProject.UNCHANGED_HUNK_LINE)
                ui.openThreadWithAutomaticManagedReview(editor)
                ui.submitCommentWithButton(RANGE_COMMENT)
                val rangePersisted = awaitValue("native inclusive range amid existing notes", 30.seconds) {
                    reviewNotes(port, managedSessionId).takeIf { HunkReviewAssert.containsNote(it, RANGE_COMMENT) }
                }
                ui.waitForSubmission(TestGoProject.CHANGED_LINE, endLine = TestGoProject.UNCHANGED_HUNK_LINE)
                HunkReviewAssert.assertThat(rangePersisted).hasReviewerRange(
                    RANGE_COMMENT, TestGoProject.CHANGED_LINE, TestGoProject.UNCHANGED_HUNK_LINE,
                )
                ui.closeThread(editor)
                ui.clearSelection(editor)
                editor.goToPosition(TestGoProject.UNCHANGED_HUNK_LINE, 2)
                ui.openThreadWithAutomaticManagedReview(editor)
                ui.submitCommentWithButton(AFTER_NAVIGATION_COMMENT)
                val allReviewerComments = awaitValue(
                    "reviewer comment after line navigation",
                    30.seconds,
                ) {
                    reviewNotes(port, managedSessionId).takeIf {
                        HunkReviewAssert.containsNote(it, REVIEW_COMMENT) &&
                            HunkReviewAssert.containsNote(it, WRAPPED_MARKDOWN_COMMENT) &&
                            HunkReviewAssert.containsNote(it, AFTER_NAVIGATION_COMMENT)
                    }
                }
                ui.waitForSubmission(TestGoProject.UNCHANGED_HUNK_LINE)
                HunkReviewAssert.assertThat(allReviewerComments)
                    .hasReviewerComment(REVIEW_COMMENT, "user", TestGoProject.CHANGED_LINE)
                    .hasReviewerComment(
                        WRAPPED_MARKDOWN_COMMENT,
                        "user",
                        TestGoProject.CHANGED_LINE,
                    )
                    .hasReviewerComment(
                        AFTER_NAVIGATION_COMMENT,
                        "user",
                        TestGoProject.UNCHANGED_HUNK_LINE,
                    )
                val cliAfterReviewerComments = liveSessionReview(
                    executable,
                    managedSessionId,
                    port,
                    configHome,
                )
                assertLiveSessionCounts(
                    cliAfterReviewerComments,
                    expectedLiveComments = 0,
                    expectedReviewNotes = 4,
                )
                HunkReviewAssert.assertThat(cliAfterReviewerComments)
                    .hasReviewerRange(RANGE_COMMENT, TestGoProject.CHANGED_LINE, TestGoProject.UNCHANGED_HUNK_LINE)
                    .hasReviewerComment(REVIEW_COMMENT, "user", TestGoProject.CHANGED_LINE)
                    .hasReviewerComment(
                        WRAPPED_MARKDOWN_COMMENT,
                        "user",
                        TestGoProject.CHANGED_LINE,
                    )
                    .hasReviewerComment(
                        AFTER_NAVIGATION_COMMENT,
                        "user",
                        TestGoProject.UNCHANGED_HUNK_LINE,
                    )

                val externalResult = post(
                    port,
                    JsonObject().apply {
                        addProperty("action", "comment-add")
                        add("selector", sessionSelector(managedSessionId))
                        addProperty("filePath", TestGoProject.MAIN_FILE)
                        addProperty("side", "new")
                        addProperty("line", TestGoProject.UNCHANGED_HUNK_LINE)
                        addProperty("summary", AGENT_NOTE)
                        addProperty("author", "external-agent")
                        addProperty("reveal", false)
                    },
                )
                assertThat(externalResult.has("result")).isTrue()
                awaitCondition("external upstream agent note in GoLand", 20.seconds) {
                    frame.xx { byAccessibleName("Agent response body") }
                        .list()
                        .filter { it.component.isShowing() }
                        .any { it.htmlInIde().contains(AGENT_NOTE) }
                }

                val replyParentId = HunkReviewAssert.noteId(cliAfterReviewerComments, REVIEW_COMMENT)
                post(port, JsonObject().apply {
                    addProperty("action", "comment-add")
                    add("selector", sessionSelector(managedSessionId))
                    addProperty("replyTo", replyParentId)
                    addProperty("summary", AGENT_REPLY)
                    addProperty("author", "reply-agent")
                    addProperty("reveal", false)
                })
                ui.closeThread(editor)
                editor.goToPosition(TestGoProject.CHANGED_LINE, 2)
                ui.openThread(editor)
                awaitCondition("native semantic reply relationship in GoLand", 20.seconds) {
                    frame.x { byAccessibleName("Reply to Hunk GoLand test") }
                        .let { it.present() && it.component.isShowing() }
                }
                awaitCondition("parent deletion restriction is explained inline") {
                    frame.x { byAccessibleName("Comment cannot be deleted while it has replies") }
                        .let { it.present() && it.component.isShowing() }
                }

                val allNotes = reviewNotes(port, managedSessionId)
                HunkReviewAssert.assertThat(allNotes)
                    .hasAgentReply(AGENT_REPLY, replyParentId, TestGoProject.CHANGED_LINE)
                    .hasReviewerComment(REVIEW_COMMENT, "user", TestGoProject.CHANGED_LINE)
                    .hasReviewerComment(
                        WRAPPED_MARKDOWN_COMMENT,
                        "user",
                        TestGoProject.CHANGED_LINE,
                    )
                    .hasReviewerComment(
                        AFTER_NAVIGATION_COMMENT,
                        "user",
                        TestGoProject.UNCHANGED_HUNK_LINE,
                    )
                    .hasAgentResponse(AGENT_NOTE, TestGoProject.UNCHANGED_HUNK_LINE)
                val cliBeforeDeletion = awaitValue(
                    "upstream CLI sees reviewer and agent session content",
                    20.seconds,
                ) {
                    liveSessionReview(executable, managedSessionId, port, configHome)
                        .takeIf { review -> liveSessionCounts(review) == (2 to 6) }
                }
                HunkReviewAssert.assertThat(cliBeforeDeletion)
                    .hasAgentReply(AGENT_REPLY, replyParentId, TestGoProject.CHANGED_LINE)
                    .hasReviewerComment(REVIEW_COMMENT, "user", TestGoProject.CHANGED_LINE)
                    .hasReviewerComment(
                        WRAPPED_MARKDOWN_COMMENT,
                        "user",
                        TestGoProject.CHANGED_LINE,
                    )
                    .hasReviewerComment(
                        AFTER_NAVIGATION_COMMENT,
                        "user",
                        TestGoProject.UNCHANGED_HUNK_LINE,
                    )
                    .hasAgentResponse(AGENT_NOTE, TestGoProject.UNCHANGED_HUNK_LINE)

                ui.closeThread(editor)
                editor.goToPosition(TestGoProject.UNCHANGED_HUNK_LINE, 2)
                ui.openThread(editor)
                frame.button { byAccessibleName("Delete review comment") }.doClickInIde()
                awaitCondition("inline delete confirmation") {
                    frame.button { byAccessibleName("Confirm delete review comment") }.present()
                }
                frame.button { byAccessibleName("Confirm delete review comment") }.doClickInIde()
                val afterDeletion = awaitValue("native upstream reviewer comment deletion", 30.seconds) {
                    reviewNotes(port, managedSessionId).takeIf {
                        !HunkReviewAssert.containsNote(it, AFTER_NAVIGATION_COMMENT)
                    }
                }
                HunkReviewAssert.assertThat(afterDeletion)
                    .hasReviewerComment(REVIEW_COMMENT, "user", TestGoProject.CHANGED_LINE)
                    .hasReviewerComment(
                        WRAPPED_MARKDOWN_COMMENT,
                        "user",
                        TestGoProject.CHANGED_LINE,
                    )
                    .doesNotHaveNote(AFTER_NAVIGATION_COMMENT)
                    .hasAgentResponse(AGENT_NOTE, TestGoProject.UNCHANGED_HUNK_LINE)
                val cliAfterDeletion = awaitValue(
                    "upstream CLI sees selective reviewer deletion",
                    20.seconds,
                ) {
                    liveSessionReview(executable, managedSessionId, port, configHome)
                        .takeIf { review -> liveSessionCounts(review) == (2 to 5) }
                }
                HunkReviewAssert.assertThat(cliAfterDeletion)
                    .hasAgentReply(AGENT_REPLY, replyParentId, TestGoProject.CHANGED_LINE)
                    .hasReviewerRange(RANGE_COMMENT, TestGoProject.CHANGED_LINE, TestGoProject.UNCHANGED_HUNK_LINE)
                    .hasReviewerComment(REVIEW_COMMENT, "user", TestGoProject.CHANGED_LINE)
                    .hasReviewerComment(
                        WRAPPED_MARKDOWN_COMMENT,
                        "user",
                        TestGoProject.CHANGED_LINE,
                    )
                    .doesNotHaveNote(AFTER_NAVIGATION_COMMENT)
                    .hasAgentResponse(AGENT_NOTE, TestGoProject.UNCHANGED_HUNK_LINE)
                Files.writeString(
                    output.resolve("real-daemon-e2e.json"),
                    JsonObject().apply {
                        addProperty("status", "passed")
                        addProperty("hunkVersion", hunkVersion(executable))
                        addProperty("hunkExecutable", executable.toString())
                        addProperty("port", port)
                        addProperty("externalSessionId", externalReview.sessionId)
                        addProperty("managedSessionId", managedSessionId)
                        add("requiredNativeActions", JsonArray().apply {
                            requiredActions.forEach(::add)
                        })
                        add("firstPersistedReviewerComment", JsonParser.parseString(firstPersisted))
                        add("sequentiallyPersistedReviewerComments", JsonParser.parseString(sequentiallyPersisted))
                        add("inclusiveRangeReviewerComment", JsonParser.parseString(rangePersisted))
                        add("allReviewerComments", JsonParser.parseString(allReviewerComments))
                        add("allNotesBeforeDeletion", JsonParser.parseString(allNotes))
                        add("allNotesAfterDeletion", JsonParser.parseString(afterDeletion))
                        add("cliAfterReviewerComments", JsonParser.parseString(cliAfterReviewerComments))
                        add("cliBeforeDeletion", JsonParser.parseString(cliBeforeDeletion))
                        add("cliAfterDeletion", JsonParser.parseString(cliAfterDeletion))
                    }.toString(),
                    StandardCharsets.UTF_8,
                )
            }
        } finally {
            stopTree(externalReview?.process)
            stopDaemon(daemon)
        }
    }

    /** Start an independently owned real TUI review before GoLand creates its off-screen peer. */
    private fun startReview(
        executable: Path,
        project: Path,
        port: Int,
        configHome: Path,
        output: Path,
    ): RunningReview {
        // PtyCommandLine needs a live IntelliJ Application. In the host test JVM it silently
        // falls back to ordinary pipes, which stock v0.23 correctly rejects for --watch.
        val process = PtyProcessBuilder(arrayOf(executable.toString(), "diff", "--watch"))
            .setConsole(false)
            .setInitialColumns(160)
            .setInitialRows(50)
            .setDirectory(project.toString())
            .setRedirectErrorStream(true)
            .setEnvironment(System.getenv() + mapOf(
                "HUNK_MCP_HOST" to "127.0.0.1", "HUNK_MCP_PORT" to port.toString(),
                "XDG_CONFIG_HOME" to configHome.toString(), "XDG_RUNTIME_DIR" to configHome.toString(),
                "TERM" to "xterm-256color",
            ))
            .start()
        Thread.ofVirtual().name("real-hunk-external-review-output").start {
            process.inputStream.use { input ->
                input.transferTo(java.io.OutputStream.nullOutputStream())
            }
        }

        return try {
            val sessionId = awaitValue("independent upstream TUI registration", 20.seconds) {
                check(process.isAlive) { "Independent Hunk review exited with status ${process.exitValue()} (terminal content omitted)" }
                sessions(port)
                    .singleOrNull { session ->
                        session.textProperty("repoRoot") == project.toString() &&
                            session.longProperty("pid") == process.pid()
                    }
                    ?.textProperty("sessionId")
            }
            RunningReview(sessionId, process)
        } catch (error: Exception) {
            stopTree(process)
            throw error
        }
    }

    private fun startDaemon(executable: Path, output: Path, configHome: Path): RunningDaemon {
        var lastFailure: Exception? = null
        repeat(3) { attempt ->
            val port = reservePort()
            transports[port] = HunkSessionTransport(configHome.resolve("hunk-mcp/security-v1"))
            val log = output.resolve("real-daemon-${attempt + 1}.log")
            val process = ProcessBuilder(executable.toString(), "daemon", "serve")
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .apply {
                    environment()["HUNK_MCP_HOST"] = "127.0.0.1"
                    environment()["HUNK_MCP_PORT"] = port.toString()
                    environment()["XDG_CONFIG_HOME"] = configHome.toString()
                    environment()["XDG_RUNTIME_DIR"] = configHome.toString()
                }
                .start()
            try {
                awaitCondition("real Hunk daemon startup on port $port", 10.seconds) {
                    check(process.isAlive) {
                        "Hunk daemon exited during startup: ${readLog(log)}"
                    }
                    runCatching { capabilities(port) }.isSuccess
                }
                val keepAlive = Executors.newSingleThreadScheduledExecutor { task ->
                    Thread.ofPlatform()
                        .daemon()
                        .name("real-hunk-daemon-keepalive-$port")
                        .unstarted(task)
                }
                keepAlive.scheduleAtFixedRate(
                    { runCatching { capabilities(port) } },
                    DAEMON_KEEPALIVE_SECONDS,
                    DAEMON_KEEPALIVE_SECONDS,
                    TimeUnit.SECONDS,
                )
                return RunningDaemon(port, process, keepAlive)
            } catch (error: Exception) {
                lastFailure = error
                stopTree(process)
            }
        }
        throw IllegalStateException("Could not start upstream Hunk after three attempts", lastFailure)
    }

    private fun capabilities(port: Int): JsonObject = get(port, "/session-api/capabilities")

    private fun reviewNotes(port: Int, sessionId: String): String =
        post(
            port,
            JsonObject().apply {
                addProperty("action", "review")
                add("selector", sessionSelector(sessionId))
                addProperty("includeNotes", true)
            },
        ).toString()

    private fun sessions(port: Int): List<JsonObject> =
        post(port, JsonObject().apply { addProperty("action", "list") })
            .getAsJsonArray("sessions")
            ?.mapNotNull { value -> value.takeIf { it.isJsonObject }?.asJsonObject }
            .orEmpty()

    private fun get(port: Int, path: String): JsonObject {
        return checked(transports.getValue(port).get(URI.create("http://127.0.0.1:$port$path"), Duration.ofSeconds(3)))
    }

    private fun post(port: Int, body: JsonObject): JsonObject {
        return checked(transports.getValue(port).post(URI.create("http://127.0.0.1:$port/session-api"), body, Duration.ofSeconds(15)))
    }

    private fun checked(response: HunkSessionTransport.Response): JsonObject {
        check(response.statusCode() in 200..299) {
            "Hunk returned HTTP ${response.statusCode()} (response body omitted)"
        }
        return JsonParser.parseString(response.body()).asJsonObject
    }

    private fun hunkVersion(executable: Path): String {
        val process = ProcessBuilder(executable.toString(), "--version")
            .redirectErrorStream(true)
            .start()
        val version = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
        check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "Could not read Hunk version from $executable"
        }
        return version
    }

    /** Inspect the same live review through the actual upstream user-facing CLI. */
    private fun liveSessionReview(
        executable: Path,
        sessionId: String,
        port: Int,
        configHome: Path,
    ): String {
        val process = ProcessBuilder(
            executable.toString(),
            "session",
            "review",
            sessionId,
            "--include-notes",
            "--json",
        )
            .redirectErrorStream(true)
            .apply {
                environment()["HUNK_MCP_HOST"] = "127.0.0.1"
                environment()["HUNK_MCP_PORT"] = port.toString()
                environment()["XDG_CONFIG_HOME"] = configHome.toString()
                environment()["XDG_RUNTIME_DIR"] = configHome.toString()
            }
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "Could not inspect live Hunk session $sessionId through the upstream CLI: $output"
        }
        return output.trim()
    }

    private fun assertLiveSessionCounts(
        review: String,
        expectedLiveComments: Int,
        expectedReviewNotes: Int,
    ) {
        assertThat(liveSessionCounts(review))
            .describedAs("upstream CLI live-comment and exported-review-note counts")
            .isEqualTo(expectedLiveComments to expectedReviewNotes)
    }

    private fun liveSessionCounts(review: String): Pair<Int, Int> {
        val value = JsonParser.parseString(review).asJsonObject.getAsJsonObject("review")
        return value.get("liveCommentCount").asInt to value.get("reviewNoteCount").asInt
    }

    private fun stopTree(process: Process?) {
        if (process == null) return
        val descendants = ProcessHandle.of(process.pid()).orElse(null)
            ?.descendants()?.use { it.toList().asReversed() }.orEmpty()
        descendants.forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            descendants.forEach { it.destroyForcibly() }
            process.destroyForcibly()
            check(process.waitFor(3, TimeUnit.SECONDS)) {
                "Process ${process.pid()} did not terminate after being destroyed forcibly"
            }
        }
    }

    private fun stopDaemon(daemon: RunningDaemon?) {
        daemon?.keepAlive?.shutdownNow()
        stopTree(daemon?.process)
        daemon?.let { transports.remove(it.port)?.close() }
    }

    private fun reservePort(): Int = ServerSocket(0).use { it.localPort }

    private fun resolveExecutable(value: String): Path {
        val configured = Path.of(value)
        val candidates = if (configured.parent != null) {
            sequenceOf(configured)
        } else {
            System.getenv("PATH").orEmpty().split(File.pathSeparator).asSequence()
                .filter(String::isNotBlank)
                .map { directory -> Path.of(directory).resolve(configured) }
        }
        return candidates
            .map { it.toAbsolutePath().normalize() }
            .firstOrNull(Files::isExecutable)
            ?: throw IllegalArgumentException("Could not resolve executable Hunk binary: $value")
    }

    private fun sessionSelector(sessionId: String): JsonObject = JsonObject().apply {
        addProperty("sessionId", sessionId)
    }

    private fun readLog(log: Path): String =
        runCatching { Files.readString(log).takeLast(4_000) }.getOrElse { "log unavailable: ${it.message}" }

    private data class RunningDaemon(
        val port: Int,
        val process: Process,
        val keepAlive: ScheduledExecutorService,
    )

    private data class RunningReview(val sessionId: String, val process: Process)

    private companion object {
        const val DAEMON_KEEPALIVE_SECONDS = 15L
        val transports = java.util.concurrent.ConcurrentHashMap<Int, HunkSessionTransport>()
        const val REVIEW_COMMENT = "Persist this reviewer comment in the live Hunk session."
        const val RANGE_COMMENT = "Review this complete import block, including its contextual closing line."
        const val WRAPPED_MARKDOWN_COMMENT =
            "**Keep this sequential reviewer comment intact even when the off-screen terminal " +
                "wraps a deliberately long Markdown paragraph across multiple rendered rows; " +
                "the daemon-confirmed body must remain exact and preserve reviewer provenance.**\n\n" +
                "- first verification point\n" +
                "- second verification point"
        const val AFTER_NAVIGATION_COMMENT =
            "A third reviewer comment still works after navigating to another hunk line."
        const val AGENT_NOTE = "External agent response arrived through the real Hunk daemon."
        const val AGENT_REPLY = "A semantic agent reply retains the original reviewer parent."
        const val RECOVERY_REVIEW_COMMENT =
            "Restore this reviewer comment after GoLand replaces its Hunk process."
        const val RECOVERY_AGENT_NOTE =
            "Keep this external agent response as local recovery history."
    }
}

private fun JsonObject.stringArray(name: String): List<String> =
    get(name)
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { value ->
            value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }
        ?: emptyList()

private fun JsonObject.textProperty(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.longProperty(name: String): Long? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
