package dev.hunk.goland.model;

import java.nio.file.Path;
import java.util.Objects;

public record ReviewTarget(
        Path repositoryRoot,
        String filePath,
        ReviewSide side,
        int line,
        HunkReviewInput reviewInput
) {
    public ReviewTarget(Path repositoryRoot, String filePath, ReviewSide side, int line) {
        this(repositoryRoot, filePath, side, line, HunkReviewInput.workingTree());
    }

    public ReviewTarget {
        repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot").toAbsolutePath().normalize();
        filePath = Objects.requireNonNull(filePath, "filePath");
        side = Objects.requireNonNull(side, "side");
        reviewInput = Objects.requireNonNull(reviewInput, "reviewInput");
        if (filePath.isBlank() || Path.of(filePath).isAbsolute()) {
            throw new IllegalArgumentException("filePath must be repository-relative");
        }
        if (line < 1) {
            throw new IllegalArgumentException("line must be 1-based");
        }
    }

    public String displayName() {
        return filePath + ":" + line + " (" + side.wireName() + ")";
    }
}
