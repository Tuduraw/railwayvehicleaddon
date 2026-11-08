package com.example.railwayvehicleaddon.item;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.operation.Timetable;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** このMODのアイテムのデータ部品。アイテムより先に登録する。 */
public final class ModComponents {
	/** 経路アイテムの運行表 */
	public static DataComponentType<Timetable> ROUTE;

	private ModComponents() {
	}

	public static void register() {
		ROUTE = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "route"),
				DataComponentType.<Timetable>builder().persistent(Timetable.CODEC).networkSynchronized(Timetable.PACKET_CODEC).cacheEncoding().build());
	}
}
