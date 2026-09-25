package com.example.railwayvehicleaddon.track.feature;

import com.example.railwayvehicleaddon.track.TrackNetwork;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/**
 * 線路に付属する設備(転車台・遷車台・車止めなど)。設備は線路グラフの区間・ノードを所有し、
 * apply()で線路グラフへの特殊な効果(ノードの連結、車止めの停止位置など)を反映する。
 *
 * <p>新しい設備を追加するには、このインターフェースを実装してFeatureTypes.register()で
 * 保存・同期用のコーデックを登録し、配置用の測量モード(SurveyMode)を作る。
 */
public interface TrackFeature {

	long id();

	/** FeatureTypesに登録した種類名。 */
	String type();

	/** この設備が所有する区間(設備を撤去すると一緒に撤去される)。 */
	Set<Long> ownedSegments();

	/** この設備が所有するノード。延伸の接続先として選べなくなる。 */
	Set<Long> reservedNodes();

	/** 線路グラフへ効果を反映する(追加時・読み込み時・状態変化時)。 */
	void apply(TrackNetwork network);

	/** 線路グラフから効果を取り除く(撤去時)。 */
	void detach(TrackNetwork network);

	/** サーバー側で毎tick呼ばれる。形や状態が変わってクライアントへ送る必要があればtrue。 */
	default boolean tick(TrackNetwork network) {
		return false;
	}

	/** 表示・選択の中心。 */
	Vec3d center();

	/** 選択判定に使う半径(水平)。 */
	double radius();
}
