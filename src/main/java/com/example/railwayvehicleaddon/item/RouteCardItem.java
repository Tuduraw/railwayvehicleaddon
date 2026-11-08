package com.example.railwayvehicleaddon.item;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.block.StopMarkerBlock;
import com.example.railwayvehicleaddon.block.TrainNumberDetectorBlock;
import com.example.railwayvehicleaddon.block.WaysideBlockEntity;
import com.example.railwayvehicleaddon.operation.Timetable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

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
	public static Consumer<InteractionHand> clientOpener = hand -> {
	};

	public RouteCardItem(Properties settings) {
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
	public InteractionResult useOn(UseOnContext context) {
		Level world = context.getLevel();
		Block block = world.getBlockState(context.getClickedPos()).getBlock();
		boolean marker = block instanceof StopMarkerBlock;
		boolean detector = block instanceof TrainNumberDetectorBlock;
		if (!marker && !detector) {
			return InteractionResult.PASS;
		}
		Player player = context.getPlayer();
		if (world.isClientSide() || player == null || !(world.getBlockEntity(context.getClickedPos()) instanceof WaysideBlockEntity entity)) {
			return InteractionResult.SUCCESS;
		}
		ItemStack stack = context.getItemInHand();
		Timetable table = get(stack);
		if (marker) {
			Timetable.Stop parsed = Timetable.parseMarkerName(entity.name());
			if (parsed.station().isEmpty()) {
				player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.route_card.unnamed_marker"), true);
				return InteractionResult.SUCCESS;
			}
			if (table.stops().size() >= Timetable.MAX_STOPS) {
				player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.route_card.full", Timetable.MAX_STOPS), true);
				return InteractionResult.SUCCESS;
			}
			List<Timetable.Stop> stops = new ArrayList<>(table.stops());
			stops.add(new Timetable.Stop(parsed.station(), parsed.platform(), false,
					Math.max(0, RailwayConfig.get().ato_default_dwell_seconds), Timetable.NO_DEPART_TIME));
			set(stack, table.withStops(stops));
			player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.route_card.stop_added",
					stops.get(stops.size() - 1).describe(), stops.size()), true);
		} else {
			String name = entity.name();
			if (name.isEmpty()) {
				player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.route_card.unnamed_detector"), true);
				return InteractionResult.SUCCESS;
			}
			if (table.routes().containsKey(name)) {
				player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.route_card.route_exists", name), true);
				return InteractionResult.SUCCESS;
			}
			if (table.routes().size() >= Timetable.MAX_ROUTES) {
				player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.route_card.full", Timetable.MAX_ROUTES), true);
				return InteractionResult.SUCCESS;
			}
			Map<String, Integer> routes = new LinkedHashMap<>(table.routes());
			routes.put(name, 0);
			set(stack, table.withRoutes(routes));
			player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.route_card.route_added", name), true);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult use(Level world, Player player, InteractionHand hand) {
		if (world.isClientSide()) {
			clientOpener.accept(hand);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay displayComponent,
							  Consumer<Component> textConsumer, TooltipFlag type) {
		Timetable table = get(stack);
		if (!table.trainNumber().isEmpty()) {
			textConsumer.accept(Component.translatable("item.railwayvehicleaddon.route_card.train", table.trainNumber()).withStyle(ChatFormatting.GRAY));
		}
		List<Timetable.Stop> stops = table.stops();
		if (stops.isEmpty()) {
			textConsumer.accept(Component.translatable("item.railwayvehicleaddon.route_card.empty").withStyle(ChatFormatting.DARK_GRAY));
		} else {
			textConsumer.accept(Component.translatable("item.railwayvehicleaddon.route_card.stops", stops.size(),
					stops.get(0).describe(), stops.get(stops.size() - 1).describe()).withStyle(ChatFormatting.GRAY));
		}
		if (!table.routes().isEmpty()) {
			textConsumer.accept(Component.translatable("item.railwayvehicleaddon.route_card.routes", table.routes().size()).withStyle(ChatFormatting.GRAY));
		}
	}
}
