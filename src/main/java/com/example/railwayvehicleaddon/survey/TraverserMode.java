package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.RoutePlanner;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.feature.MovingDeckFeature;
import com.example.railwayvehicleaddon.track.feature.TrackFeature;
import com.example.railwayvehicleaddon.track.feature.TraverserFeature;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 遷車台(トラバーサー)モード。
 * <ol>
 *   <li>1点目・2点目: 桁の両端。既存線路の端を指定すると、その線路に直接つながる</li>
 *   <li>3点目以降: 桁を平行移動させて止める位置の点。桁と直角の方向の距離を、[ ] キーで
 *       選んだ間隔(線路の間隔)に丸めて停止位置を作る</li>
 * </ol>
 * 各停止位置の両端に接続部(外向きの短い線路)ができ、そこから新規生成モードで延伸できる。
 */
public final class TraverserMode implements SurveyMode {
	private static final double[] STEPS = {2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0};
	private static final double MIN_LENGTH = 3.0;
	private static final double MAX_LENGTH = 40.0;

	@Override
	public String id() {
		return "traverser";
	}

	@Override
	public int minPoints() {
		return 3;
	}

	@Override
	public boolean allowsSnap(int index, int currentCount) {
		return index <= 1;
	}

	@Override
	public int defaultParam() {
		return 2;
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
		return String.format("%.0f", STEPS[Math.max(0, Math.min(STEPS.length - 1, param))]);
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
		double length = Math.hypot(p1.x - p0.x, p1.z - p0.z);
		if (length < MIN_LENGTH) {
			return LayoutPlan.failed(new RoutePlanner.Issue("deck_too_small", p1, MIN_LENGTH));
		}
		if (length > MAX_LENGTH) {
			return LayoutPlan.failed(new RoutePlanner.Issue("deck_too_large", p1, MAX_LENGTH));
		}
		double y = p0.y;
		double dirX = (p1.x - p0.x) / length;
		double dirZ = (p1.z - p0.z) / length;
		double axisX = -dirZ;
		double axisZ = dirX;
		double step = STEPS[Math.max(0, Math.min(STEPS.length - 1, input.param()))];
		double midX = (p0.x + p1.x) / 2.0;
		double midZ = (p0.z + p1.z) / 2.0;

		List<Double> offsets = new ArrayList<>();
		offsets.add(0.0);
		for (int k = 2; k < points.size(); k++) {
			Vec3d p = points.get(k).pos();
			double o = Math.round(((p.x - midX) * axisX + (p.z - midZ) * axisZ) / step) * step;
			boolean dup = false;
			for (double existing : offsets) {
				dup |= Math.abs(existing - o) < 1.0e-3;
			}
			if (!dup) {
				offsets.add(o);
			}
		}
		offsets.sort(Double::compare);

		List<RoutePlanner.Issue> issues = new ArrayList<>();
		if (offsets.size() < 2) {
			issues.add(new RoutePlanner.Issue("traverser_need_stop", p1, 0));
		}
		List<Vec3d> newNodes = new ArrayList<>();
		List<LayoutPlan.Edge> edges = new ArrayList<>();
		List<Integer> stubEdges = new ArrayList<>();
		List<NodeRef[]> connRefs = new ArrayList<>();
		for (double o : offsets) {
			Vec3d atA = new Vec3d(p0.x + axisX * o, y, p0.z + axisZ * o);
			Vec3d atB = new Vec3d(p1.x + axisX * o, y, p1.z + axisZ * o);
			boolean base = Math.abs(o) < 1.0e-3;
			NodeRef a = MovingDeckPlans.connection(network, base ? first : null, atA, -dirX, -dirZ, newNodes, edges, stubEdges, issues);
			NodeRef b = MovingDeckPlans.connection(network, base ? second : null, atB, dirX, dirZ, newNodes, edges, stubEdges, issues);
			connRefs.add(new NodeRef[]{a, b});
		}
		NodeRef deckRefA = NodeRef.created(newNodes.size());
		newNodes.add(p0);
		NodeRef deckRefB = NodeRef.created(newNodes.size());
		newNodes.add(new Vec3d(p1.x, y, p1.z));
		int deckEdge = edges.size();
		edges.add(new LayoutPlan.Edge(deckRefA, deckRefB, MovingDeckPlans.straight(p0, new Vec3d(p1.x, y, p1.z))));

		double minO = offsets.get(0);
		double maxO = offsets.get(offsets.size() - 1);
		int baseIndex = offsets.indexOf(0.0);
		List<Double> finalOffsets = List.copyOf(offsets);
		LayoutPlan.FeatureSpec spec = new LayoutPlan.FeatureSpec() {
			@Override
			public TrackFeature create(long featureId, LayoutPlan.IdResolver ids) {
				List<MovingDeckFeature.Stop> stops = new ArrayList<>();
				for (int i = 0; i < finalOffsets.size(); i++) {
					NodeRef[] refs = connRefs.get(i);
					stops.add(new MovingDeckFeature.Stop(finalOffsets.get(i),
							refs[0] == null ? -1L : ids.node(refs[0]), refs[1] == null ? -1L : ids.node(refs[1])));
				}
				List<Long> stubs = new ArrayList<>();
				for (int index : stubEdges) {
					stubs.add(ids.segment(index));
				}
				return new TraverserFeature(featureId, ids.segment(deckEdge), ids.node(deckRefA), ids.node(deckRefB),
						stops, stubs, y, 0.0, Math.max(0, baseIndex), p0.x, p0.z, p1.x, p1.z, axisX, axisZ);
			}

			@Override
			public Set<BlockPos> clearance(RailwayConfig.Values config) {
				Set<BlockPos> set = new HashSet<>();
				double halfWidth = config.clearanceHalfWidth();
				for (double along = 0.0; along <= length + 1.0e-6; along += 0.5) {
					for (double across = minO - halfWidth; across <= maxO + halfWidth + 1.0e-6; across += 0.5) {
						double x = p0.x + dirX * along + axisX * across;
						double z = p0.z + dirZ * along + axisZ * across;
						MovingDeckPlans.column(set, (int) Math.floor(x), (int) Math.floor(z), y, config);
					}
				}
				return set;
			}

			@Override
			public List<Vec3d[]> outline() {
				Vec3d c0 = new Vec3d(p0.x + axisX * minO, y + 0.1, p0.z + axisZ * minO);
				Vec3d c1 = new Vec3d(p1.x + axisX * minO, y + 0.1, p1.z + axisZ * minO);
				Vec3d c2 = new Vec3d(p1.x + axisX * maxO, y + 0.1, p1.z + axisZ * maxO);
				Vec3d c3 = new Vec3d(p0.x + axisX * maxO, y + 0.1, p0.z + axisZ * maxO);
				return List.of(new Vec3d[]{c0, c1}, new Vec3d[]{c1, c2}, new Vec3d[]{c2, c3}, new Vec3d[]{c3, c0});
			}
		};
		return new LayoutPlan(newNodes, List.of(), edges, List.of(), List.of(spec), issues,
				length + stubEdges.size() * MovingDeckPlans.STUB_LENGTH);
	}
}
