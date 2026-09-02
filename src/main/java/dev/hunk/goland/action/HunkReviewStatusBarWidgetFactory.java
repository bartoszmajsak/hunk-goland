package dev.hunk.goland.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

public final class HunkReviewStatusBarWidgetFactory implements StatusBarWidgetFactory {
    @Override
    public @NotNull String getId() {
        return HunkReviewStatusBarWidget.ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Hunk review comments";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return !project.isDefault();
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new HunkReviewStatusBarWidget(project);
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return statusBar.getProject() != null;
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }
}
