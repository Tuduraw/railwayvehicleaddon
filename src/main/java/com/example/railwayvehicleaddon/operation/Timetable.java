package com.example.railwayvehicleaddon.operation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.StringIdentifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 運行表。経路アイテム(RouteCardItem)に保存して作り、運行管理装置が編成の車両へ割り当てる(車両にも保存する)。
 *
 * <ul>
 *   <li>trainNumber: 列車番号(表示用)</li>
 *   <li>type: 種別(0〜15。列車番号検知器が出す)</li>
 *   <li>routes: 列車番号検知器の名前 → 経路番号(0〜15。列車番号検知器が出す)</li>
 *   <li>stops: 駅の並び(上から順にたどる)</li>
 *   <li>endMode: 最後の駅の後の動作。停止/折り返し(駅を逆順にたどる)/繰り返し(最初の駅へ)</li>
 * </ul>
 */
public record Timetable(String trainNumber, int type, Map<String, Integer> routes, List<Stop> stops, EndMode endMode) {
	/** 駅の数の上限 */
	public static final int MAX_STOPS = 256;
	/** 経路番号の数の上限 */
	public static final int MAX_ROUTES = 64;
	/** 名前(駅名・検知器の名前・列車番号)の文字数の上限 */
	public static final int MAX_NAME = 48;
	/** 停車時間の上限(秒) */
	public static final int MAX_DWELL = 3600;
	/** 発車時刻なし */
	public static final int NO_DEPART_TIME = -1;

	public enum EndMode implements StringIdentifiable {
		STOP("stop"), REVERSE("reverse"), LOOP("loop");

		public static final Codec<EndMode> CODEC = StringIdentifiable.createCodec(EndMode::values);
		private final String id;

		EndMode(String id) {
			this.id = id;
		}

		@Override
		public String asString() {
			return this.id;
		}

		public EndMode next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	/**
	 * 運行表の駅。
	 *
	 * @param platform   番線(0は指定なし)
	 * @param pass       通過
	 * @param dwell      停車時間(秒)
	 * @param departTime 発車時刻(ゲーム内の時刻を0時からの分で。0〜1439、{@link #NO_DEPART_TIME}で指定なし)
	 */
	public record Stop(String station, int platform, boolean pass, int dwell, int departTime) {
		public static final Codec<Stop> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("station").forGetter(Stop::station),
				Codec.INT.optionalFieldOf("platform", 0).forGetter(Stop::platform),
				Codec.BOOL.optionalFieldOf("pass", false).forGetter(Stop::pass),
				Codec.INT.optionalFieldOf("dwell", 0).forGetter(Stop::dwell),
				Codec.INT.optionalFieldOf("depart", NO_DEPART_TIME).forGetter(Stop::departTime)
		).apply(instance, Stop::new));

		public Stop(String station, int platform, boolean pass, int dwell) {
			this(station, platform, pass, dwell, NO_DEPART_TIME);
		}

		public String describe() {
			return this.station + (this.platform > 0 ? " " + this.platform + "番線" : "") + (this.pass ? "(通過)" : "");
		}

		Stop sanitized() {
			return new Stop(clip(this.station), Math.max(0, Math.min(99, this.platform)), this.pass,
					Math.max(0, Math.min(MAX_DWELL, this.dwell)),
					this.departTime < 0 ? NO_DEPART_TIME : Math.min(1439, this.departTime));
		}
	}

	public static final Codec<Timetable> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.optionalFieldOf("train", "").forGetter(Timetable::trainNumber),
			Codec.INT.optionalFieldOf("type", 0).forGetter(Timetable::type),
			Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("routes", Map.of()).forGetter(Timetable::routes),
			Stop.CODEC.listOf().optionalFieldOf("stops", List.of()).forGetter(Timetable::stops),
			EndMode.CODEC.optionalFieldOf("end", EndMode.STOP).forGetter(Timetable::endMode)
	).apply(instance, Timetable::new));

	public static final PacketCodec<RegistryByteBuf, Timetable> PACKET_CODEC = PacketCodecs.registryCodec(CODEC);

	public static final Timetable EMPTY = new Timetable("", 0, Map.of(), List.of(), EndMode.STOP);

	public Timetable {
		trainNumber = trainNumber == null ? "" : trainNumber;
		routes = routes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(routes));
		stops = stops == null ? List.of() : List.copyOf(stops);
		endMode = endMode == null ? EndMode.STOP : endMode;
	}

	/** 列車番号検知器nameに対する番号(書いていなければ0)。 */
	public int routeValue(String name) {
		return this.routes.getOrDefault(name.trim(), 0);
	}

	/** 駅が1つも無いか。 */
	public boolean isEmpty() {
		return this.stops.isEmpty();
	}

	/** 上限・範囲に収めた運行表(クライアントから届いた内容をアイテムへ保存する前に使う)。 */
	public Timetable sanitized() {
		Map<String, Integer> r = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : this.routes.entrySet()) {
			String name = clip(e.getKey());
			if (!name.isEmpty() && r.size() < MAX_ROUTES) {
				r.put(name, clamp(e.getValue() == null ? 0 : e.getValue()));
			}
		}
		List<Stop> s = new ArrayList<>();
		for (Stop stop : this.stops) {
			if (stop != null && s.size() < MAX_STOPS) {
				Stop clean = stop.sanitized();
				if (!clean.station().isEmpty()) {
					s.add(clean);
				}
			}
		}
		return new Timetable(clip(this.trainNumber), clamp(this.type), r, s, this.endMode);
	}

	public Timetable withStops(List<Stop> newStops) {
		return new Timetable(this.trainNumber, this.type, this.routes, newStops, this.endMode);
	}

	public Timetable withRoutes(Map<String, Integer> newRoutes) {
		return new Timetable(this.trainNumber, this.type, newRoutes, this.stops, this.endMode);
	}

	private static String clip(String text) {
		String t = text == null ? "" : text.replace('　', ' ').trim();
		return t.length() > MAX_NAME ? t.substring(0, MAX_NAME) : t;
	}

	private static int clamp(int value) {
		return Math.max(0, Math.min(15, value));
	}

	// ------------------------------------------------------------------ 発車時刻

	/** 発車時刻(0時からの分)を「HH:MM」に。指定なしは空文字。 */
	public static String formatDepartTime(int minutes) {
		if (minutes < 0) {
			return "";
		}
		return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
	}

	/** 「HH:MM」「H:MM」「HHMM」を0時からの分に。空・読めなければ{@link #NO_DEPART_TIME}。 */
	public static int parseDepartTime(String text) {
		String t = text == null ? "" : text.trim().replace('：', ':');
		if (t.isEmpty()) {
			return NO_DEPART_TIME;
		}
		int h;
		int m;
		try {
			int colon = t.indexOf(':');
			if (colon >= 0) {
				h = Integer.parseInt(t.substring(0, colon).trim());
				m = Integer.parseInt(t.substring(colon + 1).trim());
			} else if (t.length() >= 3 && t.length() <= 4) {
				h = Integer.parseInt(t.substring(0, t.length() - 2));
				m = Integer.parseInt(t.substring(t.length() - 2));
			} else {
				return NO_DEPART_TIME;
			}
		} catch (NumberFormatException e) {
			return NO_DEPART_TIME;
		}
		if (h < 0 || h > 23 || m < 0 || m > 59) {
			return NO_DEPART_TIME;
		}
		return h * 60 + m;
	}

	/** ワールドの時刻(tick)から、ゲーム内の時刻(0時からの分)。0tick = 6:00。 */
	public static int minutesOfDay(long timeOfDay) {
		long t = Math.floorMod(timeOfDay + 6000L, 24000L);
		return (int) (t * 1440L / 24000L);
	}

	// ------------------------------------------------------------------ 名前の読み取り

	/** 文字列の最初の数字の並び(全角数字も可)。無ければfallback。 */
	static int firstInt(String text, int fallback) {
		StringBuilder digits = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c >= '０' && c <= '９') {
				c = (char) ('0' + (c - '０'));
			}
			if (c >= '0' && c <= '9') {
				digits.append(c);
			} else if (!digits.isEmpty()) {
				break;
			}
		}
		if (digits.isEmpty() || digits.length() > 9) {
			return fallback;
		}
		return Integer.parseInt(digits.toString());
	}

	private static final java.util.regex.Pattern ATTACHED_WITH_SUFFIX =
			java.util.regex.Pattern.compile("^(.+?)([0-9０-９]+)(番線|番)$");
	private static final java.util.regex.Pattern ATTACHED_AFTER_STATION =
			java.util.regex.Pattern.compile("^(.+駅)([0-9０-９]+)$");

	/**
	 * 空白を入れずに番線を続けて書いた名前(「A駅1番線」「A駅1番」「A駅1」)を駅名と番線に分ける。
	 * 「番線」「番」が付いているか、駅名が「駅」で終わる場合だけ分ける(「新宿3」のような駅名はそのまま)。
	 * 分けられなければnull。
	 */
	static Stop splitAttached(String text) {
		java.util.regex.Matcher m = ATTACHED_WITH_SUFFIX.matcher(text);
		if (!m.matches()) {
			m = ATTACHED_AFTER_STATION.matcher(text);
			if (!m.matches()) {
				return null;
			}
		}
		int platform = firstInt(m.group(2), -1);
		String station = m.group(1).trim();
		if (platform < 0 || station.isEmpty()) {
			return null;
		}
		return new Stop(station, platform, false, 0);
	}

	/**
	 * 停止位置目標の名前(「A駅 1」「A駅 1番線」「A駅1番線」「A駅1」)から駅名と番線を取り出す。番線が無ければ0。
	 */
	public static Stop parseMarkerName(String name) {
		String text = name == null ? "" : name.replace('　', ' ').trim();
		if (text.isEmpty()) {
			return new Stop("", 0, false, 0);
		}
		String[] tokens = text.split("\\s+");
		if (tokens.length >= 2) {
			String last = tokens[tokens.length - 1];
			int platform = firstInt(last, -1);
			if (platform >= 0 && (Character.isDigit(last.charAt(0)) || (last.charAt(0) >= '０' && last.charAt(0) <= '９'))) {
				return new Stop(String.join(" ", java.util.Arrays.copyOf(tokens, tokens.length - 1)), platform, false, 0);
			}
		}
		Stop attached = tokens.length == 1 ? splitAttached(text) : null;
		return attached != null ? attached : new Stop(text, 0, false, 0);
	}
}
