package net.slk.bashcraft.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.slk.bashcraft.Bashcraft;

/** Server → client on join: this server runs BashCraft, and whether prefix-free chains are on (spec B §2). */
public record HelloPayload(int protocol, boolean transparentMode) implements CustomPacketPayload {
    public static final int PROTOCOL = 1;
    public static final Type<HelloPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Bashcraft.MOD_ID, "hello"));
    public static final StreamCodec<ByteBuf, HelloPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HelloPayload::protocol,
            ByteBufCodecs.BOOL, HelloPayload::transparentMode,
            HelloPayload::new);

    @Override
    public Type<HelloPayload> type() {
        return TYPE;
    }
}
