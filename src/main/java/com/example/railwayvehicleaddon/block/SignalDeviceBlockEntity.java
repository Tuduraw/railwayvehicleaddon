package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.operation.OperationState;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 信号機・踏切遮断機の共通のブロックエンティティ。状態(現示・遮断中)はブロックの状態に持つ。
 * クライアントの描画(灯火・遮断かん)がこの種類のブロックエンティティを探して描くための目印と、
 * 踏切の警報音の再生に使う。信号機は線路の位置へ連結し(WaysideLink)、地上子の索引に登録して
 * 列車のATSに現示を伝える。
 */
public class SignalDeviceBlockEntity extends BlockEntity {
	/** 警報音の間隔(tick) */
	private static final int BELL_INTERVAL = 12;
	private int bellTimer;
	private final WaysideLink link = new WaysideLink();
	private int registerTimer;
	private boolean linkTried;

	public SignalDeviceBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.SIGNAL_DEVICE_ENTITY, pos, state);
	}

	public WaysideLink link() {
		return this.link;
	}

	/** 信号機: 近くの線路へ連結し直す(設置時)。 */
	public void relink(ServerWorld world) {
		this.linkTried = true;
		this.link.link(world, this.pos, getCachedState().get(SignalBlock.FACING));
		markDirty();
		register(world);
	}

	private void register(ServerWorld world) {
		OperationState state = OperationState.get(world);
		if (!this.link.linked()) {
			state.removeWayside(this.pos);
			return;
		}
		state.putWayside(new OperationState.Wayside(this.pos.toImmutable(), OperationState.WaysideKind.SIGNAL,
				this.link.segmentId(), this.link.s(), this.link.dir(), "", 0, world.getTime()));
	}

	/** チャンクの読み込みが外れた・壊された: 地上子の索引から外す(信号機は保存しない。読み込まれたら登録し直す)。 */
	@Override
	public void markRemoved() {
		super.markRemoved();
		if (this.world instanceof ServerWorld serverWorld) {
			OperationState.get(serverWorld).unloadWayside(this.pos);
		}
	}

	@Override
	protected void readData(ReadView view) {
		super.readData(view);
		this.link.read(view);
		this.linkTried = view.getBoolean("LinkTried", false);
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		this.link.write(view);
		view.putBoolean("LinkTried", this.linkTried);
	}

	/**
	 * 信号機: 線路への連結を保ち、地上子の索引へ定期的に登録する(以前の版で置いた信号機も、
	 * 初めての処理で近くの線路へ連結する)。
	 * 踏切: 遮断中は一定間隔で警報音を鳴らす。
	 */
	public static void tick(World world, BlockPos pos, BlockState state, SignalDeviceBlockEntity entity) {
		if (world instanceof ServerWorld serverWorld && state.getBlock() instanceof SignalBlock) {
			if (!entity.linkTried) {
				entity.relink(serverWorld);
			} else if (entity.link.check(serverWorld, pos, state.get(SignalBlock.FACING))) {
				entity.markDirty();
				entity.register(serverWorld);
			}
			if (++entity.registerTimer >= 20) {
				entity.registerTimer = 0;
				entity.register(serverWorld);
			}
			return;
		}
		if (world.isClient() || !(state.getBlock() instanceof CrossingGateBlock) || !state.get(CrossingGateBlock.POWERED)) {
			entity.bellTimer = 0;
			return;
		}
		if (entity.bellTimer-- <= 0) {
			entity.bellTimer = BELL_INTERVAL;
			world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.BLOCKS, 1.2f, 1.5f);
		}
	}
}
