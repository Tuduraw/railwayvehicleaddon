package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.track.TrackManager;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackPoint;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 地上子(信号機・停止位置目標・列車番号検知器)の、線路上の位置への連結。
 *
 * <p>設置すると、ブロックの近く({@link #LINK_RANGE}以内)の線路の最も近い点に連結する。
 * 効く向きはブロックの向き(FACING、設置したプレイヤーの方を向く)から決める: 正面に向かって
 * 進んでくる列車(設置したプレイヤーが見ていた向きに進む列車)に効く。区間が分割・撤去されたら、
 * 同じ方法で連結し直す。
 */
public final class WaysideLink {
	/** 連結する線路を探す距離(ブロックの中心から) */
	public static final double LINK_RANGE = 6.0;
	/** 連結し直しを確かめる間隔(tick) */
	private static final int CHECK_INTERVAL = 20;

	private long segmentId = -1L;
	private double s;
	private int dir = 1;
	private int checkTimer;

	public long segmentId() {
		return this.segmentId;
	}

	public double s() {
		return this.s;
	}

	public int dir() {
		return this.dir;
	}

	public boolean linked() {
		return this.segmentId >= 0;
	}

	/**
	 * 近くの線路へ連結する。連結できればtrue。
	 *
	 * @param facing ブロックの正面の向き(列車が来る側)
	 */
	public boolean link(ServerWorld world, BlockPos pos, Direction facing) {
		TrackNetwork network = TrackManager.network(world);
		Vec3d center = Vec3d.ofCenter(pos);
		TrackNetwork.Nearest nearest = network.nearestPoint(center.x, center.y, center.z, LINK_RANGE);
		if (nearest == null || network.featureOfSegment(nearest.segmentId()) != null) {
			this.segmentId = -1L;
			return false;
		}
		TrackPoint p = nearest.point();
		// 列車は正面(FACING)に向かって進んでくる = 進行方向はFACINGの逆
		double tx = -facing.getOffsetX();
		double tz = -facing.getOffsetZ();
		this.segmentId = nearest.segmentId();
		this.s = nearest.s();
		this.dir = p.dirX() * tx + p.dirZ() * tz >= 0.0 ? 1 : -1;
		return true;
	}

	/** 線路の向きとブロックの向きが直交に近い(効く向きがはっきりしない)か。 */
	public boolean ambiguous(ServerWorld world, Direction facing) {
		TrackNetwork network = TrackManager.network(world);
		if (!linked() || network.segment(this.segmentId) == null) {
			return false;
		}
		TrackPoint p = network.segment(this.segmentId).sample(this.s);
		return Math.abs(p.dirX() * facing.getOffsetX() + p.dirZ() * facing.getOffsetZ()) < 0.5;
	}

	/**
	 * 一定間隔で、連結先の区間が残っているか確かめ、無くなっていれば連結し直す。
	 * 連結先が変わったらtrue。
	 */
	public boolean check(ServerWorld world, BlockPos pos, Direction facing) {
		if (++this.checkTimer < CHECK_INTERVAL) {
			return false;
		}
		this.checkTimer = 0;
		TrackNetwork network = TrackManager.network(world);
		if (this.segmentId >= 0 && network.segment(this.segmentId) != null) {
			return false;
		}
		long before = this.segmentId;
		link(world, pos, facing);
		return before != this.segmentId;
	}

	/** 連結先の説明(設置時・右クリック時のメッセージ)。 */
	public Text describe(ServerWorld world) {
		TrackNetwork network = TrackManager.network(world);
		if (!linked() || network.segment(this.segmentId) == null) {
			return Text.translatable("message.railwayvehicleaddon.wayside.no_track");
		}
		TrackPoint p = network.segment(this.segmentId).sample(this.s);
		return Text.translatable("message.railwayvehicleaddon.wayside.linked",
				(int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
	}

	public void read(ReadView view) {
		this.segmentId = view.getLong("TrackSegment", -1L);
		this.s = view.getDouble("TrackS", 0.0);
		this.dir = view.getInt("TrackDir", 1) >= 0 ? 1 : -1;
	}

	public void write(WriteView view) {
		view.putLong("TrackSegment", this.segmentId);
		view.putDouble("TrackS", this.s);
		view.putInt("TrackDir", this.dir);
	}
}
