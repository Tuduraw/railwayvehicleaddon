package com.example.railwayvehicleaddon.operation;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackPoint;
import com.example.railwayvehicleaddon.track.TrackSegment;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 分岐器の鎖錠(分岐区間に車両がいる間・進路の予約中は切り替えない)の判定。
 *
 * <p>分岐区間: 分岐側の各区間は、分岐器から他の分岐側の線路と建築限界が重ならなくなる所(車両接触限界、
 * 中心線の間隔が建築限界の片側幅の2倍以上)まで。基準側の区間は分岐器から {@link #TRUNK_ZONE} まで。
 * 在線は編成ごとの在線表({@link OperationState})で判定するので、車両と車両の間(連結部)が
 * 分岐器の上にあっても在線になる。
 */
public final class Interlock {
	/** 基準側の区間で、分岐区間に含める分岐器からの距離(連結器の張り出し程度) */
	public static final double TRUNK_ZONE = 2.0;
	/** 車両接触限界を探す最大距離 */
	private static final double MAX_FOUL_DISTANCE = 40.0;

	private Interlock() {
	}

	/** 分岐区間の一部(区間ID・範囲)。 */
	public record ZoneSpan(long segmentId, double s0, double s1) {
	}

	private record CachedZone(List<TrackSegment> segments, List<ZoneSpan> spans) {
	}

	private static final Map<TrackNetwork, Map<Long, CachedZone>> ZONES = new java.util.WeakHashMap<>();

	/** 分岐器nodeIdの分岐区間。分岐器でなければ空。 */
	public static List<ZoneSpan> zone(TrackNetwork network, long nodeId) {
		List<Long> branches = network.switchBranches(nodeId);
		if (branches.isEmpty()) {
			return List.of();
		}
		List<Long> all = network.segmentsAt(nodeId);
		List<TrackSegment> current = new ArrayList<>(all.size());
		for (long id : all) {
			TrackSegment s = network.segment(id);
			if (s != null) {
				current.add(s);
			}
		}
		Map<Long, CachedZone> cache;
		synchronized (ZONES) {
			cache = ZONES.computeIfAbsent(network, n -> new HashMap<>());
		}
		CachedZone cached = cache.get(nodeId);
		if (cached != null && sameSegments(cached.segments(), current)) {
			return cached.spans();
		}
		double foulGap = 2.0 * RailwayConfig.get().clearance_half_width;
		List<ZoneSpan> spans = new ArrayList<>();
		for (TrackSegment segment : current) {
			boolean fromA = segment.nodeA() == nodeId;
			double length = segment.length();
			double reach;
			if (branches.contains(segment.id())) {
				reach = foulDistance(network, segment, nodeId, branches, foulGap);
			} else {
				reach = Math.min(TRUNK_ZONE, length);
			}
			spans.add(fromA ? new ZoneSpan(segment.id(), 0.0, reach) : new ZoneSpan(segment.id(), length - reach, length));
		}
		cache.put(nodeId, new CachedZone(current, spans));
		return spans;
	}

	private static boolean sameSegments(List<TrackSegment> a, List<TrackSegment> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			if (a.get(i) != b.get(i)) {
				return false;
			}
		}
		return true;
	}

	/** 分岐側の区間segmentで、他の分岐側の線路との間隔がgap以上になる分岐器からの距離。 */
	private static double foulDistance(TrackNetwork network, TrackSegment segment, long nodeId, List<Long> branches, double gap) {
		List<double[]> others = new ArrayList<>();
		for (long id : branches) {
			TrackSegment other = network.segment(id);
			if (other == null || id == segment.id()) {
				continue;
			}
			boolean fromA = other.nodeA() == nodeId;
			double len = other.length();
			for (double d = 0.0; d <= Math.min(len, MAX_FOUL_DISTANCE + 8.0); d += 0.5) {
				TrackPoint p = other.sample(fromA ? d : len - d);
				others.add(new double[]{p.x(), p.z()});
			}
		}
		boolean fromA = segment.nodeA() == nodeId;
		double length = segment.length();
		double limit = Math.min(length, MAX_FOUL_DISTANCE);
		for (double d = 0.5; d <= limit; d += 0.5) {
			TrackPoint p = segment.sample(fromA ? d : length - d);
			double min = Double.MAX_VALUE;
			for (double[] o : others) {
				min = Math.min(min, Math.hypot(o[0] - p.x(), o[1] - p.z()));
			}
			if (min >= gap) {
				return d;
			}
		}
		return limit;
	}

	/**
	 * 分岐区間に、ignoreMembers(null可)に含まれない車両の編成がいるか。
	 * ignoreMembersには、割り出しで通っている編成自身の車両を渡す。
	 */
	public static boolean occupied(ServerWorld world, TrackNetwork network, long nodeId, Set<UUID> ignoreMembers) {
		OperationState state = OperationState.get(world);
		long now = world.getTime();
		for (ZoneSpan zone : zone(network, nodeId)) {
			for (OperationState.Span span : state.spansOn(zone.segmentId(), now)) {
				if (span.s1() < zone.s0() || span.s0() > zone.s1()) {
					continue;
				}
				if (ignoreMembers != null && !java.util.Collections.disjoint(ignoreMembers, span.consist().members())) {
					continue;
				}
				return true;
			}
		}
		return false;
	}

	/** 分岐区間に車両がいるか、holder以外の列車の予約があれば鎖錠中(切り替えられない)。holderはnull可。 */
	public static boolean locked(ServerWorld world, TrackNetwork network, long nodeId, UUID holder) {
		if (occupied(world, network, nodeId, null)) {
			return true;
		}
		OperationState.Reservation r = OperationState.get(world).reservation(nodeId, world.getTime());
		return r != null && !r.holder().equals(holder);
	}
}
