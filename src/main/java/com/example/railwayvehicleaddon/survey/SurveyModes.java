package com.example.railwayvehicleaddon.survey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 測量ツールのモードの登録先。登録順がモード切替キーでの巡回順になる。 */
public final class SurveyModes {
	private static final List<SurveyMode> MODES = new ArrayList<>();

	public static final SurveyMode NEW_ROUTE = register(new NewRouteMode());
	public static final SurveyMode BRANCH = register(new BranchMode());
	public static final SurveyMode CROSSOVER = register(new CrossoverMode());
	public static final SurveyMode TURNTABLE = register(new TurntableMode());
	public static final SurveyMode TRAVERSER = register(new TraverserMode());
	public static final SurveyMode BUFFER_STOP = register(new BufferStopMode());
	public static final SurveyMode REMOVE = register(new RemoveMode());

	private SurveyModes() {
	}

	/** 他のアドオンからも呼べる。初期化時にのみ呼ぶこと(サーバーとクライアントで同じ順序にするため)。 */
	public static synchronized <T extends SurveyMode> T register(T mode) {
		for (SurveyMode existing : MODES) {
			if (existing.id().equals(mode.id())) {
				throw new IllegalArgumentException("Duplicate survey mode id: " + mode.id());
			}
		}
		MODES.add(mode);
		return mode;
	}

	public static List<SurveyMode> all() {
		return Collections.unmodifiableList(MODES);
	}

	public static SurveyMode byId(String id) {
		for (SurveyMode mode : MODES) {
			if (mode.id().equals(id)) {
				return mode;
			}
		}
		return null;
	}

	public static SurveyMode next(SurveyMode current, int step) {
		int index = MODES.indexOf(current);
		int size = MODES.size();
		return MODES.get(((index + step) % size + size) % size);
	}
}
