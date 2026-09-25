package com.example.railwayvehicleaddon.track;

/**
 * 線路グラフのノード(経由点)。位置は勾配線上の値で、表示や接続判定に使う。
 * 実際の線路の高さは縦曲線の分だけこの値からわずかにずれることがある。
 */
public record TrackNode(long id, double x, double y, double z) {
}
