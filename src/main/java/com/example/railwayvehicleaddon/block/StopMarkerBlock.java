package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.operation.Timetable;
import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

/**
 * 停止位置目標。駅の停止位置(列車の先頭を合わせる位置)を表す。名前は「駅名 番線」(例: 「A駅 1」)。
 * 進路の自動設定では、運行表の次の駅に合う停止位置目標へ向かう進路を開通させる。
 * 列車の先頭が目標の3ブロック手前まで来たら、運行表の次の駅へ進む。
 */
public class StopMarkerBlock extends WaysideBlock {
	public static final MapCodec<StopMarkerBlock> CODEC = createCodec(StopMarkerBlock::new);
	private static final VoxelShape SHAPE = Block.createCuboidShape(3.0, 0.0, 3.0, 13.0, 16.0, 13.0);

	public StopMarkerBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, net.minecraft.util.math.Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	protected Text describe(ServerWorld world, WaysideBlockEntity entity) {
		Timetable.Stop parsed = Timetable.parseMarkerName(entity.name());
		Text name = parsed.station().isEmpty()
				? Text.translatable("message.railwayvehicleaddon.stop_marker.unnamed")
				: Text.translatable("message.railwayvehicleaddon.stop_marker.name", parsed.station(),
				parsed.platform() > 0 ? Text.translatable("message.railwayvehicleaddon.stop_marker.platform", parsed.platform())
						: Text.translatable("message.railwayvehicleaddon.stop_marker.any_platform"));
		return Text.empty().append(name).append(" / ").append(entity.link().describe(world));
	}

	@Override
	protected void useEmptyHand(ServerWorld world, WaysideBlockEntity entity, PlayerEntity player, boolean sneaking) {
		player.sendMessage(describe(world, entity), true);
	}
}
