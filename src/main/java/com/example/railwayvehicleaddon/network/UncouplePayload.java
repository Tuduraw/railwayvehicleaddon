package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** クライアント→サーバー: 乗車中の鉄道車両の連結を外す(連結解放キー)。どちらの端を外すかはサーバーが決める。 */
public record UncouplePayload() implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<UncouplePayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "uncouple"));

	public static final StreamCodec<RegistryFriendlyByteBuf, UncouplePayload> CODEC = StreamCodec.unit(new UncouplePayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
