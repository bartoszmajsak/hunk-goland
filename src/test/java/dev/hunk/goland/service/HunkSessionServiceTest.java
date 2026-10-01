package dev.hunk.goland.service;

import com.google.gson.JsonParser;
import dev.hunk.goland.model.HunkNoteSource;
import dev.hunk.goland.model.HunkReviewInput;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.ReviewSide;
import dev.hunk.goland.model.ReviewTarget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HunkSessionServiceTest {
    @Test
    void retainsNativeParentIdsAndRejectsSelfParenting() {
        var payload = JsonParser.parseString("""
                {"noteId":"reply","parentId":"root","source":"agent","body":"Done",
                 "newRange":[4,6]}
                """).getAsJsonObject();
        var reply = HunkSessionService.parseNote(payload).orElseThrow();
        assertThat(reply.parentId()).isEqualTo("root");
        assertThat(reply.asRecoveredHistory().parentId()).isEqualTo("root");
        assertThat(reply.withDisplayedCreationTime("2026-10-01T10:00:00Z").parentId()).isEqualTo("root");
        payload.addProperty("parentId", "reply");
        assertThat(HunkSessionService.parseNote(payload)).isEmpty();
    }

    @Test
    void retainsInclusiveUpstreamRangesAndRejectsMalformedEnds() {
        var note = JsonParser.parseString("""
                {"noteId":"range","source":"user","body":"Check this block",
                 "newRange":[4,6]}
                """).getAsJsonObject();
        assertThat(HunkSessionService.parseNote(note)).get().satisfies(parsed -> {
            assertThat(parsed.line()).isEqualTo(4);
            assertThat(parsed.endLine()).isEqualTo(6);
            assertThat(parsed.asRecoveredHistory().endLine()).isEqualTo(6);
            assertThat(parsed.withDisplayedCreationTime("2026-10-01T10:00:00Z").endLine()).isEqualTo(6);
        });
        note.add("newRange", JsonParser.parseString("[6,4]"));
        assertThat(HunkSessionService.parseNote(note)).isEmpty();
        note.add("newRange", JsonParser.parseString("[4]"));
        assertThat(HunkSessionService.parseNote(note)).isEmpty();
    }

    @Test
    void verifiesTheExactRangeRatherThanOnlyItsFirstLine() {
        var review = JsonParser.parseString("""
                {"reviewNotes":[
                  {"noteId":"single","filePath":"cmd/main.go","source":"user",
                   "body":"Check","newRange":[4,4]},
                  {"noteId":"other-range","filePath":"cmd/main.go","source":"user",
                   "body":"Check","newRange":[4,7]},
                  {"noteId":"reply","parentId":"parent","filePath":"cmd/main.go","source":"user",
                   "body":"Check","newRange":[4,6]},
                  {"noteId":"correct","filePath":"cmd/main.go","source":"user",
                   "body":"Check","newRange":[4,6]}
                ]}
                """).getAsJsonObject();
        var target = new ReviewTarget(Path.of("/workspace/repository"), "cmd/main.go",
                ReviewSide.NEW, 4, 6, HunkReviewInput.workingTree());
        assertThat(HunkSessionService.newReviewerNote(review, target, "Check", Set.of()))
                .get().extracting(note -> note.note().id()).isEqualTo("correct");
        assertThat(HunkSessionService.newReviewerNote(review, target, "Check", Set.of("correct")))
                .isEmpty();
    }

    @Test
    void requiresEverySelectedLineToBelongToOnePublishedHunk() throws HunkSessionException {
        var file = JsonParser.parseString("""
                {"hunks":[{"index":0,"newRange":[4,6]}, {"index":1,"newRange":[9,11]}]}
                """).getAsJsonObject();
        var root = Path.of("/workspace/repository");
        assertThat(HunkSessionService.hunkIndex(file, new ReviewTarget(root, "cmd/main.go",
                ReviewSide.NEW, 4, 6, HunkReviewInput.workingTree()))).isZero();
        assertThatThrownBy(() -> HunkSessionService.hunkIndex(file, new ReviewTarget(root,
                "cmd/main.go", ReviewSide.NEW, 4, 9, HunkReviewInput.workingTree())))
                .isInstanceOf(HunkSessionException.class)
                .hasMessageContaining("is not part of a hunk");
    }

    @Test
    void parsesReviewerProvenanceAndExactLine() {
        var payload = JsonParser.parseString("""
                {
                  "noteId": "user:1",
                  "source": "user",
                  "body": "Keep this review reviewer-driven.",
                  "author": "Bartek",
                  "side": "new",
                  "line": 12
                }
                """).getAsJsonObject();

        var note = HunkSessionService.parseNote(payload).orElseThrow();

        assertThat(note).usingRecursiveComparison().isEqualTo(new HunkSessionNote(
                "user:1",
                HunkNoteSource.USER,
                "Keep this review reviewer-driven.",
                "Bartek",
                null,
                ReviewSide.NEW,
                12,
                true
        ));
    }

    @Test
    void fallsBackToLegacyRangeAndAgentProvenance() {
        var payload = JsonParser.parseString("""
                {
                  "commentId": "mcp:1",
                  "summary": "Receiver response.",
                  "oldRange": [8, 8]
                }
                """).getAsJsonObject();

        var note = HunkSessionService.parseNote(payload).orElseThrow();

        assertThat(note).usingRecursiveComparison().isEqualTo(new HunkSessionNote(
                "mcp:1",
                HunkNoteSource.AGENT,
                "Receiver response.",
                null,
                null,
                ReviewSide.OLD,
                8,
                false
        ));
    }

    @Test
    void ignoresMalformedNotes() {
        var payload = JsonParser.parseString("""
                {"noteId": "user:broken", "source": "user", "body": "Missing a line."}
                """).getAsJsonObject();

        assertThat(HunkSessionService.parseNote(payload)).isEmpty();
    }

    @Test
    void keepsTheRepositoryRelativeFileIdentityForReviewWideNavigation() {
        var payload = JsonParser.parseString("""
                {
                  "noteId": "agent:other-file",
                  "source": "agent",
                  "filePath": "cmd/worker.go",
                  "body": "Checked the worker.",
                  "side": "new",
                  "line": 7
                }
                """).getAsJsonObject();

        var reviewNote = HunkSessionService.parseReviewNote(payload).orElseThrow();

        assertThat(reviewNote.filePath()).isEqualTo("cmd/worker.go");
        assertThat(reviewNote.note().id()).isEqualTo("agent:other-file");
    }

    @Test
    void projectsReviewNotesInHunksFileOrder() {
        var review = JsonParser.parseString("""
                {
                  "files": [
                    {"path": "cmd/main.go"},
                    {"path": "cmd/worker.go"}
                  ],
                  "reviewNotes": [
                    {"noteId": "worker", "filePath": "cmd/worker.go", "source": "agent",
                     "body": "worker", "side": "new", "line": 7},
                    {"noteId": "main", "filePath": "cmd/main.go", "source": "user",
                     "body": "main", "side": "new", "line": 4}
                  ]
                }
                """).getAsJsonObject();

        assertThat(HunkSessionService.reviewNotes(review))
                .extracting(note -> note.note().id())
                .containsExactly("main", "worker");
    }

    @Test
    void confirmsOnlyANewReviewerNoteAtTheExactTarget() {
        var review = JsonParser.parseString("""
                {
                  "reviewNotes": [
                    {"noteId": "user:old", "filePath": "cmd/main.go", "source": "user",
                     "body": "Repeatable body", "side": "new", "line": 4},
                    {"noteId": "agent:new", "filePath": "cmd/main.go", "source": "agent",
                     "body": "Repeatable body", "side": "new", "line": 4},
                    {"noteId": "user:wrong-line", "filePath": "cmd/main.go", "source": "user",
                     "body": "Repeatable body", "side": "new", "line": 5},
                    {"noteId": "user:new", "filePath": "cmd/main.go", "source": "user",
                     "body": "Repeatable body", "side": "new", "line": 4}
                  ]
                }
                """).getAsJsonObject();
        var target = new ReviewTarget(
                Path.of("/workspace/repository"),
                "cmd/main.go",
                ReviewSide.NEW,
                4
        );

        assertThat(HunkSessionService.newReviewerNote(
                review,
                target,
                "Repeatable body",
                Set.of("user:old")
        )).get()
                .extracting(note -> note.note().id())
                .isEqualTo("user:new");
    }

    @Test
    void doesNotMistakeAnExistingIdenticalCommentForTheNewSubmission() {
        var review = JsonParser.parseString("""
                {
                  "reviewNotes": [
                    {"noteId": "user:old", "filePath": "cmd/main.go", "source": "user",
                     "body": "Same body", "side": "new", "line": 4}
                  ]
                }
                """).getAsJsonObject();
        var target = new ReviewTarget(
                Path.of("/workspace/repository"),
                "cmd/main.go",
                ReviewSide.NEW,
                4
        );

        assertThat(HunkSessionService.newReviewerNote(
                review,
                target,
                "Same body",
                Set.of("user:old")
        )).isEmpty();
    }

    @Test
    void rejectsACommentTargetOutsidePublishedHunks() {
        var file = JsonParser.parseString("""
                {
                  "hunks": [
                    {"index": 0, "oldRange": [10, 12], "newRange": [10, 14]}
                  ]
                }
                """).getAsJsonObject();
        var target = new ReviewTarget(
                Path.of("/workspace/repository"),
                "cmd/main.go",
                ReviewSide.NEW,
                15
        );

        assertThatThrownBy(() -> HunkSessionService.hunkIndex(file, target))
                .isInstanceOf(HunkSessionException.class)
                .hasMessage("cmd/main.go:15 (new) is not part of a hunk in the active Hunk review.");
    }

    @Test
    void acceptsAnUncommentedContextLineInsideAPublishedHunk() throws HunkSessionException {
        var file = JsonParser.parseString("""
                {
                  "hunks": [
                    {"index": 7, "oldRange": [10, 12], "newRange": [10, 14]}
                  ]
                }
                """).getAsJsonObject();
        var target = new ReviewTarget(
                Path.of("/workspace/repository"),
                "cmd/main.go",
                ReviewSide.NEW,
                13
        );

        assertThat(HunkSessionService.hunkIndex(file, target)).isEqualTo(7);
    }
}
