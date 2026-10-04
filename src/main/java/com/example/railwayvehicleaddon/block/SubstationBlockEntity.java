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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 変電所。石炭・木炭(石炭ブロックも可)をくべて発電し、連結した線路区間が属する給電区画
 * (つながった電化区間のまとまり)に、燃えている間だけ供給能力(容量)を提供する。
 * 発電の有無は在線検知器などと同じく「燃えているか」の二値で、発電量そのものは持たない
 * (鉄道模型のような気軽さを優先し、電圧降下や距離による損失は扱わない)。
 *
 * <p>燃えている変電所は、ワールドごとに{@link #activeIn}で取れるよう自分自身を登録する
 * (ブロックエンティティをチャンクごとに探すより軽いため)。チャンクが即座にアンロードされた
 *場合に登録から外れないまま残ることがあるが、アンロード中はtick()が呼ばれないため実害はない。
 */
public class SubstationBlockEntity extends BlockEntity implements TrackLinkable {
	/** 燃えている変電所1つが給電区画に提供する容量(同時に力行できる電車の数の目安) */
	public static final int CAPACITY = 3;
	/** 石炭・木炭1個、石炭ブロック1個の燃焼時間(tick)。バニラのかまどと同じ値 */
	private static final int COAL_BURN_TIME = 1600;
	private static final int COAL_BLOCK_BURN_TIME = COAL_BURN_TIME * 10;
	/** 燃料を溜め込みすぎないための上限(石炭ブロック10個分) */
	private static final int MAX_BURN_TIME = COAL_BLOCK_BURN_TIME * 10;

	private static final Set<SubstationBlockEntity> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());

	private long targetId = -1L;
	private int burnTime;

	public SubstationBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.SUBSTATION_ENTITY, pos, state);
	}

	@Override
	public DeviceKind.TargetType linkTarget() {
		return DeviceKind.TargetType.SEGMENT;
	}

	@Override
	public long targetId() {
		return this.targetId;
	}

	@Override
	public void setTargetId(long targetId) {
		this.targetId = targetId;
		markDirty();
	}

	public boolean isActive() {
		return this.burnTime > 0;
	}

	public int burnTime() {
		return this.burnTime;
	}

	/** 石炭・木炭・石炭ブロックを1個くべる。くべられなければfalse。 */
	public boolean addFuel(int amount) {
		if (this.burnTime >= MAX_BURN_TIME) {
			return false;
		}
		this.burnTime = Math.min(MAX_BURN_TIME, this.burnTime + amount);
		markDirty();
		return true;
	}

	public static int coalBurnTime() {
		return COAL_BURN_TIME;
	}

	public static int coalBlockBurnTime() {
		return COAL_BLOCK_BURN_TIME;
	}

	public static void tick(World world, BlockPos pos, BlockState state, SubstationBlockEntity entity) {
		if (world.isClient()) {
			return;
		}
		if (entity.burnTime > 0) {
			entity.burnTime--;
			ACTIVE.add(entity);
			if (entity.burnTime == 0) {
				entity.markDirty();
			}
		} else {
			ACTIVE.remove(entity);
		}
	}

	@Override
	public void markRemoved() {
		super.markRemoved();
		ACTIVE.remove(this);
	}

	/** このワールドで現在燃えている変電所。TrackManagerの給電計算から呼ばれる。 */
	public static List<SubstationBlockEntity> activeIn(ServerWorld world) {
		List<SubstationBlockEntity> list = new ArrayList<>();
		for (SubstationBlockEntity entity : ACTIVE) {
			if (entity.getWorld() == world) {
				list.add(entity);
			}
		}
		return list;
	}

	@Override
	protected void readData(ReadView view) {
		super.readData(view);
		this.targetId = view.getLong("Target", -1L);
		this.burnTime = view.getInt("BurnTime", 0);
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		view.putLong("Target", this.targetId);
		view.putInt("BurnTime", this.burnTime);
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
