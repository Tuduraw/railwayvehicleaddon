package com.example.railwayvehicleaddon.track.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

import java.util.HashMap;
import java.util.Map;

/** 設備の種類と、その保存・同期用コーデックの登録先。 */
public final class FeatureTypes {
	private static final Map<String, MapCodec<? extends TrackFeature>> TYPES = new HashMap<>();

	public static final Codec<TrackFeature> CODEC = Codec.STRING.dispatch("type", TrackFeature::type, FeatureTypes::codec);

	static {
		register(TurntableFeature.TYPE, TurntableFeature.CODEC);
		register(TraverserFeature.TYPE, TraverserFeature.CODEC);
		register(BufferStopFeature.TYPE, BufferStopFeature.CODEC);
	}

	private FeatureTypes() {
	}

	/** 他のアドオンからも呼べる。初期化時にのみ呼ぶこと。 */
	public static synchronized void register(String type, MapCodec<? extends TrackFeature> codec) {
		if (TYPES.putIfAbsent(type, codec) != null) {
			throw new IllegalArgumentException("Duplicate track feature type: " + type);
		}
	}

	private static MapCodec<? extends TrackFeature> codec(String type) {
		MapCodec<? extends TrackFeature> codec = TYPES.get(type);
		if (codec == null) {
			throw new IllegalArgumentException("Unknown track feature type: " + type);
		}
		return codec;
	}
}
