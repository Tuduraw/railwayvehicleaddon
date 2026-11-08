package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** クライアント→サーバー: 測量ツールの連結モードで、装置(pos)の連結先をtargetIdに付け替える。 */
public record LinkDevicePayload(BlockPos pos, long targetId) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<LinkDevicePayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "link_device"));

	public static final StreamCodec<RegistryFriendlyByteBuf, LinkDevicePayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, LinkDevicePayload::pos,
			ByteBufCodecs.VAR_LONG, LinkDevicePayload::targetId,
			LinkDevicePayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
