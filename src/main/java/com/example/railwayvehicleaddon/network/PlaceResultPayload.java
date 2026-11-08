package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * サーバー→クライアント: 配置確定の結果。クライアントは成功したときだけ点を消し、
 * 失敗(未ロードの範囲を含む等)のときは点をそのまま残して手直しできるようにする。
 */
public record PlaceResultPayload(boolean success) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<PlaceResultPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "place_result"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PlaceResultPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, PlaceResultPayload::success,
			PlaceResultPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
