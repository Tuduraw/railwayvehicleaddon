package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.track.TrackNode;
import com.example.railwayvehicleaddon.track.TrackSegment;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * サーバー→クライアント: 線路ノード・区間の追加(または全量送信)。
 * reset=trueのとき、クライアントは現在の線路データを破棄してから取り込む
 * (ログイン時・ディメンション移動時)。大きな路線網はパケットサイズ上限を避けるため
 * 複数に分割して送られ、2通目以降はreset=false。
 * switchesは分岐器の開通方向(ノードID→区間ID)。切替時はこれだけを入れて送る。
 */
public record TrackSyncPayload(boolean reset, RailwayConfig.Values config, List<TrackNode> nodes,
							   List<TrackSegment> segments, Map<Long, Long> switches) implements CustomPayload {

	public static final CustomPayload.Id<TrackSyncPayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "track_sync"));

	public static final PacketCodec<RegistryByteBuf, TrackSyncPayload> CODEC = PacketCodec.ofStatic(
			(buf, payload) -> {
				buf.writeBoolean(payload.reset());
				TrackCodecs.writeConfig(buf, payload.config());
				buf.writeVarInt(payload.nodes().size());
				for (TrackNode node : payload.nodes()) {
					TrackCodecs.writeNode(buf, node);
				}
				buf.writeVarInt(payload.segments().size());
				for (TrackSegment segment : payload.segments()) {
					TrackCodecs.writeSegment(buf, segment);
				}
				buf.writeVarInt(payload.switches().size());
				for (Map.Entry<Long, Long> e : payload.switches().entrySet()) {
					buf.writeLong(e.getKey());
					buf.writeLong(e.getValue());
				}
			},
			buf -> {
				boolean reset = buf.readBoolean();
				RailwayConfig.Values config = TrackCodecs.readConfig(buf);
				int nodeCount = buf.readVarInt();
				List<TrackNode> nodes = new ArrayList<>(nodeCount);
				for (int i = 0; i < nodeCount; i++) {
					nodes.add(TrackCodecs.readNode(buf));
				}
				int segmentCount = buf.readVarInt();
				List<TrackSegment> segments = new ArrayList<>(segmentCount);
				for (int i = 0; i < segmentCount; i++) {
					segments.add(TrackCodecs.readSegment(buf));
				}
				int switchCount = buf.readVarInt();
				Map<Long, Long> switches = new HashMap<>(switchCount);
				for (int i = 0; i < switchCount; i++) {
					switches.put(buf.readLong(), buf.readLong());
				}
				return new TrackSyncPayload(reset, config, nodes, segments, switches);
			});

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
