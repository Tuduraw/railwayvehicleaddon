package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 停止位置目標の名前の編集。サーバー→クライアント: 右クリックした停止位置目標の今の名前を送り、編集画面を開かせる。
 * クライアント→サーバー: 編集画面で決めた名前を送る(同じ形なので1つの型を両方向に使う)。
 */
public record StopMarkerEditPayload(BlockPos pos, String name) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<StopMarkerEditPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "stop_marker_edit"));

	public static final StreamCodec<RegistryFriendlyByteBuf, StopMarkerEditPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, StopMarkerEditPayload::pos,
			ByteBufCodecs.stringUtf8(64), StopMarkerEditPayload::name,
			StopMarkerEditPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
