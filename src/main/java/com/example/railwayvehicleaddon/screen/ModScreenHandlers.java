package com.example.railwayvehicleaddon.screen;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.MenuType;

public final class ModScreenHandlers {
	public static MenuType<SubstationScreenHandler> SUBSTATION;
	public static MenuType<OperationControllerScreenHandler> OPERATION_CONTROLLER;

	private ModScreenHandlers() {
	}

	public static void register() {
		SUBSTATION = Registry.register(BuiltInRegistries.MENU,
				Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "substation"),
				new MenuType<>(SubstationScreenHandler::new, FeatureFlagSet.of()));
		OPERATION_CONTROLLER = Registry.register(BuiltInRegistries.MENU,
				Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "operation_controller"),
				new MenuType<>(OperationControllerScreenHandler::new, FeatureFlagSet.of()));
	}
}
