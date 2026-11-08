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
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.resources.Identifier;
import com.example.railwayvehicleaddon.screen.ModScreenHandlers;
import org.lwjgl.glfw.GLFW;

public class RailwayVehicleAddonClient implements ClientModInitializer {
	private static KeyMapping confirmKey;
	private static KeyMapping clearKey;
	private static KeyMapping deleteKey;
	private static KeyMapping modeKey;
	private static KeyMapping paramUpKey;
	private static KeyMapping paramDownKey;
	private static KeyMapping ballastKey;
	private static KeyMapping forceKey;
	/** 連結解放キー(乗車中に押すと連結を外す)。測量ツールを持っていなくても使える */
	private static KeyMapping uncoupleKey;
	/** 警笛・汽笛キー(運転中) */
	private static KeyMapping hornKey;
	/** ATOの入切キー(乗車中) */
	private static KeyMapping atoKey;
	/**
	 * 敷設時の調整キー(架線の高さ↑↓・曲線半径のしきい値→←・リセットR)。既定のキーは本体MODの操作と重なるため、
	 * キー入力の配信(KeyBindingの押下状態)には頼らず、割り当てたキーの物理的な状態を直接読む。測量ツールを持ち、
	 * 画面を開いていないときだけ反応する。割り当ては操作設定で個別に変えられる。
	 */
	private static KeyMapping[] placementKeys;
	private static final boolean[] PLACEMENT_KEY_DOWN = new boolean[5];

	@Override
	public void onInitializeClient() {
		RailwayVehicleAddon.clientTrackNetwork = ClientTrackData::network;
		SurveyToolItem.clientHandler = SurveySession.INSTANCE;

		EntityRendererRegistry.register(RailwayVehicleAddon.RAIL_VEHICLE, VehicleEntityRenderer::new);

		MenuScreens.register(ModScreenHandlers.SUBSTATION, SubstationScreen::new);
		MenuScreens.register(ModScreenHandlers.OPERATION_CONTROLLER, OperationControllerScreen::new);
		ClientPlayNetworking.registerGlobalReceiver(com.example.railwayvehicleaddon.network.StopMarkerEditPayload.ID, (payload, context) ->
				context.client().execute(() -> context.client().setScreen(new StopMarkerNameScreen(payload.pos(), payload.name()))));
		com.example.railwayvehicleaddon.item.RouteCardItem.clientOpener = hand -> {
			Minecraft client = Minecraft.getInstance();
			if (client.player != null) {
				client.setScreen(new RouteCardScreen(hand, client.player.getItemInHand(hand)));
			}
		};

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
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "railway"));
		confirmKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.railwayvehicleaddon.survey_confirm", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, category));
		clearKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.railwayvehicleaddon.survey_clear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSPACE, category));
		deleteKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.railwayvehicleaddon.survey_delete", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DELETE, category));
		modeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.railwayvehicleaddon.survey_mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, category));
		paramUpKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.railwayvehicleaddon.survey_param_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, category));
		paramDownKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.railwayvehicleaddon.survey_param_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, category));
		ballastKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.railwayvehicleaddon.survey_ballast", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, category));
		forceKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.railwayvehicleaddon.survey_force", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_HOME, category));
		uncoupleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.railwayvehicleaddon.uncouple", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_END, category));
		hornKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.railwayvehicleaddon.horn", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, category));
		atoKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.railwayvehicleaddon.ato", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_INSERT, category));
		placementKeys = new KeyMapping[]{
				KeyBindingHelper.registerKeyBinding(new KeyMapping("key.railwayvehicleaddon.survey_wire_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, category)),
				KeyBindingHelper.registerKeyBinding(new KeyMapping("key.railwayvehicleaddon.survey_wire_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, category)),
				KeyBindingHelper.registerKeyBinding(new KeyMapping("key.railwayvehicleaddon.survey_radius_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, category)),
				KeyBindingHelper.registerKeyBinding(new KeyMapping("key.railwayvehicleaddon.survey_radius_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, category)),
				KeyBindingHelper.registerKeyBinding(new KeyMapping("key.railwayvehicleaddon.survey_placement_reset", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category))};

		ClientTickEvents.END_CLIENT_TICK.register(RailwayVehicleAddonClient::onClientTick);
		// 乗車は入力処理(前提MODの右クリック乗車)より先に判定する
		ClientTickEvents.START_CLIENT_TICK.register(RailBoarding::tick);
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(TrackRenderer::render);
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(FeatureRenderer::render);
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(DeviceOverlayRenderer::render);
	}

	private static void onClientTick(Minecraft client) {
		while (atoKey.consumeClick()) {
			if (client.player != null && client.player.getVehicle() instanceof com.example.railwayvehicleaddon.entity.RailVehicleEntity) {
				ClientPlayNetworking.send(new com.example.railwayvehicleaddon.network.AtoTogglePayload());
			}
		}
		boolean holding = SurveySession.isHoldingTool(client.player);
		while (confirmKey.consumeClick()) {
			if (holding) {
				SurveySession.INSTANCE.confirm();
			}
		}
		while (clearKey.consumeClick()) {
			if (holding) {
				SurveySession.INSTANCE.clear();
			}
		}
		while (deleteKey.consumeClick()) {
			if (holding) {
				SurveySession.INSTANCE.deletePoints();
			}
		}
		while (modeKey.consumeClick()) {
			if (holding) {
				// スニーク中は逆順に切り替える
				SurveySession.INSTANCE.cycleMode(client.player.isShiftKeyDown() ? -1 : 1);
			}
		}
		while (paramUpKey.consumeClick()) {
			if (holding) {
				SurveySession.INSTANCE.adjustParam(1);
			}
		}
		while (paramDownKey.consumeClick()) {
			if (holding) {
				SurveySession.INSTANCE.adjustParam(-1);
			}
		}
		while (ballastKey.consumeClick()) {
			if (holding) {
				// 通常は道床の種類、スニーク中は電化方式を切り替える(同じキーを共用する)
				if (client.player.isShiftKeyDown()) {
					SurveySession.INSTANCE.cycleElectrification();
				} else {
					SurveySession.INSTANCE.cycleBallast();
				}
			}
		}
		pollPlacementKeys(client, holding);
		while (uncoupleKey.consumeClick()) {
			if (client.player != null && client.player.getVehicle() instanceof RailVehicleEntity) {
				ClientPlayNetworking.send(new UncouplePayload());
			}
		}
		while (hornKey.consumeClick()) {
			if (client.player != null && client.player.getVehicle() instanceof RailVehicleEntity) {
				ClientPlayNetworking.send(new HornPayload());
			}
		}
		RailSoundManager.tick(client);
		while (forceKey.consumeClick()) {
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
	private static void pollPlacementKeys(Minecraft client, boolean holding) {
		for (int i = 0; i < placementKeys.length; i++) {
			KeyMapping binding = placementKeys[i];
			while (binding.consumeClick()) {
				// 配信された押下は使わない(読み捨てて溜めない)
			}
			InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(binding);
			boolean down = holding && client.screen == null && key.getType() == InputConstants.Type.KEYSYM
					&& key.getValue() != GLFW.GLFW_KEY_UNKNOWN && InputConstants.isKeyDown(client.getWindow(), key.getValue());
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
