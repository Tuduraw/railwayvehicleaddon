package com.example.railwayvehicleaddon.item;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.tudursvehiclemod.item.VehicleConverterTarget;
import net.minecraft.item.Item;
import net.minecraft.util.Identifier;

/** 前提MODのティア別スポーン・変換の仕組みに「鉄道車両」カテゴリとして登録する。 */
public record RailVehicleTarget() implements VehicleConverterTarget {

	@Override
	public Identifier entityTypeId() {
		return Identifier.of(RailwayVehicleAddon.MOD_ID, "rail_vehicle");
	}

	@Override
	public String translationKey() {
		return "item.railwayvehicleaddon.category.rail_vehicle";
	}

	@Override
	public Identifier id() {
		return Identifier.of(RailwayVehicleAddon.MOD_ID, "rail_vehicle");
	}

	@Override
	public Item[] tieredSpawnerItems() {
		return ModItems.RAIL_VEHICLE_SPAWNERS;
	}
}
