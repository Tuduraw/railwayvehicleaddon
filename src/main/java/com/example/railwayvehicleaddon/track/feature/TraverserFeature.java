package com.example.railwayvehicleaddon.track.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * 遷車台(トラバーサー)。桁(A端(ax, az)〜B端(bx, bz))が、桁と直角の方向(axisX, axisZ)へ
 * 平行移動する。paramはその移動量(ブロック)。停止位置ごとに桁の両端の接続部がある。
 */
public final class TraverserFeature extends MovingDeckFeature {
	public static final String TYPE = "traverser";
	/** 1tickあたりの移動量(ブロック) */
	public static final double SPEED = 0.1;

	public static final MapCodec<TraverserFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.LONG.fieldOf("id").forGetter(TraverserFeature::id),
			Codec.LONG.fieldOf("deck").forGetter(TraverserFeature::deckSegment),
			Codec.LONG.fieldOf("deck_a").forGetter(TraverserFeature::deckNodeA),
			Codec.LONG.fieldOf("deck_b").forGetter(TraverserFeature::deckNodeB),
			Stop.CODEC.listOf().fieldOf("stops").forGetter(TraverserFeature::stops),
			Codec.LONG.listOf().optionalFieldOf("stubs", List.of()).forGetter(TraverserFeature::stubSegments),
			Codec.DOUBLE.fieldOf("y").forGetter(TraverserFeature::y),
			Codec.DOUBLE.fieldOf("param").forGetter(TraverserFeature::param),
			Codec.INT.fieldOf("target").forGetter(TraverserFeature::target),
			Codec.DOUBLE.listOf().fieldOf("deck_ends").forGetter(f -> List.of(f.ax, f.az, f.bx, f.bz)),
			Codec.DOUBLE.fieldOf("axis_x").forGetter(TraverserFeature::axisX),
			Codec.DOUBLE.fieldOf("axis_z").forGetter(TraverserFeature::axisZ)
	).apply(instance, (id, deck, a, b, stops, stubs, y, param, target, ends, axisX, axisZ) ->
			new TraverserFeature(id, deck, a, b, stops, stubs, y, param, target,
					ends.size() > 0 ? ends.get(0) : 0.0, ends.size() > 1 ? ends.get(1) : 0.0,
					ends.size() > 2 ? ends.get(2) : 0.0, ends.size() > 3 ? ends.get(3) : 0.0, axisX, axisZ)));

	private final double ax;
	private final double az;
	private final double bx;
	private final double bz;
	private final double axisX;
	private final double axisZ;

	public TraverserFeature(long id, long deckSegment, long deckNodeA, long deckNodeB, List<Stop> stops,
							List<Long> stubSegments, double y, double param, int target,
							double ax, double az, double bx, double bz, double axisX, double axisZ) {
		super(id, deckSegment, deckNodeA, deckNodeB, stops, stubSegments, y, param, target);
		this.ax = ax;
		this.az = az;
		this.bx = bx;
		this.bz = bz;
		this.axisX = axisX;
		this.axisZ = axisZ;
	}

	@Override
	public String type() {
		return TYPE;
	}

	public double axisX() {
		return this.axisX;
	}

	public double axisZ() {
		return this.axisZ;
	}

	/** 移動量0の位置での桁のA端・B端。 */
	public Vec3d baseA() {
		return new Vec3d(this.ax, this.y, this.az);
	}

	public Vec3d baseB() {
		return new Vec3d(this.bx, this.y, this.bz);
	}

	public double minParam() {
		double min = 0.0;
		for (Stop stop : this.stops) {
			min = Math.min(min, stop.param());
		}
		return min;
	}

	public double maxParam() {
		double max = 0.0;
		for (Stop stop : this.stops) {
			max = Math.max(max, stop.param());
		}
		return max;
	}

	@Override
	protected Vec3d endA(double param) {
		return new Vec3d(this.ax + this.axisX * param, this.y, this.az + this.axisZ * param);
	}

	@Override
	protected Vec3d endB(double param) {
		return new Vec3d(this.bx + this.axisX * param, this.y, this.bz + this.axisZ * param);
	}

	@Override
	protected double speed() {
		return SPEED;
	}

	@Override
	public Vec3d center() {
		double mid = (minParam() + maxParam()) / 2.0;
		return new Vec3d((this.ax + this.bx) / 2.0 + this.axisX * mid, this.y, (this.az + this.bz) / 2.0 + this.axisZ * mid);
	}

	@Override
	public double radius() {
		double halfLength = Math.hypot(this.bx - this.ax, this.bz - this.az) / 2.0;
		double halfTravel = (maxParam() - minParam()) / 2.0;
		return Math.hypot(halfLength, halfTravel) + 1.0;
	}
}
