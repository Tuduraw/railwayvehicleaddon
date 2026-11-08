package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.operation.Timetable;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 停止位置目標。駅の停止位置(列車の先頭を合わせる位置)を表す。名前は「駅名 番線」(例: 「A駅 1」)。
 * 進路の自動設定では、運行表の次の駅に合う停止位置目標へ向かう進路を開通させる。
 * 列車の先頭が目標の3ブロック手前まで来たら、運行表の次の駅へ進む。
 * 向きは問わない(どちら向きに進む列車にも効く)。
 */
public class StopMarkerBlock extends WaysideBlock {
	public static final MapCodec<StopMarkerBlock> CODEC = simpleCodec(StopMarkerBlock::new);
	private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 16.0, 13.0);

	public StopMarkerBlock(Properties settings) {
		super(settings);
		registerDefaultState(defaultBlockState().setValue(FACING, net.minecraft.core.Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected boolean usesDirection() {
		return false;
	}

	@Override
	protected Component describe(ServerLevel world, WaysideBlockEntity entity) {
		Timetable.Stop parsed = Timetable.parseMarkerName(entity.name());
		Component name = parsed.station().isEmpty()
				? Component.translatable("message.railwayvehicleaddon.stop_marker.unnamed")
				: Component.translatable("message.railwayvehicleaddon.stop_marker.name", parsed.station(),
				parsed.platform() > 0 ? Component.translatable("message.railwayvehicleaddon.stop_marker.platform", parsed.platform())
						: Component.translatable("message.railwayvehicleaddon.stop_marker.any_platform"));
		return Component.empty().append(name).append(" / ").append(entity.link().describe(world));
	}

	/** 素手で右クリック: 名前の編集画面を開く。スニーク+右クリック: 名前と連結先を表示する。 */
	@Override
	protected void useEmptyHand(ServerLevel world, WaysideBlockEntity entity, Player player, boolean sneaking) {
		if (!sneaking && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(serverPlayer,
					new com.example.railwayvehicleaddon.network.StopMarkerEditPayload(entity.getBlockPos(), entity.name()));
			return;
		}
		player.displayClientMessage(describe(world, entity), true);
	}

	/** 編集画面で決めた名前を付ける(サーバー側。プレイヤーが近く(8ブロック以内)にいて、そこが停止位置目標なら)。 */
	public static void rename(net.minecraft.server.level.ServerPlayer player, BlockPos pos, String name) {
		if (!(player.level() instanceof ServerLevel world) || player.distanceToSqr(pos.getCenter()) > 8.0 * 8.0
				|| !(world.getBlockState(pos).getBlock() instanceof StopMarkerBlock)
				|| !(world.getBlockEntity(pos) instanceof WaysideBlockEntity entity)) {
			return;
		}
		String clean = name == null ? "" : name.replace('　', ' ').trim();
		if (clean.length() > Timetable.MAX_NAME) {
			clean = clean.substring(0, Timetable.MAX_NAME);
		}
		entity.setName(clean);
		player.displayClientMessage(((StopMarkerBlock) world.getBlockState(pos).getBlock()).describe(world, entity), true);
	}
}
