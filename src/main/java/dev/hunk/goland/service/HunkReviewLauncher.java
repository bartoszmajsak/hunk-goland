package dev.hunk.goland.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.PtyCommandLine;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.hunk.goland.model.HunkReviewInput;
import dev.hunk.goland.model.ReviewTarget;
import dev.hunk.goland.settings.HunkSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Owns one off-screen stock-Hunk PTY so GoLand can create genuine reviewer notes. */
@Service(Service.Level.PROJECT)
public final class HunkReviewLauncher implements Disposable {
    private static final Logger LOG = Logger.getInstance(HunkReviewLauncher.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REGISTRATION_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration COMPOSER_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SAVE_RENDER_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_RELAY_ATTEMPTS = 2;
    private static final int TERMINAL_COLUMNS = 160;
    private static final int TERMINAL_ROWS = 50;

    private final Project project;
    private final HunkSessionTransport transport;
    private final Object lifecycleLock = new Object();
    private ManagedReview active;
    private boolean disposed;

    public HunkReviewLauncher(Project project) {
        this.project = project;
        transport = project.getService(HunkSessionTransport.class);
    }

    /**
     * Starts a Hunk review without opening a second terminal UI.
     *
     * <p>Hunk currently keeps reviewer-note composition inside its TUI process. A real PTY is
     * therefore still required, but GoLand owns it off-screen and drives the same {@code c} / body /
     * {@code Ctrl+S} path as a person using Hunk directly.</p>
     */
    public CompletionStage<String> startReview(Path repositoryRoot, HunkReviewInput input) {
        var root = repositoryRoot.toAbsolutePath().normalize();
        var ready = new CompletableFuture<String>();
        LOG.info("event=managed-review-start-request root=" + root + " input=" + input.command());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ensureStartAllowed();
                var managed = launch(root, input, ready);
                synchronized (lifecycleLock) {
                    ensureOpen();
                    var replacedPid = active == null ? "none" : Long.toString(active.process().pid());
                    LOG.info("event=managed-review-activate"
                            + " root=" + root
                            + " input=" + input.command()
                            + " pid=" + managed.process().pid()
                            + " replacedPid=" + replacedPid);
                    close(active);
                    active = managed;
                }
                drainOutput(managed);
                discoverSession(managed, System.nanoTime() + REGISTRATION_TIMEOUT.toNanos());
            } catch (Exception error) {
                LOG.warn("event=managed-review-start-failed"
                        + " root=" + root
                        + " input=" + input.command()
                        + " error=" + error.getMessage(), error);
                ready.completeExceptionally(error);
            }
        });
        return ready;
    }

    public boolean owns(ReviewTarget target) {
        synchronized (lifecycleLock) {
            return active != null
                    && active.root().equals(target.repositoryRoot())
                    && active.input().equals(target.reviewInput())
                    && active.process().isAlive()
                    && active.ready().isDone()
                    && !active.ready().isCompletedExceptionally();
        }
    }

    /**
     * Returns the input owned by the live IDE-managed review for this repository.
     *
     * <p>Ordinary editors do not carry the VCS comparison metadata available inside a diff viewer.
     * Returning the input while registration is still pending also prevents another view from
     * immediately launching a competing review.</p>
     */
    public Optional<HunkReviewInput> activeReviewInput(Path repositoryRoot) {
        var root = repositoryRoot.toAbsolutePath().normalize();
        synchronized (lifecycleLock) {
            if (active == null || !active.root().equals(root) || !active.process().isAlive()) {
                return Optional.empty();
            }
            return Optional.of(active.input());
        }
    }

    public Optional<String> sessionId(ReviewTarget target) {
        synchronized (lifecycleLock) {
            if (active == null
                    || !active.root().equals(target.repositoryRoot())
                    || !active.input().equals(target.reviewInput())
                    || !active.process().isAlive()) {
                return Optional.empty();
            }
            return Optional.ofNullable(active.sessionId());
        }
    }

    /** Relays one already-validated comment through the managed TUI's native reviewer composer. */
    public CommentRelayResult addReviewerComment(ReviewTarget target, String body)
            throws HunkSessionException {
        var managed = managedReview(target);
        var normalized = normalizeBody(body);
        if (normalized.isBlank()) {
            throw new HunkSessionException("Enter a review comment first.");
        }

        synchronized (managed.ioLock()) {
            for (var attempt = 1; attempt <= MAX_RELAY_ATTEMPTS; attempt++) {
                var stage = CommentRelayStage.OPEN_DRAFT;
                var stageMark = managed.output().mark();
                try {
                    // Do not prefix this with a bare Escape. PTYs intentionally hold Escape for a
                    // short ambiguity window and can parse an immediately following `c` as Alt+C.
                    stageMark = managed.output().mark();
                    relayStage(managed, target, attempt, stage, stageMark);
                    write(managed, "c");
                    managed.output().awaitTextAfter(
                            stageMark,
                            "Draft note",
                            "the reviewer composer",
                            COMPOSER_TIMEOUT
                    );

                    stage = CommentRelayStage.PASTE_BODY;
                    stageMark = managed.output().mark();
                    relayStage(managed, target, attempt, stage, stageMark);
                    write(managed, "\u001b[200~" + normalized + "\u001b[201~");
                    // A short prefix from the first body row remains contiguous even when a long
                    // Markdown comment wraps. Never include this private fragment in diagnostics.
                    managed.output().awaitTextAfter(
                            stageMark,
                            confirmationFragment(normalized),
                            "the pasted reviewer comment",
                            COMPOSER_TIMEOUT
                    );

                    stage = CommentRelayStage.SAVE;
                    stageMark = managed.output().mark();
                    relayStage(managed, target, attempt, stage, stageMark);
                    write(managed, "\u0013");
                    var terminalConfirmed = awaitSaveRender(managed, stageMark, target, attempt);
                    LOG.info(commentRelayEvent("managed-review-comment-relayed", managed, target)
                            + " attempt=" + attempt
                            + " terminalConfirmed=" + terminalConfirmed
                            + " bodyChars=" + normalized.length());
                    return new CommentRelayResult(terminalConfirmed, attempt);
                } catch (IOException | HunkSessionException error) {
                    LOG.warn(commentRelayEvent("managed-review-comment-relay-failed", managed, target)
                            + " attempt=" + attempt
                            + " stage=" + stage.wireName()
                            + " bodyChars=" + normalized.length()
                            + " output=" + managed.output().describeSince(stageMark), error);
                    var recovered = recoverReviewerComposer(
                            managed,
                            target,
                            "relay-failed-" + stage.wireName()
                    );
                    if (attempt == MAX_RELAY_ATTEMPTS || stage == CommentRelayStage.SAVE) {
                        throw new HunkSessionException(
                                "Managed Hunk could not " + stage.failureDescription()
                                        + " after " + attempt + " attempt"
                                        + (attempt == 1 ? "." : "s."),
                                error
                        );
                    }
                    if (!recovered) {
                        throw new HunkSessionException(
                                "Managed Hunk could not establish a clean reviewer-composer state "
                                        + "for a safe retry.",
                                error
                        );
                    }
                    LOG.info(commentRelayEvent("managed-review-comment-relay-retrying", managed, target)
                            + " nextAttempt=" + (attempt + 1)
                            + " failedStage=" + stage.wireName());
                }
            }
        }
        throw new IllegalStateException("Reviewer comment relay exhausted without a result.");
    }

    /** Cancels a draft or modal surface after daemon verification rejects a relay. */
    public void recoverReviewerComposer(ReviewTarget target, String reason) {
        final ManagedReview managed;
        try {
            managed = managedReview(target);
        } catch (HunkSessionException error) {
            LOG.warn("event=managed-review-comment-recovery-skipped"
                    + " target=" + target.displayName()
                    + " reason=" + reason
                    + " error=" + error.getMessage());
            return;
        }
        synchronized (managed.ioLock()) {
            recoverReviewerComposer(managed, target, reason);
        }
    }

    private static boolean awaitSaveRender(
            ManagedReview managed,
            long mark,
            ReviewTarget target,
            int attempt
    ) {
        try {
            managed.output().awaitTextAfter(
                    mark,
                    "Your note",
                    "the saved reviewer note",
                    SAVE_RENDER_TIMEOUT
            );
            return true;
        } catch (HunkSessionException error) {
            // Rendering is advisory after Ctrl+S. The caller verifies the authoritative daemon
            // review before it reports success or decides that recovery is required.
            LOG.warn(commentRelayEvent("managed-review-comment-save-render-missed", managed, target)
                    + " attempt=" + attempt
                    + " output=" + managed.output().describeSince(mark), error);
            return false;
        }
    }

    private static boolean recoverReviewerComposer(
            ManagedReview managed,
            ReviewTarget target,
            String reason
    ) {
        try {
            var mark = managed.output().mark();
            write(managed, "\u001b");
            managed.output().awaitOutputAfter(mark, RECOVERY_TIMEOUT);
            LOG.info(commentRelayEvent("managed-review-comment-recovered", managed, target)
                    + " reason=" + reason
                    + " terminalConfirmed=true");
            return true;
        } catch (IOException | HunkSessionException error) {
            LOG.warn(commentRelayEvent("managed-review-comment-recovery-failed", managed, target)
                    + " reason=" + reason, error);
            return false;
        }
    }

    private ManagedReview launch(
            Path root,
            HunkReviewInput input,
            CompletableFuture<String> ready
    ) throws ExecutionException {
        var settings = HunkSettings.getInstance().getState();
        var command = new ArrayList<>(input.command());
        command.set(0, settings.executable.trim().isEmpty() ? "hunk" : settings.executable.trim());

        var commandLine = new PtyCommandLine(command)
                .withConsoleMode(false)
                .withInitialColumns(TERMINAL_COLUMNS)
                .withInitialRows(TERMINAL_ROWS);
        commandLine.withWorkingDirectory(root);
        commandLine.withRedirectErrorStream(true);
        commandLine.withEnvironment("HUNK_MCP_HOST", settings.host);
        commandLine.withEnvironment("HUNK_MCP_PORT", Integer.toString(settings.port));
        commandLine.withEnvironment("TERM", "xterm-256color");
        var process = commandLine.createProcess();
        LOG.info("event=managed-review-process-started"
                + " root=" + root
                + " input=" + command
                + " pid=" + process.pid()
                + " daemon=" + settings.host + ":" + settings.port);
        return new ManagedReview(root, input, process, new TerminalOutput(), ready, System.nanoTime());
    }

    private void ensureStartAllowed() throws HunkSessionException {
        var settings = HunkSettings.getInstance().getState();
        if (!settings.sessionId.isBlank()) {
            throw new HunkSessionException(
                    "Clear the preferred session ID before starting an IDE-managed Hunk review."
            );
        }
    }

    private void discoverSession(ManagedReview managed, long deadlineNanos) {
        managed.registrationAttempted();
        if (!managed.process().isAlive()) {
            var error = new HunkSessionException(
                    "Managed Hunk exited before registering a review. " + managed.output().tail()
            );
            LOG.warn(registrationEvent("managed-review-registration-failed", managed)
                    + " reason=process-exited", error);
            managed.ready().completeExceptionally(error);
            return;
        }
        try {
            var sessions = listSessions();
            var matches = sessions.stream()
                    .filter(session -> managed.root().toString().equals(string(session, "repoRoot")))
                    .filter(session -> longValue(session.get("pid")) == managed.process().pid())
                    .map(session -> string(session, "sessionId"))
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            HunkDiagnostics.info(LOG, "session-scan", () -> "root=" + managed.root()
                    + " pid=" + managed.process().pid()
                    + " attempt=" + managed.registrationAttempts()
                    + " sessions=" + sessions.size()
                    + " matchingSessions=" + matches.size()
                    + " processAlive=" + managed.process().isAlive());
            var match = matches.stream().findFirst();
            if (match.isPresent()) {
                managed.bind(match.get());
                LOG.info(registrationEvent("managed-review-session-registered", managed)
                        + " sessionId=" + match.get());
                managed.ready().complete(match.get());
                return;
            }
        } catch (HunkSessionException error) {
            // Hunk may still be starting its daemon. Retry until the registration deadline.
            managed.registrationError(error.getMessage());
            HunkDiagnostics.warning(LOG, "session-scan-failed", () -> "root=" + managed.root()
                    + " pid=" + managed.process().pid()
                    + " attempt=" + managed.registrationAttempts()
                    + " errorType=" + error.getClass().getSimpleName());
        }

        if (System.nanoTime() >= deadlineNanos) {
            var error = new HunkSessionException(
                    "Timed out waiting for managed Hunk to register this worktree. "
                            + managed.output().tail()
            );
            LOG.warn(registrationEvent("managed-review-registration-failed", managed)
                    + " reason=timeout"
                    + " lastSessionError=" + managed.lastRegistrationError(), error);
            managed.ready().completeExceptionally(error);
            return;
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
                () -> discoverSession(managed, deadlineNanos),
                100,
                TimeUnit.MILLISECONDS
        );
    }

    private List<JsonObject> listSessions() throws HunkSessionException {
        var payload = new JsonObject();
        payload.addProperty("action", "list");
        var settings = HunkSettings.getInstance().getState();
        try {
            var response = transport.post(origin(settings.host, settings.port).resolve("/session-api"), payload, HTTP_TIMEOUT);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HunkSessionException("Could not inspect active Hunk reviews.");
            }
            var sessions = JsonParser.parseString(response.body()).getAsJsonObject()
                    .getAsJsonArray("sessions");
            if (sessions == null) {
                return List.of();
            }
            return sessions.asList().stream()
                    .filter(JsonElement::isJsonObject)
                    .map(JsonElement::getAsJsonObject)
                    .toList();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new HunkSessionException("Interrupted while inspecting active Hunk reviews.", error);
        } catch (IOException | RuntimeException error) {
            throw new HunkSessionException("Could not inspect active Hunk reviews.", error);
        }
    }

    private ManagedReview managedReview(ReviewTarget target) throws HunkSessionException {
        synchronized (lifecycleLock) {
            if (!owns(target)) {
                throw new HunkSessionException(
                        "This review is not owned by GoLand. Start an IDE-managed Hunk review "
                                + "before adding reviewer comments."
                );
            }
            return active;
        }
    }

    private static void drainOutput(ManagedReview managed) {
        Thread.ofVirtual().name("hunk-managed-review-output").start(() -> {
            try (InputStream input = managed.process().getInputStream()) {
                var buffer = new byte[8_192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    managed.output().append(buffer, read);
                }
            } catch (IOException error) {
                managed.output().fail(error);
            }
        });
    }

    private static void write(ManagedReview managed, String value) throws IOException {
        OutputStream output = managed.process().getOutputStream();
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String normalizeBody(String body) throws HunkSessionException {
        if (body.indexOf('\u001b') >= 0 || body.indexOf('\u0013') >= 0) {
            throw new HunkSessionException("Review comments cannot contain terminal control characters.");
        }
        return body.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** A short first-line prefix proves bracketed paste was rendered without depending on wrapping. */
    static String confirmationFragment(String body) {
        var line = body.lines()
                .map(String::stripLeading)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElseThrow();
        var end = line.offsetByCodePoints(0, Math.min(32, line.codePointCount(0, line.length())));
        return line.substring(0, end);
    }

    private static String string(JsonObject object, String name) {
        var value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static long longValue(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsLong()
                : -1;
    }

    private static URI origin(String host, int port) {
        var urlHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return URI.create("http://" + urlHost + ":" + port);
    }

    private void ensureOpen() {
        if (disposed || project.isDisposed()) {
            throw new IllegalStateException("The project is already closed.");
        }
    }

    @Override
    public void dispose() {
        synchronized (lifecycleLock) {
            disposed = true;
            if (active != null) {
                LOG.info("event=managed-review-dispose pid=" + active.process().pid()
                        + " sessionId=" + active.sessionId());
            }
            close(active);
            active = null;
        }
    }

    private static void close(ManagedReview managed) {
        if (managed == null || !managed.process().isAlive()) {
            return;
        }
        LOG.info("event=managed-review-stop"
                + " root=" + managed.root()
                + " input=" + managed.input().command()
                + " pid=" + managed.process().pid()
                + " sessionId=" + managed.sessionId());
        try {
            write(managed, "q");
        } catch (IOException ignored) {
            managed.process().destroy();
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
            if (managed.process().isAlive()) {
                managed.process().destroy();
            }
        }, 2, TimeUnit.SECONDS);
    }

    private static String registrationEvent(String event, ManagedReview managed) {
        return "event=" + event
                + " root=" + managed.root()
                + " input=" + managed.input().command()
                + " pid=" + managed.process().pid()
                + " attempts=" + managed.registrationAttempts()
                + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - managed.startedAtNanos()
                );
    }

    private static String commentRelayEvent(
            String event,
            ManagedReview managed,
            ReviewTarget target
    ) {
        return "event=" + event + " " + commentRelayContext(managed, target);
    }

    private static String commentRelayContext(ManagedReview managed, ReviewTarget target) {
        return "sessionId=" + managed.sessionId()
                + " pid=" + managed.process().pid()
                + " target=" + target.displayName();
    }

    private static void relayStage(
            ManagedReview managed,
            ReviewTarget target,
            int attempt,
            CommentRelayStage stage,
            long mark
    ) {
        HunkDiagnostics.info(LOG, "pty-stage", () -> commentRelayContext(managed, target)
                + " attempt=" + attempt
                + " stage=" + stage.wireName()
                + " terminalMark=" + mark);
    }

    public record CommentRelayResult(boolean terminalConfirmed, int attempts) {
    }

    private enum CommentRelayStage {
        OPEN_DRAFT("open-draft", "open its reviewer composer"),
        PASTE_BODY("paste-body", "accept the reviewer comment text"),
        SAVE("save", "save the reviewer comment");

        private final String wireName;
        private final String failureDescription;

        CommentRelayStage(String wireName, String failureDescription) {
            this.wireName = wireName;
            this.failureDescription = failureDescription;
        }

        String wireName() {
            return wireName;
        }

        String failureDescription() {
            return failureDescription;
        }
    }

    private static final class ManagedReview {
        private final Path root;
        private final HunkReviewInput input;
        private final Process process;
        private final TerminalOutput output;
        private final CompletableFuture<String> ready;
        private final long startedAtNanos;
        private final Object ioLock = new Object();
        private volatile String sessionId;
        private volatile int registrationAttempts;
        private volatile String lastRegistrationError = "none";

        private ManagedReview(
                Path root,
                HunkReviewInput input,
                Process process,
                TerminalOutput output,
                CompletableFuture<String> ready,
                long startedAtNanos
        ) {
            this.root = root;
            this.input = input;
            this.process = process;
            this.output = output;
            this.ready = ready;
            this.startedAtNanos = startedAtNanos;
        }

        Path root() {
            return root;
        }

        HunkReviewInput input() {
            return input;
        }

        Process process() {
            return process;
        }

        TerminalOutput output() {
            return output;
        }

        CompletableFuture<String> ready() {
            return ready;
        }

        Object ioLock() {
            return ioLock;
        }

        String sessionId() {
            return sessionId;
        }

        void bind(String value) {
            sessionId = value;
        }

        long startedAtNanos() {
            return startedAtNanos;
        }

        int registrationAttempts() {
            return registrationAttempts;
        }

        void registrationAttempted() {
            registrationAttempts++;
        }

        String lastRegistrationError() {
            return lastRegistrationError;
        }

        void registrationError(String value) {
            lastRegistrationError = value == null ? "unknown" : value;
        }
    }

    /** A bounded terminal transcript with condition-based waits; no fixed sleeps. */
    private static final class TerminalOutput {
        private static final int MAX_CHARS = 128 * 1024;
        private final StringBuilder text = new StringBuilder();
        private long base;
        private IOException failure;

        synchronized long mark() {
            return base + text.length();
        }

        synchronized void append(byte[] bytes, int length) {
            text.append(new String(bytes, 0, length, StandardCharsets.UTF_8));
            if (text.length() > MAX_CHARS) {
                var remove = text.length() - MAX_CHARS;
                text.delete(0, remove);
                base += remove;
            }
            notifyAll();
        }

        synchronized void fail(IOException error) {
            failure = error;
            notifyAll();
        }

        synchronized void awaitTextAfter(
                long mark,
                String expected,
                String expectation,
                Duration timeout
        )
                throws HunkSessionException {
            await(mark, expected, expectation, timeout);
        }

        synchronized void awaitOutputAfter(long mark, Duration timeout)
                throws HunkSessionException {
            var deadline = System.nanoTime() + timeout.toNanos();
            while (mark() <= mark) {
                if (failure != null) {
                    throw new HunkSessionException("Managed Hunk terminal output failed.", failure);
                }
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new HunkSessionException(
                            "Managed Hunk did not acknowledge reviewer-composer recovery in time."
                    );
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new HunkSessionException(
                            "Interrupted while recovering managed Hunk.",
                            error
                    );
                }
            }
        }

        private void await(long mark, String expected, String expectation, Duration timeout)
                throws HunkSessionException {
            var deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                if (failure != null) {
                    throw new HunkSessionException("Managed Hunk terminal output failed.", failure);
                }
                var start = (int) Math.clamp(mark - base, 0, text.length());
                if (text.indexOf(expected, start) >= 0) {
                    return;
                }
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new HunkSessionException(
                            "Managed Hunk did not render " + expectation + " in time ("
                                    + describeSince(mark) + ")."
                    );
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new HunkSessionException("Interrupted while waiting for managed Hunk.", error);
                }
            }
        }

        synchronized String tail() {
            var start = Math.max(0, text.length() - 1_000);
            return text.substring(start).replaceAll("\\x1B(?:[@-Z\\-_]|\\[[0-?]*[ -/]*[@-~])", "");
        }

        synchronized String describeSince(long mark) {
            var start = (int) Math.clamp(mark - base, 0, text.length());
            var appended = text.substring(start);
            return "outputChars=" + appended.length()
                    + ", draftVisible=" + appended.contains("Draft note")
                    + ", savedVisible=" + appended.contains("Your note");
        }
    }
}
