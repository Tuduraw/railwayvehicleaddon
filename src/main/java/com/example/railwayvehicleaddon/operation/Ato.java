package com.example.railwayvehicleaddon.operation;

import java.util.List;

/**
 * ATO(自動運転)の速度の計算。ワールドに依らない計算だけを持つ(試験できるように)。
 *
 * <p>先読みで集めた「制約」(編成の先端からの道のり d と、そこで守る速度 vₗ。停止は vₗ = 0)ごとに
 * {@code √(vₗ² + 2·b·d)} を求め、最も小さいものと上限(車両の最高速度・今の制限速度)の小さい方を目標速度にする。
 * 停止の制約の最後の数ブロックは、目標速度を残り距離に比例した低い速度にして、定位置に止める。
 * 速度の単位はブロック/tick(1ブロック/tick = 72km/h)。
 */
public final class Ato {
	/** 1km/h(ブロック/tick) */
	public static final double KMH = 1.0 / 72.0;
	/** 停止の制約で、この距離(ブロック)より近づいたら目標速度を0にする */
	public static final double STOP_TOLERANCE = 0.25;
	/** 停止の制約で、残り距離に比例した低い速度に切り替える距離(ブロック) */
	private static final double FINAL_APPROACH = 4.0;
	/** 定位置停止の最後の、残り距離に比例した速度の係数と最低速度 */
	private static final double FINAL_GAIN = 0.06;
	private static final double FINAL_MIN_SPEED = 1.5 * KMH;
	/** この速度差(目標より遅い)以上なら全力で力行する */
	private static final double POWER_BAND = 3.0 * KMH;
	/** 目標よりこれだけ速くなったらブレーキを掛ける */
	private static final double BRAKE_BAND = 1.0 * KMH;
	/** ブレーキを掛けるときの最小の強さ */
	private static final double MIN_BRAKE = 0.15;

	private Ato() {
	}

	/** 先読みの制約。distanceは編成の先端から、その速度を守る点までの道のり(余裕を引いた後)。 */
	public record Constraint(double distance, double limit) {
		public static Constraint stop(double distance) {
			return new Constraint(distance, 0.0);
		}
	}

	/** 目標速度と、それを決めた制約(上限で決まった場合はnull)。 */
	public record Target(double speed, Constraint binding) {
	}

	/** 制約cが許す速度。 */
	public static double allowed(Constraint c, double brake) {
		double d = Math.max(0.0, c.distance());
		if (c.limit() <= 0.0) {
			if (d <= STOP_TOLERANCE) {
				return 0.0;
			}
			double pattern = Math.sqrt(2.0 * brake * d);
			if (d < FINAL_APPROACH) {
				pattern = Math.min(pattern, Math.max(FINAL_MIN_SPEED, d * FINAL_GAIN));
			}
			return pattern;
		}
		return Math.sqrt(c.limit() * c.limit() + 2.0 * brake * d);
	}

	/**
	 * @param brake 常用ブレーキの減速度(ブロック/tick²)
	 * @param cap   速度の上限(ブロック/tick)
	 */
	public static Target target(List<Constraint> constraints, double brake, double cap) {
		double best = Math.max(0.0, cap);
		Constraint binding = null;
		for (Constraint c : constraints) {
			double v = allowed(c, brake);
			if (v < best) {
				best = v;
				binding = c;
			}
		}
		return new Target(best, binding);
	}

	/**
	 * 指令。handleは力行の強さ(0〜1。目標速度 = handle × maxSpeed へ近づく前提MODの車の加速に合わせる)、
	 * brakeはブレーキの強さ(0〜1。車両のbrakeに掛ける)。
	 */
	public record Command(float handle, double brake) {
		public static final Command HOLD = new Command(0f, 1.0);
	}

	/**
	 * 今の速度vと目標から指令を決める。
	 *
	 * @param brakeDecel 編成の最大のブレーキの減速度(ブロック/tick²)
	 * @param maxSpeed   ハンドル1のときの目標速度(編成の動力車の最高速度)
	 */
	public static Command control(double v, Target target, double brakeDecel, double maxSpeed) {
		double vStar = target.speed();
		if (vStar <= 1.0e-4) {
			return v > 1.0e-4 ? new Command(0f, 1.0) : Command.HOLD;
		}
		if (v > vStar + BRAKE_BAND) {
			double need;
			Constraint c = target.binding();
			if (c != null && c.distance() > STOP_TOLERANCE) {
				// 制約の点までに、その速度へ落とすのに要る減速度
				need = (v * v - c.limit() * c.limit()) / (2.0 * c.distance());
			} else {
				// 上限を超えた(制限速度の区間に入った・勾配で増速した): 数tickで戻す
				need = (v - vStar) / 10.0;
			}
			double strength = brakeDecel > 1.0e-9 ? need / brakeDecel : 1.0;
			return new Command(0f, Math.max(MIN_BRAKE, Math.min(1.0, strength)));
		}
		if (maxSpeed <= 1.0e-9) {
			return new Command(0f, 0.0);
		}
		if (v < vStar - POWER_BAND) {
			return new Command(1f, 0.0);
		}
		// 目標速度の近く: 目標速度へ近づく強さで力行する(超えていれば惰行)
		return new Command((float) Math.min(1.0, vStar / maxSpeed), 0.0);
	}
}
