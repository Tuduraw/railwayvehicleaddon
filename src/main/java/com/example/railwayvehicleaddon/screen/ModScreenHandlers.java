package com.example.railwayvehicleaddon.screen;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;

public final class ModScreenHandlers {
	public static ScreenHandlerType<SubstationScreenHandler> SUBSTATION;
	public static ScreenHandlerType<OperationControllerScreenHandler> OPERATION_CONTROLLER;

	private ModScreenHandlers() {
	}

	public static void register() {
		SUBSTATION = Registry.register(Registries.SCREEN_HANDLER,
				Identifier.of(RailwayVehicleAddon.MOD_ID, "substation"),
				new ScreenHandlerType<>(SubstationScreenHandler::new, FeatureSet.empty()));
		OPERATION_CONTROLLER = Registry.register(Registries.SCREEN_HANDLER,
				Identifier.of(RailwayVehicleAddon.MOD_ID, "operation_controller"),
				new ScreenHandlerType<>(OperationControllerScreenHandler::new, FeatureSet.empty()));
	}
}
