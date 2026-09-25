package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.TrackNetwork;

/**
 * 撤去モード。点は使わず、狙った区間を右クリックで選択し、確定キーで選択した区間をまとめて撤去する。
 * 選択・撤去の操作はクライアントのSurveySessionが直接扱う。
 */
public final class RemoveMode implements SurveyMode {
	@Override
	public String id() {
		return "remove";
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
