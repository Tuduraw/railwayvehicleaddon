package com.example.railwayvehicleaddon.track;

import com.example.railwayvehicleaddon.track.feature.FeatureTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.List;

/**
 * ディメンションごとの線路グラフの保存先(world/<dim>/data/railwayvehicleaddon_tracks.dat)。
 * 実データはTrackNetworkが持ち、このクラスは保存・読み込みだけを担当する。
 */
public final class TrackNetworkState extends PersistentState {

	private static final Codec<TrackNode> NODE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.LONG.fieldOf("id").forGetter(TrackNode::id),
			Codec.DOUBLE.fieldOf("x").forGetter(TrackNode::x),
			Codec.DOUBLE.fieldOf("y").forGetter(TrackNode::y),
			Codec.DOUBLE.fieldOf("z").forGetter(TrackNode::z)
	).apply(instance, TrackNode::new));

	private static final Codec<TrackSegment> SEGMENT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.LONG.fieldOf("id").forGetter(TrackSegment::id),
			Codec.LONG.fieldOf("a").forGetter(TrackSegment::nodeA),
			Codec.LONG.fieldOf("b").forGetter(TrackSegment::nodeB),
			Codec.DOUBLE.listOf().fieldOf("plan").forGetter(seg -> toList(seg.plan().controlPoints())),
			Codec.DOUBLE.listOf().fieldOf("profile").forGetter(seg -> toList(seg.profile().toArray())),
			Codec.FLOAT.optionalFieldOf("design_speed", 80.0f).forGetter(TrackSegment::designSpeed)
	).apply(instance, (id, a, b, plan, profile, designSpeed) -> new TrackSegment(id, a, b,
			PlanCurve.fromControlPoints(toArray(plan, 8)), HeightProfile.fromArray(toArray(profile, 8)), designSpeed)));

	private record SwitchEntry(long node, long segment) {
		static final Codec<SwitchEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.LONG.fieldOf("node").forGetter(SwitchEntry::node),
				Codec.LONG.fieldOf("segment").forGetter(SwitchEntry::segment)
		).apply(instance, SwitchEntry::new));
	}

	public static final Codec<TrackNetworkState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			NODE_CODEC.listOf().optionalFieldOf("nodes", List.of()).forGetter(state -> new ArrayList<>(state.network.nodes())),
			SEGMENT_CODEC.listOf().optionalFieldOf("segments", List.of()).forGetter(state -> new ArrayList<>(state.network.segments())),
			Codec.LONG.optionalFieldOf("next_id", 1L).forGetter(state -> state.network.peekNextId()),
			SwitchEntry.CODEC.listOf().optionalFieldOf("switches", List.of()).forGetter(state -> {
				List<SwitchEntry> list = new ArrayList<>();
				state.network.switchStates().forEach((node, segment) -> list.add(new SwitchEntry(node, segment)));
				return list;
			}),
			FeatureTypes.CODEC.listOf().optionalFieldOf("features", List.of()).forGetter(state -> new ArrayList<>(state.network.features()))
	).apply(instance, (nodes, segments, nextId, switches, features) -> {
		TrackNetworkState state = new TrackNetworkState();
		nodes.forEach(state.network::putNode);
		segments.forEach(state.network::putSegment);
		state.network.setNextId(nextId);
		switches.forEach(entry -> state.network.setSwitchState(entry.node(), entry.segment()));
		features.forEach(state.network::putFeature);
		return state;
	}));

	public static final PersistentStateType<TrackNetworkState> TYPE =
			new PersistentStateType<>("railwayvehicleaddon_tracks", TrackNetworkState::new, CODEC, null);

	private final TrackNetwork network = new TrackNetwork();

	public TrackNetworkState() {
	}

	public TrackNetwork network() {
		return this.network;
	}

	public static TrackNetworkState get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE);
	}

	private static List<Double> toList(double[] values) {
		List<Double> list = new ArrayList<>(values.length);
		for (double v : values) {
			list.add(v);
		}
		return list;
	}

	/** 要素数が足りない壊れたデータでも例外にせず、0で埋めて読み込む。 */
	private static double[] toArray(List<Double> list, int size) {
		double[] result = new double[size];
		for (int i = 0; i < size && i < list.size(); i++) {
			result[i] = list.get(i);
		}
		return result;
	}
}
