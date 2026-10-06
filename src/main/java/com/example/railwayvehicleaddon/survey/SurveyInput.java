package com.example.railwayvehicleaddon.survey;

import java.util.List;

/**
 * モードへの入力。クライアントの操作状態からそのまま作られ、確定時にサーバーへ送られる。
 *
 * @param points 指定した点(順序に意味がある。意味はモードごと)
 * @param closed 環状に閉じる(対応するモードのみ)
 * @param param   モード固有の整数パラメータ(転車台の分割角度など。[ ] キーで増減)
 * @param ballast 敷設する線路の道床(BallastType.id())
 * @param force           強制置換(クリエイティブのみ有効。本来撤去できないブロックも撤去する)
 * @param electrification 敷設する線路の電化方式(ElectrificationType.id())
 * @param wireHeight     架線の高さ(0以下なら設定の catenary_height)
 * @param minRadius      曲線半径のしきい値(設定の min_curve_radius を下回る値は設定値に切り上げる)
 */
public record SurveyInput(List<SurveyPoint> points, boolean closed, int param, int ballast, boolean force, int electrification,
						  float wireHeight, float minRadius) {

	public SurveyInput(List<SurveyPoint> points, boolean closed, int param) {
		this(points, closed, param, 0, false, 0, 0f, 0f);
	}
}
