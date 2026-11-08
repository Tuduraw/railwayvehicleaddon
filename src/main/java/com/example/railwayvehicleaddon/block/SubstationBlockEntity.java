package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.screen.SubstationScreenHandler;
import com.example.railwayvehicleaddon.track.TrackManager;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * 変電所。燃料スロットの石炭・木炭・石炭ブロックを、かまどのように1つずつ燃やして発電し、
 * 燃えている間だけ、連結した線路区間が属する給電区画(つながった電化区間のまとまり)へ
 * 供給能力(容量)を提供する。発電の有無は「燃えているか」の二値で、発電量そのものは持たない
 * (鉄道模型のような気軽さを優先し、電圧降下や距離による損失は扱わない)。
 * 燃料が尽きると停止し、燃料を入れると再び動く。右クリックで開くGUIで、燃料の出し入れと
 * 燃焼・給電・連結の状況を確認できる。
 *
 * <p>燃えている変電所は、ワールドごとに{@link #activeIn}で取れるよう自分自身を登録する
 * (ブロックエンティティをチャンクごとに探すより軽いため)。チャンクが即座にアンロードされた
 * 場合に登録から外れないまま残ることがあるが、アンロード中はtick()が呼ばれないため実害はない。
 */
public class SubstationBlockEntity extends BlockEntity implements TrackLinkable, Container, MenuProvider {
	/** 燃えている変電所1つが給電区画に提供する容量(同時に力行できる電車の数の目安) */
	public static final int CAPACITY = 3;
	/** 石炭・木炭1個、石炭ブロック1個の燃焼時間(tick)。バニラのかまどと同じ値 */
	public static final int COAL_BURN_TIME = 1600;
	public static final int COAL_BLOCK_BURN_TIME = COAL_BURN_TIME * 10;

	/** GUIへ同期する値の数(SubstationScreenHandlerと一致させること) */
	public static final int PROPERTY_COUNT = 8;
	public static final int PROP_BURN_TIME = 0;
	public static final int PROP_BURN_TOTAL = 1;
	/** 連結先の状態: 0=未連結、1=連結済み、2=連結先は電化されていない */
	public static final int PROP_LINK_STATE = 2;
	public static final int PROP_LINK_X = 3;
	public static final int PROP_LINK_Y = 4;
	public static final int PROP_LINK_Z = 5;
	/** 連結先の給電区画の給電係数(0〜100の百分率) */
	public static final int PROP_SUPPLY_PERCENT = 6;

	private static final Set<SubstationBlockEntity> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());

	private final NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
	private long targetId = -1L;
	private int burnTime;
	private int burnTotal;

	public SubstationBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.SUBSTATION_ENTITY, pos, state);
	}

	/** 燃料として使える個数と燃焼時間。燃料でなければ0。 */
	public static int fuelTicks(ItemStack stack) {
		if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) {
			return COAL_BURN_TIME;
		}
		return stack.is(Items.COAL_BLOCK) ? COAL_BLOCK_BURN_TIME : 0;
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
		setChanged();
		if (this.level != null) {
			// 測量ツールのプレビューが連結先の線を描けるよう、クライアントへも送る
			BlockState state = getBlockState();
			this.level.sendBlockUpdated(this.worldPosition, state, state, 3);
		}
	}

	public boolean isActive() {
		return this.burnTime > 0;
	}

	public static void tick(Level world, BlockPos pos, BlockState state, SubstationBlockEntity entity) {
		if (world.isClientSide()) {
			return;
		}
		boolean changed = false;
		if (entity.burnTime > 0) {
			entity.burnTime--;
			changed = entity.burnTime == 0;
		}
		if (entity.burnTime <= 0) {
			// かまどと同じく、燃え尽きたら次の燃料を1つ取り出して燃やす
			ItemStack stack = entity.items.get(0);
			int ticks = fuelTicks(stack);
			if (ticks > 0) {
				stack.shrink(1);
				entity.burnTime = ticks;
				entity.burnTotal = ticks;
				changed = true;
			}
		}
		if (entity.burnTime > 0) {
			ACTIVE.add(entity);
		} else {
			ACTIVE.remove(entity);
			if (entity.burnTotal != 0) {
				entity.burnTotal = 0;
				changed = true;
			}
		}
		if (changed) {
			entity.setChanged();
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		ACTIVE.remove(this);
	}

	/** このワールドで現在燃えている変電所。TrackManagerの給電計算から呼ばれる。 */
	public static List<SubstationBlockEntity> activeIn(ServerLevel world) {
		List<SubstationBlockEntity> list = new ArrayList<>();
		for (SubstationBlockEntity entity : ACTIVE) {
			if (entity.getLevel() == world) {
				list.add(entity);
			}
		}
		return list;
	}

	// ------------------------------------------------------------------ GUI

	@Override
	public Component getDisplayName() {
		return Component.translatable("block.railwayvehicleaddon.substation");
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
		return new SubstationScreenHandler(syncId, playerInventory, this, createPropertyDelegate());
	}

	private ContainerData createPropertyDelegate() {
		return new ContainerData() {
			@Override
			public int get(int index) {
				if (index == PROP_BURN_TIME) {
					return SubstationBlockEntity.this.burnTime;
				}
				if (index == PROP_BURN_TOTAL) {
					return SubstationBlockEntity.this.burnTotal;
				}
				return linkInfo(index);
			}

			@Override
			public void set(int index, int value) {
				// 状態の変更はサーバー側の処理だけで行う(GUIからは書き換えない)
			}

			@Override
			public int getCount() {
				return PROPERTY_COUNT;
			}
		};
	}

	/** 連結先に関する表示値(サーバー側でだけ意味を持つ。クライアント側のコピーは同期された値で上書きされる)。 */
	private int linkInfo(int index) {
		if (!(this.level instanceof ServerLevel serverWorld)) {
			return 0;
		}
		TrackNetwork network = TrackManager.network(serverWorld);
		Vec3 target = this.targetId >= 0
				? DeviceLinks.targetPosition(network, DeviceKind.TargetType.SEGMENT, this.targetId) : null;
		boolean electrified = target != null && network.segment(this.targetId) != null
				&& network.segment(this.targetId).electrified();
		return switch (index) {
			case PROP_LINK_STATE -> target == null ? 0 : (electrified ? 1 : 2);
			case PROP_LINK_X -> target == null ? 0 : (int) Math.floor(target.x);
			case PROP_LINK_Y -> target == null ? 0 : (int) Math.floor(target.y);
			case PROP_LINK_Z -> target == null ? 0 : (int) Math.floor(target.z);
			case PROP_SUPPLY_PERCENT -> electrified ? (int) Math.round(network.electricFactor(this.targetId) * 100.0) : 0;
			default -> 0;
		};
	}

	// ------------------------------------------------------------------ Inventory

	@Override
	public int getContainerSize() {
		return this.items.size();
	}

	@Override
	public boolean isEmpty() {
		return this.items.get(0).isEmpty();
	}

	@Override
	public ItemStack getItem(int slot) {
		return this.items.get(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		ItemStack result = ContainerHelper.removeItem(this.items, slot, amount);
		if (!result.isEmpty()) {
			setChanged();
		}
		return result;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return ContainerHelper.takeItem(this.items, slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		this.items.set(slot, stack);
		if (stack.getCount() > getMaxStackSize()) {
			stack.setCount(getMaxStackSize());
		}
		setChanged();
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return fuelTicks(stack) > 0;
	}

	@Override
	public boolean stillValid(Player player) {
		return this.level != null && this.level.getBlockEntity(this.worldPosition) == this
				&& player.distanceToSqr(this.worldPosition.getX() + 0.5, this.worldPosition.getY() + 0.5, this.worldPosition.getZ() + 0.5) <= 64.0;
	}

	@Override
	public void clearContent() {
		this.items.clear();
	}

	// ------------------------------------------------------------------ 保存・同期

	@Override
	protected void loadAdditional(ValueInput view) {
		super.loadAdditional(view);
		ContainerHelper.loadAllItems(view, this.items);
		this.targetId = view.getLongOr("Target", -1L);
		this.burnTime = view.getIntOr("BurnTime", 0);
		this.burnTotal = view.getIntOr("BurnTotal", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput view) {
		super.saveAdditional(view);
		ContainerHelper.saveAllItems(view, this.items, true);
		view.putLong("Target", this.targetId);
		view.putInt("BurnTime", this.burnTime);
		view.putInt("BurnTotal", this.burnTotal);
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
