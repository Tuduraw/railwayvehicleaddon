package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
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
	public static Block SUBSTATION;
	public static Block STOP_MARKER;
	public static Block TRAIN_NUMBER_DETECTOR;
	public static Block SPEED_LIMIT_SIGN;
	public static Block OPERATION_CONTROLLER;
	public static BlockEntityType<OperationControllerBlockEntity> OPERATION_CONTROLLER_ENTITY;
	public static BlockEntityType<WaysideBlockEntity> WAYSIDE_ENTITY;
	public static BlockEntityType<SubstationBlockEntity> SUBSTATION_ENTITY;
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
		DEVICE_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
				Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "track_device"),
				FabricBlockEntityTypeBuilder.create(TrackDeviceBlockEntity::new,
						MANUAL_SWITCH, SWITCH_MACHINE, DECK_PANEL, DECK_CONTROLLER, TRACK_DETECTOR).build());
		SIGNAL = register("signal", SignalBlock::new, true);
		CROSSING_GATE = register("crossing_gate", CrossingGateBlock::new, true);
		SIGNAL_DEVICE_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
				Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "signal_device"),
				FabricBlockEntityTypeBuilder.create(SignalDeviceBlockEntity::new, SIGNAL, CROSSING_GATE).build());
		STOP_MARKER = register("stop_marker", StopMarkerBlock::new, true);
		TRAIN_NUMBER_DETECTOR = register("train_number_detector", TrainNumberDetectorBlock::new);
		SPEED_LIMIT_SIGN = register("speed_limit_sign", SpeedLimitSignBlock::new, true);
		WAYSIDE_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
				Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "wayside"),
				FabricBlockEntityTypeBuilder.create(WaysideBlockEntity::new, STOP_MARKER, TRAIN_NUMBER_DETECTOR, SPEED_LIMIT_SIGN).build());
		OPERATION_CONTROLLER = register("operation_controller", OperationControllerBlock::new);
		OPERATION_CONTROLLER_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
				Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "operation_controller"),
				FabricBlockEntityTypeBuilder.create(OperationControllerBlockEntity::new, OPERATION_CONTROLLER).build());
		WATER_TOWER = register("water_tower", WaterTowerBlock::new);
		WATER_TOWER_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
				Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "water_tower"),
				FabricBlockEntityTypeBuilder.create(WaterTowerBlockEntity::new, WATER_TOWER).build());
		SUBSTATION = register("substation", SubstationBlock::new);
		SUBSTATION_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
				Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "substation"),
				FabricBlockEntityTypeBuilder.create(SubstationBlockEntity::new, SUBSTATION).build());
	}

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory) {
		return register(name, factory, false);
	}

	/** @param thin 立方体でない形(信号機など)。隣のブロックの面を隠さないようにする */
	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, boolean thin) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, name));
		BlockBehaviour.Properties settings = BlockBehaviour.Properties.of()
				.setId(blockKey)
				.mapColor(MapColor.METAL)
				.strength(2.0f);
		if (thin) {
			settings = settings.noOcclusion();
		}
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(settings));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, name));
		Item item = Registry.register(BuiltInRegistries.ITEM, itemKey,
				new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		ITEMS.add(item);
		return block;
	}
}
