package com.example.railwayvehicleaddon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 線路敷設に関する設定。config/railwayvehicleaddon.json に保存する。
 *
 * <p>プレビュー(クライアント)と確定時の検証(サーバー)で判定が食い違わないよう、
 * サーバーの値がログイン時に線路データと一緒にクライアントへ送られる
 * (TrackSyncPayload)。クライアントはこのファイルを直接は使わない。
 */
public final class RailwayConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("RailwayVehicleAddon/Config");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 軌間(ブロック)。1ブロック=1mとして標準軌1.435を既定値とする */
	public float gauge = 1.435f;
	/** 最小曲線半径(ブロック) */
	public float min_curve_radius = 12.0f;
	/** 最大勾配(高低差/水平距離)。0.1 = 100‰ */
	public float max_grade = 0.1f;
	/** 縦曲線の長さ(ブロック)。勾配変化点を中心に前後半分ずつ入る。0で縦曲線なし */
	public float vertical_curve_length = 16.0f;
	/** 建築限界の片側幅(線路中心から、ブロック) */
	public float clearance_half_width = 1.5f;
	/** 建築限界の高さ(線路基面から、ブロック) */
	public float clearance_height = 4.0f;
	/** この硬度以上のブロックは自動撤去できない(黒曜石=50、古代の残骸=30) */
	public float unbreakable_hardness = 30.0f;
	/** カント計算に使う設計速度(km/h) */
	public float design_speed_kmh = 80.0f;
	/** カントの逓減長(ブロック)。直線や別の曲線とのつなぎ目で、カントをこの長さをかけて滑らかに変化させる */
	public float cant_transition_length = 16.0f;
	/** 1回の敷設で指定できる経由点の最大数 */
	public int max_waypoints = 64;
	/** 1回の敷設の最大延長(水平距離、ブロック) */
	public float max_route_length = 2048.0f;
	/**
	 * 線路の最大描画距離(ブロック、クライアント側の設定)。この値がクライアントのチャンク表示距離
	 * (ブロック換算)を超える場合は、チャンク表示距離に合わせる(見えないチャンクの線路は
	 * どのみち描けないため)。0以下を指定するとチャンク表示距離をそのまま使う。
	 */
	public float track_render_distance = 256.0f;
	/**
	 * 撤去したブロックのドロップ(サバイバル時のみ。クリエイティブでは常にドロップしない)。
	 * "none" / "player_placed"(設置物のみ) / "all"
	 */
	public String drop_removed_blocks = "player_placed";

	private static RailwayConfig instance = new RailwayConfig();

	public static RailwayConfig get() {
		return instance;
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(RailwayVehicleAddon.MOD_ID + ".json");
		RailwayConfig loaded = null;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, RailwayConfig.class);
			} catch (Exception e) {
				LOGGER.error("Failed to read {}, using defaults", path, e);
			}
		}
		instance = loaded != null ? loaded : new RailwayConfig();
		// 欠けた項目を既定値で補った状態で書き戻す(新しい項目の追加時にファイルへ反映するため)
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to write {}", path, e);
		}
	}

	/** クライアントへ同期する値の組。 */
	public Values values() {
		return new Values(this.gauge, this.min_curve_radius, this.max_grade, this.vertical_curve_length,
				this.clearance_half_width, this.clearance_height, this.unbreakable_hardness, this.design_speed_kmh,
				this.max_waypoints, this.max_route_length, this.cant_transition_length, this.track_render_distance);
	}

	public record Values(float gauge, float minCurveRadius, float maxGrade, float verticalCurveLength,
						 float clearanceHalfWidth, float clearanceHeight, float unbreakableHardness,
						 float designSpeedKmh, int maxWaypoints, float maxRouteLength, float cantTransitionLength,
						 float trackRenderDistance) {
		public static final Values DEFAULT = new RailwayConfig().values();
	}
}
