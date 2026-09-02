package dev.hunk.goland.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HunkReviewInputTest {
    @Test
    void displaysAnAbbreviatedRangeButLaunchesTheExactRevisions() {
        var input = new HunkReviewInput.RevisionRange(
                "dac364c3c88f475cb33f4e462d3d46e7e3dfde0f",
                "fe8b9146a8fdda46b229bd41046ae346a6ba4414"
        );

        assertThat(input.commandDisplay())
                .isEqualTo("hunk diff dac364c3c8..fe8b9146a8 --watch");
        assertThat(input.command().get(2))
                .isEqualTo("dac364c3c88f475cb33f4e462d3d46e7e3dfde0f"
                        + "..fe8b9146a8fdda46b229bd41046ae346a6ba4414");
    }

    @Test
    void rejectsOptionLikeRevisionValues() {
        assertThatThrownBy(() -> new HunkReviewInput.RevisionRange("main", "--output=/tmp/review"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("afterRevision must be a concrete revision");
    }

    @Test
    void comparesOneConcreteRevisionWithTheCurrentWorkingTree() {
        var revision = "dac364c3c88f475cb33f4e462d3d46e7e3dfde0f";
        var input = new HunkReviewInput.RevisionToWorkingTree(revision);

        assertThat(input.command())
                .containsExactly("hunk", "diff", revision, "--watch");
        assertThat(input.commandDisplay())
                .isEqualTo("hunk diff dac364c3c8 --watch");
    }
}
