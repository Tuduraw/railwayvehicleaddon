package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockWithEntity;

/** 転車台・遷車台の制御器(レッドストーン)。動作はDeviceKind.REDSTONE_DECK参照。 */
public class DeckControllerBlock extends TrackDeviceBlock {
	public static final MapCodec<DeckControllerBlock> CODEC = createCodec(DeckControllerBlock::new);

	public DeckControllerBlock(Settings settings) {
		super(settings);
	}

	@Override
	public DeviceKind kind() {
		return DeviceKind.REDSTONE_DECK;
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}
}
