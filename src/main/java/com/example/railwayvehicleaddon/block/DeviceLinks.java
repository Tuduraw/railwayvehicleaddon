package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.item.ModItems;
import com.example.railwayvehicleaddon.track.TrackManager;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackNode;
import com.example.railwayvehicleaddon.track.TrackPoint;
import com.example.railwayvehicleaddon.track.TrackSegment;
import com.example.railwayvehicleaddon.track.feature.MovingDeckFeature;
import com.example.railwayvehicleaddon.track.feature.TrackFeature;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** 線路装置と対象(分岐器・転車台/遷車台・区間)の連結。 */
public final class DeviceLinks {
	/** 設置時に自動で連結する対象を探す距離 */
	public static final double AUTO_LINK_RANGE = 16.0;
	/** 装置と対象の最大距離 */
	public static final double MAX_LINK_RANGE = 48.0;
	/** 連結モードで装置を操作できるプレイヤーとの距離 */
	private static final double PLAYER_REACH = 24.0;

	private DeviceLinks() {
	}

	/** 対象の位置(表示・距離判定用)。対象が無ければnull。 */
	public static Vec3d targetPosition(TrackNetwork network, DeviceKind kind, long id) {
		switch (kind.target()) {
			case SWITCH -> {
				TrackNode node = network.node(id);
				return node == null || !network.isSwitch(id) ? null : new Vec3d(node.x(), node.y(), node.z());
			}
			case DECK -> {
				TrackFeature feature = network.feature(id);
				return feature instanceof MovingDeckFeature ? feature.center() : null;
			}
			case SEGMENT -> {
				TrackSegment segment = network.segment(id);
				if (segment == null) {
					return null;
				}
				TrackPoint p = segment.sample(segment.length() / 2.0);
				return new Vec3d(p.x(), p.y(), p.z());
			}
			default -> {
				return null;
			}
		}
	}

	/** 装置の近くで最も近い対象。無ければ-1。 */
	public static long findNearest(ServerWorld world, BlockPos pos, DeviceKind kind) {
		TrackNetwork network = TrackManager.network(world);
		Vec3d center = Vec3d.ofCenter(pos);
		long best = -1L;
		double bestDist = Double.MAX_VALUE;
		switch (kind.target()) {
			case SWITCH -> {
				for (TrackNode node : network.nodes()) {
					double d = center.squaredDistanceTo(node.x(), node.y(), node.z());
					if (d < bestDist && d <= AUTO_LINK_RANGE * AUTO_LINK_RANGE && network.isSwitch(node.id())) {
						bestDist = d;
						best = node.id();
					}
				}
			}
			case DECK -> {
				for (TrackFeature feature : network.features()) {
					if (!(feature instanceof MovingDeckFeature)) {
						continue;
					}
					double d = Math.max(0.0, Math.sqrt(center.squaredDistanceTo(feature.center())) - feature.radius());
					if (d < bestDist && d <= AUTO_LINK_RANGE) {
						bestDist = d;
						best = feature.id();
					}
				}
			}
			case SEGMENT -> {
				TrackNetwork.Nearest nearest = network.nearestPoint(center.x, center.y, center.z, AUTO_LINK_RANGE);
				if (nearest != null && network.featureOfSegment(nearest.segmentId()) == null) {
					best = nearest.segmentId();
				}
			}
			default -> {
			}
		}
		return best;
	}

	public static Text describe(ServerWorld world, DeviceKind kind, long id) {
		Vec3d p = targetPosition(TrackManager.network(world), kind, id);
		if (p == null) {
			return Text.translatable("message.railwayvehicleaddon.device.no_target");
		}
		return Text.translatable("message.railwayvehicleaddon.device.linked." + kind.target().name().toLowerCase(java.util.Locale.ROOT),
				(int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
	}

	/** 測量ツールの連結モードでの付け替え。 */
	public static void linkFromTool(ServerPlayerEntity player, BlockPos pos, long targetId) {
		if (!player.getMainHandStack().isOf(ModItems.SURVEY_TOOL)) {
			return;
		}
		ServerWorld world = (ServerWorld) player.getEntityWorld();
		if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > PLAYER_REACH * PLAYER_REACH
				|| !(world.getBlockEntity(pos) instanceof TrackDeviceBlockEntity entity)) {
			return;
		}
		DeviceKind kind = entity.kind();
		Vec3d target = targetPosition(TrackManager.network(world), kind, targetId);
		if (target == null) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.device.wrong_target"), true);
			return;
		}
		if (target.squaredDistanceTo(Vec3d.ofCenter(pos)) > MAX_LINK_RANGE * MAX_LINK_RANGE) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.device.too_far", (int) MAX_LINK_RANGE), true);
			return;
		}
		entity.setTargetId(targetId);
		player.sendMessage(describe(world, kind, targetId), true);
	}
}
