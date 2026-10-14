package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

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
	public static final MapCodec<TrainNumberDetectorBlock> CODEC = createCodec(TrainNumberDetectorBlock::new);
	public static final IntProperty POWER = Properties.POWER;

	public TrainNumberDetectorBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(POWER, 0));
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		super.appendProperties(builder);
		builder.add(POWER);
	}

	@Override
	protected boolean emitsRedstonePower(BlockState state) {
		return true;
	}

	@Override
	protected int getWeakRedstonePower(BlockState state, BlockView world, BlockPos pos, Direction direction) {
		return state.get(POWER);
	}

	@Override
	protected Text describe(ServerWorld world, WaysideBlockEntity entity) {
		Text name = entity.name().isEmpty() ? Text.translatable("message.railwayvehicleaddon.train_detector.unnamed")
				: Text.literal(entity.name());
		Text mode = Text.translatable("message.railwayvehicleaddon.train_detector.mode."
				+ entity.mode().name().toLowerCase(java.util.Locale.ROOT));
		Text direction = Text.translatable(entity.directional()
				? "message.railwayvehicleaddon.train_detector.one_way" : "message.railwayvehicleaddon.train_detector.both_ways");
		Text hold = Text.translatable(entity.hold()
				? "message.railwayvehicleaddon.train_detector.hold" : "message.railwayvehicleaddon.train_detector.passing");
		return Text.translatable("message.railwayvehicleaddon.train_detector.status", name, mode, direction, hold)
				.append(" / ").append(entity.link().describe(world));
	}

	@Override
	protected void useEmptyHand(ServerWorld world, WaysideBlockEntity entity, PlayerEntity player, boolean sneaking) {
		if (sneaking) {
			entity.cycleOptions();
		} else {
			entity.cycleMode();
		}
		player.sendMessage(describe(world, entity), true);
	}
}
