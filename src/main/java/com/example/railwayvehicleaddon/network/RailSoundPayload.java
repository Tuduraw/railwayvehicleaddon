package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * サーバー→クライアント: 鉄道車両の一回きりの音(サーバーで起きた出来事の音)。
 * kindは "horn"(警笛)・"couple"(連結・解放)。どの音を鳴らすかは車両の音の設定(SOUND_SPEC)からクライアントが決める。
 */
public record RailSoundPayload(int entityId, String kind) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<RailSoundPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "rail_sound"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RailSoundPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, RailSoundPayload::entityId,
			ByteBufCodecs.STRING_UTF8, RailSoundPayload::kind,
			RailSoundPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
