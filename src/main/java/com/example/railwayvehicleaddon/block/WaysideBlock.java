package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 線路の位置に連結する地上子ブロック(停止位置目標・列車番号検知器)の共通部分。
 * 正面(FACING)は設置したプレイヤーの方を向き、正面に向かって進んでくる列車に効く。
 * 名前(駅名・検知器の名前)は、金床で名前を付けたブロックを置くか、名前を付けた名札で右クリックして設定する。
 */
public abstract class WaysideBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	protected WaysideBlock(Properties settings) {
		super(settings);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
		return net.minecraft.world.level.block.RenderShape.MODEL;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new WaysideBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
		return world.isClientSide() ? null : createTickerHelper(type, ModBlocks.WAYSIDE_ENTITY, WaysideBlockEntity::tick);
	}

	@Override
	public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.setPlacedBy(world, pos, state, placer, itemStack);
		if (world instanceof ServerLevel serverWorld && world.getBlockEntity(pos) instanceof WaysideBlockEntity entity) {
			Component custom = itemStack.getCustomName();
			if (custom != null) {
				entity.setName(custom.getString());
			}
			entity.relink(serverWorld);
			if (placer instanceof Player player) {
				player.displayClientMessage(describe(serverWorld, entity), false);
				if (usesDirection() && entity.link().ambiguous(serverWorld, state.getValue(FACING))) {
					player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.wayside.ambiguous"), false);
				}
			}
		}
	}

	/** ブロックの向き(効く列車の進行方向)を使うか。使わないブロックには、向きがはっきりしない警告を出さない。 */
	protected boolean usesDirection() {
		return true;
	}

	/** 設置時・右クリック時に表示する説明。 */
	protected abstract Component describe(ServerLevel world, WaysideBlockEntity entity);

	/** 素手での右クリック(スニークの有無)。設定の切り替えなど。 */
	protected abstract void useEmptyHand(ServerLevel world, WaysideBlockEntity entity, Player player, boolean sneaking);

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos, Player player,
										 InteractionHand hand, BlockHitResult hit) {
		if (stack.is(ModItems.SURVEY_TOOL) || stack.getItem() instanceof com.example.railwayvehicleaddon.item.RouteCardItem) {
			// 測量ツール(連結モード)・経路アイテム(駅・検知器の登録)の操作を優先する
			return InteractionResult.PASS;
		}
		if (stack.is(Items.NAME_TAG) && stack.getCustomName() != null) {
			// 名前を付けた名札で右クリック: 名前を設定する(名札は減らない)
			if (world instanceof ServerLevel serverWorld && world.getBlockEntity(pos) instanceof WaysideBlockEntity entity) {
				entity.setName(stack.getCustomName().getString());
				player.displayClientMessage(describe(serverWorld, entity), true);
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.TRY_WITH_EMPTY_HAND;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
		if (world instanceof ServerLevel serverWorld && world.getBlockEntity(pos) instanceof WaysideBlockEntity entity) {
			useEmptyHand(serverWorld, entity, player, player.isShiftKeyDown());
		}
		return InteractionResult.SUCCESS;
	}
}
