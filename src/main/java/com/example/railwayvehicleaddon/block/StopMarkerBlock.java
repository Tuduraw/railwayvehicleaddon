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
 * 向きは問わない(どちら向きに進む列車にも効く)。
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
	protected boolean usesDirection() {
		return false;
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

	/** 素手で右クリック: 名前の編集画面を開く。スニーク+右クリック: 名前と連結先を表示する。 */
	@Override
	protected void useEmptyHand(ServerWorld world, WaysideBlockEntity entity, PlayerEntity player, boolean sneaking) {
		if (!sneaking && player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(serverPlayer,
					new com.example.railwayvehicleaddon.network.StopMarkerEditPayload(entity.getPos(), entity.name()));
			return;
		}
		player.sendMessage(describe(world, entity), true);
	}

	/** 編集画面で決めた名前を付ける(サーバー側。プレイヤーが近く(8ブロック以内)にいて、そこが停止位置目標なら)。 */
	public static void rename(net.minecraft.server.network.ServerPlayerEntity player, BlockPos pos, String name) {
		if (!(player.getEntityWorld() instanceof ServerWorld world) || player.squaredDistanceTo(pos.toCenterPos()) > 8.0 * 8.0
				|| !(world.getBlockState(pos).getBlock() instanceof StopMarkerBlock)
				|| !(world.getBlockEntity(pos) instanceof WaysideBlockEntity entity)) {
			return;
		}
		String clean = name == null ? "" : name.replace('　', ' ').trim();
		if (clean.length() > Timetable.MAX_NAME) {
			clean = clean.substring(0, Timetable.MAX_NAME);
		}
		entity.setName(clean);
		player.sendMessage(((StopMarkerBlock) world.getBlockState(pos).getBlock()).describe(world, entity), true);
	}
}
