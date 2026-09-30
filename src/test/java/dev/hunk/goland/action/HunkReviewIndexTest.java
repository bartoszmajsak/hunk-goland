package dev.hunk.goland.action;

import dev.hunk.goland.model.HunkNoteSource;
import dev.hunk.goland.model.HunkReviewNote;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.ReviewSide;
import dev.hunk.goland.model.ReviewTarget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HunkReviewIndexTest {
    private static final Path ROOT = Path.of("/workspace/repository");

    @Test
    void groupsItemsIntoSortedFileAndLineThreads() {
        var context = target("cmd/main.go", 4);
        var index = HunkReviewIndex.from(context, List.of(
                note("cmd/main.go", "agent", 4, ReviewSide.NEW, HunkNoteSource.AGENT),
                note("cmd/added.go", "review", 5, ReviewSide.NEW),
                note("cmd/main.go", "review", 4, ReviewSide.NEW),
                note("cmd/main.go", "later", 9, ReviewSide.NEW),
                note("cmd/old.go", "old", 3, ReviewSide.OLD)
        ));

        assertThat(index.files()).extracting(HunkReviewIndex.CommentedFile::filePath)
                .containsExactly("cmd/main.go", "cmd/added.go");
        assertThat(index.itemCount()).isEqualTo(4);
        assertThat(index.files().getFirst().threadCount()).isEqualTo(2);
        assertThat(index.threads().getFirst().itemCount()).isEqualTo(2);
        assertThat(index.threads().getFirst().kind()).isEqualTo(HunkGutterSummary.Kind.MIXED);
        assertThat(index.threads().get(1).kind()).isEqualTo(HunkGutterSummary.Kind.REVIEW);
        assertThat(index.threads().get(2).kind()).isEqualTo(HunkGutterSummary.Kind.REVIEW);

        assertThat(index.threads()).extracting(HunkReviewIndex.ThreadLocation::displayName)
                .containsExactly("cmd/main.go:4", "cmd/main.go:9", "cmd/added.go:5");
    }

    @Test
    void movesByThreadAndWrapsAcrossFiles() {
        var context = target("cmd/main.go", 4);
        var index = HunkReviewIndex.from(context, List.of(
                note("cmd/added.go", "first", 5, ReviewSide.NEW),
                note("cmd/main.go", "second", 4, ReviewSide.NEW)
        ));

        assertThat(index.adjacentTo(context, HunkReviewIndex.Direction.NEXT).displayName())
                .isEqualTo("cmd/added.go:5");
        assertThat(index.adjacentTo(target("cmd/added.go", 5), HunkReviewIndex.Direction.PREVIOUS)
                .displayName()).isEqualTo("cmd/main.go:4");
    }

    @Test
    void entersTheSequenceRelativeToAnUncommentedLine() {
        var context = target("cmd/main.go", 6);
        var index = HunkReviewIndex.from(context, List.of(
                note("cmd/main.go", "before", 4, ReviewSide.NEW),
                note("cmd/main.go", "after", 9, ReviewSide.NEW),
                note("cmd/added.go", "other-file", 5, ReviewSide.NEW)
        ));

        assertThat(index.adjacentTo(context, HunkReviewIndex.Direction.NEXT).displayName())
                .isEqualTo("cmd/main.go:9");
        assertThat(index.adjacentTo(context, HunkReviewIndex.Direction.PREVIOUS).displayName())
                .isEqualTo("cmd/main.go:4");
    }

    @Test
    void identifiesOnlyCommentedFilesBelowTheIndexedRepository() {
        var index = HunkReviewIndex.from(target("cmd/main.go", 4), List.of(
                note("cmd/main.go", "review", 4, ReviewSide.NEW)
        ));

        assertThat(HunkFilePathIconProvider.isCommentedFile(
                index,
                ROOT.resolve("cmd/main.go").toString()
        )).isTrue();
        assertThat(HunkFilePathIconProvider.isCommentedFile(
                index,
                ROOT.resolve("cmd/other.go").toString()
        )).isFalse();
        assertThat(HunkFilePathIconProvider.isCommentedFile(
                index,
                ROOT.resolveSibling("another-repository/cmd/main.go").toString()
        )).isFalse();
        assertThat(HunkFilePathIconProvider.isCommentedFile(index, "\0invalid")).isFalse();
    }

    private static ReviewTarget target(String filePath, int line) {
        return new ReviewTarget(ROOT, filePath, ReviewSide.NEW, line);
    }

    private static HunkReviewNote note(String filePath, String id, int line, ReviewSide side) {
        return note(filePath, id, line, side, HunkNoteSource.USER);
    }

    private static HunkReviewNote note(
            String filePath,
            String id,
            int line,
            ReviewSide side,
            HunkNoteSource source
    ) {
        return new HunkReviewNote(filePath, new HunkSessionNote(
                id,
                source,
                id,
                "reviewer",
                null,
                side,
                line,
                true
        ));
    }
}
