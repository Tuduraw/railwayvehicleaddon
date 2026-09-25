package com.example.railwayvehicleaddon.track;

/**
 * 車両の線路上の位置。
 *
 * @param segmentId 乗っている区間
 * @param s         区間上の水平距離(0 = ノードA)
 * @param facing    車両前方が区間のA→B方向なら+1、B→A方向なら-1
 */
public record TrackPos(long segmentId, double s, int facing) {
}
