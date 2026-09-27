package com.example.railwayvehicleaddon.item;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.tudursvehiclemod.item.TieredVehicleSpawnerItem;
import com.example.tudursvehiclemod.item.VehicleConverterTargets;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class ModItems {
	public static final RailVehicleTarget RAIL_VEHICLE_TARGET = new RailVehicleTarget();
	public static final Item[] RAIL_VEHICLE_SPAWNERS = new Item[5];
	public static Item SURVEY_TOOL;

	public static final RegistryKey<ItemGroup> GROUP =
			RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(RailwayVehicleAddon.MOD_ID, "railway"));

	private ModItems() {
	}

	public static void register() {
		RegistryKey<Item> surveyKey = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(RailwayVehicleAddon.MOD_ID, "survey_tool"));
		SURVEY_TOOL = Registry.register(Registries.ITEM, surveyKey,
				new SurveyToolItem(new Item.Settings().registryKey(surveyKey).maxCount(1)));

		for (int tier = 1; tier <= 5; tier++) {
			RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM,
					Identifier.of(RailwayVehicleAddon.MOD_ID, "rail_vehicle_spawner_t" + tier));
			RAIL_VEHICLE_SPAWNERS[tier - 1] = Registry.register(Registries.ITEM, key,
					new TieredVehicleSpawnerItem(new Item.Settings().registryKey(key).maxCount(1), RAIL_VEHICLE_TARGET, tier));
		}
		// 前提MODの変換ブロック・車両回収にも対応させる(登録は初期化時のみ行うこと)
		VehicleConverterTargets.register(RAIL_VEHICLE_TARGET);

		Registry.register(Registries.ITEM_GROUP, GROUP, FabricItemGroup.builder()
				.icon(() -> new ItemStack(SURVEY_TOOL))
				.displayName(Text.translatable("itemGroup.railwayvehicleaddon.railway"))
				.build());
		ItemGroupEvents.modifyEntriesEvent(GROUP).register(entries -> {
			entries.add(SURVEY_TOOL);
			for (Item item : RAIL_VEHICLE_SPAWNERS) {
				entries.add(item);
			}
			for (Item item : com.example.railwayvehicleaddon.block.ModBlocks.ITEMS) {
				entries.add(item);
			}
		});
	}
}
