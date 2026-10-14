package com.example.railwayvehicleaddon.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 運行表。本と羽根ペン(または署名した本)に書いて、車両を右クリックして読み込ませる。
 *
 * <pre>
 * 運行表
 * 列車番号: 1234M
 * 種別: 2
 * 経路 東口: 2
 * 駅: A駅 1番線 停車 30秒
 * 駅: B駅 2 通過
 * 終点: 折り返し
 * </pre>
 *
 * <ul>
 *   <li>1行目(最初の空でない行)に「運行表」または「timetable」と書いた本だけを運行表として扱う</li>
 *   <li>区切りは「:」(全角の「:」も可)。項目名は英語(train / type / route / station / end)でもよい</li>
 *   <li>経路: 列車番号検知器の名前ごとの番号(0〜15)。列車番号検知器はこの番号を出力する</li>
 *   <li>駅: 駅名、番線(省略すると番線を問わない)、停車/通過、停車時間(秒)。進路の自動設定は、
 *       次の駅の停止位置目標(駅名と番線が合うもの)へ向かう進路を開通させる</li>
 *   <li>終点: 最後の駅の後の動作。停止(既定)/折り返し(駅を逆順にたどる)/繰り返し(最初の駅へ)</li>
 * </ul>
 */
public final class Timetable {
	public enum EndMode {
		STOP, REVERSE, LOOP
	}

	/**
	 * 運行表の駅。
	 *
	 * @param platform 番線(0は指定なし)
	 * @param pass     通過
	 * @param dwell    停車時間(秒。自動運転で使う予定)
	 */
	public record Stop(String station, int platform, boolean pass, int dwell) {
		public String describe() {
			return this.station + (this.platform > 0 ? " " + this.platform + "番線" : "") + (this.pass ? "(通過)" : "");
		}
	}

	private final String source;
	private final String trainNumber;
	private final int type;
	private final Map<String, Integer> routes;
	private final List<Stop> stops;
	private final EndMode endMode;

	private Timetable(String source, String trainNumber, int type, Map<String, Integer> routes, List<Stop> stops, EndMode endMode) {
		this.source = source;
		this.trainNumber = trainNumber;
		this.type = type;
		this.routes = Collections.unmodifiableMap(routes);
		this.stops = List.copyOf(stops);
		this.endMode = endMode;
	}

	public String source() {
		return this.source;
	}

	public String trainNumber() {
		return this.trainNumber;
	}

	public int type() {
		return this.type;
	}

	public List<Stop> stops() {
		return this.stops;
	}

	public EndMode endMode() {
		return this.endMode;
	}

	/** 列車番号検知器nameに対する番号(書いていなければ0)。 */
	public int routeValue(String name) {
		return this.routes.getOrDefault(name.trim(), 0);
	}

	/** 中身が無い(項目が1つも無い)か。中身の無い運行表を読み込ませると、車両の運行表を消す。 */
	public boolean isEmpty() {
		return this.trainNumber.isEmpty() && this.type == 0 && this.routes.isEmpty() && this.stops.isEmpty();
	}

	/** 運行表として読めればその内容、運行表でない(1行目に「運行表」が無い)ならnull。 */
	public static Timetable parse(String text) {
		if (text == null) {
			return null;
		}
		String[] lines = text.replace('　', ' ').replace('\r', '\n').split("\n");
		int first = 0;
		while (first < lines.length && lines[first].trim().isEmpty()) {
			first++;
		}
		if (first >= lines.length) {
			return null;
		}
		String header = lines[first].trim().toLowerCase(Locale.ROOT);
		if (!header.startsWith("運行表") && !header.startsWith("timetable")) {
			return null;
		}
		String trainNumber = "";
		int type = 0;
		Map<String, Integer> routes = new LinkedHashMap<>();
		List<Stop> stops = new ArrayList<>();
		EndMode end = EndMode.STOP;
		for (int i = first + 1; i < lines.length; i++) {
			String line = lines[i].trim();
			if (line.isEmpty()) {
				continue;
			}
			int colon = indexOfColon(line);
			if (colon < 0) {
				continue;
			}
			String key = line.substring(0, colon).trim();
			String value = line.substring(colon + 1).trim();
			String lower = key.toLowerCase(Locale.ROOT);
			if (lower.equals("列車番号") || lower.equals("train")) {
				trainNumber = value;
			} else if (lower.equals("種別") || lower.equals("type")) {
				type = clamp(firstInt(value, 0));
			} else if (lower.startsWith("経路") || lower.startsWith("route")) {
				String name = key.substring(lower.startsWith("経路") ? 2 : 5).trim();
				int number;
				if (name.isEmpty()) {
					// 「経路: 東口 2」の書き方
					String[] tokens = value.split("\\s+");
					if (tokens.length < 2) {
						continue;
					}
					number = firstInt(tokens[tokens.length - 1], 0);
					name = String.join(" ", java.util.Arrays.copyOf(tokens, tokens.length - 1)).trim();
				} else {
					number = firstInt(value, 0);
				}
				if (!name.isEmpty()) {
					routes.put(name, clamp(number));
				}
			} else if (lower.equals("駅") || lower.equals("station")) {
				Stop stop = parseStop(value);
				if (stop != null) {
					stops.add(stop);
				}
			} else if (lower.equals("終点") || lower.equals("end")) {
				String v = value.toLowerCase(Locale.ROOT);
				end = v.contains("折") || v.startsWith("rev") ? EndMode.REVERSE
						: v.contains("繰") || v.startsWith("loop") || v.startsWith("rep") ? EndMode.LOOP : EndMode.STOP;
			}
		}
		return new Timetable(text, trainNumber, type, routes, stops, end);
	}

	private static Stop parseStop(String value) {
		String[] tokens = value.split("\\s+");
		if (tokens.length == 0 || tokens[0].isEmpty()) {
			return null;
		}
		String station = tokens[0];
		int platform = 0;
		boolean pass = false;
		int dwell = 0;
		for (int i = 1; i < tokens.length; i++) {
			String t = tokens[i].toLowerCase(Locale.ROOT);
			if (t.contains("通過") || t.equals("pass")) {
				pass = true;
			} else if (t.contains("停車") || t.equals("stop")) {
				pass = false;
			} else if (t.endsWith("秒") || t.endsWith("s") || t.endsWith("sec")) {
				dwell = Math.max(0, firstInt(t, 0));
			} else if (Character.isDigit(t.charAt(0))) {
				platform = Math.max(0, firstInt(t, 0));
			}
		}
		return new Stop(station, platform, pass, dwell);
	}

	private static int indexOfColon(String line) {
		int a = line.indexOf(':');
		int b = line.indexOf('：');
		if (a < 0) {
			return b;
		}
		return b < 0 ? a : Math.min(a, b);
	}

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

	private static int clamp(int value) {
		return Math.max(0, Math.min(15, value));
	}

	/**
	 * 停止位置目標の名前(「A駅 1」「A駅 1番線」)から駅名と番線を取り出す。番線が無ければ0。
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
		return new Stop(text, 0, false, 0);
	}
}
