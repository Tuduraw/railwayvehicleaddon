package com.example.railwayvehicleaddon.item;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.block.StopMarkerBlock;
import com.example.railwayvehicleaddon.block.TrainNumberDetectorBlock;
import com.example.railwayvehicleaddon.block.WaysideBlockEntity;
import com.example.railwayvehicleaddon.operation.Timetable;
import net.minecraft.block.Block;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 経路アイテム(運行表)。ドローンの経路帳と同じく、持ち歩いて経路を作り、運行管理装置のスロットに入れて使う。
 * <ul>
 *   <li>停止位置目標を右クリック: その駅(駅名・番線)を経路の最後に追加する</li>
 *   <li>列車番号検知器を右クリック: その検知器を経路番号の一覧に追加する(番号は設定画面で決める)</li>
 *   <li>それ以外(何もない所など)で右クリック: 設定画面を開く(列車番号・種別・終点、駅ごとの停車/通過・
 *       停車時間・発車時刻、並べ替え・削除、経路番号)</li>
 * </ul>
 */
public class RouteCardItem extends Item {
	/** 設定画面を開く処理(クライアントの初期化で差し替える。共通コードからクライアント専用のクラスを参照しないため) */
	public static Consumer<Hand> clientOpener = hand -> {
	};

	public RouteCardItem(Settings settings) {
		super(settings);
	}

	public static Timetable get(ItemStack stack) {
		Timetable table = stack.get(ModComponents.ROUTE);
		return table == null ? Timetable.EMPTY : table;
	}

	public static void set(ItemStack stack, Timetable table) {
		if (table == null || (table.isEmpty() && table.routes().isEmpty() && table.trainNumber().isEmpty())) {
			stack.remove(ModComponents.ROUTE);
		} else {
			stack.set(ModComponents.ROUTE, table);
		}
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		Block block = world.getBlockState(context.getBlockPos()).getBlock();
		boolean marker = block instanceof StopMarkerBlock;
		boolean detector = block instanceof TrainNumberDetectorBlock;
		if (!marker && !detector) {
			return ActionResult.PASS;
		}
		PlayerEntity player = context.getPlayer();
		if (world.isClient() || player == null || !(world.getBlockEntity(context.getBlockPos()) instanceof WaysideBlockEntity entity)) {
			return ActionResult.SUCCESS;
		}
		ItemStack stack = context.getStack();
		Timetable table = get(stack);
		if (marker) {
			Timetable.Stop parsed = Timetable.parseMarkerName(entity.name());
			if (parsed.station().isEmpty()) {
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.route_card.unnamed_marker"), true);
				return ActionResult.SUCCESS;
			}
			if (table.stops().size() >= Timetable.MAX_STOPS) {
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.route_card.full", Timetable.MAX_STOPS), true);
				return ActionResult.SUCCESS;
			}
			List<Timetable.Stop> stops = new ArrayList<>(table.stops());
			stops.add(new Timetable.Stop(parsed.station(), parsed.platform(), false,
					Math.max(0, RailwayConfig.get().ato_default_dwell_seconds), Timetable.NO_DEPART_TIME));
			set(stack, table.withStops(stops));
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.route_card.stop_added",
					stops.get(stops.size() - 1).describe(), stops.size()), true);
		} else {
			String name = entity.name();
			if (name.isEmpty()) {
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.route_card.unnamed_detector"), true);
				return ActionResult.SUCCESS;
			}
			if (table.routes().containsKey(name)) {
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.route_card.route_exists", name), true);
				return ActionResult.SUCCESS;
			}
			if (table.routes().size() >= Timetable.MAX_ROUTES) {
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.route_card.full", Timetable.MAX_ROUTES), true);
				return ActionResult.SUCCESS;
			}
			Map<String, Integer> routes = new LinkedHashMap<>(table.routes());
			routes.put(name, 0);
			set(stack, table.withRoutes(routes));
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.route_card.route_added", name), true);
		}
		return ActionResult.SUCCESS;
	}

	@Override
	public ActionResult use(World world, PlayerEntity player, Hand hand) {
		if (world.isClient()) {
			clientOpener.accept(hand);
		}
		return ActionResult.SUCCESS;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, net.minecraft.component.type.TooltipDisplayComponent displayComponent,
							  Consumer<Text> textConsumer, TooltipType type) {
		Timetable table = get(stack);
		if (!table.trainNumber().isEmpty()) {
			textConsumer.accept(Text.translatable("item.railwayvehicleaddon.route_card.train", table.trainNumber()).formatted(Formatting.GRAY));
		}
		List<Timetable.Stop> stops = table.stops();
		if (stops.isEmpty()) {
			textConsumer.accept(Text.translatable("item.railwayvehicleaddon.route_card.empty").formatted(Formatting.DARK_GRAY));
		} else {
			textConsumer.accept(Text.translatable("item.railwayvehicleaddon.route_card.stops", stops.size(),
					stops.get(0).describe(), stops.get(stops.size() - 1).describe()).formatted(Formatting.GRAY));
		}
		if (!table.routes().isEmpty()) {
			textConsumer.accept(Text.translatable("item.railwayvehicleaddon.route_card.routes", table.routes().size()).formatted(Formatting.GRAY));
		}
	}
}
