package dev.hunk.goland.action;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;

import javax.swing.JList;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.stream.Collectors;

/** Compact, review-wide file indicator with direct navigation into commented files. */
final class HunkReviewStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {
    static final String ID = "HunkReviewComments";
    private static JBPopup activeFileChooser;

    private final Project project;
    private HunkReviewIndex index = HunkReviewIndex.empty();
    private StatusBar statusBar;

    HunkReviewStatusBarWidget(Project project) {
        this.project = project;
        project.getService(HunkReviewGutterService.class).watchIndex(this, updated -> {
            index = updated;
            if (statusBar != null) {
                statusBar.updateWidget(ID);
            }
        });
    }

    @Override
    public @NotNull String ID() {
        return ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
    }

    @Override
    public WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public @NotNull String getText() {
        var fileCount = index.files().size();
        if (fileCount == 0) {
            return "Hunk: no comments";
        }
        return "Hunk: " + fileCount + (fileCount == 1 ? " file" : " files")
                + " · " + index.itemCount();
    }

    @Override
    public float getAlignment() {
        return 0.5f;
    }

    @Override
    public @NotNull String getTooltipText() {
        if (index.files().isEmpty()) {
            return "No right-side Hunk comment threads in the active review";
        }
        var files = index.files().stream()
                .map(HunkReviewIndex.CommentedFile::filePath)
                .collect(Collectors.joining(", "));
        return "Hunk comments in " + files + " — click to navigate";
    }

    @Override
    public Consumer<MouseEvent> getClickConsumer() {
        return event -> showFileChooser(event);
    }

    private void showFileChooser(MouseEvent event) {
        showFileChooser(project, event.getComponent());
    }

    static void showFileChooser(Project project, Component owner) {
        if (activeFileChooser != null && activeFileChooser.isVisible()) {
            activeFileChooser.cancel();
            activeFileChooser = null;
            return;
        }
        var snapshot = project.getService(HunkReviewGutterService.class).currentIndex();
        if (snapshot.files().isEmpty()) {
            return;
        }
        var popup = JBPopupFactory.getInstance()
                .createPopupChooserBuilder(snapshot.files())
                .setTitle("Hunk commented files")
                .setAccessibleName("Hunk commented files")
                .setRenderer(new CommentedFileRenderer())
                .setItemChosenCallback(file -> HunkThreadNavigator.open(
                        project,
                        snapshot,
                        file.threads().getFirst(),
                        null
                ))
                .setAdText("Ctrl+Alt+, previous thread · Ctrl+Alt+. next thread")
                .createPopup();
        activeFileChooser = popup;
        popup.setFinalRunnable(() -> {
            if (activeFileChooser == popup) {
                activeFileChooser = null;
            }
        });
        var anchor = owner;
        if (anchor == null) {
            var statusBar = WindowManager.getInstance().getStatusBar(project);
            anchor = statusBar == null ? null : statusBar.getComponent();
        }
        if (anchor != null) {
            popup.showUnderneathOf(anchor);
        } else {
            popup.showInFocusCenter();
        }
    }

    @Override
    public void dispose() {
        statusBar = null;
    }

    private static final class CommentedFileRenderer
            extends ColoredListCellRenderer<HunkReviewIndex.CommentedFile> {
        @Override
        protected void customizeCellRenderer(
                @NotNull JList<? extends HunkReviewIndex.CommentedFile> list,
                HunkReviewIndex.CommentedFile file,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            setIcon(AllIcons.General.Note);
            getAccessibleContext().setAccessibleName("Hunk commented file " + file.filePath());
            append(file.filePath());
            var threads = file.threadCount() + (file.threadCount() == 1 ? " thread" : " threads");
            var items = file.itemCount() + (file.itemCount() == 1 ? " item" : " items");
            append("  " + threads + " · " + items, SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
    }
}
