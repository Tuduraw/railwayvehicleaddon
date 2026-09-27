package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 給水塔。近く(水平5ブロック以内)に停車している蒸気機関車へ水を補給する。
 * 水は前提MODの燃料を水として使っているので、補給すると燃料計(水の量)が増える。
 */
public class WaterTowerBlock extends BlockWithEntity {
	public static final MapCodec<WaterTowerBlock> CODEC = createCodec(WaterTowerBlock::new);

	public WaterTowerBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected net.minecraft.block.BlockRenderType getRenderType(BlockState state) {
		return net.minecraft.block.BlockRenderType.MODEL;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new WaterTowerBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient() ? null : validateTicker(type, ModBlocks.WATER_TOWER_ENTITY, WaterTowerBlockEntity::tick);
	}
}
