package com.example.railwayvehicleaddon.survey;

import java.util.List;

/**
 * モードへの入力。クライアントの操作状態からそのまま作られ、確定時にサーバーへ送られる。
 *
 * @param points 指定した点(順序に意味がある。意味はモードごと)
 * @param closed 環状に閉じる(対応するモードのみ)
 * @param param   モード固有の整数パラメータ(転車台の分割角度など。[ ] キーで増減)
 * @param ballast 敷設する線路の道床(BallastType.id())
 * @param force   強制置換(クリエイティブのみ有効。本来撤去できないブロックも撤去する)
 * @param electrify 敷設する線路を電化する(架線を張る)
 */
public record SurveyInput(List<SurveyPoint> points, boolean closed, int param, int ballast, boolean force, boolean electrify) {

	public SurveyInput(List<SurveyPoint> points, boolean closed, int param) {
		this(points, closed, param, 0, false, false);
	}
}
