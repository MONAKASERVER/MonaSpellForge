package net.monacraft.spellforge.fabric;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record BridgePayload(byte[] data) implements CustomPacketPayload {
    public static final Type<BridgePayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("monaspellforge","bridge"));
    public static final StreamCodec<RegistryFriendlyByteBuf,BridgePayload> CODEC=StreamCodec.of((buffer,payload)->buffer.writeBytes(payload.data),buffer->{int length=buffer.readableBytes();if(length<=0||length>30_000)throw new IllegalArgumentException("Invalid bridge payload size: "+length);byte[] data=new byte[length];buffer.readBytes(data);return new BridgePayload(data);});
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
