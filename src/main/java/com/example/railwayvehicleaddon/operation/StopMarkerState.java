package com.example.railwayvehicleaddon.operation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 停止位置目標の保存先(world/&lt;dim&gt;/data/railwayvehicleaddon_stop_markers.dat)。
 * 進路の自動設定は、読み込まれていない遠くの駅の停止位置目標へも進路を探すため、
 * ブロックエンティティとは別に、位置・連結先・駅名・番線をディメンションごとに覚えておく。
 */
public final class StopMarkerState extends PersistentState {
	public record Entry(BlockPos pos, long segmentId, double s, int dir, String station, int platform) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
				Codec.LONG.fieldOf("segment").forGetter(Entry::segmentId),
				Codec.DOUBLE.fieldOf("s").forGetter(Entry::s),
				Codec.INT.fieldOf("dir").forGetter(Entry::dir),
				Codec.STRING.optionalFieldOf("station", "").forGetter(Entry::station),
				Codec.INT.optionalFieldOf("platform", 0).forGetter(Entry::platform)
		).apply(instance, Entry::new));
	}

	public static final Codec<StopMarkerState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Entry.CODEC.listOf().optionalFieldOf("markers", List.of()).forGetter(state -> new ArrayList<>(state.entries.values()))
	).apply(instance, list -> {
		StopMarkerState state = new StopMarkerState();
		for (Entry e : list) {
			state.entries.put(e.pos(), e);
		}
		return state;
	}));

	public static final PersistentStateType<StopMarkerState> TYPE =
			new PersistentStateType<>("railwayvehicleaddon_stop_markers", StopMarkerState::new, CODEC, null);

	private final Map<BlockPos, Entry> entries = new HashMap<>();

	public StopMarkerState() {
	}

	public static StopMarkerState get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE);
	}

	public List<Entry> entries() {
		return new ArrayList<>(this.entries.values());
	}

	public void put(Entry entry) {
		if (!entry.equals(this.entries.put(entry.pos(), entry))) {
			markDirty();
		}
	}

	public void remove(BlockPos pos) {
		if (this.entries.remove(pos) != null) {
			markDirty();
		}
	}
}
