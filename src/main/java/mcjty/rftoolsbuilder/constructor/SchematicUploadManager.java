package mcjty.rftoolsbuilder.constructor;

import mcjty.rftoolsbuilder.constructor.plan.ConstructionPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side chunked schematic receiver. The output card is emitted only after checksum and parser validation. */
public final class SchematicUploadManager {
    public static final int MAX_CHUNK_BYTES = 24 * 1024;
    public static final long MAX_FILE_BYTES = 32L * 1024L * 1024L;
    private static final long SESSION_TIMEOUT_NANOS = 60_000_000_000L;

    private record Key(UUID player, long tablePos) {}

    private static final class Session {
        final String displayName;
        final String clientRelativeFile;
        final SchematicFolderIndex.Format format;
        final long totalBytes;
        final String expectedHash;
        final Path tempPath;
        final Path finalPath;
        final String relativeFinalPath;
        final OutputStream stream;
        final MessageDigest digest;
        long received;
        long lastActivityNanos;

        Session(String displayName, String clientRelativeFile, SchematicFolderIndex.Format format,
                long totalBytes, String expectedHash, Path tempPath, Path finalPath,
                String relativeFinalPath, OutputStream stream, MessageDigest digest) {
            this.displayName = displayName;
            this.clientRelativeFile = clientRelativeFile;
            this.format = format;
            this.totalBytes = totalBytes;
            this.expectedHash = expectedHash;
            this.tempPath = tempPath;
            this.finalPath = finalPath;
            this.relativeFinalPath = relativeFinalPath;
            this.stream = stream;
            this.digest = digest;
            touch();
        }

        void touch() { lastActivityNanos = System.nanoTime(); }
    }

    private static final Map<Key, Session> ACTIVE = new ConcurrentHashMap<>();

    private SchematicUploadManager() {}

    public static void begin(ServerPlayer player, BlockPos tablePos, String rawClientFile, String formatId, long size, String sha256) {
        cleanupExpired();
        SchematicTableBlockEntity table = table(player, tablePos);
        if (table == null) return;

        SchematicFolderIndex.Format format = SchematicFolderIndex.Format.fromId(formatId);
        String clientRelative = sanitizeClientRelativePath(rawClientFile);
        String safeName = clientRelative == null ? null : sanitizeFileName(Path.of(clientRelative).getFileName().toString());
        String expected = normalizeSha256(sha256);
        if (format == null || clientRelative == null || safeName == null || !safeName.toLowerCase().endsWith(format.extension())) {
            fail(player, table, "Unsupported or unsafe schematic path/format");
            return;
        }
        if (size <= 0 || size > MAX_FILE_BYTES) {
            fail(player, table, "Schematic file size is outside the 32 MiB limit");
            return;
        }
        if (expected == null) {
            fail(player, table, "Invalid schematic checksum");
            return;
        }
        if (!table.beginUpload(player, safeName)) {
            player.sendSystemMessage(Component.literal("Schematic Table is not ready: insert a card and clear the output slot."));
            return;
        }

        Key key = new Key(player.getUUID(), tablePos.asLong());
        Session previous = ACTIVE.remove(key);
        closeAndDelete(previous);

        try {
            Path root = Path.of("schematics").toAbsolutePath().normalize();
            Path playerDir = root.resolve("quantumtools_uploaded").resolve(player.getUUID().toString()).normalize();
            if (!playerDir.startsWith(root)) throw new IOException("Invalid schematic storage path");
            Files.createDirectories(playerDir);

            String storedName = expected.substring(0, 12) + "-" + safeName;
            Path finalPath = playerDir.resolve(storedName).normalize();
            Path tempPath = playerDir.resolve("." + storedName + ".upload").normalize();
            if (!finalPath.startsWith(playerDir) || !tempPath.startsWith(playerDir)) throw new IOException("Invalid schematic file path");
            Files.deleteIfExists(tempPath);
            OutputStream stream = Files.newOutputStream(tempPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String relative = root.relativize(finalPath).toString().replace('\\', '/');
            ACTIVE.put(key, new Session(safeName, clientRelative, format, size, expected, tempPath, finalPath, relative, stream, digest));
        } catch (IOException | NoSuchAlgorithmException exception) {
            table.cancelUpload(true);
            player.sendSystemMessage(Component.literal("Could not start schematic upload: " + exception.getMessage()));
        }
    }

    public static void chunk(ServerPlayer player, BlockPos tablePos, byte[] data) {
        cleanupExpired();
        if (data == null || data.length == 0 || data.length > MAX_CHUNK_BYTES) {
            cancel(player, tablePos, true, "Invalid schematic packet size");
            return;
        }
        Key key = new Key(player.getUUID(), tablePos.asLong());
        Session session = ACTIVE.get(key);
        SchematicTableBlockEntity table = table(player, tablePos);
        if (session == null || table == null || !table.isUploadOwner(player)) {
            cancel(player, tablePos, true, "No active schematic upload");
            return;
        }
        if (session.received + data.length > session.totalBytes) {
            cancel(player, tablePos, true, "Schematic upload exceeded declared size");
            return;
        }
        try {
            session.stream.write(data);
            session.digest.update(data);
            session.received += data.length;
            session.touch();
            table.updateUploadProgress(session.received, session.totalBytes);
        } catch (IOException exception) {
            cancel(player, tablePos, true, "I/O error while receiving schematic");
        }
    }

    public static void finish(ServerPlayer player, BlockPos tablePos) {
        cleanupExpired();
        Key key = new Key(player.getUUID(), tablePos.asLong());
        Session session = ACTIVE.remove(key);
        SchematicTableBlockEntity table = table(player, tablePos);
        if (session == null || table == null || !table.isUploadOwner(player)) {
            closeAndDelete(session);
            if (table != null) table.cancelUpload(true);
            return;
        }
        try {
            session.stream.close();
            if (session.received != session.totalBytes) throw new IOException("Upload ended before all bytes arrived");
            String actual = HexFormat.of().formatHex(session.digest.digest());
            if (!actual.equals(session.expectedHash)) throw new IOException("SHA-256 checksum mismatch");

            Files.move(session.tempPath, session.finalPath, StandardCopyOption.REPLACE_EXISTING);
            ConstructionPlan plan = UniversalSchematicLoader.load(new SchematicFolderIndex.Entry(session.relativeFinalPath, session.format), false);
            if (plan.totalTargets() <= 0 || plan.sizeX() <= 0 || plan.sizeY() <= 0 || plan.sizeZ() <= 0) {
                throw new IOException("Schematic contains no printable targets or bounds");
            }

            table.finishUpload(session.displayName, session.relativeFinalPath, session.clientRelativeFile,
                    session.format.id(), actual, plan.sizeX(), plan.sizeY(), plan.sizeZ());
            player.sendSystemMessage(Component.literal("Schematic written: " + session.displayName));
        } catch (Exception exception) {
            try { Files.deleteIfExists(session.tempPath); } catch (IOException ignored) {}
            try { Files.deleteIfExists(session.finalPath); } catch (IOException ignored) {}
            table.cancelUpload(true);
            player.sendSystemMessage(Component.literal("Schematic rejected: " + safeMessage(exception)));
        }
    }

    public static void cancel(ServerPlayer player, BlockPos tablePos, boolean error, String reason) {
        Key key = new Key(player.getUUID(), tablePos.asLong());
        Session session = ACTIVE.remove(key);
        closeAndDelete(session);
        SchematicTableBlockEntity table = table(player, tablePos);
        if (table != null && table.isUploadOwner(player)) table.cancelUpload(error);
        if (reason != null && !reason.isBlank()) player.sendSystemMessage(Component.literal(reason));
    }

    private static void cleanupExpired() {
        long now = System.nanoTime();
        for (Map.Entry<Key, Session> entry : ACTIVE.entrySet()) {
            Session session = entry.getValue();
            if (now - session.lastActivityNanos <= SESSION_TIMEOUT_NANOS) continue;
            if (ACTIVE.remove(entry.getKey(), session)) closeAndDelete(session);
        }
    }

    private static SchematicTableBlockEntity table(ServerPlayer player, BlockPos pos) {
        if (player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 64.0) return null;
        return player.level().getBlockEntity(pos) instanceof SchematicTableBlockEntity table ? table : null;
    }

    private static void fail(ServerPlayer player, SchematicTableBlockEntity table, String message) {
        if (table.isUploadOwner(player)) table.cancelUpload(true);
        player.sendSystemMessage(Component.literal(message));
    }

    private static String sanitizeClientRelativePath(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 512) return null;
        try {
            Path path = Path.of(raw.replace('\\', '/')).normalize();
            if (path.isAbsolute() || path.startsWith("..")) return null;
            String normalized = path.toString().replace('\\', '/');
            return normalized.isBlank() || normalized.equals(".") ? null : normalized;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 240) return null;
        String name;
        try { name = Objects.requireNonNull(Path.of(raw).getFileName()).toString(); }
        catch (RuntimeException exception) { return null; }
        if (!name.equals(raw) || name.equals(".") || name.equals("..")) return null;
        String safe = name.replaceAll("[^\\p{L}\\p{N}._() -]", "_");
        return safe.isBlank() ? null : safe;
    }

    private static String normalizeSha256(String hash) {
        if (hash == null) return null;
        String value = hash.trim().toLowerCase();
        return value.matches("[0-9a-f]{64}") ? value : null;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static void closeAndDelete(Session session) {
        if (session == null) return;
        try { session.stream.close(); } catch (IOException ignored) {}
        try { Files.deleteIfExists(session.tempPath); } catch (IOException ignored) {}
    }
}
