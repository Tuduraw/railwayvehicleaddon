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
 * 鉄道車両のHUD向けの変数を前提MODのHUDスクリプトへ公開する。
 * <ul>
 *   <li>すべての鉄道車両: `rail_seat`(見ているプレイヤーの座席番号、0始まり。乗っていなければ-1)、
 *       `rail_is_driver`(運転席に座っていれば1)。HUDスクリプトは車両ごとに1つで全座席共通のため、
 *       運転者と砲手で表示を分けるのに使う(装甲列車など)</li>
 *   <li>蒸気機関車のみ: `rail_fire_seconds`・`rail_coal_count`・`rail_low_coal`(火室の残り燃焼時間と、
 *       インベントリに残っている石炭・木炭の個数)。水の量は前提MODの"fuel"をそのまま流用している</li>
 * </ul>
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
		int seat = rail.tudursvehiclemod$getAssignedSeatIndex(player);
		vars.put("rail_seat", (double) seat);
		java.util.List<com.example.tudursvehiclemod.asset.SeatDefinition> seats = rail.getDefinition().seats();
		boolean driverSeat = seat >= 0 && seat < seats.size() && seats.get(seat).driver();
		vars.put("rail_is_driver", driverSeat ? 1.0 : 0.0);
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
