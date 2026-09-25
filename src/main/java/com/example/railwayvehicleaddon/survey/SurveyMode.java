package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.TrackNetwork;

/**
 * 測量ツールのモード。クライアントのプレビューとサーバーの確定処理で同じplan()が使われる。
 *
 * <p>新しい設置方法を追加するには、このインターフェースを実装してSurveyModes.register()で
 * 登録する。点の追加・移動・削除・挿入、プレビュー表示、建築限界の判定とブロック撤去、
 * 線路グラフへの登録と同期は共通処理が行う。
 */
public interface SurveyMode {

	/** 通信・翻訳キーに使うID(message.railwayvehicleaddon.mode.<id>)。 */
	String id();

	/** 確定に必要な最少の点数。 */
	int minPoints();

	/** 既存の2点の間に点を挿入できるか(点の並びが1本の経路を表すモードのみ)。 */
	default boolean supportsInsert() {
		return false;
	}

	/** 最初の点をもう一度指定して環状に閉じられるか。 */
	default boolean supportsClose() {
		return false;
	}

	/** index番目(追加しようとしている位置)の点を既存線路の端に接続してよいか。 */
	default boolean allowsSnap(int index, int currentCount) {
		return true;
	}

	/** index番目の点に、線路の途中(区間の途中や、端以外のノード)を指定できるか。 */
	default boolean allowsTrackSnap(int index) {
		return false;
	}

	/** param([ ] キー)の既定値・範囲。paramを使わないモードは既定のまま。 */
	default int defaultParam() {
		return 0;
	}

	default int minParam() {
		return 0;
	}

	default int maxParam() {
		return 0;
	}

	/** paramの表示用の値(転車台の角度など)。 */
	default String paramLabel(int param) {
		return Integer.toString(param);
	}

	/** 線路を敷設するモードか(falseなら撤去など、確定時の処理をクライアント側の専用処理に任せる)。 */
	default boolean placesTrack() {
		return true;
	}

	LayoutPlan plan(TrackNetwork network, SurveyInput input, RailwayConfig.Values config);
}
