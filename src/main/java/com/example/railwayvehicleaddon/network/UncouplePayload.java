package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** クライアント→サーバー: 乗車中の鉄道車両の連結を外す(連結解放キー)。どちらの端を外すかはサーバーが決める。 */
public record UncouplePayload() implements CustomPayload {

	public static final CustomPayload.Id<UncouplePayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "uncouple"));

	public static final PacketCodec<RegistryByteBuf, UncouplePayload> CODEC = PacketCodec.unit(new UncouplePayload());

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
