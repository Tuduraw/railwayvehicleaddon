package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.network.FeatureSyncPayload;
import com.example.railwayvehicleaddon.network.TrackRemovePayload;
import com.example.railwayvehicleaddon.track.feature.MovingDeckFeature;
import com.example.railwayvehicleaddon.track.feature.TrackFeature;
import com.example.railwayvehicleaddon.network.TrackSyncPayload;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackNode;
import com.example.railwayvehicleaddon.track.TrackSegment;

/** クライアントが持つ、現在のディメンションの線路データの写しとサーバー設定。 */
public final class ClientTrackData {
	private static final TrackNetwork NETWORK = new TrackNetwork();
	private static RailwayConfig.Values config = RailwayConfig.Values.DEFAULT;
	/** 線路データが変わるたびに増える。プレビューや描画キャッシュの更新判定に使う */
	private static int revision;

	private ClientTrackData() {
	}

	public static TrackNetwork network() {
		return NETWORK;
	}

	public static RailwayConfig.Values config() {
		return config;
	}

	public static int revision() {
		return revision;
	}

	public static void apply(TrackSyncPayload payload) {
		if (payload.reset()) {
			NETWORK.clear();
			TrackRenderer.invalidateAll();
		}
		config = payload.config();
		NETWORK.setCantTransitionLength(config.cantTransitionLength());
		for (TrackNode node : payload.nodes()) {
			NETWORK.putNode(node);
		}
		for (TrackSegment segment : payload.segments()) {
			NETWORK.putSegment(segment);
			invalidateAround(segment);
		}
		payload.switches().forEach(NETWORK::setSwitchState);
		revision++;
	}

	public static void apply(TrackRemovePayload payload) {
		for (long id : payload.segmentIds()) {
			TrackSegment segment = NETWORK.segment(id);
			NETWORK.removeSegment(id);
			TrackRenderer.invalidate(id);
			if (segment != null) {
				invalidateAround(segment);
			}
		}
		for (long id : payload.nodeIds()) {
			NETWORK.removeNode(id);
		}
		revision++;
	}

	/** 区間と、その両端につながる区間の描画を作り直させる(カントの平滑化が隣の区間に及ぶため)。 */
	private static void invalidateAround(TrackSegment segment) {
		TrackRenderer.invalidate(segment.id());
		for (long node : new long[]{segment.nodeA(), segment.nodeB()}) {
			for (long id : NETWORK.segmentsAt(node)) {
				TrackRenderer.invalidate(id);
				TrackSegment neighbor = NETWORK.segment(id);
				if (neighbor != null) {
					for (long far : NETWORK.segmentsAt(neighbor.otherNode(node))) {
						TrackRenderer.invalidate(far);
					}
				}
			}
		}
	}

	public static void apply(FeatureSyncPayload payload) {
		if (payload.reset()) {
			for (TrackFeature feature : new java.util.ArrayList<>(NETWORK.features())) {
				NETWORK.removeFeature(feature.id());
			}
			FeatureRenderer.invalidateAll();
		}
		for (long id : payload.removedIds()) {
			NETWORK.removeFeature(id);
			FeatureRenderer.invalidate(id);
		}
		for (TrackFeature feature : payload.features()) {
			NETWORK.putFeature(feature);
			FeatureRenderer.invalidate(feature.id());
			if (feature instanceof MovingDeckFeature deck) {
				TrackRenderer.invalidate(deck.deckSegment());
			}
		}
		revision++;
	}

	public static void clear() {
		NETWORK.clear();
		TrackRenderer.invalidateAll();
		FeatureRenderer.invalidateAll();
		config = RailwayConfig.Values.DEFAULT;
		revision++;
	}
}
