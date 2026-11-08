package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** クライアント→サーバー: 乗っている編成のATOを入れる・切る(ATOキー)。 */
public record AtoTogglePayload() implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<AtoTogglePayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "ato_toggle"));

	public static final StreamCodec<RegistryFriendlyByteBuf, AtoTogglePayload> CODEC = StreamCodec.unit(new AtoTogglePayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
