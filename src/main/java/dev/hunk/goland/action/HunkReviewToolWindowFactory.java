package dev.hunk.goland.action;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Persistent review-wide navigator backed by the same immutable index as editor markers. */
public final class HunkReviewToolWindowFactory implements ToolWindowFactory, DumbAware {
    private static final Logger LOG = Logger.getInstance(HunkReviewToolWindowFactory.class);
    static final String ID = "Hunk Review";

    @Override
    public void createToolWindowContent(
            @NotNull Project project,
            @NotNull ToolWindow toolWindow
    ) {
        var panel = new NavigatorPanel(project);
        var content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }

    static void show(Project project) {
        var toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID);
        if (toolWindow != null) {
            toolWindow.show();
        }
    }

    private static final class NavigatorPanel extends SimpleToolWindowPanel implements Disposable {
        private static final String OPEN_SELECTED = "hunk.review.open.selected";
        private static final Icon REVIEW_ICON = IconLoader.getIcon(
                "/icons/hunkReviewComment.svg",
                HunkReviewToolWindowFactory.class
        );
        private static final Icon AGENT_ICON = IconLoader.getIcon(
                "/icons/hunkAgentResponse.svg",
                HunkReviewToolWindowFactory.class
        );
        private static final Icon MIXED_ICON = IconLoader.getIcon(
                "/icons/hunkMixedThread.svg",
                HunkReviewToolWindowFactory.class
        );

        private final Project project;
        private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Hunk Review");
        private final DefaultTreeModel model = new DefaultTreeModel(root);
        private final Tree tree = new Tree(model);
        private final JBLabel summary = new JBLabel("", SwingConstants.RIGHT);
        private final Map<String, DefaultMutableTreeNode> threadNodes = new LinkedHashMap<>();
        private HunkReviewIndex index = HunkReviewIndex.empty();

        private NavigatorPanel(Project project) {
            super(true, true);
            this.project = project;
            configureTree();
            setToolbar(createToolbar());
            setContent(new JBScrollPane(tree));
            project.getService(HunkReviewGutterService.class).watchIndex(this, this::showIndex);
        }

        private void configureTree() {
            tree.setRootVisible(false);
            tree.setShowsRootHandles(true);
            tree.setCellRenderer(new NavigatorRenderer());
            tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
            tree.getEmptyText().setText("No comments in the active Hunk review");
            tree.getAccessibleContext().setAccessibleName("Hunk review threads");
            tree.setToolTipText("Double-click a line or press Enter to open its inline review thread");
            tree.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2) {
                        var clicked = tree.getPathForLocation(event.getX(), event.getY());
                        if (clicked == null) {
                            return;
                        }
                        if (!(clicked.getLastPathComponent() instanceof DefaultMutableTreeNode node)
                                || !(node.getUserObject() instanceof ThreadRow)) {
                            return;
                        }
                        tree.setSelectionPath(clicked);
                        openSelected();
                    }
                }
            });
            tree.getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), OPEN_SELECTED);
            tree.getActionMap().put(OPEN_SELECTED, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    openSelected();
                }
            });
        }

        private JComponent createToolbar() {
            var actions = new DefaultActionGroup();
            actions.add(new OpenThreadAction());
            actions.addSeparator();
            actions.add(new StepAction(HunkReviewIndex.Direction.PREVIOUS));
            actions.add(new StepAction(HunkReviewIndex.Direction.NEXT));
            actions.addSeparator();
            actions.add(new RefreshAction());

            var toolbar = ActionManager.getInstance().createActionToolbar(
                    "HunkReviewNavigator",
                    actions,
                    true
            );
            toolbar.setTargetComponent(this);
            var header = new javax.swing.JPanel(new BorderLayout());
            header.add(toolbar.getComponent(), BorderLayout.WEST);
            summary.setBorder(JBUI.Borders.emptyRight(8));
            summary.getAccessibleContext().setAccessibleName("Hunk review summary");
            header.add(summary, BorderLayout.EAST);
            return header;
        }

        private void showIndex(HunkReviewIndex updated) {
            var selected = selectedThread().map(HunkReviewIndex.ThreadLocation::displayName);
            index = updated;
            root.removeAllChildren();
            threadNodes.clear();
            updated.files().forEach(file -> {
                var fileNode = new DefaultMutableTreeNode(new FileRow(file));
                file.threads().forEach(thread -> {
                    var threadNode = new DefaultMutableTreeNode(new ThreadRow(thread));
                    fileNode.add(threadNode);
                    threadNodes.put(thread.displayName(), threadNode);
                });
                root.add(fileNode);
            });
            model.reload();
            threadNodes.values().stream()
                    .map(DefaultMutableTreeNode::getParent)
                    .distinct()
                    .map(DefaultMutableTreeNode.class::cast)
                    .forEach(node -> tree.expandPath(new TreePath(node.getPath())));
            selected.map(threadNodes::get).ifPresentOrElse(
                    this::select,
                    () -> index.threads().stream().findFirst()
                            .map(HunkReviewIndex.ThreadLocation::displayName)
                            .map(threadNodes::get)
                            .ifPresent(this::select)
            );
            updateSummary();
        }

        private void updateSummary() {
            var files = index.files().size();
            var threads = index.threads().size();
            var items = index.itemCount();
            if (files == 0) {
                summary.setText("No comments");
                summary.setToolTipText("The active Hunk review has no comments yet");
                return;
            }
            summary.setText(files + plural(files, " file", " files")
                    + " · " + threads + plural(threads, " thread", " threads")
                    + " · " + items + plural(items, " item", " items"));
            summary.setToolTipText(shortcutHint(
                    HunkThreadNavigationAction.PREVIOUS_ACTION_ID,
                    "Previous thread"
            ) + " · " + shortcutHint(
                    HunkThreadNavigationAction.NEXT_ACTION_ID,
                    "Next thread"
            ));
        }

        private static String shortcutHint(String actionId, String label) {
            var action = ActionManager.getInstance().getAction(actionId);
            if (action == null) {
                return label;
            }
            var shortcut = KeymapUtil.getFirstKeyboardShortcutText(action);
            return shortcut.isBlank() ? label : label + ": " + shortcut;
        }

        private Optional<HunkReviewIndex.ThreadLocation> selectedThread() {
            var selected = tree.getLastSelectedPathComponent();
            if (!(selected instanceof DefaultMutableTreeNode node)) {
                return Optional.empty();
            }
            return switch (node.getUserObject()) {
                case ThreadRow(var thread) -> Optional.of(thread);
                case FileRow(var file) -> file.threads().stream().findFirst();
                default -> Optional.empty();
            };
        }

        private void openSelected() {
            selectedThread().ifPresent(thread -> {
                LOG.info("event=review-navigator-open target=" + thread.displayName()
                        + " items=" + thread.itemCount());
                HunkThreadNavigator.open(project, index, thread, null);
            });
        }

        private void step(HunkReviewIndex.Direction direction) {
            var threads = index.threads();
            if (threads.isEmpty()) {
                return;
            }
            var selected = selectedThread().orElse(null);
            var current = selected == null ? -1 : threads.indexOf(selected);
            var offset = direction == HunkReviewIndex.Direction.NEXT ? 1 : -1;
            var destinationIndex = current < 0
                    ? (direction == HunkReviewIndex.Direction.NEXT ? 0 : threads.size() - 1)
                    : Math.floorMod(current + offset, threads.size());
            var destination = threads.get(destinationIndex);
            select(threadNodes.get(destination.displayName()));
            HunkThreadNavigator.open(project, index, destination, null);
        }

        private void select(DefaultMutableTreeNode node) {
            if (node == null) {
                return;
            }
            var path = new TreePath(node.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }

        private static String plural(int count, String singular, String plural) {
            return count == 1 ? singular : plural;
        }

        @Override
        public void dispose() {
        }

        private abstract class NavigatorAction extends DumbAwareAction {
            private NavigatorAction(String text, String description, Icon icon) {
                super(text, description, icon);
            }

            @Override
            public void update(@NotNull AnActionEvent event) {
                event.getPresentation().setEnabled(!index.threads().isEmpty());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        }

        private final class OpenThreadAction extends NavigatorAction {
            private OpenThreadAction() {
                super("Open Thread", "Open the selected inline Hunk review thread", AllIcons.Actions.EditSource);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                openSelected();
            }
        }

        private final class StepAction extends NavigatorAction {
            private final HunkReviewIndex.Direction direction;

            private StepAction(HunkReviewIndex.Direction direction) {
                super(
                        direction == HunkReviewIndex.Direction.PREVIOUS
                                ? "Previous Thread"
                                : "Next Thread",
                        "Open the " + direction.name().toLowerCase() + " Hunk review thread",
                        direction == HunkReviewIndex.Direction.PREVIOUS
                                ? AllIcons.Actions.Back
                                : AllIcons.Actions.Forward
                );
                this.direction = direction;
                var globalAction = ActionManager.getInstance().getAction(
                        direction == HunkReviewIndex.Direction.PREVIOUS
                                ? HunkThreadNavigationAction.PREVIOUS_ACTION_ID
                                : HunkThreadNavigationAction.NEXT_ACTION_ID
                );
                if (globalAction != null) {
                    copyShortcutFrom(globalAction);
                }
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                step(direction);
            }
        }

        private final class RefreshAction extends DumbAwareAction {
            private RefreshAction() {
                super("Refresh", "Refresh Hunk review comments", AllIcons.Actions.Refresh);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                project.getService(HunkReviewGutterService.class).refreshSoon();
            }
        }

        private static final class NavigatorRenderer extends ColoredTreeCellRenderer {
            @Override
            public void customizeCellRenderer(
                    @NotNull JTree tree,
                    Object value,
                    boolean selected,
                    boolean expanded,
                    boolean leaf,
                    int row,
                    boolean hasFocus
            ) {
                if (!(value instanceof DefaultMutableTreeNode node)) {
                    return;
                }
                switch (node.getUserObject()) {
                    case FileRow(var file) -> {
                        setIcon(AllIcons.General.Note);
                        append(file.filePath(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                        append("  " + file.threadCount()
                                + plural(file.threadCount(), " thread", " threads")
                                + " · " + file.itemCount()
                                + plural(file.itemCount(), " item", " items"),
                                SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    }
                    case ThreadRow(var thread) -> {
                        setIcon(switch (thread.kind()) {
                            case REVIEW -> REVIEW_ICON;
                            case AGENT -> AGENT_ICON;
                            case MIXED -> MIXED_ICON;
                        });
                        append("Line " + thread.line());
                        append("  " + thread.itemCount()
                                + plural(thread.itemCount(), " item", " items"),
                                SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    }
                    default -> {
                    }
                }
            }
        }

        private record FileRow(HunkReviewIndex.CommentedFile file) {
            @Override
            public String toString() {
                return file.filePath();
            }
        }

        private record ThreadRow(HunkReviewIndex.ThreadLocation thread) {
            @Override
            public String toString() {
                return "Line " + thread.line();
            }
        }
    }
}
