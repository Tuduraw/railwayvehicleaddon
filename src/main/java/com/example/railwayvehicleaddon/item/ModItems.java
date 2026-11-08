package com.example.railwayvehicleaddon.item;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.tudursvehiclemod.item.TieredVehicleSpawnerItem;
import com.example.tudursvehiclemod.item.VehicleConverterTargets;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ModItems {
	public static final RailVehicleTarget RAIL_VEHICLE_TARGET = new RailVehicleTarget();
	public static final Item[] RAIL_VEHICLE_SPAWNERS = new Item[5];
	public static Item SURVEY_TOOL;
	/** 連結てこ: 車両を右クリックして、近い方の端の連結・連結解除を行う */
	public static Item COUPLING_LEVER;
	/** 経路アイテム(運行表): 停止位置目標・列車番号検知器を右クリックして登録し、運行管理装置に入れて使う */
	public static Item ROUTE_CARD;

	public static final ResourceKey<CreativeModeTab> GROUP =
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "railway"));

	private ModItems() {
	}

	public static void register() {
		ResourceKey<Item> surveyKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "survey_tool"));
		SURVEY_TOOL = Registry.register(BuiltInRegistries.ITEM, surveyKey,
				new SurveyToolItem(new Item.Properties().setId(surveyKey).stacksTo(1)));
		ResourceKey<Item> leverKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "coupling_lever"));
		COUPLING_LEVER = Registry.register(BuiltInRegistries.ITEM, leverKey,
				new Item(new Item.Properties().setId(leverKey).stacksTo(1)));
		ResourceKey<Item> routeKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "route_card"));
		ROUTE_CARD = Registry.register(BuiltInRegistries.ITEM, routeKey,
				new RouteCardItem(new Item.Properties().setId(routeKey).stacksTo(1)));

		for (int tier = 1; tier <= 5; tier++) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM,
					Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "rail_vehicle_spawner_t" + tier));
			RAIL_VEHICLE_SPAWNERS[tier - 1] = Registry.register(BuiltInRegistries.ITEM, key,
					new TieredVehicleSpawnerItem(new Item.Properties().setId(key).stacksTo(1), RAIL_VEHICLE_TARGET, tier));
		}
		// 前提MODの変換ブロック・車両回収にも対応させる(登録は初期化時のみ行うこと)
		VehicleConverterTargets.register(RAIL_VEHICLE_TARGET);

		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, GROUP, FabricItemGroup.builder()
				.icon(() -> new ItemStack(SURVEY_TOOL))
				.title(Component.translatable("itemGroup.railwayvehicleaddon.railway"))
				.build());
		ItemGroupEvents.modifyEntriesEvent(GROUP).register(entries -> {
			entries.accept(SURVEY_TOOL);
			entries.accept(COUPLING_LEVER);
			entries.accept(ROUTE_CARD);
			for (Item item : RAIL_VEHICLE_SPAWNERS) {
				entries.accept(item);
			}
			for (Item item : com.example.railwayvehicleaddon.block.ModBlocks.ITEMS) {
				entries.accept(item);
			}
		});
	}
}
