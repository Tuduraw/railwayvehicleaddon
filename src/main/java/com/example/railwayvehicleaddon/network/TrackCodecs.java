package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.track.HeightProfile;
import com.example.railwayvehicleaddon.track.PlanCurve;
import com.example.railwayvehicleaddon.track.TrackNode;
import com.example.railwayvehicleaddon.track.TrackSegment;
import net.minecraft.network.PacketByteBuf;

/** 線路データの通信用シリアライズ。保存形式(TrackNetworkState.CODEC)とは独立している。 */
public final class TrackCodecs {
	private TrackCodecs() {
	}

	public static void writeNode(PacketByteBuf buf, TrackNode node) {
		buf.writeLong(node.id());
		buf.writeDouble(node.x());
		buf.writeDouble(node.y());
		buf.writeDouble(node.z());
	}

	public static TrackNode readNode(PacketByteBuf buf) {
		return new TrackNode(buf.readLong(), buf.readDouble(), buf.readDouble(), buf.readDouble());
	}

	public static void writeSegment(PacketByteBuf buf, TrackSegment segment) {
		buf.writeLong(segment.id());
		buf.writeLong(segment.nodeA());
		buf.writeLong(segment.nodeB());
		for (double v : segment.plan().controlPoints()) {
			buf.writeDouble(v);
		}
		for (double v : segment.profile().toArray()) {
			buf.writeDouble(v);
		}
		buf.writeFloat(segment.designSpeed());
		buf.writeByte(segment.ballast());
		buf.writeByte(segment.electrification());
		buf.writeFloat(segment.wireHeight());
	}

	public static TrackSegment readSegment(PacketByteBuf buf) {
		long id = buf.readLong();
		long a = buf.readLong();
		long b = buf.readLong();
		double[] plan = new double[8];
		for (int i = 0; i < plan.length; i++) {
			plan[i] = buf.readDouble();
		}
		double[] profile = new double[8];
		for (int i = 0; i < profile.length; i++) {
			profile[i] = buf.readDouble();
		}
		float designSpeed = buf.readFloat();
		int ballast = buf.readByte();
		int electrification = buf.readByte();
		float wireHeight = buf.readFloat();
		return new TrackSegment(id, a, b, PlanCurve.fromControlPoints(plan), HeightProfile.fromArray(profile), designSpeed,
				ballast, electrification, wireHeight);
	}

	public static void writeConfig(PacketByteBuf buf, RailwayConfig.Values v) {
		buf.writeFloat(v.gauge());
		buf.writeFloat(v.minCurveRadius());
		buf.writeFloat(v.maxGrade());
		buf.writeFloat(v.verticalCurveLength());
		buf.writeFloat(v.clearanceHalfWidth());
		buf.writeFloat(v.clearanceHeight());
		buf.writeFloat(v.unbreakableHardness());
		buf.writeFloat(v.designSpeedKmh());
		buf.writeVarInt(v.maxWaypoints());
		buf.writeFloat(v.maxRouteLength());
		buf.writeFloat(v.cantTransitionLength());
		buf.writeFloat(v.trackRenderDistance());
		buf.writeFloat(v.catenaryHeight());
	}

	public static RailwayConfig.Values readConfig(PacketByteBuf buf) {
		return new RailwayConfig.Values(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
				buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readFloat(), buf.readFloat(),
				buf.readFloat(), buf.readFloat());
	}
}
