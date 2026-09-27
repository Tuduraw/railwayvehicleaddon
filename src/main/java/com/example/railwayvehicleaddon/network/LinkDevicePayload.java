package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** クライアント→サーバー: 測量ツールの連結モードで、装置(pos)の連結先をtargetIdに付け替える。 */
public record LinkDevicePayload(BlockPos pos, long targetId) implements CustomPayload {

	public static final CustomPayload.Id<LinkDevicePayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "link_device"));

	public static final PacketCodec<RegistryByteBuf, LinkDevicePayload> CODEC = PacketCodec.tuple(
			BlockPos.PACKET_CODEC, LinkDevicePayload::pos,
			PacketCodecs.VAR_LONG, LinkDevicePayload::targetId,
			LinkDevicePayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
