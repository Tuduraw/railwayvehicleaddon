package com.example.railwayvehicleaddon.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 信号機・踏切遮断機の共通のブロックエンティティ。状態(現示・遮断中)はブロックの状態に持つため、
 * これ自体はデータを持たない。クライアントの描画(灯火・遮断かん)がこの種類のブロックエンティティを
 * 探して描くための目印と、踏切の警報音の再生に使う。
 */
public class SignalDeviceBlockEntity extends BlockEntity {
	/** 警報音の間隔(tick) */
	private static final int BELL_INTERVAL = 12;
	private int bellTimer;

	public SignalDeviceBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.SIGNAL_DEVICE_ENTITY, pos, state);
	}

	/** 踏切: 遮断中は一定間隔で警報音を鳴らす。 */
	public static void tick(World world, BlockPos pos, BlockState state, SignalDeviceBlockEntity entity) {
		if (world.isClient() || !(state.getBlock() instanceof CrossingGateBlock) || !state.get(CrossingGateBlock.POWERED)) {
			entity.bellTimer = 0;
			return;
		}
		if (entity.bellTimer-- <= 0) {
			entity.bellTimer = BELL_INTERVAL;
			world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.BLOCKS, 1.2f, 1.5f);
		}
	}
}
