package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockWithEntity;

/** 転車台・遷車台の操作盤(人力)。動作はDeviceKind.MANUAL_DECK参照。 */
public class DeckPanelBlock extends TrackDeviceBlock {
	public static final MapCodec<DeckPanelBlock> CODEC = createCodec(DeckPanelBlock::new);

	public DeckPanelBlock(Settings settings) {
		super(settings);
	}

	@Override
	public DeviceKind kind() {
		return DeviceKind.MANUAL_DECK;
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}
}
