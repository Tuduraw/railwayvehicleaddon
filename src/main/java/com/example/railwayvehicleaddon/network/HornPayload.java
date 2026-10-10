package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** クライアント→サーバー: 運転中の鉄道車両の警笛・汽笛を鳴らす(警笛キー)。 */
public record HornPayload() implements CustomPayload {

	public static final CustomPayload.Id<HornPayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "horn"));

	public static final PacketCodec<RegistryByteBuf, HornPayload> CODEC = PacketCodec.unit(new HornPayload());

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
