package com.example.railwayvehicleaddon.track.feature;

import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackNode;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/**
 * 車止め。線路の端(ノード)に置き、車両がその手前(margin)で止まるようにする。
 * 車止めのある端は延伸の接続先として選べない(先に車止めを撤去する)。
 */
public final class BufferStopFeature implements TrackFeature {
	public static final String TYPE = "buffer_stop";
	/** 台車から車体端までの張り出しを見込んだ、端からの停止位置 */
	public static final double DEFAULT_MARGIN = 2.5;

	public static final MapCodec<BufferStopFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.LONG.fieldOf("id").forGetter(BufferStopFeature::id),
			Codec.LONG.fieldOf("node").forGetter(BufferStopFeature::node),
			Codec.DOUBLE.optionalFieldOf("margin", DEFAULT_MARGIN).forGetter(BufferStopFeature::margin)
	).apply(instance, BufferStopFeature::new));

	private final long id;
	private final long node;
	private final double margin;
	private Vec3d center = Vec3d.ZERO;

	public BufferStopFeature(long id, long node, double margin) {
		this.id = id;
		this.node = node;
		this.margin = margin;
	}

	@Override
	public long id() {
		return this.id;
	}

	@Override
	public String type() {
		return TYPE;
	}

	public long node() {
		return this.node;
	}

	public double margin() {
		return this.margin;
	}

	@Override
	public Set<Long> ownedSegments() {
		return Set.of();
	}

	@Override
	public Set<Long> reservedNodes() {
		return Set.of(this.node);
	}

	@Override
	public void apply(TrackNetwork network) {
		network.setEndMargin(this.node, this.margin);
		TrackNode n = network.node(this.node);
		if (n != null) {
			this.center = new Vec3d(n.x(), n.y(), n.z());
		}
	}

	@Override
	public void detach(TrackNetwork network) {
		network.setEndMargin(this.node, 0.0);
	}

	@Override
	public Vec3d center() {
		return this.center;
	}

	@Override
	public double radius() {
		return 1.5;
	}
}
