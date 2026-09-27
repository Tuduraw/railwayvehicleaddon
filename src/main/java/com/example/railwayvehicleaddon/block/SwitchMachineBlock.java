package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockWithEntity;

/** 転てつ機(レッドストーン)。動作はDeviceKind.REDSTONE_SWITCH参照。 */
public class SwitchMachineBlock extends TrackDeviceBlock {
	public static final MapCodec<SwitchMachineBlock> CODEC = createCodec(SwitchMachineBlock::new);

	public SwitchMachineBlock(Settings settings) {
		super(settings);
	}

	@Override
	public DeviceKind kind() {
		return DeviceKind.REDSTONE_SWITCH;
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}
}
