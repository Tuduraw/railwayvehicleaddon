package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.BaseEntityBlock;

/** 転てつ機(レッドストーン)。動作はDeviceKind.REDSTONE_SWITCH参照。 */
public class SwitchMachineBlock extends TrackDeviceBlock {
	public static final MapCodec<SwitchMachineBlock> CODEC = simpleCodec(SwitchMachineBlock::new);

	public SwitchMachineBlock(Properties settings) {
		super(settings);
	}

	@Override
	public DeviceKind kind() {
		return DeviceKind.REDSTONE_SWITCH;
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}
}
