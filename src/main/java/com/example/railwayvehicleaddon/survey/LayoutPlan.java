package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.RoutePlanner;
import com.example.railwayvehicleaddon.track.TrackSegment;
import com.example.railwayvehicleaddon.track.feature.TrackFeature;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * モードが作る配置計画。サーバーはこれを検証(問題点・建築限界)して、そのまま線路グラフへ登録する。
 * モードを追加するときは、この形に落とし込めば敷設・撤去・同期の処理を書かずに済む。
 *
 * @param newNodes       新しく作るノードの位置(NodeRef.created(i) で参照)
 * @param splits         分割する既存区間(NodeRef.split(i) で分割点のノードを参照)。同じ区間は1回まで
 * @param edges          作る区間
 * @param switchDefaults 分岐器の初期の開通方向(ノードと、開通させる区間のedges内の添字)
 * @param features       作る設備
 * @param issues         問題点。1つでもあれば配置しない
 * @param totalLength    延長(表示用)
 */
public record LayoutPlan(List<Vec3d> newNodes, List<Split> splits, List<Edge> edges, List<SwitchDefault> switchDefaults,
						 List<FeatureSpec> features, List<RoutePlanner.Issue> issues, double totalLength) {

	public record Edge(NodeRef a, NodeRef b, RoutePlanner.PlannedSegment geometry) {
	}

	public record SwitchDefault(NodeRef node, int edgeIndex) {
	}

	/** 既存区間segmentIdを距離sで分割する。 */
	public record Split(long segmentId, double s) {
	}

	/** 配置確定時に、計画中の参照を実際のIDへ解決する。 */
	public interface IdResolver {
		long node(NodeRef ref);

		long segment(int edgeIndex);
	}

	/** 作る設備。計画の区間・ノードを参照して、確定時に設備を組み立てる。 */
	public interface FeatureSpec {
		TrackFeature create(long featureId, IdResolver ids);

		/** 区間の建築限界とは別に空けておく範囲(転車台のピットなど)。 */
		default Set<BlockPos> clearance(RailwayConfig.Values config) {
			return Set.of();
		}

		/** プレビューで描く線(始点・終点の組)。 */
		default List<Vec3d[]> outline() {
			return List.of();
		}
	}

	public static LayoutPlan of(List<Vec3d> newNodes, List<Edge> edges, List<SwitchDefault> switchDefaults,
								List<RoutePlanner.Issue> issues, double totalLength) {
		return new LayoutPlan(newNodes, List.of(), edges, switchDefaults, List.of(), issues, totalLength);
	}

	public static LayoutPlan empty() {
		return new LayoutPlan(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0.0);
	}

	public static LayoutPlan failed(RoutePlanner.Issue issue) {
		return new LayoutPlan(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(issue), 0.0);
	}

	public boolean isValid() {
		return this.issues.isEmpty() && (!this.edges.isEmpty() || !this.features.isEmpty());
	}

	/** プレビュー・ブロック判定用の仮区間。 */
	public List<TrackSegment> previewSegments(float designSpeed) {
		List<TrackSegment> list = new ArrayList<>(this.edges.size());
		for (Edge edge : this.edges) {
			list.add(edge.geometry().asSegment(designSpeed));
		}
		return list;
	}
}
