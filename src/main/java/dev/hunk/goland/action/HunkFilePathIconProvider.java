package dev.hunk.goland.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.FilePathIconProvider;
import com.intellij.ui.LayeredIcon;
import com.intellij.ui.icons.IconWithToolTip;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Adds a compact Hunk badge to commented files in native VCS change trees. */
public final class HunkFilePathIconProvider implements FilePathIconProvider {
    private static final Icon BADGE = IconLoader.getIcon(
            "/icons/hunkFileBadge.svg",
            HunkFilePathIconProvider.class
    );

    @Override
    public Icon getIcon(@NotNull FilePath filePath, boolean isDirectory, Project project) {
        if (project == null || isDirectory) {
            return null;
        }
        var index = project.getService(HunkReviewGutterService.class).currentIndex();
        if (!isCommentedFile(index, filePath.getPath())) {
            return null;
        }

        var fileIcon = filePath.getFileType().getIcon();
        if (fileIcon == null) {
            return new CommentedFileIcon(BADGE);
        }
        var icon = new LayeredIcon(2);
        icon.setIcon(fileIcon, 0);
        icon.setIcon(
                BADGE,
                1,
                Math.max(0, fileIcon.getIconWidth() - BADGE.getIconWidth()),
                Math.max(0, fileIcon.getIconHeight() - BADGE.getIconHeight())
        );
        return new CommentedFileIcon(icon);
    }

    static boolean isCommentedFile(HunkReviewIndex index, String path) {
        if (index.files().isEmpty()) {
            return false;
        }
        try {
            var root = index.repositoryRoot().toAbsolutePath().normalize();
            var candidate = Path.of(path).toAbsolutePath().normalize();
            if (!candidate.startsWith(root)) {
                return false;
            }
            var relative = root.relativize(candidate).toString().replace(File.separatorChar, '/');
            return index.files().stream().anyMatch(file -> file.filePath().equals(relative));
        } catch (InvalidPathException error) {
            return false;
        }
    }

    private record CommentedFileIcon(Icon delegate) implements IconWithToolTip {
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            delegate.paintIcon(component, graphics, x, y);
        }

        @Override
        public int getIconWidth() {
            return delegate.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return delegate.getIconHeight();
        }

        @Override
        public String getToolTip(boolean composite) {
            return "This file has Hunk review comments";
        }
    }
}
