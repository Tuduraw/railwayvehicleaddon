package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.operation.Timetable;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** クライアント→サーバー: 経路アイテムの設定画面で編集した運行表を、手に持っている経路アイテムへ保存する。 */
public record RouteCardUpdatePayload(boolean mainHand, Timetable table) implements CustomPayload {

	public static final CustomPayload.Id<RouteCardUpdatePayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "route_card_update"));

	public static final PacketCodec<RegistryByteBuf, RouteCardUpdatePayload> CODEC = PacketCodec.tuple(
			PacketCodecs.BOOLEAN, RouteCardUpdatePayload::mainHand,
			Timetable.PACKET_CODEC, RouteCardUpdatePayload::table,
			RouteCardUpdatePayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
