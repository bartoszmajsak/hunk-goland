package dev.hunk.goland.action;

import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.ListSelection;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import dev.hunk.goland.model.HunkReviewInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewTargetResolverTest {
    private static final String BEFORE = "dac364c3c88f475cb33f4e462d3d46e7e3dfde0f";
    private static final String AFTER = "fe8b9146a8fdda46b229bd41046ae346a6ba4414";

    @Test
    void resolvesTheWholeRevisionRangeWhenTheSelectedFileIsNew() {
        var added = new Change(null, revision(AFTER));
        var modified = new Change(
                revision(BEFORE),
                revision(AFTER)
        );

        var input = ReviewTargetResolver.reviewInput(
                added,
                ListSelection.createAt(new ArrayList<>(List.of(added, modified)), 0)
        );

        assertThat(input)
                .isEqualTo(new HunkReviewInput.RevisionRange(BEFORE, AFTER));
        assertThat(input.command())
                .containsExactly("hunk", "diff", BEFORE + ".." + AFTER, "--watch");
    }

    @Test
    void comparesTheDiffBaseWithCurrentLocalContent() {
        var localChange = new Change(
                revision(BEFORE),
                new TestContentRevision(VcsRevisionNumber.NULL)
        );

        var input = ReviewTargetResolver.reviewInput(
                localChange,
                ListSelection.createSingleton(localChange)
        );

        assertThat(input).isEqualTo(new HunkReviewInput.RevisionToWorkingTree(BEFORE));
        assertThat(input.command()).containsExactly("hunk", "diff", BEFORE, "--watch");
    }

    @Test
    void resolvesARevisionComparisonFromTheActiveDiffRequest() {
        var request = new TestDiffRequest(
                content(BEFORE),
                content(AFTER)
        );

        var input = ReviewTargetResolver.reviewInput(null, null, request);

        assertThat(input)
                .isEqualTo(new HunkReviewInput.RevisionRange(BEFORE, AFTER));
        assertThat(input.command())
                .containsExactly("hunk", "diff", BEFORE + ".." + AFTER, "--watch");
    }

    @Test
    void resolvesBaseToWorkingTreeFromTheActiveDiffRequest() {
        var request = new TestDiffRequest(
                content(BEFORE),
                currentContent()
        );

        var input = ReviewTargetResolver.reviewInput(null, null, request);

        assertThat(input)
                .isEqualTo(new HunkReviewInput.RevisionToWorkingTree(BEFORE));
        assertThat(input.command())
                .containsExactly("hunk", "diff", BEFORE, "--watch");
    }

    @Test
    void usesTheClickedGutterLineInsteadOfTheCaretLine() {
        assertThat(ReviewTargetResolver.actionLine(3, 11)).isEqualTo(4);
        assertThat(ReviewTargetResolver.actionLine(null, 11)).isEqualTo(11);
        assertThat(ReviewTargetResolver.actionLine(-1, 11)).isEqualTo(11);
    }

    private static ContentRevision revision(String revision) {
        return new TestContentRevision(new TestRevisionNumber(revision));
    }

    private static DiffContent content(String revision) {
        var content = new TestDiffContent();
        content.putUserData(
                revisionInfoKey(),
                Pair.create((FilePath) null, new TestRevisionNumber(revision))
        );
        return content;
    }

    private static DiffContent currentContent() {
        var content = new TestDiffContent();
        content.putUserData(
                revisionInfoKey(),
                Pair.create((FilePath) null, VcsRevisionNumber.NULL)
        );
        return content;
    }

    @SuppressWarnings("unchecked")
    private static Key<Pair<FilePath, VcsRevisionNumber>> revisionInfoKey() {
        try {
            return (Key<Pair<FilePath, VcsRevisionNumber>>) Class
                    .forName("com.intellij.diff.DiffVcsDataKeys")
                    .getField("REVISION_INFO")
                    .get(null);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("GoLand must expose VCS diff revision metadata", error);
        }
    }

    private record TestContentRevision(VcsRevisionNumber revision) implements ContentRevision {
        @Override
        public String getContent() {
            return "";
        }

        @Override
        public com.intellij.openapi.vcs.FilePath getFile() {
            return null;
        }

        @Override
        public VcsRevisionNumber getRevisionNumber() {
            return revision;
        }
    }

    private record TestRevisionNumber(String value) implements VcsRevisionNumber {
        @Override
        public String asString() {
            return value;
        }

        @Override
        public int compareTo(VcsRevisionNumber other) {
            return value.compareTo(other.asString());
        }
    }

    private static final class TestDiffContent extends UserDataHolderBase implements DiffContent {
        @Override
        public FileType getContentType() {
            return null;
        }
    }

    private static final class TestDiffRequest extends ContentDiffRequest {
        private final List<DiffContent> contents;

        private TestDiffRequest(DiffContent before, DiffContent after) {
            contents = List.of(before, after);
        }

        @Override
        public String getTitle() {
            return "Compare with Branch";
        }

        @Override
        public List<DiffContent> getContents() {
            return contents;
        }

        @Override
        public List<String> getContentTitles() {
            return List.of("Before", "After");
        }
    }
}
