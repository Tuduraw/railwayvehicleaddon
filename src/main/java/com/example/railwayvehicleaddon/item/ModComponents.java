package com.example.railwayvehicleaddon.item;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.operation.Timetable;
import net.minecraft.component.ComponentType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/** このMODのアイテムのデータ部品。アイテムより先に登録する。 */
public final class ModComponents {
	/** 経路アイテムの運行表 */
	public static ComponentType<Timetable> ROUTE;

	private ModComponents() {
	}

	public static void register() {
		ROUTE = Registry.register(Registries.DATA_COMPONENT_TYPE, Identifier.of(RailwayVehicleAddon.MOD_ID, "route"),
				ComponentType.<Timetable>builder().codec(Timetable.CODEC).packetCodec(Timetable.PACKET_CODEC).cache().build());
	}
}
