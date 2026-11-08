package com.example.railwayvehicleaddon.operation;

import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackPoint;
import com.example.railwayvehicleaddon.track.TrackSegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;
import net.minecraft.server.level.ServerLevel;

/**
 * 進路の自動設定で、対向の分岐器をどの分岐側へ開通させるかを決める。
 * 分岐側の各候補から線路を(分岐器の向きに関係なく、進める方向すべてを)たどり、運行表の次の駅の
 * 停止位置目標(向きは問わない)までの道のりが最も短い候補を選ぶ。どの候補からも着けなければ-1(切り替えない)。
 * 先にある分岐器が鎖錠中(在線・他の列車の予約)なら、その分岐器は今の開通方向にしか通れないものとして探す。
 * 結果は短時間(1秒)使い回す。
 */
public final class RouteFinder {
	/** 探す道のりの上限(ブロック) */
	private static final double MAX_COST = 4096.0;
	/** 調べる区間の数の上限 */
	private static final int MAX_EXPANSIONS = 6000;
	private static final long CACHE_TICKS = 20L;

	private RouteFinder() {
	}

	/**
	 * @param holder 進路を求める列車(運行表を持つ車両)のUUID。この列車の予約している分岐器は切り替えられるものとして扱う
	 */
	public static long choose(ServerLevel world, TrackNetwork network, OperationState state, long nodeId, long fromSegment,
							  double arrivalX, double arrivalZ, Timetable.Stop target, UUID holder) {
		long now = world.getGameTime();
		String key = nodeId + "|" + fromSegment + "|" + target.station() + "|" + target.platform() + "|" + holder;
		OperationState.RouteChoice cached = state.cachedRoute(key, now);
		if (cached != null) {
			return cached.segmentId();
		}
		Map<Long, List<Double>> targets = new HashMap<>();
		for (OperationState.Wayside w : state.stopMarkers(network, target.station(), target.platform(), now)) {
			targets.computeIfAbsent(w.segmentId(), k -> new ArrayList<>()).add(w.s());
		}
		long best = -1L;
		if (!targets.isEmpty()) {
			LongPredicate fixed = n -> Interlock.locked(world, network, n, holder);
			double bestCost = Double.MAX_VALUE;
			for (long[] candidate : network.forwardCandidates(nodeId, fromSegment, arrivalX, arrivalZ)) {
				double cost = shortest(network, candidate[0], (int) candidate[1], targets, fixed);
				if (cost < bestCost) {
					bestCost = cost;
					best = candidate[0];
				}
			}
		}
		state.cacheRoute(key, new OperationState.RouteChoice(best, now + CACHE_TICKS));
		return best;
	}

	/**
	 * 区間segmentIdへ向きparamDirで入ってから、目標(区間ID → 区間上の距離)のどれかに着くまでの最短の道のり。
	 * 着けなければ最大値。fixed(null可)は、切り替えられない(鎖錠中の)分岐器ノードならtrue。
	 */
	static double shortest(TrackNetwork network, long segmentId, int paramDir, Map<Long, List<Double>> targets, LongPredicate fixed) {
		PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
		// {道のり, 区間ID(目標に着いた印は-1), 向き}
		queue.add(new double[]{0.0, segmentId, paramDir});
		Set<Long> visited = new HashSet<>();
		int expansions = 0;
		while (!queue.isEmpty() && expansions++ < MAX_EXPANSIONS) {
			double[] e = queue.poll();
			double cost = e[0];
			if (e[1] < 0) {
				return cost;
			}
			if (cost > MAX_COST) {
				break;
			}
			long id = (long) e[1];
			int dir = (int) e[2];
			if (!visited.add(id * 2 + (dir > 0 ? 1 : 0))) {
				continue;
			}
			TrackSegment segment = network.segment(id);
			if (segment == null) {
				continue;
			}
			double length = segment.length();
			List<Double> here = targets.get(id);
			if (here != null) {
				for (double t : here) {
					double inner = dir > 0 ? t : length - t;
					queue.add(new double[]{cost + Math.max(0.0, inner), -1, 0});
				}
			}
			long node = dir > 0 ? segment.nodeB() : segment.nodeA();
			TrackPoint end = segment.sample(dir > 0 ? length : 0.0);
			for (long[] next : network.forwardCandidates(node, id, end.dirX() * dir, end.dirZ() * dir)) {
				if (fixed != null && !passable(network, node, id, next[0], fixed)) {
					continue;
				}
				queue.add(new double[]{cost + length, next[0], next[1]});
			}
		}
		return Double.MAX_VALUE;
	}

	/** 次の駅(駅名・番線)の停止位置目標の位置(区間ID → 区間上の距離)。 */
	public static Map<Long, List<Double>> targetsFor(OperationState state, TrackNetwork network, Timetable.Stop stop, long now) {
		Map<Long, List<Double>> targets = new HashMap<>();
		for (OperationState.Wayside w : state.stopMarkers(network, stop.station(), stop.platform(), now)) {
			targets.computeIfAbsent(w.segmentId(), k -> new ArrayList<>()).add(w.s());
		}
		return targets;
	}

	/**
	 * 線路上の位置(区間segmentIdの距離s)から向きparamDirへ進んで、目標のどれかに着くまでの最短の道のり。
	 * 着けなければ最大値。ATOが発車の向きを決めるのに使う(編成の前端から前へ、後端から後ろへ)。
	 */
	public static double distanceFrom(TrackNetwork network, long segmentId, double s, int paramDir,
									  Map<Long, List<Double>> targets, LongPredicate fixed) {
		TrackSegment segment = network.segment(segmentId);
		if (segment == null || targets.isEmpty()) {
			return Double.MAX_VALUE;
		}
		int dir = paramDir >= 0 ? 1 : -1;
		double length = segment.length();
		double best = Double.MAX_VALUE;
		List<Double> here = targets.get(segmentId);
		if (here != null) {
			for (double t : here) {
				double along = (t - s) * dir;
				if (along >= -0.5) {
					best = Math.min(best, Math.max(0.0, along));
				}
			}
		}
		if (best < Double.MAX_VALUE) {
			return best;
		}
		double rest = dir > 0 ? length - s : s;
		long node = dir > 0 ? segment.nodeB() : segment.nodeA();
		TrackPoint end = segment.sample(dir > 0 ? length : 0.0);
		for (long[] next : network.forwardCandidates(node, segmentId, end.dirX() * dir, end.dirZ() * dir)) {
			if (fixed != null && !passable(network, node, segmentId, next[0], fixed)) {
				continue;
			}
			double cost = shortest(network, next[0], (int) next[1], targets, fixed);
			if (cost < Double.MAX_VALUE) {
				best = Math.min(best, rest + cost);
			}
		}
		return best;
	}

	/** ノードnodeで区間fromから区間toへ進めるか(鎖錠中の分岐器は、今の開通方向に沿ってしか通れない)。 */
	private static boolean passable(TrackNetwork network, long node, long from, long to, LongPredicate fixed) {
		long linked = network.linkOf(node);
		for (long at : linked >= 0 ? new long[]{node, linked} : new long[]{node}) {
			List<Long> branches = network.switchBranches(at);
			if (branches.isEmpty() || !fixed.test(at)) {
				continue;
			}
			long active = network.activeBranch(at);
			if (branches.contains(from) && from != active) {
				return false;      // 開通していない分岐側から入る(割り出し)
			}
			if (branches.contains(to) && to != active) {
				return false;      // 開通していない分岐側へ進む
			}
		}
		return true;
	}
}
