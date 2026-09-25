package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackNode;
import com.example.railwayvehicleaddon.track.TrackPoint;
import com.example.railwayvehicleaddon.track.TrackSegment;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * 既存線路上の点(ノードまたは区間の途中)を、配置計画で使える参照に解決する。
 * 区間の途中なら分割(LayoutPlan.Split)を計画に追加する。端に近すぎる点は端のノードを使う。
 */
public final class Anchors {
	/** 区間の端からこれより近い点は、分割せずに端のノードを使う */
	private static final double MIN_SPLIT_DISTANCE = 1.5;

	private Anchors() {
	}

	/**
	 * @param ref     参照
	 * @param pos     位置
	 * @param tangent 線路の向き(水平単位ベクトル。向きの正負は不定なので使う側で揃える)
	 * @param deadEnd 端点か(延伸・分岐元の判定用)
	 */
	public record Anchor(NodeRef ref, Vec3d pos, double[] tangent, boolean deadEnd, long segmentId) {
		/** 向きを基準ベクトル(dx, dz)と同じ側に揃えた接線。 */
		public double[] tangentToward(double dx, double dz) {
			return this.tangent[0] * dx + this.tangent[1] * dz >= 0.0 ? this.tangent
					: new double[]{-this.tangent[0], -this.tangent[1]};
		}
	}

	/** 解決できなければnull。splitsには必要な分割が追加される。 */
	public static Anchor resolve(TrackNetwork network, SurveyPoint point, List<LayoutPlan.Split> splits) {
		if (point.snapped()) {
			TrackNode node = network.node(point.nodeId());
			if (node == null || network.segmentsAt(node.id()).isEmpty()) {
				return null;
			}
			return nodeAnchor(network, node);
		}
		if (!point.onTrack()) {
			return null;
		}
		TrackSegment segment = network.segment(point.segmentId());
		if (segment == null) {
			return null;
		}
		double length = segment.length();
		double s = Math.max(0.0, Math.min(length, point.s()));
		if (s < MIN_SPLIT_DISTANCE || s > length - MIN_SPLIT_DISTANCE) {
			TrackNode node = network.node(s < length / 2.0 ? segment.nodeA() : segment.nodeB());
			return node == null ? null : nodeAnchor(network, node);
		}
		for (LayoutPlan.Split existing : splits) {
			if (existing.segmentId() == segment.id()) {
				return null;
			}
		}
		splits.add(new LayoutPlan.Split(segment.id(), s));
		TrackPoint p = segment.sample(s);
		return new Anchor(NodeRef.split(splits.size() - 1), new Vec3d(p.x(), p.y(), p.z()),
				new double[]{p.dirX(), p.dirZ()}, false, segment.id());
	}

	private static Anchor nodeAnchor(TrackNetwork network, TrackNode node) {
		boolean deadEnd = network.isDeadEnd(node.id());
		double[] tangent = deadEnd ? network.outwardDirection(node.id())
				: network.outwardDirectionOf(network.segmentsAt(node.id()).get(0), node.id());
		if (tangent == null) {
			return null;
		}
		return new Anchor(NodeRef.existing(node.id()), new Vec3d(node.x(), node.y(), node.z()), tangent, deadEnd, -1L);
	}
}
