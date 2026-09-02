package dev.hunk.goland.service;

import com.intellij.openapi.components.Service;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.hunk.goland.model.HunkSessionNote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/** Resolves the local reviewer's presentation name without changing Hunk note provenance. */
@Service(Service.Level.PROJECT)
public final class HunkReviewerIdentity {
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(2);
    private static final String FALLBACK_NAME = "Reviewer";

    private final ConcurrentMap<Path, CompletableFuture<String>> names = new ConcurrentHashMap<>();

    public CompletableFuture<String> reviewerName(Path repositoryRoot) {
        var root = repositoryRoot.toAbsolutePath().normalize();
        return names.computeIfAbsent(root, ignored -> CompletableFuture.supplyAsync(
                () -> gitUserName(root).orElse(FALLBACK_NAME),
                AppExecutorUtil.getAppExecutorService()
        ));
    }

    public static String displayAuthor(HunkSessionNote note, String reviewerName) {
        var author = normalized(note.author());
        if (note.isReviewerComment() && (author.isEmpty() || "user".equalsIgnoreCase(author.get()))) {
            return normalized(reviewerName).orElse(FALLBACK_NAME);
        }
        return author.orElse(note.isReviewerComment() ? FALLBACK_NAME : "Hunk agent");
    }

    static Optional<String> gitUserName(Path repositoryRoot) {
        if (!Files.isDirectory(repositoryRoot)) {
            return Optional.empty();
        }

        Process process = null;
        try {
            process = new ProcessBuilder(
                    "git",
                    "-C",
                    repositoryRoot.toString(),
                    "config",
                    "--get",
                    "user.name"
            )
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return output.lines().map(String::trim).filter(value -> !value.isEmpty()).findFirst();
        } catch (IOException error) {
            return Optional.empty();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Optional<String> normalized(String value) {
        return Optional.ofNullable(value).map(String::trim).filter(candidate -> !candidate.isEmpty());
    }
}
