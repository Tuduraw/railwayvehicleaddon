package com.example.railwayvehicleaddon.track;

/** 線路の建築限界内にあるブロックの分類。 */
public enum BlockCategory {
	/** 地形生成で現れるブロック。警告なしで自動撤去する */
	NATURAL(false, false),
	/** 地形生成には含まれないブロック(レンガ等)。プレイヤーが設置したとみなし警告するが、敷設は可能 */
	PLAYER_PLACED(true, false),
	/** 水・溶岩など流体を含むブロック。自動撤去できないため敷設不可 */
	BLOCKED_FLUID(true, true),
	/** 非常に硬い(または破壊不能な)ブロック。自動撤去できないため敷設不可 */
	BLOCKED_HARD(true, true),
	/** 未ロードのチャンクにあり判定できない。サーバー側では敷設不可として扱う */
	UNLOADED(true, true);

	private final boolean warn;
	private final boolean blocking;

	BlockCategory(boolean warn, boolean blocking) {
		this.warn = warn;
		this.blocking = blocking;
	}

	public boolean warns() {
		return this.warn;
	}

	public boolean blocksPlacement() {
		return this.blocking;
	}
}
