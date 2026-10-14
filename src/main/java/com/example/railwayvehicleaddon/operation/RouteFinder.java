package com.example.railwayvehicleaddon.operation;

import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackPoint;
import com.example.railwayvehicleaddon.track.TrackSegment;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 進路の自動設定で、対向の分岐器をどの分岐側へ開通させるかを決める。
 * 分岐側の各候補から線路を(分岐器の向きに関係なく、進める方向すべてを)たどり、運行表の次の駅の
 * 停止位置目標(同じ向きのもの)までの道のりが最も短い候補を選ぶ。どの候補からも着けなければ-1(切り替えない)。
 * 結果は短時間(5秒)使い回す。
 */
public final class RouteFinder {
	/** 探す道のりの上限(ブロック) */
	private static final double MAX_COST = 4096.0;
	/** 調べる区間の数の上限 */
	private static final int MAX_EXPANSIONS = 6000;
	private static final long CACHE_TICKS = 100L;

	private RouteFinder() {
	}

	public static long choose(ServerWorld world, TrackNetwork network, OperationState state, long nodeId, long fromSegment,
							  double arrivalX, double arrivalZ, Timetable.Stop target) {
		long now = world.getTime();
		String key = nodeId + "|" + fromSegment + "|" + target.station() + "|" + target.platform();
		OperationState.RouteChoice cached = state.cachedRoute(key, now);
		if (cached != null) {
			return cached.segmentId();
		}
		Map<Long, List<double[]>> targets = new HashMap<>();
		for (OperationState.Wayside w : state.stopMarkers(target.station(), target.platform(), now)) {
			targets.computeIfAbsent(w.segmentId(), k -> new ArrayList<>()).add(new double[]{w.s(), w.dir()});
		}
		long best = -1L;
		if (!targets.isEmpty()) {
			double bestCost = Double.MAX_VALUE;
			for (long[] candidate : network.forwardCandidates(nodeId, fromSegment, arrivalX, arrivalZ)) {
				double cost = shortest(network, candidate[0], (int) candidate[1], targets);
				if (cost < bestCost) {
					bestCost = cost;
					best = candidate[0];
				}
			}
		}
		state.cacheRoute(key, new OperationState.RouteChoice(best, now + CACHE_TICKS));
		return best;
	}

	/** 区間segmentIdへ向きparamDirで入ってから、目標に着くまでの最短の道のり。着けなければ最大値。 */
	static double shortest(TrackNetwork network, long segmentId, int paramDir, Map<Long, List<double[]>> targets) {
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
			List<double[]> here = targets.get(id);
			if (here != null) {
				for (double[] t : here) {
					if ((int) t[1] == dir) {
						double inner = dir > 0 ? t[0] : length - t[0];
						queue.add(new double[]{cost + inner, -1, 0});
					}
				}
			}
			long node = dir > 0 ? segment.nodeB() : segment.nodeA();
			TrackPoint end = segment.sample(dir > 0 ? length : 0.0);
			for (long[] next : network.forwardCandidates(node, id, end.dirX() * dir, end.dirZ() * dir)) {
				queue.add(new double[]{cost + length, next[0], next[1]});
			}
		}
		return Double.MAX_VALUE;
	}
}
