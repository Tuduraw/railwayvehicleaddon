package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.RoutePlanner;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackNode;
import com.example.railwayvehicleaddon.track.feature.BufferStopFeature;
import com.example.railwayvehicleaddon.track.feature.TrackFeature;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** 車止めモード。線路の端を指定して車止めを置く。 */
public final class BufferStopMode implements SurveyMode {
	@Override
	public String id() {
		return "buffer_stop";
	}

	@Override
	public int minPoints() {
		return 1;
	}

	@Override
	public LayoutPlan plan(TrackNetwork network, SurveyInput input, RailwayConfig.Values config) {
		List<SurveyPoint> points = input.points();
		if (points.isEmpty()) {
			return LayoutPlan.empty();
		}
		if (points.size() > 1) {
			return LayoutPlan.failed(new RoutePlanner.Issue("too_many_waypoints", points.get(1).pos(), 1));
		}
		SurveyPoint point = points.get(0);
		TrackNode node = point.snapped() ? network.node(point.nodeId()) : null;
		if (node == null || !network.isDeadEnd(node.id()) || network.isReserved(node.id())) {
			return LayoutPlan.failed(new RoutePlanner.Issue("buffer_stop_end", point.pos(), 0));
		}
		double[] outward = network.outwardDirection(node.id());
		long nodeId = node.id();
		Vec3d pos = new Vec3d(node.x(), node.y(), node.z());
		LayoutPlan.FeatureSpec spec = new LayoutPlan.FeatureSpec() {
			@Override
			public TrackFeature create(long featureId, LayoutPlan.IdResolver ids) {
				return new BufferStopFeature(featureId, nodeId, BufferStopFeature.DEFAULT_MARGIN);
			}

			@Override
			public List<Vec3d[]> outline() {
				// 止まる位置を示す線(線路と直角)
				double m = BufferStopFeature.DEFAULT_MARGIN;
				double cx = pos.x - outward[0] * m;
				double cz = pos.z - outward[1] * m;
				double lx = outward[1] * 1.2;
				double lz = -outward[0] * 1.2;
				return List.of(new Vec3d[]{new Vec3d(cx - lx, pos.y + 0.3, cz - lz), new Vec3d(cx + lx, pos.y + 0.3, cz + lz)},
						new Vec3d[]{new Vec3d(pos.x, pos.y, pos.z), new Vec3d(pos.x, pos.y + 1.2, pos.z)});
			}
		};
		return new LayoutPlan(List.of(), List.of(), List.of(), List.of(), List.of(spec), List.of(), 0.0);
	}
}
