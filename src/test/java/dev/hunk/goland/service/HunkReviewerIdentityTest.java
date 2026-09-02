package dev.hunk.goland.service;

import dev.hunk.goland.model.HunkNoteSource;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.ReviewSide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HunkReviewerIdentityTest {
    @Test
    void presentsHunksDefaultUserAsTheRepositoryReviewer() {
        assertThat(HunkReviewerIdentity.displayAuthor(note(HunkNoteSource.USER, "user"), "Bartosz Majsak"))
                .isEqualTo("Bartosz Majsak");
        assertThat(HunkReviewerIdentity.displayAuthor(note(HunkNoteSource.USER, null), "Bartosz Majsak"))
                .isEqualTo("Bartosz Majsak");
    }

    @Test
    void preservesExplicitReviewerAndAgentNames() {
        assertThat(HunkReviewerIdentity.displayAuthor(note(HunkNoteSource.USER, "Ari"), "Bartosz Majsak"))
                .isEqualTo("Ari");
        assertThat(HunkReviewerIdentity.displayAuthor(note(HunkNoteSource.AGENT, "receiver-agent"), "Bartosz Majsak"))
                .isEqualTo("receiver-agent");
        assertThat(HunkReviewerIdentity.displayAuthor(note(HunkNoteSource.AGENT, null), "Bartosz Majsak"))
                .isEqualTo("Hunk agent");
    }

    @Test
    void readsTheEffectiveGitUserName(@TempDir Path repository) throws Exception {
        git(repository, "init");
        git(repository, "config", "user.name", "Hunk GoLand test");

        assertThat(HunkReviewerIdentity.gitUserName(repository))
                .contains("Hunk GoLand test");
    }

    private static HunkSessionNote note(HunkNoteSource source, String author) {
        return new HunkSessionNote(
                (source == HunkNoteSource.USER ? "user" : "agent") + ":1",
                source,
                "Body",
                author,
                null,
                ReviewSide.NEW,
                4,
                source == HunkNoteSource.USER
        );
    }

    private static void git(Path repository, String... arguments)
            throws IOException, InterruptedException {
        var command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repository.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        var output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as("git output: %s", output).isZero();
    }
}
