package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 運行管理装置。前提MODのドローン制御ブロックと同じ考え方で、「制御スティック(前提MOD)」と「経路アイテム」を
 * 1組にしたスロットを並べ(組の数は画面で変えられ、ページで切り替える)、スティックに登録した車両を含む編成へ、
 * 同じ組の経路アイテムの運行表を割り当てて、ATOで運転させる。右クリックで画面を開く。
 */
public class OperationControllerBlock extends BlockWithEntity {
	public static final MapCodec<OperationControllerBlock> CODEC = createCodec(OperationControllerBlock::new);

	public OperationControllerBlock(Settings settings) {
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
		return new OperationControllerBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient() ? null : validateTicker(type, ModBlocks.OPERATION_CONTROLLER_ENTITY, OperationControllerBlockEntity::tick);
	}

	@Override
	protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player,
										 Hand hand, BlockHitResult hit) {
		// 持っているものに関わらず、通常の右クリック(onUse)で画面を開く
		return ActionResult.PASS_TO_DEFAULT_BLOCK_ACTION;
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world instanceof ServerWorld && world.getBlockEntity(pos) instanceof OperationControllerBlockEntity entity
				&& player instanceof ServerPlayerEntity serverPlayer) {
			serverPlayer.openHandledScreen(entity);
		}
		return ActionResult.SUCCESS;
	}
}
