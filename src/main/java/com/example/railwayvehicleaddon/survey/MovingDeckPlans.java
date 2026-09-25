package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.HeightProfile;
import com.example.railwayvehicleaddon.track.PlanCurve;
import com.example.railwayvehicleaddon.track.RoutePlanner;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Set;

/** 転車台・遷車台の配置計画で共通に使う部品。 */
final class MovingDeckPlans {
	/** 接続部から外へ伸ばす短い線路の長さ。ここから新規生成モードで延伸する */
	static final double STUB_LENGTH = 3.0;

	private MovingDeckPlans() {
	}

	static RoutePlanner.PlannedSegment straight(Vec3d a, Vec3d b) {
		return new RoutePlanner.PlannedSegment(PlanCurve.straight(a.x, a.z, b.x, b.z), HeightProfile.flat(a.y));
	}

	/**
	 * 接続部を用意する。(dirX, dirZ)は設備から外へ向かう方向。指定点が既存線路の端で、その線路が
	 * 設備に向かって伸びている(端の延長方向が設備の内側を向いている)ならそのノードを使い、
	 * そうでなければ新しいノードと外向きの短い線路を作る。
	 *
	 * @return 接続部のノード参照。既存の端の向きが合わなければnull(問題としてissuesに追加済み)
	 */
	static NodeRef connection(TrackNetwork network, SurveyPoint snapped, Vec3d at, double dirX, double dirZ,
							  List<Vec3d> newNodes, List<LayoutPlan.Edge> edges, List<Integer> stubEdges,
							  List<RoutePlanner.Issue> issues) {
		if (snapped != null && snapped.snapped()) {
			double[] outward = network.outwardDirection(snapped.nodeId());
			if (outward == null || -(outward[0] * dirX + outward[1] * dirZ) < 0.95) {
				issues.add(new RoutePlanner.Issue("deck_align", snapped.pos(), 0));
				return null;
			}
			return NodeRef.existing(snapped.nodeId());
		}
		NodeRef conn = NodeRef.created(newNodes.size());
		newNodes.add(at);
		Vec3d outer = at.add(dirX * STUB_LENGTH, 0.0, dirZ * STUB_LENGTH);
		NodeRef outerRef = NodeRef.created(newNodes.size());
		newNodes.add(outer);
		stubEdges.add(edges.size());
		edges.add(new LayoutPlan.Edge(conn, outerRef, straight(at, outer)));
		return conn;
	}

	/** 高さyの水平面で、判定関数に当てはまるブロックの列を建築限界の高さまで積んだ範囲。 */
	static Set<BlockPos> column(Set<BlockPos> out, int x, int z, double y, RailwayConfig.Values config) {
		int bottom = (int) Math.floor(y + 0.01);
		int top = (int) Math.floor(y + config.clearanceHeight() - 0.01);
		for (int by = bottom; by <= top; by++) {
			out.add(new BlockPos(x, by, z));
		}
		return out;
	}
}
