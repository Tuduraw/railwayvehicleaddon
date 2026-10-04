package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.item.ModItems;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.mojang.serialization.MapCodec;

/**
 * 変電所。設置すると近くの線路に自動で連結する(測量ツールの連結モードで付け替え可)。
 * 石炭・木炭・石炭ブロックを右クリックでくべて発電する。空手で右クリックすると状態を表示する。
 */
public class SubstationBlock extends BlockWithEntity {
	public static final MapCodec<SubstationBlock> CODEC = createCodec(SubstationBlock::new);

	public SubstationBlock(Settings settings) {
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
		return new SubstationBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient() ? null : validateTicker(type, ModBlocks.SUBSTATION_ENTITY, SubstationBlockEntity::tick);
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (world instanceof ServerWorld serverWorld && world.getBlockEntity(pos) instanceof SubstationBlockEntity entity) {
			long target = DeviceLinks.findNearest(serverWorld, pos, DeviceKind.TargetType.SEGMENT);
			entity.setTargetId(target);
			if (placer instanceof PlayerEntity player) {
				player.sendMessage(target >= 0 ? DeviceLinks.describe(serverWorld, DeviceKind.TargetType.SEGMENT, target)
						: Text.translatable("message.railwayvehicleaddon.device.no_target"), true);
			}
		}
	}

	@Override
	protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player,
										 Hand hand, BlockHitResult hit) {
		if (stack.isOf(ModItems.SURVEY_TOOL)) {
			// 測量ツールの連結モードを優先する
			return ActionResult.PASS;
		}
		int amount = stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL) ? SubstationBlockEntity.coalBurnTime()
				: stack.isOf(Items.COAL_BLOCK) ? SubstationBlockEntity.coalBlockBurnTime() : 0;
		if (amount <= 0) {
			return ActionResult.PASS;
		}
		if (world instanceof ServerWorld && world.getBlockEntity(pos) instanceof SubstationBlockEntity entity) {
			if (entity.addFuel(amount)) {
				if (!player.isCreative()) {
					stack.decrement(1);
				}
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.substation.fueled",
						entity.burnTime() / 20), true);
			} else {
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.substation.full"), true);
			}
		}
		return ActionResult.SUCCESS;
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.getBlockEntity(pos) instanceof SubstationBlockEntity entity) {
			player.sendMessage(entity.isActive()
					? Text.translatable("message.railwayvehicleaddon.substation.status_active",
							entity.burnTime() / 20, SubstationBlockEntity.CAPACITY)
					: Text.translatable("message.railwayvehicleaddon.substation.status_idle"), true);
		}
		return ActionResult.SUCCESS;
	}
}
