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
	/**
	 * 転車台・遷車台の桁の描画用の位置(設備ID → [前tick, 今tick, 受信した最新])。桁はtickごとに
	 * 位置が届くので、車両と同じくtick間を補間して滑らかに描く。
	 */
	private static final java.util.Map<Long, double[]> DECK_PARAMS = new java.util.HashMap<>();

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
		// 開通方向が変わった分岐器の区間は、分岐部のレール表示を作り直す
		for (long node : payload.switches().keySet()) {
			for (long id : NETWORK.segmentsAt(node)) {
				TrackRenderer.invalidate(id);
			}
		}
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
			DECK_PARAMS.clear();
		}
		for (long id : payload.removedIds()) {
			NETWORK.removeFeature(id);
			FeatureRenderer.invalidate(id);
			DECK_PARAMS.remove(id);
		}
		for (TrackFeature feature : payload.features()) {
			boolean known = NETWORK.feature(feature.id()) != null;
			NETWORK.putFeature(feature);
			if (feature instanceof MovingDeckFeature deck) {
				double[] p = DECK_PARAMS.computeIfAbsent(deck.id(), k -> new double[]{deck.param(), deck.param(), deck.param()});
				p[2] = deck.param();
				// 桁が動くだけならピットの形は変わらないので作り直さない
				if (!known) {
					FeatureRenderer.invalidate(feature.id());
				}
			} else {
				FeatureRenderer.invalidate(feature.id());
			}
		}
		revision++;
	}

	/** 毎tick: 桁の描画用の位置を1tick進める。 */
	public static void tickDecks() {
		for (double[] p : DECK_PARAMS.values()) {
			p[0] = p[1];
			p[1] = p[2];
		}
	}

	/** 描画用の桁の位置(tick間を補間)。 */
	public static double deckRenderParam(MovingDeckFeature deck, float tickDelta) {
		double[] p = DECK_PARAMS.get(deck.id());
		return p == null ? deck.param() : deck.interpolate(p[0], p[1], tickDelta);
	}

	public static void clear() {
		NETWORK.clear();
		TrackRenderer.invalidateAll();
		FeatureRenderer.invalidateAll();
		DECK_PARAMS.clear();
		config = RailwayConfig.Values.DEFAULT;
		revision++;
	}
}
