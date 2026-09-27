package com.example.railwayvehicleaddon.track;

/** 線路の道床の見た目。区間ごとに持ち、敷設時に測量ツールで選ぶ。保存・同期はidで行う。 */
public enum BallastType {
	/** 砕石(既定) */
	GRAVEL,
	/** 土 */
	DIRT,
	/** 道床なし(枕木を直接置く) */
	NONE;

	public int id() {
		return ordinal();
	}

	public static BallastType byId(int id) {
		BallastType[] values = values();
		return id >= 0 && id < values.length ? values[id] : GRAVEL;
	}

	public BallastType next() {
		return values()[(ordinal() + 1) % values().length];
	}
}
