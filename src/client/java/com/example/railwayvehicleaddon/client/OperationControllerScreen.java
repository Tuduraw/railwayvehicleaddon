package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.block.OperationControllerBlockEntity;
import com.example.railwayvehicleaddon.item.RouteCardItem;
import com.example.railwayvehicleaddon.operation.AtoState;
import com.example.railwayvehicleaddon.operation.Timetable;
import com.example.railwayvehicleaddon.screen.OperationControllerScreenHandler;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 運行管理装置の画面。1ページに5組(制御スティック・経路アイテム)と、その組の状態(列車・ATO・次の駅)を表示する。
 * 上の段で組の数(−10・−1・+1・+10)とページ(◀▶)を変え、各組の「↺」で最初の駅から、「ATO」でATOを入切する。
 * 変電所の画面と同じく、画像を使わず単色の塗りだけで描く。
 */
public class OperationControllerScreen extends AbstractContainerScreen<OperationControllerScreenHandler> {
	private static final int PANEL_COLOR = 0xFFC6C6C6;
	private static final int SLOT_FILL = 0xFF8B8B8B;
	private static final int SLOT_SHADOW = 0xFF373737;
	private static final int SLOT_HIGHLIGHT = 0xFFFFFFFF;
	private static final int ROW_DISABLED = 0xFFA8A8A8;
	private static final int TEXT = 0xFF404040;
	private static final int TEXT_WARN = 0xFFA02020;
	private static final int TEXT_OK = 0xFF2A7A2A;

	public OperationControllerScreen(OperationControllerScreenHandler handler, Inventory inventory, Component title) {
		super(handler, inventory, title);
		this.imageWidth = OperationControllerScreenHandler.WIDTH;
		this.imageHeight = OperationControllerScreenHandler.HEIGHT;
		this.inventoryLabelX = OperationControllerScreenHandler.INVENTORY_X;
		this.inventoryLabelY = OperationControllerScreenHandler.INVENTORY_Y - 11;
	}

	@Override
	protected void init() {
		super.init();
		int x = this.leftPos;
		int y = this.topPos + 14;
		addButton(x + 100, y, 22, Component.literal("-10"), 4);
		addButton(x + 124, y, 18, Component.literal("-1"), 2);
		addButton(x + 172, y, 18, Component.literal("+1"), 3);
		addButton(x + 192, y, 22, Component.literal("+10"), 5);
		addButton(x + 216, y, 14, Component.literal("◀"), 0);
		addButton(x + 232, y, 14, Component.literal("▶"), 1);
		for (int i = 0; i < OperationControllerScreenHandler.PAGE_ROWS; i++) {
			int ry = this.topPos + OperationControllerScreenHandler.ROW_Y + i * OperationControllerScreenHandler.ROW_HEIGHT + 2;
			addButton(x + 202, ry, 16, Component.literal("↺"), 10 + i);
			addButton(x + 220, ry, 24, Component.literal("ATO"), 20 + i);
		}
	}

	private void addButton(int x, int y, int width, Component label, int id) {
		addRenderableWidget(Button.builder(label, b -> {
			if (this.minecraft != null && this.minecraft.gameMode != null) {
				this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
			}
		}).bounds(x, y, width, 16).build());
	}

	private static void drawSlot(GuiGraphics context, int slotX, int slotY, boolean enabled) {
		int x = slotX - 1;
		int y = slotY - 1;
		context.fill(x, y, x + 18, y + 18, enabled ? SLOT_FILL : ROW_DISABLED);
		context.fill(x, y, x + 18, y + 1, SLOT_SHADOW);
		context.fill(x, y, x + 1, y + 18, SLOT_SHADOW);
		context.fill(x, y + 17, x + 18, y + 18, SLOT_HIGHLIGHT);
		context.fill(x + 17, y, x + 18, y + 18, SLOT_HIGHLIGHT);
	}

	@Override
	protected void renderBg(GuiGraphics context, float delta, int mouseX, int mouseY) {
		int x = this.leftPos;
		int y = this.topPos;
		context.fill(x, y, x + this.imageWidth, y + this.imageHeight, PANEL_COLOR);
		OperationControllerScreenHandler handler = getMenu();
		for (int i = 0; i < OperationControllerScreenHandler.PAGE_ROWS; i++) {
			int ry = y + OperationControllerScreenHandler.ROW_Y + i * OperationControllerScreenHandler.ROW_HEIGHT + 2;
			boolean enabled = handler.rowExists(i);
			drawSlot(context, x + OperationControllerScreenHandler.STICK_X, ry, enabled);
			drawSlot(context, x + OperationControllerScreenHandler.CARD_X, ry, enabled);
		}
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				drawSlot(context, x + OperationControllerScreenHandler.INVENTORY_X + col * 18,
						y + OperationControllerScreenHandler.INVENTORY_Y + row * 18, true);
			}
		}
		for (int col = 0; col < 9; col++) {
			drawSlot(context, x + OperationControllerScreenHandler.INVENTORY_X + col * 18,
					y + OperationControllerScreenHandler.INVENTORY_Y + 58, true);
		}
	}

	@Override
	protected void renderLabels(GuiGraphics context, int mouseX, int mouseY) {
		context.drawString(this.font, this.title, 8, 5, TEXT, false);
		OperationControllerScreenHandler handler = getMenu();
		context.drawString(this.font, Component.translatable("screen.railwayvehicleaddon.operation_controller.rows", handler.rows()),
				8, 18, TEXT, false);
		context.drawString(this.font, Component.literal((handler.page() + 1) + "/" + handler.pageCount()), 146, 18, TEXT, false);
		context.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, TEXT, false);
		for (int i = 0; i < OperationControllerScreenHandler.PAGE_ROWS; i++) {
			if (!handler.rowExists(i)) {
				continue;
			}
			int ry = OperationControllerScreenHandler.ROW_Y + i * OperationControllerScreenHandler.ROW_HEIGHT;
			int number = handler.page() * OperationControllerScreenHandler.PAGE_ROWS + i + 1;
			context.drawString(this.font, Component.literal(Integer.toString(number)), 4, ry + 7, TEXT, false);
			int status = handler.status(i);
			int color = status == OperationControllerBlockEntity.STATUS_RUNNING ? TEXT_OK
					: status == OperationControllerBlockEntity.STATUS_MANUAL || status == OperationControllerBlockEntity.STATUS_EMPTY ? TEXT : TEXT_WARN;
			Component line1 = statusText(status, handler.display(i));
			context.drawString(this.font, Component.literal(this.font.plainSubstrByWidth(line1.getString(), 136)), 62, ry + 2, color, false);
			ItemStack card = handler.getSlot(i * 2 + 1).getItem();
			if (card.getItem() instanceof RouteCardItem && (status == OperationControllerBlockEntity.STATUS_RUNNING
					|| status == OperationControllerBlockEntity.STATUS_MANUAL)) {
				Timetable table = RouteCardItem.get(card);
				List<Timetable.Stop> stops = table.stops();
				int index = handler.stopIndex(i);
				Component line2 = index >= 0 && index < stops.size()
						? Component.translatable("screen.railwayvehicleaddon.operation_controller.next", stops.get(index).describe())
						: Component.translatable("screen.railwayvehicleaddon.operation_controller.no_next");
				String prefix = table.trainNumber().isEmpty() ? "" : table.trainNumber() + " ";
				context.drawString(this.font, Component.literal(this.font.plainSubstrByWidth(prefix + line2.getString(), 136)),
						62, ry + 12, TEXT, false);
			}
		}
	}

	private static Component statusText(int status, int display) {
		return switch (status) {
			case OperationControllerBlockEntity.STATUS_NO_STICK -> Component.translatable("screen.railwayvehicleaddon.operation_controller.no_stick");
			case OperationControllerBlockEntity.STATUS_NO_CARD -> Component.translatable("screen.railwayvehicleaddon.operation_controller.no_card");
			case OperationControllerBlockEntity.STATUS_SEARCHING -> Component.translatable("screen.railwayvehicleaddon.operation_controller.searching");
			case OperationControllerBlockEntity.STATUS_NOT_RAIL -> Component.translatable("screen.railwayvehicleaddon.operation_controller.not_rail");
			case OperationControllerBlockEntity.STATUS_DUPLICATE -> Component.translatable("screen.railwayvehicleaddon.operation_controller.duplicate");
			case OperationControllerBlockEntity.STATUS_MANUAL -> Component.translatable("screen.railwayvehicleaddon.operation_controller.manual");
			case OperationControllerBlockEntity.STATUS_RUNNING -> {
				AtoState.Display[] values = AtoState.Display.values();
				AtoState.Display d = display >= 0 && display < values.length ? values[display] : AtoState.Display.OFF;
				yield Component.translatable("screen.railwayvehicleaddon.ato." + d.name().toLowerCase(java.util.Locale.ROOT));
			}
			default -> Component.translatable("screen.railwayvehicleaddon.operation_controller.empty");
		};
	}
}
