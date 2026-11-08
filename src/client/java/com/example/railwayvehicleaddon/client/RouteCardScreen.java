package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.item.RouteCardItem;
import com.example.railwayvehicleaddon.network.RouteCardUpdatePayload;
import com.example.railwayvehicleaddon.operation.Timetable;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 経路アイテムの設定画面。手に持っている経路アイテムの運行表を編集し、「保存」でサーバーへ送る。
 * <ul>
 *   <li>全体: 列車番号・種別(0〜15)・終点(停止/折り返し/繰り返し)</li>
 *   <li>駅ごと(駅は停止位置目標を右クリックして追加する): 停車/通過、停車時間(秒)、発車時刻(HH:MM、空欄で指定なし)、
 *       並べ替え(▲▼)・削除(✕)</li>
 *   <li>経路番号(列車番号検知器を右クリックして追加する): 番号(0〜15)、削除</li>
 * </ul>
 */
public class RouteCardScreen extends Screen {
	private static final int STOP_ROWS = 7;
	private static final int ROUTE_ROWS = 3;
	private static final int ROW_H = 22;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFA0A0A0;

	private final Hand hand;
	private String trainNumber;
	private int type;
	private Timetable.EndMode endMode;
	private final List<MutableStop> stops = new ArrayList<>();
	private final List<MutableRoute> routes = new ArrayList<>();
	private int stopPage;
	private int routePage;
	private int left;
	private int top;

	private static final class MutableStop {
		String station;
		int platform;
		boolean pass;
		String dwell;
		String depart;
	}

	private static final class MutableRoute {
		String name;
		String value;
	}

	public RouteCardScreen(Hand hand, ItemStack stack) {
		super(Text.translatable("screen.railwayvehicleaddon.route_card.title"));
		this.hand = hand;
		Timetable table = RouteCardItem.get(stack);
		this.trainNumber = table.trainNumber();
		this.type = table.type();
		this.endMode = table.endMode();
		for (Timetable.Stop stop : table.stops()) {
			MutableStop m = new MutableStop();
			m.station = stop.station();
			m.platform = stop.platform();
			m.pass = stop.pass();
			m.dwell = Integer.toString(stop.dwell());
			m.depart = Timetable.formatDepartTime(stop.departTime());
			this.stops.add(m);
		}
		for (Map.Entry<String, Integer> e : table.routes().entrySet()) {
			MutableRoute r = new MutableRoute();
			r.name = e.getKey();
			r.value = Integer.toString(e.getValue());
			this.routes.add(r);
		}
	}

	@Override
	protected void init() {
		int width = 380;
		this.left = (this.width - width) / 2;
		this.top = Math.max(4, (this.height - (60 + STOP_ROWS * ROW_H + 30 + ROUTE_ROWS * ROW_H + 50)) / 2);
		int x = this.left;
		int y = this.top + 14;

		TextFieldWidget number = new TextFieldWidget(this.textRenderer, x + 50, y, 90, 18, Text.empty());
		number.setMaxLength(Timetable.MAX_NAME);
		number.setText(this.trainNumber);
		number.setChangedListener(t -> this.trainNumber = t);
		addDrawableChild(number);
		TextFieldWidget typeField = new TextFieldWidget(this.textRenderer, x + 180, y, 30, 18, Text.empty());
		typeField.setMaxLength(2);
		typeField.setText(Integer.toString(this.type));
		typeField.setTextPredicate(t -> t.isEmpty() || t.matches("\\d{1,2}"));
		typeField.setChangedListener(t -> this.type = t.isEmpty() ? 0 : Math.min(15, Integer.parseInt(t)));
		addDrawableChild(typeField);
		addDrawableChild(ButtonWidget.builder(endModeText(), b -> {
			this.endMode = this.endMode.next();
			b.setMessage(endModeText());
		}).dimensions(x + 250, y, 130, 18).build());

		// 駅の一覧
		int listTop = y + 36;
		int first = this.stopPage * STOP_ROWS;
		for (int i = 0; i < STOP_ROWS && first + i < this.stops.size(); i++) {
			final int index = first + i;
			MutableStop stop = this.stops.get(index);
			int ry = listTop + i * ROW_H;
			addDrawableChild(ButtonWidget.builder(passText(stop), b -> {
				stop.pass = !stop.pass;
				b.setMessage(passText(stop));
			}).dimensions(x + 150, ry, 44, 18).build());
			TextFieldWidget dwell = new TextFieldWidget(this.textRenderer, x + 198, ry, 36, 18, Text.empty());
			dwell.setMaxLength(4);
			dwell.setText(stop.dwell);
			dwell.setTextPredicate(t -> t.isEmpty() || t.matches("\\d{1,4}"));
			dwell.setChangedListener(t -> stop.dwell = t);
			addDrawableChild(dwell);
			TextFieldWidget depart = new TextFieldWidget(this.textRenderer, x + 252, ry, 46, 18, Text.empty());
			depart.setMaxLength(5);
			depart.setText(stop.depart);
			depart.setTextPredicate(t -> t.isEmpty() || t.matches("[0-9:：]{1,5}"));
			depart.setChangedListener(t -> stop.depart = t);
			addDrawableChild(depart);
			addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> moveStop(index, -1)).dimensions(x + 304, ry, 20, 18).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> moveStop(index, 1)).dimensions(x + 326, ry, 20, 18).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("✕"), b -> {
				this.stops.remove(index);
				this.stopPage = Math.min(this.stopPage, Math.max(0, (this.stops.size() - 1) / STOP_ROWS));
				rebuild();
			}).dimensions(x + 350, ry, 20, 18).build());
		}
		int stopPagerY = listTop + STOP_ROWS * ROW_H;
		addDrawableChild(ButtonWidget.builder(Text.literal("◀"), b -> {
			this.stopPage = Math.max(0, this.stopPage - 1);
			rebuild();
		}).dimensions(x + 250, stopPagerY, 20, 16).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("▶"), b -> {
			this.stopPage = Math.min(stopPages() - 1, this.stopPage + 1);
			rebuild();
		}).dimensions(x + 350, stopPagerY, 20, 16).build());

		// 経路番号
		int routeTop = stopPagerY + 34;
		int firstRoute = this.routePage * ROUTE_ROWS;
		for (int i = 0; i < ROUTE_ROWS && firstRoute + i < this.routes.size(); i++) {
			final int index = firstRoute + i;
			MutableRoute route = this.routes.get(index);
			int ry = routeTop + i * ROW_H;
			TextFieldWidget value = new TextFieldWidget(this.textRenderer, x + 198, ry, 36, 18, Text.empty());
			value.setMaxLength(2);
			value.setText(route.value);
			value.setTextPredicate(t -> t.isEmpty() || t.matches("\\d{1,2}"));
			value.setChangedListener(t -> route.value = t);
			addDrawableChild(value);
			addDrawableChild(ButtonWidget.builder(Text.literal("✕"), b -> {
				this.routes.remove(index);
				this.routePage = Math.min(this.routePage, Math.max(0, (this.routes.size() - 1) / ROUTE_ROWS));
				rebuild();
			}).dimensions(x + 240, ry, 20, 18).build());
		}
		int routePagerY = routeTop + ROUTE_ROWS * ROW_H;
		addDrawableChild(ButtonWidget.builder(Text.literal("◀"), b -> {
			this.routePage = Math.max(0, this.routePage - 1);
			rebuild();
		}).dimensions(x + 250, routePagerY - 22, 20, 16).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("▶"), b -> {
			this.routePage = Math.min(routePages() - 1, this.routePage + 1);
			rebuild();
		}).dimensions(x + 350, routePagerY - 22, 20, 16).build());

		int buttonsY = routePagerY + 6;
		addDrawableChild(ButtonWidget.builder(Text.translatable("screen.railwayvehicleaddon.route_card.save"), b -> {
			save();
			close();
		}).dimensions(x + 100, buttonsY, 86, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("screen.railwayvehicleaddon.route_card.cancel"), b -> close())
				.dimensions(x + 194, buttonsY, 86, 20).build());
	}

	private int stopPages() {
		return Math.max(1, (this.stops.size() + STOP_ROWS - 1) / STOP_ROWS);
	}

	private int routePages() {
		return Math.max(1, (this.routes.size() + ROUTE_ROWS - 1) / ROUTE_ROWS);
	}

	private void rebuild() {
		clearChildren();
		init();
	}

	private void moveStop(int index, int delta) {
		int target = index + delta;
		if (target < 0 || target >= this.stops.size()) {
			return;
		}
		MutableStop moved = this.stops.remove(index);
		this.stops.add(target, moved);
		this.stopPage = target / STOP_ROWS;
		rebuild();
	}

	private Text endModeText() {
		return Text.translatable("screen.railwayvehicleaddon.route_card.end." + this.endMode.asString());
	}

	private static Text passText(MutableStop stop) {
		return Text.translatable(stop.pass ? "screen.railwayvehicleaddon.route_card.pass" : "screen.railwayvehicleaddon.route_card.stop");
	}

	private void save() {
		List<Timetable.Stop> out = new ArrayList<>();
		for (MutableStop m : this.stops) {
			int dwell = m.dwell.isEmpty() ? 0 : Math.min(Timetable.MAX_DWELL, Integer.parseInt(m.dwell));
			out.add(new Timetable.Stop(m.station, m.platform, m.pass, dwell, Timetable.parseDepartTime(m.depart)));
		}
		Map<String, Integer> outRoutes = new LinkedHashMap<>();
		for (MutableRoute r : this.routes) {
			outRoutes.put(r.name, r.value.isEmpty() ? 0 : Math.min(15, Integer.parseInt(r.value)));
		}
		Timetable table = new Timetable(this.trainNumber, this.type, outRoutes, out, this.endMode);
		ClientPlayNetworking.send(new RouteCardUpdatePayload(this.hand == Hand.MAIN_HAND, table));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int x = this.left;
		int y = this.top;
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, y, TEXT);
		y += 14;
		context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.railwayvehicleaddon.route_card.train"), x, y + 5, TEXT);
		context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.railwayvehicleaddon.route_card.type"), x + 148, y + 5, TEXT);
		context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.railwayvehicleaddon.route_card.end"), x + 216, y + 5, TEXT);

		int listTop = y + 36;
		context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.railwayvehicleaddon.route_card.stops_header"), x, listTop - 12, TEXT_DIM);
		context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.railwayvehicleaddon.route_card.dwell"), x + 198, listTop - 12, TEXT_DIM);
		context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.railwayvehicleaddon.route_card.depart"), x + 252, listTop - 12, TEXT_DIM);
		int first = this.stopPage * STOP_ROWS;
		if (this.stops.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.railwayvehicleaddon.route_card.no_stops"), x, listTop + 5, TEXT_DIM);
		}
		for (int i = 0; i < STOP_ROWS && first + i < this.stops.size(); i++) {
			MutableStop stop = this.stops.get(first + i);
			String label = (first + i + 1) + ". " + stop.station + (stop.platform > 0 ? " " + stop.platform + "番線" : "");
			context.drawTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(label, 145)), x, listTop + i * ROW_H + 5, TEXT);
			context.drawTextWithShadow(this.textRenderer, Text.literal("秒"), x + 237, listTop + i * ROW_H + 5, TEXT_DIM);
		}
		int stopPagerY = listTop + STOP_ROWS * ROW_H;
		context.drawCenteredTextWithShadow(this.textRenderer, Text.literal((this.stopPage + 1) + " / " + stopPages()),
				x + 310, stopPagerY + 4, TEXT_DIM);

		int routeTop = stopPagerY + 34;
		context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.railwayvehicleaddon.route_card.routes_header"), x, routeTop - 12, TEXT_DIM);
		int firstRoute = this.routePage * ROUTE_ROWS;
		if (this.routes.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.railwayvehicleaddon.route_card.no_routes"), x, routeTop + 5, TEXT_DIM);
		}
		for (int i = 0; i < ROUTE_ROWS && firstRoute + i < this.routes.size(); i++) {
			MutableRoute route = this.routes.get(firstRoute + i);
			context.drawTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(route.name, 190)), x, routeTop + i * ROW_H + 5, TEXT);
		}
		context.drawCenteredTextWithShadow(this.textRenderer, Text.literal((this.routePage + 1) + " / " + routePages()),
				x + 310, routeTop + ROUTE_ROWS * ROW_H - 18, TEXT_DIM);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
