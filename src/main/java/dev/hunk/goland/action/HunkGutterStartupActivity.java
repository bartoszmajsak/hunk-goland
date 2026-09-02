package dev.hunk.goland.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/** Starts non-blocking annotation discovery after the project UI is ready. */
public final class HunkGutterStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        project.getService(HunkReviewGutterService.class).start();
    }
}
