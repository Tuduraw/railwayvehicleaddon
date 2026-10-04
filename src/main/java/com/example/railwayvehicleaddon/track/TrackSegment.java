package com.example.railwayvehicleaddon.track;

/**
 * 2つのノード間を結ぶ線路区間。平面線形(PlanCurve)と縦断線形(HeightProfile)を持つ。
 * 距離sは水平距離で、0がノードA、length()がノードB。
 *
 * @param designSpeed 設計速度(km/h)。カントの計算に使う
 * @param ballast        道床の見た目(BallastType.id())
 * @param electrification 電化方式(ElectrificationType.id())。給電の可否には方式を区別しない(NONE以外なら等しく給電対象)
 */
public record TrackSegment(long id, long nodeA, long nodeB, PlanCurve plan, HeightProfile profile, float designSpeed,
						   int ballast, int electrification) {

	public TrackSegment(long id, long nodeA, long nodeB, PlanCurve plan, HeightProfile profile, float designSpeed) {
		this(id, nodeA, nodeB, plan, profile, designSpeed, BallastType.GRAVEL.id(), ElectrificationType.NONE.id());
	}

	public TrackSegment(long id, long nodeA, long nodeB, PlanCurve plan, HeightProfile profile, float designSpeed, int ballast) {
		this(id, nodeA, nodeB, plan, profile, designSpeed, ballast, ElectrificationType.NONE.id());
	}

	/** 電化方式だけを変えた区間。 */
	public TrackSegment withElectrification(int value) {
		return new TrackSegment(this.id, this.nodeA, this.nodeB, this.plan, this.profile, this.designSpeed, this.ballast, value);
	}

	public BallastType ballastType() {
		return BallastType.byId(this.ballast);
	}

	public ElectrificationType electrificationType() {
		return ElectrificationType.byId(this.electrification);
	}

	/** 給電の対象になるか(電化方式を問わない)。旧来の"electrified()"に相当する。 */
	public boolean electrified() {
		return this.electrification != ElectrificationType.NONE.id();
	}

	/** カントの上限(ラジアン)は敷設時の設定に依存させず、この値に固定する。 */
	public static final double MAX_CANT_RAD = Math.toRadians(6.0);

	public double length() {
		return this.plan.length();
	}

	public TrackPoint sample(double s) {
		double length = this.plan.length();
		double clamped = Math.max(0.0, Math.min(length, s));
		double t = this.plan.tAt(clamped);
		double dx = this.plan.dx(t);
		double dz = this.plan.dz(t);
		double speed = Math.hypot(dx, dz);
		if (speed < 1.0e-9) {
			dx = this.plan.x(1.0) - this.plan.x(0.0);
			dz = this.plan.z(1.0) - this.plan.z(0.0);
			speed = Math.max(1.0e-9, Math.hypot(dx, dz));
		}
		double curvature = this.plan.signedCurvature(t);
		return new TrackPoint(this.plan.x(t), this.profile.y(clamped, length), this.plan.z(t),
				dx / speed, dz / speed, this.profile.gradeAt(clamped, length), cantFor(curvature, this.designSpeed));
	}

	/** 均衡カント tanθ = v² / (g R) を上限付きで求める。 */
	public static double cantFor(double signedCurvature, float designSpeedKmh) {
		if (designSpeedKmh <= 0f || Math.abs(signedCurvature) < 1.0e-6) {
			return 0.0;
		}
		double v = designSpeedKmh / 3.6;
		double angle = Math.atan(v * v * Math.abs(signedCurvature) / 9.8);
		return Math.copySign(Math.min(angle, MAX_CANT_RAD), signedCurvature);
	}

	/** この区間の反対側のノード。nodeがどちらの端でもなければ-1。 */
	public long otherNode(long node) {
		if (node == this.nodeA) {
			return this.nodeB;
		}
		if (node == this.nodeB) {
			return this.nodeA;
		}
		return -1L;
	}
}
