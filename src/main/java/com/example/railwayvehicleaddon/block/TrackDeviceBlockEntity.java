package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.track.TrackManager;
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
 * 線路装置の状態。連結先(分岐器ノード・転車台/遷車台・区間のID)と、レッドストーン入力の前回値を持つ。
 * 連結先はクライアントにも同期し、測量ツールで装置を狙ったときに連結先を線で示す。
 */
public class TrackDeviceBlockEntity extends BlockEntity {
	/** 在線検知器の判定間隔(tick) */
	private static final int DETECT_INTERVAL = 4;

	private long targetId = -1L;
	private int lastPower;
	private int detectTimer;

	public TrackDeviceBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.DEVICE_ENTITY, pos, state);
	}

	public DeviceKind kind() {
		return getCachedState().getBlock() instanceof TrackDeviceBlock block ? block.kind() : DeviceKind.MANUAL_SWITCH;
	}

	public long targetId() {
		return this.targetId;
	}

	public void setTargetId(long targetId) {
		this.targetId = targetId;
		markDirty();
		if (this.world != null) {
			BlockState state = getCachedState();
			this.world.updateListeners(this.pos, state, state, 3);
		}
	}

	public int lastPower() {
		return this.lastPower;
	}

	public void setLastPower(int power) {
		if (this.lastPower != power) {
			this.lastPower = power;
			markDirty();
		}
	}

	/** 在線検知器: 一定間隔で連結区間の在線を調べ、出力(POWERED)を更新する。 */
	public static void tick(World world, BlockPos pos, BlockState state, TrackDeviceBlockEntity entity) {
		if (!(world instanceof ServerWorld serverWorld) || !entity.kind().output()) {
			return;
		}
		if (++entity.detectTimer < DETECT_INTERVAL) {
			return;
		}
		entity.detectTimer = 0;
		boolean occupied = entity.targetId >= 0 && TrackManager.isOccupied(serverWorld, entity.targetId);
		if (state.get(TrackDeviceBlock.POWERED) != occupied) {
			// NOTIFY_ALLで周囲のブロック(レッドストーンダスト等)にも出力の変化を伝える
			world.setBlockState(pos, state.with(TrackDeviceBlock.POWERED, occupied), 3);
		}
	}

	@Override
	protected void readData(ReadView view) {
		super.readData(view);
		this.targetId = view.getLong("Target", -1L);
		this.lastPower = view.getInt("LastPower", 0);
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		view.putLong("Target", this.targetId);
		view.putInt("LastPower", this.lastPower);
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
