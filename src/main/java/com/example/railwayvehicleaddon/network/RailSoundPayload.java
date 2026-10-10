package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * サーバー→クライアント: 鉄道車両の一回きりの音(サーバーで起きた出来事の音)。
 * kindは "horn"(警笛)・"couple"(連結・解放)。どの音を鳴らすかは車両の音の設定(SOUND_SPEC)からクライアントが決める。
 */
public record RailSoundPayload(int entityId, String kind) implements CustomPayload {

	public static final CustomPayload.Id<RailSoundPayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "rail_sound"));

	public static final PacketCodec<RegistryByteBuf, RailSoundPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, RailSoundPayload::entityId,
			PacketCodecs.STRING, RailSoundPayload::kind,
			RailSoundPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
