package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.operation.Timetable;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** クライアント→サーバー: 経路アイテムの設定画面で編集した運行表を、手に持っている経路アイテムへ保存する。 */
public record RouteCardUpdatePayload(boolean mainHand, Timetable table) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<RouteCardUpdatePayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "route_card_update"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RouteCardUpdatePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, RouteCardUpdatePayload::mainHand,
			Timetable.PACKET_CODEC, RouteCardUpdatePayload::table,
			RouteCardUpdatePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
