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
 *   "smoke": [ { "x": 0.0, "y": 4.2, "z": 8.9, "type": "campfire", "rate": 2.5, "idle_rate": 0.25 } ],
 *   "articulated": [ { "part": "$tender", "hinge_y": 1.3, "hinge_z": -6.0, "rear_z": -11.0 } ],
 *   "wheels": [ { "part": "$driver0", "pivot_y": 0.8, "pivot_z": 4.9, "radius": 0.8 } ],
 *   "rods": [ { "part": "$rod_l", "type": "coupling", "axle_y": 0.8, "axle_z": 3.1, "crank_radius": 0.36, "wheel_radius": 0.8, "phase_deg": 0 } ]
 * }
 * </pre>
 *
 * @param bogies         台車。carry=trueのうちpivot_zが最大のものを前台車、最小のものを後台車として線路に載せる
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
 * @param wheelSpin      wheels・rodsの回し方。"throttle"(既定。スロットルに応じた速さで回し、速度が追いつかない間は空転して見える。
 *                       スロットル0の惰行中は走行に合わせて回る)、"distance"(常に走った距離どおりに回る)
 */
public record RailVehicleParams(List<Bogie> bogies, float brake, float resistance, float gradeGravity,
								int powerNotches, int reverseNotches, float mass, String powerSource,
								float couplerFront, float couplerRear, List<SmokeEmitter> smoke, java.util.Optional<Boolean> powered,
								Extras extras) {

	public static final String FUEL = "fuel";
	public static final String STEAM = "steam";
	public static final String ELECTRIC = "electric";

	public static final RailVehicleParams DEFAULT =
			new RailVehicleParams(List.of(), 0.015f, 0.0003f, 0.04f, 0, 0, 1.0f, FUEL, Float.NaN, Float.NaN, List.of(), java.util.Optional.empty(), Extras.DEFAULT);

	public List<Articulated> articulated() {
		return this.extras.articulated();
	}

	public List<Wheel> wheels() {
		return this.extras.wheels();
	}

	public List<Rod> rods() {
		return this.extras.rods();
	}

	public String wheelSpin() {
		return this.extras.wheelSpin();
	}

	public java.util.Optional<Sounds> sounds() {
		return this.extras.sounds();
	}

	/**
	 * 表示・音に関する追加項目。JSONでは "rail" 直下に並べて書く(入れ子にはしない)。
	 * 項目数がコーデックの上限を超えないよう、まとめて1つのフィールドにしている。
	 */
	public record Extras(List<Articulated> articulated, List<Wheel> wheels, List<Rod> rods, String wheelSpin,
						 java.util.Optional<Sounds> sounds) {
		public static final Extras DEFAULT = new Extras(List.of(), List.of(), List.of(), "throttle", java.util.Optional.empty());
		public static final com.mojang.serialization.MapCodec<Extras> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Articulated.CODEC.listOf().optionalFieldOf("articulated", List.of()).forGetter(Extras::articulated),
				Wheel.CODEC.listOf().optionalFieldOf("wheels", List.of()).forGetter(Extras::wheels),
				Rod.CODEC.listOf().optionalFieldOf("rods", List.of()).forGetter(Extras::rods),
				Codec.STRING.optionalFieldOf("wheel_spin", "throttle").forGetter(Extras::wheelSpin),
				Sounds.CODEC.optionalFieldOf("sounds").forGetter(Extras::sounds)
		).apply(instance, Extras::new));
	}

	/**
	 * 動作音。音の名前はOGGのファイル名(拡張子なし。assets/&lt;名前空間&gt;/sounds/ に置く。前提MODのengine_soundと同じ扱い)。
	 * 空文字にするとその音を鳴らさない。省略した項目は動力(power_source)に応じた既定の音になる。
	 *
	 * @param running       走行音(ループ。速度に応じて音量・高さが変わる)
	 * @param joint         レールの継ぎ目を通る音(台車の車軸ごとに鳴る)
	 * @param jointSpacing  継ぎ目の間隔(ブロック)。0以下なら継ぎ目の音を鳴らさない(ロングレール)
	 * @param brake         停車間際のブレーキのきしみ(ループ)
	 * @param motor         主電動機・エンジンの音(ループ)
	 * @param motorType     "electric"(速度で高さが変わる)、"engine"(スロットルで回転数=高さが変わり、停車中もアイドリング)
	 * @param motorPitchMin 高さの下限(停止・アイドル時)
	 * @param motorPitchMax 高さの上限(最高速度・全開時)
	 * @param chuff         蒸気機関車のドラフト音(動輪1回転にchuffs_per_rev回、スロットルに応じた音量で鳴る)
	 * @param chuffsPerRev  動輪1回転あたりのドラフト音の回数(既定1)
	 * @param horn          警笛・汽笛(運転者が警笛キーを押すと鳴る)
	 * @param couple        連結・解放の音
	 * @param air           停車時の空気の排出音
	 * @param volume        音全体の大きさ(聞こえる距離も比例して伸びる)
	 */
	public record Sounds(java.util.Optional<String> running, java.util.Optional<String> joint, float jointSpacing,
						 java.util.Optional<String> brake, java.util.Optional<String> motor, java.util.Optional<String> motorType,
						 float motorPitchMin, float motorPitchMax, java.util.Optional<String> chuff, int chuffsPerRev,
						 java.util.Optional<String> horn, java.util.Optional<String> couple, java.util.Optional<String> air, float volume) {
		public static final Codec<Sounds> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.optionalFieldOf("running").forGetter(Sounds::running),
				Codec.STRING.optionalFieldOf("joint").forGetter(Sounds::joint),
				Codec.FLOAT.optionalFieldOf("joint_spacing", 25.0f).forGetter(Sounds::jointSpacing),
				Codec.STRING.optionalFieldOf("brake").forGetter(Sounds::brake),
				Codec.STRING.optionalFieldOf("motor").forGetter(Sounds::motor),
				Codec.STRING.optionalFieldOf("motor_type").forGetter(Sounds::motorType),
				Codec.FLOAT.optionalFieldOf("motor_pitch_min", Float.NaN).forGetter(Sounds::motorPitchMin),
				Codec.FLOAT.optionalFieldOf("motor_pitch_max", Float.NaN).forGetter(Sounds::motorPitchMax),
				Codec.STRING.optionalFieldOf("chuff").forGetter(Sounds::chuff),
				Codec.INT.optionalFieldOf("chuffs_per_rev", 1).forGetter(Sounds::chuffsPerRev),
				Codec.STRING.optionalFieldOf("horn").forGetter(Sounds::horn),
				Codec.STRING.optionalFieldOf("couple").forGetter(Sounds::couple),
				Codec.STRING.optionalFieldOf("air").forGetter(Sounds::air),
				Codec.FLOAT.optionalFieldOf("volume", 1.0f).forGetter(Sounds::volume)
		).apply(instance, Sounds::new));
	}

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
	 * @param carry  車体を載せる台車か(既定true)。trueの台車のうち最前・最後の2点で車体の位置と向きを決める。
	 *               falseは「線路に追随するだけの台車」(蒸気機関車の先台車・従台車、炭水車の台車など)で、
	 *               車体の位置には影響せず、表示上は線路の真上へ横にずれて線路の向きに回る
	 */
	public record Bogie(String part, float pivotX, float pivotY, float pivotZ, boolean carry) {
		public static final Codec<Bogie> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.optionalFieldOf("part", "").forGetter(Bogie::part),
				Codec.FLOAT.optionalFieldOf("pivot_x", 0.0f).forGetter(Bogie::pivotX),
				Codec.FLOAT.optionalFieldOf("pivot_y", 0.0f).forGetter(Bogie::pivotY),
				Codec.FLOAT.fieldOf("pivot_z").forGetter(Bogie::pivotZ),
				Codec.BOOL.optionalFieldOf("carry", true).forGetter(Bogie::carry)
		).apply(instance, Bogie::new));
	}

	/**
	 * 関節でつながった部分(蒸気機関車の炭水車など)。表示だけで、物理的な位置には影響しない。
	 * <ul>
	 *   <li>front_z を指定した場合(推奨): 前後2点(front_z・rear_z。通常は炭水車の前後の台車の中心)をそれぞれ線路に載せた、
	 *       独立した車体として置く。実車と同じく、曲線では機関車の後端と炭水車の前端がどちらも曲線の外側へ同じくらい張り出す</li>
	 *   <li>front_z を省略した場合: 前端の関節(hinge)を車体に固定したまま、後端(rear_z)が線路の上に来るように関節まわりに振る。
	 *       車体の張り出しが大きい車両では、急曲線で関節ごと線路の外側へずれる</li>
	 * </ul>
	 *
	 * @param part   OBJグループ名
	 * @param hingeY 関節(または載せる点)の高さ(モデル座標)
	 * @param hingeZ 関節の位置(モデル座標のZ。front_z省略時に使う)
	 * @param rearZ  線路に載せる後側の点(モデル座標のZ)
	 * @param frontZ 線路に載せる前側の点(モデル座標のZ)。NaN(省略)なら関節で振る方式
	 */
	public record Articulated(String part, float hingeY, float hingeZ, float rearZ, float frontZ) {
		public static final Codec<Articulated> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("part").forGetter(Articulated::part),
				Codec.FLOAT.optionalFieldOf("hinge_y", 1.0f).forGetter(Articulated::hingeY),
				Codec.FLOAT.optionalFieldOf("hinge_z", 0.0f).forGetter(Articulated::hingeZ),
				Codec.FLOAT.fieldOf("rear_z").forGetter(Articulated::rearZ),
				Codec.FLOAT.optionalFieldOf("front_z", Float.NaN).forGetter(Articulated::frontZ)
		).apply(instance, Articulated::new));
	}

	/**
	 * 走行に合わせて回る車輪(動輪など)。車軸(X軸に平行、高さpivot_y・位置pivot_z)まわりに、走った距離÷半径だけ回る。
	 */
	public record Wheel(String part, float pivotY, float pivotZ, float radius) {
		public static final Codec<Wheel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("part").forGetter(Wheel::part),
				Codec.FLOAT.fieldOf("pivot_y").forGetter(Wheel::pivotY),
				Codec.FLOAT.fieldOf("pivot_z").forGetter(Wheel::pivotZ),
				Codec.FLOAT.fieldOf("radius").forGetter(Wheel::radius)
		).apply(instance, Wheel::new));
	}

	/**
	 * 車輪の回転に連動して動くロッド類。クランクピンは車軸(axle_y・axle_z)から半径crank_radius、
	 * 角度phase_deg(0=真上、正で前方へ)の位置にあり、車輪(半径wheel_radius)と一緒に回る。
	 * モデルはクランク角0(=回転していない状態)の位置で作っておく。
	 *
	 * @param type        "coupling"(連結棒。クランクピンと一緒に平行移動する)、
	 *                    "main"(主連棒。一端はクランクピン、他端はクロスヘッドで、クロスヘッドは水平に滑る)、
	 *                    "crosshead"(クロスヘッド・ピストン棒。前後に滑るだけ)
	 * @param length      主連棒の長さ(クランクピン〜クロスヘッド。main・crossheadで使う)
	 * @param crossheadY  クロスヘッドの高さ(main・crossheadで使う)
	 * @param direction   シリンダーがある向き(1=前方、-1=後方)
	 */
	public record Rod(String part, String type, float axleY, float axleZ, float crankRadius, float wheelRadius,
					  float phaseDeg, float length, float crossheadY, float direction) {
		public static final Codec<Rod> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("part").forGetter(Rod::part),
				Codec.STRING.optionalFieldOf("type", "coupling").forGetter(Rod::type),
				Codec.FLOAT.fieldOf("axle_y").forGetter(Rod::axleY),
				Codec.FLOAT.fieldOf("axle_z").forGetter(Rod::axleZ),
				Codec.FLOAT.fieldOf("crank_radius").forGetter(Rod::crankRadius),
				Codec.FLOAT.fieldOf("wheel_radius").forGetter(Rod::wheelRadius),
				Codec.FLOAT.optionalFieldOf("phase_deg", 0.0f).forGetter(Rod::phaseDeg),
				Codec.FLOAT.optionalFieldOf("length", 0.0f).forGetter(Rod::length),
				Codec.FLOAT.optionalFieldOf("crosshead_y", 0.0f).forGetter(Rod::crossheadY),
				Codec.FLOAT.optionalFieldOf("direction", 1.0f).forGetter(Rod::direction)
		).apply(instance, Rod::new));
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
			Codec.BOOL.optionalFieldOf("powered").forGetter(RailVehicleParams::powered),
			Extras.MAP_CODEC.forGetter(RailVehicleParams::extras)
	).apply(instance, RailVehicleParams::new));
}
