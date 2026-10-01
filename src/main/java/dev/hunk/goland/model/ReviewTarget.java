package dev.hunk.goland.model;

import java.nio.file.Path;
import java.util.Objects;

public record ReviewTarget(
        Path repositoryRoot,
        String filePath,
        ReviewSide side,
        int line,
        int endLine,
        HunkReviewInput reviewInput
) {
    public ReviewTarget(Path repositoryRoot, String filePath, ReviewSide side, int line) {
        this(repositoryRoot, filePath, side, line, line, HunkReviewInput.workingTree());
    }

    public ReviewTarget(Path repositoryRoot, String filePath, ReviewSide side, int line,
                        HunkReviewInput reviewInput) {
        this(repositoryRoot, filePath, side, line, line, reviewInput);
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
        if (endLine < line) {
            throw new IllegalArgumentException("endLine must not precede line");
        }
    }

    public String displayName() {
        return filePath + ":" + line + (endLine == line ? "" : "-" + endLine)
                + " (" + side.wireName() + ")";
    }
}
