package mcjty.rftoolsbuilder.constructor.client;

import mcjty.rftoolsbuilder.constructor.ConstructorNetworking;
import mcjty.rftoolsbuilder.constructor.SchematicFolderIndex;
import mcjty.rftoolsbuilder.constructor.SchematicUploadManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/** Create-style client uploader: hash first, then stream one bounded packet each client tick. */
public final class ClientSchematicUploader {
    private static Active active;
    private static int generation;

    private ClientSchematicUploader() {}

    public static void upload(BlockPos tablePos, SchematicFolderIndex.Entry entry) {
        cancel(false);
        Minecraft minecraft = Minecraft.getInstance();
        int request = ++generation;
        CompletableFuture.supplyAsync(() -> prepare(entry)).whenComplete((prepared, error) -> minecraft.execute(() -> {
            if (request != generation) return;
            if (error != null || prepared == null) {
                message("Could not read schematic: " + (error == null ? "unknown error" : safeMessage(error)));
                return;
            }
            try {
                InputStream stream = Files.newInputStream(prepared.path);
                active = new Active(tablePos.immutable(), entry, prepared.size, prepared.sha256, stream);
                ClientPacketDistributor.sendToServer(new ConstructorNetworking.BeginUpload(
                        tablePos, entry.fileName(), entry.format().id(), prepared.size, prepared.sha256));
            } catch (IOException | RuntimeException exception) {
                close(active);
                active = null;
                message("Could not start schematic upload: " + safeMessage(exception));
            }
        }));
    }

    /** Called from ClientTickEvent.Post. Sends at most one packet per tick. */
    public static void tick() {
        Active current = active;
        if (current == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            cancel(true);
            return;
        }

        byte[] buffer = new byte[SchematicUploadManager.MAX_CHUNK_BYTES];
        try {
            int read = current.stream.read(buffer);
            if (read < 0) {
                ClientPacketDistributor.sendToServer(new ConstructorNetworking.FinishUpload(current.tablePos));
                close(current);
                active = null;
                return;
            }
            byte[] packet = read == buffer.length ? buffer : Arrays.copyOf(buffer, read);
            ClientPacketDistributor.sendToServer(new ConstructorNetworking.UploadChunk(current.tablePos, packet));
            current.sent += read;
            if (current.sent >= current.totalBytes) {
                ClientPacketDistributor.sendToServer(new ConstructorNetworking.FinishUpload(current.tablePos));
                close(current);
                active = null;
            }
        } catch (IOException | RuntimeException exception) {
            try { ClientPacketDistributor.sendToServer(new ConstructorNetworking.CancelUpload(current.tablePos)); }
            catch (RuntimeException ignored) {}
            close(current);
            active = null;
            message("Schematic upload failed: " + safeMessage(exception));
        }
    }

    public static boolean isUploading() { return active != null; }

    public static void cancel(boolean notifyServer) {
        generation++;
        Active current = active;
        if (current != null && notifyServer) {
            try { ClientPacketDistributor.sendToServer(new ConstructorNetworking.CancelUpload(current.tablePos)); }
            catch (RuntimeException ignored) {}
        }
        close(current);
        active = null;
    }

    private static Prepared prepare(SchematicFolderIndex.Entry entry) {
        if (entry == null) throw new IllegalArgumentException("No schematic selected");
        Path path = SchematicFolderIndex.resolve(entry.fileName());
        if (path == null || !Files.isRegularFile(path)) throw new IllegalArgumentException("Schematic file is missing");
        try {
            long size = Files.size(path);
            if (size <= 0 || size > SchematicUploadManager.MAX_FILE_BYTES) throw new IllegalArgumentException("Schematic exceeds the 32 MiB limit");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = stream.read(buffer)) >= 0;) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return new Prepared(path, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void close(Active current) {
        if (current == null) return;
        try { current.stream.close(); } catch (IOException ignored) {}
    }

    private static void message(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) minecraft.gui.getChat().addClientSystemMessage(Component.literal(message));
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static final class Active {
        final BlockPos tablePos;
        final SchematicFolderIndex.Entry entry;
        final long totalBytes;
        final String sha256;
        final InputStream stream;
        long sent;

        Active(BlockPos tablePos, SchematicFolderIndex.Entry entry, long totalBytes, String sha256, InputStream stream) {
            this.tablePos = tablePos;
            this.entry = entry;
            this.totalBytes = totalBytes;
            this.sha256 = sha256;
            this.stream = stream;
        }
    }

    private record Prepared(Path path, long size, String sha256) {}
}
