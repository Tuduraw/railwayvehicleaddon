package com.example.railwayvehicleaddon.operation;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.block.SignalBlock;
import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.track.TrackManager;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackPoint;
import com.example.railwayvehicleaddon.track.TrackPos;
import com.example.railwayvehicleaddon.track.TrackSegment;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 編成ごとの運転・保安の処理。毎tick、編成の位置をまとめて計算する車(単独の車両はその車両)から、
 * ハンドル・ブレーキを決める前に呼ばれる。
 * <ol>
 *   <li>編成の在線範囲を在線表に登録する(分岐器の鎖錠・列車番号検知器・自動閉塞が使う)</li>
 *   <li>速度制限標識を過ぎたら、編成の今の制限速度を更新する(上がるのは最後尾が過ぎてから)</li>
 *   <li>前方の線路を、分岐器の開通方向に沿って先読みする
 *     <ul>
 *       <li>進路の自動設定: 運行表の次の駅へ向かう進路になるよう、前方の分岐器を切り替えて予約する</li>
 *       <li>ATS: 先読みの範囲の信号機・速度制限標識をすべて照査し、どれかに対して速度が高すぎれば
 *           ブレーキを掛け、力行を切る</li>
 *       <li>ATO: 先読みで集めた制約(信号機・速度制限・停車駅・車止め・前の列車)から目標速度を決め、
 *           ハンドルとブレーキの強さを指令する。駅に止まり、停車時間・発車時刻を守って発車する</li>
 *     </ul>
 *   </li>
 * </ol>
 */
public final class TrainOperation {
	/** 停止中とみなす速さ(ブロック/tick) */
	private static final double MOVING = 1.0e-4;
	/** 停車したとみなす速さ(ブロック/tick。ATOの駅の到着) */
	private static final double STOPPED = 5.0e-3;
	/** ATS: 停止現示の信号機の手前で止まる余裕(ブロック。編成の先頭の連結面から信号機の位置まで) */
	private static final double STOP_MARGIN = 2.0;
	/** ATO: 停止現示の信号機の手前で止まる位置(ATSより手前に止まる) */
	private static final double ATO_SIGNAL_MARGIN = 3.5;
	/** ATO: 車止め・線路の終わりの手前で止まる余裕 */
	private static final double ATO_END_MARGIN = 1.5;
	/** ATO: 前の列車の最後尾の手前で止まる余裕 */
	private static final double ATO_TRAIN_MARGIN = 10.0;
	/** ATO: 開通していない(切り替えられない)分岐器の手前で止まる余裕 */
	private static final double ATO_SWITCH_MARGIN = 6.0;
	/** ATSの速度照査に使うブレーキの割合(車両の最大のブレーキより弱い減速度で照査し、余裕を持たせる) */
	private static final double PATTERN_BRAKE_RATIO = 0.8;
	/** ATOの常用ブレーキの割合(ATSの照査より弱くし、ATSが掛からないようにする) */
	private static final double SERVICE_BRAKE_RATIO = 0.6;
	/** ATSが速度制限を超えたとみなす余裕 */
	private static final double LIMIT_TOLERANCE = 5.0 * Ato.KMH;
	/** 先読みの最短・最長距離 */
	private static final double MIN_SCAN = 64.0;
	private static final double MAX_SCAN = 512.0;
	/** 分岐器の予約の有効時間(tick)。先読みのたびに延長し、通り過ぎたら期限で外れる */
	private static final long RESERVE_TICKS = 40L;
	/** 停止位置目標のこの距離まで来たら、運行表の次の駅へ進む(手動運転・通過駅) */
	private static final double ARRIVAL_DISTANCE = 3.0;
	/** ATOで、停止位置目標に先頭がこの距離以内で止まったら到着 */
	private static final double ATO_ARRIVAL_DISTANCE = 1.0;
	/** 発車時刻の待ち: 発車時刻からこの分数までの間なら発車する */
	private static final int DEPART_WINDOW_MINUTES = 30;
	/** 後方の速度制限標識を探す距離(制限の初期値) */
	private static final double LIMIT_SEARCH_BACK = 1024.0;
	/** 読み込んでおく先読みの経路の長さの上限と、先読みより先に余分に読み込む長さ */
	private static final double MAX_FORCE_AHEAD = 640.0;
	private static final double FORCE_AHEAD_MARGIN = 96.0;
	/** 読み込むチャンクを計算し直す間隔(tick) */
	private static final int CHUNK_INTERVAL = 10;

	private TrainOperation() {
	}

	/**
	 * 編成の1両。offsetは計算する車(leader)の中心からの距離(leaderの前方が正)、signはleaderと同じ向きなら+1。
	 * front/rearはその車の中心から前後の連結面までの距離(車の前方が正。frontは正、rearは負)。
	 */
	public record Car(RailVehicleEntity entity, double offset, int sign, double front, double rear) {
	}

	/**
	 * 処理の結果。
	 *
	 * @param ato      ATOで運転している(handleを運転のハンドルの代わりに使う)
	 * @param handle   ATOのハンドル(leaderの前方が正、-1〜1)
	 * @param brake    ブレーキの強さ(0〜1。ATS・ATO)
	 * @param cutPower 力行を切る(ATS)
	 */
	public record Result(boolean ato, float handle, double brake, boolean cutPower) {
		public static final Result NONE = new Result(false, 0f, 0.0, false);
	}

	/**
	 * @param leaderPos   計算する車の線路上の位置
	 * @param speed       編成の速度(leaderの前方が正、ブロック/tick)
	 * @param handle      運転者のハンドル(スロットル。leaderの前方が正)
	 * @param brakeDecel  編成のブレーキの減速度(ブロック/tick²)
	 * @param maxSpeed    ハンドル1のときの目標速度(編成の動力車の最高速度)
	 * @param capSpeed    編成の最高速度(各車の最高速度の最小)
	 * @param override    運転者がノッチ・ブレーキを操作している(ATOを切って手動に戻す)
	 */
	public static Result update(ServerWorld world, TrackNetwork network, RailVehicleEntity leader, TrackPos leaderPos,
								List<Car> cars, double speed, float handle, double brakeDecel, double maxSpeed, double capSpeed,
								boolean override) {
		if (leaderPos == null || network.segment(leaderPos.segmentId()) == null) {
			return Result.NONE;
		}
		long now = world.getTime();
		OperationState state = OperationState.get(world);
		RailwayConfig config = RailwayConfig.get();

		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		boolean hasCab = false;
		Set<UUID> members = new HashSet<>();
		for (Car car : cars) {
			for (double local : new double[]{car.front(), car.rear()}) {
				double e = car.offset() + car.sign() * local;
				min = Math.min(min, e);
				max = Math.max(max, e);
			}
			hasCab |= car.entity().hasDriverSeat();
			members.add(car.entity().getUuid());
		}
		double v = Math.abs(speed);
		int manualSigma = speed > MOVING ? 1 : speed < -MOVING ? -1 : handle > 0f ? 1 : handle < 0f ? -1 : 0;
		RailVehicleEntity controller = selectController(cars, manualSigma != 0 ? manualSigma : leader.getLastTravelSign());
		Car controllerCar = carOf(cars, controller);

		// ATO: 運転者がノッチ・ブレーキを操作したら手動に戻す
		AtoState ato = controller != null && controller.getTimetable() != null && controller.atoState().enabled
				? controller.atoState() : null;
		if (ato != null && override) {
			ato.enabled = false;
			ato.display = AtoState.Display.OFF;
			notifyDrivers(world, cars, Text.translatable("message.railwayvehicleaddon.ato.manual"), false);
			ato = null;
		}
		int atoLeaderDir = ato != null && controllerCar != null ? ato.dir * controllerCar.sign() : 0;
		int sigma = ato != null ? (atoLeaderDir != 0 ? atoLeaderDir : (speed > MOVING ? 1 : speed < -MOVING ? -1 : 0)) : manualSigma;
		if (Math.abs(speed) > MOVING) {
			int moving = speed > 0 ? 1 : -1;
			for (Car car : cars) {
				car.entity().setLastTravelSign(moving * car.sign());
			}
		}
		int travel = sigma != 0 ? sigma : leader.getLastTravelSign();

		// 1. 在線範囲(編成の後端から前端まで、線路に沿って)
		OperationState.Consist consist = new OperationState.Consist(leader.getUuid(), members, controller, now);
		List<OperationState.Span> spans = new ArrayList<>();
		final int spanTravel = travel;
		TrackPos start = network.walk(leaderPos, min).pos();
		network.traverse(start.segmentId(), start.s(), start.facing(), Math.max(0.01, max - min),
				(segment, a, b, paramDir, distance) -> {
					spans.add(new OperationState.Span(segment.id(), Math.min(a, b), Math.max(a, b), paramDir * spanTravel, consist));
					return true;
				});
		state.registerConsist(consist, spans);

		// 2. 速度制限(編成の今の制限)
		TrackPos front = network.walk(leaderPos, travel > 0 ? max : min).pos();
		int frontDir = front.facing() * travel;
		OperationState.SpeedLimitState limit = updateSpeedLimit(state, network, leader.getUuid(), spans, front, frontDir, travel, now);

		Timetable.Stop next = controller == null ? null : controller.nextStop();
		if (ato == null && next != null && markerWithin(state, spans, next, now)) {
			// 手動運転: 編成が次の駅の停止位置目標の上にいる(行き過ぎた場合も含む)なら、次の駅へ進む
			advance(controller, cars);
			next = controller.nextStop();
		}

		// ATO: 発車の向きを決める(割り当て直後・発車時)
		if (ato != null && ato.phase == AtoState.Phase.RUN && ato.dir == 0) {
			if (next == null) {
				ato.phase = AtoState.Phase.FINISHED;
			} else if (markerWithin(state, spans, next, now) && !next.pass()) {
				arrive(world, ato, next, now, cars);
			} else {
				int dir = departureDirection(world, network, state, leaderPos, min, max, next, controller.getUuid(), now);
				if (dir == 0) {
					ato.phase = AtoState.Phase.NO_ROUTE;
					ato.dwellUntil = now + 40L;
				} else if (controllerCar != null) {
					ato.dir = dir * controllerCar.sign();
					atoLeaderDir = dir;
					sigma = dir;
				}
			}
		} else if (ato != null && ato.phase == AtoState.Phase.NO_ROUTE && now >= ato.dwellUntil) {
			ato.phase = AtoState.Phase.RUN;
			ato.dir = 0;
		}
		if (ato != null && sigma != travel && sigma != 0) {
			travel = sigma;
			front = network.walk(leaderPos, travel > 0 ? max : min).pos();
			frontDir = front.facing() * travel;
		}

		boolean routing = next != null;
		boolean ats = hasCab && config.ats_enabled;
		if (controller != null && controller.isManaged() && (now + (leader.getUuid().hashCode() & 0xF)) % CHUNK_INTERVAL == 0) {
			state.setLoadChunks(leader.getUuid(), chunksFor(network, spans, front, frontDir, v, brakeDecel));
		}
		if (sigma == 0 || (!routing && !ats && ato == null)) {
			state.setAtsLatched(leader.getUuid(), false);
			if (ato != null) {
				updateDisplay(ato, cars, AtoState.Display.HELD, ato.phase, limit, -1.0);
				return new Result(true, 0f, 1.0, false);
			}
			return Result.NONE;
		}

		// 3. 先読み
		double b = Math.max(1.0e-4, brakeDecel * PATTERN_BRAKE_RATIO);
		double routeDistance = Math.max(config.route_request_distance, v * v / (2.0 * b) + 32.0);
		double scan = Math.min(MAX_SCAN, Math.max(MIN_SCAN, v * v / (2.0 * b) + MIN_SCAN));
		if (routing) {
			scan = Math.min(MAX_SCAN, Math.max(scan, routeDistance));
		}
		Scan result = new Scan();
		final Timetable.Stop target = next;
		final UUID holder = controller == null ? null : controller.getUuid();
		final boolean atoActive = ato != null;
		network.traverse(front.segmentId(), front.s(), frontDir, scan, new TrackNetwork.PathVisitor() {
			@Override
			public boolean span(TrackSegment segment, double sFrom, double sTo, int dir, double distance) {
				result.reached = distance + Math.abs(sTo - sFrom);
				for (OperationState.Wayside w : state.waysideOn(segment.id(), now)) {
					double along = (w.s() - sFrom) * dir;
					if (along < -1.0e-6 || along > Math.abs(sTo - sFrom) + 1.0e-6) {
						continue;
					}
					double d = distance + Math.max(0.0, along);
					switch (w.kind()) {
						case SIGNAL -> {
							// 信号機は正面に向かって進む列車にだけ効く。先読みの範囲の信号機はすべて照査する
							if (w.dir() == dir) {
								result.signals.add(new SignalAhead(w.pos(), d));
							}
						}
						case SPEED_LIMIT -> {
							if (w.dir() == dir && w.platform() > 0) {
								result.limits.add(new double[]{d, w.platform() * Ato.KMH});
							}
						}
						case STOP_MARKER -> {
							// 停止位置目標は向きを問わない
							if (target != null && matches(w, target)) {
								result.markerDistance = Math.min(result.markerDistance, d);
							}
						}
					}
				}
				if (atoActive && result.trainAhead == Double.MAX_VALUE) {
					// 前の列車(この編成以外の在線)の手前で止まる
					double lo = Math.min(sFrom, sTo);
					double hi = Math.max(sFrom, sTo);
					for (OperationState.Span other : state.spansOn(segment.id(), now)) {
						if (other.consist().key().equals(consist.key()) || other.s1() < lo || other.s0() > hi) {
							continue;
						}
						double near = dir > 0 ? Math.max(lo, other.s0()) : Math.min(hi, other.s1());
						result.trainAhead = Math.min(result.trainAhead, distance + Math.abs(near - sFrom));
					}
				}
				return true;
			}

			@Override
			public void node(long nodeId, long fromSegment, double arrivalX, double arrivalZ, double distance) {
				List<Long> branches = network.switchBranches(nodeId);
				if (branches.isEmpty()) {
					return;
				}
				if (target != null && holder != null && distance <= routeDistance) {
					if (branches.contains(fromSegment)) {
						// 背向: 通ってくる分岐側へ開通させておく(割り出しを避ける)
						TrackManager.routeSwitch(world, nodeId, fromSegment, holder, now + RESERVE_TICKS);
					} else {
						long want = RouteFinder.choose(world, network, state, nodeId, fromSegment, arrivalX, arrivalZ, target, holder);
						if (want >= 0) {
							TrackManager.routeSwitch(world, nodeId, want, holder, now + RESERVE_TICKS);
						}
					}
				}
				if (atoActive && branches.contains(fromSegment) && network.activeBranch(nodeId) != fromSegment
						&& result.blockedSwitch == Double.MAX_VALUE) {
					// 開通していない分岐側から入る(割り出し)ことになる: ATOは分岐器の手前で待つ
					result.blockedSwitch = distance;
				}
			}
		});
		boolean trackEnds = result.reached < scan - 1.0e-6;

		if (ato == null && routing && result.markerDistance <= ARRIVAL_DISTANCE) {
			advance(controller, cars);
		}

		// 4. ATS: 先読みの範囲の信号機・速度制限すべてについて、許容速度を求め、どれかを超えていればブレーキを掛ける
		//    (手前の信号機が進行でも、その先の停止の信号機に対して止まれる速さかを見る)
		boolean latched = state.atsLatched(leader.getUuid());
		boolean engage = false;
		Text reason = null;
		double strictest = Double.MAX_VALUE;
		List<SignalState> signals = readSignals(world, result.signals);
		if (ats) {
			List<double[]> checks = new ArrayList<>();       // {距離, 許容速度, 種類(0停止,1注意,2制限)}
			for (SignalState sig : signals) {
				if (sig.aspect() == 2) {
					checks.add(new double[]{sig.distance() - STOP_MARGIN, 0.0, 0});
				} else if (sig.aspect() == 1) {
					checks.add(new double[]{sig.distance(), config.ats_caution_speed_kmh * Ato.KMH, 1});
				}
			}
			for (double[] l : result.limits) {
				checks.add(new double[]{l[0], l[1], 2});
			}
			for (double[] c : checks) {
				double room = c[0];
				double vt = c[1];
				boolean over;
				double allow;
				if (c[2] == 0 && room <= 0.5) {
					// 停止現示の信号機の直前: 停車中でも力行させない(少しずつ動き出して信号機を越えないように)
					allow = 0.0;
					over = true;
				} else {
					allow = Math.sqrt(vt * vt + 2.0 * b * Math.max(0.0, room));
					if (c[2] == 2) {
						allow += LIMIT_TOLERANCE;
					}
					// 動作中は、照査の速度より十分に下がるまでブレーキを緩めない
					over = v > allow + 1.0e-4 || (latched && v > 1.0e-3 && v >= allow * 0.85);
				}
				if (over) {
					engage = true;
					if (allow < strictest) {
						strictest = allow;
						reason = c[2] == 0 ? Text.translatable("message.railwayvehicleaddon.ats.stop")
								: c[2] == 1 ? Text.translatable("message.railwayvehicleaddon.ats.caution", Math.round(config.ats_caution_speed_kmh))
								: Text.translatable("message.railwayvehicleaddon.ats.limit", Math.round(vt / Ato.KMH));
					}
				}
			}
			if (limit.current < Double.MAX_VALUE && v > limit.current + LIMIT_TOLERANCE
					&& limit.current + LIMIT_TOLERANCE < strictest) {
				engage = true;
				strictest = limit.current + LIMIT_TOLERANCE;
				reason = Text.translatable("message.railwayvehicleaddon.ats.limit", Math.round(limit.current / Ato.KMH));
			}
		}
		if (engage && !latched && reason != null) {
			notifyDrivers(world, cars, reason, true);
		}
		state.setAtsLatched(leader.getUuid(), engage);

		// 5. ATO
		if (ato != null) {
			return runAto(world, ato, controller, cars, state, spans, next, result, signals, trackEnds, limit, v,
					brakeDecel, maxSpeed, capSpeed, atoLeaderDir, engage, now, config);
		}
		return engage ? new Result(false, 0f, 1.0, true) : Result.NONE;
	}

	// ------------------------------------------------------------------ ATO

	private static Result runAto(ServerWorld world, AtoState ato, RailVehicleEntity controller, List<Car> cars,
								 OperationState state, List<OperationState.Span> spans, Timetable.Stop next, Scan scan,
								 List<SignalState> signals, boolean trackEnds, OperationState.SpeedLimitState limit, double v,
								 double brakeDecel, double maxSpeed, double capSpeed, int leaderDir, boolean atsEngaged,
								 long now, RailwayConfig config) {
		double markerDistance = scan.markerDistance;
		// 駅の動作
		switch (ato.phase) {
			case RUN -> {
				if (next == null) {
					ato.phase = AtoState.Phase.FINISHED;
				} else if (next.pass()) {
					if (markerWithin(state, spans, next, now) || markerDistance <= ARRIVAL_DISTANCE) {
						advance(controller, cars);
					}
				} else if (v < STOPPED && (markerDistance <= ATO_ARRIVAL_DISTANCE || markerWithin(state, spans, next, now))) {
					arrive(world, ato, next, now, cars);
				}
			}
			case DWELL, WAIT_DEPART -> {
				if (now >= ato.dwellUntil) {
					Timetable.Stop at = controller.nextStop();
					if (at != null && at.departTime() >= 0) {
						int minutes = Timetable.minutesOfDay(world.getTimeOfDay());
						int late = Math.floorMod(minutes - at.departTime(), 1440);
						if (late >= DEPART_WINDOW_MINUTES) {
							ato.phase = AtoState.Phase.WAIT_DEPART;
							break;
						}
					}
					// 発車: 次の駅へ進み、向きを決め直す(折り返しなら逆向きになる)
					advance(controller, cars);
					ato.phase = AtoState.Phase.RUN;
					ato.dir = 0;
					ato.dwellUntil = -1L;
				}
			}
			default -> {
			}
		}

		double cap = Math.min(capSpeed, limit.current);
		AtoState.Display display;
		Ato.Command command;
		if (ato.phase != AtoState.Phase.RUN || ato.dir == 0 || leaderDir == 0) {
			command = Ato.Command.HOLD;
			display = switch (ato.phase) {
				case DWELL -> AtoState.Display.DWELL;
				case WAIT_DEPART -> AtoState.Display.WAIT_DEPART;
				case FINISHED -> AtoState.Display.FINISHED;
				case NO_ROUTE -> AtoState.Display.NO_ROUTE;
				default -> AtoState.Display.HELD;
			};
		} else {
			List<Ato.Constraint> constraints = new ArrayList<>();
			for (SignalState sig : signals) {
				if (sig.aspect() == 2) {
					constraints.add(Ato.Constraint.stop(sig.distance() - ATO_SIGNAL_MARGIN));
				} else if (sig.aspect() == 1) {
					constraints.add(new Ato.Constraint(sig.distance(), config.ats_caution_speed_kmh * Ato.KMH));
				}
			}
			for (double[] l : scan.limits) {
				constraints.add(new Ato.Constraint(l[0], l[1]));
			}
			Timetable.Stop stopAt = controller.nextStop();
			if (stopAt != null && !stopAt.pass()) {
				if (markerDistance < Double.MAX_VALUE) {
					constraints.add(Ato.Constraint.stop(markerDistance));
				} else if (markerWithin(state, spans, stopAt, now)) {
					constraints.add(Ato.Constraint.stop(0.0));     // 行き過ぎた: その場で止まる
				}
			}
			if (trackEnds) {
				constraints.add(Ato.Constraint.stop(scan.reached - ATO_END_MARGIN));
			}
			if (scan.trainAhead < Double.MAX_VALUE) {
				constraints.add(Ato.Constraint.stop(scan.trainAhead - ATO_TRAIN_MARGIN));
			}
			if (scan.blockedSwitch < Double.MAX_VALUE) {
				constraints.add(Ato.Constraint.stop(scan.blockedSwitch - ATO_SWITCH_MARGIN));
			}
			Ato.Target t = Ato.target(constraints, Math.max(1.0e-4, brakeDecel * SERVICE_BRAKE_RATIO), cap);
			command = Ato.control(v, t, brakeDecel, maxSpeed);
			if (t.speed() <= 1.0e-4 && v < STOPPED) {
				ato.held++;
			} else {
				ato.held = 0;
			}
			display = command.brake() > 0.0 ? (v < STOPPED ? AtoState.Display.HELD : AtoState.Display.BRAKE)
					: command.handle() > 0f ? AtoState.Display.POWER : AtoState.Display.COAST;
		}
		updateDisplay(ato, cars, display, ato.phase, limit, markerDistance < Double.MAX_VALUE ? markerDistance : -1.0);
		if (atsEngaged) {
			return new Result(true, 0f, 1.0, true);
		}
		return new Result(true, command.handle() * leaderDir, command.brake(), false);
	}

	/** 停車駅に着いた: 停車時間を数え始める。 */
	private static void arrive(ServerWorld world, AtoState ato, Timetable.Stop stop, long now, List<Car> cars) {
		ato.phase = AtoState.Phase.DWELL;
		ato.dwellUntil = now + Math.max(0, stop.dwell()) * 20L;
		notifyDrivers(world, cars, Text.translatable("message.railwayvehicleaddon.ato.arrived", stop.describe(), stop.dwell()), false);
	}

	/**
	 * 発車の向き(leaderの前方なら+1)。編成の前端から前へ、後端から後ろへ、次の駅の停止位置目標までの道のりを比べ、
	 * 短い方にする。どちらからも着けなければ0。
	 */
	private static int departureDirection(ServerWorld world, TrackNetwork network, OperationState state, TrackPos leaderPos,
										  double min, double max, Timetable.Stop next, UUID holder, long now) {
		Map<Long, List<Double>> targets = RouteFinder.targetsFor(state, network, next, now);
		if (targets.isEmpty()) {
			return 0;
		}
		java.util.function.LongPredicate fixed = n -> Interlock.locked(world, network, n, holder);
		TrackPos head = network.walk(leaderPos, max).pos();
		TrackPos tail = network.walk(leaderPos, min).pos();
		double forward = RouteFinder.distanceFrom(network, head.segmentId(), head.s(), head.facing(), targets, fixed);
		double backward = RouteFinder.distanceFrom(network, tail.segmentId(), tail.s(), -tail.facing(), targets, fixed);
		if (forward == Double.MAX_VALUE && backward == Double.MAX_VALUE) {
			return 0;
		}
		return forward <= backward ? 1 : -1;
	}

	private static void updateDisplay(AtoState ato, List<Car> cars, AtoState.Display display, AtoState.Phase phase,
									  OperationState.SpeedLimitState limit, double nextDistance) {
		ato.display = display;
		ato.limitKmh = limit.current < Double.MAX_VALUE ? limit.current / Ato.KMH : -1.0;
		ato.nextDistance = nextDistance;
		for (Car car : cars) {
			car.entity().setAtoDisplay(display.ordinal(), (float) ato.limitKmh, (float) nextDistance);
		}
	}

	// ------------------------------------------------------------------ 速度制限

	/**
	 * 編成の今の制限速度を更新する。新しく編成の範囲に入った(先頭が過ぎた)標識が今より低ければすぐ下げ、
	 * 高ければ最後尾がその標識を過ぎてから上げる。状態が無い(または進行方向が変わった)ときは、前端から後方へ
	 * 最初の標識をさかのぼって初期値にする。
	 */
	private static OperationState.SpeedLimitState updateSpeedLimit(OperationState state, TrackNetwork network, UUID key,
																	List<OperationState.Span> spans, TrackPos front, int frontDir,
																	int travel, long now) {
		OperationState.SpeedLimitState ls = state.speedLimit(key);
		Set<BlockPos> inside = new HashSet<>();
		Map<BlockPos, Integer> values = new java.util.HashMap<>();
		for (OperationState.Span span : spans) {
			for (OperationState.Wayside w : state.waysideOn(span.segmentId(), now)) {
				if (w.kind() == OperationState.WaysideKind.SPEED_LIMIT && w.dir() == span.travelDir()
						&& w.s() >= span.s0() && w.s() <= span.s1()) {
					inside.add(w.pos());
					values.put(w.pos(), w.platform());
				}
			}
		}
		if (!ls.initialized || ls.travel != travel) {
			ls.travel = travel;
			ls.initialized = true;
			ls.pending = Double.NaN;
			ls.pendingSign = null;
			ls.current = initialLimit(state, network, front, frontDir, now);
			ls.inside = inside;
			return ls;
		}
		for (BlockPos pos : inside) {
			if (ls.inside.contains(pos)) {
				continue;
			}
			int kmh = values.get(pos);
			double value = kmh > 0 ? kmh * Ato.KMH : Double.MAX_VALUE;
			if (value <= ls.current) {
				ls.current = value;
				ls.pending = Double.NaN;
				ls.pendingSign = null;
			} else {
				ls.pending = value;
				ls.pendingSign = pos;
			}
		}
		if (ls.pendingSign != null && !inside.contains(ls.pendingSign)) {
			// 最後尾が制限の上がる標識を過ぎた
			ls.current = ls.pending;
			ls.pending = Double.NaN;
			ls.pendingSign = null;
		}
		ls.inside = inside;
		return ls;
	}

	/** 前端から後方へさかのぼって、最初に見つかったこの向きの速度制限標識の値(見つからなければ制限なし)。 */
	private static double initialLimit(OperationState state, TrackNetwork network, TrackPos front, int frontDir, long now) {
		double[] found = {Double.NaN};
		network.traverse(front.segmentId(), front.s(), -frontDir, LIMIT_SEARCH_BACK, (segment, a, b, dir, distance) -> {
			double best = Double.MAX_VALUE;
			int value = 0;
			for (OperationState.Wayside w : state.waysideOn(segment.id(), now)) {
				if (w.kind() != OperationState.WaysideKind.SPEED_LIMIT || w.dir() != -dir) {
					continue;
				}
				double along = (w.s() - a) * dir;
				if (along < -1.0e-6 || along > Math.abs(b - a) + 1.0e-6) {
					continue;
				}
				if (along < best) {
					best = along;
					value = w.platform();
				}
			}
			if (best < Double.MAX_VALUE) {
				found[0] = value > 0 ? value * Ato.KMH : Double.MAX_VALUE;
				return false;
			}
			return true;
		});
		return Double.isNaN(found[0]) ? Double.MAX_VALUE : found[0];
	}

	// ------------------------------------------------------------------ チャンクの読み込み

	/**
	 * 運行管理装置が読み込んでおくチャンク: 編成の在線範囲の周り(3×3)と、前方の先読みの経路
	 * (線路の両側8ブロックまで。信号機・標識が線路のそばにあるため)。
	 */
	private static Set<ChunkPos> chunksFor(TrackNetwork network, List<OperationState.Span> spans, TrackPos front,
										   int frontDir, double v, double brakeDecel) {
		Set<ChunkPos> chunks = new HashSet<>();
		for (OperationState.Span span : spans) {
			TrackSegment segment = network.segment(span.segmentId());
			if (segment == null) {
				continue;
			}
			for (double s = span.s0(); ; s = Math.min(span.s1(), s + 8.0)) {
				TrackPoint p = segment.sample(s);
				int cx = ((int) Math.floor(p.x())) >> 4;
				int cz = ((int) Math.floor(p.z())) >> 4;
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						chunks.add(new ChunkPos(cx + dx, cz + dz));
					}
				}
				if (s >= span.s1()) {
					break;
				}
			}
		}
		double b = Math.max(1.0e-4, brakeDecel * PATTERN_BRAKE_RATIO);
		double ahead = Math.min(MAX_FORCE_AHEAD, v * v / (2.0 * b) + MIN_SCAN + FORCE_AHEAD_MARGIN);
		network.traverse(front.segmentId(), front.s(), frontDir, ahead, (segment, a, bEnd, dir, distance) -> {
			double len = Math.abs(bEnd - a);
			for (double d = 0.0; ; d = Math.min(len, d + 12.0)) {
				TrackPoint p = segment.sample(a + dir * d);
				for (int side = -1; side <= 1; side++) {
					double x = p.x() + p.lateralX() * 8.0 * side;
					double z = p.z() + p.lateralZ() * 8.0 * side;
					chunks.add(new ChunkPos(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4));
				}
				if (d >= len) {
					break;
				}
			}
			return true;
		});
		return chunks;
	}

	// ------------------------------------------------------------------ 共通

	/** 先読みで見つけた信号機(位置と、編成の先端からの道のり)。 */
	private record SignalAhead(BlockPos pos, double distance) {
	}

	/** 現示を読んだ信号機(0=進行、1=注意、2=停止)。 */
	private record SignalState(double distance, int aspect) {
	}

	private static List<SignalState> readSignals(ServerWorld world, List<SignalAhead> ahead) {
		List<SignalState> result = new ArrayList<>(ahead.size());
		for (SignalAhead a : ahead) {
			if (!world.isChunkLoaded(a.pos())) {
				continue;
			}
			BlockState signal = world.getBlockState(a.pos());
			if (signal.getBlock() instanceof SignalBlock) {
				result.add(new SignalState(a.distance(), signal.get(SignalBlock.ASPECT)));
			}
		}
		return result;
	}

	private static final class Scan {
		final List<SignalAhead> signals = new ArrayList<>();
		/** 速度制限標識 {距離, 制限速度(ブロック/tick)} */
		final List<double[]> limits = new ArrayList<>();
		double markerDistance = Double.MAX_VALUE;
		/** 前の列車の最後尾までの道のり */
		double trainAhead = Double.MAX_VALUE;
		/** 開通していない分岐側から入る分岐器までの道のり */
		double blockedSwitch = Double.MAX_VALUE;
		/** たどれた道のり(先読みの距離より短ければ線路の終わり・車止め) */
		double reached;
	}

	static boolean matches(OperationState.Wayside w, Timetable.Stop target) {
		return w.kind() == OperationState.WaysideKind.STOP_MARKER && w.name().equals(target.station())
				&& (target.platform() <= 0 || w.platform() == target.platform());
	}

	/** 編成の在線範囲の中に、次の駅の停止位置目標があるか。 */
	private static boolean markerWithin(OperationState state, List<OperationState.Span> spans, Timetable.Stop target, long now) {
		for (OperationState.Span span : spans) {
			for (OperationState.Wayside w : state.waysideOn(span.segmentId(), now)) {
				if (matches(w, target) && w.s() >= span.s0() - 0.5 && w.s() <= span.s1() + 0.5) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 運行表を持つ車両の選び方: 運転者が乗っている車両、なければ進行方向の一番前にある車両。
	 */
	private static RailVehicleEntity selectController(List<Car> cars, int travel) {
		RailVehicleEntity best = null;
		double bestPos = -Double.MAX_VALUE;
		for (Car car : cars) {
			if (car.entity().getTimetable() == null) {
				continue;
			}
			if (car.entity().drivingPlayer() != null) {
				return car.entity();
			}
			double p = car.offset() * (travel == 0 ? 1 : travel);
			if (best == null || p > bestPos) {
				best = car.entity();
				bestPos = p;
			}
		}
		return best;
	}

	private static Car carOf(List<Car> cars, RailVehicleEntity entity) {
		if (entity == null) {
			return null;
		}
		for (Car car : cars) {
			if (car.entity() == entity) {
				return car;
			}
		}
		return null;
	}

	private static void advance(RailVehicleEntity controller, List<Car> cars) {
		controller.advanceStop();
		Timetable.Stop next = controller.nextStop();
		Text text = next == null ? Text.translatable("message.railwayvehicleaddon.timetable.finished")
				: Text.translatable("message.railwayvehicleaddon.timetable.next", next.describe());
		for (Car car : cars) {
			if (car.entity().drivingPlayer() instanceof ServerPlayerEntity player) {
				player.sendMessage(text, true);
			}
		}
	}

	private static void notifyDrivers(ServerWorld world, List<Car> cars, Text reason, boolean bell) {
		for (Car car : cars) {
			PlayerEntity driver = car.entity().drivingPlayer();
			if (driver instanceof ServerPlayerEntity player) {
				player.sendMessage(reason, true);
				if (bell) {
					world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 1.0f, 2.0f);
				}
			}
		}
	}
}
