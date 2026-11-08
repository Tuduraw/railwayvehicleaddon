package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.block.OperationControllerBlockEntity;
import com.example.railwayvehicleaddon.item.RouteCardItem;
import com.example.railwayvehicleaddon.operation.AtoState;
import com.example.railwayvehicleaddon.operation.Timetable;
import com.example.railwayvehicleaddon.screen.OperationControllerScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 運行管理装置の画面。1ページに5組(制御スティック・経路アイテム)と、その組の状態(列車・ATO・次の駅)を表示する。
 * 上の段で組の数(−10・−1・+1・+10)とページ(◀▶)を変え、各組の「↺」で最初の駅から、「ATO」でATOを入切する。
 * 変電所の画面と同じく、画像を使わず単色の塗りだけで描く。
 */
public class OperationControllerScreen extends HandledScreen<OperationControllerScreenHandler> {
	private static final int PANEL_COLOR = 0xFFC6C6C6;
	private static final int SLOT_FILL = 0xFF8B8B8B;
	private static final int SLOT_SHADOW = 0xFF373737;
	private static final int SLOT_HIGHLIGHT = 0xFFFFFFFF;
	private static final int ROW_DISABLED = 0xFFA8A8A8;
	private static final int TEXT = 0xFF404040;
	private static final int TEXT_WARN = 0xFFA02020;
	private static final int TEXT_OK = 0xFF2A7A2A;

	public OperationControllerScreen(OperationControllerScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		this.backgroundWidth = OperationControllerScreenHandler.WIDTH;
		this.backgroundHeight = OperationControllerScreenHandler.HEIGHT;
		this.playerInventoryTitleX = OperationControllerScreenHandler.INVENTORY_X;
		this.playerInventoryTitleY = OperationControllerScreenHandler.INVENTORY_Y - 11;
	}

	@Override
	protected void init() {
		super.init();
		int x = this.x;
		int y = this.y + 14;
		addButton(x + 100, y, 22, Text.literal("-10"), 4);
		addButton(x + 124, y, 18, Text.literal("-1"), 2);
		addButton(x + 172, y, 18, Text.literal("+1"), 3);
		addButton(x + 192, y, 22, Text.literal("+10"), 5);
		addButton(x + 216, y, 14, Text.literal("◀"), 0);
		addButton(x + 232, y, 14, Text.literal("▶"), 1);
		for (int i = 0; i < OperationControllerScreenHandler.PAGE_ROWS; i++) {
			int ry = this.y + OperationControllerScreenHandler.ROW_Y + i * OperationControllerScreenHandler.ROW_HEIGHT + 2;
			addButton(x + 202, ry, 16, Text.literal("↺"), 10 + i);
			addButton(x + 220, ry, 24, Text.literal("ATO"), 20 + i);
		}
	}

	private void addButton(int x, int y, int width, Text label, int id) {
		addDrawableChild(ButtonWidget.builder(label, b -> {
			if (this.client != null && this.client.interactionManager != null) {
				this.client.interactionManager.clickButton(this.handler.syncId, id);
			}
		}).dimensions(x, y, width, 16).build());
	}

	private static void drawSlot(DrawContext context, int slotX, int slotY, boolean enabled) {
		int x = slotX - 1;
		int y = slotY - 1;
		context.fill(x, y, x + 18, y + 18, enabled ? SLOT_FILL : ROW_DISABLED);
		context.fill(x, y, x + 18, y + 1, SLOT_SHADOW);
		context.fill(x, y, x + 1, y + 18, SLOT_SHADOW);
		context.fill(x, y + 17, x + 18, y + 18, SLOT_HIGHLIGHT);
		context.fill(x + 17, y, x + 18, y + 18, SLOT_HIGHLIGHT);
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		int x = this.x;
		int y = this.y;
		context.fill(x, y, x + this.backgroundWidth, y + this.backgroundHeight, PANEL_COLOR);
		OperationControllerScreenHandler handler = getScreenHandler();
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
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		context.drawText(this.textRenderer, this.title, 8, 5, TEXT, false);
		OperationControllerScreenHandler handler = getScreenHandler();
		context.drawText(this.textRenderer, Text.translatable("screen.railwayvehicleaddon.operation_controller.rows", handler.rows()),
				8, 18, TEXT, false);
		context.drawText(this.textRenderer, Text.literal((handler.page() + 1) + "/" + handler.pageCount()), 146, 18, TEXT, false);
		context.drawText(this.textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, TEXT, false);
		for (int i = 0; i < OperationControllerScreenHandler.PAGE_ROWS; i++) {
			if (!handler.rowExists(i)) {
				continue;
			}
			int ry = OperationControllerScreenHandler.ROW_Y + i * OperationControllerScreenHandler.ROW_HEIGHT;
			int number = handler.page() * OperationControllerScreenHandler.PAGE_ROWS + i + 1;
			context.drawText(this.textRenderer, Text.literal(Integer.toString(number)), 4, ry + 7, TEXT, false);
			int status = handler.status(i);
			int color = status == OperationControllerBlockEntity.STATUS_RUNNING ? TEXT_OK
					: status == OperationControllerBlockEntity.STATUS_MANUAL || status == OperationControllerBlockEntity.STATUS_EMPTY ? TEXT : TEXT_WARN;
			Text line1 = statusText(status, handler.display(i));
			context.drawText(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(line1.getString(), 136)), 62, ry + 2, color, false);
			ItemStack card = handler.getSlot(i * 2 + 1).getStack();
			if (card.getItem() instanceof RouteCardItem && (status == OperationControllerBlockEntity.STATUS_RUNNING
					|| status == OperationControllerBlockEntity.STATUS_MANUAL)) {
				Timetable table = RouteCardItem.get(card);
				List<Timetable.Stop> stops = table.stops();
				int index = handler.stopIndex(i);
				Text line2 = index >= 0 && index < stops.size()
						? Text.translatable("screen.railwayvehicleaddon.operation_controller.next", stops.get(index).describe())
						: Text.translatable("screen.railwayvehicleaddon.operation_controller.no_next");
				String prefix = table.trainNumber().isEmpty() ? "" : table.trainNumber() + " ";
				context.drawText(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(prefix + line2.getString(), 136)),
						62, ry + 12, TEXT, false);
			}
		}
	}

	private static Text statusText(int status, int display) {
		return switch (status) {
			case OperationControllerBlockEntity.STATUS_NO_STICK -> Text.translatable("screen.railwayvehicleaddon.operation_controller.no_stick");
			case OperationControllerBlockEntity.STATUS_NO_CARD -> Text.translatable("screen.railwayvehicleaddon.operation_controller.no_card");
			case OperationControllerBlockEntity.STATUS_SEARCHING -> Text.translatable("screen.railwayvehicleaddon.operation_controller.searching");
			case OperationControllerBlockEntity.STATUS_NOT_RAIL -> Text.translatable("screen.railwayvehicleaddon.operation_controller.not_rail");
			case OperationControllerBlockEntity.STATUS_DUPLICATE -> Text.translatable("screen.railwayvehicleaddon.operation_controller.duplicate");
			case OperationControllerBlockEntity.STATUS_MANUAL -> Text.translatable("screen.railwayvehicleaddon.operation_controller.manual");
			case OperationControllerBlockEntity.STATUS_RUNNING -> {
				AtoState.Display[] values = AtoState.Display.values();
				AtoState.Display d = display >= 0 && display < values.length ? values[display] : AtoState.Display.OFF;
				yield Text.translatable("screen.railwayvehicleaddon.ato." + d.name().toLowerCase(java.util.Locale.ROOT));
			}
			default -> Text.translatable("screen.railwayvehicleaddon.operation_controller.empty");
		};
	}
}
