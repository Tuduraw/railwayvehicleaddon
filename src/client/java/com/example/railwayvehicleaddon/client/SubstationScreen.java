package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.block.SubstationBlockEntity;
import com.example.railwayvehicleaddon.screen.SubstationScreenHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 変電所のGUI。前提MODの燃料精製機の画面と同じく、画像を使わず単色の塗りだけで描く
 * (テクスチャ素材を増やさないため)。座標はSubstationScreenHandlerのスロット位置と対応している。
 */
public class SubstationScreen extends AbstractContainerScreen<SubstationScreenHandler> {
	private static final int PANEL_COLOR = 0xFFC6C6C6;
	private static final int SLOT_FILL = 0xFF8B8B8B;
	private static final int SLOT_SHADOW = 0xFF373737;
	private static final int SLOT_HIGHLIGHT = 0xFFFFFFFF;
	private static final int BAR_BG = 0xFF373737;
	private static final int BAR_FILL = 0xFFE0892C;
	private static final int TEXT = 0xFF404040;
	private static final int TEXT_WARN = 0xFFA02020;
	private static final int TEXT_OK = 0xFF2A7A2A;

	public SubstationScreen(SubstationScreenHandler handler, Inventory inventory, Component title) {
		super(handler, inventory, title);
		this.imageWidth = 176;
		this.imageHeight = 166;
		this.inventoryLabelY = this.imageHeight - 94;
	}

	private static void drawSlot(GuiGraphics context, int slotX, int slotY) {
		int x = slotX - 1;
		int y = slotY - 1;
		context.fill(x, y, x + 18, y + 18, SLOT_FILL);
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

		SubstationScreenHandler handler = getMenu();
		drawSlot(context, x + SubstationScreenHandler.FUEL_SLOT_X, y + SubstationScreenHandler.FUEL_SLOT_Y);
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				drawSlot(context, x + 8 + col * 18, y + 84 + row * 18);
			}
		}
		for (int col = 0; col < 9; col++) {
			drawSlot(context, x + 8 + col * 18, y + 142);
		}

		// 今燃えている燃料の残り
		int barX = x + 52;
		int barY = y + 38;
		int barWidth = 112;
		int barHeight = 8;
		context.fill(barX, barY, barX + barWidth, barY + barHeight, BAR_BG);
		int filled = Math.round(barWidth * handler.burnRatio());
		if (filled > 0) {
			context.fill(barX, barY, barX + filled, barY + barHeight, BAR_FILL);
		}

		// 状況の文字
		Component status = handler.isBurning()
				? Component.translatable("screen.railwayvehicleaddon.substation.status_active", handler.burnTime() / 20)
				: Component.translatable("screen.railwayvehicleaddon.substation.status_idle");
		context.drawString(this.font, status, x + 52, y + 24, handler.isBurning() ? TEXT_OK : TEXT_WARN, false);

		Component capacity = handler.supplyPercent() > 0 || handler.isBurning()
				? Component.translatable("screen.railwayvehicleaddon.substation.capacity", SubstationBlockEntity.CAPACITY,
						Component.literal(handler.supplyPercent() + "%"))
				: Component.translatable("screen.railwayvehicleaddon.substation.capacity_idle", SubstationBlockEntity.CAPACITY);
		context.drawString(this.font, capacity, x + 8, y + 54, TEXT, false);

		Component link;
		int linkColor = TEXT;
		switch (handler.linkState()) {
			case 1 -> link = Component.translatable("screen.railwayvehicleaddon.substation.linked",
					handler.linkX(), handler.linkY(), handler.linkZ());
			case 2 -> {
				link = Component.translatable("screen.railwayvehicleaddon.substation.link_not_electrified");
				linkColor = TEXT_WARN;
			}
			default -> {
				link = Component.translatable("screen.railwayvehicleaddon.substation.link_none");
				linkColor = TEXT_WARN;
			}
		}
		context.drawString(this.font, link, x + 8, y + 68, linkColor, false);
	}
}
