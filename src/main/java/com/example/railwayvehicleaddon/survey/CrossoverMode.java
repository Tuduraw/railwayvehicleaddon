package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.RoutePlanner;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 渡り線モード。並んだ2本の線路の上をそれぞれ1点ずつ指定すると、両方の線路を分割して
 * 分岐器を入れ、その間をS字の連絡線でつなぐ。連絡線の両端は各線路と同じ向きで接続する。
 * 2本の線路のなす角が大きすぎる(30°超)場合は置けない。
 */
public final class CrossoverMode implements SurveyMode {
	private static final double MAX_ANGLE_COS = Math.cos(Math.toRadians(30.0));

	@Override
	public String id() {
		return "crossover";
	}

	@Override
	public int minPoints() {
		return 2;
	}

	@Override
	public boolean allowsSnap(int index, int currentCount) {
		return false;
	}

	@Override
	public boolean allowsTrackSnap(int index) {
		return index <= 1;
	}

	@Override
	public LayoutPlan plan(TrackNetwork network, SurveyInput input, RailwayConfig.Values config) {
		List<SurveyPoint> points = input.points();
		if (points.isEmpty()) {
			return LayoutPlan.empty();
		}
		List<LayoutPlan.Split> splits = new ArrayList<>();
		Anchors.Anchor a = Anchors.resolve(network, points.get(0), splits);
		if (a == null) {
			return LayoutPlan.failed(new RoutePlanner.Issue("crossover_point", points.get(0).pos(), 0));
		}
		if (points.size() < 2) {
			return LayoutPlan.empty();
		}
		if (points.size() > 2) {
			return LayoutPlan.failed(new RoutePlanner.Issue("too_many_waypoints", points.get(2).pos(), 2));
		}
		Anchors.Anchor b = Anchors.resolve(network, points.get(1), splits);
		if (b == null) {
			return LayoutPlan.failed(new RoutePlanner.Issue("crossover_point", points.get(1).pos(), 0));
		}
		if (a.ref().equals(b.ref()) || (a.segmentId() >= 0 && a.segmentId() == b.segmentId())) {
			return LayoutPlan.failed(new RoutePlanner.Issue("crossover_same_track", points.get(1).pos(), 0));
		}
		Vec3d pa = a.pos();
		Vec3d pb = b.pos();
		double[] ta = a.tangentToward(pb.x - pa.x, pb.z - pa.z);
		double[] tb = b.tangentToward(ta[0], ta[1]);
		if (ta[0] * tb[0] + ta[1] * tb[1] < MAX_ANGLE_COS) {
			return LayoutPlan.failed(new RoutePlanner.Issue("crossover_not_parallel", pb, 0));
		}
		RoutePlanner.PlannedRoute route = RoutePlanner.plan(List.of(pa, pb), ta, tb, false, config);
		List<LayoutPlan.Edge> edges = new ArrayList<>();
		if (!route.segments().isEmpty()) {
			edges.add(new LayoutPlan.Edge(a.ref(), b.ref(), route.segments().get(0)));
		}
		return new LayoutPlan(List.of(), splits, edges, List.of(), List.of(), route.issues(), route.totalLength());
	}
}
