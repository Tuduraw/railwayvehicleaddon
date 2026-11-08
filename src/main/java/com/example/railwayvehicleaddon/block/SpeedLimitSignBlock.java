package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

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
	public static final MapCodec<SpeedLimitSignBlock> CODEC = createCodec(SpeedLimitSignBlock::new);
	/** 制限速度÷5(0は制限解除) */
	public static final IntProperty SPEED = IntProperty.of("speed", 0, 60);
	private static final VoxelShape SHAPE = Block.createCuboidShape(2.0, 0.0, 6.0, 14.0, 16.0, 10.0);

	public SpeedLimitSignBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(SPEED, 12));
	}

	/** 制限速度(km/h。0は制限解除)。 */
	public static int kmh(BlockState state) {
		return state.getBlock() instanceof SpeedLimitSignBlock ? state.get(SPEED) * 5 : 0;
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		super.appendProperties(builder);
		builder.add(SPEED);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	protected boolean usesDirection() {
		return true;
	}

	@Override
	public void onPlaced(net.minecraft.world.World world, BlockPos pos, BlockState state, net.minecraft.entity.LivingEntity placer,
						 net.minecraft.item.ItemStack itemStack) {
		Text custom = itemStack.getCustomName();
		if (custom != null && world instanceof ServerWorld) {
			int value = parseKmh(custom.getString());
			if (value >= 0) {
				state = state.with(SPEED, value / 5);
				world.setBlockState(pos, state, Block.NOTIFY_ALL);
			}
		}
		super.onPlaced(world, pos, state, placer, itemStack);
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
	protected Text describe(ServerWorld world, WaysideBlockEntity entity) {
		int value = kmh(entity.getCachedState());
		Text speed = value > 0 ? Text.translatable("message.railwayvehicleaddon.speed_limit.value", value)
				: Text.translatable("message.railwayvehicleaddon.speed_limit.release");
		return Text.empty().append(speed).append(" / ").append(entity.link().describe(world));
	}

	@Override
	protected void useEmptyHand(ServerWorld world, WaysideBlockEntity entity, PlayerEntity player, boolean sneaking) {
		BlockState state = entity.getCachedState();
		int step = state.get(SPEED) + (sneaking ? -1 : 1);
		step = Math.floorMod(step, 61);
		BlockState next = state.with(SPEED, step);
		world.setBlockState(entity.getPos(), next, Block.NOTIFY_ALL);
		entity.refresh(world);
		player.sendMessage(describe(world, entity), true);
	}
}
