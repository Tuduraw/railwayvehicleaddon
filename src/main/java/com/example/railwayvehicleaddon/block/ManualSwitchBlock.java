package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.BaseEntityBlock;

/** 転てつてこ(人力)。動作はDeviceKind.MANUAL_SWITCH参照。 */
public class ManualSwitchBlock extends TrackDeviceBlock {
	public static final MapCodec<ManualSwitchBlock> CODEC = simpleCodec(ManualSwitchBlock::new);

	public ManualSwitchBlock(Properties settings) {
		super(settings);
	}

	@Override
	public DeviceKind kind() {
		return DeviceKind.MANUAL_SWITCH;
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}
}
