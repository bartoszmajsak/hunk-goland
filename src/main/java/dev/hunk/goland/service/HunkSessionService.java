package dev.hunk.goland.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.hunk.goland.model.HunkNoteSource;
import dev.hunk.goland.model.HunkReviewNote;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.ReviewSide;
import dev.hunk.goland.model.ReviewTarget;
import dev.hunk.goland.settings.HunkSettings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service(Service.Level.PROJECT)
public final class HunkSessionService {
    private static final Logger LOG = Logger.getInstance(HunkSessionService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(6);
    private static final Duration PERSISTENCE_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration PERSISTENCE_POLL_INTERVAL = Duration.ofMillis(100);
    private static final String READ_REVIEW = "review";
    private static final String NAVIGATE = "navigate";
    private static final String REMOVE_COMMENT = "comment-rm";
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    private final HunkReviewLauncher launcher;

    public HunkSessionService(Project project) {
        launcher = project.getService(HunkReviewLauncher.class);
    }

    public void addReviewComment(ReviewTarget target, String body)
            throws HunkSessionException {
        var settings = HunkSettings.getInstance().getState();
        var origin = origin(settings.host, settings.port);
        var normalizedBody = body.replace("\r\n", "\n").replace('\r', '\n');
        var reviewBefore = readReview(origin, target);
        validateLineTarget(reviewBefore, target);
        if (!launcher.owns(target)) {
            throw unmanagedReview();
        }
        var existingNoteIds = reviewNotes(reviewBefore).stream()
                .map(HunkReviewNote::note)
                .map(HunkSessionNote::id)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        requireCapability(origin, NAVIGATE);
        var navigate = new JsonObject();
        navigate.addProperty("action", NAVIGATE);
        navigate.add("selector", selector(target));
        navigate.addProperty("filePath", target.filePath());
        navigate.addProperty("side", target.side().wireName());
        navigate.addProperty("line", target.line());
        responseObject(post(origin.resolve("/session-api"), navigate), NAVIGATE);

        LOG.info("event=review-comment-submit-start"
                + " sessionId=" + launcher.sessionId(target).orElse("unknown")
                + " target=" + target.displayName()
                + " existingNotes=" + existingNoteIds.size()
                + " bodyChars=" + body.length());
        var startedAt = System.nanoTime();
        var relay = launcher.addReviewerComment(target, body);
        try {
            var persisted = awaitPersistedReviewerNote(
                    origin,
                    target,
                    normalizedBody,
                    existingNoteIds
            );
            LOG.info("event=review-comment-submit-succeeded"
                    + " sessionId=" + launcher.sessionId(target).orElse("unknown")
                    + " target=" + target.displayName()
                    + " noteId=" + persisted.note().id()
                    + " relayAttempts=" + relay.attempts()
                    + " terminalConfirmed=" + relay.terminalConfirmed()
                    + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - startedAt
                    ));
        } catch (HunkSessionException error) {
            launcher.recoverReviewerComposer(target, "daemon-persistence-unconfirmed");
            LOG.warn("event=review-comment-submit-failed"
                    + " sessionId=" + launcher.sessionId(target).orElse("unknown")
                    + " target=" + target.displayName()
                    + " relayAttempts=" + relay.attempts()
                    + " terminalConfirmed=" + relay.terminalConfirmed()
                    + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - startedAt
                    ), error);
            throw error;
        }
    }

    private HunkReviewNote awaitPersistedReviewerNote(
            URI origin,
            ReviewTarget target,
            String body,
            Set<String> existingNoteIds
    ) throws HunkSessionException {
        var result = new CompletableFuture<HunkReviewNote>();
        var lastReadError = new AtomicReference<HunkSessionException>();
        var attempts = new AtomicInteger();
        var startedAt = System.nanoTime();
        var deadline = System.nanoTime() + PERSISTENCE_TIMEOUT.toNanos();
        pollPersistedReviewerNote(
                origin,
                target,
                body,
                Set.copyOf(existingNoteIds),
                attempts,
                startedAt,
                deadline,
                lastReadError,
                result
        );
        try {
            return result.get(
                    PERSISTENCE_TIMEOUT.plus(TIMEOUT).toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            result.cancel(false);
            throw new HunkSessionException(
                    "Interrupted while verifying the reviewer comment in Hunk.",
                    error
            );
        } catch (ExecutionException error) {
            if (error.getCause() instanceof HunkSessionException hunkError) {
                throw hunkError;
            }
            throw new HunkSessionException(
                    "Could not verify the reviewer comment in Hunk.",
                    error.getCause()
            );
        } catch (TimeoutException error) {
            result.cancel(false);
            throw persistenceTimeout(target, lastReadError.get());
        }
    }

    private void pollPersistedReviewerNote(
            URI origin,
            ReviewTarget target,
            String body,
            Set<String> existingNoteIds,
            AtomicInteger attempts,
            long startedAt,
            long deadline,
            AtomicReference<HunkSessionException> lastReadError,
            CompletableFuture<HunkReviewNote> result
    ) {
        if (result.isDone()) {
            return;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            if (result.isDone()) {
                return;
            }
            try {
                var attempt = attempts.incrementAndGet();
                var review = requestReview(origin, target);
                var notes = reviewNotes(review);
                var match = newReviewerNote(
                        review,
                        target,
                        body,
                        existingNoteIds
                );
                var candidates = notes.stream()
                        .filter(note -> !existingNoteIds.contains(note.note().id()))
                        .filter(note -> note.filePath().equals(target.filePath()))
                        .filter(note -> note.note().source() == HunkNoteSource.USER)
                        .filter(note -> note.note().side() == target.side())
                        .filter(note -> note.note().line() == target.line())
                        .count();
                HunkDiagnostics.info(LOG, "persistence-poll", () -> "sessionId="
                        + launcher.sessionId(target).orElse("unknown")
                        + " target=" + target.displayName()
                        + " attempt=" + attempt
                        + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - startedAt
                        )
                        + " reviewNotes=" + notes.size()
                        + " newLocationCandidates=" + candidates
                        + " exactMatch=" + match.isPresent());
                if (match.isPresent()) {
                    result.complete(match.get());
                    return;
                }
            } catch (HunkSessionException error) {
                lastReadError.set(error);
                var attempt = attempts.get();
                HunkDiagnostics.warning(LOG, "persistence-poll-failed", () -> "sessionId="
                        + launcher.sessionId(target).orElse("unknown")
                        + " target=" + target.displayName()
                        + " attempt=" + attempt
                        + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - startedAt
                        )
                        + " errorType=" + error.getClass().getSimpleName());
            }

            if (System.nanoTime() >= deadline) {
                result.completeExceptionally(persistenceTimeout(target, lastReadError.get()));
                return;
            }
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    () -> pollPersistedReviewerNote(
                            origin,
                            target,
                            body,
                            existingNoteIds,
                            attempts,
                            startedAt,
                            deadline,
                            lastReadError,
                            result
                    ),
                    PERSISTENCE_POLL_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        });
    }

    static Optional<HunkReviewNote> newReviewerNote(
            JsonObject review,
            ReviewTarget target,
            String body,
            Set<String> existingNoteIds
    ) {
        return reviewNotes(review).stream()
                .filter(note -> !existingNoteIds.contains(note.note().id()))
                .filter(note -> note.filePath().equals(target.filePath()))
                .filter(note -> note.note().source() == HunkNoteSource.USER)
                .filter(note -> note.note().side() == target.side())
                .filter(note -> note.note().line() == target.line())
                .filter(note -> note.note().body().equals(body))
                .findFirst();
    }

    private static HunkSessionException persistenceTimeout(
            ReviewTarget target,
            HunkSessionException lastReadError
    ) {
        return new HunkSessionException(
                "Hunk did not publish the reviewer comment on " + target.displayName()
                        + " within " + PERSISTENCE_TIMEOUT.toSeconds()
                        + " seconds. The composer was recovered; retry without retyping your comment.",
                lastReadError
        );
    }

    public void removeReviewComment(ReviewTarget target, String noteId)
            throws HunkSessionException {
        var settings = HunkSettings.getInstance().getState();
        var origin = origin(settings.host, settings.port);
        requireCapability(origin, REMOVE_COMMENT);
        var payload = new JsonObject();
        payload.addProperty("action", REMOVE_COMMENT);
        payload.add("selector", selector(target));
        payload.addProperty("commentId", noteId);
        requireRemoved(responseObject(post(origin.resolve("/session-api"), payload), REMOVE_COMMENT));
    }

    /** Verifies that a line has a native address in the active Hunk review. */
    public void validateCommentTarget(ReviewTarget target) throws HunkSessionException {
        var settings = HunkSettings.getInstance().getState();
        var origin = origin(settings.host, settings.port);
        validateLineTarget(readReview(origin, target), target);
        if (!launcher.owns(target)) {
            throw unmanagedReview();
        }
    }

    public List<HunkSessionNote> listNotes(ReviewTarget target) throws HunkSessionException {
        return listFileNotes(target).stream()
                .filter(note -> note.line() == target.line())
                .toList();
    }

    /** Returns every native Hunk note for the target file and review side. */
    public List<HunkSessionNote> listFileNotes(ReviewTarget target) throws HunkSessionException {
        return listReviewNotes(target).stream()
                .filter(note -> target.filePath().equals(note.filePath()))
                .map(HunkReviewNote::note)
                .filter(note -> note.side() == target.side())
                .toList();
    }

    /** Returns every native note in the selected Hunk review, including its file identity. */
    public List<HunkReviewNote> listReviewNotes(ReviewTarget target) throws HunkSessionException {
        var settings = HunkSettings.getInstance().getState();
        var origin = origin(settings.host, settings.port);
        return reviewNotes(readReview(origin, target));
    }

    static List<HunkReviewNote> reviewNotes(JsonObject review) {
        var comments = review.getAsJsonArray("reviewNotes");
        if (comments == null) {
            return List.of();
        }

        var fileOrder = fileOrder(review.getAsJsonArray("files"));
        return comments.asList().stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .map(HunkSessionService::parseReviewNote)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingInt(note -> fileOrder.getOrDefault(
                        note.filePath(),
                        Integer.MAX_VALUE
                )))
                .toList();
    }

    private static Map<String, Integer> fileOrder(JsonArray files) {
        if (files == null) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, Integer>();
        for (var index = 0; index < files.size(); index++) {
            var value = files.get(index);
            if (!value.isJsonObject()) {
                continue;
            }
            var file = value.getAsJsonObject();
            var rank = index;
            Optional.ofNullable(string(file, "path")).ifPresent(path -> result.putIfAbsent(path, rank));
            Optional.ofNullable(string(file, "previousPath"))
                    .ifPresent(path -> result.putIfAbsent(path, rank));
        }
        return result;
    }

    static Optional<HunkReviewNote> parseReviewNote(JsonObject value) {
        var filePath = string(value, "filePath");
        if (filePath == null) {
            return Optional.empty();
        }
        try {
            return parseNote(value).map(note -> new HunkReviewNote(filePath, note));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private JsonObject readReview(URI origin, ReviewTarget target) throws HunkSessionException {
        requireCapability(origin, READ_REVIEW);

        return requestReview(origin, target);
    }

    private JsonObject requestReview(URI origin, ReviewTarget target) throws HunkSessionException {

        var request = new JsonObject();
        request.addProperty("action", READ_REVIEW);
        request.add("selector", selector(target));
        request.addProperty("includeNotes", true);

        var response = responseObject(post(origin.resolve("/session-api"), request), "review");
        var review = response.getAsJsonObject("review");
        if (review == null) {
            throw new HunkSessionException("Hunk's review response did not contain a review.");
        }
        var notes = reviewNotes(review);
        var reviewerNotes = notes.stream()
                .filter(note -> note.note().source() == HunkNoteSource.USER)
                .count();
        var agentNotes = notes.stream()
                .filter(note -> note.note().source() == HunkNoteSource.AGENT)
                .count();
        HunkDiagnostics.info(LOG, "review-snapshot", () -> "target=" + target.displayName()
                + " selector=" + selectorKind(request)
                + " sessionId=" + diagnosticProperty(review, "sessionId")
                + " inputKind=" + diagnosticProperty(review, "inputKind")
                + " files=" + arraySize(review, "files")
                + " liveComments=" + diagnosticProperty(review, "liveCommentCount")
                + " reviewNotes=" + notes.size()
                + " reviewerNotes=" + reviewerNotes
                + " agentNotes=" + agentNotes);
        return review;
    }

    private static void validateLineTarget(JsonObject review, ReviewTarget target)
            throws HunkSessionException {
        var files = review.getAsJsonArray("files");
        if (files == null) {
            throw new HunkSessionException(
                    "Hunk's review response did not contain its diff files."
            );
        }

        var file = files.asList().stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .filter(candidate -> target.filePath().equals(string(candidate, "path"))
                        || target.filePath().equals(string(candidate, "previousPath")))
                .findFirst()
                .orElseThrow(() -> new HunkSessionException(
                        "The active Hunk review does not contain " + target.filePath() + "."
                ));
        hunkIndex(file, target);
    }

    static int hunkIndex(JsonObject file, ReviewTarget target) throws HunkSessionException {
        var hunks = file.getAsJsonArray("hunks");
        if (hunks != null) {
            var rangeName = target.side() == ReviewSide.NEW ? "newRange" : "oldRange";
            var matching = hunks.asList().stream()
                    .filter(JsonElement::isJsonObject)
                    .map(JsonElement::getAsJsonObject)
                    .filter(hunk -> rangeContains(hunk.getAsJsonArray(rangeName), target.line()))
                    .map(hunk -> nonNegativeInt(hunk.get("index")))
                    .flatMap(Optional::stream)
                    .findFirst();
            if (matching.isPresent()) {
                return matching.get();
            }
        }
        throw new HunkSessionException(
                target.displayName() + " is not part of a hunk in the active Hunk review."
        );
    }

    private static boolean rangeContains(JsonArray range, int line) {
        if (range == null || range.size() != 2) {
            return false;
        }
        var start = positiveInt(range.get(0));
        var end = positiveInt(range.get(1));
        return start != null && end != null && line >= start && line <= end;
    }

    private static void requireRemoved(JsonObject response) throws HunkSessionException {
        var result = response.getAsJsonObject("result");
        if (result == null || !result.has("removed")) {
            throw new HunkSessionException("Hunk returned an invalid comment-rm result.");
        }
        if (!result.get("removed").getAsBoolean()) {
            throw new HunkSessionException("Hunk did not remove this reviewer comment.");
        }
    }

    private void requireCapability(URI origin, String requiredAction) throws HunkSessionException {
        var capabilities = responseObject(
                get(origin.resolve("/session-api/capabilities")),
                "capabilities"
        );
        var actions = capabilities.getAsJsonArray("actions");
        var supported = actions != null && actions.asList().stream()
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .anyMatch(requiredAction::equals);
        if (!supported) {
            throw new HunkSessionException(
                    "This Hunk build does not expose the required `" + requiredAction
                            + "` session action. Update upstream Hunk when IDE review actions become available."
            );
        }
    }

    private JsonObject selector(ReviewTarget target) {
        var sessionId = trimmed(HunkSettings.getInstance().getState().sessionId);
        if (sessionId.isEmpty()) {
            sessionId = launcher.sessionId(target);
        }
        var selector = new JsonObject();
        sessionId.ifPresentOrElse(
                id -> selector.addProperty("sessionId", id),
                () -> selector.addProperty("repoRoot", target.repositoryRoot().toString())
        );
        return selector;
    }

    private static HunkSessionException unmanagedReview() {
        return new HunkSessionException(
                "This live Hunk review is readable, but GoLand does not own its reviewer composer. "
                        + "Start an IDE-managed review here; the external Hunk TUI can remain open."
        );
    }

    static Optional<HunkSessionNote> parseNote(JsonObject value) {
        try {
            var id = primitiveString(firstProperty(value, "noteId", "commentId"));
            var body = primitiveString(firstProperty(value, "body", "summary"));
            var location = noteLocation(value);
            if (id == null || body == null || location == null) {
                return Optional.empty();
            }

            var source = HunkNoteSource.fromWireName(string(value, "source"));
            return Optional.of(new HunkSessionNote(
                    id,
                    source,
                    body,
                    string(value, "author"),
                    string(value, "createdAt"),
                    location.side(),
                    location.line(),
                    booleanValue(value.get("editable")).orElse(source == HunkNoteSource.USER)
            ));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static NoteLocation noteLocation(JsonObject value) {
        var explicitSide = ReviewSide.fromWireName(string(value, "side"));
        var explicitLine = positiveInt(value.get("line"));
        if (explicitSide != null && explicitLine != null) {
            return new NoteLocation(explicitSide, explicitLine);
        }

        var newLine = rangeStart(value.getAsJsonArray("newRange"));
        if (newLine != null) {
            return new NoteLocation(ReviewSide.NEW, newLine);
        }
        var oldLine = rangeStart(value.getAsJsonArray("oldRange"));
        return oldLine == null ? null : new NoteLocation(ReviewSide.OLD, oldLine);
    }

    private static Integer rangeStart(JsonArray range) {
        return range == null || range.isEmpty() ? null : positiveInt(range.get(0));
    }

    private static JsonElement firstProperty(JsonObject value, String... names) {
        return Arrays.stream(names)
                .filter(value::has)
                .map(value::get)
                .findFirst()
                .orElse(null);
    }

    private static String string(JsonObject value, String name) {
        return primitiveString(value.get(name));
    }

    private static String primitiveString(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static Integer positiveInt(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        var value = element.getAsInt();
        return value > 0 ? value : null;
    }

    private static Optional<Integer> nonNegativeInt(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return Optional.empty();
        }
        var value = element.getAsInt();
        return value >= 0 ? Optional.of(value) : Optional.empty();
    }

    private static Optional<Boolean> booleanValue(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? Optional.of(element.getAsBoolean())
                : Optional.empty();
    }

    private HttpResponse<String> get(URI uri) throws HunkSessionException {
        return send(HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build(), "capabilities", "none");
    }

    private HttpResponse<String> post(URI uri, JsonObject payload) throws HunkSessionException {
        return send(HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build(), diagnosticProperty(payload, "action"), selectorKind(payload));
    }

    private HttpResponse<String> send(
            HttpRequest request,
            String action,
            String selector
    ) throws HunkSessionException {
        var requestId = REQUEST_SEQUENCE.incrementAndGet();
        var startedAt = System.nanoTime();
        HunkDiagnostics.info(LOG, "http-start", () -> "requestId=" + requestId
                + " method=" + request.method()
                + " path=" + request.uri().getPath()
                + " action=" + action
                + " selector=" + selector);
        final HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            logHttpFailure(requestId, request, action, selector, startedAt, error);
            throw new HunkSessionException("Interrupted while contacting the Hunk session daemon.", error);
        } catch (IOException error) {
            logHttpFailure(requestId, request, action, selector, startedAt, error);
            var uri = request.uri();
            throw new HunkSessionException(
                    "Could not reach the Hunk session daemon at " + uri.getScheme() + "://" + uri.getAuthority()
                            + ". Open a Hunk review or run `hunk daemon serve`, then retry.",
                    error
            );
        }

        HunkDiagnostics.info(LOG, "http-complete", () -> "requestId=" + requestId
                + " method=" + request.method()
                + " path=" + request.uri().getPath()
                + " action=" + action
                + " selector=" + selector
                + " status=" + response.statusCode()
                + " responseBytes=" + response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new HunkSessionException(readError(response.body(), response.statusCode()));
        }
        return response;
    }

    private void logHttpFailure(
            long requestId,
            HttpRequest request,
            String action,
            String selector,
            long startedAt,
            Exception error
    ) {
        HunkDiagnostics.warning(LOG, "http-failed", () -> "requestId=" + requestId
                + " method=" + request.method()
                + " path=" + request.uri().getPath()
                + " action=" + action
                + " selector=" + selector
                + " errorType=" + error.getClass().getSimpleName()
                + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private static int arraySize(JsonObject object, String name) {
        var value = object.getAsJsonArray(name);
        return value == null ? 0 : value.size();
    }

    private static String diagnosticProperty(JsonObject object, String name) {
        var value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return "none";
        }
        var text = value.getAsString();
        return text.isBlank() ? "none" : text;
    }

    private static String selectorKind(JsonObject payload) {
        var selector = payload.getAsJsonObject("selector");
        if (selector == null) {
            return "none";
        }
        if (selector.has("sessionId")) {
            return "session-id";
        }
        if (selector.has("repoRoot")) {
            return "repo-root";
        }
        return "unknown";
    }

    private static JsonObject responseObject(HttpResponse<String> response, String kind)
            throws HunkSessionException {
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new HunkSessionException("Hunk returned an invalid " + kind + " response.", error);
        }
    }

    private static Optional<String> trimmed(String value) {
        return Optional.ofNullable(value).map(String::trim).filter(text -> !text.isEmpty());
    }

    private static URI origin(String host, int port) {
        var urlHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return URI.create("http://" + urlHost + ":" + port);
    }

    private static String readError(String body, int status) {
        try {
            var error = JsonParser.parseString(body).getAsJsonObject().get("error");
            if (error != null && error.isJsonPrimitive()) {
                return error.getAsString();
            }
        } catch (RuntimeException ignored) {
            // Fall through to a bounded generic error.
        }
        return "Hunk session request failed with HTTP " + status + ".";
    }

    private record NoteLocation(ReviewSide side, int line) {
    }

}
