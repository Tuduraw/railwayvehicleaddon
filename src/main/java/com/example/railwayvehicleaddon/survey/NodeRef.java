package com.example.railwayvehicleaddon.survey;

/**
 * 配置計画中のノード参照。既存ノード(existingId)、計画で新しく作るノード(newIndex)、
 * 区間の分割でできるノード(splitIndex)のいずれか。
 */
public record NodeRef(long existingId, int newIndex, int splitIndex) {
	public static NodeRef existing(long id) {
		return new NodeRef(id, -1, -1);
	}

	public static NodeRef created(int index) {
		return new NodeRef(-1L, index, -1);
	}

	public static NodeRef split(int index) {
		return new NodeRef(-1L, -1, index);
	}

	public boolean isExisting() {
		return this.existingId >= 0L;
	}

	public boolean isSplit() {
		return this.splitIndex >= 0;
	}
}
