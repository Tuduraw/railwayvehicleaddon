package com.example.railwayvehicleaddon.track.feature;

import com.example.railwayvehicleaddon.track.HeightProfile;
import com.example.railwayvehicleaddon.track.PlanCurve;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackNode;
import com.example.railwayvehicleaddon.track.TrackSegment;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 可動桁を持つ設備(転車台・遷車台)の共通部分。
 *
 * <p>桁は通常の線路区間(deckSegment、両端ノードdeckNodeA/B)で、位置はparam(転車台は角度、
 * 遷車台は横方向の移動量)で決まる。停止位置(Stop)ごとに、桁の両端とつながる接続ノードを持ち、
 * 桁が停止位置にそろっている間だけ、桁の端ノードと接続ノードをTrackNetworkのリンクで結ぶ。
 * 移動中はリンクが無いので、桁の上の車両も周りの線路の車両も桁の端で止まる。
 * 桁の区間IDは移動しても変わらないため、桁に載った車両は桁と一緒に動く。
 */
public abstract class MovingDeckFeature implements TrackFeature {

	/**
	 * 停止位置。
	 *
	 * @param param 停止位置の桁の位置(角度または移動量)
	 * @param connA 桁のA端がつながるノード(無ければ-1)
	 * @param connB 桁のB端がつながるノード(無ければ-1)
	 */
	public record Stop(double param, long connA, long connB) {
		public static final Codec<Stop> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.DOUBLE.fieldOf("param").forGetter(Stop::param),
				Codec.LONG.fieldOf("a").forGetter(Stop::connA),
				Codec.LONG.fieldOf("b").forGetter(Stop::connB)
		).apply(instance, Stop::new));
	}

	protected final long id;
	protected final long deckSegment;
	protected final long deckNodeA;
	protected final long deckNodeB;
	protected final List<Stop> stops;
	/** 接続用に作った短い線路(撤去時に一緒に消す) */
	protected final List<Long> stubSegments;
	protected final double y;
	protected double param;
	protected int target;

	protected MovingDeckFeature(long id, long deckSegment, long deckNodeA, long deckNodeB, List<Stop> stops,
								List<Long> stubSegments, double y, double param, int target) {
		this.id = id;
		this.deckSegment = deckSegment;
		this.deckNodeA = deckNodeA;
		this.deckNodeB = deckNodeB;
		this.stops = List.copyOf(stops);
		this.stubSegments = List.copyOf(stubSegments);
		this.y = y;
		this.param = param;
		this.target = Math.max(0, Math.min(stops.size() - 1, target));
	}

	/** 桁のA端の位置(param時)。 */
	public abstract Vec3d endA(double param);

	/** 桁のB端の位置(param時)。 */
	public abstract Vec3d endB(double param);

	/** 描画用: 2つの位置の間をtで補間する(転車台は角度として近い向きに補間)。 */
	public double interpolate(double from, double to, double t) {
		return from - difference(from, to) * t;
	}

	/** 1tickの移動量。 */
	protected abstract double speed();

	/** 現在位置から目標へspeedだけ近づけた位置。 */
	protected double approach(double current, double goal, double speed) {
		double delta = goal - current;
		return Math.abs(delta) <= speed ? goal : current + Math.copySign(speed, delta);
	}

	/** 2つの位置の差(転車台は角度の差)。 */
	protected double difference(double a, double b) {
		return a - b;
	}

	@Override
	public long id() {
		return this.id;
	}

	public long deckSegment() {
		return this.deckSegment;
	}

	public long deckNodeA() {
		return this.deckNodeA;
	}

	public long deckNodeB() {
		return this.deckNodeB;
	}

	public List<Stop> stops() {
		return this.stops;
	}

	public List<Long> stubSegments() {
		return this.stubSegments;
	}

	public double y() {
		return this.y;
	}

	public double param() {
		return this.param;
	}

	public int target() {
		return this.target;
	}

	public boolean isMoving() {
		return !this.stops.isEmpty() && Math.abs(difference(this.param, this.stops.get(this.target).param())) > 1.0e-6;
	}

	@Override
	public Set<Long> ownedSegments() {
		Set<Long> set = new HashSet<>(this.stubSegments);
		set.add(this.deckSegment);
		return set;
	}

	@Override
	public Set<Long> reservedNodes() {
		Set<Long> set = new HashSet<>();
		set.add(this.deckNodeA);
		set.add(this.deckNodeB);
		for (Stop stop : this.stops) {
			if (stop.connA() >= 0) {
				set.add(stop.connA());
			}
			if (stop.connB() >= 0) {
				set.add(stop.connB());
			}
		}
		return set;
	}

	/** 次(stepが負なら前)の停止位置へ動かし始める。 */
	public void selectNext(int step) {
		if (this.stops.isEmpty()) {
			return;
		}
		int size = this.stops.size();
		this.target = ((this.target + step) % size + size) % size;
	}

	/** 指定した停止位置(0始まり、範囲外は丸める)へ動かし始める。 */
	public void selectStop(int index) {
		if (!this.stops.isEmpty()) {
			this.target = Math.max(0, Math.min(this.stops.size() - 1, index));
		}
	}

	@Override
	public boolean tick(TrackNetwork network) {
		if (!isMoving()) {
			return false;
		}
		this.param = approach(this.param, this.stops.get(this.target).param(), speed());
		apply(network);
		return true;
	}

	/** 桁の区間・端ノードを現在位置に合わせ、停止位置にそろっていれば接続ノードとリンクする。 */
	@Override
	public void apply(TrackNetwork network) {
		Vec3d a = endA(this.param);
		Vec3d b = endB(this.param);
		network.putNode(new TrackNode(this.deckNodeA, a.x, a.y, a.z));
		network.putNode(new TrackNode(this.deckNodeB, b.x, b.y, b.z));
		network.putSegment(new TrackSegment(this.deckSegment, this.deckNodeA, this.deckNodeB,
				PlanCurve.straight(a.x, a.z, b.x, b.z), HeightProfile.flat(this.y), 0f));
		network.unlink(this.deckNodeA);
		network.unlink(this.deckNodeB);
		if (!isMoving() && !this.stops.isEmpty()) {
			Stop stop = this.stops.get(this.target);
			if (stop.connA() >= 0) {
				network.link(this.deckNodeA, stop.connA());
			}
			if (stop.connB() >= 0) {
				network.link(this.deckNodeB, stop.connB());
			}
		}
	}

	@Override
	public void detach(TrackNetwork network) {
		network.unlink(this.deckNodeA);
		network.unlink(this.deckNodeB);
	}
}
