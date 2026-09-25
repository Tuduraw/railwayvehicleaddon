package com.example.railwayvehicleaddon.track;

import com.example.railwayvehicleaddon.RailwayConfig;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 経由点列から線路の線形を生成する。クライアントのプレビューとサーバーの確定処理で
 * 同じ入力から同じ結果になるよう、ワールドの状態には一切依存しない。
 *
 * <p>平面線形: 弦長パラメータの3次スプライン(C2連続)で経由点を必ず通る曲線を作り、
 * 区間ごとに3次ベジェへ変換する。C2連続なので経由点の前後で曲率(=カント)が連続し、
 * 経由点でレールがねじれたり車体が揺れたりしない。以前のCatmull-Romは接線方向しか
 * 連続せず、経由点で曲率が跳んでいた。
 * 端の条件は、既存線路へ接続する端は接線固定、それ以外は自然端(曲率0。直線へ滑らかにつながる)。
 * 環状線は周期スプラインで、全経由点で曲率が連続する。
 *
 * <p>縦断線形: 経由点間を一定勾配で結び、勾配変化点に放物線の縦曲線を入れる
 * (HeightProfile参照)。既存線路に接続した端では縦曲線を入れない。
 */
public final class RoutePlanner {
	public static final double MIN_WAYPOINT_SPACING = 2.0;

	private RoutePlanner() {
	}

	public static PlannedRoute plan(List<Vec3d> waypoints, double[] startTangent, double[] endTangent, RailwayConfig.Values config) {
		return plan(waypoints, startTangent, endTangent, false, config);
	}

	/**
	 * @param waypoints    経由点(線路基面の位置)。開路線は2点以上、環状線は3点以上
	 * @param startTangent 始点で線路が向かう水平単位ベクトル(既存線路からの延長時)。nullなら自然端
	 * @param endTangent   終点に到達するときの水平単位ベクトル(既存線路への接続時)。nullなら自然端
	 * @param closed       環状線(最後の経由点から最初の経由点へ戻る区間も作る)
	 */
	public static PlannedRoute plan(List<Vec3d> waypoints, double[] startTangent, double[] endTangent,
									boolean closed, RailwayConfig.Values config) {
		List<Issue> issues = new ArrayList<>();
		List<PlannedSegment> segments = new ArrayList<>();
		int n = waypoints.size();
		if (n < 2 || (closed && n < 3)) {
			return new PlannedRoute(segments, issues, 0.0, closed);
		}
		if (n > config.maxWaypoints()) {
			issues.add(new Issue("too_many_waypoints", waypoints.get(n - 1), config.maxWaypoints()));
		}
		int intervals = closed ? n : n - 1;
		double[] h = new double[intervals];
		for (int i = 0; i < intervals; i++) {
			Vec3d a = waypoints.get(i);
			Vec3d b = waypoints.get((i + 1) % n);
			h[i] = Math.hypot(b.x - a.x, b.z - a.z);
			if (h[i] < MIN_WAYPOINT_SPACING) {
				issues.add(new Issue("waypoints_too_close", b, MIN_WAYPOINT_SPACING));
				return new PlannedRoute(segments, issues, 0.0, closed);
			}
		}

		// 平面線形: 各経由点での接線(弦長パラメータに対する微分。大きさはほぼ1)
		double[] mx = new double[n];
		double[] mz = new double[n];
		solveTangents(waypoints, h, closed, startTangent, endTangent, mx, mz);
		List<PlanCurve> curves = new ArrayList<>(intervals);
		for (int i = 0; i < intervals; i++) {
			int j = (i + 1) % n;
			Vec3d a = waypoints.get(i);
			Vec3d b = waypoints.get(j);
			double k = h[i] / 3.0;
			curves.add(new PlanCurve(a.x, a.z, a.x + mx[i] * k, a.z + mz[i] * k,
					b.x - mx[j] * k, b.z - mz[j] * k, b.x, b.z));
		}

		// 縦断線形
		double[] lengths = new double[intervals];
		double[] grades = new double[intervals];
		double total = 0.0;
		for (int i = 0; i < intervals; i++) {
			lengths[i] = curves.get(i).length();
			grades[i] = (waypoints.get((i + 1) % n).y - waypoints.get(i).y) / lengths[i];
			total += lengths[i];
		}
		// 経由点vの縦曲線の片側長さ(開路線の両端は0)
		double[] half = new double[n];
		for (int v = 0; v < n; v++) {
			boolean interior = closed || (v > 0 && v < n - 1);
			if (!interior) {
				continue;
			}
			double before = lengths[(v - 1 + intervals) % intervals];
			double after = lengths[v % intervals];
			half[v] = Math.max(0.0, Math.min(config.verticalCurveLength() / 2.0, Math.min(before, after) / 2.0));
		}
		for (int i = 0; i < intervals; i++) {
			int j = (i + 1) % n;
			boolean hasPrev = closed || i > 0;
			boolean hasNext = closed || i + 1 < intervals;
			double gPrev = hasPrev ? grades[(i - 1 + intervals) % intervals] : grades[i];
			double gNext = hasNext ? grades[(i + 1) % intervals] : grades[i];
			HeightProfile profile = new HeightProfile(waypoints.get(i).y, waypoints.get(j).y,
					gPrev, gNext, hasPrev ? half[i] : 0.0, hasNext ? half[j] : 0.0);
			segments.add(new PlannedSegment(curves.get(i), profile));
		}

		// 検証
		if (total > config.maxRouteLength()) {
			issues.add(new Issue("route_too_long", waypoints.get(n - 1), (double) config.maxRouteLength()));
		}
		for (int i = 0; i < segments.size(); i++) {
			if (Math.abs(grades[i]) > config.maxGrade() + 1.0e-6) {
				Vec3d mid = waypoints.get(i).add(waypoints.get((i + 1) % n)).multiply(0.5);
				issues.add(new Issue("grade_too_steep", mid, Math.abs(grades[i]) * 1000.0));
			}
			checkRadius(segments.get(i), config, issues);
		}
		return new PlannedRoute(segments, issues, total, closed);
	}

	/** 最小曲線半径の検査。同じ場所の報告は1つにまとめる。 */
	public static void checkRadius(PlannedSegment segment, RailwayConfig.Values config, List<Issue> issues) {
		PlanCurve plan = segment.plan();
		double length = plan.length();
		int steps = Math.max(8, (int) Math.ceil(length / 0.5));
		double best = 0.0;
		double bestS = 0.0;
		for (int i = 0; i <= steps; i++) {
			double s = length * i / steps;
			double k = Math.abs(plan.signedCurvature(plan.tAt(s)));
			if (k > best) {
				best = k;
				bestS = s;
			}
		}
		if (best <= 1.0e-9 || 1.0 / best >= config.minCurveRadius() - 1.0e-6) {
			return;
		}
		double t = plan.tAt(bestS);
		Vec3d at = new Vec3d(plan.x(t), segment.profile().y(bestS, length), plan.z(t));
		for (Issue existing : issues) {
			if (existing.key().equals("radius_too_small") && existing.position().squaredDistanceTo(at) < 4.0) {
				return;
			}
		}
		issues.add(new Issue("radius_too_small", at, 1.0 / best));
	}

	/**
	 * 3次スプラインの接線を解く。内部の経由点では2階微分の連続条件
	 * h[i] m[i-1] + 2(h[i-1]+h[i]) m[i] + h[i-1] m[i+1] = 3(h[i] d[i-1] + h[i-1] d[i])
	 * (d = 区間の平均の傾き)を、端では接線固定か自然端(2階微分0)を課す。
	 */
	private static void solveTangents(List<Vec3d> p, double[] h, boolean closed, double[] startTangent,
									  double[] endTangent, double[] mx, double[] mz) {
		int n = p.size();
		double[] a = new double[n];
		double[] b = new double[n];
		double[] c = new double[n];
		double[] rx = new double[n];
		double[] rz = new double[n];
		for (int i = 0; i < n; i++) {
			boolean interior = closed || (i > 0 && i < n - 1);
			if (interior) {
				int prev = (i - 1 + n) % n;
				int next = (i + 1) % n;
				double hp = h[(i - 1 + h.length) % h.length];
				double hn = h[i % h.length];
				double dpx = (p.get(i).x - p.get(prev).x) / hp;
				double dpz = (p.get(i).z - p.get(prev).z) / hp;
				double dnx = (p.get(next).x - p.get(i).x) / hn;
				double dnz = (p.get(next).z - p.get(i).z) / hn;
				a[i] = hn;
				b[i] = 2.0 * (hp + hn);
				c[i] = hp;
				rx[i] = 3.0 * (hn * dpx + hp * dnx);
				rz[i] = 3.0 * (hn * dpz + hp * dnz);
			} else if (i == 0) {
				if (startTangent != null) {
					b[i] = 1.0;
					rx[i] = startTangent[0];
					rz[i] = startTangent[1];
				} else {
					b[i] = 2.0;
					c[i] = 1.0;
					rx[i] = 3.0 * (p.get(1).x - p.get(0).x) / h[0];
					rz[i] = 3.0 * (p.get(1).z - p.get(0).z) / h[0];
				}
			} else {
				if (endTangent != null) {
					b[i] = 1.0;
					rx[i] = endTangent[0];
					rz[i] = endTangent[1];
				} else {
					a[i] = 1.0;
					b[i] = 2.0;
					rx[i] = 3.0 * (p.get(i).x - p.get(i - 1).x) / h[i - 1];
					rz[i] = 3.0 * (p.get(i).z - p.get(i - 1).z) / h[i - 1];
				}
			}
		}
		if (closed) {
			solveCyclic(a, b, c, rx, mx);
			solveCyclic(a, b, c, rz, mz);
		} else {
			solveTridiagonal(a, b, c, rx, mx);
			solveTridiagonal(a, b, c, rz, mz);
		}
	}

	/** 三重対角行列の解法(Thomas法)。a[i]は下対角(i-1列)、c[i]は上対角(i+1列)。 */
	private static void solveTridiagonal(double[] a, double[] b, double[] c, double[] r, double[] out) {
		int n = b.length;
		double[] cp = new double[n];
		double[] rp = new double[n];
		cp[0] = c[0] / b[0];
		rp[0] = r[0] / b[0];
		for (int i = 1; i < n; i++) {
			double m = b[i] - a[i] * cp[i - 1];
			cp[i] = i < n - 1 ? c[i] / m : 0.0;
			rp[i] = (r[i] - a[i] * rp[i - 1]) / m;
		}
		out[n - 1] = rp[n - 1];
		for (int i = n - 2; i >= 0; i--) {
			out[i] = rp[i] - cp[i] * out[i + 1];
		}
	}

	/** 巡回三重対角行列(a[0]が右上隅、c[n-1]が左下隅)の解法。Sherman-Morrisonで三重対角に帰着する。 */
	private static void solveCyclic(double[] a, double[] b, double[] c, double[] r, double[] out) {
		int n = b.length;
		double alpha = c[n - 1];
		double beta = a[0];
		double gamma = -b[0];
		double[] bb = b.clone();
		bb[0] = b[0] - gamma;
		bb[n - 1] = b[n - 1] - alpha * beta / gamma;
		double[] aa = a.clone();
		double[] cc = c.clone();
		aa[0] = 0.0;
		cc[n - 1] = 0.0;
		double[] x = new double[n];
		solveTridiagonal(aa, bb, cc, r, x);
		double[] u = new double[n];
		u[0] = gamma;
		u[n - 1] = alpha;
		double[] z = new double[n];
		solveTridiagonal(aa, bb, cc, u, z);
		double fact = (x[0] + beta * x[n - 1] / gamma) / (1.0 + z[0] + beta * z[n - 1] / gamma);
		for (int i = 0; i < n; i++) {
			out[i] = x[i] - fact * z[i];
		}
	}

	public record PlannedSegment(PlanCurve plan, HeightProfile profile) {
		/** 設計速度を与えてTrackSegmentとして扱う(ID・ノードは仮)。プレビューや判定用。 */
		public TrackSegment asSegment(float designSpeed) {
			return new TrackSegment(-1L, -1L, -1L, this.plan, this.profile, designSpeed);
		}
	}

	/**
	 * 線形上の問題。keyは翻訳キー message.railwayvehicleaddon.issue.<key> の末尾。
	 * valueはメッセージに埋め込む数値(半径・勾配‰など)。
	 */
	public record Issue(String key, Vec3d position, double value) {
		/** メッセージ埋め込み用の表記(整数値は小数点なし)。 */
		public String formattedValue() {
			return this.value == Math.rint(this.value) ? String.format("%.0f", this.value) : String.format("%.1f", this.value);
		}
	}

	public record PlannedRoute(List<PlannedSegment> segments, List<Issue> issues, double totalLength, boolean closed) {
		public boolean isValid() {
			return this.issues.isEmpty() && !this.segments.isEmpty();
		}
	}
}
