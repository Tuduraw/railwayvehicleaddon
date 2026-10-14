package com.example.railwayvehicleaddon.operation;

import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 運転・保安の処理で使う、ディメンションごとのサーバー上の状態(保存しない)。
 * <ul>
 *   <li>編成の在線表: 編成ごとに、線路のどの範囲に載っているか。毎tick、編成の位置を計算した車が登録する</li>
 *   <li>地上子の索引: 線路に連結した信号機・停止位置目標の位置。ブロックエンティティが定期的に登録し直す</li>
 *   <li>分岐器の予約(進路の自動設定)と、鎖錠中に保留した転てつ機の入力</li>
 * </ul>
 */
public final class OperationState {
	private static final Map<ServerWorld, OperationState> STATES = new WeakHashMap<>();
	/** 在線表の登録がこれより古い(tick)編成は、もう無いものとして扱う */
	private static final long CONSIST_STALE_TICKS = 1L;
	/** 地上子の登録がこれより古い(tick)ものは、チャンクの読み込みが外れたものとして扱う */
	private static final long WAYSIDE_STALE_TICKS = 60L;

	public static OperationState get(ServerWorld world) {
		synchronized (STATES) {
			OperationState state = STATES.get(world);
			if (state == null) {
				state = new OperationState(StopMarkerState.get(world));
				STATES.put(world, state);
			}
			return state;
		}
	}

	private final StopMarkerState stopMarkers;

	private OperationState(StopMarkerState stopMarkers) {
		this.stopMarkers = stopMarkers;
		// 停止位置目標は保存してあるものを最初から索引に入れる(読み込まれていない駅へも進路を探せるように)
		for (StopMarkerState.Entry e : stopMarkers.entries()) {
			this.wayside.put(e.pos(), new Wayside(e.pos(), WaysideKind.STOP_MARKER, e.segmentId(), e.s(), e.dir(),
					e.station(), e.platform(), Long.MAX_VALUE));
		}
	}

	// ------------------------------------------------------------------ 編成の在線表

	/**
	 * 1つの編成の在線の記録。
	 *
	 * @param key        編成の識別(位置を計算した車のUUID)
	 * @param members    編成の車両のUUID
	 * @param controller 運行表を持つ車両(無ければnull)。列車番号検知器の出力に使う
	 * @param tick       登録したワールド時刻
	 */
	public record Consist(UUID key, Set<UUID> members, RailVehicleEntity controller, long tick) {
	}

	/**
	 * 編成が載っている範囲(区間ごと)。s0 &lt;= s1。travelDirは編成の進行方向を区間の向きで表したもの
	 * (+1でA→B、-1でB→A)。
	 */
	public record Span(long segmentId, double s0, double s1, int travelDir, Consist consist) {
	}

	private final Map<UUID, Consist> consists = new HashMap<>();
	private final Map<UUID, List<Span>> consistSpans = new HashMap<>();
	private final Map<Long, List<Span>> spansBySegment = new HashMap<>();

	/** 編成の在線範囲を登録する(同じ編成の前回の登録は置き換える)。 */
	public void registerConsist(Consist consist, List<Span> spans) {
		removeConsist(consist.key());
		this.consists.put(consist.key(), consist);
		this.consistSpans.put(consist.key(), spans);
		for (Span span : spans) {
			this.spansBySegment.computeIfAbsent(span.segmentId(), k -> new ArrayList<>(2)).add(span);
		}
	}

	private void removeConsist(UUID key) {
		this.consists.remove(key);
		List<Span> old = this.consistSpans.remove(key);
		if (old == null) {
			return;
		}
		for (Span span : old) {
			List<Span> list = this.spansBySegment.get(span.segmentId());
			if (list != null) {
				list.remove(span);
				if (list.isEmpty()) {
					this.spansBySegment.remove(span.segmentId());
				}
			}
		}
	}

	/** 区間に載っている、今tickまたは前tickに登録された編成の範囲。 */
	public List<Span> spansOn(long segmentId, long now) {
		List<Span> list = this.spansBySegment.get(segmentId);
		if (list == null || list.isEmpty()) {
			return List.of();
		}
		List<Span> result = new ArrayList<>(list.size());
		for (Span span : list) {
			if (span.consist().tick() >= now - CONSIST_STALE_TICKS) {
				result.add(span);
			}
		}
		return result;
	}

	// ------------------------------------------------------------------ 地上子

	public enum WaysideKind {
		SIGNAL, STOP_MARKER
	}

	/**
	 * 線路に連結した地上子。dirはその地上子が効く列車の進行方向(区間の向きで、+1でA→B)。
	 * 停止位置目標のnameは駅名、platformは番線(0は指定なし)。seenは最後に登録された時刻
	 * (信号機はチャンクの読み込みが外れたら古くなる。停止位置目標は保存してあるので古くならない)。
	 */
	public record Wayside(BlockPos pos, WaysideKind kind, long segmentId, double s, int dir, String name, int platform, long seen) {
	}

	private final Map<BlockPos, Wayside> wayside = new HashMap<>();
	private Map<Long, List<Wayside>> waysideBySegment = null;

	public void putWayside(Wayside entry) {
		if (entry.kind() == WaysideKind.STOP_MARKER) {
			entry = new Wayside(entry.pos(), entry.kind(), entry.segmentId(), entry.s(), entry.dir(), entry.name(),
					entry.platform(), Long.MAX_VALUE);
			this.stopMarkers.put(new StopMarkerState.Entry(entry.pos(), entry.segmentId(), entry.s(), entry.dir(),
					entry.name(), entry.platform()));
		} else {
			this.stopMarkers.remove(entry.pos());
		}
		final Wayside added = entry;
		Wayside old = this.wayside.put(added.pos(), added);
		if (old == null || old.segmentId() != added.segmentId() || old.s() != added.s() || old.dir() != added.dir()
				|| old.kind() != added.kind() || !old.name().equals(added.name()) || old.platform() != added.platform()) {
			this.waysideBySegment = null;
		} else {
			// 位置が同じなら索引はそのまま(登録時刻だけ更新)
			List<Wayside> list = this.waysideBySegment == null ? null : this.waysideBySegment.get(added.segmentId());
			if (list != null) {
				list.replaceAll(w -> w.pos().equals(added.pos()) ? added : w);
			}
		}
	}

	/** 地上子を索引から外す(停止位置目標は保存からも消す。ブロックが壊されたとき)。 */
	public void removeWayside(BlockPos pos) {
		this.stopMarkers.remove(pos);
		unloadWayside(pos);
	}

	/** 地上子を索引から外す(チャンクの読み込みが外れた信号機)。 */
	public void unloadWayside(BlockPos pos) {
		if (this.wayside.remove(pos) != null) {
			this.waysideBySegment = null;
		}
	}

	/** 区間に連結している地上子(登録が新しいものだけ)。 */
	public List<Wayside> waysideOn(long segmentId, long now) {
		if (this.waysideBySegment == null) {
			Map<Long, List<Wayside>> index = new HashMap<>();
			for (Wayside w : this.wayside.values()) {
				index.computeIfAbsent(w.segmentId(), k -> new ArrayList<>(2)).add(w);
			}
			this.waysideBySegment = index;
		}
		List<Wayside> list = this.waysideBySegment.get(segmentId);
		if (list == null) {
			return List.of();
		}
		List<Wayside> result = new ArrayList<>(list.size());
		for (Wayside w : list) {
			if (w.seen() >= now - WAYSIDE_STALE_TICKS) {
				result.add(w);
			}
		}
		return result;
	}

	/** 駅名・番線(0は任意の番線)に合う停止位置目標。 */
	public List<Wayside> stopMarkers(String station, int platform, long now) {
		List<Wayside> result = new ArrayList<>();
		for (Wayside w : this.wayside.values()) {
			if (w.kind() == WaysideKind.STOP_MARKER && w.seen() >= now - WAYSIDE_STALE_TICKS
					&& w.name().equals(station) && (platform <= 0 || w.platform() == platform)) {
				result.add(w);
			}
		}
		return result;
	}

	// ------------------------------------------------------------------ 分岐器の予約・保留

	/** 分岐器ノード → 予約(進路の自動設定)。holderは運行表を持つ車両のUUID、untilは期限のワールド時刻。 */
	public record Reservation(UUID holder, long until) {
	}

	private final Map<Long, Reservation> reservations = new HashMap<>();
	/** 分岐器ノード → 鎖錠中に届いた転てつ機の入力(分岐側の番号)。鎖錠が解けたら適用する */
	private final Map<Long, Integer> pendingSwitches = new HashMap<>();

	public Reservation reservation(long nodeId, long now) {
		Reservation r = this.reservations.get(nodeId);
		if (r != null && r.until() < now) {
			this.reservations.remove(nodeId);
			return null;
		}
		return r;
	}

	public void reserve(long nodeId, UUID holder, long until) {
		this.reservations.put(nodeId, new Reservation(holder, until));
	}

	public Map<Long, Integer> pendingSwitches() {
		return this.pendingSwitches;
	}

	// ------------------------------------------------------------------ 進路の探索の結果(短時間だけ使い回す)

	public record RouteChoice(long segmentId, long until) {
	}

	private final Map<String, RouteChoice> routeCache = new HashMap<>();

	public RouteChoice cachedRoute(String key, long now) {
		RouteChoice choice = this.routeCache.get(key);
		return choice != null && choice.until() >= now ? choice : null;
	}

	public void cacheRoute(String key, RouteChoice choice) {
		this.routeCache.put(key, choice);
	}

	// ------------------------------------------------------------------ ATSの動作中

	private final Map<UUID, Boolean> atsLatched = new HashMap<>();

	public boolean atsLatched(UUID consistKey) {
		return this.atsLatched.getOrDefault(consistKey, false);
	}

	public void setAtsLatched(UUID consistKey, boolean latched) {
		if (latched) {
			this.atsLatched.put(consistKey, true);
		} else {
			this.atsLatched.remove(consistKey);
		}
	}

	// ------------------------------------------------------------------ 後片付け

	/** 古い登録を消す(ワールドのtickの終わりに呼ぶ)。 */
	public void cleanup(long now) {
		for (Iterator<Map.Entry<UUID, Consist>> it = this.consists.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Consist> e = it.next();
			if (e.getValue().tick() < now - 2) {
				List<Span> old = this.consistSpans.remove(e.getKey());
				if (old != null) {
					for (Span span : old) {
						List<Span> list = this.spansBySegment.get(span.segmentId());
						if (list != null) {
							list.remove(span);
							if (list.isEmpty()) {
								this.spansBySegment.remove(span.segmentId());
							}
						}
					}
				}
				this.atsLatched.remove(e.getKey());
				it.remove();
			}
		}
		if (now % 100 == 0) {
			this.reservations.values().removeIf(r -> r.until() < now);
			this.routeCache.values().removeIf(r -> r.until() < now);
			int before = this.wayside.size();
			this.wayside.values().removeIf(w -> w.seen() < now - WAYSIDE_STALE_TICKS * 4);
			if (this.wayside.size() != before) {
				this.waysideBySegment = null;
			}
		}
	}
}
