package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * クライアント→サーバー: 鉄道車両の車体(当たり判定の外も含む車体全体)を右クリックして乗る。
 * 前提MODの当たり判定は車両の中心付近の小さな箱だけなので、長い客車のどこを右クリックしても乗れるよう、
 * クライアントが車体の箱との交点(hx, hy, hz。ワールド座標)を送り、サーバーがその近くの空いた座席を選ぶ。
 */
public record BoardPayload(int entityId, double hx, double hy, double hz) implements CustomPayload {

	public static final CustomPayload.Id<BoardPayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "board"));

	public static final PacketCodec<RegistryByteBuf, BoardPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, BoardPayload::entityId,
			PacketCodecs.DOUBLE, BoardPayload::hx,
			PacketCodecs.DOUBLE, BoardPayload::hy,
			PacketCodecs.DOUBLE, BoardPayload::hz,
			BoardPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
