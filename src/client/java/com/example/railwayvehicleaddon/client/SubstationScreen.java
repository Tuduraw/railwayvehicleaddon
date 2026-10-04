package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.block.SubstationBlockEntity;
import com.example.railwayvehicleaddon.screen.SubstationScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/**
 * 変電所のGUI。前提MODの燃料精製機の画面と同じく、画像を使わず単色の塗りだけで描く
 * (テクスチャ素材を増やさないため)。座標はSubstationScreenHandlerのスロット位置と対応している。
 */
public class SubstationScreen extends HandledScreen<SubstationScreenHandler> {
	private static final int PANEL_COLOR = 0xFFC6C6C6;
	private static final int SLOT_FILL = 0xFF8B8B8B;
	private static final int SLOT_SHADOW = 0xFF373737;
	private static final int SLOT_HIGHLIGHT = 0xFFFFFFFF;
	private static final int BAR_BG = 0xFF373737;
	private static final int BAR_FILL = 0xFFE0892C;
	private static final int TEXT = 0xFF404040;
	private static final int TEXT_WARN = 0xFFA02020;
	private static final int TEXT_OK = 0xFF2A7A2A;

	public SubstationScreen(SubstationScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		this.backgroundWidth = 176;
		this.backgroundHeight = 166;
		this.playerInventoryTitleY = this.backgroundHeight - 94;
	}

	private static void drawSlot(DrawContext context, int slotX, int slotY) {
		int x = slotX - 1;
		int y = slotY - 1;
		context.fill(x, y, x + 18, y + 18, SLOT_FILL);
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

		SubstationScreenHandler handler = getScreenHandler();
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
		Text status = handler.isBurning()
				? Text.translatable("screen.railwayvehicleaddon.substation.status_active", handler.burnTime() / 20)
				: Text.translatable("screen.railwayvehicleaddon.substation.status_idle");
		context.drawText(this.textRenderer, status, x + 52, y + 24, handler.isBurning() ? TEXT_OK : TEXT_WARN, false);

		Text capacity = handler.supplyPercent() > 0 || handler.isBurning()
				? Text.translatable("screen.railwayvehicleaddon.substation.capacity", SubstationBlockEntity.CAPACITY,
						Text.literal(handler.supplyPercent() + "%"))
				: Text.translatable("screen.railwayvehicleaddon.substation.capacity_idle", SubstationBlockEntity.CAPACITY);
		context.drawText(this.textRenderer, capacity, x + 8, y + 54, TEXT, false);

		Text link;
		int linkColor = TEXT;
		switch (handler.linkState()) {
			case 1 -> link = Text.translatable("screen.railwayvehicleaddon.substation.linked",
					handler.linkX(), handler.linkY(), handler.linkZ());
			case 2 -> {
				link = Text.translatable("screen.railwayvehicleaddon.substation.link_not_electrified");
				linkColor = TEXT_WARN;
			}
			default -> {
				link = Text.translatable("screen.railwayvehicleaddon.substation.link_none");
				linkColor = TEXT_WARN;
			}
		}
		context.drawText(this.textRenderer, link, x + 8, y + 68, linkColor, false);
	}
}
