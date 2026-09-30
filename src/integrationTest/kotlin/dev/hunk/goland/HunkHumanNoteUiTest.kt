package dev.hunk.goland

import com.intellij.driver.sdk.closeAndDisableAllBalloonNotifications
import com.intellij.driver.sdk.invokeAction
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

class HunkReviewCommentUiTest {
    @Test
    fun `reviews editor and right diff side in a real GoLand instance`(@TempDir temporaryDirectory: Path) {
        val ux = UxReport.configured()
        val project = TestGoProject.create(temporaryDirectory)
        val fakeHunk = installFakeHunk(temporaryDirectory)
        var status = "failed"

        try {
            FakeHunkDaemon.start(TEST_AUTHOR).use { daemon ->
                daemon.connectExternalReview(project)
                val pluginPath = Path.of(System.getProperty("path.to.build.plugin"))
                val context = Starter.newContext(
                    "hunk-review-comment-editor-${System.nanoTime()}",
                    goLandTestCase(project),
                ).apply {
                    preserveSystemDir = true
                    PluginConfigurator(this).installPluginFromPath(pluginPath)
                    applyVMOptionsPatch {
                        addSystemProperty("hunk.session.port", daemon.port)
                        addSystemProperty("hunk.diagnostics", "true")
                        withEnv(
                            "PATH",
                            "${fakeHunk.binDirectory}${File.pathSeparator}${System.getenv("PATH")}",
                        )
                        withEnv("HUNK_TEST_EVIDENCE", fakeHunk.evidence.toString())
                        withEnv("HUNK_FAKE_DRIVER", fakeHunk.driver.toString())
                        System.getProperty("hunk.uiToolkit")
                            ?.takeIf(String::isNotBlank)
                            ?.let { addSystemProperty("awt.toolkit.name", it) }
                    }
                    disableGotItTooltips()
                }
                context.runIdeWithDriver().useDriverAndCloseIde {
                    waitForIndicators(5.minutes)
                    openFile(TestGoProject.MAIN_FILE)

                    val frame = ideFrame()
                    val ui = HunkUiDriver(frame)
                    frame.setFocus()
                    frame.driver.closeAndDisableAllBalloonNotifications()

                    val editor = frame.codeEditorForFile("main.go")
                    editor.goToPosition(TestGoProject.CHANGED_LINE, 2)
                    ui.openThreadWithAutomaticManagedReview(editor)
                    awaitCondition("automatic Hunk gutter discovery") {
                        daemon.reviewReadCount > 0
                    }
                    awaitCondition("review-wide commented-file index") {
                        frame.x { byVisibleText("Hunk: 2 files · 4") }
                            .let { it.present() && it.component.isShowing() }
                    }
                    awaitCondition("managed reviewer composer") {
                        frame.button { byAccessibleName("Add comment") }
                            .let { it.present() && it.component.isShowing() }
                    }
                    ui.closeThread(editor)
                    frame.robot.waitForIdle()
                    ux.capture(
                        frame,
                        "01-editor-line",
                        "Review markers in the code editor",
                        "A mixed blue-and-purple gutter glyph marks a line with both reviewer and agent comments before its thread is opened.",
                    )

                    editor.goToPosition(TestGoProject.CHANGED_LINE, 2)
                    ui.openThread(editor)
                    ui.assertThreadNavigationControls()
                    val earlyAgentY = ui.messageBodyY(
                        "Agent response body",
                        "earlier observation",
                    )
                    val reviewerY = ui.messageBodyY(
                        "Comment body",
                        "cancellation semantics",
                    )
                    val latestAgentY = ui.messageBodyY(
                        "Agent response body",
                        "cancellation",
                    )
                    assertThat(earlyAgentY).isLessThan(reviewerY)
                    assertThat(reviewerY).isLessThan(latestAgentY)
                    ux.capture(
                        frame,
                        "02-editor-thread",
                        "Modern line-attached review thread",
                        "Natural-height cards give comments and agent responses distinct titles, provenance colors, author metadata, comfortable spacing, accessible disclosure controls, and keymap-aware previous/next navigation. Messages are chronological, with the newest response last.",
                    )

                    val (compactMessagesHeight, expandedMessagesHeight) = ui.expandThreadMessages()
                    assertThat(expandedMessagesHeight).isGreaterThan(compactMessagesHeight)
                    ux.capture(
                        frame,
                        "02-expanded-messages",
                        "Expandable review conversation",
                        "The message area expands independently when a longer review conversation needs more reading room, while individual cards remain collapsible.",
                    )
                    ui.compactThreadMessages()

                    val (compactComposerHeight, expandedComposerHeight) = ui.expandCommentEditor()
                    assertThat(expandedComposerHeight).isGreaterThan(compactComposerHeight)
                    ux.capture(
                        frame,
                        "02-expanded-composer",
                        "Expandable Markdown comment editor",
                        "The compact composer expands in place for longer Markdown without opening a dialog or losing the line attachment.",
                    )
                    ui.compactCommentEditor()

                    val messageScroll = ui.scrollMessagesToBottom()
                    assertThat(messageScroll).isGreaterThan(0)
                    val readsBeforeScrollCheck = daemon.reviewReadCount
                    awaitCondition("two unchanged live-refresh cycles") {
                        daemon.reviewReadCount >= readsBeforeScrollCheck + 2
                    }
                    assertThat(ui.messageScrollValue()).isEqualTo(messageScroll)
                    ux.capture(
                        frame,
                        "02-stable-message-scroll",
                        "Live refresh preserves reading position",
                        "The thread stays at the reviewer-selected scroll position across unchanged Hunk refreshes instead of snapping back to the top.",
                    )

                    ui.closeThread(editor)
                    val navigator = ui.openReviewNavigator(
                        expectedStatus = "Hunk: 2 files · 4",
                        expectedSummary = "2 files · 2 threads · 4 items",
                    )
                    ux.capture(
                        frame,
                        "02-review-navigator",
                        "Review-wide comment navigator",
                        "A persistent Hunk Review pane groups comment threads by file and line, shows reviewer/agent glyphs and item counts, and keeps navigation at hand in its toolbar.",
                    )
                    ui.openThreadFromReviewNavigator(
                        navigator,
                        TestGoProject.MAIN_FILE,
                        TestGoProject.CHANGED_LINE,
                    )
                    ux.capture(
                        frame,
                        "02-pane-navigation",
                        "Open a thread from the review pane",
                        "Selecting a line entry and choosing Open Thread opens its inline conversation while the review index remains available at the side.",
                    )
                    val mainEditor = frame.codeEditorForFile("main.go")
                    ui.navigateToNextThread(mainEditor)
                    ux.capture(
                        frame,
                        "02-shortcut-navigation",
                        "Previous and next thread shortcuts",
                        "The existing review-order shortcuts remain available for a fast sequential pass across files and wrap at either end.",
                    )
                    val addedEditor = frame.codeEditorForFile("added.go")
                    ui.navigateToPreviousThread(addedEditor)

                    frame.button { byAccessibleName("Copy agent prompt") }.doClickInIde()
                    assertThat(frame.x { byVisibleText("Show in Hunk") }.notPresent()).isTrue()
                    awaitCondition("agent prompt clipboard confirmation") {
                        frame.x { byVisibleText("Copied agent prompt to clipboard.") }.present()
                    }
                    ux.capture(
                        frame,
                        "02-agent-prompt-copied",
                        "Agent handoff copied from the review thread",
                        "A visible per-thread action uses Hunk's own agent-skill prompt and confirms the clipboard handoff. Posting remains focused on adding the comment; the confusing terminal-navigation checkbox is gone.",
                    )

                    frame.button { byAccessibleName("Collapse comment from Ari (reviewer)") }.doClickInIde()
                    awaitCondition("collapsed comment card") {
                        frame.button { byAccessibleName("Expand comment from Ari (reviewer)") }.present()
                    }
                    ux.capture(
                    frame,
                    "03-collapsed-comment",
                    "Collapsible comment card",
                    "A saved comment folds down to its compact title and metadata while its delete action remains available in the header.",
                    )
                    frame.button { byAccessibleName("Expand comment from Ari (reviewer)") }.doClickInIde()
                    awaitCondition("expanded comment card") {
                        frame.button { byAccessibleName("Collapse comment from Ari (reviewer)") }.present()
                    }

                    frame.button { byAccessibleName("Delete review comment") }.doClickInIde()
                    awaitCondition("inline delete confirmation") {
                        frame.button { byAccessibleName("Confirm delete review comment") }.present()
                    }
                    ux.capture(
                    frame,
                    "04-delete-confirmation",
                    "Inline delete confirmation",
                    "A compact destructive action expands into an inline confirmation inside the card header, without opening a dialog.",
                    )
                    frame.button { byAccessibleName("Confirm delete review comment") }.doClickInIde()
                    val deleteRequest = daemon.takeReviewAction("reviewer comment deletion")
                    HunkReviewActionAssert.assertThat(deleteRequest)
                        .targetsSession("ui-session")
                        .removesComment("user:review-new")
                    ux.recordRequest(deleteRequest)
                    awaitCondition("deleted reviewer comment to disappear") {
                        frame.x { byVisibleText(INITIAL_REVIEW_COMMENT_TEXT) }.notPresent()
                    }
                    ux.capture(
                    frame,
                    "05-comment-deleted",
                    "Saved comment deleted",
                    "Hunk accepted the native remove intent and the line thread refreshed without the deleted comment.",
                    )

                    val reviewReadsBeforeExternal = daemon.publishExternalAgentResponse()
                    awaitCondition("external review refresh") {
                        daemon.reviewReadCount > reviewReadsBeforeExternal
                    }
                    awaitCondition("external agent response") {
                        frame.xx { byAccessibleName("Agent response body") }
                            .list()
                            .filter { it.component.isShowing() }
                            .any { it.htmlInIde().contains(FakeHunkDaemon.EXTERNAL_RESPONSE) }
                    }
                    ux.capture(
                    frame,
                    "06-external-sync",
                    "External agent response synced live",
                    "An agent response added by another client appears as a distinct modern card while the line thread remains open.",
                    )
                    ui.submitCommentWithShortcut(TEST_COMMENT)
                    val editorRequest = daemon.takeReviewAction("normal editor")
                    HunkReviewActionAssert.assertThat(editorRequest)
                        .targetsRepository(project)
                        .isFromIde(TEST_AUTHOR)
                        .addsComment(TestGoProject.CHANGED_LINE, TEST_COMMENT)
                    ux.recordRequest(editorRequest)
                    ui.waitForSubmission(TestGoProject.CHANGED_LINE)
                    ux.capture(
                    frame,
                    "07-editor-submitted",
                    "Ctrl+Enter submission confirmed in place",
                    "The real composer keyboard shortcut submits the comment and feedback stays beneath the reviewed line; no separate window is opened.",
                    )

                    ui.closeThread(editor)
                    editor.goToPosition(TestGoProject.UNCHANGED_HUNK_LINE, 2)
                    ui.openThread(editor, requireExistingNotes = false)
                    awaitCondition("empty clean-line thread") {
                        frame.x { byVisibleText("No comments on this line yet.") }.present()
                    }
                    ux.capture(
                    frame,
                    "08-empty-line-thread",
                    "Commenting on a line without an existing thread",
                    "An uncommented hunk line opens a focused empty state and the full composer is scrolled into view.",
                    )
                    ui.submitCommentWithButton(CLEAN_LINE_COMMENT)
                    val cleanLineRequest = daemon.takeReviewAction("uncommented editor line")
                    HunkReviewActionAssert.assertThat(cleanLineRequest)
                        .targetsRepository(project)
                        .isFromIde(TEST_AUTHOR)
                        .addsComment(TestGoProject.UNCHANGED_HUNK_LINE, CLEAN_LINE_COMMENT)
                    ux.recordRequest(cleanLineRequest)
                    awaitCondition("first clean-line comment to appear") {
                        frame.xx { byAccessibleName("Comment body") }
                            .list()
                            .filter { it.component.isShowing() }
                            .any { it.htmlInIde().contains("First comment on this line") }
                    }
                    ux.capture(
                    frame,
                    "09-first-line-comment",
                    "First comment added to a clean line",
                    "A multiline Markdown body is persisted by the fixture and immediately becomes the line's first review card.",
                    )

                    ui.closeThread(editor)
                    editor.goToPosition(TestGoProject.CHANGED_LINE, 2)
                    frame.driver.invokeAction(
                        COMPARE_WITH_BASE_ACTION,
                        now = false,
                        component = editor.component,
                    )
                    val reviewReadsBeforeDiff = daemon.reviewReadCount
                    val (_, newEditor) = ui.waitForDiffEditors()
                    awaitCondition("right-side diff gutter discovery") {
                        daemon.reviewReadCount > reviewReadsBeforeDiff
                    }
                    frame.robot.waitForIdle()
                    ux.capture(
                    frame,
                    "10-two-sided-diff",
                    "Right-side markers in a two-sided diff",
                    "Hunk comments are intentionally placed on the right/new side; the left baseline remains uncluttered.",
                    )

                    newEditor.goToPosition(TestGoProject.CHANGED_LINE, 2)
                    ui.openThreadFromDiffGutterMenu(newEditor, DIFF_GUTTER_POPUP_LINE)
                    ux.capture(
                        frame,
                        "11-diff-right-thread",
                        "Diff context-menu comment action",
                        "The real diff line-number menu opens the same line-attached composer on the right diff pane.",
                    )
                    ui.submitCommentWithButton(RIGHT_SIDE_COMMENT)
                    val newRequest = daemon.takeReviewAction("right diff pane")
                    HunkReviewActionAssert.assertThat(newRequest)
                        .targetsRepository(project)
                        .isFromIde(TEST_AUTHOR)
                        .addsComment(DIFF_GUTTER_POPUP_LINE, RIGHT_SIDE_COMMENT)
                    ux.recordRequest(newRequest)

                    ui.closeThread(newEditor)
                    ui.selectUnifiedDiffViewer()
                    val unifiedEditor = ui.waitForDiffEditorContaining("func main")
                    ui.moveCaretToLine(unifiedEditor, TestGoProject.UNIFIED_VISUAL_LINE)
                    ui.openThreadFromDiffToolbar()
                    ui.submitCommentWithButton(UNIFIED_COMMENT)
                    val unifiedRequest = daemon.takeReviewAction("unified diff line")
                    HunkReviewActionAssert.assertThat(unifiedRequest)
                        .targetsRepository(project)
                        .targetsFile(TestGoProject.MAIN_FILE)
                        .isFromIde(TEST_AUTHOR)
                        .addsComment(TestGoProject.UNIFIED_SOURCE_LINE, UNIFIED_COMMENT)
                    ux.recordRequest(unifiedRequest)
                    ux.capture(
                        frame,
                        "12-unified-diff-thread",
                        "Unified-diff line mapping",
                        "The thread is attached to visual line ${TestGoProject.UNIFIED_VISUAL_LINE} while the submitted Hunk target is the corresponding current-side source line ${TestGoProject.UNIFIED_SOURCE_LINE}.",
                    )

                    ui.closeThread(unifiedEditor)
                    daemon.disconnectReview()
                    val previousWorkingTreeLaunch = Files.getLastModifiedTime(fakeHunk.evidence)
                    ui.clickDiffToolbarAction()
                    val launchEvidence = awaitValue("working-tree Hunk command launch") {
                        fakeHunk.evidence
                            .takeIf { evidence ->
                                Files.isRegularFile(evidence) &&
                                    Files.getLastModifiedTime(evidence) > previousWorkingTreeLaunch
                            }
                            ?.let(Files::readAllLines)
                    }
                    assertThat(launchEvidence).containsExactly(
                        "cwd=$project",
                        "arg=diff",
                        "arg=--watch",
                    )
                    awaitCondition("automatic missing-session recovery") {
                        frame.button { byAccessibleName("Add comment") }
                            .let { it.present() && it.component.isShowing() }
                    }
                    ux.capture(
                        frame,
                        "13-missing-session",
                        "Automatic missing-review recovery",
                        "Adding a comment starts the matching off-screen Hunk review automatically and reveals the composer without a setup click.",
                    )

                    ui.closeThread(unifiedEditor)
                    daemon.disconnectReview()
                    ui.openBranchComparison(
                        TestGoProject.BASELINE_BRANCH,
                        TestGoProject.FEATURE_BRANCH,
                    )
                    val changes = ui.changesTreeContaining("added.go")
                    changes.selectRows { rows -> rows.filter { row -> row.contains("added.go") } }
                    awaitCondition("added file selection") {
                        changes.collectSelectedPaths().any { path ->
                            path.path.lastOrNull()?.contains("added.go") == true
                        }
                    }
                    changes.setFocus()
                    ui.clickActionButton("Show Diff")
                    ui.waitForDiffEditorContaining("func greeting")
                    ui.selectUnifiedDiffViewer()
                    val branchEditor = ui.waitForDiffEditorContaining("func greeting")
                    branchEditor.goToPosition(TestGoProject.ADDED_FILE_LINE, 2)
                    // Invoke from the diff editor itself, as the real line-number popup does. Its
                    // local data context does not necessarily carry the changes-tree selection.
                    ui.openThreadWithAutomaticManagedReview(branchEditor)
                    val branchLaunchEvidence = awaitValue("revision-range Hunk command launch") {
                        fakeHunk.evidence
                            .takeIf(Files::isRegularFile)
                            ?.let(Files::readAllLines)
                            ?.takeIf { lines -> lines.size == 4 }
                    }
                    val baseline = TestGoProject.resolveRevision(project, TestGoProject.BASELINE_BRANCH)
                    val feature = TestGoProject.resolveRevision(project, TestGoProject.FEATURE_BRANCH)
                    assertThat(branchLaunchEvidence).containsExactly(
                        "cwd=$project",
                        "arg=diff",
                        "arg=$baseline..$feature",
                        "arg=--watch",
                    )
                    ux.capture(
                        frame,
                        "14-branch-comparison-recovery",
                        "Automatic matching review for a branch comparison",
                        "An added file inherits the comparison's complete revision pair and starts that exact Hunk range automatically instead of an empty working-tree review.",
                    )
                    ui.submitCommentWithButton(ADDED_FILE_COMMENT)
                    val addedFileRequest = daemon.takeReviewAction("added file in one-sided diff")
                    HunkReviewActionAssert.assertThat(addedFileRequest)
                        .targetsRepository(project)
                        .targetsFile(TestGoProject.ADDED_FILE)
                        .isFromIde(TEST_AUTHOR)
                        .addsComment(TestGoProject.ADDED_FILE_LINE, ADDED_FILE_COMMENT)
                    ux.recordRequest(addedFileRequest)
                    awaitCondition("Hunk badge on commented comparison file") {
                        ui.hasHunkFileBadge(changes, "added.go")
                    }
                    ux.capture(
                        frame,
                        "15-branch-comparison-ready",
                        "Added-file comment composer",
                        "The matching revision review is running, a comment submitted from the net-new file is persisted against cmd/added.go line 4, and its native Changes-tree file icon carries a Hunk review badge.",
                    )

                }
                val ideaLog = readIdeaLog(context.paths.testHome)
                assertThat(ideaLog)
                    .describedAs("opt-in body-free Hunk diagnostics in the Driver idea.log")
                    .contains(
                        "event=hunk-diagnostic-http-complete",
                        "event=hunk-diagnostic-session-scan",
                        "event=hunk-diagnostic-review-snapshot",
                        "event=hunk-diagnostic-persistence-poll",
                        "event=hunk-diagnostic-pty-stage",
                    )
                    .doesNotContain(
                        TEST_COMMENT,
                        CLEAN_LINE_COMMENT,
                        RIGHT_SIDE_COMMENT,
                        UNIFIED_COMMENT,
                        ADDED_FILE_COMMENT,
                        FakeHunkDaemon.EXTERNAL_RESPONSE,
                    )
            }
            status = "passed"
        } finally {
            ux.finish(status)
        }
    }

    private fun readIdeaLog(testHome: Path): String =
        Files.walk(testHome).use { paths ->
            paths.filter { path -> path.fileName.toString() == "idea.log" }
                .map(Files::readString)
                .reduce("", String::plus)
        }

    @Test
    fun `starts a base-to-current review from GoLand Compare with Branch`(
        @TempDir temporaryDirectory: Path,
    ) {
        val project = TestGoProject.create(temporaryDirectory)
        TestGoProject.checkoutFeature(project)
        val baseline = TestGoProject.resolveRevision(project, TestGoProject.BASELINE_BRANCH)
        val fakeHunk = installFakeHunk(temporaryDirectory)
        val ux = UxReport.configuredSubreport("cross-view")

        FakeHunkDaemon.start(TEST_AUTHOR).use { daemon ->
            Starter.newContext(
                "hunk-compare-with-current-${System.nanoTime()}",
                goLandTestCase(project),
            ).apply {
                PluginConfigurator(this).installPluginFromPath(
                    Path.of(System.getProperty("path.to.build.plugin")),
                )
                applyVMOptionsPatch {
                    addSystemProperty("hunk.session.port", daemon.port)
                    addSystemProperty("hunk.diagnostics", "true")
                    withEnv(
                        "PATH",
                        "${fakeHunk.binDirectory}${File.pathSeparator}${System.getenv("PATH")}",
                    )
                    withEnv("HUNK_TEST_EVIDENCE", fakeHunk.evidence.toString())
                    withEnv("HUNK_FAKE_DRIVER", fakeHunk.driver.toString())
                    System.getProperty("hunk.uiToolkit")
                        ?.takeIf(String::isNotBlank)
                        ?.let { addSystemProperty("awt.toolkit.name", it) }
                }
                disableGotItTooltips()
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val frame = ideFrame()
                val ui = HunkUiDriver(frame)
                frame.setFocus()
                frame.driver.closeAndDisableAllBalloonNotifications()

                ui.openCompareWithBranch(
                    TestGoProject.BASELINE_BRANCH,
                    project.fileName.toString(),
                )
                val changes = ui.changesTreeContaining("added.go")
                changes.selectRows { rows -> rows.filter { row -> row.contains("added.go") } }
                awaitCondition("added file selection in current-version comparison") {
                    changes.collectSelectedPaths().any { path ->
                        path.path.lastOrNull()?.contains("added.go") == true
                    }
                }
                changes.setFocus()
                ui.clickActionButton("Show Diff")
                ui.selectUnifiedDiffViewer()
                val editor = ui.waitForDiffEditorContaining("func greeting")
                editor.goToPosition(TestGoProject.ADDED_FILE_LINE, 2)
                ui.openThreadWithAutomaticManagedReview(editor)

                val launchEvidence = awaitValue("base-to-current Hunk command launch") {
                    fakeHunk.evidence
                        .takeIf(Files::isRegularFile)
                        ?.let(Files::readAllLines)
                        ?.takeIf { lines -> lines.size == 4 }
                }
                assertThat(launchEvidence).containsExactly(
                    "cwd=$project",
                    "arg=diff",
                    "arg=$baseline",
                    "arg=--watch",
                )
                assertThat(frame.button { byAccessibleName("Add comment") }.component.isShowing())
                    .isTrue()

                ui.submitCommentWithButton(BRANCH_DIFF_COMMENT)
                val diffRequest = daemon.takeReviewAction("Compare with Branch diff")
                HunkReviewActionAssert.assertThat(diffRequest)
                    .targetsRepository(project)
                    .targetsFile(TestGoProject.ADDED_FILE)
                    .addsComment(TestGoProject.ADDED_FILE_LINE, BRANCH_DIFF_COMMENT)
                ux.recordRequest(diffRequest)
                ui.waitForSubmission(
                    TestGoProject.ADDED_FILE_LINE,
                    filePath = TestGoProject.ADDED_FILE,
                )
                ux.capture(
                    frame,
                    "01-branch-diff-comment",
                    "Comment in Compare with Branch",
                    "The current/right diff pane saves a reviewer comment into the base-to-working-tree Hunk review.",
                )
                ui.closeThread(editor)

                openFile(TestGoProject.ADDED_FILE)
                val sourceEditor = frame.codeEditorForFile("added.go")
                sourceEditor.goToPosition(TestGoProject.ADDED_FILE_LINE, 2)
                ui.openThread(sourceEditor)
                awaitCondition("diff comment visible in the ordinary editor") {
                    frame.x { byVisibleText(BRANCH_DIFF_COMMENT) }
                        .let { it.present() && it.component.isShowing() }
                }
                ux.capture(
                    frame,
                    "02-shared-source-thread",
                    "The same review in the source editor",
                    "Opening the file normally keeps the active branch-review context and shows the comment created in the diff.",
                )

                ui.submitCommentWithShortcut(SOURCE_EDITOR_COMMENT)
                val sourceRequest = daemon.takeReviewAction("ordinary editor sharing branch review")
                HunkReviewActionAssert.assertThat(sourceRequest)
                    .targetsRepository(project)
                    .targetsFile(TestGoProject.ADDED_FILE)
                    .addsComment(TestGoProject.ADDED_FILE_LINE, SOURCE_EDITOR_COMMENT)
                ux.recordRequest(sourceRequest)
                ui.waitForSubmission(
                    TestGoProject.ADDED_FILE_LINE,
                    filePath = TestGoProject.ADDED_FILE,
                )

                assertThat(Files.readAllLines(fakeHunk.evidence)).containsExactlyElementsOf(
                    launchEvidence,
                )
            }
        }
        ux.finish("passed")
    }

    private companion object {
        const val DIFF_GUTTER_POPUP_LINE = 11
        const val COMPARE_WITH_BASE_ACTION = "Compare.SameVersion"
        const val TEST_AUTHOR = "GoLand reviewer"
        const val INITIAL_REVIEW_COMMENT_TEXT = "Keep the cancellation semantics explicit."
        const val TEST_COMMENT = "Please keep this cancellation path explicit."
        const val RIGHT_SIDE_COMMENT = "Please verify the new-side behavior here."
        const val UNIFIED_COMMENT = "Keep this unified-view line mapping exact."
        const val ADDED_FILE_COMMENT = "Please review this newly added greeting."
        const val BRANCH_DIFF_COMMENT = "This comment starts in Compare with Branch."
        const val SOURCE_EDITOR_COMMENT = "This source view shares the branch review."
        const val CLEAN_LINE_COMMENT = "First comment on this line.\n\n- Markdown stays multiline."

        fun installFakeHunk(directory: Path): FakeHunk {
            val binDirectory = Files.createDirectories(directory.resolve("fake-hunk-bin"))
            val evidence = directory.resolve("hunk-launch.txt")
            val driver = directory.resolve("fake-hunk-driver.py")
            Files.writeString(
                driver,
                """import json
                    |import os
                    |import sys
                    |import termios
                    |import tty
                    |import urllib.request
                    |
                    |port = os.environ["HUNK_MCP_PORT"]
                    |endpoint = f"http://127.0.0.1:{port}/session-api"
                    |
                    |def post(payload):
                    |    request = urllib.request.Request(
                    |        endpoint,
                    |        data=json.dumps(payload).encode("utf-8"),
                    |        headers={"Content-Type": "application/json"},
                    |        method="POST",
                    |    )
                    |    with urllib.request.urlopen(request, timeout=5) as response:
                    |        return json.loads(response.read().decode("utf-8"))
                    |
                    |post({"action": "test-register", "repoRoot": os.getcwd(), "pid": os.getpid()})
                    |old = termios.tcgetattr(0)
                    |tty.setraw(0)
                    |draft = None
                    |try:
                    |    while True:
                    |        value = os.read(0, 1)
                    |        if not value:
                    |            break
                    |        if draft is None:
                    |            if value == b"q":
                    |                break
                    |            if value == b"c":
                    |                draft = bytearray()
                    |                os.write(1, b"Draft note\n")
                    |        elif value == b"\x13":
                    |            body = bytes(draft)
                    |            body = body.replace(b"\x1b[200~", b"").replace(b"\x1b[201~", b"")
                    |            post({
                    |                "action": "test-add-user",
                    |                "body": body.decode("utf-8"),
                    |                "author": "GoLand reviewer",
                    |            })
                    |            os.write(1, b"Your note\n")
                    |            draft = None
                    |        else:
                    |            draft.extend(value)
                    |            os.write(1, value)
                    |finally:
                    |    termios.tcsetattr(0, termios.TCSADRAIN, old)
                    |""".trimMargin(),
            )
            val executable = binDirectory.resolve("hunk")
            Files.writeString(
                executable,
                """#!/bin/sh
                    |{
                    |  printf 'cwd=%s\n' "${'$'}PWD"
                    |  printf 'arg=%s\n' "${'$'}@"
                    |} > "${'$'}HUNK_TEST_EVIDENCE"
                    |exec python3 "${'$'}HUNK_FAKE_DRIVER"
                    |""".trimMargin(),
            )
            check(executable.toFile().setExecutable(true)) {
                "Could not make the fake Hunk executable runnable"
            }
            return FakeHunk(binDirectory, evidence, driver)
        }
    }

    private data class FakeHunk(val binDirectory: Path, val evidence: Path, val driver: Path)
}
