package com.example.railwayvehicleaddon.track;

/**
 * 1区間(経由点A→B)の縦断線形。基本は一定勾配の直線で、勾配が変わる経由点の
 * 前後に放物線の縦曲線を入れる。
 *
 * <p>縦曲線は経由点(勾配変化点)を中心に前後それぞれ長さh。前後の区間が同じ
 * 公式で補正量を計算するため、境界(経由点)で高さ・勾配とも連続になる。
 * 補正量は勾配線から見た放物線のずれ: (g - gPrev)(s - hA)^2 / (4 hA)。
 * 経由点そのものでは (g - gPrev) hA / 4 だけ勾配線から離れる(縦曲線の性質上避けられない)。
 *
 * <p>線路の途中で区間を分割したときは、元の区間の縦断をそのまま使い、offset(元の区間での
 * 開始位置)とparentLength(元の区間の長さ)で範囲を指す。これで分割しても高さが変わらない。
 *
 * @param yA           始点(経由点A)の勾配線上の高さ
 * @param yB           終点(経由点B)の勾配線上の高さ
 * @param gPrev        始点より手前の区間の勾配(縦曲線なしならgと同じでよい)
 * @param gNext        終点より先の区間の勾配
 * @param hA           始点側の縦曲線の片側長さ(0で縦曲線なし)
 * @param hB           終点側の縦曲線の片側長さ(0で縦曲線なし)
 * @param offset       分割された区間の、元の区間での開始位置(分割していなければ0)
 * @param parentLength 元の区間の長さ(分割していなければ0)
 */
public record HeightProfile(double yA, double yB, double gPrev, double gNext, double hA, double hB,
							double offset, double parentLength) {

	public HeightProfile(double yA, double yB, double gPrev, double gNext, double hA, double hB) {
		this(yA, yB, gPrev, gNext, hA, hB, 0.0, 0.0);
	}

	/** 水平で一定の高さ(転車台・遷車台の桁など)。 */
	public static HeightProfile flat(double y) {
		return new HeightProfile(y, y, 0.0, 0.0, 0.0, 0.0);
	}

	private boolean isSub() {
		return this.parentLength > 1.0e-9;
	}

	private double baseLength(double length) {
		return isSub() ? this.parentLength : length;
	}

	private double grade(double baseLength) {
		return baseLength > 1.0e-9 ? (this.yB - this.yA) / baseLength : 0.0;
	}

	/** 水平距離sにおける線路基面の高さ。 */
	public double y(double s, double length) {
		double L = baseLength(length);
		double x = isSub() ? s + this.offset : s;
		double g = grade(L);
		double y = this.yA + g * x;
		if (this.hA > 1.0e-6 && x < this.hA) {
			double d = x - this.hA;
			y += (g - this.gPrev) * d * d / (4.0 * this.hA);
		}
		double curveStartB = L - this.hB;
		if (this.hB > 1.0e-6 && x > curveStartB) {
			double d = x - curveStartB;
			y += (this.gNext - g) * d * d / (4.0 * this.hB);
		}
		return y;
	}

	/** 水平距離sにおける勾配(dy/ds、パラメータ進行方向基準)。 */
	public double gradeAt(double s, double length) {
		double L = baseLength(length);
		double x = isSub() ? s + this.offset : s;
		double g = grade(L);
		double result = g;
		if (this.hA > 1.0e-6 && x < this.hA) {
			result += (g - this.gPrev) * (x - this.hA) / (2.0 * this.hA);
		}
		double curveStartB = L - this.hB;
		if (this.hB > 1.0e-6 && x > curveStartB) {
			result += (this.gNext - g) * (x - curveStartB) / (2.0 * this.hB);
		}
		return result;
	}

	/** 自身(長さlength)の[from, from+subLength]の範囲を表す縦断。 */
	public HeightProfile sub(double from, double length) {
		double parent = baseLength(length);
		double start = (isSub() ? this.offset : 0.0) + from;
		return new HeightProfile(this.yA, this.yB, this.gPrev, this.gNext, this.hA, this.hB, start, parent);
	}

	public double[] toArray() {
		return new double[]{yA, yB, gPrev, gNext, hA, hB, offset, parentLength};
	}

	/** 6要素(分割に対応する前の保存形式)と8要素のどちらも読める。 */
	public static HeightProfile fromArray(double[] a) {
		return new HeightProfile(a[0], a[1], a[2], a[3], a[4], a[5], a.length > 6 ? a[6] : 0.0, a.length > 7 ? a[7] : 0.0);
	}
}
