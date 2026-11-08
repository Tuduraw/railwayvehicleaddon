package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** クライアント→サーバー: 測量ツールで狙った転車台・遷車台を次(step=-1なら前)の停止位置へ動かす。 */
public record FeatureActionPayload(long featureId, int step) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<FeatureActionPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "feature_action"));

	public static final StreamCodec<RegistryFriendlyByteBuf, FeatureActionPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, FeatureActionPayload::featureId,
			ByteBufCodecs.VAR_INT, FeatureActionPayload::step,
			FeatureActionPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
