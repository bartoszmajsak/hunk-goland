package dev.hunk.goland.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hunk.goland.model.HunkReviewInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HunkReviewFingerprintTest {
    @Test
    void workingTreeFingerprintTracksContentNotOnlyHunkCoordinates(@TempDir Path repository)
            throws IOException {
        var file = Files.createDirectories(repository.resolve("cmd")).resolve("main.go");
        Files.writeString(file, "package main\n\nvar answer = 41\n");
        initialize(repository);
        var review = review();

        var before = HunkReviewFingerprint.compute(
                repository,
                HunkReviewInput.workingTree(),
                review
        ).orElseThrow();
        Files.writeString(file, "package main\n\nvar answer = 42\n");
        var after = HunkReviewFingerprint.compute(
                repository,
                HunkReviewInput.workingTree(),
                review
        ).orElseThrow();

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void immutableRevisionRangeIgnoresUnrelatedWorkingTreeEdits(@TempDir Path repository)
            throws IOException {
        var file = Files.createDirectories(repository.resolve("cmd")).resolve("main.go");
        Files.writeString(file, "committed one");
        initialize(repository);
        var beforeRevision = git(repository, "rev-parse", "HEAD");
        Files.writeString(file, "committed two");
        git(repository, "add", ".");
        git(repository, "commit", "--quiet", "--no-gpg-sign", "--no-verify", "-m", "after");
        var afterRevision = git(repository, "rev-parse", "HEAD");
        Files.writeString(file, "uncommitted one");
        var review = review();
        var input = new HunkReviewInput.RevisionRange(beforeRevision, afterRevision);

        var before = HunkReviewFingerprint.compute(repository, input, review).orElseThrow();
        Files.writeString(file, "uncommitted two");
        var after = HunkReviewFingerprint.compute(repository, input, review).orElseThrow();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void revisionIdentityAndHunkShapeArePartOfTheFingerprint(@TempDir Path repository)
            throws IOException {
        var file = Files.createDirectories(repository.resolve("cmd")).resolve("main.go");
        Files.writeString(file, "before");
        initialize(repository);
        var beforeRevision = git(repository, "rev-parse", "HEAD");
        Files.writeString(file, "after");
        git(repository, "add", ".");
        git(repository, "commit", "--quiet", "--no-gpg-sign", "--no-verify", "-m", "after");
        var afterRevision = git(repository, "rev-parse", "HEAD");
        git(repository, "commit", "--quiet", "--allow-empty", "--no-gpg-sign", "--no-verify", "-m", "other");
        var otherRevision = git(repository, "rev-parse", "HEAD");
        var review = review();
        var differentHunk = review();
        differentHunk.getAsJsonArray("files")
                .get(0).getAsJsonObject()
                .getAsJsonArray("hunks")
                .get(0).getAsJsonObject()
                .addProperty("header", "@@ -2,1 +2,1 @@");

        var original = HunkReviewFingerprint.compute(
                repository,
                new HunkReviewInput.RevisionRange(beforeRevision, afterRevision),
                review
        ).orElseThrow();
        var differentRevision = HunkReviewFingerprint.compute(
                repository,
                new HunkReviewInput.RevisionRange(otherRevision, afterRevision),
                review
        ).orElseThrow();
        var differentShape = HunkReviewFingerprint.compute(
                repository,
                new HunkReviewInput.RevisionRange(beforeRevision, afterRevision),
                differentHunk
        ).orElseThrow();

        assertThat(original).isNotEqualTo(differentRevision).isNotEqualTo(differentShape);
    }

    @Test
    void refusesToFingerprintAnUnresolvedRevision(@TempDir Path repository) throws IOException {
        Files.createDirectories(repository.resolve("cmd"));
        Files.writeString(repository.resolve("cmd/main.go"), "package main\n");
        initialize(repository);

        assertThat(HunkReviewFingerprint.compute(
                repository,
                new HunkReviewInput.RevisionRange("missing", "also-missing"),
                review()
        )).isEmpty();
    }

    private static void initialize(Path repository) {
        git(repository, "init", "--quiet");
        git(repository, "config", "user.name", "Hunk recovery test");
        git(repository, "config", "user.email", "hunk-recovery@example.invalid");
        git(repository, "add", ".");
        git(repository, "commit", "--quiet", "--no-gpg-sign", "--no-verify", "-m", "before");
    }

    private static String git(Path repository, String... arguments) {
        var command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        try {
            var process = new ProcessBuilder(command)
                    .directory(repository.toFile())
                    .redirectErrorStream(true)
                    .start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(5, TimeUnit.SECONDS))
                    .describedAs("Git command completed: %s", command)
                    .isTrue();
            assertThat(process.exitValue())
                    .describedAs("Git command succeeded: %s%n%s", command, output)
                    .isZero();
            return output.trim();
        } catch (IOException | InterruptedException error) {
            throw new AssertionError("Could not run " + command, error);
        }
    }

    private static JsonObject review() {
        return JsonParser.parseString("""
                {
                  "files": [{
                    "path": "cmd/main.go",
                    "additions": 1,
                    "deletions": 1,
                    "hunks": [{
                      "index": 0,
                      "header": "@@ -3,1 +3,1 @@",
                      "oldRange": [3, 3],
                      "newRange": [3, 3]
                    }]
                  }]
                }
                """).getAsJsonObject();
    }
}
