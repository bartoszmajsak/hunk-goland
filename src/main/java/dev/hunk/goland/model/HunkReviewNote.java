package dev.hunk.goland.model;

import java.nio.file.Path;
import java.util.Objects;

/** A Hunk note together with the repository-relative file that owns it. */
public record HunkReviewNote(String filePath, HunkSessionNote note) {
    public HunkReviewNote {
        filePath = Objects.requireNonNull(filePath, "filePath");
        note = Objects.requireNonNull(note, "note");
        if (filePath.isBlank() || Path.of(filePath).isAbsolute()) {
            throw new IllegalArgumentException("filePath must be repository-relative");
        }
    }
}
