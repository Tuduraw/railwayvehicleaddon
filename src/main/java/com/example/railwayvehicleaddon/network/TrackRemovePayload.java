package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** サーバー→クライアント: 線路区間とノードの削除。 */
public record TrackRemovePayload(List<Long> segmentIds, List<Long> nodeIds) implements CustomPayload {

	public static final CustomPayload.Id<TrackRemovePayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "track_remove"));

	public static final PacketCodec<RegistryByteBuf, TrackRemovePayload> CODEC = PacketCodec.ofStatic(
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
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
