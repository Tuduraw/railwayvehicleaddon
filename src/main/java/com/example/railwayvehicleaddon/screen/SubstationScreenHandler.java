package com.example.railwayvehicleaddon.screen;

import com.example.railwayvehicleaddon.block.SubstationBlockEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 変電所のGUI。燃料スロット1つと、プレイヤーのインベントリ。燃焼・給電・連結の状況は
 * PropertyDelegate(サーバーから同期される整数)で受け取る。
 */
public class SubstationScreenHandler extends AbstractContainerMenu {
	/** 燃料スロットの座標(パネル内) */
	public static final int FUEL_SLOT_X = 26;
	public static final int FUEL_SLOT_Y = 34;

	private final Container inventory;
	private final ContainerData properties;

	/** クライアント側(開く操作を受けたとき)。実データはサーバーから同期される。 */
	public SubstationScreenHandler(int syncId, Inventory playerInventory) {
		this(syncId, playerInventory, new SimpleContainer(1), new SimpleContainerData(SubstationBlockEntity.PROPERTY_COUNT));
	}

	public SubstationScreenHandler(int syncId, Inventory playerInventory, Container inventory, ContainerData properties) {
		super(ModScreenHandlers.SUBSTATION, syncId);
		this.inventory = inventory;
		this.properties = properties;
		inventory.startOpen(playerInventory.player);

		this.addSlot(new Slot(inventory, 0, FUEL_SLOT_X, FUEL_SLOT_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return SubstationBlockEntity.fuelTicks(stack) > 0;
			}
		});
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
			}
		}
		for (int col = 0; col < 9; col++) {
			this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
		}
		this.addDataSlots(properties);
	}

	public int burnTime() {
		return this.properties.get(SubstationBlockEntity.PROP_BURN_TIME);
	}

	public boolean isBurning() {
		return burnTime() > 0;
	}

	/** 今燃えている燃料の残りの割合(0〜1)。 */
	public float burnRatio() {
		int total = this.properties.get(SubstationBlockEntity.PROP_BURN_TOTAL);
		int time = burnTime();
		if (total <= 0) {
			return time > 0 ? 1f : 0f;
		}
		return Mth.clamp(time / (float) total, 0f, 1f);
	}

	/** 0=未連結、1=連結済み、2=連結先は電化されていない */
	public int linkState() {
		return this.properties.get(SubstationBlockEntity.PROP_LINK_STATE);
	}

	public int linkX() {
		return this.properties.get(SubstationBlockEntity.PROP_LINK_X);
	}

	public int linkY() {
		return this.properties.get(SubstationBlockEntity.PROP_LINK_Y);
	}

	public int linkZ() {
		return this.properties.get(SubstationBlockEntity.PROP_LINK_Z);
	}

	public int supplyPercent() {
		return this.properties.get(SubstationBlockEntity.PROP_SUPPLY_PERCENT);
	}

	@Override
	public boolean stillValid(Player player) {
		return this.inventory.stillValid(player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		ItemStack result = ItemStack.EMPTY;
		Slot slot = this.slots.get(index);
		if (slot != null && slot.hasItem()) {
			ItemStack original = slot.getItem();
			result = original.copy();
			if (index == 0) {
				// 燃料スロットからプレイヤーのインベントリへ
				if (!this.moveItemStackTo(original, 1, this.slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else if (SubstationBlockEntity.fuelTicks(original) > 0) {
				// プレイヤーのインベントリから燃料スロットへ
				if (!this.moveItemStackTo(original, 0, 1, false)) {
					return ItemStack.EMPTY;
				}
			} else {
				return ItemStack.EMPTY;
			}
			if (original.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
		}
		return result;
	}
}
