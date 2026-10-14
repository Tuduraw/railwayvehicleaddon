package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.item.ModItems;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * 線路の位置に連結する地上子ブロック(停止位置目標・列車番号検知器)の共通部分。
 * 正面(FACING)は設置したプレイヤーの方を向き、正面に向かって進んでくる列車に効く。
 * 名前(駅名・検知器の名前)は、金床で名前を付けたブロックを置くか、名前を付けた名札で右クリックして設定する。
 */
public abstract class WaysideBlock extends BlockWithEntity {
	public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

	protected WaysideBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	protected net.minecraft.block.BlockRenderType getRenderType(BlockState state) {
		return net.minecraft.block.BlockRenderType.MODEL;
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new WaysideBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient() ? null : validateTicker(type, ModBlocks.WAYSIDE_ENTITY, WaysideBlockEntity::tick);
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (world instanceof ServerWorld serverWorld && world.getBlockEntity(pos) instanceof WaysideBlockEntity entity) {
			Text custom = itemStack.getCustomName();
			if (custom != null) {
				entity.setName(custom.getString());
			}
			entity.relink(serverWorld);
			if (placer instanceof PlayerEntity player) {
				player.sendMessage(describe(serverWorld, entity), false);
				if (entity.link().ambiguous(serverWorld, state.get(FACING))) {
					player.sendMessage(Text.translatable("message.railwayvehicleaddon.wayside.ambiguous"), false);
				}
			}
		}
	}

	/** 設置時・右クリック時に表示する説明。 */
	protected abstract Text describe(ServerWorld world, WaysideBlockEntity entity);

	/** 素手での右クリック(スニークの有無)。設定の切り替えなど。 */
	protected abstract void useEmptyHand(ServerWorld world, WaysideBlockEntity entity, PlayerEntity player, boolean sneaking);

	@Override
	protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player,
										 Hand hand, BlockHitResult hit) {
		if (stack.isOf(ModItems.SURVEY_TOOL)) {
			return ActionResult.PASS;
		}
		if (stack.isOf(Items.NAME_TAG) && stack.getCustomName() != null) {
			// 名前を付けた名札で右クリック: 名前を設定する(名札は減らない)
			if (world instanceof ServerWorld serverWorld && world.getBlockEntity(pos) instanceof WaysideBlockEntity entity) {
				entity.setName(stack.getCustomName().getString());
				player.sendMessage(describe(serverWorld, entity), true);
			}
			return ActionResult.SUCCESS;
		}
		return ActionResult.PASS_TO_DEFAULT_BLOCK_ACTION;
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world instanceof ServerWorld serverWorld && world.getBlockEntity(pos) instanceof WaysideBlockEntity entity) {
			useEmptyHand(serverWorld, entity, player, player.isSneaking());
		}
		return ActionResult.SUCCESS;
	}
}
