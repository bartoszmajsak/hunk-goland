package dev.hunk.goland.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewTargetTest {
    @Test
    void keepsLegacySingleLineTargetsAndFormatsInclusiveRanges() {
        var root = Path.of("/workspace/repository");
        assertThat(new ReviewTarget(root, "cmd/main.go", ReviewSide.NEW, 4).endLine()).isEqualTo(4);
        assertThat(new ReviewTarget(root, "cmd/main.go", ReviewSide.NEW, 4, 6,
                HunkReviewInput.workingTree()).displayName()).isEqualTo("cmd/main.go:4-6 (new)");
    }

    @Test
    void rejectsReversedRanges() {
        assertThatThrownBy(() -> new ReviewTarget(Path.of("/workspace/repository"), "cmd/main.go",
                ReviewSide.NEW, 6, 4, HunkReviewInput.workingTree()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endLine must not precede line");
    }
}
