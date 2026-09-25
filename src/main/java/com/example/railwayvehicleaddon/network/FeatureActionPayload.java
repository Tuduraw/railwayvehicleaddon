package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** クライアント→サーバー: 測量ツールで狙った転車台・遷車台を次(step=-1なら前)の停止位置へ動かす。 */
public record FeatureActionPayload(long featureId, int step) implements CustomPayload {

	public static final CustomPayload.Id<FeatureActionPayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "feature_action"));

	public static final PacketCodec<RegistryByteBuf, FeatureActionPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_LONG, FeatureActionPayload::featureId,
			PacketCodecs.VAR_INT, FeatureActionPayload::step,
			FeatureActionPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
