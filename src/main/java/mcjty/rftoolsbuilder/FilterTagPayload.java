package mcjty.rftoolsbuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record FilterTagPayload(int cardSlot, String tag) implements CustomPacketPayload {
    public static final Type<FilterTagPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(RFToolsBuilder.MOD_ID, "filter_tag"));

    public static final StreamCodec<ByteBuf, FilterTagPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FilterTagPayload::cardSlot,
            ByteBufCodecs.STRING_UTF8, FilterTagPayload::tag,
            FilterTagPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FilterTagPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (payload.cardSlot < 0 || payload.cardSlot >= player.getInventory().getContainerSize()) return;
        ItemStack card = player.getInventory().getItem(payload.cardSlot);
        if (!(card.getItem() instanceof QuarryCardItem)) return;
        QuarryCardItem.addFilterTag(card, payload.tag);
        player.containerMenu.broadcastChanges();
    }
}
