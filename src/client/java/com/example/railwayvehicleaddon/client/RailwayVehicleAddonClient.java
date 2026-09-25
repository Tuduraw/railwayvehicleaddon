package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.item.SurveyToolItem;
import com.example.railwayvehicleaddon.network.FeatureSyncPayload;
import com.example.railwayvehicleaddon.network.TrackRemovePayload;
import com.example.railwayvehicleaddon.network.TrackSyncPayload;
import com.example.tudursvehiclemod.client.render.VehicleEntityRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
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

	@Override
	public void onInitializeClient() {
		RailwayVehicleAddon.clientTrackNetwork = ClientTrackData::network;
		SurveyToolItem.clientHandler = SurveySession.INSTANCE;

		EntityRendererRegistry.register(RailwayVehicleAddon.RAIL_VEHICLE, VehicleEntityRenderer::new);

		ClientPlayNetworking.registerGlobalReceiver(TrackSyncPayload.ID, (payload, context) ->
				context.client().execute(() -> ClientTrackData.apply(payload)));
		ClientPlayNetworking.registerGlobalReceiver(TrackRemovePayload.ID, (payload, context) ->
				context.client().execute(() -> ClientTrackData.apply(payload)));
		ClientPlayNetworking.registerGlobalReceiver(FeatureSyncPayload.ID, (payload, context) ->
				context.client().execute(() -> ClientTrackData.apply(payload)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
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

		ClientTickEvents.END_CLIENT_TICK.register(RailwayVehicleAddonClient::onClientTick);
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(TrackRenderer::render);
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(FeatureRenderer::render);
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
		SurveySession.INSTANCE.tick(client);
		SurveyPreviewRenderer.tick(client);
	}
}
