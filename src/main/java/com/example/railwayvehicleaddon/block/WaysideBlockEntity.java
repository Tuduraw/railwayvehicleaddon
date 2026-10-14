package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.operation.OperationState;
import com.example.railwayvehicleaddon.operation.Timetable;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 停止位置目標・列車番号検知器の状態。線路への連結(WaysideLink)と名前、列車番号検知器の設定と出力を持つ。
 */
public class WaysideBlockEntity extends BlockEntity {
	/** 列車番号検知器が在線を調べる間隔(tick) */
	private static final int DETECT_INTERVAL = 2;
	/** 地上子の索引へ登録し直す間隔(tick) */
	private static final int REGISTER_INTERVAL = 20;
	/** 列車番号検知器: 編成の在線範囲が検知点にかかっているとみなす余裕(ブロック) */
	private static final double DETECT_TOLERANCE = 0.3;

	/** 列車番号検知器が出力する値 */
	public enum DetectorMode {
		/** 運行表の「経路 (検知器の名前): 番号」 */
		ROUTE,
		/** 運行表の「種別」 */
		TYPE,
		/** 運行表の次の駅の番線 */
		PLATFORM;

		public DetectorMode next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	private final WaysideLink link = new WaysideLink();
	private String name = "";
	private DetectorMode mode = DetectorMode.ROUTE;
	/** 列車番号検知器: 正面に向かって進む列車だけに反応する */
	private boolean directional;
	/** 列車番号検知器: 列車が去った後も、次の列車が来るまで最後の値を出し続ける */
	private boolean hold;
	private int timer;
	private int registerTimer;

	public WaysideBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.WAYSIDE_ENTITY, pos, state);
	}

	public WaysideLink link() {
		return this.link;
	}

	public String name() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name == null ? "" : name.trim();
		changed();
	}

	public DetectorMode mode() {
		return this.mode;
	}

	public boolean directional() {
		return this.directional;
	}

	public boolean hold() {
		return this.hold;
	}

	public void cycleMode() {
		this.mode = this.mode.next();
		changed();
	}

	/** 方向(両方向/片方向)と保持(通過中だけ/保持)の4通りを順に切り替える。 */
	public void cycleOptions() {
		int code = (this.directional ? 1 : 0) + (this.hold ? 2 : 0);
		code = (code + 1) % 4;
		this.directional = (code & 1) != 0;
		this.hold = (code & 2) != 0;
		changed();
	}

	private boolean isDetector() {
		return getCachedState().getBlock() instanceof TrainNumberDetectorBlock;
	}

	private void changed() {
		markDirty();
		if (this.world instanceof ServerWorld serverWorld) {
			register(serverWorld);
			BlockState state = getCachedState();
			this.world.updateListeners(this.pos, state, state, 3);
		}
	}

	public void relink(ServerWorld world) {
		this.link.link(world, this.pos, getCachedState().get(WaysideBlock.FACING));
		markDirty();
		register(world);
	}

	/** 停止位置目標を地上子の索引へ登録する(列車番号検知器は在線表を見るだけなので登録しない)。 */
	private void register(ServerWorld world) {
		OperationState state = OperationState.get(world);
		if (isDetector() || !this.link.linked()) {
			state.removeWayside(this.pos);
			return;
		}
		Timetable.Stop parsed = Timetable.parseMarkerName(this.name);
		state.putWayside(new OperationState.Wayside(this.pos.toImmutable(), OperationState.WaysideKind.STOP_MARKER,
				this.link.segmentId(), this.link.s(), this.link.dir(), parsed.station(), parsed.platform(), world.getTime()));
	}

	public static void tick(World world, BlockPos pos, BlockState state, WaysideBlockEntity entity) {
		if (!(world instanceof ServerWorld serverWorld)) {
			return;
		}
		if (entity.link.check(serverWorld, pos, state.get(WaysideBlock.FACING))) {
			entity.markDirty();
			entity.register(serverWorld);
		}
		if (++entity.registerTimer >= REGISTER_INTERVAL) {
			entity.registerTimer = 0;
			entity.register(serverWorld);
		}
		if (entity.isDetector() && ++entity.timer >= DETECT_INTERVAL) {
			entity.timer = 0;
			entity.detect(serverWorld, state);
		}
	}

	/** 列車番号検知器: 検知点にかかっている編成の値を出力する。 */
	private void detect(ServerWorld world, BlockState state) {
		int current = state.get(TrainNumberDetectorBlock.POWER);
		int value = current;
		boolean found = false;
		if (this.link.linked()) {
			for (OperationState.Span span : OperationState.get(world).spansOn(this.link.segmentId(), world.getTime())) {
				if (this.link.s() < span.s0() - DETECT_TOLERANCE || this.link.s() > span.s1() + DETECT_TOLERANCE) {
					continue;
				}
				if (this.directional && span.travelDir() != this.link.dir()) {
					continue;
				}
				found = true;
				value = valueFor(span.consist().controller());
				break;
			}
		}
		if (!found && !this.hold) {
			value = 0;
		}
		if (value != current) {
			world.setBlockState(this.pos, state.with(TrainNumberDetectorBlock.POWER, value), 3);
		}
	}

	private int valueFor(RailVehicleEntity controller) {
		Timetable timetable = controller == null ? null : controller.getTimetable();
		if (timetable == null) {
			return 0;
		}
		int v = switch (this.mode) {
			case ROUTE -> timetable.routeValue(this.name);
			case TYPE -> timetable.type();
			case PLATFORM -> {
				Timetable.Stop next = controller.nextStop();
				yield next == null ? 0 : next.platform();
			}
		};
		return Math.max(0, Math.min(15, v));
	}

	/** ブロックが壊された・置き換えられたら、地上子の索引(と保存)から外す。チャンクの読み込みが外れただけなら残す。 */
	@Override
	public void onBlockReplaced(BlockPos pos, BlockState oldState) {
		super.onBlockReplaced(pos, oldState);
		if (this.world instanceof ServerWorld serverWorld) {
			OperationState.get(serverWorld).removeWayside(pos);
		}
	}

	@Override
	protected void readData(ReadView view) {
		super.readData(view);
		this.link.read(view);
		this.name = view.getString("Name", "");
		int m = view.getInt("Mode", 0);
		this.mode = DetectorMode.values()[Math.max(0, Math.min(DetectorMode.values().length - 1, m))];
		this.directional = view.getBoolean("Directional", false);
		this.hold = view.getBoolean("Hold", false);
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		this.link.write(view);
		view.putString("Name", this.name);
		view.putInt("Mode", this.mode.ordinal());
		view.putBoolean("Directional", this.directional);
		view.putBoolean("Hold", this.hold);
	}

	@Override
	public Packet<ClientPlayPacketListener> toUpdatePacket() {
		return BlockEntityUpdateS2CPacket.create(this);
	}

	@Override
	public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registries) {
		return createNbt(registries);
	}
}
