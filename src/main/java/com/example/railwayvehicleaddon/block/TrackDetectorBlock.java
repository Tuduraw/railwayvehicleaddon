package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockWithEntity;

/** 在線検知器(レッドストーン出力)。動作はDeviceKind.DETECTOR参照。 */
public class TrackDetectorBlock extends TrackDeviceBlock {
	public static final MapCodec<TrackDetectorBlock> CODEC = createCodec(TrackDetectorBlock::new);

	public TrackDetectorBlock(Settings settings) {
		super(settings);
	}

	@Override
	public DeviceKind kind() {
		return DeviceKind.DETECTOR;
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}
}
