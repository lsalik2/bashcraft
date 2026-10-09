package net.slk.bashcraft.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.slk.bashcraft.Bashcraft;

import java.util.List;

/** Server → client: the alias names this player may call, so the chat box can colour alias calls (spec C4 §5). */
public record AliasNamesPayload(List<String> names) implements CustomPacketPayload {
    public static final Type<AliasNamesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Bashcraft.MOD_ID, "aliases"));
    public static final StreamCodec<ByteBuf, AliasNamesPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(256)), AliasNamesPayload::names,
            AliasNamesPayload::new);

    @Override
    public Type<AliasNamesPayload> type() {
        return TYPE;
    }
}
