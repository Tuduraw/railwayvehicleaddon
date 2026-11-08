package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** クライアント→サーバー: 撤去モードで選択した線路区間・設備の撤去。 */
public record RemoveSegmentsPayload(List<Long> segmentIds, List<Long> featureIds) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<RemoveSegmentsPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "remove_segments"));

	private static final int HARD_LIMIT = 256;

	public static final StreamCodec<RegistryFriendlyByteBuf, RemoveSegmentsPayload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeVarInt(payload.segmentIds().size());
				payload.segmentIds().forEach(buf::writeLong);
				buf.writeVarInt(payload.featureIds().size());
				payload.featureIds().forEach(buf::writeLong);
			},
			buf -> {
				int count = Math.min(buf.readVarInt(), HARD_LIMIT);
				List<Long> ids = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					ids.add(buf.readLong());
				}
				int featureCount = Math.min(buf.readVarInt(), HARD_LIMIT);
				List<Long> features = new ArrayList<>(featureCount);
				for (int i = 0; i < featureCount; i++) {
					features.add(buf.readLong());
				}
				return new RemoveSegmentsPayload(ids, features);
			});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
