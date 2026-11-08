package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** サーバー→クライアント: 線路区間とノードの削除。 */
public record TrackRemovePayload(List<Long> segmentIds, List<Long> nodeIds) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<TrackRemovePayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "track_remove"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TrackRemovePayload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeVarInt(payload.segmentIds().size());
				payload.segmentIds().forEach(buf::writeLong);
				buf.writeVarInt(payload.nodeIds().size());
				payload.nodeIds().forEach(buf::writeLong);
			},
			buf -> {
				int segmentCount = buf.readVarInt();
				List<Long> segments = new ArrayList<>(segmentCount);
				for (int i = 0; i < segmentCount; i++) {
					segments.add(buf.readLong());
				}
				int nodeCount = buf.readVarInt();
				List<Long> nodes = new ArrayList<>(nodeCount);
				for (int i = 0; i < nodeCount; i++) {
					nodes.add(buf.readLong());
				}
				return new TrackRemovePayload(segments, nodes);
			});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
