package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.network.StopMarkerEditPayload;
import com.example.railwayvehicleaddon.operation.Timetable;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * 停止位置目標の名前の編集画面(素手で右クリックすると開く)。「駅名 番線」(例:「A駅 1」)を入力し、読み取った駅名と番線を
 * その場で表示する。「保存」(またはEnter)で名前を付ける。
 */
public class StopMarkerNameScreen extends Screen {
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFA0A0A0;

	private final BlockPos pos;
	private final String initial;
	private TextFieldWidget field;

	public StopMarkerNameScreen(BlockPos pos, String name) {
		super(Text.translatable("screen.railwayvehicleaddon.stop_marker.title"));
		this.pos = pos;
		this.initial = name == null ? "" : name;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 100;
		int y = this.height / 2 - 30;
		String text = this.field != null ? this.field.getText() : this.initial;
		this.field = new TextFieldWidget(this.textRenderer, x, y, 200, 20, Text.empty());
		this.field.setMaxLength(Timetable.MAX_NAME);
		this.field.setText(text);
		addDrawableChild(this.field);
		setInitialFocus(this.field);
		addDrawableChild(ButtonWidget.builder(Text.translatable("screen.railwayvehicleaddon.route_card.save"), b -> save())
				.dimensions(x, y + 50, 96, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("screen.railwayvehicleaddon.route_card.cancel"), b -> close())
				.dimensions(x + 104, y + 50, 96, 20).build());
	}

	private void save() {
		ClientPlayNetworking.send(new StopMarkerEditPayload(this.pos, this.field.getText()));
		close();
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
			save();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int x = this.width / 2 - 100;
		int y = this.height / 2 - 30;
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, y - 28, TEXT);
		context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.railwayvehicleaddon.stop_marker.hint"), x, y - 12, TEXT_DIM);
		Timetable.Stop parsed = Timetable.parseMarkerName(this.field.getText());
		Text preview = parsed.station().isEmpty()
				? Text.translatable("screen.railwayvehicleaddon.stop_marker.preview_empty")
				: Text.translatable("screen.railwayvehicleaddon.stop_marker.preview", parsed.station(),
				parsed.platform() > 0 ? Text.translatable("message.railwayvehicleaddon.stop_marker.platform", parsed.platform())
						: Text.translatable("message.railwayvehicleaddon.stop_marker.any_platform"));
		context.drawTextWithShadow(this.textRenderer, preview, x, y + 28, TEXT_DIM);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
