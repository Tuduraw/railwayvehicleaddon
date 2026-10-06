package com.example.railwayvehicleaddon.vehicle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * 鉄道車両固有の設定。前提MODの車両JSONに "rail" オブジェクトとして書く
 * (前提MODのJSON読み込みは未知のキーを無視するため、同じファイルに同居できる)。
 * 加速の追従度・最高速度・スロットル変化の速さは、前提MODの共通項目
 * (acceleration・max_speed・throttle_up_down)をそのまま使う。
 *
 * <pre>
 * "rail": {
 *   "bogies": [
 *     { "part": "$bogie_front", "pivot_x": 0.0, "pivot_y": 0.45, "pivot_z": 4.5 },
 *     { "part": "$bogie_rear",  "pivot_x": 0.0, "pivot_y": 0.45, "pivot_z": -4.5 }
 *   ],
 *   "brake": 0.015,
 *   "resistance": 0.0003,
 *   "grade_gravity": 0.04,
 *   "power_notches": 7,
 *   "reverse_notches": 2,
 *   "mass": 1.0,
 *   "power_source": "fuel",
 *   "coupler_front": 4.5,
 *   "coupler_rear": -4.5,
 *   "smoke": [ { "x": 0.0, "y": 4.2, "z": 8.9, "type": "campfire", "rate": 2.5, "idle_rate": 0.25 } ]
 * }
 * </pre>
 *
 * @param bogies         台車。pivot_zが最大のものを前台車、最小のものを後台車として線路に載せる
 * @param brake          ブレーキ時の減速度(ブロック/tick²)
 * @param resistance     惰行時の走行抵抗による減速度(ブロック/tick²)
 * @param gradeGravity   勾配で車両にかかる重力加速度(ブロック/tick²)。勾配を掛けた分が加減速になる
 * @param powerNotches   前進側のノッチ数。前進・後進とも0ならノッチ式を使わず、前提MODと同じスロットル式になる
 * @param reverseNotches 後進側のノッチ数
 * @param mass           重さ(編成の加速に使う)。単車では加速に影響しない
 * @param powerSource    動力: "fuel"(前提MODの燃料)、"steam"(石炭・木炭と水)、"electric"(架線から給電)
 * @param couplerFront   連結器の位置(モデル座標のZ、前側)。未指定(NaN)なら台車の位置をそのまま使う
 *                       (車体が台車より外へ張り出している車両では連結時に車体が重なって見えるため、
 *                       車体の実際の前端に合わせて指定することを推奨する)
 * @param couplerRear    連結器の位置(モデル座標のZ、後側)。未指定(NaN)の扱いはcouplerFrontと同じ
 * @param smoke          排煙(蒸気機関車の煙突・ディーゼルの排気など)。運転中または走行中に、出力に応じて煙を出す
 * @param powered        動力車か(引張力を持つか)。省略時は「運転席がある車両=動力車」。運転台の無い電動車(モハ等)は true、
 *                       運転台はあるが動力の無い制御車(クハ等)は false にする。編成では運転者のノッチが全動力車に伝わる(総括制御)
 */
public record RailVehicleParams(List<Bogie> bogies, float brake, float resistance, float gradeGravity,
								int powerNotches, int reverseNotches, float mass, String powerSource,
								float couplerFront, float couplerRear, List<SmokeEmitter> smoke, java.util.Optional<Boolean> powered) {

	public static final String FUEL = "fuel";
	public static final String STEAM = "steam";
	public static final String ELECTRIC = "electric";

	public static final RailVehicleParams DEFAULT =
			new RailVehicleParams(List.of(), 0.015f, 0.0003f, 0.04f, 0, 0, 1.0f, FUEL, Float.NaN, Float.NaN, List.of(), java.util.Optional.empty());

	/** ノッチ式か(前進・後進どちらかのノッチ数が1以上)。 */
	public boolean usesNotches() {
		return this.powerNotches > 0 || this.reverseNotches > 0;
	}

	/** ノッチ位置(正=前進、負=後進)に対応するスロットルの目標値。ノッチ数で等分する。 */
	public float notchTarget(int notch) {
		if (notch > 0 && this.powerNotches > 0) {
			return (float) notch / this.powerNotches;
		}
		if (notch < 0 && this.reverseNotches > 0) {
			return (float) notch / this.reverseNotches;
		}
		return 0f;
	}

	/**
	 * @param part   台車のOBJグループ名(省略可。省略すると線路への載せ位置の指定だけに使われ、表示上は回転しない)
	 * @param pivotX 回転中心(モデル座標)
	 * @param pivotY 回転中心(モデル座標)
	 * @param pivotZ 回転中心(モデル座標)。scaleを掛けた値が線路上の載せ位置になる
	 */
	public record Bogie(String part, float pivotX, float pivotY, float pivotZ) {
		public static final Codec<Bogie> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.optionalFieldOf("part", "").forGetter(Bogie::part),
				Codec.FLOAT.optionalFieldOf("pivot_x", 0.0f).forGetter(Bogie::pivotX),
				Codec.FLOAT.optionalFieldOf("pivot_y", 0.0f).forGetter(Bogie::pivotY),
				Codec.FLOAT.fieldOf("pivot_z").forGetter(Bogie::pivotZ)
		).apply(instance, Bogie::new));
	}

	/**
	 * 排煙の発生源。
	 *
	 * @param x        発生位置(モデル座標。scaleが掛かる)
	 * @param y        発生位置(モデル座標)
	 * @param z        発生位置(モデル座標)
	 * @param type     煙の種類: "smoke"(黒っぽい煙。短く消える)、"campfire"(高く立ちのぼる灰白色の煙。蒸気機関車向け)、"steam"(白い蒸気)
	 * @param rate     出力100%のときの1tickあたりの粒子数(小数は確率で出す)
	 * @param idleRate 停車中・惰行中(運転者がいるか走行中)の1tickあたりの粒子数
	 */
	public record SmokeEmitter(float x, float y, float z, String type, float rate, float idleRate) {
		public static final Codec<SmokeEmitter> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.FLOAT.optionalFieldOf("x", 0.0f).forGetter(SmokeEmitter::x),
				Codec.FLOAT.optionalFieldOf("y", 0.0f).forGetter(SmokeEmitter::y),
				Codec.FLOAT.optionalFieldOf("z", 0.0f).forGetter(SmokeEmitter::z),
				Codec.STRING.optionalFieldOf("type", "smoke").forGetter(SmokeEmitter::type),
				Codec.FLOAT.optionalFieldOf("rate", 1.0f).forGetter(SmokeEmitter::rate),
				Codec.FLOAT.optionalFieldOf("idle_rate", 0.1f).forGetter(SmokeEmitter::idleRate)
		).apply(instance, SmokeEmitter::new));
	}

	public static final Codec<RailVehicleParams> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Bogie.CODEC.listOf().optionalFieldOf("bogies", List.of()).forGetter(RailVehicleParams::bogies),
			Codec.FLOAT.optionalFieldOf("brake", DEFAULT.brake).forGetter(RailVehicleParams::brake),
			Codec.FLOAT.optionalFieldOf("resistance", DEFAULT.resistance).forGetter(RailVehicleParams::resistance),
			Codec.FLOAT.optionalFieldOf("grade_gravity", DEFAULT.gradeGravity).forGetter(RailVehicleParams::gradeGravity),
			Codec.INT.optionalFieldOf("power_notches", 0).forGetter(RailVehicleParams::powerNotches),
			Codec.INT.optionalFieldOf("reverse_notches", 0).forGetter(RailVehicleParams::reverseNotches),
			Codec.FLOAT.optionalFieldOf("mass", 1.0f).forGetter(RailVehicleParams::mass),
			Codec.STRING.optionalFieldOf("power_source", FUEL).forGetter(RailVehicleParams::powerSource),
			Codec.FLOAT.optionalFieldOf("coupler_front", Float.NaN).forGetter(RailVehicleParams::couplerFront),
			Codec.FLOAT.optionalFieldOf("coupler_rear", Float.NaN).forGetter(RailVehicleParams::couplerRear),
			SmokeEmitter.CODEC.listOf().optionalFieldOf("smoke", List.of()).forGetter(RailVehicleParams::smoke),
			Codec.BOOL.optionalFieldOf("powered").forGetter(RailVehicleParams::powered)
	).apply(instance, RailVehicleParams::new));
}
