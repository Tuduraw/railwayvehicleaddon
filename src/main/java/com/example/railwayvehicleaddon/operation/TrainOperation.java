package com.example.railwayvehicleaddon.operation;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.block.SignalBlock;
import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.track.TrackManager;
import com.example.railwayvehicleaddon.track.TrackNetwork;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 編成ごとの運転・保安の処理。毎tick、編成の位置をまとめて計算する車(単独の車両はその車両)から呼ばれる。
 * <ol>
 *   <li>編成の在線範囲を在線表に登録する(分岐器の鎖錠・列車番号検知器が使う)</li>
 *   <li>前方の線路を、分岐器の開通方向に沿って先読みする
 *     <ul>
 *       <li>進路の自動設定: 運行表の次の駅へ向かう進路になるよう、前方の分岐器を切り替えて予約する</li>
 *       <li>ATS: 最も近い信号機の現示(停止・注意)に対して速度が高すぎればブレーキを掛け、力行を切る</li>
 *     </ul>
 *   </li>
 *   <li>運行表の次の駅の停止位置目標に着いたら(先頭が3ブロック手前まで来たか、編成が目標の上にいる)、次の駅へ進む</li>
 * </ol>
 */
public final class TrainOperation {
	/** 停止中とみなす速さ(ブロック/tick) */
	private static final double MOVING = 1.0e-4;
	/** 停止現示の信号機の手前で止まる余裕(ブロック。編成の先頭の連結面から信号機の位置まで) */
	private static final double STOP_MARGIN = 2.0;
	/** ATSの速度照査に使うブレーキの割合(車両の最大のブレーキより弱い減速度で照査し、余裕を持たせる) */
	private static final double PATTERN_BRAKE_RATIO = 0.8;
	/** 先読みの最短・最長距離 */
	private static final double MIN_SCAN = 64.0;
	private static final double MAX_SCAN = 512.0;
	/** 分岐器の予約の有効時間(tick)。先読みのたびに延長し、通り過ぎたら期限で外れる */
	private static final long RESERVE_TICKS = 40L;
	/** 停止位置目標のこの距離まで来たら、運行表の次の駅へ進む */
	private static final double ARRIVAL_DISTANCE = 3.0;

	private TrainOperation() {
	}

	/**
	 * 編成の1両。offsetは計算する車(leader)の中心からの距離(leaderの前方が正)、signはleaderと同じ向きなら+1。
	 * front/rearはその車の中心から前後の連結面までの距離(車の前方が正。frontは正、rearは負)。
	 */
	public record Car(RailVehicleEntity entity, double offset, int sign, double front, double rear) {
	}

	/** 処理の結果。brakeはATSのブレーキ、cutPowerは力行を切る。 */
	public record Result(boolean brake, boolean cutPower) {
		public static final Result NONE = new Result(false, false);
	}

	/**
	 * @param leaderPos   計算する車の線路上の位置
	 * @param speed       編成の速度(leaderの前方が正、ブロック/tick)
	 * @param handle      運転のハンドル(スロットル。leaderの前方が正)
	 * @param brakeDecel  編成のブレーキの減速度(ブロック/tick²)
	 */
	public static Result update(ServerWorld world, TrackNetwork network, RailVehicleEntity leader, TrackPos leaderPos,
								List<Car> cars, double speed, float handle, double brakeDecel) {
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
		int sigma = speed > MOVING ? 1 : speed < -MOVING ? -1 : handle > 0f ? 1 : handle < 0f ? -1 : 0;
		if (Math.abs(speed) > MOVING) {
			for (Car car : cars) {
				car.entity().setLastTravelSign(sigma * car.sign());
			}
		}
		int travel = sigma != 0 ? sigma : leader.getLastTravelSign();
		RailVehicleEntity controller = selectController(cars, travel);

		// 1. 在線範囲(編成の後端から前端まで、線路に沿って)
		OperationState.Consist consist = new OperationState.Consist(leader.getUuid(), members, controller, now);
		List<OperationState.Span> spans = new ArrayList<>();
		TrackPos start = network.walk(leaderPos, min).pos();
		network.traverse(start.segmentId(), start.s(), start.facing(), Math.max(0.01, max - min),
				(segment, a, b, paramDir, distance) -> {
					spans.add(new OperationState.Span(segment.id(), Math.min(a, b), Math.max(a, b), paramDir * travel, consist));
					return true;
				});
		state.registerConsist(consist, spans);

		Timetable.Stop next = controller == null ? null : controller.nextStop();
		// 編成が次の駅の停止位置目標の上にいる(行き過ぎた場合も含む)なら、次の駅へ進む
		if (next != null && markerWithin(state, spans, next, now)) {
			advance(controller, cars);
			next = controller.nextStop();
		}
		boolean routing = next != null;
		boolean ats = hasCab && config.ats_enabled;
		if (sigma == 0 || (!routing && !ats)) {
			state.setAtsLatched(leader.getUuid(), false);
			return Result.NONE;
		}

		// 2. 先読み
		TrackPos front = network.walk(leaderPos, sigma > 0 ? max : min).pos();
		int paramDir = front.facing() * sigma;
		double b = Math.max(1.0e-4, brakeDecel * PATTERN_BRAKE_RATIO);
		double v = Math.abs(speed);
		double routeDistance = Math.max(config.route_request_distance, v * v / (2.0 * b) + 32.0);
		double scan = Math.min(MAX_SCAN, Math.max(MIN_SCAN, v * v / (2.0 * b) + MIN_SCAN));
		if (routing) {
			scan = Math.min(MAX_SCAN, Math.max(scan, routeDistance));
		}
		Scan result = new Scan();
		final Timetable.Stop target = next;
		final UUID holder = controller == null ? null : controller.getUuid();
		network.traverse(front.segmentId(), front.s(), paramDir, scan, new TrackNetwork.PathVisitor() {
			@Override
			public boolean span(TrackSegment segment, double sFrom, double sTo, int dir, double distance) {
				double nearestSignal = Double.MAX_VALUE;
				BlockPos signalPos = null;
				for (OperationState.Wayside w : state.waysideOn(segment.id(), now)) {
					if (w.dir() != dir) {
						continue;
					}
					double along = (w.s() - sFrom) * dir;
					if (along < -1.0e-6 || along > Math.abs(sTo - sFrom) + 1.0e-6) {
						continue;
					}
					double d = distance + Math.max(0.0, along);
					if (w.kind() == OperationState.WaysideKind.SIGNAL) {
						if (d < nearestSignal) {
							nearestSignal = d;
							signalPos = w.pos();
						}
					} else if (target != null && matches(w, target)) {
						result.markerDistance = Math.min(result.markerDistance, d);
					}
				}
				if (signalPos != null && result.signalPos == null) {
					result.signalPos = signalPos;
					result.signalDistance = nearestSignal;
				}
				return true;
			}

			@Override
			public void node(long nodeId, long fromSegment, double arrivalX, double arrivalZ, double distance) {
				if (target == null || holder == null || distance > routeDistance) {
					return;
				}
				List<Long> branches = network.switchBranches(nodeId);
				if (branches.isEmpty()) {
					return;
				}
				if (branches.contains(fromSegment)) {
					// 背向: 通ってくる分岐側へ開通させておく(割り出しを避ける)
					TrackManager.routeSwitch(world, nodeId, fromSegment, holder, now + RESERVE_TICKS);
					return;
				}
				long want = RouteFinder.choose(world, network, state, nodeId, fromSegment, arrivalX, arrivalZ, target);
				if (want >= 0) {
					TrackManager.routeSwitch(world, nodeId, want, holder, now + RESERVE_TICKS);
				}
			}
		});

		if (routing && result.markerDistance <= ARRIVAL_DISTANCE) {
			advance(controller, cars);
		}

		// 3. ATS
		boolean latched = state.atsLatched(leader.getUuid());
		boolean engage = false;
		Text reason = null;
		if (ats && result.signalPos != null && world.isChunkLoaded(result.signalPos)) {
			BlockState signal = world.getBlockState(result.signalPos);
			if (signal.getBlock() instanceof SignalBlock) {
				int aspect = signal.get(SignalBlock.ASPECT);
				double vt;
				double room;
				if (aspect == 2) {
					vt = 0.0;
					room = result.signalDistance - STOP_MARGIN;
					reason = Text.translatable("message.railwayvehicleaddon.ats.stop");
				} else if (aspect == 1) {
					vt = config.ats_caution_speed_kmh / 72.0;
					room = result.signalDistance;
					reason = Text.translatable("message.railwayvehicleaddon.ats.caution", Math.round(config.ats_caution_speed_kmh));
				} else {
					vt = Double.MAX_VALUE;
					room = 0.0;
				}
				if (aspect == 2 && room <= 0.5) {
					// 停止現示の信号機の直前: 停車中でも力行させない(少しずつ動き出して信号機を越えないように)
					engage = true;
				} else if (vt < Double.MAX_VALUE) {
					double allow = Math.sqrt(vt * vt + 2.0 * b * Math.max(0.0, room));
					if (v > allow + 1.0e-4) {
						engage = true;
					} else if (latched && v > 1.0e-3 && v >= allow * 0.85) {
						engage = true;          // 照査の速度より十分に下がるまでブレーキを緩めない
					}
				}
			}
		}
		if (engage && !latched && reason != null) {
			notifyDrivers(world, cars, reason);
		}
		state.setAtsLatched(leader.getUuid(), engage);
		return engage ? new Result(true, true) : Result.NONE;
	}

	private static final class Scan {
		BlockPos signalPos;
		double signalDistance = Double.MAX_VALUE;
		double markerDistance = Double.MAX_VALUE;
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

	private static void notifyDrivers(ServerWorld world, List<Car> cars, Text reason) {
		for (Car car : cars) {
			PlayerEntity driver = car.entity().drivingPlayer();
			if (driver instanceof ServerPlayerEntity player) {
				player.sendMessage(reason, true);
				world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 1.0f, 2.0f);
			}
		}
	}
}
