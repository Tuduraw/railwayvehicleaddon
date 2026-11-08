package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** クライアント→サーバー: 乗っている編成のATOを入れる・切る(ATOキー)。 */
public record AtoTogglePayload() implements CustomPayload {

	public static final CustomPayload.Id<AtoTogglePayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "ato_toggle"));

	public static final PacketCodec<RegistryByteBuf, AtoTogglePayload> CODEC = PacketCodec.unit(new AtoTogglePayload());

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
