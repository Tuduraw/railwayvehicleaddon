package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.item.SurveyToolItem;
import com.example.railwayvehicleaddon.network.FeatureSyncPayload;
import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.network.PlaceResultPayload;
import com.example.railwayvehicleaddon.network.UncouplePayload;
import com.example.railwayvehicleaddon.network.HornPayload;
import com.example.railwayvehicleaddon.network.RailSoundPayload;
import com.example.railwayvehicleaddon.network.TrackRemovePayload;
import com.example.railwayvehicleaddon.network.TrackSyncPayload;
import com.example.tudursvehiclemod.client.hud.HudVariableProvider;
import com.example.tudursvehiclemod.client.render.VehicleEntityRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import com.example.railwayvehicleaddon.screen.ModScreenHandlers;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class RailwayVehicleAddonClient implements ClientModInitializer {
	private static KeyBinding confirmKey;
	private static KeyBinding clearKey;
	private static KeyBinding deleteKey;
	private static KeyBinding modeKey;
	private static KeyBinding paramUpKey;
	private static KeyBinding paramDownKey;
	private static KeyBinding ballastKey;
	private static KeyBinding forceKey;
	/** 連結解放キー(乗車中に押すと連結を外す)。測量ツールを持っていなくても使える */
	private static KeyBinding uncoupleKey;
	/** 警笛・汽笛キー(運転中) */
	private static KeyBinding hornKey;
	/**
	 * 敷設時の調整キー(架線の高さ↑↓・曲線半径のしきい値→←・リセットR)。既定のキーは本体MODの操作と重なるため、
	 * キー入力の配信(KeyBindingの押下状態)には頼らず、割り当てたキーの物理的な状態を直接読む。測量ツールを持ち、
	 * 画面を開いていないときだけ反応する。割り当ては操作設定で個別に変えられる。
	 */
	private static KeyBinding[] placementKeys;
	private static final boolean[] PLACEMENT_KEY_DOWN = new boolean[5];

	@Override
	public void onInitializeClient() {
		RailwayVehicleAddon.clientTrackNetwork = ClientTrackData::network;
		SurveyToolItem.clientHandler = SurveySession.INSTANCE;

		EntityRendererRegistry.register(RailwayVehicleAddon.RAIL_VEHICLE, VehicleEntityRenderer::new);

		HandledScreens.register(ModScreenHandlers.SUBSTATION, SubstationScreen::new);

		// 蒸気機関車の石炭・火室状況をHUDスクリプトへ公開する(rail_fire_seconds・rail_coal_count・rail_low_coal)
		HudVariableProvider.EVENT.register(RailHudVariables.INSTANCE);

		ClientPlayNetworking.registerGlobalReceiver(TrackSyncPayload.ID, (payload, context) ->
				context.client().execute(() -> ClientTrackData.apply(payload)));
		ClientPlayNetworking.registerGlobalReceiver(TrackRemovePayload.ID, (payload, context) ->
				context.client().execute(() -> ClientTrackData.apply(payload)));
		ClientPlayNetworking.registerGlobalReceiver(FeatureSyncPayload.ID, (payload, context) ->
				context.client().execute(() -> ClientTrackData.apply(payload)));
		ClientPlayNetworking.registerGlobalReceiver(PlaceResultPayload.ID, (payload, context) ->
				context.client().execute(() -> SurveySession.INSTANCE.onPlaceResult(payload.success())));
		ClientPlayNetworking.registerGlobalReceiver(RailSoundPayload.ID, (payload, context) ->
				context.client().execute(() -> RailSoundManager.playEvent(context.client(), payload.entityId(), payload.kind())));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			RailSoundManager.stopAll();
			ClientTrackData.clear();
			SurveySession.INSTANCE.clear();
		}));

		// 前提MODの既定キーと重ならないキーを既定にし、それぞれ個別に変更できるようにする
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(RailwayVehicleAddon.MOD_ID, "railway"));
		confirmKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.railwayvehicleaddon.survey_confirm", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, category));
		clearKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.railwayvehicleaddon.survey_clear", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_BACKSPACE, category));
		deleteKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.railwayvehicleaddon.survey_delete", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_DELETE, category));
		modeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.railwayvehicleaddon.survey_mode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, category));
		paramUpKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.railwayvehicleaddon.survey_param_up", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, category));
		paramDownKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.railwayvehicleaddon.survey_param_down", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, category));
		ballastKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.railwayvehicleaddon.survey_ballast", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_I, category));
		forceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.railwayvehicleaddon.survey_force", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_HOME, category));
		uncoupleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.railwayvehicleaddon.uncouple", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_END, category));
		hornKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.railwayvehicleaddon.horn", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, category));
		placementKeys = new KeyBinding[]{
				KeyBindingHelper.registerKeyBinding(new KeyBinding("key.railwayvehicleaddon.survey_wire_up", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UP, category)),
				KeyBindingHelper.registerKeyBinding(new KeyBinding("key.railwayvehicleaddon.survey_wire_down", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, category)),
				KeyBindingHelper.registerKeyBinding(new KeyBinding("key.railwayvehicleaddon.survey_radius_up", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, category)),
				KeyBindingHelper.registerKeyBinding(new KeyBinding("key.railwayvehicleaddon.survey_radius_down", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, category)),
				KeyBindingHelper.registerKeyBinding(new KeyBinding("key.railwayvehicleaddon.survey_placement_reset", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, category))};

		ClientTickEvents.END_CLIENT_TICK.register(RailwayVehicleAddonClient::onClientTick);
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(TrackRenderer::render);
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(FeatureRenderer::render);
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(DeviceOverlayRenderer::render);
	}

	private static void onClientTick(MinecraftClient client) {
		boolean holding = SurveySession.isHoldingTool(client.player);
		while (confirmKey.wasPressed()) {
			if (holding) {
				SurveySession.INSTANCE.confirm();
			}
		}
		while (clearKey.wasPressed()) {
			if (holding) {
				SurveySession.INSTANCE.clear();
			}
		}
		while (deleteKey.wasPressed()) {
			if (holding) {
				SurveySession.INSTANCE.deletePoints();
			}
		}
		while (modeKey.wasPressed()) {
			if (holding) {
				// スニーク中は逆順に切り替える
				SurveySession.INSTANCE.cycleMode(client.player.isSneaking() ? -1 : 1);
			}
		}
		while (paramUpKey.wasPressed()) {
			if (holding) {
				SurveySession.INSTANCE.adjustParam(1);
			}
		}
		while (paramDownKey.wasPressed()) {
			if (holding) {
				SurveySession.INSTANCE.adjustParam(-1);
			}
		}
		while (ballastKey.wasPressed()) {
			if (holding) {
				// 通常は道床の種類、スニーク中は電化方式を切り替える(同じキーを共用する)
				if (client.player.isSneaking()) {
					SurveySession.INSTANCE.cycleElectrification();
				} else {
					SurveySession.INSTANCE.cycleBallast();
				}
			}
		}
		pollPlacementKeys(client, holding);
		while (uncoupleKey.wasPressed()) {
			if (client.player != null && client.player.getVehicle() instanceof RailVehicleEntity) {
				ClientPlayNetworking.send(new UncouplePayload());
			}
		}
		while (hornKey.wasPressed()) {
			if (client.player != null && client.player.getVehicle() instanceof RailVehicleEntity) {
				ClientPlayNetworking.send(new HornPayload());
			}
		}
		RailSoundManager.tick(client);
		while (forceKey.wasPressed()) {
			if (holding) {
				SurveySession.INSTANCE.toggleForce();
			}
		}
		ClientTrackData.tickDecks();
		DeviceOverlayRenderer.tick(client);
		SurveySession.INSTANCE.tick(client);
		SurveyPreviewRenderer.tick(client);
	}

	/** 敷設時の調整キーを、割り当てたキーの物理的な状態から読む(押した瞬間だけ反応)。 */
	private static void pollPlacementKeys(MinecraftClient client, boolean holding) {
		for (int i = 0; i < placementKeys.length; i++) {
			KeyBinding binding = placementKeys[i];
			while (binding.wasPressed()) {
				// 配信された押下は使わない(読み捨てて溜めない)
			}
			InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(binding);
			boolean down = holding && client.currentScreen == null && key.getCategory() == InputUtil.Type.KEYSYM
					&& key.getCode() != GLFW.GLFW_KEY_UNKNOWN && InputUtil.isKeyPressed(client.getWindow(), key.getCode());
			if (down && !PLACEMENT_KEY_DOWN[i]) {
				switch (i) {
					case 0 -> SurveySession.INSTANCE.adjustWireHeight(1);
					case 1 -> SurveySession.INSTANCE.adjustWireHeight(-1);
					case 2 -> SurveySession.INSTANCE.adjustMinRadius(1);
					case 3 -> SurveySession.INSTANCE.adjustMinRadius(-1);
					default -> SurveySession.INSTANCE.resetPlacementSettings();
				}
			}
			PLACEMENT_KEY_DOWN[i] = down;
		}
	}
}
