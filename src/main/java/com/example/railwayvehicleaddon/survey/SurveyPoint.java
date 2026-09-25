package com.example.railwayvehicleaddon.survey;

import net.minecraft.util.math.Vec3d;

/**
 * 測量ツールで指定した1点。
 *
 * @param pos       位置(線路基面)。既存ノード・線路上の点を指定した場合はその位置
 * @param nodeId    指定した既存ノード(無ければ-1)。端点のほか、途中のノードのこともある
 * @param segmentId 線路の途中を指定した場合の区間(無ければ-1)
 * @param s         segmentId上の距離
 */
public record SurveyPoint(Vec3d pos, long nodeId, long segmentId, double s) {

	public SurveyPoint(Vec3d pos, long nodeId) {
		this(pos, nodeId, -1L, 0.0);
	}

	/** 既存ノードを指定しているか。 */
	public boolean snapped() {
		return this.nodeId >= 0L;
	}

	/** 線路の途中を指定しているか。 */
	public boolean onTrack() {
		return this.segmentId >= 0L;
	}
}
