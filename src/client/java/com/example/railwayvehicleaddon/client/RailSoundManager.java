package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.tudursvehiclemod.client.sound.AddonSoundLoader;
import com.example.tudursvehiclemod.client.sound.VanillaStyleSoundAttenuation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.openal.AL10;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 鉄道車両の動作音(クライアント)。前提MODのエンジン音と同じく、OGGはAddonSoundLoaderが読み込んだバッファを使い、
 * OpenALの音源を直接鳴らす(距離による音量は前提MODと同じVanillaStyleSoundAttenuationで手計算する)。
 *
 * <ul>
 *   <li>走行音(ループ): 速度に応じて音量・高さが上がる</li>
 *   <li>レールの継ぎ目: 車軸の位置が継ぎ目の間隔をまたぐたびに鳴る(台車ごとに2軸。前の台車から順に「タタン…タタン」)</li>
 *   <li>主電動機・エンジン(ループ): 電車・電気機関車は速度で高さが変わり、力行中に大きくなる。ディーゼルはスロットルで回転数が変わる</li>
 *   <li>蒸気機関車のドラフト音: 動輪の回転(空転を含む)に合わせ、1回転にchuffs_per_rev回</li>
 *   <li>ブレーキのきしみ(ループ): 停車間際に減速しているとき</li>
 *   <li>空気の排出: 停車した瞬間</li>
 *   <li>警笛・連結音: サーバーからの通知(RailSoundPayload)で鳴る</li>
 * </ul>
 */
public final class RailSoundManager {
	/** 距離減衰の基準(前提MODのエンジン音と同じ値に、車両ごとの volume と下の倍率を掛ける) */
	private static final float BASE_REFERENCE_DISTANCE = 8.0f;
	private static final float BASE_MAX_DISTANCE = 128.0f;
	private static final float LOOP_RANGE = 2.0f;
	private static final float HORN_RANGE = 4.0f;
	/** 継ぎ目の音を鳴らす台車の軸距(ブロック) */
	private static final double AXLE_SPACING = 2.1;

	private static final Map<Integer, State> STATES = new HashMap<>();
	private static final List<int[]> ONE_SHOTS = new ArrayList<>();
	/** いま処理している車両の位置(音源の位置。前提MODのエンジン音と同じく左右の定位に使う) */
	private static Vec3d currentPos = Vec3d.ZERO;

	private RailSoundManager() {
	}

	private static final class State {
		final Map<String, Integer> loops = new HashMap<>();
		final Map<String, Float> gains = new HashMap<>();
		/** 車軸ごとの、直前の線路上の位置(区間・継ぎ目の番号)と、最後に継ぎ目を通ってからの距離 */
		long[] axleSegment = new long[0];
		long[] axleJoint = new long[0];
		double[] axleSinceJoint = new double[0];
		int lastChuffSource = -1;
		double lastSpeed;
		double chuffIndex = Double.NaN;
		float motorLevel;
		float motorPitch = -1f;
		boolean moving;
	}

	public static void tick(MinecraftClient client) {
		if (client.world == null || client.isPaused()) {
			if (client.world == null) {
				stopAll();
			} else {
				pauseLoops();
			}
			cleanupOneShots();
			return;
		}
		Vec3d listener = client.gameRenderer.getCamera().getCameraPos();
		float master = categoryVolume(client);
		Set<Integer> present = new HashSet<>();
		for (Entity entity : client.world.getEntities()) {
			if (!(entity instanceof RailVehicleEntity vehicle) || vehicle.isRemoved()) {
				continue;
			}
			RailVehicleEntity.SoundSpec spec = vehicle.getSoundSpec();
			if (spec == null) {
				continue;
			}
			double distance = vehicle.getEntityPos().distanceTo(listener);
			float loopGain = VanillaStyleSoundAttenuation.computeGain(distance, spec.volume() * LOOP_RANGE,
					BASE_REFERENCE_DISTANCE, BASE_MAX_DISTANCE) * master;
			currentPos = vehicle.getEntityPos();
			State state = STATES.computeIfAbsent(vehicle.getId(), id -> new State());
			present.add(vehicle.getId());
			update(vehicle, spec, state, loopGain);
		}
		STATES.entrySet().removeIf(e -> {
			if (!present.contains(e.getKey())) {
				for (int source : e.getValue().loops.values()) {
					AL10.alSourceStop(source);
					AL10.alDeleteSources(source);
				}
				return true;
			}
			return false;
		});
		cleanupOneShots();
	}

	private static void update(RailVehicleEntity vehicle, RailVehicleEntity.SoundSpec spec, State state, float gain) {
		double v = vehicle.getRailSpeed();
		double speed = Math.abs(v);
		float maxSpeed = Math.max(0.05f, vehicle.getDefinition().maxSpeed());
		float speedFrac = (float) MathHelper.clamp(speed / maxSpeed, 0.0, 1.0);
		float throttle = MathHelper.clamp(Math.abs(vehicle.getThrottle()), 0f, 1f);

		// 走行音
		float runGain = (float) MathHelper.clamp(speed / 0.5, 0.0, 1.0);
		setLoop(state, "running", spec.running(), runGain * 0.6f * gain, 0.6f + 0.9f * speedFrac);

		// 主電動機・エンジン
		if (!spec.motor().isEmpty()) {
			float level;
			float pitch;
			if (spec.motorEngine()) {
				boolean alive = vehicle.hasPassengers() || speed > 0.01;
				level = alive ? 0.35f + 0.65f * throttle : 0f;
				pitch = MathHelper.lerp(throttle, spec.motorPitchMin(), spec.motorPitchMax());
			} else {
				level = speed > 0.003 || throttle > 0.01f
						? 0.12f * Math.min(1f, speedFrac * 6f) + 0.75f * throttle * Math.min(1f, 0.35f + speedFrac * 3f) : 0f;
				pitch = MathHelper.lerp(speedFrac, spec.motorPitchMin(), spec.motorPitchMax());
			}
			state.motorLevel += (level - state.motorLevel) * 0.15f;
			state.motorPitch = state.motorPitch < 0f ? pitch : state.motorPitch + (pitch - state.motorPitch) * 0.2f;
			setLoop(state, "motor", spec.motor(), state.motorLevel * 0.7f * gain, state.motorPitch);
		}

		// ブレーキのきしみ: 停車間際に減速しているとき
		boolean decelerating = speed < state.lastSpeed - 1.0e-4;
		boolean braking = decelerating && speed > 0.01 && speed < 0.3 && (vehicle.getSyncedBrakeInput() || throttle < 0.01f);
		// 停車直前ほど大きくなるが、上の方は頭打ちにする(小さい部分はほぼそのまま)
		float brakeTarget = braking ? (float) (0.32 * Math.tanh(1.7 * (1.0 - speed / 0.3))) : 0f;
		float brakeNow = state.gains.getOrDefault("brake_level", 0f);
		brakeNow += (brakeTarget - brakeNow) * 0.25f;
		state.gains.put("brake_level", brakeNow);
		setLoop(state, "brake", spec.brake(), brakeNow * gain, 1.0f + (float) speed * 0.4f);

		// 停車した瞬間の空気の排出
		if (state.moving && speed < 0.002) {
			playOneShot(spec.air(), 0.5f * gain, 1.0f);
		}
		if (speed > 0.05) {
			state.moving = true;
		} else if (speed < 0.002) {
			state.moving = false;
		}

		// レールの継ぎ目: 線路上にjoint_spacingごと(各区間の始点から)に継ぎ目があるものとし、車軸がそこを通ったら鳴らす。
		// 継ぎ目は線路に固定されているので、編成の各車両・各車軸が同じ継ぎ目を順に通って「タタン…タタン」となる。
		// 区間のつなぎ目も継ぎ目として扱う(ただし直前の継ぎ目から間隔の半分以上進んでいるときだけ)。
		if (spec.jointSpacing() > 0.5f && !spec.joint().isEmpty() && speed > 0.002) {
			double[] bogies = vehicle.getCarryBogieOffsets();
			int n = bogies.length * 2;
			if (state.axleSegment.length != n) {
				state.axleSegment = new long[n];
				state.axleJoint = new long[n];
				state.axleSinceJoint = new double[n];
				java.util.Arrays.fill(state.axleSegment, -1L);
			}
			float clickGain = (float) MathHelper.clamp(speed / 0.25, 0.15, 1.0) * gain;
			int i = 0;
			for (double bogie : bogies) {
				for (double axle : new double[]{bogie + AXLE_SPACING / 2, bogie - AXLE_SPACING / 2}) {
					com.example.railwayvehicleaddon.track.TrackPos pos = vehicle.clientTrackPosAt(axle);
					if (pos != null) {
						long joint = (long) Math.floor(pos.s() / spec.jointSpacing());
						boolean click;
						if (state.axleSegment[i] < 0) {
							click = false;
						} else if (pos.segmentId() == state.axleSegment[i]) {
							click = joint != state.axleJoint[i];
						} else {
							click = state.axleSinceJoint[i] > spec.jointSpacing() * 0.5;
						}
						state.axleSinceJoint[i] += speed;
						if (click) {
							playOneShot(spec.joint(), clickGain * 0.8f, 0.9f + (float) Math.random() * 0.2f);
							state.axleSinceJoint[i] = 0.0;
						}
						state.axleSegment[i] = pos.segmentId();
						state.axleJoint[i] = joint;
					}
					i++;
				}
			}
		}

		// 蒸気機関車のドラフト音
		if (!spec.chuff().isEmpty() && spec.chuffsPerRev() > 0) {
			double radius = vehicle.getDriveWheelRadius();
			if (radius <= 0.0) {
				radius = 0.8;
			}
			double index = Math.floor(vehicle.getWheelRolled() / radius * spec.chuffsPerRev() / (Math.PI * 2.0));
			if (!Double.isNaN(state.chuffIndex) && index != state.chuffIndex) {
				// 音量は一定。速く回るほど間隔が詰まり、前の音がまだ鳴っていれば打ち切って次を鳴らす
				// (再生の高さは変えずに、テンポだけが速くなる)
				if (state.lastChuffSource >= 0) {
					stopOneShot(state.lastChuffSource);
				}
				state.lastChuffSource = playOneShot(spec.chuff(), 0.8f * gain, 1.0f);
			}
			state.chuffIndex = index;
		}
		state.lastSpeed = speed;
	}

	/** サーバーから届いた一回きりの音。 */
	public static void playEvent(MinecraftClient client, int entityId, String kind) {
		if (client.world == null || !(client.world.getEntityById(entityId) instanceof RailVehicleEntity vehicle)) {
			return;
		}
		RailVehicleEntity.SoundSpec spec = vehicle.getSoundSpec();
		if (spec == null) {
			return;
		}
		currentPos = vehicle.getEntityPos();
		double distance = currentPos.distanceTo(client.gameRenderer.getCamera().getCameraPos());
		float master = categoryVolume(client);
		switch (kind) {
			case "horn" -> playOneShot(spec.horn(), VanillaStyleSoundAttenuation.computeGain(distance,
					spec.volume() * HORN_RANGE, BASE_REFERENCE_DISTANCE, BASE_MAX_DISTANCE) * master, 1.0f);
			case "couple" -> playOneShot(spec.couple(), VanillaStyleSoundAttenuation.computeGain(distance,
					spec.volume() * LOOP_RANGE, BASE_REFERENCE_DISTANCE, BASE_MAX_DISTANCE) * master, 0.95f + (float) Math.random() * 0.1f);
			default -> {
			}
		}
	}

	private static void setLoop(State state, String key, String sound, float gain, float pitch) {
		Integer source = state.loops.get(key);
		if (sound.isEmpty() || gain <= 0.001f) {
			if (source != null) {
				// 聞こえない音源は解放する(長い編成で音源を使い切らないように)
				AL10.alSourceStop(source);
				AL10.alDeleteSources(source);
				state.loops.remove(key);
			}
			return;
		}
		if (source == null) {
			Integer buffer = AddonSoundLoader.getBuffer(sound);
			if (buffer == null) {
				return;
			}
			source = AL10.alGenSources();
			AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
			AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_TRUE);
			AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0.0f);
			state.loops.put(key, source);
		}
		AL10.alSource3f(source, AL10.AL_POSITION, (float) currentPos.x, (float) currentPos.y, (float) currentPos.z);
		AL10.alSourcef(source, AL10.AL_GAIN, Math.min(1f, gain));
		AL10.alSourcef(source, AL10.AL_PITCH, MathHelper.clamp(pitch, 0.25f, 3.0f));
		if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
			AL10.alSourcePlay(source);
		}
	}

	private static int playOneShot(String sound, float gain, float pitch) {
		if (sound.isEmpty() || gain <= 0.001f || ONE_SHOTS.size() > 64) {
			return -1;
		}
		Integer buffer = AddonSoundLoader.getBuffer(sound);
		if (buffer == null) {
			return -1;
		}
		int source = AL10.alGenSources();
		AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
		AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0.0f);
		AL10.alSourcef(source, AL10.AL_GAIN, Math.min(1f, gain));
		AL10.alSourcef(source, AL10.AL_PITCH, pitch);
		AL10.alSource3f(source, AL10.AL_POSITION, (float) currentPos.x, (float) currentPos.y, (float) currentPos.z);
		AL10.alSourcePlay(source);
		ONE_SHOTS.add(new int[]{source});
		return source;
	}

	/** 鳴っている一回きりの音を止める(片付けはcleanupOneShotsで行う)。 */
	private static void stopOneShot(int source) {
		for (int[] s : ONE_SHOTS) {
			if (s[0] == source) {
				AL10.alSourceStop(source);
				return;
			}
		}
	}

	private static void cleanupOneShots() {
		Iterator<int[]> it = ONE_SHOTS.iterator();
		while (it.hasNext()) {
			int source = it.next()[0];
			if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
				AL10.alDeleteSources(source);
				it.remove();
			}
		}
	}

	private static void pauseLoops() {
		for (State state : STATES.values()) {
			for (int source : state.loops.values()) {
				AL10.alSourcePause(source);
			}
		}
	}

	/** ゲームの音量設定(マスター×「友好的な生物」)を掛ける。前提MODの乗り物と同じ扱いになるようNEUTRALを使う。 */
	private static float categoryVolume(MinecraftClient client) {
		return (float) (client.options.getSoundVolumeOption(SoundCategory.MASTER).getValue()
				* client.options.getSoundVolumeOption(SoundCategory.NEUTRAL).getValue());
	}

	/** すべての音を止めて片付ける(切断時・ワールドを離れたとき)。 */
	public static void stopAll() {
		for (State state : STATES.values()) {
			for (int source : state.loops.values()) {
				AL10.alSourceStop(source);
				AL10.alDeleteSources(source);
			}
		}
		STATES.clear();
		for (int[] s : ONE_SHOTS) {
			AL10.alSourceStop(s[0]);
			AL10.alDeleteSources(s[0]);
		}
		ONE_SHOTS.clear();
	}
}
