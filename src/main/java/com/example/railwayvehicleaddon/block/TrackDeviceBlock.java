package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.item.ModItems;
import com.example.railwayvehicleaddon.track.TrackManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 線路装置の共通部分。設置すると近くの対象(種類に応じて分岐器・転車台/遷車台・線路区間)に
 * 自動で連結する。連結先は測量ツールの連結モードで付け替えられる。
 *
 * <p>人力装置は右クリックで操作する。測量ツールを持っているときは装置を操作せず、ツールの操作
 * (連結モードでの装置の選択など)を優先する。レッドストーン入力の装置は入力の強さが変わったときに
 * 動作し、設置時・読み込み時点の入力では動作しない。
 */
public abstract class TrackDeviceBlock extends BaseEntityBlock {
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	protected TrackDeviceBlock(Properties settings) {
		super(settings);
		registerDefaultState(defaultBlockState().setValue(POWERED, false));
	}

	public abstract DeviceKind kind();

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(POWERED);
	}

	@Override
	protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
		return net.minecraft.world.level.block.RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TrackDeviceBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
		// 在線検知器(出力)と、分岐器の装置(鎖錠中をコンパレーターへ出す)だけが毎tick動く
		if (world.isClientSide() || (!kind().output() && kind().target() != DeviceKind.TargetType.SWITCH)) {
			return null;
		}
		return createTickerHelper(type, ModBlocks.DEVICE_ENTITY, TrackDeviceBlockEntity::tick);
	}

	@Override
	public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.setPlacedBy(world, pos, state, placer, itemStack);
		if (world instanceof ServerLevel serverWorld && world.getBlockEntity(pos) instanceof TrackDeviceBlockEntity entity) {
			long target = DeviceLinks.findNearest(serverWorld, pos, kind().target());
			entity.setTargetId(target);
			entity.setLastPower(world.getBestNeighborSignal(pos));
			if (placer instanceof Player player) {
				player.displayClientMessage(target >= 0 ? DeviceLinks.describe(serverWorld, kind().target(), target)
						: Component.translatable("message.railwayvehicleaddon.device.no_target"), true);
			}
		}
	}

	// ------------------------------------------------------------------ 人力操作

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos, Player player,
										 InteractionHand hand, BlockHitResult hit) {
		if (stack.is(ModItems.SURVEY_TOOL)) {
			// 測量ツールの操作(連結モードの装置選択など)を優先する
			return InteractionResult.PASS;
		}
		return kind().manual() ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
		if (!kind().manual()) {
			return InteractionResult.PASS;
		}
		if (!(world instanceof ServerLevel serverWorld) || !(world.getBlockEntity(pos) instanceof TrackDeviceBlockEntity entity)) {
			return InteractionResult.SUCCESS;
		}
		long target = entity.targetId();
		boolean done = false;
		if (target >= 0) {
			if (kind().target() == DeviceKind.TargetType.SWITCH) {
				if (TrackManager.network(serverWorld).isSwitch(target) && TrackManager.isSwitchLocked(serverWorld, target)) {
					// 分岐区間に車両がいる・進路が予約されている間は切り替えない
					player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.switch_locked"), true);
					return InteractionResult.SUCCESS;
				}
				done = TrackManager.cycleSwitch(serverWorld, target);
			} else if (kind().target() == DeviceKind.TargetType.DECK) {
				int step = player.isShiftKeyDown() ? -1 : 1;
				done = TrackManager.moveDeck(serverWorld, target, deck -> deck.selectNext(step), text -> player.displayClientMessage(text, true));
				if (done) {
					player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.device.deck_moving"), true);
				}
				return InteractionResult.SUCCESS;
			}
		}
		if (!done) {
			player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.device.no_target"), true);
		}
		return InteractionResult.SUCCESS;
	}

	// ------------------------------------------------------------------ レッドストーン入力

	@Override
	protected void neighborChanged(BlockState state, Level world, BlockPos pos, Block sourceBlock,
								  Orientation wireOrientation, boolean notify) {
		super.neighborChanged(state, world, pos, sourceBlock, wireOrientation, notify);
		if (!kind().input() || !(world instanceof ServerLevel serverWorld)
				|| !(world.getBlockEntity(pos) instanceof TrackDeviceBlockEntity entity)) {
			return;
		}
		int power = world.getBestNeighborSignal(pos);
		if (power == entity.lastPower()) {
			return;
		}
		entity.setLastPower(power);
		if (state.getValue(POWERED) != (power > 0)) {
			world.setBlock(pos, state.setValue(POWERED, power > 0), Block.UPDATE_CLIENTS);
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
	protected boolean isSignalSource(BlockState state) {
		return kind().output();
	}

	/** 分岐器の装置(転てつてこ・転てつ機)は、分岐器が鎖錠中の間、コンパレーターへ強さ15を出す。 */
	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return kind().target() == DeviceKind.TargetType.SWITCH;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level world, BlockPos pos, Direction direction) {
		return world.getBlockEntity(pos) instanceof TrackDeviceBlockEntity entity && entity.switchLocked() ? 15 : 0;
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter world, BlockPos pos, Direction direction) {
		return kind().output() && state.getValue(POWERED) ? 15 : 0;
	}
}
