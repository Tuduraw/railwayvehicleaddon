package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** クライアント→サーバー: 測量ツールで狙った分岐器の開通方向を切り替える。 */
public record ToggleSwitchPayload(long nodeId) implements CustomPayload {

	public static final CustomPayload.Id<ToggleSwitchPayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "toggle_switch"));

	public static final PacketCodec<RegistryByteBuf, ToggleSwitchPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_LONG, ToggleSwitchPayload::nodeId,
			ToggleSwitchPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
