package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * サーバー→クライアント: 配置確定の結果。クライアントは成功したときだけ点を消し、
 * 失敗(未ロードの範囲を含む等)のときは点をそのまま残して手直しできるようにする。
 */
public record PlaceResultPayload(boolean success) implements CustomPayload {

	public static final CustomPayload.Id<PlaceResultPayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "place_result"));

	public static final PacketCodec<RegistryByteBuf, PlaceResultPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.BOOLEAN, PlaceResultPayload::success,
			PlaceResultPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
