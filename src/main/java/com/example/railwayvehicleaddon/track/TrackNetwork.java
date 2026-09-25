package com.example.railwayvehicleaddon.track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.railwayvehicleaddon.track.feature.TrackFeature;

/**
 * 1ディメンション分の線路グラフ。サーバー(TrackNetworkStateが保存を担当)と
 * クライアント(同期された写し)で同じクラスを使い、走行位置の計算を一致させる。
 *
 * <p>ノードには任意の本数の区間が接続できる。3本以上接続したノードは分岐器で、
 * ノードから外へ伸びる向きで区間を2群に分け、多い側(分岐側)のどれに開通しているかを
 * switchStatesで持つ。走行時の次区間はnextSegmentAt()が進行方向から決める。
 */
public final class TrackNetwork {
	private final Map<Long, TrackNode> nodes = new HashMap<>();
	private final Map<Long, TrackSegment> segments = new HashMap<>();
	private final Map<Long, List<Long>> nodeSegments = new HashMap<>();
	/** チャンク座標(ChunkPos.toLong相当)→ そのチャンクを通る区間ID */
	private final Map<Long, Set<Long>> chunkIndex = new HashMap<>();
	private final Map<Long, long[]> segmentChunks = new HashMap<>();
	/** 区間ごとの平滑化済みカント(CANT_STEP間隔)。線路のつながりが変わると周辺を作り直す */
	private final Map<Long, float[]> cantTables = new HashMap<>();
	private double cantTransitionLength = 16.0;
	/** 連結されたノードの組(双方向)。転車台・遷車台の桁の端と接続部をつなぐ */
	private final Map<Long, Long> links = new HashMap<>();
	/** 車止めのある端ノード → 端から止まる位置までの距離 */
	private final Map<Long, Double> endMargins = new HashMap<>();
	private final Map<Long, TrackFeature> features = new java.util.LinkedHashMap<>();
	private final Map<Long, Long> segmentOwners = new HashMap<>();
	private final Set<Long> reservedNodes = new HashSet<>();
	/** 分割された区間ID → 分割結果(走行中の車両を載せ替えるため。保存しない) */
	private final Map<Long, SplitResult> splitHistory = new java.util.LinkedHashMap<>();
	/** 分岐器ノード → 開通している分岐側の区間ID */
	private final Map<Long, Long> switchStates = new HashMap<>();
	private long nextId = 1L;

	public long allocateId() {
		return this.nextId++;
	}

	public long peekNextId() {
		return this.nextId;
	}

	public void setNextId(long nextId) {
		this.nextId = Math.max(this.nextId, nextId);
	}

	public void clear() {
		this.nodes.clear();
		this.segments.clear();
		this.nodeSegments.clear();
		this.chunkIndex.clear();
		this.segmentChunks.clear();
		this.switchStates.clear();
		this.cantTables.clear();
		this.links.clear();
		this.endMargins.clear();
		this.features.clear();
		this.segmentOwners.clear();
		this.reservedNodes.clear();
		this.splitHistory.clear();
	}

	public void putNode(TrackNode node) {
		this.nodes.put(node.id(), node);
		this.nextId = Math.max(this.nextId, node.id() + 1);
	}

	public void putSegment(TrackSegment segment) {
		removeSegmentInternal(segment.id());
		invalidateCantAround(segment.nodeA(), segment.nodeB());
		this.segments.put(segment.id(), segment);
		this.nodeSegments.computeIfAbsent(segment.nodeA(), k -> new ArrayList<>()).add(segment.id());
		this.nodeSegments.computeIfAbsent(segment.nodeB(), k -> new ArrayList<>()).add(segment.id());
		long[] chunks = coveredChunks(segment);
		this.segmentChunks.put(segment.id(), chunks);
		for (long chunk : chunks) {
			this.chunkIndex.computeIfAbsent(chunk, k -> new HashSet<>()).add(segment.id());
		}
		this.nextId = Math.max(this.nextId, segment.id() + 1);
	}

	/** 区間を取り除き、接続先がなくなったノードも取り除く。取り除いたノードIDを返す。 */
	public List<Long> removeSegment(long segmentId) {
		TrackSegment removed = removeSegmentInternal(segmentId);
		if (removed == null) {
			return List.of();
		}
		List<Long> orphanNodes = new ArrayList<>(2);
		for (long node : new long[]{removed.nodeA(), removed.nodeB()}) {
			List<Long> list = this.nodeSegments.get(node);
			if (list == null || list.isEmpty()) {
				this.nodeSegments.remove(node);
				if (this.nodes.remove(node) != null) {
					orphanNodes.add(node);
				}
			}
		}
		return orphanNodes;
	}

	public void removeNode(long nodeId) {
		this.nodes.remove(nodeId);
		this.nodeSegments.remove(nodeId);
		this.switchStates.remove(nodeId);
		unlink(nodeId);
		this.endMargins.remove(nodeId);
	}

	private TrackSegment removeSegmentInternal(long segmentId) {
		TrackSegment removed = this.segments.remove(segmentId);
		if (removed == null) {
			return null;
		}
		this.cantTables.remove(segmentId);
		invalidateCantAround(removed.nodeA(), removed.nodeB());
		for (long node : new long[]{removed.nodeA(), removed.nodeB()}) {
			List<Long> list = this.nodeSegments.get(node);
			if (list != null) {
				list.remove(Long.valueOf(segmentId));
			}
		}
		this.switchStates.values().removeIf(v -> v == segmentId);
		long[] chunks = this.segmentChunks.remove(segmentId);
		if (chunks != null) {
			for (long chunk : chunks) {
				Set<Long> set = this.chunkIndex.get(chunk);
				if (set != null) {
					set.remove(segmentId);
					if (set.isEmpty()) {
						this.chunkIndex.remove(chunk);
					}
				}
			}
		}
		return removed;
	}

	public TrackNode node(long id) {
		return this.nodes.get(id);
	}

	public TrackSegment segment(long id) {
		return this.segments.get(id);
	}

	public Collection<TrackNode> nodes() {
		return Collections.unmodifiableCollection(this.nodes.values());
	}

	public Collection<TrackSegment> segments() {
		return Collections.unmodifiableCollection(this.segments.values());
	}

	public List<Long> segmentsAt(long nodeId) {
		List<Long> list = this.nodeSegments.get(nodeId);
		return list == null ? List.of() : Collections.unmodifiableList(list);
	}

	/** 端点(接続区間が1本だけのノード)かどうか。延長敷設の接続先になれるのはこれだけ。 */
	public boolean isDeadEnd(long nodeId) {
		return segmentsAt(nodeId).size() == 1 && linkOf(nodeId) < 0;
	}

	/** 区間segmentIdがノードnodeIdから外へ伸びる水平方向(単位ベクトル)。nodeIdが端でなければnull。 */
	public double[] outwardDirectionOf(long segmentId, long nodeId) {
		TrackSegment segment = this.segments.get(segmentId);
		if (segment == null) {
			return null;
		}
		if (segment.nodeA() == nodeId) {
			TrackPoint p = segment.sample(0.0);
			return new double[]{p.dirX(), p.dirZ()};
		}
		if (segment.nodeB() == nodeId) {
			TrackPoint p = segment.sample(segment.length());
			return new double[]{-p.dirX(), -p.dirZ()};
		}
		return null;
	}

	/**
	 * ノードnodeIdに区間fromSegmentから進入したとき(進行方向arrivalX/Z)、次に進む区間。
	 * 進行方向の前方側にある区間だけが候補で、候補が複数あれば分岐器として開通側を選ぶ。
	 * 分岐側から進入した場合、前方にあるのは基準側の1本だけなので自動的にそちらへ進む
	 * (割り出し。フェーズ2の段階では分岐器の向きによる進入制限は行わない)。
	 * 無ければ-1(車止め)。
	 */
	public long nextSegmentAt(long nodeId, long fromSegment, double arrivalX, double arrivalZ) {
		long best = -1L;
		double bestDot = 0.0;
		int candidates = 0;
		long active = segmentsAt(nodeId).size() >= 3 ? activeBranch(nodeId) : -1L;
		long linked = linkOf(nodeId);
		for (int pass = 0; pass < 2; pass++) {
			long at = pass == 0 ? nodeId : linked;
			if (at < 0) {
				continue;
			}
			for (long candidate : segmentsAt(at)) {
				if (candidate == fromSegment) {
					continue;
				}
				double[] out = outwardDirectionOf(candidate, at);
				if (out == null) {
					continue;
				}
				double dot = out[0] * arrivalX + out[1] * arrivalZ;
				if (dot <= 0.0) {
					continue;
				}
				candidates++;
				if (candidate == active) {
					return candidate;
				}
				if (dot > bestDot) {
					bestDot = dot;
					best = candidate;
				}
			}
		}
		return candidates > 0 ? best : -1L;
	}

	// ------------------------------------------------------------------ リンク・車止め・設備

	/** 2つのノードを同じ地点として連結する(転車台・遷車台の桁の端と接続部)。 */
	public void link(long a, long b) {
		unlink(a);
		unlink(b);
		this.links.put(a, b);
		this.links.put(b, a);
	}

	public void unlink(long node) {
		Long other = this.links.remove(node);
		if (other != null) {
			this.links.remove(other);
		}
	}

	/** 連結先のノード。無ければ-1。 */
	public long linkOf(long node) {
		Long other = this.links.get(node);
		return other == null ? -1L : other;
	}

	/** 端ノードの車止めの余裕(車両はこの距離だけ手前で止まる)。0で解除。 */
	public void setEndMargin(long node, double margin) {
		if (margin > 0.0) {
			this.endMargins.put(node, margin);
		} else {
			this.endMargins.remove(node);
		}
	}

	public double endMargin(long node) {
		return this.endMargins.getOrDefault(node, 0.0);
	}

	public void putFeature(TrackFeature feature) {
		TrackFeature previous = this.features.put(feature.id(), feature);
		if (previous != null && previous != feature) {
			previous.detach(this);
		}
		rebuildFeatureIndex();
		feature.apply(this);
		this.nextId = Math.max(this.nextId, feature.id() + 1);
	}

	public TrackFeature removeFeature(long id) {
		TrackFeature removed = this.features.remove(id);
		if (removed != null) {
			removed.detach(this);
			rebuildFeatureIndex();
		}
		return removed;
	}

	public TrackFeature feature(long id) {
		return this.features.get(id);
	}

	public Collection<TrackFeature> features() {
		return Collections.unmodifiableCollection(this.features.values());
	}

	/** 区間を所有する設備。無ければnull。 */
	public TrackFeature featureOfSegment(long segmentId) {
		Long owner = this.segmentOwners.get(segmentId);
		return owner == null ? null : this.features.get(owner);
	}

	/** 設備が使っているノード(延伸の接続先にできない)。 */
	public boolean isReserved(long nodeId) {
		return this.reservedNodes.contains(nodeId);
	}

	private void rebuildFeatureIndex() {
		this.segmentOwners.clear();
		this.reservedNodes.clear();
		for (TrackFeature feature : this.features.values()) {
			for (long segment : feature.ownedSegments()) {
				this.segmentOwners.put(segment, feature.id());
			}
			this.reservedNodes.addAll(feature.reservedNodes());
		}
	}

	// ------------------------------------------------------------------ 区間の分割

	/** 分割の結果。 */
	public record SplitResult(long nodeId, long firstSegment, long secondSegment, double splitS) {
	}

	/**
	 * 区間を距離sで2つに分割し、間に新しいノードを作る。形・高さは元の区間と完全に一致する。
	 * 分割された区間に載っていた車両は resolveMoved() で新しい区間に載せ替えられる。
	 */
	public SplitResult splitSegment(long segmentId, double s) {
		TrackSegment segment = this.segments.get(segmentId);
		if (segment == null) {
			return null;
		}
		double length = segment.length();
		double t = segment.plan().tAt(s);
		PlanCurve[] halves = segment.plan().split(t);
		TrackPoint p = segment.sample(s);
		TrackNode node = new TrackNode(allocateId(), p.x(), p.y(), p.z());
		TrackSegment first = new TrackSegment(allocateId(), segment.nodeA(), node.id(), halves[0],
				segment.profile().sub(0.0, length), segment.designSpeed());
		TrackSegment second = new TrackSegment(allocateId(), node.id(), segment.nodeB(), halves[1],
				segment.profile().sub(s, length), segment.designSpeed());
		Long switchState = null;
		removeSegment(segmentId);
		putNode(node);
		putSegment(first);
		putSegment(second);
		// 元の区間を開通方向にしていた分岐器は、同じ側の半分へ付け替える
		for (Map.Entry<Long, Long> e : new HashMap<>(this.switchStates).entrySet()) {
			if (e.getValue() == segmentId) {
				this.switchStates.put(e.getKey(), e.getKey() == segment.nodeA() ? first.id() : second.id());
			}
		}
		SplitResult result = new SplitResult(node.id(), first.id(), second.id(), s);
		this.splitHistory.put(segmentId, result);
		if (this.splitHistory.size() > 256) {
			this.splitHistory.remove(this.splitHistory.keySet().iterator().next());
		}
		return result;
	}

	/** 分割で無くなった区間上の位置を、分割後の区間の位置へ直す。直せなければnull。 */
	public TrackPos resolveMoved(TrackPos pos) {
		TrackPos current = pos;
		for (int i = 0; i < 8 && !this.segments.containsKey(current.segmentId()); i++) {
			SplitResult split = this.splitHistory.get(current.segmentId());
			if (split == null) {
				return null;
			}
			current = current.s() <= split.splitS()
					? new TrackPos(split.firstSegment(), current.s(), current.facing())
					: new TrackPos(split.secondSegment(), current.s() - split.splitS(), current.facing());
		}
		return this.segments.containsKey(current.segmentId()) ? current : null;
	}

	// ------------------------------------------------------------------ 分岐器

	/**
	 * 分岐器の分岐側(2本以上ある側)の区間。3本以上の区間が接続していなければ空。
	 * 基準の向きとの内積の正負で区間を2群に分け、多い側を分岐側とする。
	 */
	public List<Long> switchBranches(long nodeId) {
		List<Long> list = segmentsAt(nodeId);
		if (list.size() < 3) {
			return List.of();
		}
		double[] reference = null;
		List<Long> positive = new ArrayList<>();
		List<Long> negative = new ArrayList<>();
		for (long id : list) {
			double[] out = outwardDirectionOf(id, nodeId);
			if (out == null) {
				continue;
			}
			if (reference == null) {
				reference = out;
			}
			if (out[0] * reference[0] + out[1] * reference[1] >= 0.0) {
				positive.add(id);
			} else {
				negative.add(id);
			}
		}
		if (positive.size() >= 2 && positive.size() >= negative.size()) {
			return positive;
		}
		return negative.size() >= 2 ? negative : List.of();
	}

	public boolean isSwitch(long nodeId) {
		return !switchBranches(nodeId).isEmpty();
	}

	/** 開通している分岐側の区間(未設定なら分岐側の最初の区間)。分岐器でなければ-1。 */
	public long activeBranch(long nodeId) {
		List<Long> branches = switchBranches(nodeId);
		if (branches.isEmpty()) {
			return -1L;
		}
		Long selected = this.switchStates.get(nodeId);
		return selected != null && branches.contains(selected) ? selected : branches.get(0);
	}

	public void setSwitchState(long nodeId, long segmentId) {
		this.switchStates.put(nodeId, segmentId);
	}

	/** 開通方向を次の分岐側区間へ切り替え、新しい開通区間を返す。分岐器でなければ-1。 */
	public long cycleSwitch(long nodeId) {
		List<Long> branches = switchBranches(nodeId);
		if (branches.isEmpty()) {
			return -1L;
		}
		int index = branches.indexOf(activeBranch(nodeId));
		long next = branches.get((index + 1) % branches.size());
		this.switchStates.put(nodeId, next);
		return next;
	}

	public Map<Long, Long> switchStates() {
		return Collections.unmodifiableMap(this.switchStates);
	}

	/** 位置の近くにある分岐器ノード。無ければnull。 */
	public TrackNode findSwitchNear(double x, double y, double z, double radius) {
		TrackNode best = null;
		double bestDist = radius * radius;
		for (TrackNode node : this.nodes.values()) {
			double dx = node.x() - x;
			double dy = node.y() - y;
			double dz = node.z() - z;
			double d = dx * dx + dy * dy + dz * dz;
			if (d <= bestDist && isSwitch(node.id())) {
				bestDist = d;
				best = node;
			}
		}
		return best;
	}

	/**
	 * 端点ノードから線路が外へ伸びる向き(水平単位ベクトル)。延長敷設で接線を連続させるのに使う。
	 * 端点でなければnull。
	 */
	public double[] outwardDirection(long nodeId) {
		List<Long> list = segmentsAt(nodeId);
		if (list.size() != 1) {
			return null;
		}
		TrackSegment segment = this.segments.get(list.get(0));
		if (segment == null) {
			return null;
		}
		if (segment.nodeB() == nodeId) {
			TrackPoint p = segment.sample(segment.length());
			return new double[]{p.dirX(), p.dirZ()};
		}
		TrackPoint p = segment.sample(0.0);
		return new double[]{-p.dirX(), -p.dirZ()};
	}

	/** 位置(x,y,z)の近くにある端点ノード。無ければnull。 */
	public TrackNode findDeadEndNear(double x, double y, double z, double horizontalRadius, double verticalRadius) {
		TrackNode best = null;
		double bestDist = horizontalRadius * horizontalRadius;
		for (TrackNode node : this.nodes.values()) {
			if (Math.abs(node.y() - y) > verticalRadius || !isDeadEnd(node.id()) || isReserved(node.id())) {
				continue;
			}
			double dx = node.x() - x;
			double dz = node.z() - z;
			double d = dx * dx + dz * dz;
			if (d <= bestDist) {
				bestDist = d;
				best = node;
			}
		}
		return best;
	}

	/** 指定チャンク範囲を通る区間ID。 */
	public Set<Long> segmentsNearChunks(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
		Set<Long> result = new HashSet<>();
		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				Set<Long> set = this.chunkIndex.get(chunkKey(cx, cz));
				if (set != null) {
					result.addAll(set);
				}
			}
		}
		return result;
	}

	public Set<Long> segmentsNear(double x, double z, double radius) {
		return segmentsNearChunks((int) Math.floor((x - radius) / 16.0), (int) Math.floor((z - radius) / 16.0),
				(int) Math.floor((x + radius) / 16.0), (int) Math.floor((z + radius) / 16.0));
	}

	/** 位置に最も近い線路上の点。maxDistance以内に無ければnull。 */
	public Nearest nearestPoint(double x, double y, double z, double maxDistance) {
		Nearest best = null;
		double bestDistSq = maxDistance * maxDistance;
		for (long segmentId : segmentsNear(x, z, maxDistance)) {
			TrackSegment segment = this.segments.get(segmentId);
			if (segment == null) {
				continue;
			}
			double length = segment.length();
			int steps = Math.max(1, (int) Math.ceil(length / 0.25));
			for (int i = 0; i <= steps; i++) {
				double s = length * i / steps;
				TrackPoint p = segment.sample(s);
				double dx = p.x() - x;
				double dy = p.y() - y;
				double dz = p.z() - z;
				double d = dx * dx + dy * dy + dz * dz;
				if (d < bestDistSq) {
					bestDistSq = d;
					best = new Nearest(segmentId, s, p, Math.sqrt(d));
				}
			}
		}
		return best;
	}

	public record Nearest(long segmentId, double s, TrackPoint point, double distance) {
	}

	/**
	 * 線路に沿って移動した結果。
	 *
	 * @param blocked   車止め(接続先の無い端点)に達して止まったか
	 * @param overshoot 車止めを越えて進めなかった残り距離(ボギーの外挿表示用)
	 */
	public record Walk(long segmentId, double s, int facing, boolean blocked, double overshoot) {
		public TrackPos pos() {
			return new TrackPos(this.segmentId, this.s, this.facing);
		}
	}

	/**
	 * 車両基準の距離distance(正で車両の前方)だけ線路に沿って進む。
	 * facingは「車両前方が区間のA→B方向なら+1」。区間の向きが逆につながっていれば
	 * 乗り移り時にfacingが反転する。
	 */
	public Walk walk(TrackPos from, double distance) {
		TrackSegment segment = this.segments.get(from.segmentId());
		if (segment == null) {
			return new Walk(from.segmentId(), from.s(), from.facing(), true, Math.abs(distance));
		}
		int motionSign = distance >= 0.0 ? 1 : -1;
		int paramDir = motionSign * from.facing();
		double left = Math.abs(distance);
		double s = from.s();
		int facing = from.facing();
		for (int hop = 0; hop < 256; hop++) {
			double length = segment.length();
			double room = paramDir > 0 ? length - s : s;
			long node = paramDir > 0 ? segment.nodeB() : segment.nodeA();
			// 車止めのある端は、端から余裕(margin)を残した位置までしか進めない
			double margin = this.endMargins.getOrDefault(node, 0.0);
			double limit = margin > 0.0 ? Math.max(0.0, room - margin) : room;
			if (left <= limit) {
				return new Walk(segment.id(), s + paramDir * left, facing, false, 0.0);
			}
			if (margin > 0.0) {
				return new Walk(segment.id(), s + paramDir * limit, facing, true, left - limit);
			}
			TrackPoint end = segment.sample(paramDir > 0 ? length : 0.0);
			long nextId = nextSegmentAt(node, segment.id(), end.dirX() * paramDir, end.dirZ() * paramDir);
			TrackSegment next = nextId >= 0 ? this.segments.get(nextId) : null;
			if (next == null) {
				return new Walk(segment.id(), paramDir > 0 ? length : 0.0, facing, true, left - room);
			}
			left -= room;
			long entry = next.nodeA() == node || next.nodeB() == node ? node : linkOf(node);
			if (next.nodeA() == entry) {
				s = 0.0;
				paramDir = 1;
			} else {
				s = next.length();
				paramDir = -1;
			}
			facing = paramDir * motionSign;
			segment = next;
		}
		return new Walk(segment.id(), s, facing, true, left);
	}

	/** 線路位置の点。区間が無ければnull。 */
	public TrackPoint pointAt(TrackPos pos) {
		TrackSegment segment = this.segments.get(pos.segmentId());
		return segment == null ? null : sample(segment, pos.s());
	}

	// ------------------------------------------------------------------ カントの平滑化

	private static final double CANT_STEP = 0.5;

	/**
	 * カントの逓減長。区間の生のカント(曲率から求めた均衡カント、上限6°)は曲線の入口で
	 * すぐ上限に達するため、線路に沿って前後(逓減長の半分ずつ)の移動平均を取る。
	 * 一定のカントの曲線と直線のつなぎ目は、これで逓減長をかけた直線的な変化になる。
	 */
	public void setCantTransitionLength(double length) {
		double value = Math.max(0.0, length);
		if (value != this.cantTransitionLength) {
			this.cantTransitionLength = value;
			this.cantTables.clear();
		}
	}

	/** 平滑化済みのカントを使った線路上の点。区間の描画・車両の傾きはこちらを使う。 */
	public TrackPoint sample(TrackSegment segment, double s) {
		TrackPoint raw = segment.sample(s);
		if (!this.segments.containsKey(segment.id()) || this.cantTransitionLength <= CANT_STEP) {
			return raw;
		}
		float[] table = this.cantTables.computeIfAbsent(segment.id(), id -> buildCantTable(segment));
		double f = Math.max(0.0, Math.min(segment.length(), s)) / CANT_STEP;
		int i = Math.min(table.length - 1, (int) Math.floor(f));
		int j = Math.min(table.length - 1, i + 1);
		double t = f - i;
		double cant = table[i] + (table[j] - table[i]) * t;
		return new TrackPoint(raw.x(), raw.y(), raw.z(), raw.dirX(), raw.dirZ(), raw.grade(), cant);
	}

	private float[] buildCantTable(TrackSegment segment) {
		double length = segment.length();
		int n = (int) Math.ceil(length / CANT_STEP) + 1;
		int k = (int) Math.ceil(this.cantTransitionLength / 2.0 / CANT_STEP);
		int m = n + 2 * k;
		double[] prefix = new double[m + 1];
		for (int j = 0; j < m; j++) {
			double s = (j - k) * CANT_STEP;
			prefix[j + 1] = prefix[j] + rawCantAlong(segment, length, s);
		}
		float[] table = new float[n];
		for (int i = 0; i < n; i++) {
			// 区間上の点iは拡張配列の添字i+k。前後kずつの平均
			table[i] = (float) ((prefix[i + 2 * k + 1] - prefix[i]) / (2 * k + 1));
		}
		return table;
	}

	/** 区間の始点からの距離s(負や区間長超えは隣の区間へたどる)での生のカント。この区間の向き基準。 */
	private double rawCantAlong(TrackSegment segment, double length, double s) {
		if (s >= 0.0 && s <= length) {
			return segment.sample(s).cantRad();
		}
		double from = s < 0.0 ? 0.0 : length;
		Walk walk = walk(new TrackPos(segment.id(), from, 1), s - from);
		if (walk.blocked()) {
			return 0.0;
		}
		TrackSegment other = this.segments.get(walk.segmentId());
		return other == null ? 0.0 : other.sample(walk.s()).cantRad() * walk.facing();
	}

	/** つながりが変わったノード周辺(2区間先まで)の平滑化カントを作り直させる。 */
	private void invalidateCantAround(long... nodeIds) {
		java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>();
		Set<Long> seen = new HashSet<>();
		for (long node : nodeIds) {
			queue.add(new long[]{node, 0});
		}
		while (!queue.isEmpty()) {
			long[] item = queue.poll();
			if (!seen.add(item[0])) {
				continue;
			}
			for (long segmentId : segmentsAt(item[0])) {
				this.cantTables.remove(segmentId);
				TrackSegment seg = this.segments.get(segmentId);
				if (seg != null && item[1] < 2) {
					queue.add(new long[]{seg.otherNode(item[0]), item[1] + 1});
				}
			}
		}
	}

	/** 区間が通るチャンク(線形の両側2ブロックの余裕を含む)。 */
	private static long[] coveredChunks(TrackSegment segment) {
		Set<Long> set = new HashSet<>();
		double length = segment.length();
		int steps = Math.max(1, (int) Math.ceil(length / 2.0));
		for (int i = 0; i <= steps; i++) {
			TrackPoint p = segment.sample(length * i / steps);
			for (int ox = -1; ox <= 1; ox++) {
				for (int oz = -1; oz <= 1; oz++) {
					set.add(chunkKey((int) Math.floor((p.x() + ox * 2.0) / 16.0), (int) Math.floor((p.z() + oz * 2.0) / 16.0)));
				}
			}
		}
		long[] result = new long[set.size()];
		int i = 0;
		for (long key : set) {
			result[i++] = key;
		}
		return result;
	}

	public static long chunkKey(int chunkX, int chunkZ) {
		return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
	}
}
