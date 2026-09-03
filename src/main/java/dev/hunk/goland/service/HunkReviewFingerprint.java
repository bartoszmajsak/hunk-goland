package dev.hunk.goland.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.hunk.goland.model.HunkReviewInput;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Computes the exact comparison identity used to guard local comment recovery. */
final class HunkReviewFingerprint {
    private static final long GIT_TIMEOUT_SECONDS = 3;

    private HunkReviewFingerprint() {
    }

    /** Cheap change detector; the full fingerprint remains authoritative for replay. */
    static String shape(HunkReviewInput input, JsonObject review) {
        var digest = sha256();
        add(digest, input.command().toString());
        var files = review.getAsJsonArray("files");
        add(digest, files == null ? null : files.toString());
        return HexFormat.of().formatHex(digest.digest());
    }

    static Optional<String> compute(Path repositoryRoot, HunkReviewInput input, JsonObject review) {
        var digest = sha256();
        add(digest, "hunk-goland-review-v1");
        if (!addInput(digest, repositoryRoot, input)) {
            return Optional.empty();
        }

        var files = review.getAsJsonArray("files");
        add(digest, files == null ? 0 : files.size());
        if (files != null) {
            for (var element : files) {
                if (!element.isJsonObject()) {
                    add(digest, "invalid-file");
                    continue;
                }
                var file = element.getAsJsonObject();
                add(digest, text(file, "path"));
                add(digest, text(file, "previousPath"));
                add(digest, text(file, "additions"));
                add(digest, text(file, "deletions"));
                addHunks(digest, file.getAsJsonArray("hunks"));
                if (usesWorkingTree(input)
                        && !addCurrentFile(digest, repositoryRoot, text(file, "path"))) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(HexFormat.of().formatHex(digest.digest()));
    }

    private static boolean addInput(MessageDigest digest, Path root, HunkReviewInput input) {
        return switch (input) {
            case HunkReviewInput.WorkingTree ignored -> {
                add(digest, "working-tree");
                yield addRevision(digest, root, "HEAD");
            }
            case HunkReviewInput.RevisionRange range -> {
                add(digest, "revision-range");
                yield addRevision(digest, root, range.beforeRevision())
                        && addRevision(digest, root, range.afterRevision());
            }
            case HunkReviewInput.RevisionToWorkingTree comparison -> {
                add(digest, "revision-to-working-tree");
                yield addRevision(digest, root, comparison.beforeRevision());
            }
        };
    }

    private static boolean addRevision(MessageDigest digest, Path root, String revision) {
        var identity = revisionIdentity(root, revision);
        identity.ifPresent(value -> add(digest, value));
        return identity.isPresent();
    }

    private static boolean usesWorkingTree(HunkReviewInput input) {
        return switch (input) {
            case HunkReviewInput.WorkingTree ignored -> true;
            case HunkReviewInput.RevisionToWorkingTree ignored -> true;
            case HunkReviewInput.RevisionRange ignored -> false;
        };
    }

    private static void addHunks(MessageDigest digest, JsonArray hunks) {
        add(digest, hunks == null ? 0 : hunks.size());
        if (hunks == null) {
            return;
        }
        for (var element : hunks) {
            if (!element.isJsonObject()) {
                add(digest, "invalid-hunk");
                continue;
            }
            var hunk = element.getAsJsonObject();
            add(digest, text(hunk, "index"));
            add(digest, text(hunk, "header"));
            add(digest, arrayText(hunk.getAsJsonArray("oldRange")));
            add(digest, arrayText(hunk.getAsJsonArray("newRange")));
        }
    }

    private static boolean addCurrentFile(MessageDigest digest, Path root, String relativePath) {
        if (relativePath == null) {
            return false;
        }
        var path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root)) {
            return false;
        }
        if (!Files.exists(path)) {
            add(digest, "absent-file");
            return true;
        }
        if (Files.isSymbolicLink(path)) {
            try {
                add(digest, "symbolic-link");
                add(digest, Files.readSymbolicLink(path).toString());
                return true;
            } catch (IOException error) {
                return false;
            }
        }
        if (!Files.isRegularFile(path)) {
            return false;
        }
        add(digest, "present-file");
        try {
            add(digest, Long.toString(Files.size(path)));
        } catch (IOException error) {
            return false;
        }
        try (InputStream input = Files.newInputStream(path)) {
            var buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException error) {
            return false;
        }
        return true;
    }

    private static Optional<String> revisionIdentity(Path root, String revision) {
        var command = new ProcessBuilder("git", "rev-parse", "--verify", revision + "^{commit}")
                .directory(root.toFile())
                .redirectErrorStream(true);
        Process process = null;
        try {
            process = command.start();
            if (process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
                var output = process.getInputStream().readAllBytes();
                var identity = new String(output, StandardCharsets.UTF_8).trim();
                return identity.isEmpty() ? Optional.empty() : Optional.of(identity);
            }
            process.destroyForcibly();
        } catch (IOException error) {
            return Optional.empty();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return Optional.empty();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("The runtime does not provide SHA-256.", error);
        }
    }

    private static String text(JsonObject object, String name) {
        var value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static String arrayText(JsonArray array) {
        return array == null ? null : array.toString();
    }

    private static void add(MessageDigest digest, int value) {
        add(digest, Integer.toString(value));
    }

    private static void add(MessageDigest digest, String value) {
        var bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }
}
