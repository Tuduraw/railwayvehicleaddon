package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 停止位置目標の名前の編集。サーバー→クライアント: 右クリックした停止位置目標の今の名前を送り、編集画面を開かせる。
 * クライアント→サーバー: 編集画面で決めた名前を送る(同じ形なので1つの型を両方向に使う)。
 */
public record StopMarkerEditPayload(BlockPos pos, String name) implements CustomPayload {

	public static final CustomPayload.Id<StopMarkerEditPayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "stop_marker_edit"));

	public static final PacketCodec<RegistryByteBuf, StopMarkerEditPayload> CODEC = PacketCodec.tuple(
			BlockPos.PACKET_CODEC, StopMarkerEditPayload::pos,
			PacketCodecs.string(64), StopMarkerEditPayload::name,
			StopMarkerEditPayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
