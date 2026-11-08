package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** クライアント→サーバー: 測量ツールで狙った分岐器の開通方向を切り替える。 */
public record ToggleSwitchPayload(long nodeId) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<ToggleSwitchPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "toggle_switch"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ToggleSwitchPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, ToggleSwitchPayload::nodeId,
			ToggleSwitchPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
