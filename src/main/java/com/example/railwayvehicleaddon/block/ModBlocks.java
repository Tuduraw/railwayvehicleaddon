package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class ModBlocks {
	public static Block MANUAL_SWITCH;
	public static Block SWITCH_MACHINE;
	public static Block DECK_PANEL;
	public static Block DECK_CONTROLLER;
	public static Block TRACK_DETECTOR;
	public static Block SIGNAL;
	public static Block CROSSING_GATE;
	public static BlockEntityType<TrackDeviceBlockEntity> DEVICE_ENTITY;
	public static BlockEntityType<SignalDeviceBlockEntity> SIGNAL_DEVICE_ENTITY;
	public static Block WATER_TOWER;
	public static BlockEntityType<WaterTowerBlockEntity> WATER_TOWER_ENTITY;
	/** クリエイティブタブに並べる順 */
	public static final List<Item> ITEMS = new ArrayList<>();

	private ModBlocks() {
	}

	public static void register() {
		MANUAL_SWITCH = register("manual_switch", ManualSwitchBlock::new);
		SWITCH_MACHINE = register("switch_machine", SwitchMachineBlock::new);
		DECK_PANEL = register("deck_panel", DeckPanelBlock::new);
		DECK_CONTROLLER = register("deck_controller", DeckControllerBlock::new);
		TRACK_DETECTOR = register("track_detector", TrackDetectorBlock::new);
		DEVICE_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE,
				Identifier.of(RailwayVehicleAddon.MOD_ID, "track_device"),
				FabricBlockEntityTypeBuilder.create(TrackDeviceBlockEntity::new,
						MANUAL_SWITCH, SWITCH_MACHINE, DECK_PANEL, DECK_CONTROLLER, TRACK_DETECTOR).build());
		SIGNAL = register("signal", SignalBlock::new, true);
		CROSSING_GATE = register("crossing_gate", CrossingGateBlock::new, true);
		SIGNAL_DEVICE_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE,
				Identifier.of(RailwayVehicleAddon.MOD_ID, "signal_device"),
				FabricBlockEntityTypeBuilder.create(SignalDeviceBlockEntity::new, SIGNAL, CROSSING_GATE).build());
		WATER_TOWER = register("water_tower", WaterTowerBlock::new);
		WATER_TOWER_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE,
				Identifier.of(RailwayVehicleAddon.MOD_ID, "water_tower"),
				FabricBlockEntityTypeBuilder.create(WaterTowerBlockEntity::new, WATER_TOWER).build());
	}

	private static Block register(String name, Function<AbstractBlock.Settings, Block> factory) {
		return register(name, factory, false);
	}

	/** @param thin 立方体でない形(信号機など)。隣のブロックの面を隠さないようにする */
	private static Block register(String name, Function<AbstractBlock.Settings, Block> factory, boolean thin) {
		RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(RailwayVehicleAddon.MOD_ID, name));
		AbstractBlock.Settings settings = AbstractBlock.Settings.create()
				.registryKey(blockKey)
				.mapColor(MapColor.IRON_GRAY)
				.strength(2.0f);
		if (thin) {
			settings = settings.nonOpaque();
		}
		Block block = Registry.register(Registries.BLOCK, blockKey, factory.apply(settings));
		RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(RailwayVehicleAddon.MOD_ID, name));
		Item item = Registry.register(Registries.ITEM, itemKey,
				new BlockItem(block, new Item.Settings().registryKey(itemKey).useBlockPrefixedTranslationKey()));
		ITEMS.add(item);
		return block;
	}
}
