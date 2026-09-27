package com.example.railwayvehicleaddon.survey;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.TrackNetwork;

/**
 * 連結モード。線路装置(転てつてこ・転てつ機・操作盤・制御器・在線検知器)を右クリックで選び、
 * 続けて対象(分岐器・転車台/遷車台・線路区間)を右クリックして連結先を付け替える。
 * 操作はクライアントのSurveySessionが直接扱う。
 */
public final class LinkMode implements SurveyMode {
	@Override
	public String id() {
		return "link";
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
