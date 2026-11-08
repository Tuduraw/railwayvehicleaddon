package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.item.ModItems;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
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
 * 変電所。設置すると近くの線路に自動で連結する(測量ツールの連結モードで付け替え可)。
 * 石炭・木炭・石炭ブロックを右クリックでくべて発電する。空手で右クリックすると状態を表示する。
 */
public class SubstationBlock extends BaseEntityBlock {
	public static final MapCodec<SubstationBlock> CODEC = simpleCodec(SubstationBlock::new);

	public SubstationBlock(Properties settings) {
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
		return new SubstationBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
		return world.isClientSide() ? null : createTickerHelper(type, ModBlocks.SUBSTATION_ENTITY, SubstationBlockEntity::tick);
	}

	@Override
	public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.setPlacedBy(world, pos, state, placer, itemStack);
		if (world instanceof ServerLevel serverWorld && world.getBlockEntity(pos) instanceof SubstationBlockEntity entity) {
			long target = DeviceLinks.findNearest(serverWorld, pos, DeviceKind.TargetType.SEGMENT);
			entity.setTargetId(target);
			if (placer instanceof Player player) {
				player.displayClientMessage(target >= 0 ? DeviceLinks.describe(serverWorld, DeviceKind.TargetType.SEGMENT, target)
						: Component.translatable("message.railwayvehicleaddon.device.no_target"), true);
			}
		}
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos, Player player,
										 InteractionHand hand, BlockHitResult hit) {
		if (stack.is(ModItems.SURVEY_TOOL)) {
			// 測量ツールの連結モードを優先する
			return InteractionResult.PASS;
		}
		// 持っているものに関わらず、通常の右クリック(onUse)でGUIを開く
		return InteractionResult.TRY_WITH_EMPTY_HAND;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
		if (!(world instanceof ServerLevel)) {
			return InteractionResult.SUCCESS;
		}
		if (world.getBlockEntity(pos) instanceof SubstationBlockEntity entity
				&& player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
			serverPlayer.openMenu(entity);
		}
		return InteractionResult.SUCCESS;
	}

	/** 壊したとき、燃料スロットの中身を地面に落とす。 */
	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel world, BlockPos pos, boolean moved) {
		Containers.updateNeighboursAfterDestroy(state, world, pos);
		super.affectNeighborsAfterRemoval(state, world, pos, moved);
	}
}
