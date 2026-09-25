package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.RoutePlanner;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 分岐生成モード。最初の点(分岐元)に分岐器を置き、2点目以降の各点へ1本ずつ線路を伸ばす。
 * <ul>
 *   <li>分岐元が既存線路の端: 分岐先は2つ以上。開通方向の初期値は2点目への線路</li>
 *   <li>分岐元が線路の途中: その地点で線路を分割して分岐器を入れる。分岐先は1つ以上で、
 *       元の線路の続きも分岐の1本になる(開通方向の初期値は元の線路の続き)。
 *       分岐する向きは最初の分岐先の方向で決まり、分岐先はすべて同じ向きの側に置く</li>
 * </ul>
 * 分岐先に既存線路の端を指定するとそこへ接続する。分岐した線路はどれも分岐器の位置で
 * 元の線路と同じ向きから始まるので、分岐器の前後で線路の向きが連続する。
 */
public final class BranchMode implements SurveyMode {
	@Override
	public String id() {
		return "branch";
	}

	@Override
	public int minPoints() {
		return 2;
	}

	@Override
	public boolean allowsTrackSnap(int index) {
		return index == 0;
	}

	@Override
	public LayoutPlan plan(TrackNetwork network, SurveyInput input, RailwayConfig.Values config) {
		List<SurveyPoint> points = input.points();
		if (points.isEmpty()) {
			return LayoutPlan.empty();
		}
		SurveyPoint origin = points.get(0);
		List<LayoutPlan.Split> splits = new ArrayList<>();
		Anchors.Anchor anchor = Anchors.resolve(network, origin, splits);
		if (anchor == null) {
			return LayoutPlan.failed(new RoutePlanner.Issue("branch_origin", origin.pos(), 0));
		}
		if (points.size() < 2) {
			return LayoutPlan.empty();
		}
		Vec3d o = anchor.pos();
		Vec3d firstTarget = points.get(1).pos();
		double[] tangent = anchor.deadEnd() ? anchor.tangent()
				: anchor.tangentToward(firstTarget.x - o.x, firstTarget.z - o.z);
		int required = anchor.deadEnd() ? 2 : 1;

		List<RoutePlanner.Issue> issues = new ArrayList<>();
		List<Vec3d> newNodes = new ArrayList<>();
		List<LayoutPlan.Edge> edges = new ArrayList<>();
		Set<Long> usedNodes = new HashSet<>();
		double total = 0.0;
		for (int k = 1; k < points.size(); k++) {
			SurveyPoint target = points.get(k);
			Vec3d t = target.pos();
			if ((t.x - o.x) * tangent[0] + (t.z - o.z) * tangent[1] <= 0.0) {
				issues.add(new RoutePlanner.Issue("branch_direction", t, 0));
				continue;
			}
			double[] endTangent = null;
			NodeRef ref;
			if (target.snapped()) {
				double[] outward = network.outwardDirection(target.nodeId());
				boolean sameAsOrigin = anchor.ref().isExisting() && anchor.ref().existingId() == target.nodeId();
				if (outward == null || sameAsOrigin || !usedNodes.add(target.nodeId())) {
					issues.add(new RoutePlanner.Issue("snap_invalid", t, 0));
					continue;
				}
				endTangent = new double[]{-outward[0], -outward[1]};
				ref = NodeRef.existing(target.nodeId());
			} else {
				ref = NodeRef.created(newNodes.size());
				newNodes.add(t);
			}
			RoutePlanner.PlannedRoute route = RoutePlanner.plan(List.of(o, t), tangent, endTangent, false, config);
			issues.addAll(route.issues());
			if (!route.segments().isEmpty()) {
				edges.add(new LayoutPlan.Edge(anchor.ref(), ref, route.segments().get(0)));
				total += route.totalLength();
			}
		}
		if (points.size() - 1 < required) {
			// プレビューは出すが、分岐先が足りなければ確定できない
			issues.add(new RoutePlanner.Issue("branch_need_two", o, required));
		}
		List<LayoutPlan.SwitchDefault> defaults = anchor.deadEnd() && !edges.isEmpty()
				? List.of(new LayoutPlan.SwitchDefault(anchor.ref(), 0)) : List.of();
		return new LayoutPlan(newNodes, splits, edges, defaults, List.of(), issues, total);
	}
}
