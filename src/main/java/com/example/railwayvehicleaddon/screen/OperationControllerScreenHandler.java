package com.example.railwayvehicleaddon.screen;

import com.example.railwayvehicleaddon.block.OperationControllerBlockEntity;
import com.example.railwayvehicleaddon.item.RouteCardItem;
import com.example.tudursvehiclemod.item.DroneControlStickItem;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

/**
 * 運行管理装置の画面。1ページにPAGE_ROWS組(制御スティック・経路アイテム)を表示し、ページで切り替える
 * (前提MODのドローン制御ブロックの編隊スロットと同じく、スロットは固定で、見ている組の範囲だけを変える)。
 * 組の数・ページ・各組の状態はPropertyDelegateで同期する。
 */
public class OperationControllerScreenHandler extends ScreenHandler {
	public static final int PAGE_ROWS = 5;
	public static final int PROPERTY_COUNT = 2 + PAGE_ROWS * 3;
	/** スロットの位置(画面内) */
	public static final int WIDTH = 248;
	public static final int ROW_Y = 30;
	public static final int ROW_HEIGHT = 22;
	public static final int STICK_X = 22;
	public static final int CARD_X = 42;
	public static final int INVENTORY_X = (WIDTH - 162) / 2;
	public static final int INVENTORY_Y = ROW_Y + PAGE_ROWS * ROW_HEIGHT + 18;
	public static final int HEIGHT = INVENTORY_Y + 76 + 6;

	private final OperationControllerBlockEntity blockEntity;
	private final PagedView view;
	private final PropertyDelegate properties;

	/** ページの中の組だけを見せるスロットの裏側(スロットは固定で、見ている範囲 offset だけを変える)。 */
	private static final class PagedView implements Inventory {
		private final Inventory backing;
		private final OperationControllerBlockEntity blockEntity;

		private PagedView(Inventory backing, OperationControllerBlockEntity blockEntity) {
			this.backing = backing;
			this.blockEntity = blockEntity;
		}

		private int offset() {
			return this.blockEntity == null ? 0 : this.blockEntity.page() * PAGE_ROWS * 2;
		}

		boolean inRange(int index) {
			return this.blockEntity == null || offset() + index < this.backing.size();
		}

		@Override
		public int size() {
			return PAGE_ROWS * 2;
		}

		@Override
		public boolean isEmpty() {
			for (int i = 0; i < size(); i++) {
				if (!getStack(i).isEmpty()) {
					return false;
				}
			}
			return true;
		}

		@Override
		public ItemStack getStack(int index) {
			if (this.blockEntity == null) {
				return this.backing.getStack(index);
			}
			return inRange(index) ? this.backing.getStack(offset() + index) : ItemStack.EMPTY;
		}

		@Override
		public ItemStack removeStack(int index, int amount) {
			if (this.blockEntity == null) {
				return this.backing.removeStack(index, amount);
			}
			return inRange(index) ? this.backing.removeStack(offset() + index, amount) : ItemStack.EMPTY;
		}

		@Override
		public ItemStack removeStack(int index) {
			if (this.blockEntity == null) {
				return this.backing.removeStack(index);
			}
			return inRange(index) ? this.backing.removeStack(offset() + index) : ItemStack.EMPTY;
		}

		@Override
		public void setStack(int index, ItemStack stack) {
			if (this.blockEntity == null) {
				this.backing.setStack(index, stack);
			} else if (inRange(index)) {
				this.backing.setStack(offset() + index, stack);
			}
		}

		@Override
		public int getMaxCountPerStack() {
			return 1;
		}

		@Override
		public void markDirty() {
			this.backing.markDirty();
		}

		@Override
		public boolean canPlayerUse(PlayerEntity player) {
			return this.backing.canPlayerUse(player);
		}

		@Override
		public void clear() {
			for (int i = 0; i < size(); i++) {
				removeStack(i);
			}
		}
	}

	/** クライアント側(実データはサーバーから同期される)。 */
	public OperationControllerScreenHandler(int syncId, PlayerInventory playerInventory) {
		this(syncId, playerInventory, null);
	}

	public OperationControllerScreenHandler(int syncId, PlayerInventory playerInventory, OperationControllerBlockEntity blockEntity) {
		super(ModScreenHandlers.OPERATION_CONTROLLER, syncId);
		this.blockEntity = blockEntity;
		Inventory backing = blockEntity != null ? blockEntity.inventory() : new SimpleInventory(PAGE_ROWS * 2);
		this.view = new PagedView(backing, blockEntity);
		this.properties = blockEntity != null ? blockEntity.properties() : new ArrayPropertyDelegate(PROPERTY_COUNT);
		this.addProperties(this.properties);

		for (int i = 0; i < PAGE_ROWS; i++) {
			int y = ROW_Y + i * ROW_HEIGHT + 2;
			final int viewRow = i;
			this.addSlot(new Slot(this.view, i * 2, STICK_X, y) {
				@Override
				public boolean canInsert(ItemStack stack) {
					return stack.getItem() instanceof DroneControlStickItem && rowExists(viewRow);
				}

				@Override
				public int getMaxItemCount() {
					return 1;
				}
			});
			this.addSlot(new Slot(this.view, i * 2 + 1, CARD_X, y) {
				@Override
				public boolean canInsert(ItemStack stack) {
					return stack.getItem() instanceof RouteCardItem && rowExists(viewRow);
				}

				@Override
				public int getMaxItemCount() {
					return 1;
				}
			});
		}
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				this.addSlot(new Slot(playerInventory, col + row * 9 + 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
			}
		}
		for (int col = 0; col < 9; col++) {
			this.addSlot(new Slot(playerInventory, col, INVENTORY_X + col * 18, INVENTORY_Y + 58));
		}
	}

	/** ページの中のviewRow番目の組が、組の数の範囲にあるか。 */
	public boolean rowExists(int viewRow) {
		return page() * PAGE_ROWS + viewRow < rows();
	}

	public int rows() {
		return this.properties.get(0);
	}

	public int page() {
		return this.properties.get(1);
	}

	public int pageCount() {
		return Math.max(1, (rows() + PAGE_ROWS - 1) / PAGE_ROWS);
	}

	public int status(int viewRow) {
		return this.properties.get(2 + viewRow * 3);
	}

	public int display(int viewRow) {
		return this.properties.get(3 + viewRow * 3);
	}

	public int stopIndex(int viewRow) {
		return this.properties.get(4 + viewRow * 3);
	}

	@Override
	public boolean onButtonClick(PlayerEntity player, int id) {
		if (this.blockEntity == null) {
			return false;
		}
		boolean handled = this.blockEntity.handleButton(id);
		if (handled) {
			sendContentUpdates();
		}
		return handled;
	}

	@Override
	public boolean canUse(PlayerEntity player) {
		return this.view.canPlayerUse(player);
	}

	@Override
	public ItemStack quickMove(PlayerEntity player, int index) {
		Slot slot = this.slots.get(index);
		if (slot == null || !slot.hasStack()) {
			return ItemStack.EMPTY;
		}
		ItemStack original = slot.getStack();
		ItemStack result = original.copy();
		int rowSlots = PAGE_ROWS * 2;
		if (index < rowSlots) {
			if (!this.insertItem(original, rowSlots, this.slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else {
			boolean moved = false;
			for (int i = 0; i < rowSlots && !moved; i++) {
				Slot target = this.slots.get(i);
				if (!target.hasStack() && target.canInsert(original)) {
					target.setStack(original.split(1));
					moved = true;
				}
			}
			if (!moved) {
				return ItemStack.EMPTY;
			}
		}
		if (original.isEmpty()) {
			slot.setStack(ItemStack.EMPTY);
		} else {
			slot.markDirty();
		}
		return result;
	}
}
