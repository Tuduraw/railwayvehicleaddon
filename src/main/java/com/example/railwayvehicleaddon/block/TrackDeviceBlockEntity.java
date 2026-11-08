package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.track.TrackManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 線路装置の状態。連結先(分岐器ノード・転車台/遷車台・区間のID)と、レッドストーン入力の前回値を持つ。
 * 連結先はクライアントにも同期し、測量ツールで装置を狙ったときに連結先を線で示す。
 */
public class TrackDeviceBlockEntity extends BlockEntity implements TrackLinkable {
	/** 在線検知器の判定間隔(tick) */
	private static final int DETECT_INTERVAL = 4;

	private long targetId = -1L;
	private int lastPower;
	private int detectTimer;
	/** 分岐器の装置: 連結した分岐器が鎖錠中か(コンパレーター出力。保存しない) */
	private boolean switchLocked;

	public TrackDeviceBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.DEVICE_ENTITY, pos, state);
	}

	public DeviceKind kind() {
		return getBlockState().getBlock() instanceof TrackDeviceBlock block ? block.kind() : DeviceKind.MANUAL_SWITCH;
	}

	@Override
	public DeviceKind.TargetType linkTarget() {
		return kind().target();
	}

	@Override
	public long targetId() {
		return this.targetId;
	}

	@Override
	public void setTargetId(long targetId) {
		this.targetId = targetId;
		setChanged();
		if (this.level != null) {
			BlockState state = getBlockState();
			this.level.sendBlockUpdated(this.worldPosition, state, state, 3);
		}
	}

	public int lastPower() {
		return this.lastPower;
	}

	public void setLastPower(int power) {
		if (this.lastPower != power) {
			this.lastPower = power;
			setChanged();
		}
	}

	public boolean switchLocked() {
		return this.switchLocked;
	}

	/**
	 * 在線検知器: 一定間隔で連結区間の在線を調べ、出力(POWERED)を更新する。
	 * 分岐器の装置: 一定間隔で分岐器の鎖錠を調べ、変わったらコンパレーターへ知らせる。
	 */
	public static void tick(Level world, BlockPos pos, BlockState state, TrackDeviceBlockEntity entity) {
		if (!(world instanceof ServerLevel serverWorld)) {
			return;
		}
		if (++entity.detectTimer < DETECT_INTERVAL) {
			return;
		}
		entity.detectTimer = 0;
		if (entity.kind().target() == DeviceKind.TargetType.SWITCH) {
			boolean locked = entity.targetId >= 0 && TrackManager.network(serverWorld).isSwitch(entity.targetId)
					&& TrackManager.isSwitchLocked(serverWorld, entity.targetId);
			if (locked != entity.switchLocked) {
				entity.switchLocked = locked;
				world.updateNeighbourForOutputSignal(pos, state.getBlock());
			}
			return;
		}
		if (!entity.kind().output()) {
			return;
		}
		boolean occupied = entity.targetId >= 0 && TrackManager.isOccupied(serverWorld, entity.targetId);
		if (state.getValue(TrackDeviceBlock.POWERED) != occupied) {
			// NOTIFY_ALLで周囲のブロック(レッドストーンダスト等)にも出力の変化を伝える
			world.setBlock(pos, state.setValue(TrackDeviceBlock.POWERED, occupied), 3);
		}
	}

	@Override
	protected void loadAdditional(ValueInput view) {
		super.loadAdditional(view);
		this.targetId = view.getLongOr("Target", -1L);
		this.lastPower = view.getIntOr("LastPower", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput view) {
		super.saveAdditional(view);
		view.putLong("Target", this.targetId);
		view.putInt("LastPower", this.lastPower);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}
}
