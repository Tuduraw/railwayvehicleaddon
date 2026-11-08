package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.BaseEntityBlock;

/** 転車台・遷車台の制御器(レッドストーン)。動作はDeviceKind.REDSTONE_DECK参照。 */
public class DeckControllerBlock extends TrackDeviceBlock {
	public static final MapCodec<DeckControllerBlock> CODEC = simpleCodec(DeckControllerBlock::new);

	public DeckControllerBlock(Properties settings) {
		super(settings);
	}

	@Override
	public DeviceKind kind() {
		return DeviceKind.REDSTONE_DECK;
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}
}
