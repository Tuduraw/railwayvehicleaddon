package com.example.railwayvehicleaddon.track.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * 転車台。中心(cx, cz)・半径radiusの円形のピットに、直径いっぱいの桁が載って回転する。
 * paramは桁のA端の方向(度。0 = +X、90 = +Z)。停止位置は接続部の方向ごとにあり、桁のA端が
 * その接続部に、B端が反対側(180°先)の接続部(あれば)につながる。回転は近い向きへ回る。
 */
public final class TurntableFeature extends MovingDeckFeature {
	public static final String TYPE = "turntable";
	/** 1tickあたりの回転角(度) */
	public static final double SPEED = 1.2;

	public static final MapCodec<TurntableFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.LONG.fieldOf("id").forGetter(TurntableFeature::id),
			Codec.LONG.fieldOf("deck").forGetter(TurntableFeature::deckSegment),
			Codec.LONG.fieldOf("deck_a").forGetter(TurntableFeature::deckNodeA),
			Codec.LONG.fieldOf("deck_b").forGetter(TurntableFeature::deckNodeB),
			Stop.CODEC.listOf().fieldOf("stops").forGetter(TurntableFeature::stops),
			Codec.LONG.listOf().optionalFieldOf("stubs", List.of()).forGetter(TurntableFeature::stubSegments),
			Codec.DOUBLE.fieldOf("y").forGetter(TurntableFeature::y),
			Codec.DOUBLE.fieldOf("param").forGetter(TurntableFeature::param),
			Codec.INT.fieldOf("target").forGetter(TurntableFeature::target),
			Codec.DOUBLE.fieldOf("cx").forGetter(TurntableFeature::cx),
			Codec.DOUBLE.fieldOf("cz").forGetter(TurntableFeature::cz),
			Codec.DOUBLE.fieldOf("radius").forGetter(TurntableFeature::radius)
	).apply(instance, TurntableFeature::new));

	private final double cx;
	private final double cz;
	private final double radius;

	public TurntableFeature(long id, long deckSegment, long deckNodeA, long deckNodeB, List<Stop> stops,
							List<Long> stubSegments, double y, double param, int target, double cx, double cz, double radius) {
		super(id, deckSegment, deckNodeA, deckNodeB, stops, stubSegments, y, param, target);
		this.cx = cx;
		this.cz = cz;
		this.radius = radius;
	}

	@Override
	public String type() {
		return TYPE;
	}

	public double cx() {
		return this.cx;
	}

	public double cz() {
		return this.cz;
	}

	@Override
	public double radius() {
		return this.radius;
	}

	@Override
	public Vec3d center() {
		return new Vec3d(this.cx, this.y, this.cz);
	}

	/** 中心から角度angle(度)の方向、半径radiusの位置。 */
	public Vec3d rim(double angle, double r) {
		double rad = Math.toRadians(angle);
		return new Vec3d(this.cx + Math.cos(rad) * r, this.y, this.cz + Math.sin(rad) * r);
	}

	@Override
	protected Vec3d endA(double param) {
		return rim(param, this.radius);
	}

	@Override
	protected Vec3d endB(double param) {
		return rim(param + 180.0, this.radius);
	}

	@Override
	protected double speed() {
		return SPEED;
	}

	@Override
	protected double difference(double a, double b) {
		double d = (a - b) % 360.0;
		if (d > 180.0) {
			d -= 360.0;
		} else if (d < -180.0) {
			d += 360.0;
		}
		return d;
	}

	@Override
	protected double approach(double current, double goal, double speed) {
		double delta = -difference(current, goal);
		double next = Math.abs(delta) <= speed ? goal : current + Math.copySign(speed, delta);
		return ((next % 360.0) + 360.0) % 360.0;
	}
}
