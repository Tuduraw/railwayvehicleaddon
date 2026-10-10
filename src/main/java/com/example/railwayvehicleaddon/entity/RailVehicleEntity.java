package com.example.railwayvehicleaddon.entity;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.track.TrackManager;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackPoint;
import com.example.railwayvehicleaddon.track.TrackPos;
import com.example.railwayvehicleaddon.track.TrackSegment;
import com.example.railwayvehicleaddon.vehicle.RailVehicleParams;
import com.example.railwayvehicleaddon.vehicle.RailVehicleParamsLoader;
import com.example.tudursvehiclemod.asset.VehicleDefinition;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import com.example.tudursvehiclemod.entity.FreeCameraVehicle;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.util.math.Box;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;

/**
 * 独自レール上を走る鉄道車両。
 *
 * <p><b>位置の決め方</b>: 線路上の位置(TrackPos)は前後台車の中間点を表す。前後台車をそれぞれ
 * 線路に載せ、車体原点は2つの台車の接地点を結ぶ直線上に置く。ヨー・ピッチはこの直線の向き、
 * ロールは台車位置のカントの平均。これによりカーブで車体端が外へはみ出す動きが自然に出る。
 *
 * <p><b>同期</b>: 位置を決めるのはサーバーだけ。前提MODの他の乗り物(運転者のクライアントが
 * 動かして送る方式)と違い、isControlledByPlayer()/isControlledByMainPlayer()をfalseにして
 * VehicleMoveパケット自体を送らせない。クライアントは同期された「区間・距離・向き・速度」から
 * 同じ線路データで位置を自分で計算し、サーバーから届く通常の位置パケットの影響は毎tick
 * 打ち消す(restorePlacedState参照。高速走行時・カーブでのバニラ補間のがたつきを避けるため)。
 * 通信遅延による差は、速度で先読みしたうえで少しずつ詰める。
 */
public class RailVehicleEntity extends AbstractVehicleEntity implements FreeCameraVehicle {

	/** 線路基面からレール上面までの高さ(TrackRendererの描画寸法と一致させる) */
	public static final double RAIL_TOP = 0.25;
	/** 線路に載っていないときに近くの線路を探す距離 */
	private static final double ATTACH_RADIUS = 6.0;
	private static final int ATTACH_RETRY_TICKS = 20;
	/** クライアント位置の補正で、1tickに詰める誤差の割合 */
	private static final double CLIENT_CORRECTION = 0.3;
	/** これ以上ずれていたら補正せずサーバー位置へ飛ばす */
	private static final double CLIENT_SNAP_DISTANCE = 4.0;

	private static final TrackedData<Long> TRACK_SEGMENT =
			DataTracker.registerData(RailVehicleEntity.class, TrackedDataHandlerRegistry.LONG);
	private static final TrackedData<Float> TRACK_S =
			DataTracker.registerData(RailVehicleEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Byte> TRACK_FACING =
			DataTracker.registerData(RailVehicleEntity.class, TrackedDataHandlerRegistry.BYTE);
	private static final TrackedData<Float> RAIL_SPEED =
			DataTracker.registerData(RailVehicleEntity.class, TrackedDataHandlerRegistry.FLOAT);
	/** 台車の設定。クライアントがマルチプレイでもサーバーのデータを知らずに済むよう文字列で同期する */
	/** 排煙の発生源(rail.smoke)。rail設定はサーバー側のデータなので、BOGIE_SPECと同じく文字列で同期する */
	private static final TrackedData<String> SMOKE_SPEC =
			DataTracker.registerData(RailVehicleEntity.class, TrackedDataHandlerRegistry.STRING);
	private static final TrackedData<String> BOGIE_SPEC =
			DataTracker.registerData(RailVehicleEntity.class, TrackedDataHandlerRegistry.STRING);
	/** 関節部(炭水車)・回る車輪・ロッドの設定(rail.articulated / wheels / rods)。BOGIE_SPECと同じく文字列で同期する */
	private static final TrackedData<String> ANIM_SPEC =
			DataTracker.registerData(RailVehicleEntity.class, TrackedDataHandlerRegistry.STRING);
	/** 動作音の設定(rail.sounds と動力から決めた既定値)。BOGIE_SPECと同じく文字列で同期する */
	private static final TrackedData<String> SOUND_SPEC =
			DataTracker.registerData(RailVehicleEntity.class, TrackedDataHandlerRegistry.STRING);
	/** ノッチを押し続けたときに次の段へ進むまでのtick数(最初の1段目の後と、それ以降) */
	private static final int NOTCH_REPEAT_FIRST = 10;
	private static final int NOTCH_REPEAT = 5;
	/** 石炭・木炭1個で燃やせる時間(出力100%でのtick数) */
	private static final int COAL_TICKS = 1600;

	/** 蒸気機関車のHUD表示用(HudVariableProviderで"rail_fire_seconds"・"rail_coal_count"として公開) */
	private static final TrackedData<Float> FIRE_SECONDS_SYNC =
			DataTracker.registerData(RailVehicleEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Integer> COAL_COUNT_SYNC =
			DataTracker.registerData(RailVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);

	// サーバー側
	private TrackPos trackPos;
	private double speed;
	private int attachCooldown;
	private int notch;
	private float lastNotchInput;
	private int notchRepeat;
	/** 蒸気機関車: 火室に残っている燃焼時間(tick、出力100%で1ずつ減る) */
	private double fireTicks;
	private int powerWarningCooldown;

	// ------------------------------------------------------------------ 連結

	/** 自動で連結を試みる、端から相手の端までの距離 */
	private static final double COUPLE_DISTANCE = 0.8;
	/** 連結てこで連結するときに相手を探す、端どうしの距離 */
	private static final double MANUAL_COUPLE_DISTANCE = 2.5;
	/** 連結を外した相手と、自動連結が再び効くようになる端どうしの距離(外した直後に再連結しないため) */
	private static final double REARM_DISTANCE = 2.0;
	/** 連結相手を探す範囲(車両の中心から端までの最大距離。これより長い車両は端で相手を見つけられない) */
	private static final double MAX_HALF_LENGTH = 40.0;
	/** 停車中とみなす速さ(ブロック/tick)。これより遅いときだけ、ノッチ・スロットルを逆方向へ入れられる */
	private static final double STOP_THRESHOLD = 0.005;
	/** 連結した車両間の隙間(前後台車の張り出しに加えて確保する分) */
	private static final double COUPLER_GAP = 0.4;
	/** 連結相手を探す間隔(tick) */
	/** 停車中の車両が連結相手を探す間隔(tick)。走行中は毎tick探す */
	private static final int COUPLE_SCAN_INTERVAL = 10;
	/** この編成の位置更新を先頭車がまとめて済ませたワールド時刻。自分の番が来たときの二重処理を防ぐ */
	private long consistHandledTick = -1L;
	private int coupleScanCooldown;

	/** frontZ側(A)・rearZ側(B)の連結相手。reversedは、相手の車首がこちらと逆向きかどうか(連結時に固定) */
	private Optional<UUID> couplingA = Optional.empty();
	private Optional<UUID> couplingB = Optional.empty();
	private boolean couplingAReversed;
	private boolean couplingBReversed;
	/** 連結を外した相手(端ごと)。離れるまで自動連結しない。保存する */
	private Optional<UUID> uncoupledA = Optional.empty();
	private Optional<UUID> uncoupledB = Optional.empty();
	private int reverserWarningCooldown;
	private int hornCooldown;
	private String parsedSoundSpec = null;
	private SoundSpec soundSpec = null;

	// クライアント側
	private TrackPos clientPos;
	private long seenSegment = Long.MIN_VALUE;
	private float seenS;
	private byte seenFacing;
	private int ticksSinceServerUpdate;
	/** 前tickに自分で計算した位置・角度(サーバーの位置パケットで上書きされた分を戻すため) */
	private boolean hasPlacedState;
	private double placedX;
	private double placedY;
	private double placedZ;
	private float placedYaw;
	private float placedPitch;

	// 両側(表示・配置)
	private String parsedSpec = null;
	private List<BogieSpec> bogies = List.of();
	private double frontZ = 1.0;
	private double rearZ = -1.0;
	// 表示(クライアント): 線路に追随する部品の姿勢(モデル座標の変換行列)と、車輪の回転量
	private Map<String, Matrix4f> partPoses = Map.of();
	private Map<String, Matrix4f> prevPartPoses = Map.of();
	private String parsedAnimSpec = null;
	private List<ArticulatedSpec> articulatedParts = List.of();
	private List<WheelSpec> wheelParts = List.of();
	private List<RodSpec> rodParts = List.of();
	/** 走った距離の累計(ブロック、車両の前向きを正)。車輪・ロッドの回転角に使う */
	private double rolled;
	private double prevRolled;
	/** 車輪の回転の速さ(周速、ブロック/tick)。空転を表すためスロットルに引っ張られる */
	private double spinSpeed;
	private boolean spinByThrottle = true;
	private boolean placedOnce;

	public RailVehicleEntity(EntityType<?> type, World world) {
		super(type, world);
	}

	@Override
	protected Identifier defaultDefinitionId() {
		return Identifier.of(RailwayVehicleAddon.MOD_ID, "sample_railcar");
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(TRACK_SEGMENT, -1L);
		builder.add(TRACK_S, 0f);
		builder.add(TRACK_FACING, (byte) 1);
		builder.add(RAIL_SPEED, 0f);
		builder.add(BOGIE_SPEC, "");
		builder.add(ANIM_SPEC, "");
		builder.add(SOUND_SPEC, "");
		builder.add(SMOKE_SPEC, "");
		builder.add(FIRE_SECONDS_SYNC, 0f);
		builder.add(COAL_COUNT_SYNC, 0);
	}

	/**
	 * サーバーだけが位置を決める(クラスの説明参照)。
	 * Entity.isLogicalSideForUpdatingMovement()はfinalで、クライアントでは
	 * isControlledByMainPlayer()、サーバーでは!isControlledByPlayer()を返す。両方をfalseにすると
	 * 運転者がいてもクライアントはVehicleMoveパケットを送らず、サーバーが常に位置の正本になる。
	 */
	@Override
	public boolean isControlledByMainPlayer() {
		return false;
	}

	@Override
	public boolean isControlledByPlayer() {
		return false;
	}

	/** 火室の残り燃焼時間(秒)。蒸気機関車のHUD表示用。 */
	public float getFireSeconds() {
		return this.dataTracker.get(FIRE_SECONDS_SYNC);
	}

	/** インベントリに残っている石炭・木炭・石炭ブロックの個数(ブロックも1個として数える)。 */
	public int getCoalCount() {
		return this.dataTracker.get(COAL_COUNT_SYNC);
	}

	public double getRailSpeed() {
		return this.dataTracker.get(RAIL_SPEED);
	}

	public boolean isOnTrack() {
		return this.dataTracker.get(TRACK_SEGMENT) >= 0L;
	}

	/** 現在載っている区間(中心)のID。載っていなければ-1。給電計算(TrackManager)から呼ばれる。 */
	public long currentSegmentId() {
		return this.trackPos == null ? -1L : this.trackPos.segmentId();
	}

	// ------------------------------------------------------------------ 移動

	@Override
	protected void updateVehicleMovement(VehicleDefinition def) {
		if (this.getEntityWorld().isClient()) {
			clientMovement();
			return;
		}
		ServerWorld world = (ServerWorld) this.getEntityWorld();
		if (this.reverserWarningCooldown > 0) {
			this.reverserWarningCooldown--;
		}
		if (this.hornCooldown > 0) {
			this.hornCooldown--;
		}
		if (this.consistHandledTick == world.getTime()) {
			// この編成の位置は、今tickすでに先頭車がまとめて更新済み
			return;
		}
		TrackNetwork network = TrackManager.network(world);
		RailVehicleParams params = RailVehicleParamsLoader.get(this.getVehicleDefinitionId()).orElse(RailVehicleParams.DEFAULT);
		updateBogieSpec(params);
		syncStatus(params);
		syncSmokeSpec(params);

		if (this.trackPos != null && network.segment(this.trackPos.segmentId()) == null) {
			// 区間が分割された(線路の途中に分岐器・渡り線を入れた)なら新しい区間へ載せ替え、
			// 撤去されたなら線路から外れる
			this.trackPos = network.resolveMoved(this.trackPos);
		}
		if (this.trackPos == null) {
			if (this.attachCooldown-- <= 0) {
				this.attachCooldown = ATTACH_RETRY_TICKS;
				tryAttach(network);
			}
			if (this.trackPos == null) {
				derailedMovement(def);
				return;
			}
		}

		// 走行中は毎tick、停車中は一定間隔で連結相手を探す(速いと接触の瞬間を見逃すため)
		if (Math.abs(this.speed) > 1.0e-4 || --this.coupleScanCooldown <= 0) {
			this.coupleScanCooldown = COUPLE_SCAN_INTERVAL;
			tryAutoCouple(world, network);
		}

		List<ConsistMember> consist = buildConsist(world);
		if (consist.size() > 1) {
			RailVehicleEntity leader = electLeader(consist);
			if (leader != this) {
				// 先頭車が今tickのうちにこの車両も含めて位置を更新する(処理順は問わない)
				return;
			}
			if (this.trackPos != null) {
				runConsistPhysics(world, network, consist, world.getTime());
				return;
			}
			// 先頭車自身が未着線なら編成として動かせない(各車がそれぞれ単独車扱いになる)
		}

		float throttle = updateOwnThrottleAndNotch(def, params);
		boolean braking = isOwnBraking();
		// 動力が得られなければ(石炭が無い・架線や給電が無い)力行できない。ハンドル(表示)はそのまま動かせる。
		// 架線の給電が混雑しているときは、0より大きく1未満の割合で力行が弱まる
		float powerFactor = 1f;
		if (throttle != 0f) {
			if (!isPowerUnit(params)) {
				// 運転台はあるが動力の無い車両(制御車)だけで走らせようとしている
				powerFactor = 0f;
				warnDriver("message.railwayvehicleaddon.power.no_power_unit");
			} else {
				powerFactor = consumePower(network, params, throttle);
				if (powerFactor <= 0f) {
					warnNoPower(params);
				}
			}
		}

		TrackPoint center = network.pointAt(this.trackPos);
		double grade = center != null ? center.grade() * this.trackPos.facing() : 0.0;
		double maxSpeed = this.tudursvehiclemod$getEffectiveMaxSpeed();
		// 前提MODの車と同じく、スロットルに応じた目標速度へacceleration(追従度)で近づく。
		// ノッチを下げた・切にしたときは減速させず惰行する(走行抵抗で少しずつ落ちる)
		double acceleration = def.acceleration();
		double effectiveThrottle = throttle * powerFactor;
		double target = effectiveThrottle * maxSpeed;
		double v = this.speed;
		boolean powering = effectiveThrottle > 0 ? target > v : effectiveThrottle < 0 && target < v;
		if (powering) {
			v += (target - v) * acceleration;
		}
		v -= params.gradeGravity() * grade / Math.sqrt(1.0 + grade * grade);
		double decel = (powering ? 0.0 : params.resistance()) + (braking ? params.brake() : 0.0);
		if (Math.abs(v) <= decel) {
			v = 0.0;
		} else {
			v -= Math.copySign(decel, v);
		}
		if (drivingPlayer() == null && Math.abs(v) < 1.0e-3) {
			v = 0.0;
		}
		v = MathHelper.clamp(v, -maxSpeed, maxSpeed);

		if (v != 0.0) {
			TrackNetwork.Walk moved = network.walk(this.trackPos, v);
			double leadOffset = (v > 0 ? this.frontZ : this.rearZ) - centerZ();
			TrackNetwork.Walk lead = network.walk(moved.pos(), leadOffset);
			if (moved.blocked() || lead.blocked()) {
				// 車止めに当たった
				v = 0.0;
			} else {
				this.trackPos = moved.pos();
			}
		}
		this.speed = v;
		this.cruiseSpeed = (float) v;

		this.dataTracker.set(TRACK_SEGMENT, this.trackPos.segmentId());
		this.dataTracker.set(TRACK_S, (float) this.trackPos.s());
		this.dataTracker.set(TRACK_FACING, (byte) this.trackPos.facing());
		this.dataTracker.set(RAIL_SPEED, (float) v);
		applyPlacement(network, this.trackPos);
	}

	/** この車自身の運転者の入力からノッチ・スロットルを更新する(連結の有無に関わらず共通)。 */
	private float updateOwnThrottleAndNotch(VehicleDefinition def, RailVehicleParams params) {
		PlayerEntity driver = drivingPlayer();
		float handleStep = 0.03f * def.throttleUpDown().orElse(1.0f);
		if (driver != null) {
			// 走行中は進行方向と逆側へハンドルを入れられない(停車してからでないと逆転できない)
			float min = this.speed > STOP_THRESHOLD ? 0f : -1.0f;
			float max = this.speed < -STOP_THRESHOLD ? 0f : 1.0f;
			float throttle = params.usesNotches() ? updateNotch(params, handleStep)
					: updateThrottle(driver, handleStep, -1.0f, 1.0f);
			if (throttle < min || throttle > max) {
				throttle = MathHelper.clamp(throttle, min, max);
				this.setThrottleDirect(throttle);
				if (!params.usesNotches()) {
					warnReverser();
				}
			}
			return throttle;
		}
		// 無人の車両は停止保持(連結先の判断に使われないよう、常にノッチ切のまま)
		this.setThrottleDirect(0f);
		this.notch = 0;
		return 0f;
	}

	private void warnReverser() {
		if (drivingPlayer() instanceof net.minecraft.server.network.ServerPlayerEntity player && this.reverserWarningCooldown <= 0) {
			this.reverserWarningCooldown = 40;
			player.sendMessage(net.minecraft.text.Text.translatable("message.railwayvehicleaddon.reverser_locked"), true);
		}
	}

	private boolean isOwnBraking() {
		return drivingPlayer() == null || this.getSyncedBrakeInput() || this.tudursvehiclemod$isDestroyed();
	}

	/** 動力車か。rail.powered を指定していなければ、運転席がある車両を動力車とみなす(従来どおり)。 */
	public boolean isPowerUnit(RailVehicleParams params) {
		return params.powered().orElseGet(this::hasDriverSeat);
	}

	/** 運転者にだけ、一定間隔でアクションバーに知らせる。 */
	private void warnDriver(String key) {
		if (drivingPlayer() instanceof net.minecraft.server.network.ServerPlayerEntity player && this.powerWarningCooldown-- <= 0) {
			this.powerWarningCooldown = 60;
			player.sendMessage(net.minecraft.text.Text.translatable(key), true);
		}
	}

	private void warnNoPower(RailVehicleParams params) {
		if (drivingPlayer() instanceof net.minecraft.server.network.ServerPlayerEntity player
				&& this.powerWarningCooldown-- <= 0) {
			this.powerWarningCooldown = 60;
			player.sendMessage(net.minecraft.text.Text.translatable(RailVehicleParams.STEAM.equals(params.powerSource())
					? "message.railwayvehicleaddon.power.no_coal" : "message.railwayvehicleaddon.power.no_catenary"), true);
		}
	}

	/**
	 * ノッチ式の操作。前進/後進キー(前提MODのスロットル入力)を押すたびにノッチが1段ずつ動き、
	 * 押し続けると一定間隔で進む。スロットル(前提MODのHUDにハンドル位置として表示される値)は、
	 * ノッチの目標値へ前提MODと同じ速さ(0.03 × throttle_up_down / tick)で動く。
	 * 燃料切れ・撃破時はノッチを切(0)に戻す。
	 */
	private float updateNotch(RailVehicleParams params, float step) {
		float input = this.getSyncedThrottleInput();
		int direction = input > 0 ? 1 : input < 0 ? -1 : 0;
		// 走行中は進行方向と逆側のノッチに入れられない(勾配で逆向きに動き出した場合も、ノッチを切に戻す)
		int lower = this.speed > STOP_THRESHOLD ? 0 : -params.reverseNotches();
		int upper = this.speed < -STOP_THRESHOLD ? 0 : params.powerNotches();
		this.notch = MathHelper.clamp(this.notch, lower, upper);
		if (this.tudursvehiclemod$isOutOfFuel() || this.tudursvehiclemod$isDestroyed()) {
			this.notch = 0;
		} else if (direction != 0) {
			boolean pressed = Math.signum(this.lastNotchInput) != direction;
			if (pressed || --this.notchRepeat <= 0) {
				int wanted = this.notch + direction;
				this.notch = MathHelper.clamp(wanted, lower, upper);
				boolean withinRange = wanted <= params.powerNotches() && wanted >= -params.reverseNotches();
				if (wanted != this.notch && withinRange) {
					// 走行中に逆側へ入れようとした
					warnReverser();
				}
				this.notchRepeat = pressed ? NOTCH_REPEAT_FIRST : NOTCH_REPEAT;
			}
		}
		this.lastNotchInput = input;
		float target = params.notchTarget(this.notch);
		float current = this.getThrottle();
		float next = Math.abs(target - current) <= step ? target : current + Math.copySign(step, target - current);
		this.setThrottleDirect(next);
		return next;
	}

	/**
	 * 動力を得る。得られればtrue。
	 * <ul>
	 *   <li>fuel: 前提MODの燃料システムに任せる(燃料切れはスロットル側で0になる)</li>
	 *   <li>steam: 車両のインベントリの石炭・木炭(石炭ブロック)を火室へくべて燃やす。
	 *       水は前提MODの燃料を水として使い、給水塔で補給する</li>
	 *   <li>electric: 車両(中心・前後の台車のいずれか)が電化区間にいれば架線から給電される</li>
	 * </ul>
	 */
	/**
	 * 動力を得る。得られた割合(0.0〜1.0)を返す。0は力行不可。
	 * <ul>
	 *   <li>fuel: 前提MODの燃料システムに任せる(常に1。燃料切れはスロットル側で0になる)</li>
	 *   <li>steam: 車両のインベントリの石炭・木炭(石炭ブロック)を火室へくべて燃やす。
	 *       水は前提MODの燃料を水として使い、給水塔で補給する(常に0か1)</li>
	 *   <li>electric: 車両(中心・前後の台車のいずれか)がいる区間の給電係数(変電所の供給能力に対する
	 *       需要の割合。混雑時は1未満になる)。架線が無い・給電が無ければ0</li>
	 * </ul>
	 */
	private float consumePower(TrackNetwork network, RailVehicleParams params, float throttle) {
		String source = params.powerSource();
		if (RailVehicleParams.STEAM.equals(source)) {
			if (this.fireTicks <= 0.0) {
				for (int i = 0; i < this.size(); i++) {
					ItemStack stack = this.getStack(i);
					int ticks = stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL) ? COAL_TICKS
							: stack.isOf(Items.COAL_BLOCK) ? COAL_TICKS * 10 : 0;
					if (ticks > 0) {
						this.removeStack(i, 1);
						this.fireTicks += ticks;
						break;
					}
				}
			}
			if (this.fireTicks <= 0.0) {
				return 0f;
			}
			this.fireTicks -= Math.abs(throttle);
			return 1f;
		}
		if (RailVehicleParams.ELECTRIC.equals(source)) {
			if (this.trackPos == null) {
				return 0f;
			}
			double zc = centerZ();
			double best = 0.0;
			for (double offset : new double[]{0.0, this.frontZ - zc, this.rearZ - zc}) {
				long segmentId = network.walk(this.trackPos, offset).segmentId();
				best = Math.max(best, network.electricFactor(segmentId));
			}
			return (float) best;
		}
		return 1f;
	}

	// ------------------------------------------------------------------ 連結

	// ------------------------------------------------------------------ 運転席と視点

	/**
	 * 運転席(座席定義の driver が true)を持つ車両か。前提MODは「座席0の乗員」を常に操縦者
	 * (getControllingPassenger)として扱うため、運転席の無い客車・砲車でも座席0の乗客が操縦者に
	 * なってしまう。このアドオンでは、運転席を持つ車両だけを動力車として扱う。
	 */
	public boolean hasDriverSeat() {
		for (com.example.tudursvehiclemod.asset.SeatDefinition seat : this.getDefinition().seats()) {
			if (seat.driver()) {
				return true;
			}
		}
		return false;
	}

	/** 実際に運転している(運転席のある車両の座席0にいる)プレイヤー。いなければnull。 */
	public PlayerEntity drivingPlayer() {
		if (!hasDriverSeat()) {
			return null;
		}
		return this.getControllingPassenger() instanceof PlayerEntity player ? player : null;
	}

	/**
	 * 運転席の無い車両(客車・砲車など)では、座席0の乗員も含めて全員をフリールック扱いにする。
	 * FreeCameraVehicleを実装しているため、前提MODは座席0の乗員を「視点で操縦する操縦者」とみなし、
	 * 視点を車両に固定したうえ、照準付きの武器を既定の向きに固定してしまう(航空機向けの仕様)。
	 * 運転席のある車両はこれまでどおり(運転者の視点は車両に連動。照準はフリールック中のみ)。
	 */
	@Override
	public boolean tudursvehiclemod$isEffectiveFreeLook(Entity viewer) {
		if (!hasDriverSeat()) {
			return true;
		}
		return super.tudursvehiclemod$isEffectiveFreeLook(viewer);
	}

	// ------------------------------------------------------------------ 連結てこ・連結解放キー

	/**
	 * 連結てこを持って車両を右クリックすると、プレイヤーに近い方の端で、つながっていれば連結を外し、
	 * つながっていなければ近く(2.5ブロック以内)の車両と連結する。通常の右クリック(乗車)・
	 * スニーク+右クリック(前提MODの収納)とは衝突しないよう、連結てこを持っているときだけ割り込む。
	 */
	@Override
	public ActionResult interact(PlayerEntity player, Hand hand) {
		if (player.getStackInHand(hand).isOf(com.example.railwayvehicleaddon.item.ModItems.COUPLING_LEVER)) {
			if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
				useCouplingLever(player, serverWorld, TrackManager.network(serverWorld));
			}
			return ActionResult.SUCCESS;
		}
		return super.interact(player, hand);
	}

	private void useCouplingLever(PlayerEntity player, ServerWorld world, TrackNetwork network) {
		Vec3d a = endPosition(network, true);
		Vec3d b = endPosition(network, false);
		if (a == null || b == null) {
			return;
		}
		boolean aEnd = player.getEntityPos().squaredDistanceTo(a) <= player.getEntityPos().squaredDistanceTo(b);
		if (uncoupleEnd(aEnd)) {
			player.sendMessage(net.minecraft.text.Text.translatable("message.railwayvehicleaddon.uncoupled"), true);
			return;
		}
		if (coupleAt(world, network, aEnd, MANUAL_COUPLE_DISTANCE, true)) {
			player.sendMessage(net.minecraft.text.Text.translatable("message.railwayvehicleaddon.coupled"), true);
		} else {
			player.sendMessage(net.minecraft.text.Text.translatable("message.railwayvehicleaddon.no_coupling_partner"), true);
		}
	}

	/**
	 * 連結解放キー(乗車中)。片側だけつながっていればその側を、両側つながっていればプレイヤーが
	 * 向いている側を外す。
	 */
	public void uncoupleByKey(net.minecraft.server.network.ServerPlayerEntity player) {
		if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		boolean hasA = this.couplingA.isPresent();
		boolean hasB = this.couplingB.isPresent();
		if (!hasA && !hasB) {
			player.sendMessage(net.minecraft.text.Text.translatable("message.railwayvehicleaddon.not_coupled"), true);
			return;
		}
		boolean aEnd;
		if (hasA != hasB) {
			aEnd = hasA;
		} else {
			TrackNetwork network = TrackManager.network(serverWorld);
			Vec3d a = endPosition(network, true);
			Vec3d b = endPosition(network, false);
			if (a == null || b == null) {
				return;
			}
			Vec3d eye = player.getEyePos();
			Vec3d look = player.getRotationVec(1.0f);
			aEnd = look.dotProduct(a.subtract(eye).normalize()) >= look.dotProduct(b.subtract(eye).normalize());
		}
		if (uncoupleEnd(aEnd)) {
			player.sendMessage(net.minecraft.text.Text.translatable("message.railwayvehicleaddon.uncoupled"), true);
		}
	}

	/** 連結の有無による車体の性能や編成内での位置を表す。offsetは先頭車の中心からの符号付き距離(先頭車基準)。 */
	private record ConsistMember(RailVehicleEntity entity, double offset, int sign) {
	}

	/**
	 * TrackPosの基準点(台車位置から求めたcenterZ)から見た、その端(A=前側、B=後側)の連結器までの
	 * 局所距離。車両JSONの rail.coupler_front / rail.coupler_rear(モデル座標Z、scale倍する前の値)を
	 * 優先して使う。未指定(NaN)の場合は、台車の位置(frontZ/rearZ)をそのまま使う(車体が台車より
	 * 外側へ張り出している車両では、連結時に車体どうしが重なって見えるため、車体の実際の前後端に
	 * 合わせてこの項目を指定することを推奨する)。
	 */
	private double couplerOffset(boolean aEnd) {
		RailVehicleParams params = RailVehicleParamsLoader.get(this.getVehicleDefinitionId()).orElse(RailVehicleParams.DEFAULT);
		float local = aEnd ? params.couplerFront() : params.couplerRear();
		double zc = centerZ();
		if (Float.isNaN(local)) {
			return (aEnd ? this.frontZ : this.rearZ) - zc;
		}
		return local * this.getScale() - zc;
	}

	/** その端のワールド座標。線路に載っていなければnull。 */
	private Vec3d endPosition(TrackNetwork network, boolean aEnd) {
		if (this.trackPos == null) {
			return null;
		}
		TrackNetwork.Walk walk = network.walk(this.trackPos, couplerOffset(aEnd));
		TrackPoint p = network.pointAt(walk.pos());
		return p == null ? null : new Vec3d(p.x(), p.y(), p.z());
	}

	/** この車の「車首」が向く実世界の水平方向(サンプル点の接線に向きを合わせたもの)。 */
	private double[] noseDirection(TrackNetwork network) {
		if (this.trackPos == null) {
			return null;
		}
		TrackPoint p = network.pointAt(this.trackPos);
		return p == null ? null : new double[]{p.dirX() * this.trackPos.facing(), p.dirZ() * this.trackPos.facing()};
	}

	private boolean endConnectsTo(boolean aEnd, UUID id) {
		return (aEnd ? this.couplingA : this.couplingB).map(id::equals).orElse(false);
	}

	/**
	 * 連結相手を探して自動で連結する。実際の自動連結器のように、互いの端が触れると自動的につながる。
	 * 外すには連結てこで右クリックするか、乗車中に連結解放キーを押す。
	 */
	private void tryAutoCouple(ServerWorld world, TrackNetwork network) {
		if (this.trackPos == null) {
			return;
		}
		for (boolean aEnd : new boolean[]{true, false}) {
			if ((aEnd ? this.couplingA : this.couplingB).isEmpty()) {
				// 走行中は1tickで進む分だけ判定を広げ、速くても接触の瞬間を見逃さないようにする
				coupleAt(world, network, aEnd, COUPLE_DISTANCE + Math.abs(this.speed), false);
			}
		}
	}

	/**
	 * この車の端(aEnd)と、近くの車両の空いている端を連結する。連結できればtrue。
	 * 相手を探す範囲は、相手の車体の長さを考えて端の位置から広く取る(車両の当たり判定の箱は車体の中心に
	 * 小さくあるだけなので、端の近くの箱を探しても見つからない)。互いの端が向き合っていること
	 * (車体どうしが重なっていない・交差する線路の上ではないこと)も確かめる。
	 *
	 * @param manual 連結てこによる操作。連結を外した直後の相手とも連結する
	 */
	private boolean coupleAt(ServerWorld world, TrackNetwork network, boolean aEnd, double maxDistance, boolean manual) {
		if ((aEnd ? this.couplingA : this.couplingB).isPresent()) {
			return false;
		}
		Vec3d myEnd = endPosition(network, aEnd);
		Vec3d myCenter = centerPosition(network);
		if (myEnd == null || myCenter == null) {
			return false;
		}
		Vec3d myOut = myEnd.subtract(myCenter);
		if (myOut.lengthSquared() < 1.0e-6) {
			return false;
		}
		myOut = myOut.normalize();
		Optional<UUID> blocked = aEnd ? this.uncoupledA : this.uncoupledB;
		Box area = new Box(myEnd, myEnd).expand(MAX_HALF_LENGTH + maxDistance, 6.0, MAX_HALF_LENGTH + maxDistance);
		RailVehicleEntity best = null;
		boolean bestEnd = false;
		double bestDist = maxDistance * maxDistance;
		for (RailVehicleEntity other : world.getEntitiesByClass(RailVehicleEntity.class, area, e -> e != this)) {
			if (other.trackPos == null || other.tudursvehiclemod$isDestroyed()) {
				continue;
			}
			Vec3d otherCenter = other.centerPosition(network);
			if (otherCenter == null || otherCenter.subtract(myEnd).dotProduct(myOut) <= 0.0) {
				continue;
			}
			for (boolean otherEnd : new boolean[]{true, false}) {
				if ((otherEnd ? other.couplingA : other.couplingB).isPresent()) {
					continue;
				}
				Vec3d otherPos = other.endPosition(network, otherEnd);
				if (otherPos == null) {
					continue;
				}
				Vec3d otherOut = otherPos.subtract(otherCenter);
				if (otherOut.lengthSquared() < 1.0e-6 || otherOut.normalize().dotProduct(myOut) > -0.7
						|| myCenter.subtract(otherPos).dotProduct(otherOut) <= 0.0) {
					continue;
				}
				double dist = myEnd.squaredDistanceTo(otherPos);
				if (dist > bestDist) {
					continue;
				}
				if (!manual && blocked.map(other.getUuid()::equals).orElse(false)) {
					// 外した直後の相手。十分に離れるまでは自動連結しない
					continue;
				}
				best = other;
				bestEnd = otherEnd;
				bestDist = dist;
			}
		}
		// 外した相手から十分に離れたら、自動連結を再び有効にする
		if (blocked.isPresent()) {
			if (!(world.getEntity(blocked.get()) instanceof RailVehicleEntity former)
					|| former.nearestEndDistance(network, myEnd) > REARM_DISTANCE) {
				if (aEnd) {
					this.uncoupledA = Optional.empty();
				} else {
					this.uncoupledB = Optional.empty();
				}
			}
		}
		if (best == null) {
			return false;
		}
		coupleWith(best, aEnd, bestEnd, network);
		return true;
	}

	private Vec3d centerPosition(TrackNetwork network) {
		if (this.trackPos == null) {
			return null;
		}
		TrackPoint p = network.pointAt(this.trackPos);
		return p == null ? null : new Vec3d(p.x(), p.y(), p.z());
	}

	private double nearestEndDistance(TrackNetwork network, Vec3d point) {
		double best = Double.MAX_VALUE;
		for (boolean end : new boolean[]{true, false}) {
			Vec3d e = endPosition(network, end);
			if (e != null) {
				best = Math.min(best, Math.sqrt(e.squaredDistanceTo(point)));
			}
		}
		return best;
	}

	private void coupleWith(RailVehicleEntity other, boolean selfAEnd, boolean otherAEnd, TrackNetwork network) {
		double[] myNose = noseDirection(network);
		double[] otherNose = other.noseDirection(network);
		boolean reversed = myNose == null || otherNose == null
				|| (myNose[0] * otherNose[0] + myNose[1] * otherNose[1]) < 0.0;
		if (selfAEnd) {
			this.couplingA = Optional.of(other.getUuid());
			this.couplingAReversed = reversed;
			this.uncoupledA = Optional.empty();
		} else {
			this.couplingB = Optional.of(other.getUuid());
			this.couplingBReversed = reversed;
			this.uncoupledB = Optional.empty();
		}
		if (otherAEnd) {
			other.couplingA = Optional.of(this.getUuid());
			other.couplingAReversed = reversed;
			other.uncoupledA = Optional.empty();
		} else {
			other.couplingB = Optional.of(this.getUuid());
			other.couplingBReversed = reversed;
			other.uncoupledB = Optional.empty();
		}
		playRailSound("couple");
		if (this.getControllingPassenger() instanceof net.minecraft.server.network.ServerPlayerEntity player) {
			player.sendMessage(net.minecraft.text.Text.translatable("message.railwayvehicleaddon.coupled"), true);
		}
		if (other.getControllingPassenger() instanceof net.minecraft.server.network.ServerPlayerEntity player) {
			player.sendMessage(net.minecraft.text.Text.translatable("message.railwayvehicleaddon.coupled"), true);
		}
	}

	private boolean uncoupleEnd(boolean aEnd) {
		Optional<UUID> neighborId = aEnd ? this.couplingA : this.couplingB;
		if (neighborId.isEmpty()) {
			return false;
		}
		if (aEnd) {
			this.couplingA = Optional.empty();
			this.uncoupledA = neighborId;
		} else {
			this.couplingB = Optional.empty();
			this.uncoupledB = neighborId;
		}
		playRailSound("couple");
		if (this.getEntityWorld().getEntity(neighborId.get()) instanceof RailVehicleEntity other) {
			Optional<UUID> self = Optional.of(this.getUuid());
			if (other.couplingA.equals(self)) {
				other.couplingA = Optional.empty();
				other.uncoupledA = self;
			}
			if (other.couplingB.equals(self)) {
				other.couplingB = Optional.empty();
				other.uncoupledB = self;
			}
		}
		return true;
	}

	/**
	 * この車を起点に、連結でつながった編成全体を集める(自分自身も含む)。
	 * offsetは「自分の中心から見た、そのメンバーの中心までの符号付き距離」、
	 * signは「自分の前後と同じ向きなら+1、車首が逆向きに連結されていれば-1」。
	 */
	private List<ConsistMember> buildConsist(World world) {
		List<ConsistMember> members = new ArrayList<>();
		Set<UUID> visited = new HashSet<>();
		visited.add(this.getUuid());
		ConsistMember self = new ConsistMember(this, 0.0, 1);
		members.add(self);
		ArrayDeque<ConsistMember> queue = new ArrayDeque<>();
		queue.add(self);
		while (!queue.isEmpty() && members.size() < 64) {
			ConsistMember cur = queue.poll();
			for (boolean aEnd : new boolean[]{true, false}) {
				Optional<UUID> neighborId = aEnd ? cur.entity().couplingA : cur.entity().couplingB;
				if (neighborId.isEmpty() || !visited.add(neighborId.get())) {
					continue;
				}
				if (!(world.getEntity(neighborId.get()) instanceof RailVehicleEntity neighbor)) {
					continue;
				}
				boolean reversed = aEnd ? cur.entity().couplingAReversed : cur.entity().couplingBReversed;
				boolean neighborIsAEnd = neighbor.endConnectsTo(true, cur.entity().getUuid());
				double hop = Math.abs(cur.entity().couplerOffset(aEnd)) + COUPLER_GAP
						+ Math.abs(neighbor.couplerOffset(neighborIsAEnd));
				int direction = aEnd ? 1 : -1;
				double offset = cur.offset() + cur.sign() * direction * hop;
				int sign = cur.sign() * (reversed ? -1 : 1);
				ConsistMember next = new ConsistMember(neighbor, offset, sign);
				members.add(next);
				queue.add(next);
			}
		}
		return members;
	}

	/**
	 * 編成の中で、今tickの位置計算をまとめて行う車を選ぶ。運転者がいる車を優先し(複数いれば
	 * IDが最小のもの)、誰も乗っていなければIDが最小の車にする。結果はどの車から見ても同じになる。
	 */
	private static RailVehicleEntity electLeader(List<ConsistMember> members) {
		RailVehicleEntity leader = null;
		for (ConsistMember m : members) {
			if (m.entity().drivingPlayer() != null
					&& (leader == null || m.entity().getId() < leader.getId())) {
				leader = m.entity();
			}
		}
		if (leader == null) {
			for (ConsistMember m : members) {
				if (leader == null || m.entity().getId() < leader.getId()) {
					leader = m.entity();
				}
			}
		}
		return leader;
	}

	/**
	 * 編成全体の速度を1つだけ計算し(動力車の(acceleration×mass)の合計で重み付けした目標速度・追従度)、
	 * 先頭車(=this)を基準に各車の位置をoffsetぶん歩いた場所へ置く。隊列は常に剛体として扱うため、
	 * 曲線や勾配の途中でも車間は一定に保たれる。
	 */
	private void runConsistPhysics(ServerWorld world, TrackNetwork network, List<ConsistMember> members, long tick) {
		double totalMass = 0.0;
		double weightedAccelMass = 0.0;
		double weightedTargetNum = 0.0;
		double weightedGradeMass = 0.0;
		// 総括制御: 先頭車(=this。運転者がいれば必ず運転している車両)のハンドルを、編成のすべての動力車に伝える。
		// 他の運転台にいる人のノッチ操作は使わない(ブレーキだけは、どの運転台からでも編成全体に効く)
		RailVehicleParams leaderParams = RailVehicleParamsLoader.get(this.getVehicleDefinitionId()).orElse(RailVehicleParams.DEFAULT);
		float handle = this.updateOwnThrottleAndNotch(this.getDefinition(), leaderParams);
		boolean braking = this.drivingPlayer() == null || this.isOwnBraking();
		int powerUnits = 0;
		int poweredUnits = 0;
		for (ConsistMember m : members) {
			RailVehicleEntity car = m.entity();
			RailVehicleParams carParams = RailVehicleParamsLoader.get(car.getVehicleDefinitionId()).orElse(RailVehicleParams.DEFAULT);
			VehicleDefinition carDef = car.getDefinition();
			totalMass += carParams.mass();
			boolean powerUnit = car.isPowerUnit(carParams);
			if (car != this) {
				if (car.drivingPlayer() != null && car.isOwnBraking()) {
					braking = true;
				}
				// 各車のハンドル表示・燃料消費も先頭車のハンドルに合わせる(向きが逆の車両は符号を反転)
				car.setThrottleDirect(powerUnit ? handle * m.sign() : 0f);
			}
			// 動力車だけが引張力に寄与する。客車・貨車・制御車などは重さ(mass)だけを足す
			if (powerUnit) {
				powerUnits++;
				float powerFactor = 1f;
				if (handle != 0f) {
					powerFactor = car.tudursvehiclemod$isOutOfFuel() || car.tudursvehiclemod$isDestroyed()
							? 0f : car.consumePower(network, carParams, handle);
					if (powerFactor > 0f) {
						poweredUnits++;
					}
				}
				double carMaxSpeed = car.tudursvehiclemod$getEffectiveMaxSpeed();
				double accelMass = carDef.acceleration() * carParams.mass();
				weightedAccelMass += accelMass;
				weightedTargetNum += handle * powerFactor * carMaxSpeed * accelMass;
			}

			// 勾配は先頭車の進行方向で測る(walkの向きは先頭車の向きのまま)。車両の向き(sign)は掛けない
			TrackNetwork.Walk carWalk = network.walk(this.trackPos, m.offset());
			TrackPoint carPoint = network.pointAt(carWalk.pos());
			double carGrade = carPoint != null ? carPoint.grade() * carWalk.pos().facing() : 0.0;
			weightedGradeMass += carParams.gradeGravity() * carGrade * carParams.mass();
		}
		if (handle != 0f) {
			if (powerUnits == 0) {
				warnDriver("message.railwayvehicleaddon.power.no_power_unit");
			} else if (poweredUnits == 0) {
				warnDriver("message.railwayvehicleaddon.power.no_power_supply");
			}
		}

		double effAcceleration = weightedAccelMass > 1.0e-9 ? weightedAccelMass / totalMass : 0.0;
		double target = weightedAccelMass > 1.0e-9 ? weightedTargetNum / weightedAccelMass : 0.0;
		double meanGrade = totalMass > 1.0e-9 ? weightedGradeMass / totalMass : 0.0;
		double meanBrake = 0.0;
		double meanResistance = 0.0;
		for (ConsistMember m : members) {
			RailVehicleParams carParams = RailVehicleParamsLoader.get(m.entity().getVehicleDefinitionId()).orElse(RailVehicleParams.DEFAULT);
			meanBrake += carParams.brake() * carParams.mass();
			meanResistance += carParams.resistance() * carParams.mass();
		}
		if (totalMass > 1.0e-9) {
			meanBrake /= totalMass;
			meanResistance /= totalMass;
		}

		double v = this.speed;
		boolean powering = target > 0 ? target > v : target < 0 && target < v;
		if (powering) {
			v += (target - v) * effAcceleration;
		}
		v -= meanGrade;
		double decel = (powering ? 0.0 : meanResistance) + (braking ? meanBrake : 0.0);
		if (Math.abs(v) <= decel) {
			v = 0.0;
		} else {
			v -= Math.copySign(decel, v);
		}
		double leadMaxSpeed = this.tudursvehiclemod$getEffectiveMaxSpeed();
		v = MathHelper.clamp(v, -leadMaxSpeed, leadMaxSpeed);

		TrackNetwork.Walk leaderMoved = v != 0.0 ? network.walk(this.trackPos, v) : new TrackNetwork.Walk(
				this.trackPos.segmentId(), this.trackPos.s(), this.trackPos.facing(), false, 0.0);
		if (leaderMoved.blocked()) {
			v = 0.0;
			leaderMoved = network.walk(this.trackPos, 0.0);
		}
		TrackPos newLeaderPos = leaderMoved.pos();
		// 編成の先頭(進行方向の端の台車)が車止めに当たるなら止める(単独車と同じく台車の位置で判定)
		if (v != 0.0) {
			double extent = v > 0 ? -Double.MAX_VALUE : Double.MAX_VALUE;
			for (ConsistMember m : members) {
				RailVehicleEntity car = m.entity();
				double zc = car.centerZ();
				for (double local : new double[]{car.frontZ - zc, car.rearZ - zc}) {
					double e = m.offset() + m.sign() * local;
					extent = v > 0 ? Math.max(extent, e) : Math.min(extent, e);
				}
			}
			if (network.walk(newLeaderPos, extent).blocked()) {
				v = 0.0;
				newLeaderPos = this.trackPos;
			}
		}

		for (ConsistMember m : members) {
			RailVehicleEntity car = m.entity();
			RailVehicleParams carParams = RailVehicleParamsLoader.get(car.getVehicleDefinitionId()).orElse(RailVehicleParams.DEFAULT);
			// walkで得られる向きは先頭車の向き。逆向きに連結された車両は向きを反転して置く(反転しないと180度回ってしまう)
			TrackPos walked = network.walk(newLeaderPos, m.offset()).pos();
			TrackPos carPos = m.sign() > 0 ? walked : new TrackPos(walked.segmentId(), walked.s(), -walked.facing());
			double carSpeed = v * m.sign();
			car.trackPos = carPos;
			car.speed = carSpeed;
			car.cruiseSpeed = (float) carSpeed;
			car.dataTracker.set(TRACK_SEGMENT, carPos.segmentId());
			car.dataTracker.set(TRACK_S, (float) carPos.s());
			car.dataTracker.set(TRACK_FACING, (byte) carPos.facing());
			car.dataTracker.set(RAIL_SPEED, (float) carSpeed);
			car.updateBogieSpec(carParams);
			car.syncStatus(carParams);
			car.syncSmokeSpec(carParams);
			car.applyPlacement(network, carPos);
			car.consistHandledTick = tick;
		}
		// 編成の各車は今tickの自分の処理を飛ばすので、連結相手の探索は先頭車がまとめて行う
		// (後部で押し込んだ車両にも連結できるように。探索は空いている端だけ)
		if (v != 0.0) {
			for (ConsistMember m : members) {
				m.entity().tryAutoCouple(world, network);
			}
		}
	}

	private void tryAttach(TrackNetwork network) {
		TrackNetwork.Nearest nearest = network.nearestPoint(this.getX(), this.getY() - RAIL_TOP, this.getZ(), ATTACH_RADIUS);
		if (nearest == null) {
			return;
		}
		double yawRad = Math.toRadians(this.getYaw());
		double forwardX = -Math.sin(yawRad);
		double forwardZ = Math.cos(yawRad);
		int facing = forwardX * nearest.point().dirX() + forwardZ * nearest.point().dirZ() >= 0.0 ? 1 : -1;
		this.trackPos = new TrackPos(nearest.segmentId(), nearest.s(), facing);
		this.speed = 0.0;
		this.placedOnce = false;
	}

	/** 線路に載っていないときは単純に落下させるだけ(宙に浮いたままにしない)。 */
	private void derailedMovement(VehicleDefinition def) {
		this.speed = 0.0;
		this.cruiseSpeed = 0f;
		this.dataTracker.set(TRACK_SEGMENT, -1L);
		this.dataTracker.set(RAIL_SPEED, 0f);
		Vec3d velocity = this.getVelocity();
		this.setVelocity(0.0, this.isOnGround() ? 0.0 : Math.max(-2.0, velocity.y + def.gravity()), 0.0);
		this.move(MovementType.SELF, this.getVelocity());
	}

	private void clientMovement() {
		emitSmoke();
		TrackNetwork network = RailwayVehicleAddon.clientTrackNetwork.get();
		long segment = this.dataTracker.get(TRACK_SEGMENT);
		if (network == null || segment < 0L || network.segment(segment) == null) {
			this.clientPos = null;
			this.hasPlacedState = false;
			return;
		}
		float s = this.dataTracker.get(TRACK_S);
		byte facing = this.dataTracker.get(TRACK_FACING);
		double v = this.dataTracker.get(RAIL_SPEED);
		if (segment != this.seenSegment || s != this.seenS || facing != this.seenFacing) {
			this.seenSegment = segment;
			this.seenS = s;
			this.seenFacing = facing;
			this.ticksSinceServerUpdate = 0;
		} else {
			this.ticksSinceServerUpdate++;
		}
		TrackPos server = new TrackPos(segment, s, facing == 0 ? 1 : facing);
		// サーバー値が届かなかったtickの分は、速度で先読みする(最大10tick)
		TrackPos target = network.walk(server, v * Math.min(this.ticksSinceServerUpdate, 10)).pos();

		if (this.clientPos == null || network.segment(this.clientPos.segmentId()) == null) {
			this.clientPos = target;
		} else {
			TrackPos advanced = network.walk(this.clientPos, v).pos();
			TrackPoint pc = network.pointAt(advanced);
			TrackPoint pt = network.pointAt(target);
			if (pc == null || pt == null) {
				this.clientPos = target;
			} else {
				double dx = pt.x() - pc.x();
				double dy = pt.y() - pc.y();
				double dz = pt.z() - pc.z();
				double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (distance > CLIENT_SNAP_DISTANCE) {
					this.clientPos = target;
				} else {
					double forward = (dx * pc.dirX() + dz * pc.dirZ()) * advanced.facing();
					this.clientPos = network.walk(advanced, forward * CLIENT_CORRECTION).pos();
				}
			}
		}
		this.cruiseSpeed = (float) v;
		this.prevRolled = this.rolled;
		this.rolled += wheelSpinSpeed(v);
		if (Math.abs(this.rolled) > 1.0e6) {
			// 精度が落ちないよう、十分大きくなったら巻き戻す(どの半径でも角度が連続するように両方同じ量だけずらす)
			double shift = this.rolled;
			this.rolled -= shift;
			this.prevRolled -= shift;
		}
		restorePlacedState();
		applyPlacement(network, this.clientPos);
		this.hasPlacedState = true;
		this.placedX = this.getX();
		this.placedY = this.getY();
		this.placedZ = this.getZ();
		this.placedYaw = this.getYaw();
		this.placedPitch = this.getPitch();
	}

	/**
	 * サーバーから届く通常の位置パケットは、Entity側の処理(final)で位置と角度を直接書き換える。
	 * パケット処理はエンティティのtickより先に行われ、その値が描画補間の始点(lastRenderX等)に
	 * 入ってしまうため、前tickに自分で計算した値へ戻してから今tickの位置を計算する。
	 * これで描画補間は常に「自分の前回位置→今回位置」になり、パケットの到着タイミングに左右されない。
	 * ヨーも連続値(±180°で折り返さない)を保つため、パケットの値ではなく前回値を使う。
	 */
	private void restorePlacedState() {
		if (!this.hasPlacedState) {
			return;
		}
		this.setPosition(this.placedX, this.placedY, this.placedZ);
		this.lastX = this.placedX;
		this.lastY = this.placedY;
		this.lastZ = this.placedZ;
		this.lastRenderX = this.placedX;
		this.lastRenderY = this.placedY;
		this.lastRenderZ = this.placedZ;
		this.setYaw(this.placedYaw);
		this.setPitch(this.placedPitch);
		this.lastYaw = this.placedYaw;
		this.lastPitch = this.placedPitch;
	}

	/**
	 * 車両が区間segmentIdと他の線路にまたがっているか(転車台・遷車台を動かしてよいかの判定)。
	 * 前後の台車と中心のうち、一部だけがその区間に載っていればtrue。
	 */
	public boolean straddles(TrackNetwork network, long segmentId) {
		if (this.trackPos == null) {
			return false;
		}
		double zc = centerZ();
		int on = 0;
		for (double offset : new double[]{0.0, this.frontZ - zc, this.rearZ - zc}) {
			TrackNetwork.Walk walk = network.walk(this.trackPos, offset);
			if (walk.segmentId() == segmentId) {
				on++;
			}
		}
		return on > 0 && on < 3;
	}

	/** 車両(中心・前後の台車のいずれか)が区間segmentIdに載っているか。 */
	public boolean occupies(TrackNetwork network, long segmentId) {
		if (this.trackPos == null) {
			return false;
		}
		double zc = centerZ();
		for (double offset : new double[]{0.0, this.frontZ - zc, this.rearZ - zc}) {
			if (network.walk(this.trackPos, offset).segmentId() == segmentId) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ 配置

	private double centerZ() {
		return (this.frontZ + this.rearZ) * 0.5;
	}

	/** 台車の接地点。車止めの先は線路の端の向きに沿って外挿する。 */
	private static Vec3d contactPoint(TrackNetwork network, TrackNetwork.Walk walk, double requestedOffset) {
		TrackPoint p = network.pointAt(walk.pos());
		if (p == null) {
			return null;
		}
		Vec3d base = new Vec3d(p.x(), p.y(), p.z());
		if (walk.blocked() && walk.overshoot() > 0.0) {
			double sign = Math.signum(requestedOffset) * walk.facing();
			base = base.add(p.dirX() * sign * walk.overshoot(), p.grade() * sign * walk.overshoot(), p.dirZ() * sign * walk.overshoot());
		}
		return base;
	}

	private void applyPlacement(TrackNetwork network, TrackPos center) {
		double zc = centerZ();
		// サーバーでは、台車が開通していない分岐側から分岐器を通り抜けたら分岐器を進入側へ切り替える(割り出し)。
		// これをしないと、先頭の台車の通過後に後ろの台車・後続車が分岐器の開通方向へ逸れる
		java.util.function.BiConsumer<Long, Long> trailing = this.getEntityWorld() instanceof ServerWorld serverWorld
				? (node, segment) -> TrackManager.trailSwitch(serverWorld, node, segment) : null;
		TrackNetwork.Walk frontWalk = network.walk(center, this.frontZ - zc, trailing);
		TrackNetwork.Walk rearWalk = network.walk(center, this.rearZ - zc, trailing);
		Vec3d front = contactPoint(network, frontWalk, this.frontZ - zc);
		Vec3d rear = contactPoint(network, rearWalk, this.rearZ - zc);
		if (front == null || rear == null) {
			return;
		}
		double span = this.frontZ - this.rearZ;
		Vec3d axis = front.subtract(rear);
		Vec3d origin = rear.add(axis.multiply(span > 1.0e-6 ? -this.rearZ / span : 0.5));
		double horizontal = Math.hypot(axis.x, axis.z);
		float targetYaw = (float) Math.toDegrees(Math.atan2(-axis.x, axis.z));
		float targetPitch = (float) -Math.toDegrees(Math.atan2(axis.y, Math.max(1.0e-6, horizontal)));

		TrackPoint frontPoint = network.pointAt(frontWalk.pos());
		TrackPoint rearPoint = network.pointAt(rearWalk.pos());
		double cant = 0.0;
		if (frontPoint != null && rearPoint != null) {
			cant = (frontPoint.cantRad() * frontWalk.facing() + rearPoint.cantRad() * rearWalk.facing()) * 0.5;
		}

		Vec3d previous = new Vec3d(this.getX(), this.getY(), this.getZ());
		this.setPosition(origin.x, origin.y + RAIL_TOP, origin.z);
		if (this.placedOnce) {
			this.setVelocity(this.getX() - previous.x, this.getY() - previous.y, this.getZ() - previous.z);
			// 角度はラップさせず連続値にする(getYaw(tickDelta)は単純な線形補間のため、±180°をまたぐと一回転して見える)
			this.setYaw(this.getYaw() + MathHelper.wrapDegrees(targetYaw - this.getYaw()));
		} else {
			this.setVelocity(Vec3d.ZERO);
			this.setYaw(targetYaw);
			this.lastYaw = targetYaw;
			this.placedOnce = true;
		}
		this.setPitch(targetPitch);
		this.prevRoll = this.roll;
		this.roll = (float) Math.toDegrees(cant);

		if (this.getEntityWorld().isClient()) {
			updatePartPoses(network, center, targetYaw, targetPitch, this.roll);
		}
	}

	/**
	 * 線路に追随する部品(carry=falseを含む全台車・関節部)の姿勢を、車体から見たモデル座標の行列として求める(クライアントのみ)。
	 * 台車は「回転中心が実際の線路の真上に来る」ように横・上下にも動かす(曲線で車体の弦から外れる先台車・炭水車の台車が
	 * 線路に載って見えるように)。関節部は前端の関節を車体に固定したまま、後端が線路に載るように振る。
	 */
	private void updatePartPoses(TrackNetwork network, TrackPos center, float bodyYaw, float bodyPitch, float bodyRoll) {
		Map<String, Matrix4f> poses = new HashMap<>();
		float scale = this.getScale();
		if (scale <= 0f) {
			scale = 1f;
		}
		Quaternionf bodyRot = orientation(bodyYaw, bodyPitch, bodyRoll);
		Quaternionf bodyInv = new Quaternionf(bodyRot).conjugate();
		Vec3d entity = new Vec3d(this.getX(), this.getY(), this.getZ());
		double zc = centerZ();
		for (BogieSpec bogie : this.bogies) {
			if (bogie.part.isEmpty()) {
				continue;
			}
			double offset = bogie.contactZ - zc;
			TrackNetwork.Walk walk = network.walk(center, offset);
			TrackPoint p = network.pointAt(walk.pos());
			Vec3d contact = contactPoint(network, walk, offset);
			if (p == null || contact == null) {
				continue;
			}
			double dirX = p.dirX() * walk.facing();
			double dirZ = p.dirZ() * walk.facing();
			float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
			float pitch = (float) -Math.toDegrees(Math.atan(p.grade() * walk.facing()));
			float roll = (float) Math.toDegrees(p.cantRad() * walk.facing());
			Quaternionf trackRot = orientation(yaw, pitch, roll);
			// 回転中心の行き先(ワールド)→モデル座標
			org.joml.Vector3f local = new org.joml.Vector3f(bogie.pivotX * scale, bogie.pivotY * scale, 0f);
			trackRot.transform(local);
			org.joml.Vector3f target = new org.joml.Vector3f(
					(float) (contact.x + local.x - entity.x),
					(float) (contact.y + RAIL_TOP + local.y - entity.y),
					(float) (contact.z + local.z - entity.z));
			bodyInv.transform(target);
			target.div(scale);
			Quaternionf rel = new Quaternionf(bodyInv).mul(trackRot);
			poses.put(bogie.part, new Matrix4f()
					.translate(target)
					.rotate(rel)
					.translate(-bogie.pivotX, -bogie.pivotY, -bogie.pivotZ));
		}
		for (ArticulatedSpec art : this.articulatedParts) {
			org.joml.Vector3f hingeLocal = new org.joml.Vector3f(0f, art.hingeY * scale, art.hingeZ * scale);
			bodyRot.transform(hingeLocal);
			Vec3d hinge = entity.add(hingeLocal.x, hingeLocal.y, hingeLocal.z);
			double length = (art.hingeZ - art.rearZ) * scale;
			if (length <= 1.0e-3) {
				continue;
			}
			// 弦の長さが関節〜後端の長さに一致するよう、線路上の後端の位置を少しずつ合わせる
			double distance = art.rearZ * scale - zc;
			TrackNetwork.Walk walk = null;
			Vec3d rear = null;
			for (int iter = 0; iter < 4; iter++) {
				walk = network.walk(center, distance);
				Vec3d c = contactPoint(network, walk, distance);
				if (c == null) {
					break;
				}
				rear = c.add(0.0, RAIL_TOP + art.hingeY * scale, 0.0);
				double chord = hinge.distanceTo(rear);
				distance += chord - length;
			}
			if (rear == null || walk == null) {
				continue;
			}
			Vec3d axis = hinge.subtract(rear);
			double horizontal = Math.hypot(axis.x, axis.z);
			float yaw = (float) Math.toDegrees(Math.atan2(-axis.x, axis.z));
			float pitch = (float) -Math.toDegrees(Math.atan2(axis.y, Math.max(1.0e-6, horizontal)));
			TrackPoint p = network.pointAt(walk.pos());
			float roll = p == null ? 0f : (float) Math.toDegrees(p.cantRad() * walk.facing());
			Quaternionf rel = new Quaternionf(bodyInv).mul(orientation(yaw, pitch, roll));
			poses.put(art.part, new Matrix4f()
					.translate(0f, art.hingeY, art.hingeZ)
					.rotate(rel)
					.translate(0f, -art.hingeY, -art.hingeZ));
		}
		this.prevPartPoses = this.partPoses.isEmpty() ? poses : this.partPoses;
		this.partPoses = poses;
	}

	/**
	 * 車輪の周速。"throttle"では、スロットルが求める速さ(スロットル×最高速度)が実際の速度を上回っている間
	 * (発進・加速中など)はそちらで回し、空転して見えるようにする。惰行中・減速中は走行どおりに回る。
	 * 急に変わらないよう少しずつ追従させる。
	 */
	private double wheelSpinSpeed(double v) {
		double target = v;
		if (this.spinByThrottle) {
			double demand = this.getThrottle() * this.getDefinition().maxSpeed();
			boolean sameDirection = demand * v >= 0.0 || Math.abs(v) < 0.005;
			if (sameDirection && Math.abs(demand) > Math.abs(v)) {
				target = demand;
			}
		}
		this.spinSpeed += (target - this.spinSpeed) * 0.15;
		if (Math.abs(target - this.spinSpeed) < 1.0e-4) {
			this.spinSpeed = target;
		}
		return this.spinSpeed;
	}

	/** 車体の描画と同じ規約(rotationY(-yaw)→rotateX(pitch)→rotateZ(roll))の回転。 */
	private static Quaternionf orientation(float yaw, float pitch, float roll) {
		return new Quaternionf()
				.rotationY((float) Math.toRadians(-yaw))
				.rotateX((float) Math.toRadians(pitch))
				.rotateZ((float) Math.toRadians(roll));
	}

	// ------------------------------------------------------------------ 台車

	private record BogieSpec(String part, float pivotX, float pivotY, float pivotZ, double contactZ, boolean carry) {
	}

	private record ArticulatedSpec(String part, float hingeY, float hingeZ, float rearZ) {
	}

	private record WheelSpec(String part, float pivotY, float pivotZ, float radius) {
	}

	private record RodSpec(String part, String type, float axleY, float axleZ, float crankRadius, float wheelRadius,
						   float phaseRad, float length, float crossheadY, float direction) {
	}

	/** サーバー側: パラメータから台車設定文字列を作って同期する。 */
	/** 蒸気機関車のHUD向けに、火室の残り時間とインベントリの石炭個数を同期する(毎tick、サーバーのみ)。 */
	private void syncStatus(RailVehicleParams params) {
		if (!RailVehicleParams.STEAM.equals(params.powerSource())) {
			return;
		}
		this.dataTracker.set(FIRE_SECONDS_SYNC, (float) (this.fireTicks / 20.0));
		int count = 0;
		for (int i = 0; i < this.size(); i++) {
			ItemStack stack = this.getStack(i);
			if (stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL) || stack.isOf(Items.COAL_BLOCK)) {
				count += stack.getCount();
			}
		}
		this.dataTracker.set(COAL_COUNT_SYNC, count);
	}

	// ------------------------------------------------------------------ 排煙

	private String parsedSmokeSpec = "";
	private float[][] smokeEmitters = new float[0][];
	private String[] smokeTypes = new String[0];

	/** サーバー: rail.smoke を文字列にして同期する(変わったときだけ書き込む)。 */
	private void syncSmokeSpec(RailVehicleParams params) {
		StringBuilder sb = new StringBuilder();
		for (RailVehicleParams.SmokeEmitter e : params.smoke()) {
			if (!sb.isEmpty()) {
				sb.append(';');
			}
			sb.append(e.x()).append('|').append(e.y()).append('|').append(e.z()).append('|').append(e.type())
					.append('|').append(e.rate()).append('|').append(e.idleRate());
		}
		String spec = sb.toString();
		if (!spec.equals(this.dataTracker.get(SMOKE_SPEC))) {
			this.dataTracker.set(SMOKE_SPEC, spec);
		}
	}

	/**
	 * クライアント: 運転者がいるか走行中のとき、各発生源から煙を出す。粒子数は
	 * 停車・惰行中の idle_rate から、出力(スロットルの絶対値)に応じて rate まで増える。
	 * 位置は車体の向き・傾き(getBodyOrientation)に合わせて回す。
	 */
	private void emitSmoke() {
		String spec = this.dataTracker.get(SMOKE_SPEC);
		if (!spec.equals(this.parsedSmokeSpec)) {
			this.parsedSmokeSpec = spec;
			List<float[]> list = new ArrayList<>();
			List<String> types = new ArrayList<>();
			if (!spec.isEmpty()) {
				for (String entry : spec.split(";")) {
					String[] f = entry.split("\\|");
					if (f.length < 6) {
						continue;
					}
					try {
						list.add(new float[]{Float.parseFloat(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2]),
								Float.parseFloat(f[4]), Float.parseFloat(f[5])});
						types.add(f[3]);
					} catch (NumberFormatException ignored) {
						// 壊れた項目は無視する
					}
				}
			}
			this.smokeEmitters = list.toArray(new float[0][]);
			this.smokeTypes = types.toArray(new String[0]);
		}
		if (this.smokeEmitters.length == 0 || this.tudursvehiclemod$isDestroyed()) {
			return;
		}
		boolean active = this.getControllingPassenger() != null || Math.abs(this.getRailSpeed()) > 0.01;
		if (!active) {
			return;
		}
		float intensity = MathHelper.clamp(Math.abs(this.getThrottle()), 0f, 1f);
		org.joml.Quaternionf orientation = this.tudursvehiclemod$getBodyOrientation();
		float scale = this.getScale();
		net.minecraft.util.math.random.Random random = this.getRandom();
		for (int i = 0; i < this.smokeEmitters.length; i++) {
			float[] e = this.smokeEmitters[i];
			float perTick = e[4] + (e[3] - e[4]) * intensity;
			int count = (int) perTick + (random.nextFloat() < perTick - (int) perTick ? 1 : 0);
			if (count <= 0) {
				continue;
			}
			org.joml.Vector3f local = new org.joml.Vector3f(e[0] * scale, e[1] * scale, e[2] * scale);
			orientation.transform(local);
			net.minecraft.particle.ParticleEffect effect = switch (this.smokeTypes[i]) {
				case "campfire" -> net.minecraft.particle.ParticleTypes.CAMPFIRE_COSY_SMOKE;
				case "steam" -> net.minecraft.particle.ParticleTypes.CLOUD;
				default -> net.minecraft.particle.ParticleTypes.LARGE_SMOKE;
			};
			for (int k = 0; k < count; k++) {
				double x = this.getX() + local.x + (random.nextDouble() - 0.5) * 0.25;
				double y = this.getY() + local.y;
				double z = this.getZ() + local.z + (random.nextDouble() - 0.5) * 0.25;
				this.getEntityWorld().addParticleClient(effect, true, false, x, y, z,
						(random.nextDouble() - 0.5) * 0.02, 0.06 + random.nextDouble() * 0.06, (random.nextDouble() - 0.5) * 0.02);
			}
		}
	}

	private void updateBogieSpec(RailVehicleParams params) {
		StringBuilder sb = new StringBuilder();
		float scale = this.getScale();
		for (RailVehicleParams.Bogie bogie : params.bogies()) {
			if (!sb.isEmpty()) {
				sb.append(';');
			}
			sb.append(bogie.part()).append('|').append(bogie.pivotX()).append('|').append(bogie.pivotY())
					.append('|').append(bogie.pivotZ()).append('|').append(bogie.pivotZ() * scale)
					.append('|').append(bogie.carry() ? 1 : 0);
		}
		String spec = sb.toString();
		if (!spec.equals(this.dataTracker.get(BOGIE_SPEC))) {
			this.dataTracker.set(BOGIE_SPEC, spec);
		}
		parseBogieSpec(spec);
		syncAnimSpec(params);
		syncSoundSpec(params);
	}

	// ------------------------------------------------------------------ 音

	/**
	 * 動作音の設定(クライアントの音の管理が使う)。名前が空の音は鳴らさない。
	 *
	 * @param motorEngine 主電動機の音がエンジン式(スロットルで高さが変わり、停車中もアイドリングする)か
	 */
	public record SoundSpec(String running, String joint, float jointSpacing, String brake, String motor, boolean motorEngine,
							float motorPitchMin, float motorPitchMax, String chuff, int chuffsPerRev, String horn,
							String couple, String air, float volume) {
	}

	/** サーバー側: rail.soundsと動力から音の設定を決めて同期する。省略した項目は動力に応じた既定の音になる。 */
	private void syncSoundSpec(RailVehicleParams params) {
		RailVehicleParams.Sounds snd = params.sounds().orElse(null);
		boolean power = isPowerUnit(params);
		boolean cab = hasDriverSeat();
		String source = params.powerSource();
		boolean steam = RailVehicleParams.STEAM.equals(source);
		boolean electric = RailVehicleParams.ELECTRIC.equals(source);
		String defMotor = !power || steam ? "" : electric ? "rva_motor_vvvf" : "rva_engine_diesel";
		String defHorn = !cab ? "" : steam ? "rva_whistle_steam" : electric ? "rva_horn_electric" : "rva_horn_diesel";
		String motorType = snd != null && snd.motorType().isPresent() ? snd.motorType().get() : (electric ? "electric" : "engine");
		boolean engine = "engine".equals(motorType);
		float pmin = snd != null && !Float.isNaN(snd.motorPitchMin()) ? snd.motorPitchMin() : (engine ? 0.8f : 0.5f);
		float pmax = snd != null && !Float.isNaN(snd.motorPitchMax()) ? snd.motorPitchMax() : (engine ? 1.6f : 2.0f);
		String[] f = {
				pick(snd == null ? null : snd.running(), "rva_rolling"),
				pick(snd == null ? null : snd.joint(), "rva_joint"),
				Float.toString(snd == null ? 25.0f : snd.jointSpacing()),
				pick(snd == null ? null : snd.brake(), "rva_brake"),
				pick(snd == null ? null : snd.motor(), defMotor),
				engine ? "1" : "0",
				Float.toString(pmin),
				Float.toString(pmax),
				pick(snd == null ? null : snd.chuff(), steam && power ? "rva_chuff" : ""),
				Integer.toString(snd == null ? 1 : snd.chuffsPerRev()),
				pick(snd == null ? null : snd.horn(), defHorn),
				pick(snd == null ? null : snd.couple(), "rva_couple"),
				pick(snd == null ? null : snd.air(), power || cab ? "rva_air" : ""),
				Float.toString(snd == null ? 1.0f : snd.volume())};
		String spec = String.join("|", f);
		if (!spec.equals(this.dataTracker.get(SOUND_SPEC))) {
			this.dataTracker.set(SOUND_SPEC, spec);
		}
	}

	private static String pick(Optional<String> value, String fallback) {
		return value == null || value.isEmpty() ? fallback : value.get().replace("|", "").replace(";", "");
	}

	/** クライアント側: 同期された音の設定。まだ届いていなければnull。 */
	public SoundSpec getSoundSpec() {
		String spec = this.dataTracker.get(SOUND_SPEC);
		if (!spec.equals(this.parsedSoundSpec)) {
			this.parsedSoundSpec = spec;
			this.soundSpec = null;
			String[] f = spec.split("\\|", -1);
			if (f.length == 14) {
				try {
					this.soundSpec = new SoundSpec(f[0], f[1], Float.parseFloat(f[2]), f[3], f[4], "1".equals(f[5]),
							Float.parseFloat(f[6]), Float.parseFloat(f[7]), f[8], Integer.parseInt(f[9]), f[10], f[11], f[12],
							Float.parseFloat(f[13]));
				} catch (NumberFormatException ignored) {
					// 壊れた設定は無視する
				}
			}
		}
		return this.soundSpec;
	}

	/** 車輪が回った量(周の長さ。ブロック、前向き正)。空転も含む。蒸気機関車のドラフト音に使う。 */
	public double getWheelRolled() {
		return this.rolled;
	}

	/** ドラフト音の基準にする動輪の半径(ブロック。scale込み)。rail.wheelsが無ければ0。 */
	public double getDriveWheelRadius() {
		if (this.wheelParts.isEmpty()) {
			return 0.0;
		}
		return this.wheelParts.get(0).radius * (this.getScale() > 0f ? this.getScale() : 1f);
	}

	/**
	 * クライアント側: 車両中心から線路に沿ってoffset(前向き正)だけ進んだ線路上の位置。
	 * レールの継ぎ目の音を線路の位置に合わせて鳴らすのに使う。線路に載っていなければnull。
	 */
	public TrackPos clientTrackPosAt(double offset) {
		TrackNetwork network = RailwayVehicleAddon.clientTrackNetwork.get();
		if (network == null || this.clientPos == null || network.segment(this.clientPos.segmentId()) == null) {
			return null;
		}
		return network.walk(this.clientPos, offset).pos();
	}

	/** 台車(車体を載せるもの)の位置(ブロック。車両中心から前向き正)。レールの継ぎ目の音を台車ごとに鳴らすのに使う。 */
	public double[] getCarryBogieOffsets() {
		double zc = centerZ();
		return new double[]{this.frontZ - zc, this.rearZ - zc};
	}

	/** サーバー側: 一回きりの音(警笛・連結)を、この車両が見えているプレイヤーへ送る。 */
	public void playRailSound(String kind) {
		if (!(this.getEntityWorld() instanceof ServerWorld)) {
			return;
		}
		com.example.railwayvehicleaddon.network.RailSoundPayload payload =
				new com.example.railwayvehicleaddon.network.RailSoundPayload(this.getId(), kind);
		java.util.Set<net.minecraft.server.network.ServerPlayerEntity> targets = new java.util.HashSet<>(
				net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(this));
		for (Entity passenger : this.getPassengerList()) {
			if (passenger instanceof net.minecraft.server.network.ServerPlayerEntity player) {
				targets.add(player);
			}
		}
		for (net.minecraft.server.network.ServerPlayerEntity player : targets) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
		}
	}

	/** 警笛キー(運転者のみ)。連打しても1秒に1回まで。 */
	public void hornByKey(net.minecraft.server.network.ServerPlayerEntity player) {
		if (drivingPlayer() != player || this.hornCooldown > 0) {
			return;
		}
		this.hornCooldown = 20;
		playRailSound("horn");
	}

	/** サーバー側: 関節部・車輪・ロッドの設定を文字列にして同期する。 */
	private void syncAnimSpec(RailVehicleParams params) {
		StringBuilder sb = new StringBuilder();
		for (RailVehicleParams.Articulated a : params.articulated()) {
			appendEntry(sb, "A", a.part(), a.hingeY(), a.hingeZ(), a.rearZ());
		}
		for (RailVehicleParams.Wheel w : params.wheels()) {
			appendEntry(sb, "W", w.part(), w.pivotY(), w.pivotZ(), w.radius());
		}
		for (RailVehicleParams.Rod r : params.rods()) {
			appendEntry(sb, "R", r.part() + "|" + r.type(), r.axleY(), r.axleZ(), r.crankRadius(), r.wheelRadius(),
					r.phaseDeg(), r.length(), r.crossheadY(), r.direction());
		}
		if (!"throttle".equals(params.wheelSpin())) {
			appendEntry(sb, "S", params.wheelSpin());
		}
		String spec = sb.toString();
		if (!spec.equals(this.dataTracker.get(ANIM_SPEC))) {
			this.dataTracker.set(ANIM_SPEC, spec);
		}
	}

	private static void appendEntry(StringBuilder sb, String kind, String text, float... values) {
		if (!sb.isEmpty()) {
			sb.append(';');
		}
		sb.append(kind).append('|').append(text);
		for (float v : values) {
			sb.append('|').append(v);
		}
	}

	private void parseAnimSpec(String spec) {
		if (spec.equals(this.parsedAnimSpec)) {
			return;
		}
		this.parsedAnimSpec = spec;
		List<ArticulatedSpec> arts = new ArrayList<>();
		List<WheelSpec> wheels = new ArrayList<>();
		List<RodSpec> rods = new ArrayList<>();
		boolean byThrottle = true;
		if (!spec.isEmpty()) {
			for (String entry : spec.split(";")) {
				String[] f = entry.split("\\|", -1);
				try {
					switch (f[0]) {
						case "A" -> {
							if (f.length == 5) {
								arts.add(new ArticulatedSpec(f[1], Float.parseFloat(f[2]), Float.parseFloat(f[3]), Float.parseFloat(f[4])));
							}
						}
						case "W" -> {
							if (f.length == 5) {
								wheels.add(new WheelSpec(f[1], Float.parseFloat(f[2]), Float.parseFloat(f[3]), Float.parseFloat(f[4])));
							}
						}
						case "R" -> {
							if (f.length == 11) {
								rods.add(new RodSpec(f[1], f[2], Float.parseFloat(f[3]), Float.parseFloat(f[4]), Float.parseFloat(f[5]),
										Float.parseFloat(f[6]), (float) Math.toRadians(Float.parseFloat(f[7])), Float.parseFloat(f[8]),
										Float.parseFloat(f[9]), Float.parseFloat(f[10])));
							}
						}
						case "S" -> byThrottle = f.length < 2 || !"distance".equals(f[1]);
						default -> {
						}
					}
				} catch (NumberFormatException ignored) {
					// 壊れた項目は無視する
				}
			}
		}
		this.articulatedParts = List.copyOf(arts);
		this.wheelParts = List.copyOf(wheels);
		this.rodParts = List.copyOf(rods);
		this.spinByThrottle = byThrottle;
	}

	private void parseBogieSpec(String spec) {
		if (spec.equals(this.parsedSpec)) {
			return;
		}
		this.parsedSpec = spec;
		List<BogieSpec> list = new ArrayList<>();
		if (!spec.isEmpty()) {
			for (String entry : spec.split(";")) {
				String[] f = entry.split("\\|", -1);
				if (f.length != 5 && f.length != 6) {
					continue;
				}
				try {
					list.add(new BogieSpec(f[0], Float.parseFloat(f[1]), Float.parseFloat(f[2]),
							Float.parseFloat(f[3]), Double.parseDouble(f[4]), f.length < 6 || !"0".equals(f[5])));
				} catch (NumberFormatException ignored) {
					// 壊れた項目は無視する
				}
			}
		}
		this.bogies = List.copyOf(list);
		double max = Double.NEGATIVE_INFINITY;
		double min = Double.POSITIVE_INFINITY;
		boolean anyCarry = false;
		for (BogieSpec b : this.bogies) {
			if (!b.carry) {
				continue;
			}
			anyCarry = true;
			max = Math.max(max, b.contactZ);
			min = Math.min(min, b.contactZ);
		}
		if (!anyCarry) {
			this.frontZ = 1.0;
			this.rearZ = -1.0;
		} else if (max - min < 0.5) {
			// 台車が1つ(または同位置)なら、その前後1ブロックで線路に載せる
			this.frontZ = max + 1.0;
			this.rearZ = min - 1.0;
		} else {
			this.frontZ = max;
			this.rearZ = min;
		}
	}

	@Override
	public void onTrackedDataSet(TrackedData<?> data) {
		super.onTrackedDataSet(data);
		if (BOGIE_SPEC.equals(data) && this.getEntityWorld().isClient()) {
			parseBogieSpec(this.dataTracker.get(BOGIE_SPEC));
		}
		if (ANIM_SPEC.equals(data) && this.getEntityWorld().isClient()) {
			parseAnimSpec(this.dataTracker.get(ANIM_SPEC));
		}
	}

	@Override
	public Map<String, Matrix4f> tudursvehiclemod$getCustomPartTransforms(float tickDelta) {
		if (this.partPoses.isEmpty() && this.wheelParts.isEmpty() && this.rodParts.isEmpty()) {
			return Map.of();
		}
		Map<String, Matrix4f> transforms = new HashMap<>();
		// 線路に追随する部品(台車・関節部): 前tickと今tickの行列を補間する(1tickでの変化は小さいので成分ごとの補間で足りる)
		for (Map.Entry<String, Matrix4f> e : this.partPoses.entrySet()) {
			Matrix4f prev = this.prevPartPoses.get(e.getKey());
			transforms.put(e.getKey(), prev == null ? new Matrix4f(e.getValue()) : new Matrix4f(prev).lerp(e.getValue(), tickDelta));
		}
		float scale = this.getScale() > 0f ? this.getScale() : 1f;
		double distance = MathHelper.lerp(tickDelta, this.prevRolled, this.rolled) / scale;
		for (WheelSpec w : this.wheelParts) {
			if (w.radius <= 1.0e-4f) {
				continue;
			}
			float angle = (float) ((distance / w.radius) % (Math.PI * 2.0));
			transforms.put(w.part, new Matrix4f()
					.translate(0f, w.pivotY, w.pivotZ)
					.rotateX(angle)
					.translate(0f, -w.pivotY, -w.pivotZ));
		}
		for (RodSpec r : this.rodParts) {
			if (r.wheelRadius <= 1.0e-4f) {
				continue;
			}
			float theta = (float) ((distance / r.wheelRadius) % (Math.PI * 2.0));
			Matrix4f m = rodTransform(r, theta);
			if (m != null) {
				transforms.put(r.part, m);
			}
		}
		return transforms;
	}

	/**
	 * ロッドの変換。クランクピンは車軸から見て(y, z) = (rc·cos(θ+φ), rc·sin(θ+φ))にあり、θが車輪の回転角(前進で増える)。
	 * モデルはθ=0の位置で作られている前提。
	 */
	private static Matrix4f rodTransform(RodSpec r, float theta) {
		float pin0Y = r.axleY + r.crankRadius * (float) Math.cos(r.phaseRad);
		float pin0Z = r.axleZ + r.crankRadius * (float) Math.sin(r.phaseRad);
		float pinY = r.axleY + r.crankRadius * (float) Math.cos(theta + r.phaseRad);
		float pinZ = r.axleZ + r.crankRadius * (float) Math.sin(theta + r.phaseRad);
		switch (r.type) {
			case "coupling" -> {
				return new Matrix4f().translation(0f, pinY - pin0Y, pinZ - pin0Z);
			}
			case "main", "crosshead" -> {
				float dir = r.direction >= 0f ? 1f : -1f;
				float dy0 = pin0Y - r.crossheadY;
				float dy = pinY - r.crossheadY;
				float sq0 = r.length * r.length - dy0 * dy0;
				float sq = r.length * r.length - dy * dy;
				if (sq0 <= 0f || sq <= 0f) {
					return null;
				}
				float head0Z = pin0Z + dir * (float) Math.sqrt(sq0);
				float headZ = pinZ + dir * (float) Math.sqrt(sq);
				if (r.type.equals("crosshead")) {
					return new Matrix4f().translation(0f, 0f, headZ - head0Z);
				}
				// 主連棒: クランクピン側の端を中心に、ピン→クロスヘッドの向きの変化だけ回す
				double a0 = Math.atan2(head0Z - pin0Z, r.crossheadY - pin0Y);
				double a1 = Math.atan2(headZ - pinZ, r.crossheadY - pinY);
				return new Matrix4f()
						.translate(0f, pinY, pinZ)
						.rotateX((float) (a1 - a0))
						.translate(0f, -pin0Y, -pin0Z);
			}
			default -> {
				return null;
			}
		}
	}

	// ------------------------------------------------------------------ 保存

	@Override
	protected void writeCustomData(WriteView view) {
		super.writeCustomData(view);
		if (this.trackPos != null) {
			view.putLong("RailSegment", this.trackPos.segmentId());
			view.putDouble("RailS", this.trackPos.s());
			view.putInt("RailFacing", this.trackPos.facing());
		}
		view.putDouble("RailSpeed", this.speed);
		view.putInt("RailNotch", this.notch);
		view.putDouble("RailFire", this.fireTicks);
		this.couplingA.ifPresent(id -> view.putString("CouplingA", id.toString()));
		this.couplingB.ifPresent(id -> view.putString("CouplingB", id.toString()));
		view.putBoolean("CouplingAReversed", this.couplingAReversed);
		view.putBoolean("CouplingBReversed", this.couplingBReversed);
		this.uncoupledA.ifPresent(id -> view.putString("UncoupledA", id.toString()));
		this.uncoupledB.ifPresent(id -> view.putString("UncoupledB", id.toString()));
	}

	@Override
	protected void readCustomData(ReadView view) {
		super.readCustomData(view);
		long segment = view.getLong("RailSegment", -1L);
		if (segment >= 0L) {
			int facing = view.getInt("RailFacing", 1);
			this.trackPos = new TrackPos(segment, view.getDouble("RailS", 0.0), facing >= 0 ? 1 : -1);
		}
		this.speed = view.getDouble("RailSpeed", 0.0);
		this.notch = view.getInt("RailNotch", 0);
		this.fireTicks = view.getDouble("RailFire", 0.0);
		this.couplingA = parseUuid(view.getString("CouplingA", ""));
		this.couplingB = parseUuid(view.getString("CouplingB", ""));
		this.couplingAReversed = view.getBoolean("CouplingAReversed", false);
		this.couplingBReversed = view.getBoolean("CouplingBReversed", false);
		this.uncoupledA = parseUuid(view.getString("UncoupledA", ""));
		this.uncoupledB = parseUuid(view.getString("UncoupledB", ""));
	}

	private static Optional<UUID> parseUuid(String value) {
		if (value.isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(UUID.fromString(value));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
