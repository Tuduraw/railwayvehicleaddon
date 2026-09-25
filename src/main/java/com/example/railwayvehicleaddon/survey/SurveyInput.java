package com.example.railwayvehicleaddon.survey;

import java.util.List;

/**
 * モードへの入力。クライアントの操作状態からそのまま作られ、確定時にサーバーへ送られる。
 *
 * @param points 指定した点(順序に意味がある。意味はモードごと)
 * @param closed 環状に閉じる(対応するモードのみ)
 * @param param  モード固有の整数パラメータ(転車台の分割角度など。[ ] キーで増減)
 */
public record SurveyInput(List<SurveyPoint> points, boolean closed, int param) {
}
