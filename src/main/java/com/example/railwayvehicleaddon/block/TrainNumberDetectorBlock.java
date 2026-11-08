package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * 列車番号検知器(レッドストーン出力)。連結した線路の点(検知点)に編成がかかっている間、
 * その編成の運行表から決まる値(0〜15)を出力する。値は編成ごとに1つで、車両と車両の間が
 * 検知点の上にあっても途切れない。出力を転てつ機につなげば、列車ごとに分岐器の向きを変えられる。
 *
 * <ul>
 *   <li>右クリック(素手): 出力する値を切り替える(経路番号 → 種別 → 次の駅の番線)</li>
 *   <li>スニーク+右クリック(素手): 反応する向き(両方向/正面へ進む列車だけ)と、列車が去った後の出力
 *       (0に戻す/次の列車まで保持)を切り替える</li>
 *   <li>名前(経路番号を引く名前)は、金床で名前を付けて置くか、名前を付けた名札で右クリックして設定する</li>
 * </ul>
 */
public class TrainNumberDetectorBlock extends WaysideBlock {
	public static final MapCodec<TrainNumberDetectorBlock> CODEC = simpleCodec(TrainNumberDetectorBlock::new);
	public static final IntegerProperty POWER = BlockStateProperties.POWER;

	public TrainNumberDetectorBlock(Properties settings) {
		super(settings);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(POWER, 0));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(POWER);
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return true;
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter world, BlockPos pos, Direction direction) {
		return state.getValue(POWER);
	}

	@Override
	protected Component describe(ServerLevel world, WaysideBlockEntity entity) {
		Component name = entity.name().isEmpty() ? Component.translatable("message.railwayvehicleaddon.train_detector.unnamed")
				: Component.literal(entity.name());
		Component mode = Component.translatable("message.railwayvehicleaddon.train_detector.mode."
				+ entity.mode().name().toLowerCase(java.util.Locale.ROOT));
		Component direction = Component.translatable(entity.directional()
				? "message.railwayvehicleaddon.train_detector.one_way" : "message.railwayvehicleaddon.train_detector.both_ways");
		Component hold = Component.translatable(entity.hold()
				? "message.railwayvehicleaddon.train_detector.hold" : "message.railwayvehicleaddon.train_detector.passing");
		return Component.translatable("message.railwayvehicleaddon.train_detector.status", name, mode, direction, hold)
				.append(" / ").append(entity.link().describe(world));
	}

	@Override
	protected void useEmptyHand(ServerLevel world, WaysideBlockEntity entity, Player player, boolean sneaking) {
		if (sneaking) {
			entity.cycleOptions();
		} else {
			entity.cycleMode();
		}
		player.displayClientMessage(describe(world, entity), true);
	}
}
