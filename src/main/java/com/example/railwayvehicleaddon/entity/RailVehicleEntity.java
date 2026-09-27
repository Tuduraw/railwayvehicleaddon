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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
public class RailVehicleEntity extends AbstractVehicleEntity {

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
	private static final TrackedData<String> BOGIE_SPEC =
			DataTracker.registerData(RailVehicleEntity.class, TrackedDataHandlerRegistry.STRING);
	/** ノッチを押し続けたときに次の段へ進むまでのtick数(最初の1段目の後と、それ以降) */
	private static final int NOTCH_REPEAT_FIRST = 10;
	private static final int NOTCH_REPEAT = 5;
	/** 石炭・木炭1個で燃やせる時間(出力100%でのtick数) */
	private static final int COAL_TICKS = 1600;

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
	private float[] bogieYaw = new float[0];
	private float[] prevBogieYaw = new float[0];
	private float[] bogiePitch = new float[0];
	private float[] prevBogiePitch = new float[0];
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

	public double getRailSpeed() {
		return this.dataTracker.get(RAIL_SPEED);
	}

	public boolean isOnTrack() {
		return this.dataTracker.get(TRACK_SEGMENT) >= 0L;
	}

	// ------------------------------------------------------------------ 移動

	@Override
	protected void updateVehicleMovement(VehicleDefinition def) {
		if (this.getEntityWorld().isClient()) {
			clientMovement();
			return;
		}
		ServerWorld world = (ServerWorld) this.getEntityWorld();
		TrackNetwork network = TrackManager.network(world);
		RailVehicleParams params = RailVehicleParamsLoader.get(this.getVehicleDefinitionId()).orElse(RailVehicleParams.DEFAULT);
		updateBogieSpec(params);

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

		PlayerEntity driver = this.getControllingPassenger() instanceof PlayerEntity player ? player : null;
		float throttle;
		boolean braking;
		float handleStep = 0.03f * def.throttleUpDown().orElse(1.0f);
		if (driver != null) {
			throttle = params.usesNotches() ? updateNotch(params, handleStep)
					: updateThrottle(driver, handleStep, -1.0f, 1.0f);
			braking = this.getSyncedBrakeInput() || this.tudursvehiclemod$isDestroyed();
		} else {
			// 無人の車両は停止保持(フェーズ2で編成・自動運転を扱うまでの暫定)
			this.setThrottleDirect(0f);
			this.notch = 0;
			throttle = 0f;
			braking = true;
		}
		// 動力が得られなければ(石炭が無い・架線が無い)力行できない。ハンドル(表示)はそのまま動かせる
		if (throttle != 0f && !consumePower(network, params, throttle)) {
			if (driver instanceof net.minecraft.server.network.ServerPlayerEntity player && this.powerWarningCooldown-- <= 0) {
				this.powerWarningCooldown = 60;
				player.sendMessage(net.minecraft.text.Text.translatable(RailVehicleParams.STEAM.equals(params.powerSource())
						? "message.railwayvehicleaddon.power.no_coal" : "message.railwayvehicleaddon.power.no_catenary"), true);
			}
			throttle = 0f;
		}

		TrackPoint center = network.pointAt(this.trackPos);
		double grade = center != null ? center.grade() * this.trackPos.facing() : 0.0;
		double maxSpeed = this.tudursvehiclemod$getEffectiveMaxSpeed();
		// 前提MODの車と同じく、スロットルに応じた目標速度へacceleration(追従度)で近づく。
		// ノッチを下げた・切にしたときは減速させず惰行する(走行抵抗で少しずつ落ちる)
		double acceleration = def.acceleration();
		double target = throttle * maxSpeed;
		double v = this.speed;
		boolean powering = throttle > 0f ? target > v : throttle < 0f && target < v;
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
		if (driver == null && Math.abs(v) < 1.0e-3) {
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

	/**
	 * ノッチ式の操作。前進/後進キー(前提MODのスロットル入力)を押すたびにノッチが1段ずつ動き、
	 * 押し続けると一定間隔で進む。スロットル(前提MODのHUDにハンドル位置として表示される値)は、
	 * ノッチの目標値へ前提MODと同じ速さ(0.03 × throttle_up_down / tick)で動く。
	 * 燃料切れ・撃破時はノッチを切(0)に戻す。
	 */
	private float updateNotch(RailVehicleParams params, float step) {
		float input = this.getSyncedThrottleInput();
		int direction = input > 0 ? 1 : input < 0 ? -1 : 0;
		if (this.tudursvehiclemod$isOutOfFuel() || this.tudursvehiclemod$isDestroyed()) {
			this.notch = 0;
		} else if (direction != 0) {
			boolean pressed = Math.signum(this.lastNotchInput) != direction;
			if (pressed || --this.notchRepeat <= 0) {
				this.notch = MathHelper.clamp(this.notch + direction, -params.reverseNotches(), params.powerNotches());
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
	private boolean consumePower(TrackNetwork network, RailVehicleParams params, float throttle) {
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
				return false;
			}
			this.fireTicks -= Math.abs(throttle);
			return true;
		}
		if (RailVehicleParams.ELECTRIC.equals(source)) {
			if (this.trackPos == null) {
				return false;
			}
			double zc = centerZ();
			for (double offset : new double[]{0.0, this.frontZ - zc, this.rearZ - zc}) {
				TrackSegment segment = network.segment(network.walk(this.trackPos, offset).segmentId());
				if (segment != null && segment.electrified()) {
					return true;
				}
			}
			return false;
		}
		return true;
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
		TrackNetwork.Walk frontWalk = network.walk(center, this.frontZ - zc);
		TrackNetwork.Walk rearWalk = network.walk(center, this.rearZ - zc);
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

		updateBogieAngles(network, center, targetYaw, targetPitch);
	}

	private void updateBogieAngles(TrackNetwork network, TrackPos center, float bodyYaw, float bodyPitch) {
		int n = this.bogies.size();
		if (this.bogieYaw.length != n) {
			this.bogieYaw = new float[n];
			this.prevBogieYaw = new float[n];
			this.bogiePitch = new float[n];
			this.prevBogiePitch = new float[n];
		}
		double zc = centerZ();
		for (int i = 0; i < n; i++) {
			BogieSpec bogie = this.bogies.get(i);
			TrackNetwork.Walk walk = network.walk(center, bogie.contactZ - zc);
			TrackPoint p = network.pointAt(walk.pos());
			this.prevBogieYaw[i] = this.bogieYaw[i];
			this.prevBogiePitch[i] = this.bogiePitch[i];
			if (p == null) {
				continue;
			}
			double dirX = p.dirX() * walk.facing();
			double dirZ = p.dirZ() * walk.facing();
			float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
			float pitch = (float) -Math.toDegrees(Math.atan(p.grade() * walk.facing()));
			this.bogieYaw[i] = MathHelper.wrapDegrees(yaw - bodyYaw);
			this.bogiePitch[i] = pitch - bodyPitch;
		}
	}

	// ------------------------------------------------------------------ 台車

	private record BogieSpec(String part, float pivotX, float pivotY, float pivotZ, double contactZ) {
	}

	/** サーバー側: パラメータから台車設定文字列を作って同期する。 */
	private void updateBogieSpec(RailVehicleParams params) {
		StringBuilder sb = new StringBuilder();
		float scale = this.getScale();
		for (RailVehicleParams.Bogie bogie : params.bogies()) {
			if (!sb.isEmpty()) {
				sb.append(';');
			}
			sb.append(bogie.part()).append('|').append(bogie.pivotX()).append('|').append(bogie.pivotY())
					.append('|').append(bogie.pivotZ()).append('|').append(bogie.pivotZ() * scale);
		}
		String spec = sb.toString();
		if (!spec.equals(this.dataTracker.get(BOGIE_SPEC))) {
			this.dataTracker.set(BOGIE_SPEC, spec);
		}
		parseBogieSpec(spec);
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
				if (f.length != 5) {
					continue;
				}
				try {
					list.add(new BogieSpec(f[0], Float.parseFloat(f[1]), Float.parseFloat(f[2]),
							Float.parseFloat(f[3]), Double.parseDouble(f[4])));
				} catch (NumberFormatException ignored) {
					// 壊れた項目は無視する
				}
			}
		}
		this.bogies = List.copyOf(list);
		double max = Double.NEGATIVE_INFINITY;
		double min = Double.POSITIVE_INFINITY;
		for (BogieSpec b : this.bogies) {
			max = Math.max(max, b.contactZ);
			min = Math.min(min, b.contactZ);
		}
		if (this.bogies.isEmpty()) {
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
	}

	@Override
	public Map<String, Matrix4f> tudursvehiclemod$getCustomPartTransforms(float tickDelta) {
		if (this.bogies.isEmpty() || this.bogieYaw.length != this.bogies.size()) {
			return Map.of();
		}
		Map<String, Matrix4f> transforms = new HashMap<>();
		for (int i = 0; i < this.bogies.size(); i++) {
			BogieSpec bogie = this.bogies.get(i);
			if (bogie.part.isEmpty()) {
				continue;
			}
			float yaw = MathHelper.lerp(tickDelta, this.prevBogieYaw[i], this.bogieYaw[i]);
			float pitch = MathHelper.lerp(tickDelta, this.prevBogiePitch[i], this.bogiePitch[i]);
			// 車体の回転と同じ規約(rotationY(-yaw)→rotateX(pitch))で、台車の回転中心まわりに回す
			Matrix4f m = new Matrix4f()
					.translate(bogie.pivotX, bogie.pivotY, bogie.pivotZ)
					.rotateY((float) Math.toRadians(-yaw))
					.rotateX((float) Math.toRadians(pitch))
					.translate(-bogie.pivotX, -bogie.pivotY, -bogie.pivotZ);
			transforms.put(bogie.part, m);
		}
		return transforms;
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
	}
}
