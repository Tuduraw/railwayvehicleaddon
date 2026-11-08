package com.example.railwayvehicleaddon.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

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
public class SignalBlock extends BlockWithEntity {
	public static final MapCodec<SignalBlock> CODEC = createCodec(SignalBlock::new);
	public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;
	/** 0=進行、1=注意、2=停止 */
	public static final IntProperty ASPECT = IntProperty.of("aspect", 0, 2);
	private static final VoxelShape SHAPE = Block.createCuboidShape(4.0, 0.0, 4.0, 12.0, 16.0, 12.0);

	public SignalBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(ASPECT, 0));
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, ASPECT);
	}

	@Override
	protected net.minecraft.block.BlockRenderType getRenderType(BlockState state) {
		return net.minecraft.block.BlockRenderType.MODEL;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new SignalDeviceBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient() ? null : validateTicker(type, ModBlocks.SIGNAL_DEVICE_ENTITY, SignalDeviceBlockEntity::tick);
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (world instanceof ServerWorld serverWorld && world.getBlockEntity(pos) instanceof SignalDeviceBlockEntity entity) {
			entity.setAutoBlock(true);
			entity.relink(serverWorld);
			if (placer instanceof PlayerEntity player) {
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.signal.placed", entity.link().describe(serverWorld)), false);
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.signal.mode_auto"), false);
				if (entity.link().ambiguous(serverWorld, state.get(FACING))) {
					player.sendMessage(Text.translatable("message.railwayvehicleaddon.wayside.ambiguous"), false);
				}
			}
		}
	}

	@Override
	protected net.minecraft.util.ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos,
															PlayerEntity player, net.minecraft.util.Hand hand,
															net.minecraft.util.hit.BlockHitResult hit) {
		if (stack.isOf(com.example.railwayvehicleaddon.item.ModItems.SURVEY_TOOL)) {
			return net.minecraft.util.ActionResult.PASS;
		}
		return super.onUseWithItem(stack, state, world, pos, player, hand, hit);
	}

	/**
	 * 素手で右クリック: 今の方式(自動閉塞 / レッドストーン)を表示する。スニーク+右クリック: 方式を切り替える。
	 * 自動閉塞でも、レッドストーンの入力があれば、入力の現示と厳しい方になる。
	 */
	@Override
	protected net.minecraft.util.ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
													net.minecraft.util.hit.BlockHitResult hit) {
		if (world instanceof ServerWorld serverWorld && world.getBlockEntity(pos) instanceof SignalDeviceBlockEntity entity) {
			if (player.isSneaking()) {
				entity.setAutoBlock(!entity.isAutoBlock());
				int aspect = aspectFor(serverWorld.getReceivedRedstonePower(pos));
				if (state.get(ASPECT) != aspect) {
					serverWorld.setBlockState(pos, state.with(ASPECT, aspect), Block.NOTIFY_LISTENERS);
				}
			}
			player.sendMessage(Text.translatable(entity.isAutoBlock()
					? "message.railwayvehicleaddon.signal.mode_auto" : "message.railwayvehicleaddon.signal.mode_redstone"), true);
		}
		return net.minecraft.util.ActionResult.SUCCESS;
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		// 灯火を設置したプレイヤーの方へ向ける
		BlockState state = getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
		return state.with(ASPECT, aspectFor(ctx.getWorld().getReceivedRedstonePower(ctx.getBlockPos())));
	}

	public static int aspectFor(int power) {
		return power <= 0 ? 0 : power < 8 ? 1 : 2;
	}

	@Override
	protected void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock,
								  WireOrientation wireOrientation, boolean notify) {
		super.neighborUpdate(state, world, pos, sourceBlock, wireOrientation, notify);
		if (world instanceof ServerWorld) {
			int aspect = aspectFor(world.getReceivedRedstonePower(pos));
			if (world.getBlockEntity(pos) instanceof SignalDeviceBlockEntity entity) {
				// 自動閉塞: 在線で決めた現示とレッドストーンの入力の、厳しい方
				aspect = Math.max(aspect, entity.autoAspect());
			}
			if (state.get(ASPECT) != aspect) {
				world.setBlockState(pos, state.with(ASPECT, aspect), Block.NOTIFY_LISTENERS);
			}
		}
	}
}
