package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.item.ModItems;
import com.example.railwayvehicleaddon.track.TrackManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

/**
 * 線路装置の共通部分。設置すると近くの対象(種類に応じて分岐器・転車台/遷車台・線路区間)に
 * 自動で連結する。連結先は測量ツールの連結モードで付け替えられる。
 *
 * <p>人力装置は右クリックで操作する。測量ツールを持っているときは装置を操作せず、ツールの操作
 * (連結モードでの装置の選択など)を優先する。レッドストーン入力の装置は入力の強さが変わったときに
 * 動作し、設置時・読み込み時点の入力では動作しない。
 */
public abstract class TrackDeviceBlock extends BlockWithEntity {
	public static final BooleanProperty POWERED = Properties.POWERED;

	protected TrackDeviceBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(POWERED, false));
	}

	public abstract DeviceKind kind();

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(POWERED);
	}

	@Override
	protected net.minecraft.block.BlockRenderType getRenderType(BlockState state) {
		return net.minecraft.block.BlockRenderType.MODEL;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new TrackDeviceBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		if (world.isClient() || !kind().output()) {
			return null;
		}
		return validateTicker(type, ModBlocks.DEVICE_ENTITY, TrackDeviceBlockEntity::tick);
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (world instanceof ServerWorld serverWorld && world.getBlockEntity(pos) instanceof TrackDeviceBlockEntity entity) {
			long target = DeviceLinks.findNearest(serverWorld, pos, kind().target());
			entity.setTargetId(target);
			entity.setLastPower(world.getReceivedRedstonePower(pos));
			if (placer instanceof PlayerEntity player) {
				player.sendMessage(target >= 0 ? DeviceLinks.describe(serverWorld, kind().target(), target)
						: Text.translatable("message.railwayvehicleaddon.device.no_target"), true);
			}
		}
	}

	// ------------------------------------------------------------------ 人力操作

	@Override
	protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player,
										 Hand hand, BlockHitResult hit) {
		if (stack.isOf(ModItems.SURVEY_TOOL)) {
			// 測量ツールの操作(連結モードの装置選択など)を優先する
			return ActionResult.PASS;
		}
		return kind().manual() ? ActionResult.PASS_TO_DEFAULT_BLOCK_ACTION : ActionResult.PASS;
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!kind().manual()) {
			return ActionResult.PASS;
		}
		if (!(world instanceof ServerWorld serverWorld) || !(world.getBlockEntity(pos) instanceof TrackDeviceBlockEntity entity)) {
			return ActionResult.SUCCESS;
		}
		long target = entity.targetId();
		boolean done = false;
		if (target >= 0) {
			if (kind().target() == DeviceKind.TargetType.SWITCH) {
				done = TrackManager.cycleSwitch(serverWorld, target);
			} else if (kind().target() == DeviceKind.TargetType.DECK) {
				int step = player.isSneaking() ? -1 : 1;
				done = TrackManager.moveDeck(serverWorld, target, deck -> deck.selectNext(step), text -> player.sendMessage(text, true));
				if (done) {
					player.sendMessage(Text.translatable("message.railwayvehicleaddon.device.deck_moving"), true);
				}
				return ActionResult.SUCCESS;
			}
		}
		if (!done) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.device.no_target"), true);
		}
		return ActionResult.SUCCESS;
	}

	// ------------------------------------------------------------------ レッドストーン入力

	@Override
	protected void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock,
								  WireOrientation wireOrientation, boolean notify) {
		super.neighborUpdate(state, world, pos, sourceBlock, wireOrientation, notify);
		if (!kind().input() || !(world instanceof ServerWorld serverWorld)
				|| !(world.getBlockEntity(pos) instanceof TrackDeviceBlockEntity entity)) {
			return;
		}
		int power = world.getReceivedRedstonePower(pos);
		if (power == entity.lastPower()) {
			return;
		}
		entity.setLastPower(power);
		if (state.get(POWERED) != (power > 0)) {
			world.setBlockState(pos, state.with(POWERED, power > 0), Block.NOTIFY_LISTENERS);
		}
		long target = entity.targetId();
		if (target < 0) {
			return;
		}
		if (kind().target() == DeviceKind.TargetType.SWITCH) {
			// 0で0番目、1以上でその番号の分岐側(範囲外は最後)。2方向の分岐器なら入力の有無で切り替わる
			TrackManager.setSwitchBranch(serverWorld, target, power);
		} else if (kind().target() == DeviceKind.TargetType.DECK && power > 0) {
			// 強さnで停止位置n番目(1始まり)へ。停止位置の並びは転車台は1点目の方向から角度順、遷車台は横方向の位置順
			int index = power - 1;
			TrackManager.moveDeck(serverWorld, target, deck -> deck.selectStop(index), null);
		}
	}

	// ------------------------------------------------------------------ レッドストーン出力

	@Override
	protected boolean emitsRedstonePower(BlockState state) {
		return kind().output();
	}

	@Override
	protected int getWeakRedstonePower(BlockState state, BlockView world, BlockPos pos, Direction direction) {
		return kind().output() && state.get(POWERED) ? 15 : 0;
	}
}
