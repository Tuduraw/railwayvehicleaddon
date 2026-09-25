package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.RoutePlanner;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 新規生成モード(既定)。点の並びをそのまま1本の路線にする。
 * <ul>
 *   <li>最初の点を既存線路の端に置くと、その端から向きを揃えて延伸する</li>
 *   <li>最後の点を既存線路の端に置くと、その端へ向きを揃えて接続する</li>
 *   <li>最初の点をもう一度指定すると環状線になる(既存線路からの延伸時は不可)</li>
 * </ul>
 */
public final class NewRouteMode implements SurveyMode {
	@Override
	public String id() {
		return "new";
	}

	@Override
	public int minPoints() {
		return 2;
	}

	@Override
	public boolean supportsInsert() {
		return true;
	}

	@Override
	public boolean supportsClose() {
		return true;
	}

	@Override
	public LayoutPlan plan(TrackNetwork network, SurveyInput input, RailwayConfig.Values config) {
		List<SurveyPoint> points = input.points();
		int n = points.size();
		if (n < 2) {
			return LayoutPlan.empty();
		}
		for (int i = 1; i < n - 1; i++) {
			if (points.get(i).snapped()) {
				return LayoutPlan.failed(new RoutePlanner.Issue("snap_interior", points.get(i).pos(), 0));
			}
		}
		SurveyPoint first = points.get(0);
		SurveyPoint last = points.get(n - 1);
		if (input.closed() && (first.snapped() || last.snapped())) {
			return LayoutPlan.failed(new RoutePlanner.Issue("close_snapped", first.pos(), 0));
		}
		if (first.snapped() && last.snapped() && first.nodeId() == last.nodeId()) {
			return LayoutPlan.failed(new RoutePlanner.Issue("snap_invalid", last.pos(), 0));
		}
		double[] startTangent = null;
		double[] endTangent = null;
		if (first.snapped()) {
			startTangent = network.outwardDirection(first.nodeId());
			if (startTangent == null) {
				return LayoutPlan.failed(new RoutePlanner.Issue("snap_invalid", first.pos(), 0));
			}
		}
		if (last.snapped()) {
			double[] outward = network.outwardDirection(last.nodeId());
			if (outward == null) {
				return LayoutPlan.failed(new RoutePlanner.Issue("snap_invalid", last.pos(), 0));
			}
			endTangent = new double[]{-outward[0], -outward[1]};
		}

		List<Vec3d> positions = new ArrayList<>(n);
		for (SurveyPoint p : points) {
			positions.add(p.pos());
		}
		RoutePlanner.PlannedRoute route = RoutePlanner.plan(positions, startTangent, endTangent, input.closed(), config);

		List<Vec3d> newNodes = new ArrayList<>();
		List<NodeRef> refs = new ArrayList<>(n);
		for (SurveyPoint p : points) {
			if (p.snapped()) {
				refs.add(NodeRef.existing(p.nodeId()));
			} else {
				refs.add(NodeRef.created(newNodes.size()));
				newNodes.add(p.pos());
			}
		}
		List<LayoutPlan.Edge> edges = new ArrayList<>();
		for (int i = 0; i < route.segments().size(); i++) {
			edges.add(new LayoutPlan.Edge(refs.get(i), refs.get((i + 1) % n), route.segments().get(i)));
		}
		return LayoutPlan.of(newNodes, edges, List.of(), route.issues(), route.totalLength());
	}
}
