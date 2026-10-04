package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.vehicle.RailVehicleParams;
import com.example.railwayvehicleaddon.vehicle.RailVehicleParamsLoader;
import com.example.tudursvehiclemod.client.hud.HudVariableProvider;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Map;

/**
 * 蒸気機関車のHUD向けに、火室の残り燃焼時間と、インベントリに残っている石炭・木炭の個数を
 * 前提MODのHUDスクリプトへ公開する(`rail_fire_seconds`・`rail_coal_count`・`rail_low_coal`)。
 * 水の量は前提MODの"fuel"をそのまま流用しているため、ここでは扱わない。
 * 蒸気機関車以外(気動車・電車)では何も追加しない。
 */
public final class RailHudVariables implements HudVariableProvider {
	public static final RailHudVariables INSTANCE = new RailHudVariables();

	private RailHudVariables() {
	}

	@Override
	public void provideNumeric(MinecraftClient client, PlayerEntity player, AbstractVehicleEntity vehicle, Map<String, Double> vars) {
		if (!(vehicle instanceof RailVehicleEntity rail)) {
			return;
		}
		RailVehicleParams params = RailVehicleParamsLoader.get(rail.getVehicleDefinitionId()).orElse(RailVehicleParams.DEFAULT);
		if (!RailVehicleParams.STEAM.equals(params.powerSource())) {
			return;
		}
		float fireSeconds = rail.getFireSeconds();
		int coalCount = rail.getCoalCount();
		vars.put("rail_fire_seconds", (double) fireSeconds);
		vars.put("rail_coal_count", (double) coalCount);
		// low_fuel(前提MOD本体)と同じ点滅(10tickごと)にそろえる
		boolean lowCoal = fireSeconds <= 0.0f && coalCount <= 0;
		boolean blinkOn = (vehicle.getEntityWorld().getTime() / 10L) % 2L == 0L;
		vars.put("rail_low_coal", lowCoal && blinkOn ? 1.0 : 0.0);
	}
}
