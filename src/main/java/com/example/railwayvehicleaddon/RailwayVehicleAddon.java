package com.example.railwayvehicleaddon;

import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.block.DeviceLinks;
import com.example.railwayvehicleaddon.block.ModBlocks;
import com.example.railwayvehicleaddon.item.ModItems;
import com.example.railwayvehicleaddon.network.ElectrifyPayload;
import com.example.railwayvehicleaddon.network.LinkDevicePayload;
import com.example.railwayvehicleaddon.survey.SurveyModes;
import com.example.railwayvehicleaddon.network.FeatureActionPayload;
import com.example.railwayvehicleaddon.network.FeatureSyncPayload;
import com.example.railwayvehicleaddon.network.PlaceLayoutPayload;
import com.example.railwayvehicleaddon.network.PlaceResultPayload;
import com.example.railwayvehicleaddon.network.RemoveSegmentsPayload;
import com.example.railwayvehicleaddon.network.ToggleSwitchPayload;
import com.example.railwayvehicleaddon.network.TrackRemovePayload;
import com.example.railwayvehicleaddon.network.TrackSyncPayload;
import com.example.railwayvehicleaddon.track.TrackManager;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.vehicle.RailVehicleParamsLoader;
import com.example.tudursvehiclemod.registry.ModEntityTypes;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.entity.EntityType;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.util.function.Supplier;

public class RailwayVehicleAddon implements ModInitializer {
	public static final String MOD_ID = "railwayvehicleaddon";

	public static EntityType<RailVehicleEntity> RAIL_VEHICLE;

	/**
	 * クライアントが持つ線路データの写し。共通コード(RailVehicleEntity)からクライアント専用
	 * クラスを参照しないための橋渡しで、クライアント初期化時に差し替えられる。
	 * 専用サーバーでは常にnullを返す。
	 */
	public static Supplier<TrackNetwork> clientTrackNetwork = () -> null;

	@Override
	public void onInitialize() {
		RailwayConfig.load();

		RAIL_VEHICLE = ModEntityTypes.registerAddonVehicleType(
				Identifier.of(MOD_ID, "rail_vehicle"), RailVehicleEntity::new, 2.0f, 2.0f);

		ModBlocks.register();
		ModItems.register();

		ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new RailVehicleParamsLoader());

		// モードの登録順を確定させる(サーバーとクライアントで同じ順序にするため初期化時に読み込む)
		SurveyModes.all();

		PayloadTypeRegistry.playC2S().register(PlaceLayoutPayload.ID, PlaceLayoutPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RemoveSegmentsPayload.ID, RemoveSegmentsPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ToggleSwitchPayload.ID, ToggleSwitchPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(FeatureActionPayload.ID, FeatureActionPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(LinkDevicePayload.ID, LinkDevicePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ElectrifyPayload.ID, ElectrifyPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(FeatureSyncPayload.ID, FeatureSyncPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(PlaceResultPayload.ID, PlaceResultPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TrackSyncPayload.ID, TrackSyncPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TrackRemovePayload.ID, TrackRemovePayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(PlaceLayoutPayload.ID, (payload, context) ->
				context.server().execute(() -> TrackManager.placeLayout(context.player(), payload.modeId(), payload.input())));
		ServerPlayNetworking.registerGlobalReceiver(RemoveSegmentsPayload.ID, (payload, context) ->
				context.server().execute(() -> TrackManager.removeSegments(context.player(), payload.segmentIds(), payload.featureIds())));
		ServerPlayNetworking.registerGlobalReceiver(ToggleSwitchPayload.ID, (payload, context) ->
				context.server().execute(() -> TrackManager.toggleSwitch(context.player(), payload.nodeId())));
		ServerPlayNetworking.registerGlobalReceiver(FeatureActionPayload.ID, (payload, context) ->
				context.server().execute(() -> TrackManager.featureAction(context.player(), payload.featureId(), payload.step())));

		ServerPlayNetworking.registerGlobalReceiver(LinkDevicePayload.ID, (payload, context) ->
				context.server().execute(() -> DeviceLinks.linkFromTool(context.player(), payload.pos(), payload.targetId())));

		ServerPlayNetworking.registerGlobalReceiver(ElectrifyPayload.ID, (payload, context) ->
				context.server().execute(() -> TrackManager.electrify(context.player(), payload.segmentIds())));

		// 転車台・遷車台の動作、変電所による給電の計算
		ServerTickEvents.END_WORLD_TICK.register(TrackManager::tickFeatures);
		ServerTickEvents.END_WORLD_TICK.register(TrackManager::tickElectricalSupply);

		// 線路データはディメンション単位なので、プレイヤーのいるディメンションが変わるたびに全量を送り直す
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> TrackManager.sendFullSync(handler.player));
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
				TrackManager.sendFullSync(player));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> TrackManager.sendFullSync(newPlayer));
	}
}
