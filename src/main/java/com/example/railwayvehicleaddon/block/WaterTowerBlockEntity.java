package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.vehicle.RailVehicleParams;
import com.example.railwayvehicleaddon.vehicle.RailVehicleParamsLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** 給水塔の動作。一定間隔で近くの停車中の蒸気機関車を探して水を足す。 */
public class WaterTowerBlockEntity extends BlockEntity {
	private static final int INTERVAL = 10;
	private static final double RANGE = 5.0;
	/** 1回(INTERVALごと)に補給する量(満タンに対する割合)。約25秒で満タンになる */
	private static final float FILL_RATIO = 0.02f;
	/** 停車中とみなす速さ(ブロック/tick) */
	private static final double STOPPED_SPEED = 0.02;

	private int timer;

	public WaterTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.WATER_TOWER_ENTITY, pos, state);
	}

	public static void tick(Level world, BlockPos pos, BlockState state, WaterTowerBlockEntity entity) {
		if (world.isClientSide() || ++entity.timer < INTERVAL) {
			return;
		}
		entity.timer = 0;
		AABB area = new AABB(pos).inflate(RANGE, 4.0, RANGE);
		for (RailVehicleEntity vehicle : world.getEntitiesOfClass(RailVehicleEntity.class, area, e -> true)) {
			RailVehicleParams params = RailVehicleParamsLoader.get(vehicle.getVehicleDefinitionId()).orElse(RailVehicleParams.DEFAULT);
			if (!RailVehicleParams.STEAM.equals(params.powerSource()) || Math.abs(vehicle.getRailSpeed()) > STOPPED_SPEED) {
				continue;
			}
			float missing = vehicle.getMaxFuel() - vehicle.getFuel();
			if (missing > 0f) {
				vehicle.tudursvehiclemod$addFuel(Math.min(missing, vehicle.getMaxFuel() * FILL_RATIO));
			}
		}
	}
}
