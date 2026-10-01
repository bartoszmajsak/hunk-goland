package dev.hunk.goland.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** Stock Hunk 0.23 caller authentication. No anonymous fallback, subprocess, or manual token. */
@Service(Service.Level.PROJECT)
public final class HunkSessionTransport implements Disposable, AutoCloseable {
    private static final String APP = "dev.hunk";
    private static final int APP_REVISION = 16;
    private static final String DOMAIN = "dev.hunk.session-broker.v1/";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final BigInteger MAX_SEQUENCE = new BigInteger("18446744073709551615");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Path credentialsDirectory;
    private final Consumer<String> diagnostics;
    private final Map<URI, Caller> callers = new HashMap<>();

    public HunkSessionTransport() {
        this(HunkBrokerCredentials.defaultDirectory(), phase -> {
            if (ApplicationManager.getApplication() != null)
                HunkDiagnostics.info(Logger.getInstance(HunkSessionTransport.class), "authentication", () -> "phase=" + phase);
        });
    }

    /** Also allows isolated credential stores in protocol tests. Never creates or modifies credentials. */
    public HunkSessionTransport(Path credentialsDirectory) {
        this(credentialsDirectory, _ -> {});
    }

    private HunkSessionTransport(Path credentialsDirectory, Consumer<String> diagnostics) {
        this.credentialsDirectory = credentialsDirectory;
        this.diagnostics = diagnostics;
    }

    public record Response(int statusCode, String body) {
        @Override public String toString() { return "Hunk response: HTTP " + statusCode + " (body omitted)"; }
    }

    @Override public void dispose() { close(); }
    @Override public void close() { client.shutdownNow(); }

    public Response get(URI uri, Duration timeout) throws IOException, InterruptedException {
        return request(uri, "GET", "", false, timeout);
    }

    public Response post(URI uri, JsonObject payload, Duration timeout) throws IOException, InterruptedException {
        return request(uri, "POST", payload.toString(), !"list".equals(payload.get("action").getAsString()), timeout);
    }

    private Response request(URI uri, String method, String body, boolean targetSpecific, Duration timeout)
            throws IOException, InterruptedException {
        require(uri.getScheme().equals("http") && uri.getUserInfo() == null && uri.getFragment() == null);
        require(Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(uri.getHost()));
        require(Set.of("/session-api", "/session-api/capabilities").contains(uri.getRawPath()) && uri.getRawQuery() == null);
        var origin = uri.resolve("/");
        final Caller caller;
        synchronized (callers) {
            caller = callers.computeIfAbsent(origin, _ -> new Caller(origin));
        }
        // Serialize each origin's short control calls: one negotiation, monotonically ordered sequences,
        // and no stale 401 invalidating a newly negotiated session. Never hold a global project lock.
        synchronized (caller) {
            try {
                return caller.request(uri, method, body, targetSpecific, timeout);
            } catch (GeneralSecurityException | RuntimeException ignored) {
                // JSON/provider exceptions can echo their input. Surface only a body-free error.
                throw authenticationError();
            }
        }
    }

    private final class Caller {
        private final URI origin;
        private HunkBrokerCredentials keys;
        private JsonObject challenge;
        private JsonObject session;
        private String helloHash;
        private BigInteger sequence = BigInteger.ONE;

        Caller(URI origin) { this.origin = origin; }

        Response request(URI uri, String method, String body, boolean targetSpecific, Duration timeout)
                throws IOException, InterruptedException, GeneralSecurityException {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (session == null || System.currentTimeMillis() >= session.get("expiresAt").getAsLong()) negotiate(timeout);
                require(sequence.compareTo(MAX_SEQUENCE) <= 0);
                var requestId = nonce();
                var requestSequence = sequence.toString();
                sequence = sequence.add(BigInteger.ONE);
                var transcript = transcript("caller-request");
                transcript.addProperty("callerSessionId", session.get("callerSessionId").getAsString());
                transcript.addProperty("helloTranscriptHash", helloHash);
                transcript.addProperty("requestId", requestId);
                transcript.addProperty("sequence", requestSequence);
                transcript.addProperty("method", method);
                transcript.addProperty("target", uri.getRawPath());
                transcript.addProperty("bodyDigest", digest(body.getBytes(StandardCharsets.UTF_8)));
                var builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json")
                        .header("x-session-broker-caller-session", session.get("callerSessionId").getAsString())
                        .header("x-session-broker-request-id", requestId)
                        .header("x-session-broker-sequence", requestSequence)
                        .header("x-session-broker-signature", sign(transcript));
                if (method.equals("POST")) builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
                else builder.GET();
                var response = send(builder.build());
                try {
                    var envelope = parse(response.body());
                    verifyResponse(envelope, response.statusCode(), requestId, requestSequence, targetSpecific);
                    return new Response(response.statusCode(), envelope.get("body").toString());
                } catch (IOException | GeneralSecurityException | RuntimeException ignored) {
                    // Only an authentication rejection permits one retry. Never retry an ambiguous write
                    // after timeout, disconnect, malformed success, or a failed response signature.
                    if (response.statusCode() != 401 || attempt != 0) throw authenticationError();
                    diagnostics.accept("caller-rejected-renegotiating");
                    session = null;
                }
            }
            throw authenticationError();
        }

        private void negotiate(Duration timeout) throws IOException, InterruptedException, GeneralSecurityException {
            diagnostics.accept("hello-start");
            keys = HunkBrokerCredentials.load(credentialsDirectory);
            var hello = new JsonObject();
            hello.addProperty("role", "caller");
            hello.addProperty("appId", APP);
            hello.addProperty("endpoint", origin.resolve("/session-auth/challenge").toString());
            hello.add("keyId", keys.grant.get("keyId"));
            hello.add("grantId", keys.grant.get("grantId"));
            hello.addProperty("initiatorNonce", nonce());
            hello.add("proposal", selection());
            challenge = handshake("/session-auth/challenge", hello, timeout);
            exact(challenge, "challengeId", "generation", "responderNonce", "expiresAt", "daemonKeyId", "daemonSignature");
            identifier(challenge, "challengeId"); identifier(challenge, "generation"); identifier(challenge, "responderNonce");
            require(challenge.get("expiresAt").getAsLong() > System.currentTimeMillis());
            require(challenge.get("daemonKeyId").getAsString().equals(keys.daemonKeyId));
            var transcript = transcript("caller-hello");
            transcript.add("endpoint", hello.get("endpoint"));
            transcript.add("initiatorNonce", hello.get("initiatorNonce"));
            transcript.add("proposal", hello.get("proposal"));
            transcript.add("responderNonce", challenge.get("responderNonce"));
            verify(transcript, challenge.get("daemonSignature").getAsString());
            diagnostics.accept("challenge-verified");
            helloHash = digest(canonical(transcript));
            var proof = new JsonObject();
            proof.add("challengeId", challenge.get("challengeId"));
            proof.addProperty("signature", sign(transcript));
            var ack = handshake("/session-auth/proof", proof, timeout);
            exact(ack, "callerSessionId", "principal", "expiresAt", "initialSequence", "brokerRevision", "appRevision",
                    "features", "helloTranscriptHash", "daemonKeyId", "daemonSignature");
            identifier(ack, "callerSessionId");
            require(ack.get("expiresAt").getAsLong() > System.currentTimeMillis());
            require(ack.get("initialSequence").getAsString().equals("1"));
            require(ack.get("brokerRevision").getAsInt() == 1 && ack.get("appRevision").getAsInt() == APP_REVISION);
            require(ack.getAsJsonArray("features").isEmpty());
            require(ack.get("helloTranscriptHash").getAsString().equals(helloHash));
            require(ack.get("daemonKeyId").getAsString().equals(keys.daemonKeyId));
            var principal = ack.getAsJsonObject("principal");
            exact(principal, "kind", "appId", "principalId", "keyId", "grantId", "operations", "commands");
            for (var field : principal.keySet()) require(principal.get(field).equals(keys.grant.get(field)));
            var ackTranscript = transcript("caller-hello-ack");
            ackTranscript.add("callerSessionId", ack.get("callerSessionId"));
            ackTranscript.add("initialSequence", ack.get("initialSequence"));
            ackTranscript.addProperty("helloTranscriptHash", helloHash);
            ackTranscript.add("selection", selection());
            verify(ackTranscript, ack.get("daemonSignature").getAsString());
            session = ack;
            sequence = BigInteger.ONE;
            diagnostics.accept("caller-ready");
        }

        private JsonObject handshake(String path, JsonObject payload, Duration timeout) throws IOException, InterruptedException {
            var response = send(HttpRequest.newBuilder(origin.resolve(path)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build());
            if (response.statusCode() != 200) throw new HunkAuthenticationException("Hunk local authentication failed. "
                    + "Ensure the running daemon and installed Hunk are both 0.23.0 and belong to the same OS user.");
            return parse(response.body());
        }

        private JsonObject transcript(String kind) {
            var value = new JsonObject();
            value.addProperty("appId", APP);
            value.addProperty("domain", DOMAIN + kind);
            value.add("generation", challenge.get("generation"));
            value.add("grantId", keys.grant.get("grantId"));
            value.add("keyId", keys.grant.get("keyId"));
            return value;
        }

        private void verifyResponse(JsonObject envelope, int status, String requestId, String requestSequence,
                                    boolean targetSpecific) throws IOException, GeneralSecurityException {
            exact(envelope, "body", "authentication");
            var auth = envelope.getAsJsonObject("authentication");
            var fields = new java.util.HashSet<>(Set.of("generation", "brokerRevision", "callerSessionId", "requestId",
                    "sequence", "httpStatus", "bodyDigest", "daemonKeyId", "daemonSignature"));
            if (targetSpecific) fields.add("appContract");
            require(auth.keySet().equals(fields));
            require(auth.get("generation").equals(challenge.get("generation")));
            require(auth.get("callerSessionId").equals(session.get("callerSessionId")));
            require(auth.get("requestId").getAsString().equals(requestId));
            require(auth.get("sequence").getAsString().equals(requestSequence));
            require(auth.get("httpStatus").getAsInt() == status && auth.get("brokerRevision").getAsInt() == 1);
            require(auth.get("daemonKeyId").getAsString().equals(keys.daemonKeyId));
            require(auth.get("bodyDigest").getAsString().equals(digest(canonical(envelope.get("body")))));
            var value = new JsonObject();
            value.addProperty("appId", APP);
            value.addProperty("domain", DOMAIN + "caller-response");
            for (var field : List.of("generation", "brokerRevision", "callerSessionId", "requestId", "sequence", "httpStatus", "bodyDigest"))
                value.add(field, auth.get(field));
            if (targetSpecific) {
                var contract = auth.getAsJsonObject("appContract");
                exact(contract, "appRevision", "features");
                require(contract.get("appRevision").getAsInt() == APP_REVISION && contract.getAsJsonArray("features").isEmpty());
                value.add("appContract", contract);
            }
            verify(value, auth.get("daemonSignature").getAsString());
        }

        private String sign(JsonObject transcript) throws GeneralSecurityException, IOException {
            var signature = Signature.getInstance("Ed25519");
            signature.initSign(keys.callerKey);
            signature.update(canonical(transcript));
            return encode(signature.sign());
        }

        private void verify(JsonObject transcript, String encoded) throws IOException, GeneralSecurityException {
            var signature = Signature.getInstance("Ed25519");
            signature.initVerify(keys.daemonKey);
            signature.update(canonical(transcript));
            require(signature.verify(HunkBrokerCredentials.decode(encoded)));
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        try {
            return client.send(request, info -> {
                var length = info.headers().firstValueAsLong("Content-Length");
                if (length.isPresent() && (length.getAsLong() < 0 || length.getAsLong() > MAX_RESPONSE_BYTES))
                    return new BoundedBody(0);
                return new BoundedBody(MAX_RESPONSE_BYTES);
            });
        } catch (IOException error) {
            for (var cause = error; cause != null; ) {
                if (cause instanceof HunkAuthenticationException) throw authenticationError();
                var next = cause.getCause();
                if (!(next instanceof IOException io)) break;
                cause = io;
            }
            throw new IOException("Hunk authentication transport failed. Check daemon connectivity and version, then retry.");
        }
    }

    private static JsonObject parse(byte[] bytes) throws IOException {
        try {
            var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            // The canonicalizer rejects duplicate keys and invalid JSON before Gson's permissive parser.
            var encoded = new JsonCanonicalizer(text).getEncodedString();
            var value = JsonParser.parseString(encoded);
            validateUnicode(value);
            return value.getAsJsonObject();
        } catch (RuntimeException | IOException ignored) { throw authenticationError(); }
    }

    static byte[] canonical(JsonElement value) throws IOException {
        validateUnicode(value);
        try { return new JsonCanonicalizer(value.toString()).getEncodedUTF8(); }
        catch (RuntimeException | IOException ignored) { throw authenticationError(); }
    }

    private static void validateUnicode(JsonElement value) throws IOException {
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                scalar(entry.getKey()); validateUnicode(entry.getValue());
            }
        } else if (value.isJsonArray()) {
            for (var entry : value.getAsJsonArray()) validateUnicode(entry);
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) scalar(value.getAsString());
    }

    private static void scalar(String value) throws IOException {
        for (int index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (Character.isHighSurrogate(character)) require(++index < value.length() && Character.isLowSurrogate(value.charAt(index)));
            else require(!Character.isLowSurrogate(character));
        }
    }

    private static JsonObject selection() {
        var value = new JsonObject();
        value.addProperty("brokerRevision", 1);
        value.addProperty("appRevision", APP_REVISION);
        value.add("features", new JsonArray());
        return value;
    }

    private static void exact(JsonObject value, String... fields) throws IOException { require(value.keySet().equals(Set.of(fields))); }
    private static void identifier(JsonObject value, String field) throws IOException {
        require(value.get(field).getAsString().matches("[A-Za-z0-9](?:[A-Za-z0-9._~-]{0,126}[A-Za-z0-9])?"));
    }
    private static String nonce() { var bytes = new byte[24]; RANDOM.nextBytes(bytes); return "b_" + encode(bytes) + "_0"; }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String digest(byte[] bytes) throws GeneralSecurityException { return encode(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void require(boolean condition) throws IOException { if (!condition) throw authenticationError(); }
    private static HunkAuthenticationException authenticationError() { return new HunkAuthenticationException("Hunk local authentication could not be verified. Check the daemon version and local credentials, then retry."); }

    /** Enforces the upstream 8 MiB limit before allocating/decoding an untrusted response. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (limit == 0) { subscription.cancel(); result.completeExceptionally(authenticationError()); }
            else subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (chunk.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(authenticationError()); return;
                }
                var content = new byte[chunk.remaining()]; chunk.get(content); bytes.writeBytes(content);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(authenticationError()); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
