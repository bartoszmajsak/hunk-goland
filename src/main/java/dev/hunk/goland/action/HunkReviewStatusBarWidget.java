package dev.hunk.goland.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;

import java.util.stream.Collectors;

/** Compact review summary that opens the persistent Hunk Review navigator. */
final class HunkReviewStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {
    static final String ID = "HunkReviewComments";

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
        return event -> HunkReviewToolWindowFactory.show(project);
    }

    @Override
    public void dispose() {
        statusBar = null;
    }

}
