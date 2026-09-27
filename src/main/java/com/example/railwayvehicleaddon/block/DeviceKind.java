package com.example.railwayvehicleaddon.block;

/**
 * 線路装置の種類。人力(プレイヤーのクリック)とレッドストーンで、それぞれ別の装置にしている。
 *
 * @param target  連結する対象の種類
 * @param manual  右クリックで操作する人力装置か
 * @param input   レッドストーン入力で操作する装置か
 * @param output  レッドストーンを出力する装置か
 */
public enum DeviceKind {
	/** 転てつてこ: 右クリックで分岐器を次の分岐側へ切り替える */
	MANUAL_SWITCH(TargetType.SWITCH, true, false, false),
	/** 転てつ機: 入力の強さで分岐側を選ぶ(0で0番目、1以上でその番号。2方向なら入力の有無で切替) */
	REDSTONE_SWITCH(TargetType.SWITCH, false, true, false),
	/** 転車台・遷車台の操作盤: 右クリックで次、スニーク+右クリックで前の停止位置へ */
	MANUAL_DECK(TargetType.DECK, true, false, false),
	/** 転車台・遷車台の制御器: 入力の強さnで停止位置n番目(1始まり)へ。0では動かさない */
	REDSTONE_DECK(TargetType.DECK, false, true, false),
	/** 在線検知器: 連結した区間に車両がいる間、強さ15を出力する */
	DETECTOR(TargetType.SEGMENT, false, false, true);

	private final TargetType target;
	private final boolean manual;
	private final boolean input;
	private final boolean output;

	DeviceKind(TargetType target, boolean manual, boolean input, boolean output) {
		this.target = target;
		this.manual = manual;
		this.input = input;
		this.output = output;
	}

	public TargetType target() {
		return this.target;
	}

	public boolean manual() {
		return this.manual;
	}

	public boolean input() {
		return this.input;
	}

	public boolean output() {
		return this.output;
	}

	/** 連結対象の種類。IDの意味(ノード・設備・区間)が変わる。 */
	public enum TargetType {
		NONE, SWITCH, DECK, SEGMENT
	}
}
