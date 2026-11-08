package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 運行管理装置。前提MODのドローン制御ブロックと同じ考え方で、「制御スティック(前提MOD)」と「経路アイテム」を
 * 1組にしたスロットを並べ(組の数は画面で変えられ、ページで切り替える)、スティックに登録した車両を含む編成へ、
 * 同じ組の経路アイテムの運行表を割り当てて、ATOで運転させる。右クリックで画面を開く。
 */
public class OperationControllerBlock extends BaseEntityBlock {
	public static final MapCodec<OperationControllerBlock> CODEC = simpleCodec(OperationControllerBlock::new);

	public OperationControllerBlock(Properties settings) {
		super(settings);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
		return net.minecraft.world.level.block.RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new OperationControllerBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
		return world.isClientSide() ? null : createTickerHelper(type, ModBlocks.OPERATION_CONTROLLER_ENTITY, OperationControllerBlockEntity::tick);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos, Player player,
										 InteractionHand hand, BlockHitResult hit) {
		// 持っているものに関わらず、通常の右クリック(onUse)で画面を開く
		return InteractionResult.TRY_WITH_EMPTY_HAND;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
		if (world instanceof ServerLevel && world.getBlockEntity(pos) instanceof OperationControllerBlockEntity entity
				&& player instanceof ServerPlayer serverPlayer) {
			serverPlayer.openMenu(entity);
		}
		return InteractionResult.SUCCESS;
	}
}
