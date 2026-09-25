package com.example.railwayvehicleaddon.vehicle;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.tudursvehiclemod.asset.AddonPaths;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 車両JSONの "rail" オブジェクトを読み込む。前提MODのVehicleDefinitionReloadListenerと
 * 同じ場所(データパックの data/<ns>/vehicles/ と tudursvehiclemod-addons/<pack>/data/<ns>/vehicles/)
 * を同じ規則で走査するため、車両IDも前提MODと一致する。
 */
public final class RailVehicleParamsLoader implements SimpleSynchronousResourceReloadListener {
	private static final Logger LOGGER = LoggerFactory.getLogger("RailwayVehicleAddon/Params");
	private static final String DIRECTORY = "vehicles";
	private static final String SUFFIX = ".json";

	private static volatile Map<Identifier, RailVehicleParams> params = Map.of();

	public static Optional<RailVehicleParams> get(Identifier vehicleId) {
		return Optional.ofNullable(params.get(vehicleId));
	}

	@Override
	public Identifier getFabricId() {
		return Identifier.of(RailwayVehicleAddon.MOD_ID, "rail_vehicle_params");
	}

	@Override
	public void reload(ResourceManager manager) {
		Map<Identifier, RailVehicleParams> loaded = new HashMap<>();
		for (Map.Entry<Identifier, Resource> entry :
				manager.findResources(DIRECTORY, id -> id.getPath().endsWith(SUFFIX)).entrySet()) {
			Identifier fileId = entry.getKey();
			String path = fileId.getPath();
			Identifier vehicleId = Identifier.of(fileId.getNamespace(),
					path.substring(DIRECTORY.length() + 1, path.length() - SUFFIX.length()));
			try (Reader reader = entry.getValue().getReader()) {
				parse(vehicleId, JsonParser.parseReader(reader), loaded);
			} catch (Exception e) {
				LOGGER.error("Failed to read rail params of {}", vehicleId, e);
			}
		}
		for (Path addonDir : AddonPaths.listSubdirectories(AddonPaths.getAddonsRoot())) {
			for (Path namespaceDir : AddonPaths.listSubdirectories(addonDir.resolve("data"))) {
				String namespace = namespaceDir.getFileName().toString();
				Path vehiclesDir = namespaceDir.resolve(DIRECTORY);
				if (!Files.isDirectory(vehiclesDir)) {
					continue;
				}
				try (var files = Files.walk(vehiclesDir)) {
					for (Path jsonFile : (Iterable<Path>) files.filter(p -> p.toString().endsWith(SUFFIX))::iterator) {
						String relative = vehiclesDir.relativize(jsonFile).toString().replace('\\', '/');
						Identifier vehicleId = Identifier.of(namespace, relative.substring(0, relative.length() - SUFFIX.length()));
						try (BufferedReader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
							parse(vehicleId, JsonParser.parseReader(reader), loaded);
						} catch (Exception e) {
							LOGGER.error("Failed to read rail params of {} ({})", vehicleId, jsonFile, e);
						}
					}
				} catch (Exception e) {
					LOGGER.error("Failed to scan {}", vehiclesDir, e);
				}
			}
		}
		params = Map.copyOf(loaded);
		LOGGER.info("Loaded rail params for {} vehicle(s)", loaded.size());
	}

	private static void parse(Identifier vehicleId, JsonElement json, Map<Identifier, RailVehicleParams> out) {
		if (!(json instanceof JsonObject object) || !object.has("rail")) {
			return;
		}
		RailVehicleParams.CODEC.parse(JsonOps.INSTANCE, object.get("rail"))
				.resultOrPartial(error -> LOGGER.error("Invalid \"rail\" in vehicle '{}': {}", vehicleId, error))
				.ifPresent(p -> out.put(vehicleId, p));
	}
}
