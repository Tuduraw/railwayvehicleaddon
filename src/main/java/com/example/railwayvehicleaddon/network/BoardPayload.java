package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * クライアント→サーバー: 鉄道車両の車体(当たり判定の外も含む車体全体)を右クリックして乗る。
 * 前提MODの当たり判定は車両の中心付近の小さな箱だけなので、長い客車のどこを右クリックしても乗れるよう、
 * クライアントが車体の箱との交点(hx, hy, hz。ワールド座標)を送り、サーバーがその近くの空いた座席を選ぶ。
 */
public record BoardPayload(int entityId, double hx, double hy, double hz) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<BoardPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "board"));

	public static final StreamCodec<RegistryFriendlyByteBuf, BoardPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, BoardPayload::entityId,
			ByteBufCodecs.DOUBLE, BoardPayload::hx,
			ByteBufCodecs.DOUBLE, BoardPayload::hy,
			ByteBufCodecs.DOUBLE, BoardPayload::hz,
			BoardPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
