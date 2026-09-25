package com.example.railwayvehicleaddon.track;

/**
 * 線路上の1点の幾何情報。方向・勾配・カントはすべて区間のパラメータ進行方向(A→B)基準。
 * 車両の向きで使うときは、TrackPosのfacing(+1/-1)を掛けて車両基準に直す。
 *
 * @param x       線路中心のX
 * @param y       線路基面(枕木の下面)の高さ
 * @param z       線路中心のZ
 * @param dirX    水平方向の単位接線X
 * @param dirZ    水平方向の単位接線Z
 * @param grade   勾配(dy/水平距離)
 * @param cantRad カント角(ラジアン)。正で進行方向右手側(モデル-X側)が下がる
 */
public record TrackPoint(double x, double y, double z, double dirX, double dirZ, double grade, double cantRad) {

	/** 進行方向に対する横方向単位ベクトルX(モデル座標の+X方向に相当)。 */
	public double lateralX() {
		return this.dirZ;
	}

	/** 進行方向に対する横方向単位ベクトルZ(モデル座標の+X方向に相当)。 */
	public double lateralZ() {
		return -this.dirX;
	}
}
