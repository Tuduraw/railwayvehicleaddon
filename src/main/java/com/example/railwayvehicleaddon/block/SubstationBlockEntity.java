package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.screen.SubstationScreenHandler;
import com.example.railwayvehicleaddon.track.TrackManager;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
public class SubstationBlockEntity extends BlockEntity implements TrackLinkable, Inventory, NamedScreenHandlerFactory {
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

	private final DefaultedList<ItemStack> items = DefaultedList.ofSize(1, ItemStack.EMPTY);
	private long targetId = -1L;
	private int burnTime;
	private int burnTotal;

	public SubstationBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.SUBSTATION_ENTITY, pos, state);
	}

	/** 燃料として使える個数と燃焼時間。燃料でなければ0。 */
	public static int fuelTicks(ItemStack stack) {
		if (stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL)) {
			return COAL_BURN_TIME;
		}
		return stack.isOf(Items.COAL_BLOCK) ? COAL_BLOCK_BURN_TIME : 0;
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
		if (this.world != null) {
			// 測量ツールのプレビューが連結先の線を描けるよう、クライアントへも送る
			BlockState state = getCachedState();
			this.world.updateListeners(this.pos, state, state, 3);
		}
	}

	public boolean isActive() {
		return this.burnTime > 0;
	}

	public static void tick(World world, BlockPos pos, BlockState state, SubstationBlockEntity entity) {
		if (world.isClient()) {
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
				stack.decrement(1);
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
			entity.markDirty();
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

	// ------------------------------------------------------------------ GUI

	@Override
	public Text getDisplayName() {
		return Text.translatable("block.railwayvehicleaddon.substation");
	}

	@Override
	public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
		return new SubstationScreenHandler(syncId, playerInventory, this, createPropertyDelegate());
	}

	private PropertyDelegate createPropertyDelegate() {
		return new PropertyDelegate() {
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
			public int size() {
				return PROPERTY_COUNT;
			}
		};
	}

	/** 連結先に関する表示値(サーバー側でだけ意味を持つ。クライアント側のコピーは同期された値で上書きされる)。 */
	private int linkInfo(int index) {
		if (!(this.world instanceof ServerWorld serverWorld)) {
			return 0;
		}
		TrackNetwork network = TrackManager.network(serverWorld);
		Vec3d target = this.targetId >= 0
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
	public int size() {
		return this.items.size();
	}

	@Override
	public boolean isEmpty() {
		return this.items.get(0).isEmpty();
	}

	@Override
	public ItemStack getStack(int slot) {
		return this.items.get(slot);
	}

	@Override
	public ItemStack removeStack(int slot, int amount) {
		ItemStack result = Inventories.splitStack(this.items, slot, amount);
		if (!result.isEmpty()) {
			markDirty();
		}
		return result;
	}

	@Override
	public ItemStack removeStack(int slot) {
		return Inventories.removeStack(this.items, slot);
	}

	@Override
	public void setStack(int slot, ItemStack stack) {
		this.items.set(slot, stack);
		if (stack.getCount() > getMaxCountPerStack()) {
			stack.setCount(getMaxCountPerStack());
		}
		markDirty();
	}

	@Override
	public boolean isValid(int slot, ItemStack stack) {
		return fuelTicks(stack) > 0;
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return this.world != null && this.world.getBlockEntity(this.pos) == this
				&& player.squaredDistanceTo(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5) <= 64.0;
	}

	@Override
	public void clear() {
		this.items.clear();
	}

	// ------------------------------------------------------------------ 保存・同期

	@Override
	protected void readData(ReadView view) {
		super.readData(view);
		Inventories.readData(view, this.items);
		this.targetId = view.getLong("Target", -1L);
		this.burnTime = view.getInt("BurnTime", 0);
		this.burnTotal = view.getInt("BurnTotal", 0);
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		Inventories.writeData(view, this.items, true);
		view.putLong("Target", this.targetId);
		view.putInt("BurnTime", this.burnTime);
		view.putInt("BurnTotal", this.burnTotal);
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
