package dev.hunk.goland.model;

import java.nio.file.Path;

public final class PathMapping {
    private PathMapping() {
    }

    public static String repositoryRelative(Path repositoryRoot, Path file) {
        var root = repositoryRoot.toAbsolutePath().normalize();
        var normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(root) || normalizedFile.equals(root)) {
            throw new IllegalArgumentException("File is outside the repository root");
        }
        return root.relativize(normalizedFile).toString().replace('\\', '/');
    }
}
