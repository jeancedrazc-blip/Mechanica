package mcjty.rftoolsbuilder.constructor;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import mcjty.rftoolsbuilder.constructor.client.MaterialListTabletClient;

public final class ConstructorNetworking {
    private ConstructorNetworking() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("3");
        registrar.playToServer(BeginUpload.TYPE, BeginUpload.STREAM_CODEC, ConstructorNetworking::handleBegin);
        registrar.playToServer(UploadChunk.TYPE, UploadChunk.STREAM_CODEC, ConstructorNetworking::handleChunk);
        registrar.playToServer(FinishUpload.TYPE, FinishUpload.STREAM_CODEC, ConstructorNetworking::handleFinish);
        registrar.playToServer(CancelUpload.TYPE, CancelUpload.STREAM_CODEC, ConstructorNetworking::handleCancel);
        registrar.playToServer(SyncDeployment.TYPE, SyncDeployment.STREAM_CODEC, ConstructorNetworking::handleDeployment);
        registrar.playToServer(RequestTabletMaterials.TYPE, RequestTabletMaterials.STREAM_CODEC, ConstructorNetworking::handleTabletRequest);
        registrar.playToClient(SyncTabletMaterials.TYPE, SyncTabletMaterials.STREAM_CODEC, ConstructorNetworking::handleTabletSync);
    }

    private static ServerPlayer serverPlayer(IPayloadContext context) {
        return context.player() instanceof ServerPlayer player ? player : null;
    }

    private static void handleBegin(BeginUpload payload, IPayloadContext context) {
        ServerPlayer player = serverPlayer(context);
        if (player != null) SchematicUploadManager.begin(player, payload.tablePos(), payload.fileName(), payload.format(), payload.size(), payload.sha256());
    }

    private static void handleChunk(UploadChunk payload, IPayloadContext context) {
        ServerPlayer player = serverPlayer(context);
        if (player != null) SchematicUploadManager.chunk(player, payload.tablePos(), payload.data());
    }

    private static void handleFinish(FinishUpload payload, IPayloadContext context) {
        ServerPlayer player = serverPlayer(context);
        if (player != null) SchematicUploadManager.finish(player, payload.tablePos());
    }

    private static void handleCancel(CancelUpload payload, IPayloadContext context) {
        ServerPlayer player = serverPlayer(context);
        if (player != null) SchematicUploadManager.cancel(player, payload.tablePos(), false, "Upload cancelled");
    }

    private static void handleDeployment(SyncDeployment payload, IPayloadContext context) {
        ServerPlayer player = serverPlayer(context);
        if (player == null) return;
        int slot = payload.slot();
        if (slot < 0 || slot >= 9 || slot != player.getInventory().getSelectedSlot()) return;
        ItemStack stack = player.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof SchematicCardItem) || !SchematicCardItem.hasSource(stack) || !SchematicCardItem.hasBounds(stack)) return;
        String expectedHash = SchematicCardItem.sha256(stack);
        if (!expectedHash.isBlank() && !expectedHash.equals(payload.sha256())) return;
        BlockPos anchor = payload.deployed() ? payload.anchor() : BlockPos.ZERO;
        if (payload.deployed()) {
            if (Math.abs((long) anchor.getX()) > 30_000_000L || Math.abs((long) anchor.getZ()) > 30_000_000L) return;
            if (anchor.getY() < -2048 || anchor.getY() > 2048) return;
            double allowed = 96.0 + Math.max(SchematicCardItem.sizeX(stack), SchematicCardItem.sizeZ(stack));
            if (player.distanceToSqr(anchor.getX() + .5, anchor.getY() + .5, anchor.getZ() + .5) > allowed * allowed) return;
        }
        SchematicCardItem.setDeployment(stack, anchor, payload.rotation(), payload.mirror(), payload.deployed());
        player.getInventory().setChanged();
    }

    private static void handleTabletRequest(RequestTabletMaterials payload, IPayloadContext context) {
        ServerPlayer player = serverPlayer(context);
        if (player == null || payload.hand() < 0 || payload.hand() > 1) return;
        net.minecraft.world.InteractionHand hand = payload.hand() == 0
                ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND;
        ItemStack tablet = player.getItemInHand(hand);
        if (!(tablet.getItem() instanceof MaterialListTabletItem)) return;
        String status = MaterialListTabletItem.refreshFromLinkedConstructor(player.level(), tablet, player.blockPosition());
        CustomData data = tablet.get(DataComponents.CUSTOM_DATA);
        if (data == null) return;
        var tag = data.copyTag();
        PacketDistributor.sendToPlayer(player, new SyncTabletMaterials(
                tag.getString("QTSchematicName").orElse("-"),
                tag.getIntOr("QTMaterialTotal", 0),
                tag.getString("QTMaterials").orElse(""),
                status
        ));
    }

    private static void handleTabletSync(SyncTabletMaterials payload, IPayloadContext context) {
        MaterialListTabletClient.receive(payload.schematicName(), payload.total(), payload.materials(), payload.status());
    }

    public record BeginUpload(BlockPos tablePos, String fileName, String format, long size, String sha256) implements CustomPacketPayload {
        public static final Type<BeginUpload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ConstructorBootstrap.MOD_ID, "schematic_upload_begin"));
        public static final StreamCodec<ByteBuf, BeginUpload> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, BeginUpload::tablePos,
                ByteBufCodecs.stringUtf8(512), BeginUpload::fileName,
                ByteBufCodecs.stringUtf8(32), BeginUpload::format,
                ByteBufCodecs.VAR_LONG, BeginUpload::size,
                ByteBufCodecs.stringUtf8(64), BeginUpload::sha256,
                BeginUpload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record UploadChunk(BlockPos tablePos, byte[] data) implements CustomPacketPayload {
        public static final Type<UploadChunk> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ConstructorBootstrap.MOD_ID, "schematic_upload_chunk"));
        public static final StreamCodec<ByteBuf, UploadChunk> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, UploadChunk::tablePos,
                ByteBufCodecs.byteArray(SchematicUploadManager.MAX_CHUNK_BYTES), UploadChunk::data,
                UploadChunk::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record FinishUpload(BlockPos tablePos) implements CustomPacketPayload {
        public static final Type<FinishUpload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ConstructorBootstrap.MOD_ID, "schematic_upload_finish"));
        public static final StreamCodec<ByteBuf, FinishUpload> STREAM_CODEC = BlockPos.STREAM_CODEC.map(FinishUpload::new, FinishUpload::tablePos);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record CancelUpload(BlockPos tablePos) implements CustomPacketPayload {
        public static final Type<CancelUpload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ConstructorBootstrap.MOD_ID, "schematic_upload_cancel"));
        public static final StreamCodec<ByteBuf, CancelUpload> STREAM_CODEC = BlockPos.STREAM_CODEC.map(CancelUpload::new, CancelUpload::tablePos);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SyncDeployment(int slot, BlockPos anchor, int rotation, int mirror, boolean deployed, String sha256) implements CustomPacketPayload {
        public static final Type<SyncDeployment> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ConstructorBootstrap.MOD_ID, "schematic_deployment"));
        public static final StreamCodec<ByteBuf, SyncDeployment> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SyncDeployment::slot,
                BlockPos.STREAM_CODEC, SyncDeployment::anchor,
                ByteBufCodecs.VAR_INT, SyncDeployment::rotation,
                ByteBufCodecs.VAR_INT, SyncDeployment::mirror,
                ByteBufCodecs.BOOL, SyncDeployment::deployed,
                ByteBufCodecs.stringUtf8(64), SyncDeployment::sha256,
                SyncDeployment::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RequestTabletMaterials(int hand) implements CustomPacketPayload {
        public static final Type<RequestTabletMaterials> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ConstructorBootstrap.MOD_ID, "tablet_material_request"));
        public static final StreamCodec<ByteBuf, RequestTabletMaterials> STREAM_CODEC = ByteBufCodecs.VAR_INT
                .map(RequestTabletMaterials::new, RequestTabletMaterials::hand);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SyncTabletMaterials(String schematicName, int total, String materials, String status) implements CustomPacketPayload {
        public static final Type<SyncTabletMaterials> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ConstructorBootstrap.MOD_ID, "tablet_material_sync"));
        public static final StreamCodec<ByteBuf, SyncTabletMaterials> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(512), SyncTabletMaterials::schematicName,
                ByteBufCodecs.VAR_INT, SyncTabletMaterials::total,
                ByteBufCodecs.stringUtf8(262144), SyncTabletMaterials::materials,
                ByteBufCodecs.stringUtf8(64), SyncTabletMaterials::status,
                SyncTabletMaterials::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
