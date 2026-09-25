package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.RoutePlanner;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.feature.MovingDeckFeature;
import com.example.railwayvehicleaddon.track.feature.TrackFeature;
import com.example.railwayvehicleaddon.track.feature.TurntableFeature;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 転車台モード。
 * <ol>
 *   <li>1点目・2点目: 桁の両端(直径)。中点が回転の中心になる。既存線路の端を指定すると、
 *       その線路に直接つながる</li>
 *   <li>3点目以降: 接続部を増やしたい方向の点。1点目の方向から、[ ] キーで選んだ角度刻みの
 *       方向に丸めて接続部を作る(扇形機関庫のような放射状の配置)</li>
 * </ol>
 * 1点目・2点目だけなら、その2か所だけに接続する。新しく作る接続部には外向きの短い線路が付き、
 * そこから新規生成モードで延伸できる。
 */
public final class TurntableMode implements SurveyMode {
	private static final double[] STEPS = {5.0, 7.5, 10.0, 15.0, 22.5, 30.0, 45.0, 90.0};
	private static final double MIN_RADIUS = 3.0;
	private static final double MAX_RADIUS = 24.0;

	@Override
	public String id() {
		return "turntable";
	}

	@Override
	public int minPoints() {
		return 2;
	}

	@Override
	public boolean allowsSnap(int index, int currentCount) {
		return index <= 1;
	}

	@Override
	public int defaultParam() {
		return 3;
	}

	@Override
	public int minParam() {
		return 0;
	}

	@Override
	public int maxParam() {
		return STEPS.length - 1;
	}

	@Override
	public String paramLabel(int param) {
		double step = STEPS[Math.max(0, Math.min(STEPS.length - 1, param))];
		return step == Math.rint(step) ? String.format("%.0f", step) : String.format("%.1f", step);
	}

	private static double norm(double angle) {
		return ((angle % 360.0) + 360.0) % 360.0;
	}

	private static boolean same(double a, double b) {
		double d = Math.abs(norm(a) - norm(b));
		return d < 1.0e-3 || Math.abs(d - 360.0) < 1.0e-3;
	}

	@Override
	public LayoutPlan plan(TrackNetwork network, SurveyInput input, RailwayConfig.Values config) {
		List<SurveyPoint> points = input.points();
		if (points.size() < 2) {
			return LayoutPlan.empty();
		}
		SurveyPoint first = points.get(0);
		SurveyPoint second = points.get(1);
		Vec3d p0 = first.pos();
		Vec3d p1 = second.pos();
		if (Math.abs(p0.y - p1.y) > 0.51) {
			return LayoutPlan.failed(new RoutePlanner.Issue("deck_level", p1, 0));
		}
		double cx = (p0.x + p1.x) / 2.0;
		double cz = (p0.z + p1.z) / 2.0;
		double y = p0.y;
		double radius = Math.hypot(p1.x - p0.x, p1.z - p0.z) / 2.0;
		if (radius < MIN_RADIUS) {
			return LayoutPlan.failed(new RoutePlanner.Issue("deck_too_small", p1, MIN_RADIUS * 2.0));
		}
		if (radius > MAX_RADIUS) {
			return LayoutPlan.failed(new RoutePlanner.Issue("deck_too_large", p1, MAX_RADIUS * 2.0));
		}
		double step = STEPS[Math.max(0, Math.min(STEPS.length - 1, input.param()))];
		double a0 = norm(Math.toDegrees(Math.atan2(p0.z - cz, p0.x - cx)));

		// 接続部の方向(1点目の方向からの角度順)
		List<Double> angles = new ArrayList<>();
		angles.add(a0);
		angles.add(norm(a0 + 180.0));
		for (int k = 2; k < points.size(); k++) {
			Vec3d p = points.get(k).pos();
			double a = norm(Math.toDegrees(Math.atan2(p.z - cz, p.x - cx)));
			double snapped = norm(a0 + Math.round(norm(a - a0) / step) * step);
			boolean dup = false;
			for (double existing : angles) {
				dup |= same(existing, snapped);
			}
			if (!dup) {
				angles.add(snapped);
			}
		}
		angles.sort((x, z) -> Double.compare(norm(x - a0), norm(z - a0)));

		List<RoutePlanner.Issue> issues = new ArrayList<>();
		List<Vec3d> newNodes = new ArrayList<>();
		List<LayoutPlan.Edge> edges = new ArrayList<>();
		List<Integer> stubEdges = new ArrayList<>();
		List<NodeRef> connRefs = new ArrayList<>();
		for (double angle : angles) {
			double rad = Math.toRadians(angle);
			double dx = Math.cos(rad);
			double dz = Math.sin(rad);
			Vec3d at = new Vec3d(cx + dx * radius, y, cz + dz * radius);
			SurveyPoint snapped = same(angle, a0) ? first : same(angle, a0 + 180.0) ? second : null;
			connRefs.add(MovingDeckPlans.connection(network, snapped, at, dx, dz, newNodes, edges, stubEdges, issues));
		}
		// 桁(初期位置は1点目の方向)
		Vec3d deckA = new Vec3d(cx + Math.cos(Math.toRadians(a0)) * radius, y, cz + Math.sin(Math.toRadians(a0)) * radius);
		Vec3d deckB = new Vec3d(2.0 * cx - deckA.x, y, 2.0 * cz - deckA.z);
		NodeRef deckRefA = NodeRef.created(newNodes.size());
		newNodes.add(deckA);
		NodeRef deckRefB = NodeRef.created(newNodes.size());
		newNodes.add(deckB);
		int deckEdge = edges.size();
		edges.add(new LayoutPlan.Edge(deckRefA, deckRefB, MovingDeckPlans.straight(deckA, deckB)));

		double total = 2.0 * radius + stubEdges.size() * MovingDeckPlans.STUB_LENGTH;
		List<Double> finalAngles = List.copyOf(angles);
		LayoutPlan.FeatureSpec spec = new LayoutPlan.FeatureSpec() {
			@Override
			public TrackFeature create(long featureId, LayoutPlan.IdResolver ids) {
				List<MovingDeckFeature.Stop> stops = new ArrayList<>();
				for (int i = 0; i < finalAngles.size(); i++) {
					long a = connRefs.get(i) == null ? -1L : ids.node(connRefs.get(i));
					long b = -1L;
					for (int j = 0; j < finalAngles.size(); j++) {
						if (same(finalAngles.get(j), finalAngles.get(i) + 180.0) && connRefs.get(j) != null) {
							b = ids.node(connRefs.get(j));
						}
					}
					stops.add(new MovingDeckFeature.Stop(finalAngles.get(i), a, b));
				}
				List<Long> stubs = new ArrayList<>();
				for (int index : stubEdges) {
					stubs.add(ids.segment(index));
				}
				return new TurntableFeature(featureId, ids.segment(deckEdge), ids.node(deckRefA), ids.node(deckRefB),
						stops, stubs, y, a0, 0, cx, cz, radius);
			}

			@Override
			public Set<BlockPos> clearance(RailwayConfig.Values config) {
				Set<BlockPos> set = new HashSet<>();
				double r = radius + 0.5;
				for (int x = (int) Math.floor(cx - r); x <= (int) Math.floor(cx + r); x++) {
					for (int z = (int) Math.floor(cz - r); z <= (int) Math.floor(cz + r); z++) {
						double dx = x + 0.5 - cx;
						double dz = z + 0.5 - cz;
						if (dx * dx + dz * dz <= r * r) {
							MovingDeckPlans.column(set, x, z, y, config);
						}
					}
				}
				return set;
			}

			@Override
			public List<Vec3d[]> outline() {
				List<Vec3d[]> lines = new ArrayList<>();
				Vec3d prev = null;
				for (int i = 0; i <= 48; i++) {
					double rad = Math.PI * 2.0 * i / 48.0;
					Vec3d cur = new Vec3d(cx + Math.cos(rad) * radius, y + 0.1, cz + Math.sin(rad) * radius);
					if (prev != null) {
						lines.add(new Vec3d[]{prev, cur});
					}
					prev = cur;
				}
				return lines;
			}
		};
		return new LayoutPlan(newNodes, List.of(), edges, List.of(), List.of(spec), issues, total);
	}
}
