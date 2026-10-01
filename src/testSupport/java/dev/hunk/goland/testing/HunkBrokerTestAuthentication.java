package dev.hunk.goland.testing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent server-side v0.23 protocol fixture. Never shares production transcript builders. */
public final class HunkBrokerTestAuthentication {
    private static final String DOMAIN = "dev.hunk.session-broker.v1/";
    private final KeyPair daemon;
    private final KeyPair caller;
    private final JsonObject grant;
    private final Map<String, JsonObject> hellos = new ConcurrentHashMap<>();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<HttpExchange, JsonObject> pendingResponses = new ConcurrentHashMap<>();
    public final AtomicInteger challenges = new AtomicInteger();
    public final AtomicInteger proofs = new AtomicInteger();
    public final List<String> sequences = new CopyOnWriteArrayList<>();
    public volatile String fault = "";
    public volatile boolean rejectNext = false;
    public final Path directory;

    public HunkBrokerTestAuthentication(Path runtime) throws Exception {
        directory = Files.createDirectories(runtime.resolve("hunk-mcp/security-v1"));
        for (var path : List.of(runtime, directory.getParent(), directory))
            if (Files.getFileStore(path).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        var generator = KeyPairGenerator.getInstance("Ed25519");
        daemon = generator.generateKeyPair(); caller = generator.generateKeyPair();
        grant = object("kind", "caller", "appId", "dev.hunk", "principalId", "hunk-caller", "keyId", "h_caller_0",
                "grantId", "hunk-caller-bootstrap-v1", "algorithm", "Ed25519", "issuedAt", System.currentTimeMillis() - 1000,
                "expiresAt", System.currentTimeMillis() + 86_400_000, "revocationId", "hunk-caller-bootstrap-v1", "mayDelegate", false);
        var operations = new JsonArray(); List.of("list", "get", "dispatch", "diagnostics").forEach(operations::add);
        grant.add("operations", operations);
        var commands = new JsonArray();
        for (var name : List.of("navigate_to_hunk", "reload_session", "comment", "comment_batch", "remove_comment",
                "clear_comments", "highlight", "clear_highlights")) commands.add(object("name", name, "version", 1));
        grant.add("commands", commands);
        store("daemon", "h_daemon_0", daemon, null);
        store("caller", "h_caller_0", caller, grant);
    }

    private void store(String role, String keyId, KeyPair pair, JsonObject grant) throws IOException {
        var record = object("version", 1, "role", role, "keyId", keyId,
                "publicKey", encode(pair.getPublic().getEncoded()), "privateKey", encode(pair.getPrivate().getEncoded()));
        if (grant != null) record.add("grant", grant);
        var path = directory.resolve(role + ".json");
        Files.writeString(path, record.toString());
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    public void install(HttpServer server) {
        server.createContext("/session-auth/challenge", exchange -> safe(exchange, () -> {
            challenges.incrementAndGet();
            var hello = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!hello.get("role").getAsString().equals("caller") || hello.getAsJsonObject("proposal").get("appRevision").getAsInt() != 16)
                throw new IOException("Unexpected client contract");
            var challengeId = "challenge-" + challenges.get();
            var transcript = object("appId", "dev.hunk", "domain", DOMAIN + "caller-hello",
                    "generation", "generation-test", "responderNonce", "responder-test");
            for (var field : List.of("endpoint", "grantId", "initiatorNonce", "keyId", "proposal")) transcript.add(field, hello.get(field));
            hellos.put(challengeId, transcript);
            var response = object("challengeId", challengeId, "generation", "generation-test", "responderNonce", "responder-test",
                    "expiresAt", System.currentTimeMillis() + 30_000, "daemonKeyId", "h_daemon_0", "daemonSignature", sign(transcript));
            if (fault.equals("challenge")) response.addProperty("generation", "other-generation");
            unsigned(exchange, response, 200);
        }));
        server.createContext("/session-auth/proof", exchange -> safe(exchange, () -> {
            proofs.incrementAndGet();
            var proof = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            var transcript = hellos.remove(proof.get("challengeId").getAsString());
            verify(transcript, proof.get("signature").getAsString());
            var hash = digest(canonical(transcript));
            var sessionId = "caller-" + proofs.get();
            sessions.put(sessionId, hash);
            var principal = new JsonObject();
            for (var field : List.of("kind", "appId", "principalId", "keyId", "grantId", "operations", "commands")) principal.add(field, grant.get(field));
            var ackTranscript = object("appId", "dev.hunk", "callerSessionId", sessionId, "initialSequence", "1",
                    "domain", DOMAIN + "caller-hello-ack", "generation", "generation-test", "grantId", "hunk-caller-bootstrap-v1",
                    "helloTranscriptHash", hash, "keyId", "h_caller_0");
            ackTranscript.add("selection", selection());
            var response = object("callerSessionId", sessionId, "expiresAt", System.currentTimeMillis() + 30_000,
                    "initialSequence", "1", "brokerRevision", 1, "appRevision", 16, "helloTranscriptHash", hash,
                    "daemonKeyId", "h_daemon_0", "daemonSignature", sign(ackTranscript));
            response.add("principal", principal); response.add("features", new JsonArray());
            if (fault.equals("ack")) principal.addProperty("principalId", "wrong-principal");
            if (fault.equals("expired")) response.addProperty("expiresAt", System.currentTimeMillis() - 1);
            unsigned(exchange, response, 200);
        }));
    }

    /** Called with the exact bytes received before a fixture handles a stock control request. */
    public boolean authorize(HttpExchange exchange, byte[] body, boolean targetSpecific) throws IOException {
        try {
            var headers = exchange.getRequestHeaders();
            var sessionId = headers.getFirst("x-session-broker-caller-session");
            if (fault.equals("reject")) { unsigned(exchange, object("error", "authentication-required"), 401); return false; }
            if (rejectNext) { rejectNext = false; sessions.clear(); unsigned(exchange, object("error", "authentication-required"), 401); return false; }
            if (sessionId == null || !sessions.containsKey(sessionId)) { unsigned(exchange, object("error", "authentication-required"), 401); return false; }
            var requestId = headers.getFirst("x-session-broker-request-id");
            var sequence = headers.getFirst("x-session-broker-sequence");
            var transcript = object("appId", "dev.hunk", "bodyDigest", digest(body), "callerSessionId", sessionId,
                    "domain", DOMAIN + "caller-request", "generation", "generation-test", "grantId", "hunk-caller-bootstrap-v1",
                    "helloTranscriptHash", sessions.get(sessionId), "keyId", "h_caller_0", "method", exchange.getRequestMethod(),
                    "requestId", requestId, "sequence", sequence, "target", exchange.getRequestURI().getRawPath());
            verify(transcript, headers.getFirst("x-session-broker-signature"));
            sequences.add(sequence);
            var binding = object("generation", "generation-test", "brokerRevision", 1, "callerSessionId", sessionId,
                    "requestId", requestId, "sequence", sequence);
            if (targetSpecific) { var contract = object("appRevision", 16); contract.add("features", new JsonArray()); binding.add("appContract", contract); }
            pendingResponses.put(exchange, binding);
            return true;
        } catch (Exception ignored) {
            unsigned(exchange, object("error", "authentication-failed"), 401); return false;
        }
    }

    public void respond(HttpExchange exchange, JsonObject body, int status) throws IOException {
        try {
            var binding = pendingResponses.remove(exchange);
            if (binding == null) { unsigned(exchange, body, status); return; }
            binding.addProperty("httpStatus", status); binding.addProperty("bodyDigest", digest(canonical(body)));
            var transcript = binding.deepCopy();
            transcript.addProperty("appId", "dev.hunk"); transcript.addProperty("domain", DOMAIN + "caller-response");
            binding.addProperty("daemonKeyId", "h_daemon_0"); binding.addProperty("daemonSignature", sign(transcript));
            if (fault.equals("response")) body.addProperty("tampered", true);
            if (fault.equals("binding")) binding.addProperty("requestId", "other-request");
            if (fault.equals("contract")) binding.remove("appContract");
            var envelope = new JsonObject(); envelope.add("body", body); envelope.add("authentication", binding);
            unsigned(exchange, envelope, status);
        } catch (Exception ignored) { throw new IOException("Fixture signing failed (input omitted)"); }
    }

    private String sign(JsonObject value) throws Exception {
        var signature = Signature.getInstance("Ed25519"); signature.initSign(daemon.getPrivate()); signature.update(canonical(value));
        return encode(signature.sign());
    }
    private void verify(JsonObject value, String encoded) throws Exception {
        var signature = Signature.getInstance("Ed25519"); signature.initVerify(caller.getPublic()); signature.update(canonical(value));
        if (!signature.verify(Base64.getUrlDecoder().decode(encoded))) throw new IOException("Fixture verification failed");
    }
    private static byte[] canonical(JsonObject value) throws IOException { return new JsonCanonicalizer(value.toString()).getEncodedUTF8(); }
    private static String digest(byte[] bytes) throws Exception { return encode(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static JsonObject selection() { var value = object("appRevision", 16, "brokerRevision", 1); value.add("features", new JsonArray()); return value; }
    public static JsonObject object(Object... pairs) {
        var value = new JsonObject();
        for (int index = 0; index < pairs.length; index += 2) {
            var name = (String) pairs[index]; var item = pairs[index + 1];
            if (item instanceof Number number) value.addProperty(name, number);
            else if (item instanceof Boolean flag) value.addProperty(name, flag);
            else value.addProperty(name, (String) item);
        }
        return value;
    }
    public static void unsigned(HttpExchange exchange, JsonObject value, int status) throws IOException {
        var bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }
    private interface Action { void run() throws Exception; }
    private static void safe(HttpExchange exchange, Action action) throws IOException {
        try { action.run(); } catch (Exception ignored) { unsigned(exchange, object("error", "fixture-authentication-failed"), 401); }
    }
}
