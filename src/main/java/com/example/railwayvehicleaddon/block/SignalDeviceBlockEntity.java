package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.operation.Interlock;
import com.example.railwayvehicleaddon.operation.OperationState;
import com.example.railwayvehicleaddon.track.TrackManager;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackSegment;
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
	private int registerTimer = 19;
	private boolean linkTried;
	/** 信号機: 自動閉塞(閉塞区間の在線をサーバーで直接調べて現示を決める)。新しく置いた信号機は自動閉塞 */
	private boolean autoBlock;
	/** 自動閉塞で決めた現示(0=進行、1=注意、2=停止) */
	private int autoAspect;
	private int autoTimer;
	/** 自動閉塞で現示を決め直す間隔(tick) */
	private static final int AUTO_INTERVAL = 4;
	/** 自動閉塞で、次の信号機を探す(閉塞区間の長さの)上限 */
	private static final double MAX_SECTION = 2048.0;

	public SignalDeviceBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.SIGNAL_DEVICE_ENTITY, pos, state);
	}

	public WaysideLink link() {
		return this.link;
	}

	public boolean isAutoBlock() {
		return this.autoBlock;
	}

	/** 自動閉塞で決めた現示(自動閉塞でなければ0)。 */
	public int autoAspect() {
		return this.autoBlock ? this.autoAspect : 0;
	}

	public void setAutoBlock(boolean autoBlock) {
		this.autoBlock = autoBlock;
		this.autoAspect = 0;
		this.autoTimer = 0;
		markDirty();
	}

	/**
	 * 自動閉塞の現示: この信号機から、同じ向きの次の信号機まで(閉塞区間。分岐器は今の開通方向に沿って)に
	 * 車両がいれば停止。区間の中の分岐器の分岐区間に車両がいても(開通していない側から入ってくる列車)停止。
	 * いなければ、次の信号機が停止なら注意、そうでなければ進行。
	 */
	private int computeAutoAspect(ServerWorld world) {
		TrackNetwork network = TrackManager.network(world);
		if (!this.link.linked() || network.segment(this.link.segmentId()) == null) {
			return 0;
		}
		OperationState state = OperationState.get(world);
		long now = world.getTime();
		boolean[] occupied = {false};
		BlockPos[] nextSignal = {null};
		BlockPos self = this.pos;
		network.traverse(this.link.segmentId(), this.link.s(), this.link.dir(), MAX_SECTION, new TrackNetwork.PathVisitor() {
			@Override
			public boolean span(TrackSegment segment, double sFrom, double sTo, int dir, double distance) {
				double length = Math.abs(sTo - sFrom);
				double cut = length;
				for (OperationState.Wayside w : state.waysideOn(segment.id(), now)) {
					if (w.kind() != OperationState.WaysideKind.SIGNAL || w.dir() != dir || w.pos().equals(self)) {
						continue;
					}
					double along = (w.s() - sFrom) * dir;
					if (along > 0.5 && along <= length + 1.0e-6 && along < cut) {
						cut = along;
						nextSignal[0] = w.pos();
					}
				}
				double end = sFrom + dir * cut;
				double lo = Math.min(sFrom, end);
				double hi = Math.max(sFrom, end);
				for (OperationState.Span span : state.spansOn(segment.id(), now)) {
					if (span.s1() >= lo && span.s0() <= hi) {
						occupied[0] = true;
						return false;
					}
				}
				return nextSignal[0] == null;
			}

			@Override
			public void node(long nodeId, long fromSegment, double arrivalX, double arrivalZ, double distance) {
				if (!occupied[0] && network.isSwitch(nodeId) && Interlock.occupied(world, network, nodeId, null)) {
					occupied[0] = true;
				}
			}
		});
		if (occupied[0]) {
			return 2;
		}
		if (nextSignal[0] != null && world.isChunkLoaded(nextSignal[0])) {
			BlockState next = world.getBlockState(nextSignal[0]);
			if (next.getBlock() instanceof SignalBlock && next.get(SignalBlock.ASPECT) == 2) {
				return 1;
			}
		}
		return 0;
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
		// 以前の版で置いた信号機(保存に項目が無い)は、これまでどおりレッドストーンで現示を決める
		this.autoBlock = view.getBoolean("AutoBlock", false);
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		this.link.write(view);
		view.putBoolean("LinkTried", this.linkTried);
		view.putBoolean("AutoBlock", this.autoBlock);
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
			if (entity.autoBlock && ++entity.autoTimer >= AUTO_INTERVAL) {
				entity.autoTimer = 0;
				entity.autoAspect = entity.computeAutoAspect(serverWorld);
				int aspect = Math.max(entity.autoAspect, SignalBlock.aspectFor(serverWorld.getReceivedRedstonePower(pos)));
				if (state.get(SignalBlock.ASPECT) != aspect) {
					serverWorld.setBlockState(pos, state.with(SignalBlock.ASPECT, aspect), net.minecraft.block.Block.NOTIFY_LISTENERS);
				}
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
