package dev.hunk.goland

import com.intellij.driver.client.Remote
import com.intellij.driver.model.LockSemantics
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.GutterUiComponent
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.accessibleTree
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.elements.popupMenu
import com.intellij.driver.sdk.ui.components.elements.textField
import org.assertj.core.api.Assertions.assertThat
import java.awt.Point
import kotlin.time.Duration.Companion.seconds

@Remote("java.lang.Class")
private interface AwtClassRef {
    fun getName(): String
}

@Remote("java.awt.Component")
private interface AwtComponentRef {
    fun getParent(): AwtComponentRef?
    fun getClass(): AwtClassRef
    fun dispatchEvent(event: AwtMouseEventRef)
}

@Remote("java.awt.Container")
private interface AwtContainerRef {
    fun getComponents(): Array<AwtComponentRef>
}

@Remote("java.awt.event.MouseEvent")
private interface AwtMouseEventRef

@Remote("com.intellij.openapi.editor.ex.util.EditorUtil")
private interface EditorUtilRef {
    fun logicalLineToYRange(editor: IdeEditorRef, logicalLine: Int): IntervalPairRef
}

@Remote("com.intellij.openapi.util.Pair")
private interface IntervalPairRef {
    fun getFirst(): EditorIntervalRef
}

@Remote("com.intellij.openapi.editor.impl.Interval")
private interface EditorIntervalRef {
    fun intervalStart(): Int
    fun intervalEnd(): Int
}

@Remote("com.intellij.openapi.actionSystem.impl.ActionButton")
private interface IdeActionButtonRef {
    fun click()
}

@Remote("javax.swing.JMenuItem")
private interface SwingMenuItemRef {
    fun doClick()
}

@Remote("javax.swing.JPopupMenu")
private interface SwingPopupMenuRef {
    fun setVisible(visible: Boolean)
}

@Remote("javax.swing.JScrollPane")
private interface SwingScrollPaneRef {
    fun getVerticalScrollBar(): SwingScrollBarRef
}

@Remote("javax.swing.JScrollBar")
private interface SwingScrollBarRef {
    fun getValue(): Int
    fun getMaximum(): Int
    fun getVisibleAmount(): Int
    fun setValue(value: Int)
}

@Remote("java.lang.Object")
private interface JavaObjectRef

@Remote("javax.swing.JTree")
private interface SwingTreeRef {
    fun getCellRenderer(): SwingTreeCellRendererRef
    fun getRowCount(): Int
    fun getPathForRow(row: Int): SwingTreePathRef
}

@Remote("javax.swing.tree.TreePath")
private interface SwingTreePathRef {
    fun getLastPathComponent(): JavaObjectRef
    override fun toString(): String
}

@Remote("javax.swing.tree.TreeCellRenderer")
private interface SwingTreeCellRendererRef {
    fun getTreeCellRendererComponent(
        tree: SwingTreeRef,
        value: JavaObjectRef,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): AwtComponentRef
}

@Remote("com.intellij.ui.SimpleColoredComponent")
private interface SimpleColoredComponentRef {
    fun getIcon(): SwingIconRef?
}

@Remote("javax.swing.Icon")
private interface SwingIconRef

@Remote("com.intellij.ui.icons.IconWithToolTip")
private interface IconWithToolTipRef {
    fun getToolTip(composite: Boolean): String?
}

@Remote("com.intellij.openapi.editor.impl.EditorComponentImpl")
private interface IdeEditorComponentRef {
    fun getEditor(): IdeEditorRef
}

@Remote("com.intellij.openapi.editor.impl.EditorImpl")
private interface IdeEditorRef {
    fun getDocument(): IdeDocumentRef
    fun getCaretModel(): IdeCaretModelRef
}

@Remote("com.intellij.openapi.editor.impl.DocumentImpl")
private interface IdeDocumentRef {
    fun getLineCount(): Int
    fun getLineStartOffset(line: Int): Int
}

@Remote("com.intellij.openapi.editor.impl.CaretModelImpl")
private interface IdeCaretModelRef {
    fun getOffset(): Int
    fun moveToOffset(offset: Int)
}

@Remote("git4idea.repo.GitRepository", plugin = "Git4Idea/intellij.vcs.git.backend")
private interface GitRepositoryRef

@Remote("git4idea.repo.GitRepositoryManager", plugin = "Git4Idea/intellij.vcs.git.backend")
private interface GitRepositoryManagerRef {
    fun getRepositories(): List<GitRepositoryRef>
}

@Remote("git4idea.branch.GitBrancher", plugin = "Git4Idea/intellij.vcs.git.backend")
private interface GitBrancherRef {
    fun showDiff(left: String, right: String, repositories: List<GitRepositoryRef>)
}

/** Drives the line-review interface while hiding JetBrains component lookup and waiting details. */
internal class HunkUiDriver(private val frame: UiComponent) {
    fun openBranchComparison(left: String, right: String) {
        frame.driver.withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
            val project = singleProject()
            val repositories = service(GitRepositoryManagerRef::class, project).getRepositories()
            check(repositories.isNotEmpty()) { "GoLand did not discover the fixture Git repository" }
            service(GitBrancherRef::class, project).showDiff(left, right, repositories)
        }
        frame.robot.waitForIdle()
    }

    /** Drives the repository-level Compare with Branch action that opens a Current version diff. */
    fun openCompareWithBranch(branch: String, repositoryName: String) {
        frame.driver.invokeAction("ActivateProjectToolWindow", now = false)
        val projectTree = frame.accessibleTree { byClass("MyProjectViewTree") }
        projectTree.selectRows { rows ->
            rows.filter { row -> row.contains(repositoryName) }.take(1)
        }
        awaitCondition("project root selection for Compare with Branch") {
            projectTree.collectSelectedPaths().any { path ->
                path.path.lastOrNull()?.contains(repositoryName) == true
            }
        }
        frame.driver.invokeAction(
            "Git.CompareWithBranch",
            now = false,
            component = projectTree.component,
        )
        val chooser = frame.popup(
            "//div[@class='HeavyWeightWindow' and @accessiblename='Select Branch to Compare']",
        )
        awaitCondition("Compare with Branch chooser") {
            chooser.present() && chooser.component.isShowing()
        }
        val search = chooser.textField { byAccessibleName("Search") }
        search.text = branch
        val branches = chooser.accessibleTree { byAccessibleName("Branches Tree") }
        awaitCondition("branch $branch in Compare with Branch chooser") {
            branches.collectExpandedPathsAsStrings().any { path -> path.endsWith(branch) }
        }
        branches.selectRows { rows -> rows.filter { row -> row.contains(branch) } }
        awaitCondition("selected branch $branch in Compare with Branch chooser") {
            branches.collectSelectedPaths().any { path -> path.path.lastOrNull() == branch }
        }
        branches.setFocus()
        branches.pressEnterInIde()
        frame.robot.waitForIdle()
    }

    fun changesTreeContaining(fileName: String): JTreeUiComponent = awaitValue(
        label = "changes tree containing $fileName",
    ) {
        frame.xx(JTreeUiComponent::class.java) { byClass("AsyncChangesBrowserTreeList") }
            .list()
            .firstOrNull { tree ->
                tree.collectExpandedPathsAsStrings().any { path -> path.endsWith(fileName) }
            }
    }

    fun hasHunkFileBadge(tree: JTreeUiComponent, fileName: String): Boolean =
        frame.driver.withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
            val swingTree = cast(tree.component, SwingTreeRef::class)
            (0 until swingTree.getRowCount()).any { row ->
                val path = swingTree.getPathForRow(row)
                if (!path.toString().contains(fileName)) {
                    false
                } else {
                    val renderer = swingTree.getCellRenderer().getTreeCellRendererComponent(
                        swingTree,
                        path.getLastPathComponent(),
                        false,
                        false,
                        true,
                        row,
                        false,
                    )
                    val textRenderer = cast(renderer, AwtContainerRef::class)
                        .getComponents()
                        .firstOrNull { component ->
                            component.getClass().getName().endsWith("ChangesBrowserNodeRenderer")
                        }
                    val icon = textRenderer
                        ?.let { component -> cast(component, SimpleColoredComponentRef::class) }
                        ?.getIcon()
                    icon != null && cast(icon, IconWithToolTipRef::class)
                        .getToolTip(false)
                        ?.contains("Hunk review comments") == true
                }
            }
        }

    fun assertThreadNavigationControls() {
        val previous = awaitValue("previous-thread control") {
            frame.button { byAccessibleName("Previous Hunk comment thread") }
                .takeIf { it.present() && it.component.isShowing() }
        }
        val next = awaitValue("next-thread control") {
            frame.button { byAccessibleName("Next Hunk comment thread") }
                .takeIf { it.present() && it.component.isShowing() }
        }
        assertThat(previous.text).contains("Previous", "Ctrl")
        assertThat(next.text).contains("Next", "Ctrl")
    }

    fun openThread(editor: JEditorUiComponent, requireExistingNotes: Boolean = true) {
        frame.driver.invokeAction(ACTION_ID, now = false, component = editor.component)
        awaitCondition("line review composer") {
            val composer = frame.textField { byAccessibleName("Hunk review comment") }
            composer.present() && composer.component.isShowing()
        }
        if (requireExistingNotes) {
            awaitCondition("review comment card") {
                frame.x { byAccessibleName("Comment body") }.present()
            }
            awaitCondition("agent note card") {
                frame.xx { byAccessibleName("Agent response body") }
                    .list()
                    .any { it.component.isShowing() }
            }
        }
        awaitCondition("line review composer fully visible") {
            val submit = frame.button { byAccessibleName("Add comment") }
            if (!submit.present()) {
                false
            } else {
                val editorLocation = editor.component.getLocationOnScreen()
                val submitLocation = submit.component.getLocationOnScreen()
                submitLocation.y >= editorLocation.y &&
                    submitLocation.y + submit.component.height <=
                    editorLocation.y + editor.component.height
            }
        }
        frame.robot.waitForIdle()
    }

    fun openThreadWithAutomaticManagedReview(editor: JEditorUiComponent) {
        frame.driver.invokeAction(ACTION_ID, now = false, component = editor.component)
        awaitCondition("automatic IDE-managed review") {
            frame.button { byAccessibleName("Add comment") }
                .let { it.present() && it.component.isShowing() }
        }
        frame.robot.waitForIdle()
    }

    fun closeThread(editor: JEditorUiComponent) {
        frame.driver.invokeAction(ACTION_ID, now = false, component = editor.component)
        awaitCondition("inline line review to close") {
            frame.textField { byAccessibleName("Hunk review comment") }.notPresent()
        }
    }

    fun submitCommentWithShortcut(comment: String) {
        val composer = composerWith(comment)
        frame.setFocus()
        awaitCondition("comment composer focus") {
            composer.setFocus()
            composer.isFocusOwner()
        }
        composer.pressControlEnterInIde()
    }

    fun submitCommentWithButton(comment: String) {
        composerWith(comment)
        frame.button { byAccessibleName("Add comment") }.doClickInIde()
    }

    fun expandCommentEditor(): Pair<Int, Int> {
        val composer = frame.textField { byAccessibleName("Hunk review comment") }
        val compactHeight = composer.component.height
        frame.button { byAccessibleName("Expand comment editor") }.doClickInIde()
        val expandedHeight = awaitValue("expanded comment editor") {
            composer.component.height.takeIf { height -> height > compactHeight }
        }
        return compactHeight to expandedHeight
    }

    fun compactCommentEditor() {
        frame.button { byAccessibleName("Use compact comment editor") }.doClickInIde()
        awaitCondition("compact comment editor control") {
            frame.button { byAccessibleName("Expand comment editor") }.present()
        }
    }

    fun expandThreadMessages(): Pair<Int, Int> {
        val messages = frame.x { byAccessibleName("Hunk thread messages") }
        val compactHeight = messages.component.height
        frame.button { byAccessibleName("Expand thread messages") }.doClickInIde()
        val expandedHeight = awaitValue("expanded thread messages") {
            messages.component.height.takeIf { height -> height > compactHeight }
        }
        return compactHeight to expandedHeight
    }

    fun compactThreadMessages() {
        frame.button { byAccessibleName("Use compact thread messages") }.doClickInIde()
        awaitCondition("compact thread messages control") {
            frame.button { byAccessibleName("Expand thread messages") }.present()
        }
    }

    fun awaitCommentAuthor(author: String) {
        awaitCondition("comment author '$author'") {
            frame.x { byAccessibleName("Comment author: $author") }
                .let { it.present() && it.component.isShowing() }
        }
    }

    fun messageBodyY(accessibleName: String, bodyText: String): Int = awaitValue(
        label = "$accessibleName containing '$bodyText'",
    ) {
        frame.xx { byAccessibleName(accessibleName) }
            .list()
            .filter { it.component.isShowing() }
            .firstOrNull { it.htmlInIde().contains(bodyText) }
            ?.component
            ?.getLocationOnScreen()
            ?.y
    }

    fun scrollMessagesToBottom(): Int = frame.driver.withContext(
        OnDispatcher.EDT,
        LockSemantics.NO_LOCK,
    ) {
        val pane = frame.x { byAccessibleName("Hunk thread messages") }
        val bar = cast(pane.component, SwingScrollPaneRef::class).getVerticalScrollBar()
        bar.setValue(bar.getMaximum())
        bar.getValue()
    }

    fun messageScrollValue(): Int = frame.driver.withContext(
        OnDispatcher.EDT,
        LockSemantics.NO_LOCK,
    ) {
        val pane = frame.x { byAccessibleName("Hunk thread messages") }
        cast(pane.component, SwingScrollPaneRef::class).getVerticalScrollBar().getValue()
    }

    fun openReviewNavigator(
        expectedStatus: String,
        expectedSummary: String,
    ): JTreeUiComponent {
        awaitValue("Hunk commented-file status indicator") {
            frame.x { byVisibleText(expectedStatus) }
                .takeIf { it.present() && it.component.isShowing() }
        }
        frame.driver.invokeAction(SHOW_COMMENTED_FILES_ACTION_ID, now = false)
        val tree = awaitValue("Hunk review navigator") {
            frame.xx(JTreeUiComponent::class.java) {
                byAccessibleName("Hunk review threads")
            }.list().firstOrNull { it.component.isShowing() }
        }
        awaitCondition("Hunk review navigator summary") {
            frame.x { byVisibleText(expectedSummary) }
                .let { it.present() && it.component.isShowing() }
        }
        return tree
    }

    fun openThreadFromReviewNavigator(
        tree: JTreeUiComponent,
        filePath: String,
        line: Int,
    ) {
        val row = awaitValue("Hunk navigator row for $filePath:$line") {
            tree.collectExpandedPaths()
                .firstOrNull { path ->
                    path.path.any { it.startsWith(filePath) } &&
                        path.path.lastOrNull()?.startsWith("Line $line") == true
                }
                ?.row
        }
        tree.clickRow(row)
        awaitCondition("Hunk navigator row selected") {
            val selected = tree.collectSelectedPaths()
                .singleOrNull()
                ?.path
                .orEmpty()
            selected.any { it.startsWith(filePath) } &&
                selected.lastOrNull()?.startsWith("Line $line") == true
        }
        clickActionButton("Open Thread")
        awaitCondition("inline thread opened from Hunk navigator") {
            frame.x { byVisibleText("$filePath:$line (new)") }
                .let { it.present() && it.component.isShowing() }
        }
    }

    fun navigateToNextThread(editor: JEditorUiComponent) {
        frame.driver.invokeAction(NEXT_THREAD_ACTION_ID, now = false, component = editor.component)
        awaitCondition("next Hunk thread") {
            frame.x { byVisibleText("${TestGoProject.ADDED_FILE}:4 (new)") }
                .let { it.present() && it.component.isShowing() }
        }
    }

    fun navigateToPreviousThread(editor: JEditorUiComponent) {
        frame.driver.invokeAction(PREVIOUS_THREAD_ACTION_ID, now = false, component = editor.component)
        awaitCondition("previous Hunk thread") {
            frame.x { byVisibleText("${TestGoProject.MAIN_FILE}:4 (new)") }
                .let { it.present() && it.component.isShowing() }
        }
    }

    fun waitForSubmission(
        line: Int,
        side: String = "new",
        filePath: String = TestGoProject.MAIN_FILE,
    ) {
        val message = "Comment submitted on $filePath:$line ($side)."
        awaitCondition("$side-side submission confirmation") {
            val composer = frame.textField { byAccessibleName("Hunk review comment") }
            frame.x { byVisibleText(message) }.present() && composer.text.isEmpty()
        }
        frame.robot.waitForIdle()
    }

    fun waitForDiffEditors(): Pair<JEditorUiComponent, JEditorUiComponent> = awaitValue(
        label = "visible two-sided diff",
        timeout = 30.seconds,
    ) {
        visibleDiffEditors()
            .sortedBy { it.component.getLocationOnScreen().x }
            .takeIf { it.size == 2 }
            ?.let { it.first() to it.last() }
    }

    fun waitForDiffEditorContaining(text: String): JEditorUiComponent = awaitValue(
        label = "visible diff editor containing '$text'",
        timeout = 30.seconds,
    ) {
        visibleDiffEditors().firstOrNull { editor -> editor.text.contains(text) }
    }

    /** Moves the caret on the IDE EDT without depending on compositor focus or robot clicks. */
    fun moveCaretToLine(editor: JEditorUiComponent, oneBasedLine: Int) {
        val expectedOffset = frame.driver.withContext<Int>(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
            val ideEditor = cast(editor.component, IdeEditorComponentRef::class).getEditor()
            val document = ideEditor.getDocument()
            require(oneBasedLine in 1..document.getLineCount()) {
                "Line $oneBasedLine is outside the ${document.getLineCount()}-line editor"
            }
            document.getLineStartOffset(oneBasedLine - 1).also { offset ->
                ideEditor.getCaretModel().moveToOffset(offset)
            }
        }
        awaitCondition("caret at unified line $oneBasedLine") {
            frame.driver.withContext<Int>(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
                cast(editor.component, IdeEditorComponentRef::class)
                    .getEditor()
                    .getCaretModel()
                    .getOffset()
            } == expectedOffset
        }
    }

    fun openThreadFromDiffToolbar() {
        clickDiffToolbarAction()
        awaitCondition("line review composer opened from diff toolbar") {
            frame.textField { byAccessibleName("Hunk review comment") }.present()
        }
        frame.robot.waitForIdle()
    }

    /** Exercise the real diff line-number popup and the data context it gives the action. */
    fun openThreadFromDiffGutterMenu(
        editor: JEditorUiComponent,
        expectedLine: Int,
        expectedFile: String = TestGoProject.MAIN_FILE,
    ) {
        moveCaretToLine(editor, expectedLine)
        editor.scrollToCaret()
        editor.setFocus()
        awaitCondition("focused right-side diff editor") { editor.isFocusOwner() }
        val gutter = awaitValue("gutter beside the focused diff editor") {
            val editorLocation = editor.component.getLocationOnScreen()
            frame.xx(GutterUiComponent::class.java) { byClass("EditorGutterComponentImpl") }
                .list()
                .filter { candidate ->
                    candidate.component.isShowing() &&
                        candidate.component.getLocationOnScreen().x < editorLocation.x
                }
                .minByOrNull { candidate ->
                    editorLocation.x - candidate.component.getLocationOnScreen().x
                }
        }
        val gutterPoint = frame.driver.withContext<Point>(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
            val ideEditor = cast(editor.component, IdeEditorComponentRef::class).getEditor()
            val lineRange = utility(EditorUtilRef::class)
                .logicalLineToYRange(ideEditor, expectedLine - 1)
                .getFirst()
            val editorLocation = editor.component.getLocationOnScreen()
            val gutterLocation = gutter.component.getLocationOnScreen()
            val lineCenterY = (lineRange.intervalStart() + lineRange.intervalEnd()) / 2
            val localY = editorLocation.y + lineCenterY - gutterLocation.y
            require(localY in 0 until gutter.component.height) {
                "Line $expectedLine maps outside the visible gutter: y=$localY height=${gutter.component.height}"
            }
            Point((gutter.component.width - 8).coerceAtLeast(1), localY)
        }
        gutter.moveMouse(gutterPoint)
        frame.driver.withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
            val component = cast(gutter.component, AwtComponentRef::class)
            component.dispatchEvent(newMouseEvent(component, gutterPoint, 503, 0, 0, false))
            component.dispatchEvent(newMouseEvent(component, gutterPoint, 501, 4096, 3, true))
            component.dispatchEvent(newMouseEvent(component, gutterPoint, 502, 0, 3, true))
        }
        frame.robot.waitForIdle()
        // Select the real menu item created by the gutter's exact right-click event. Activating
        // it on the EDT avoids compositor-specific popup release handling after the menu exists.
        val menu = awaitValue("diff editor context menu") {
            frame.popupMenu()
                .takeIf { popup -> popup.present() && popup.itemsList().contains(ACTION_TEXT) }
        }
        val item = menu.findMenuItemByText(ACTION_TEXT)
        frame.driver.withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
            cast(item.component, SwingMenuItemRef::class).doClick()
            cast(menu.component, SwingPopupMenuRef::class).setVisible(false)
        }
        awaitCondition("line review opened from the real diff gutter menu") {
            frame.x { byAccessibleName("Hunk line review") }
                .let { thread -> thread.present() && thread.component.isShowing() }
        }
        awaitCondition("diff gutter popup targets the clicked line") {
            frame.x { byVisibleText("$expectedFile:$expectedLine (new)") }
                .let { location -> location.present() && location.component.isShowing() }
        }
        awaitCondition("line review composer opened from the real diff gutter menu") {
            frame.textField { byAccessibleName("Hunk review comment") }
                .let { composer -> composer.present() && composer.component.isShowing() }
        }
        frame.robot.waitForIdle()
    }

    private fun com.intellij.driver.client.Driver.newMouseEvent(
        component: AwtComponentRef,
        point: Point,
        id: Int,
        modifiers: Int,
        button: Int,
        popupTrigger: Boolean,
    ): AwtMouseEventRef = new(
        AwtMouseEventRef::class,
        component,
        id,
        System.currentTimeMillis(),
        modifiers,
        point.x,
        point.y,
        if (button == 0) 0 else 1,
        popupTrigger,
        button,
    )

    fun selectUnifiedDiffViewer() {
        val editorsBefore = visibleDiffEditors().map { editor -> editor.component }
        val unifiedToggle = awaitValue("Unified diff viewer action") {
            frame.xx { byClass("SegmentedButton") }
                .list()
                .filter { button -> button.component.isShowing() }
                .maxByOrNull { button -> button.component.getLocationOnScreen().x }
        }
        frame.driver.withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
            cast(unifiedToggle.component, IdeActionButtonRef::class).click()
        }
        awaitCondition("unified diff editor replacement") {
            val visibleEditors = visibleDiffEditors()
            visibleEditors.size == 1 && visibleEditors.none { editor ->
                editorsBefore.contains(editor.component)
            }
        }
        frame.robot.waitForIdle()
    }

    fun clickDiffToolbarAction() {
        clickActionButton(ACTION_TEXT, "Hunk diff toolbar action")
        frame.robot.waitForIdle()
    }

    fun clickActionButton(accessibleName: String, label: String = accessibleName) {
        val action = awaitValue(label) {
            frame.button { byAccessibleName(accessibleName) }
                .takeIf { it.present() && it.component.isShowing() }
        }
        frame.driver.withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
            cast(action.component, IdeActionButtonRef::class).click()
        }
    }

    private fun composerWith(comment: String) =
        frame.textField { byAccessibleName("Hunk review comment") }.also { composer ->
            composer.text = comment
            assertThat(composer.text).isEqualTo(comment)
        }

    private fun JEditorUiComponent.isInsideDiffViewer(): Boolean =
        driver.withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
            var current: AwtComponentRef? = cast(component, AwtComponentRef::class)
            while (current != null) {
                if (current.getClass().getName().startsWith("com.intellij.diff.")) {
                    return@withContext true
                }
                current = current.getParent()
            }
            false
        }

    private fun visibleDiffEditors(): List<JEditorUiComponent> =
        frame.xx(JEditorUiComponent::class.java) { byClass("EditorComponentImpl") }
            .list()
            .filter { it.component.isShowing() && it.isInsideDiffViewer() }

    private companion object {
        const val ACTION_ID = "dev.hunk.goland.AddComment"
        const val ACTION_TEXT = "Add Hunk Comment…"
        const val PREVIOUS_THREAD_ACTION_ID = "dev.hunk.goland.PreviousCommentThread"
        const val NEXT_THREAD_ACTION_ID = "dev.hunk.goland.NextCommentThread"
        const val SHOW_COMMENTED_FILES_ACTION_ID = "dev.hunk.goland.ShowCommentedFiles"
    }
}
