package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 信号機(3灯式)。受けたレッドストーン入力の強さで現示が変わる。
 * <ul>
 *   <li>0: 進行(緑)</li>
 *   <li>1〜7: 注意(黄)</li>
 *   <li>8〜15: 停止(赤)</li>
 * </ul>
 * 在線検知器の出力(強さ15)をそのままつなげば、在線中は停止現示になる。灯火は上から緑・黄・赤で、
 * 点灯中の灯火は周囲の明るさに関係なく光って見えるよう、クライアントの描画(DeviceOverlayRenderer)で重ねて描く。
 * 支柱を兼ねた形なので、下にフェンスなどを積めば背の高い信号機になる。
 *
 * <p>設置すると近くの線路の位置へ連結し、灯火の正面へ向かって進む列車に現示を伝える(ATS)。
 * 停止現示では信号機の手前で止まるよう、注意現示では信号機までに注意の速度へ落とすよう、
 * 速度を超えた列車にブレーキを掛ける。
 */
public class SignalBlock extends BaseEntityBlock {
	public static final MapCodec<SignalBlock> CODEC = simpleCodec(SignalBlock::new);
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	/** 0=進行、1=注意、2=停止 */
	public static final IntegerProperty ASPECT = IntegerProperty.create("aspect", 0, 2);
	private static final VoxelShape SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 16.0, 12.0);

	public SignalBlock(Properties settings) {
		super(settings);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(ASPECT, 0));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, ASPECT);
	}

	@Override
	protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
		return net.minecraft.world.level.block.RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new SignalDeviceBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
		return world.isClientSide() ? null : createTickerHelper(type, ModBlocks.SIGNAL_DEVICE_ENTITY, SignalDeviceBlockEntity::tick);
	}

	@Override
	public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.setPlacedBy(world, pos, state, placer, itemStack);
		if (world instanceof ServerLevel serverWorld && world.getBlockEntity(pos) instanceof SignalDeviceBlockEntity entity) {
			entity.setAutoBlock(true);
			entity.relink(serverWorld);
			if (placer instanceof Player player) {
				player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.signal.placed", entity.link().describe(serverWorld)), false);
				player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.signal.mode_auto"), false);
				if (entity.link().ambiguous(serverWorld, state.getValue(FACING))) {
					player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.wayside.ambiguous"), false);
				}
			}
		}
	}

	@Override
	protected net.minecraft.world.InteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos,
															Player player, net.minecraft.world.InteractionHand hand,
															net.minecraft.world.phys.BlockHitResult hit) {
		if (stack.is(com.example.railwayvehicleaddon.item.ModItems.SURVEY_TOOL)) {
			return net.minecraft.world.InteractionResult.PASS;
		}
		return super.useItemOn(stack, state, world, pos, player, hand, hit);
	}

	/**
	 * 素手で右クリック: 今の方式(自動閉塞 / レッドストーン)を表示する。スニーク+右クリック: 方式を切り替える。
	 * 自動閉塞でも、レッドストーンの入力があれば、入力の現示と厳しい方になる。
	 */
	@Override
	protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player,
													net.minecraft.world.phys.BlockHitResult hit) {
		if (world instanceof ServerLevel serverWorld && world.getBlockEntity(pos) instanceof SignalDeviceBlockEntity entity) {
			if (player.isShiftKeyDown()) {
				entity.setAutoBlock(!entity.isAutoBlock());
				int aspect = aspectFor(serverWorld.getBestNeighborSignal(pos));
				if (state.getValue(ASPECT) != aspect) {
					serverWorld.setBlock(pos, state.setValue(ASPECT, aspect), Block.UPDATE_CLIENTS);
				}
			}
			player.displayClientMessage(Component.translatable(entity.isAutoBlock()
					? "message.railwayvehicleaddon.signal.mode_auto" : "message.railwayvehicleaddon.signal.mode_redstone"), true);
		}
		return net.minecraft.world.InteractionResult.SUCCESS;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		// 灯火を設置したプレイヤーの方へ向ける
		BlockState state = defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
		return state.setValue(ASPECT, aspectFor(ctx.getLevel().getBestNeighborSignal(ctx.getClickedPos())));
	}

	public static int aspectFor(int power) {
		return power <= 0 ? 0 : power < 8 ? 1 : 2;
	}

	@Override
	protected void neighborChanged(BlockState state, Level world, BlockPos pos, Block sourceBlock,
								  Orientation wireOrientation, boolean notify) {
		super.neighborChanged(state, world, pos, sourceBlock, wireOrientation, notify);
		if (world instanceof ServerLevel) {
			int aspect = aspectFor(world.getBestNeighborSignal(pos));
			if (world.getBlockEntity(pos) instanceof SignalDeviceBlockEntity entity) {
				// 自動閉塞: 在線で決めた現示とレッドストーンの入力の、厳しい方
				aspect = Math.max(aspect, entity.autoAspect());
			}
			if (state.getValue(ASPECT) != aspect) {
				world.setBlock(pos, state.setValue(ASPECT, aspect), Block.UPDATE_CLIENTS);
			}
		}
	}
}
