package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** クライアント→サーバー: 運転中の鉄道車両の警笛・汽笛を鳴らす(警笛キー)。 */
public record HornPayload() implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<HornPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "horn"));

	public static final StreamCodec<RegistryFriendlyByteBuf, HornPayload> CODEC = StreamCodec.unit(new HornPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
