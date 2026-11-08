package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.item.ModItems;
import com.example.railwayvehicleaddon.track.TrackManager;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackNode;
import com.example.railwayvehicleaddon.track.TrackPoint;
import com.example.railwayvehicleaddon.track.TrackSegment;
import com.example.railwayvehicleaddon.track.feature.MovingDeckFeature;
import com.example.railwayvehicleaddon.track.feature.TrackFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

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
	public static Vec3 targetPosition(TrackNetwork network, DeviceKind.TargetType type, long id) {
		switch (type) {
			case SWITCH -> {
				TrackNode node = network.node(id);
				return node == null || !network.isSwitch(id) ? null : new Vec3(node.x(), node.y(), node.z());
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
				return new Vec3(p.x(), p.y(), p.z());
			}
			default -> {
				return null;
			}
		}
	}

	/** 装置の近くで最も近い対象。無ければ-1。 */
	public static long findNearest(ServerLevel world, BlockPos pos, DeviceKind.TargetType type) {
		TrackNetwork network = TrackManager.network(world);
		Vec3 center = Vec3.atCenterOf(pos);
		long best = -1L;
		double bestDist = Double.MAX_VALUE;
		switch (type) {
			case SWITCH -> {
				for (TrackNode node : network.nodes()) {
					double d = center.distanceToSqr(node.x(), node.y(), node.z());
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
					double d = Math.max(0.0, Math.sqrt(center.distanceToSqr(feature.center())) - feature.radius());
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

	public static Component describe(ServerLevel world, DeviceKind.TargetType type, long id) {
		Vec3 p = targetPosition(TrackManager.network(world), type, id);
		if (p == null) {
			return Component.translatable("message.railwayvehicleaddon.device.no_target");
		}
		return Component.translatable("message.railwayvehicleaddon.device.linked." + type.name().toLowerCase(java.util.Locale.ROOT),
				(int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
	}

	/** 測量ツールの連結モードでの付け替え。 */
	public static void linkFromTool(ServerPlayer player, BlockPos pos, long targetId) {
		if (!player.getMainHandItem().is(ModItems.SURVEY_TOOL)) {
			return;
		}
		ServerLevel world = (ServerLevel) player.level();
		if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > PLAYER_REACH * PLAYER_REACH
				|| !(world.getBlockEntity(pos) instanceof TrackLinkable entity)) {
			return;
		}
		DeviceKind.TargetType type = entity.linkTarget();
		Vec3 target = targetPosition(TrackManager.network(world), type, targetId);
		if (target == null) {
			player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.device.wrong_target"), true);
			return;
		}
		if (target.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_LINK_RANGE * MAX_LINK_RANGE) {
			player.displayClientMessage(Component.translatable("message.railwayvehicleaddon.device.too_far", (int) MAX_LINK_RANGE), true);
			return;
		}
		entity.setTargetId(targetId);
		player.displayClientMessage(describe(world, type, targetId), true);
	}
}
