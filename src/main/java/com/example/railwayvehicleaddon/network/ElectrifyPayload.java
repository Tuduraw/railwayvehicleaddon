package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * クライアント→サーバー: 電化モードで選んだ区間の電化方式を切り替える。
 *
 * @param electrification 適用する電化方式(ElectrificationType.id())。選んだ区間が全部すでに
 *                        この方式なら、サーバー側で非電化に戻す(トグル)
 */
public record ElectrifyPayload(List<Long> segmentIds, int electrification, float wireHeight) implements CustomPayload {

	public static final CustomPayload.Id<ElectrifyPayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "electrify"));

	private static final int HARD_LIMIT = 256;

	public static final PacketCodec<RegistryByteBuf, ElectrifyPayload> CODEC = PacketCodec.ofStatic(
			(buf, payload) -> {
				buf.writeVarInt(payload.segmentIds().size());
				payload.segmentIds().forEach(buf::writeLong);
				buf.writeVarInt(payload.electrification());
				buf.writeFloat(payload.wireHeight());
			},
			buf -> {
				int count = Math.min(buf.readVarInt(), HARD_LIMIT);
				List<Long> ids = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					ids.add(buf.readLong());
				}
				int electrification = buf.readVarInt();
				float wireHeight = buf.readFloat();
				return new ElectrifyPayload(ids, electrification, wireHeight);
			});

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
