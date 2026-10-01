package dev.hunk.goland.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;

/** Reads the same owner-private bootstrap caller and daemon verifier as stock Hunk 0.23. */
final class HunkBrokerCredentials {
    final JsonObject grant;
    final PrivateKey callerKey;
    final String daemonKeyId;
    final PublicKey daemonKey;

    private HunkBrokerCredentials(JsonObject grant, PrivateKey callerKey, String daemonKeyId, PublicKey daemonKey) {
        this.grant = grant;
        this.callerKey = callerKey;
        this.daemonKeyId = daemonKeyId;
        this.daemonKey = daemonKey;
    }

    static Path defaultDirectory() {
        var runtime = System.getenv("XDG_RUNTIME_DIR");
        var base = runtime != null && !runtime.isBlank() ? Path.of(runtime.trim())
                : System.getProperty("os.name").startsWith("Windows")
                ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(System.getProperty("user.home"), ".hunk");
        return base.resolve("hunk-mcp/security-v1");
    }

    static HunkBrokerCredentials load(Path directory) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.notExists(directory.resolve("caller.json"), LinkOption.NOFOLLOW_LINKS)
                || Files.notExists(directory.resolve("daemon.json"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Hunk's owner-private credentials have not been created. "
                    + "Open a review or start the installed Hunk 0.23 daemon as the same OS user.");
        try {
            checkDirectory(directory.getParent(), false);
            checkDirectory(directory, true);
            var caller = read(directory.resolve("caller.json"));
            var daemon = read(directory.resolve("daemon.json"));
            require(caller.get("version").getAsInt() == 1 && caller.get("role").getAsString().equals("caller"));
            require(daemon.get("version").getAsInt() == 1 && daemon.get("role").getAsString().equals("daemon"));
            var grant = caller.getAsJsonObject("grant");
            require(grant.get("kind").getAsString().equals("caller"));
            require(grant.get("appId").getAsString().equals("dev.hunk"));
            require(grant.get("algorithm").getAsString().equals("Ed25519"));
            require(grant.get("keyId").equals(caller.get("keyId")));
            require(!grant.get("mayDelegate").getAsBoolean());
            require(grant.get("issuedAt").getAsLong() < grant.get("expiresAt").getAsLong());
            require(grant.get("expiresAt").getAsLong() > System.currentTimeMillis());
            require(caller.get("keyId").getAsString().matches("h_[A-Za-z0-9_-]+_0"));
            require(daemon.get("keyId").getAsString().matches("h_[A-Za-z0-9_-]+_0"));
            var factory = KeyFactory.getInstance("Ed25519");
            return new HunkBrokerCredentials(grant.deepCopy(),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(decode(caller.get("privateKey").getAsString()))),
                    daemon.get("keyId").getAsString(),
                    factory.generatePublic(new X509EncodedKeySpec(decode(daemon.get("publicKey").getAsString()))));
        } catch (Exception ignored) {
            // Parsing/key-provider errors may include secret input. Never retain their messages or causes.
            throw new HunkAuthenticationException("Cannot load Hunk's owner-private local credentials. Start the installed Hunk 0.23 daemon "
                    + "as the same OS user as GoLand; check ownership and permissions of its security-v1 directory.");
        }
    }

    private static JsonObject read(Path file) throws IOException {
        require(!Files.isSymbolicLink(file));
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            var attributes = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            checkOwner(file, attributes);
            require(attributes.isRegularFile());
            require(attributes.permissions().stream().allMatch(Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)::contains));
        }
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            require(channel.size() > 0 && channel.size() <= 64 * 1024);
            var bytes = ByteBuffer.allocate(64 * 1024 + 1);
            while (channel.read(bytes) != -1) require(bytes.hasRemaining());
            bytes.flip();
            return JsonParser.parseString(StandardCharsets.UTF_8.newDecoder().decode(bytes).toString()).getAsJsonObject();
        }
    }

    private static void checkDirectory(Path directory, boolean privateDirectory) throws IOException {
        require(!Files.isSymbolicLink(directory));
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS));
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            var attributes = Files.readAttributes(directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            checkOwner(directory, attributes);
            var denied = privateDirectory ? Set.of(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
                    : Set.of(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);
            require(attributes.permissions().stream().noneMatch(denied::contains));
        }
    }

    private static void checkOwner(Path file, PosixFileAttributes attributes) throws IOException {
        var currentUser = file.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        require(attributes.owner().equals(currentUser));
    }

    static byte[] decode(String value) throws IOException {
        try {
            require(!value.isEmpty() && value.matches("[A-Za-z0-9_-]+"));
            var bytes = Base64.getUrlDecoder().decode(value);
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value));
            return bytes;
        } catch (IllegalArgumentException ignored) {
            throw new IOException("Invalid Hunk authentication encoding.");
        }
    }

    private static void require(boolean condition) throws IOException {
        if (!condition) throw new IOException("Unsafe or invalid Hunk credentials.");
    }
}
