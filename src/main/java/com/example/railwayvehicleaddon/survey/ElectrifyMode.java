package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.TrackNetwork;

/**
 * 電化モード。敷設済みの区間を右クリックで選び、確定キーで電化(架線を張る)・架線の撤去を切り替える。
 * 選んだ区間に1つでも未電化があれば全部を電化し、すべて電化済みなら全部の架線を撤去する。
 * 選択の操作は撤去モードと同じで、クライアントのSurveySessionが直接扱う。
 */
public final class ElectrifyMode implements SurveyMode {
	@Override
	public String id() {
		return "electrify";
	}

	@Override
	public int minPoints() {
		return 0;
	}

	@Override
	public boolean placesTrack() {
		return false;
	}

	@Override
	public LayoutPlan plan(TrackNetwork network, SurveyInput input, RailwayConfig.Values config) {
		return LayoutPlan.empty();
	}
}
