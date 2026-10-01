package dev.hunk.goland.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.hunk.goland.testing.HunkBrokerTestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

import static dev.hunk.goland.testing.HunkBrokerTestAuthentication.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HunkSessionTransportTest {
    @TempDir Path temporaryDirectory;
    private HunkBrokerTestAuthentication authentication;
    private HttpServer server;
    private HunkSessionTransport transport;
    private URI origin;
    private final CountDownLatch releaseSlowResponse = new CountDownLatch(1);
    private java.util.concurrent.ExecutorService executor;

    @BeforeEach void start() throws Exception {
        authentication = new HunkBrokerTestAuthentication(temporaryDirectory);
        transport = new HunkSessionTransport(authentication.directory);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        authentication.install(server);
        server.createContext("/session-api", exchange -> {
            var bytes = exchange.getRequestBody().readAllBytes();
            var targetSpecific = bytes.length > 0 && !JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject().get("action").getAsString().equals("list");
            if (!authentication.authorize(exchange, bytes, targetSpecific)) return;
            switch (authentication.fault) {
                case "unsigned" -> HunkBrokerTestAuthentication.unsigned(exchange, object("ok", true), 200);
                case "oversized" -> { exchange.sendResponseHeaders(200, 9 * 1024 * 1024); exchange.close(); }
                case "invalid-utf8" -> {
                    exchange.sendResponseHeaders(200, 2);
                    try (var stream = exchange.getResponseBody()) { stream.write(new byte[]{(byte) 0xc0, (byte) 0xaf}); }
                }
                case "slow" -> {
                    try { releaseSlowResponse.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    exchange.close();
                }
                default -> authentication.respond(exchange, object("ok", true, "fraction", 0.000001, "time", 1_797_000_000_123L), 200);
            }
        });
        server.start();
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach void stop() { releaseSlowResponse.countDown(); server.stop(0); transport.close(); executor.shutdownNow(); }

    private HunkSessionTransport.Response get() throws Exception {
        return transport.get(origin.resolve("/session-api/capabilities"), Duration.ofSeconds(2));
    }

    private HunkSessionTransport.Response post(String action) throws Exception {
        return transport.post(origin.resolve("/session-api"), object("action", action), Duration.ofSeconds(2));
    }

    @Test void authenticatesCapabilitiesListAndTargetedActionsWithOneHello() throws Exception {
        assertThat(get().statusCode()).isEqualTo(200);
        assertThat(post("list").body()).contains("\"ok\":true").doesNotContain("authentication");
        assertThat(post("review").statusCode()).isEqualTo(200);
        assertThat(authentication.challenges.get()).isOne();
        assertThat(authentication.proofs.get()).isOne();
        assertThat(authentication.sequences).containsExactly("1", "2", "3");
    }

    @Test void renegotiatesOnceAfterDaemonRejectsExpiredCaller() throws Exception {
        get(); authentication.rejectNext = true;
        assertThat(post("review").statusCode()).isEqualTo(200);
        assertThat(authentication.challenges.get()).isEqualTo(2);
        assertThat(authentication.sequences).containsExactly("1", "1");
    }

    @ParameterizedTest @ValueSource(strings = {"challenge", "ack", "expired"})
    void rejectsTamperedHelloBeforeAnyControlRequest(String fault) {
        authentication.fault = fault;
        assertThatThrownBy(this::get).isInstanceOf(HunkAuthenticationException.class).hasMessageContaining("authentication");
        assertThat(authentication.sequences).isEmpty();
        assertThat(authentication.proofs.get()).isEqualTo(fault.equals("challenge") ? 0 : 1);
    }

    @ParameterizedTest @ValueSource(strings = {"response", "binding", "contract", "unsigned", "oversized", "invalid-utf8"})
    void rejectsTamperedResponseWithoutRepeatingMutation(String fault) {
        authentication.fault = fault;
        assertThatThrownBy(() -> post("comment-rm")).isInstanceOf(IOException.class).hasMessageContaining("authentication");
        assertThat(authentication.sequences).containsExactly("1");
        assertThat(authentication.challenges.get()).isOne();
    }

    @Test void repeatedRejectionStopsAfterOneRenegotiation() {
        authentication.fault = "reject";
        assertThatThrownBy(this::get).isInstanceOf(IOException.class).hasMessageContaining("authentication");
        assertThat(authentication.challenges.get()).isEqualTo(2);
        assertThat(authentication.proofs.get()).isEqualTo(2);
        assertThat(authentication.sequences).isEmpty();
    }

    @Test void timeoutDoesNotRepeatAnAmbiguousMutation() throws Exception {
        get(); authentication.fault = "slow";
        assertThatThrownBy(() -> transport.post(origin.resolve("/session-api"), object("action", "comment-rm"), Duration.ofMillis(200)))
                .isInstanceOf(IOException.class);
        assertThat(authentication.challenges.get()).isOne();
        assertThat(authentication.sequences).containsExactly("1", "2");
    }

    @Test void concurrentRefreshesShareHelloAndUseOrderedSequences() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = IntStream.range(0, 12).mapToObj(_ -> executor.submit(this::get)).toList();
            for (var result : results) assertThat(result.get().statusCode()).isEqualTo(200);
        }
        assertThat(authentication.challenges.get()).isOne();
        assertThat(authentication.sequences).containsExactlyElementsOf(IntStream.rangeClosed(1, 12).mapToObj(Integer::toString).toList());
    }

    @Test void rejectsSymlinkCredentialBeforeConnecting() throws Exception {
        var original = authentication.directory.resolve("caller.json");
        var moved = Files.move(original, authentication.directory.resolve("moved.json"));
        Files.createSymbolicLink(original, moved);
        assertThatThrownBy(this::get).isInstanceOf(HunkAuthenticationException.class).hasMessageContaining("owner-private");
        assertThat(authentication.challenges.get()).isZero();
    }

    @Test void rejectsWorldReadableCredentialBeforeConnecting() throws Exception {
        Files.setPosixFilePermissions(authentication.directory.resolve("caller.json"), PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(this::get).isInstanceOf(HunkAuthenticationException.class).hasMessageContaining("owner-private");
        assertThat(authentication.challenges.get()).isZero();
    }

    @Test void rejectsMissingCredentialWithoutCreatingOne() throws Exception {
        var missing = temporaryDirectory.resolve("absent/security-v1");
        try (var fresh = new HunkSessionTransport(missing)) {
            assertThatThrownBy(() -> fresh.get(origin.resolve("/session-api/capabilities"), Duration.ofSeconds(2)))
                    .isInstanceOf(IOException.class).isNotInstanceOf(HunkAuthenticationException.class)
                    .hasMessageContaining("owner-private");
        }
        assertThat(Files.exists(missing)).isFalse();
        assertThat(authentication.challenges.get()).isZero();
    }

    @Test void usesEcmaScriptNumberFormattingAndUtf16PropertyOrdering() throws Exception {
        var input = JsonParser.parseString("{\"z\":1.0,\"a\":1e-7,\"b\":1e-6,\"c\":1e21,\"😀\":2,\"דּ\":3}");
        assertThat(new String(HunkSessionTransport.canonical(input), StandardCharsets.UTF_8))
                .isEqualTo("{\"a\":1e-7,\"b\":0.000001,\"c\":1e+21,\"z\":1,\"😀\":2,\"דּ\":3}");
    }

    @Test void rejectsLoneSurrogatesInsteadOfSigningReplacementCharacters() {
        var input = new JsonObject(); input.addProperty("invalid", "\uD800");
        assertThatThrownBy(() -> HunkSessionTransport.canonical(input)).isInstanceOf(IOException.class);
    }

    @Test void neverSendsCredentialsToNonLoopbackOrUnexpectedRoutes() {
        assertThatThrownBy(() -> transport.get(URI.create("http://example.com/session-api/capabilities"), Duration.ofSeconds(2)))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> transport.get(origin.resolve("/other"), Duration.ofSeconds(2))).isInstanceOf(IOException.class);
        assertThat(authentication.challenges.get()).isZero();
    }
}
