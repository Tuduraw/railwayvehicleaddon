package com.example.railwayvehicleaddon.track;

/**
 * 線路の電化方式。区間ごとに持ち、敷設時や電化モードで選ぶ。保存・同期はidで行う。
 * 給電の可否(電化区画のつながり・給電係数)は方式を区別せず、NONE以外なら等しく扱う
 * (架線でも第三軌条でも、電車はどちらからでも集電できるものとする)。方式の違いは見た目だけ。
 */
public enum ElectrificationType {
	/** 非電化(既定) */
	NONE,
	/** 架線(電車線・架線柱) */
	OVERHEAD,
	/** 第三軌条 */
	THIRD_RAIL,
	/**
	 * 非表示。給電の対象にはなるが、架線も第三軌条も描かない。ブロックや別のmodで設備を再現したい
	 * ときに使う(idは既存の保存データとの互換のため、必ず末尾に追加する)。
	 */
	HIDDEN;

	public int id() {
		return ordinal();
	}

	public static ElectrificationType byId(int id) {
		ElectrificationType[] values = values();
		return id >= 0 && id < values.length ? values[id] : NONE;
	}

	public ElectrificationType next() {
		return values()[(ordinal() + 1) % values().length];
	}

	public boolean isElectrified() {
		return this != NONE;
	}
}
