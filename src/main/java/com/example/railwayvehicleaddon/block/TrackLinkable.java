package com.example.railwayvehicleaddon.block;

/**
 * 線路上の何か(分岐器・転車台や遷車台・線路区間)に連結するブロックエンティティの共通インターフェース。
 * 測量ツールの連結モードは、ブロックの種類ではなくこのインターフェースの有無で装置を見分ける。
 * 実装先: {@link TrackDeviceBlockEntity}(転てつてこ等)、{@link SubstationBlockEntity}(変電所)。
 */
public interface TrackLinkable {
	/** 連結先の種類。IDの意味(分岐器ノード・設備・区間)はこれで決まる。 */
	DeviceKind.TargetType linkTarget();

	long targetId();

	void setTargetId(long targetId);
}
