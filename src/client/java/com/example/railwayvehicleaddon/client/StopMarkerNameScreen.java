package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.network.StopMarkerEditPayload;
import com.example.railwayvehicleaddon.operation.Timetable;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 停止位置目標の名前の編集画面(素手で右クリックすると開く)。「駅名 番線」(例:「A駅 1」)を入力し、読み取った駅名と番線を
 * その場で表示する。「保存」(またはEnter)で名前を付ける。
 */
public class StopMarkerNameScreen extends Screen {
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFA0A0A0;

	private final BlockPos pos;
	private final String initial;
	private EditBox field;

	public StopMarkerNameScreen(BlockPos pos, String name) {
		super(Component.translatable("screen.railwayvehicleaddon.stop_marker.title"));
		this.pos = pos;
		this.initial = name == null ? "" : name;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 100;
		int y = this.height / 2 - 30;
		String text = this.field != null ? this.field.getValue() : this.initial;
		this.field = new EditBox(this.font, x, y, 200, 20, Component.empty());
		this.field.setMaxLength(Timetable.MAX_NAME);
		this.field.setValue(text);
		addRenderableWidget(this.field);
		setInitialFocus(this.field);
		addRenderableWidget(Button.builder(Component.translatable("screen.railwayvehicleaddon.route_card.save"), b -> save())
				.bounds(x, y + 50, 96, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.railwayvehicleaddon.route_card.cancel"), b -> onClose())
				.bounds(x + 104, y + 50, 96, 20).build());
	}

	private void save() {
		ClientPlayNetworking.send(new StopMarkerEditPayload(this.pos, this.field.getValue()));
		onClose();
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
		if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
			save();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int x = this.width / 2 - 100;
		int y = this.height / 2 - 30;
		context.drawCenteredString(this.font, this.title, this.width / 2, y - 28, TEXT);
		context.drawString(this.font, Component.translatable("screen.railwayvehicleaddon.stop_marker.hint"), x, y - 12, TEXT_DIM);
		Timetable.Stop parsed = Timetable.parseMarkerName(this.field.getValue());
		Component preview = parsed.station().isEmpty()
				? Component.translatable("screen.railwayvehicleaddon.stop_marker.preview_empty")
				: Component.translatable("screen.railwayvehicleaddon.stop_marker.preview", parsed.station(),
				parsed.platform() > 0 ? Component.translatable("message.railwayvehicleaddon.stop_marker.platform", parsed.platform())
						: Component.translatable("message.railwayvehicleaddon.stop_marker.any_platform"));
		context.drawString(this.font, preview, x, y + 28, TEXT_DIM);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
