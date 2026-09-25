package com.example.railwayvehicleaddon.track;

/**
 * 線路の平面線形(XZ平面)を表す3次ベジェ曲線。高さはHeightProfileが別に持つ。
 *
 * <p>平面と縦断を分離しているのは、経由点間の勾配を一定に保つため(3次元のまま
 * 曲線補間すると坂の途中で勾配が波打つ)。距離sはすべて「水平距離」で、
 * 勾配も水平距離あたりの高低差として定義する。
 *
 * <p>弧長パラメータ化は構築時にルックアップテーブルを作って行う。
 * 同じ制御点からは常に同じテーブルが得られるため、サーバーとクライアントで
 * 同じ位置計算結果になる。
 */
public final class PlanCurve {
	private final double x0, z0, x1, z1, x2, z2, x3, z3;
	/** lut[i] = t = i / (n - 1) における累積弧長 */
	private final double[] lut;
	private final double length;

	public PlanCurve(double x0, double z0, double x1, double z1, double x2, double z2, double x3, double z3) {
		this.x0 = x0;
		this.z0 = z0;
		this.x1 = x1;
		this.z1 = z1;
		this.x2 = x2;
		this.z2 = z2;
		this.x3 = x3;
		this.z3 = z3;
		double roughLength = Math.hypot(x1 - x0, z1 - z0) + Math.hypot(x2 - x1, z2 - z1) + Math.hypot(x3 - x2, z3 - z2);
		int samples = (int) Math.max(16, Math.min(8192, Math.ceil(roughLength * 8.0)));
		this.lut = new double[samples];
		double prevX = x0;
		double prevZ = z0;
		double acc = 0.0;
		for (int i = 1; i < samples; i++) {
			double t = (double) i / (samples - 1);
			double px = bezier(x0, x1, x2, x3, t);
			double pz = bezier(z0, z1, z2, z3, t);
			acc += Math.hypot(px - prevX, pz - prevZ);
			this.lut[i] = acc;
			prevX = px;
			prevZ = pz;
		}
		this.length = acc;
	}

	public double length() {
		return this.length;
	}

	public double[] controlPoints() {
		return new double[]{x0, z0, x1, z1, x2, z2, x3, z3};
	}

	public static PlanCurve fromControlPoints(double[] c) {
		return new PlanCurve(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
	}

	/** 直線(転車台・遷車台の桁、接続用の短い線路など)。 */
	public static PlanCurve straight(double ax, double az, double bx, double bz) {
		return new PlanCurve(ax, az, ax + (bx - ax) / 3.0, az + (bz - az) / 3.0,
				ax + (bx - ax) * 2.0 / 3.0, az + (bz - az) * 2.0 / 3.0, bx, bz);
	}

	/** パラメータtで2つに分割する(de Casteljau法。形は元の曲線と完全に一致する)。 */
	public PlanCurve[] split(double t) {
		double ax = lerp(x0, x1, t), az = lerp(z0, z1, t);
		double bx = lerp(x1, x2, t), bz = lerp(z1, z2, t);
		double cx = lerp(x2, x3, t), cz = lerp(z2, z3, t);
		double dx = lerp(ax, bx, t), dz = lerp(az, bz, t);
		double ex = lerp(bx, cx, t), ez = lerp(bz, cz, t);
		double fx = lerp(dx, ex, t), fz = lerp(dz, ez, t);
		return new PlanCurve[]{
				new PlanCurve(x0, z0, ax, az, dx, dz, fx, fz),
				new PlanCurve(fx, fz, ex, ez, cx, cz, x3, z3)
		};
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/** 弧長s(水平距離)に対応するベジェパラメータt。範囲外はクランプする。 */
	public double tAt(double s) {
		if (s <= 0.0) {
			return 0.0;
		}
		if (s >= this.length) {
			return 1.0;
		}
		int lo = 0;
		int hi = this.lut.length - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (this.lut[mid] < s) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		double segLen = this.lut[hi] - this.lut[lo];
		double frac = segLen > 1.0e-9 ? (s - this.lut[lo]) / segLen : 0.0;
		return (lo + frac) / (this.lut.length - 1);
	}

	public double x(double t) {
		return bezier(x0, x1, x2, x3, t);
	}

	public double z(double t) {
		return bezier(z0, z1, z2, z3, t);
	}

	public double dx(double t) {
		return derivative(x0, x1, x2, x3, t);
	}

	public double dz(double t) {
		return derivative(z0, z1, z2, z3, t);
	}

	/**
	 * 符号付き曲率(1/半径)。正 = パラメータ進行方向に対し -X側(モデル座標の右手側)へ曲がる。
	 * 前提MODの機体姿勢(rotationY(-yaw)→rotateX(pitch)→rotateZ(roll))でロールを正にすると
	 * モデルの+X側が持ち上がるため、この符号のままカント(内側を下げる傾き)に使える。
	 */
	public double signedCurvature(double t) {
		double ddx = derivative(x0, x1, x2, x3, t);
		double ddz = derivative(z0, z1, z2, z3, t);
		double d2x = secondDerivative(x0, x1, x2, x3, t);
		double d2z = secondDerivative(z0, z1, z2, z3, t);
		double speed = Math.hypot(ddx, ddz);
		if (speed < 1.0e-9) {
			return 0.0;
		}
		return (ddx * d2z - ddz * d2x) / (speed * speed * speed);
	}

	private static double bezier(double p0, double p1, double p2, double p3, double t) {
		double u = 1.0 - t;
		return u * u * u * p0 + 3.0 * u * u * t * p1 + 3.0 * u * t * t * p2 + t * t * t * p3;
	}

	private static double derivative(double p0, double p1, double p2, double p3, double t) {
		double u = 1.0 - t;
		return 3.0 * u * u * (p1 - p0) + 6.0 * u * t * (p2 - p1) + 3.0 * t * t * (p3 - p2);
	}

	private static double secondDerivative(double p0, double p1, double p2, double p3, double t) {
		return 6.0 * (1.0 - t) * (p2 - 2.0 * p1 + p0) + 6.0 * t * (p3 - 2.0 * p2 + p1);
	}
}
