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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 速度制限標識。線路に連結し、正面に向かって進む列車に効く。その標識から、同じ向きの次の標識まで
 * (次が「制限解除」なら解除まで)、列車の速さを制限速度までにする(ATOは手前から減速し、ATSは超えた列車に
 * ブレーキを掛ける)。制限が上がるのは、列車の最後尾が標識を過ぎてから。
 *
 * <ul>
 *   <li>制限速度は5km/h刻み(5〜300km/h)。0は「制限解除」</li>
 *   <li>右クリック(素手): 5km/h上げる。スニーク+右クリック: 5km/h下げる</li>
 *   <li>金床で数字(例:「60」)の名前を付けて置くと、その制限速度で置ける</li>
 *   <li>板に制限速度の数字を表示する(解除は斜線)</li>
 * </ul>
 */
public class SpeedLimitSignBlock extends WaysideBlock {
	public static final MapCodec<SpeedLimitSignBlock> CODEC = simpleCodec(SpeedLimitSignBlock::new);
	/** 制限速度÷5(0は制限解除) */
	public static final IntegerProperty SPEED = IntegerProperty.create("speed", 0, 60);
	private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 6.0, 14.0, 16.0, 10.0);

	public SpeedLimitSignBlock(Properties settings) {
		super(settings);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(SPEED, 12));
	}

	/** 制限速度(km/h。0は制限解除)。 */
	public static int kmh(BlockState state) {
		return state.getBlock() instanceof SpeedLimitSignBlock ? state.getValue(SPEED) * 5 : 0;
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(SPEED);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected boolean usesDirection() {
		return true;
	}

	@Override
	public void setPlacedBy(net.minecraft.world.level.Level world, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer,
						 net.minecraft.world.item.ItemStack itemStack) {
		Component custom = itemStack.getCustomName();
		if (custom != null && world instanceof ServerLevel) {
			int value = parseKmh(custom.getString());
			if (value >= 0) {
				state = state.setValue(SPEED, value / 5);
				world.setBlock(pos, state, Block.UPDATE_ALL);
			}
		}
		super.setPlacedBy(world, pos, state, placer, itemStack);
	}

	/** 名前(「60」「60km/h」「解除」)から制限速度。読めなければ-1。 */
	static int parseKmh(String name) {
		String t = name == null ? "" : name.trim();
		if (t.contains("解除") || t.equalsIgnoreCase("release") || t.equalsIgnoreCase("end")) {
			return 0;
		}
		StringBuilder digits = new StringBuilder();
		for (char c : t.toCharArray()) {
			if (c >= '０' && c <= '９') {
				c = (char) ('0' + (c - '０'));
			}
			if (c >= '0' && c <= '9') {
				digits.append(c);
			} else if (!digits.isEmpty()) {
				break;
			}
		}
		if (digits.isEmpty() || digits.length() > 3) {
			return -1;
		}
		int v = Integer.parseInt(digits.toString());
		v = Math.round(v / 5.0f) * 5;
		return Math.max(0, Math.min(300, v));
	}

	@Override
	protected Component describe(ServerLevel world, WaysideBlockEntity entity) {
		int value = kmh(entity.getBlockState());
		Component speed = value > 0 ? Component.translatable("message.railwayvehicleaddon.speed_limit.value", value)
				: Component.translatable("message.railwayvehicleaddon.speed_limit.release");
		return Component.empty().append(speed).append(" / ").append(entity.link().describe(world));
	}

	@Override
	protected void useEmptyHand(ServerLevel world, WaysideBlockEntity entity, Player player, boolean sneaking) {
		BlockState state = entity.getBlockState();
		int step = state.getValue(SPEED) + (sneaking ? -1 : 1);
		step = Math.floorMod(step, 61);
		BlockState next = state.setValue(SPEED, step);
		world.setBlock(entity.getBlockPos(), next, Block.UPDATE_ALL);
		entity.refresh(world);
		player.displayClientMessage(describe(world, entity), true);
	}
}
