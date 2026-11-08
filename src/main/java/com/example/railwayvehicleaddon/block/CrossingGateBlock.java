package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 踏切遮断機。レッドストーン入力がある間は遮断かんを下ろし、警報灯を交互に点滅させて警報音を鳴らす。
 * 在線検知器の出力をつなげば、列車が来ると遮断する。警報灯は設置したプレイヤーの方を向き、
 * 遮断かんは(警報灯の方から見て)右側へ4ブロック伸びる。遮断かんと警報灯の点滅はクライアントの
 * 描画(DeviceOverlayRenderer)で動かす。
 */
public class CrossingGateBlock extends BaseEntityBlock {
	public static final MapCodec<CrossingGateBlock> CODEC = simpleCodec(CrossingGateBlock::new);
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
	private static final VoxelShape SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 16.0, 12.0);

	public CrossingGateBlock(Properties settings) {
		super(settings);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(POWERED, false));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, POWERED);
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
	protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite())
				.setValue(POWERED, ctx.getLevel().getBestNeighborSignal(ctx.getClickedPos()) > 0);
	}

	@Override
	protected void neighborChanged(BlockState state, Level world, BlockPos pos, Block sourceBlock,
								  Orientation wireOrientation, boolean notify) {
		super.neighborChanged(state, world, pos, sourceBlock, wireOrientation, notify);
		if (world instanceof ServerLevel) {
			boolean powered = world.getBestNeighborSignal(pos) > 0;
			if (state.getValue(POWERED) != powered) {
				world.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
			}
		}
	}
}
