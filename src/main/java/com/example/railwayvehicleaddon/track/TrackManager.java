package com.example.railwayvehicleaddon.track;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.item.ModItems;
import com.example.railwayvehicleaddon.network.TrackRemovePayload;
import com.example.railwayvehicleaddon.network.TrackSyncPayload;
import com.example.railwayvehicleaddon.network.FeatureSyncPayload;
import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.track.feature.MovingDeckFeature;
import com.example.railwayvehicleaddon.track.feature.TrackFeature;
import com.example.railwayvehicleaddon.survey.LayoutPlan;
import com.example.railwayvehicleaddon.survey.NodeRef;
import com.example.railwayvehicleaddon.survey.SurveyInput;
import com.example.railwayvehicleaddon.survey.SurveyMode;
import com.example.railwayvehicleaddon.survey.SurveyModes;
import com.example.railwayvehicleaddon.survey.SurveyPoint;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** サーバー側の線路操作(敷設・撤去・クライアント同期)。 */
public final class TrackManager {
	/** 1パケットに入れる区間数の上限(カスタムペイロードのサイズ上限対策。1区間は約150バイト) */
	private static final int SYNC_BATCH = 2048;
	/** 撤去・分岐器切替を受け付ける、プレイヤーとの最大距離(クライアントの選択可能距離に余裕を足した値) */
	private static final double REMOVE_REACH = 40.0;
	/** 延長敷設で既存の端点に接続する判定距離(クライアント側と同じ値) */
	public static final double SNAP_RADIUS = 1.5;
	/** 線路上の点として受け付ける、指定位置と線路の最大距離 */
	private static final double TRACK_SNAP_TOLERANCE = 2.0;

	private TrackManager() {
	}

	public static TrackNetwork network(ServerWorld world) {
		TrackNetwork network = TrackNetworkState.get(world).network();
		network.setCantTransitionLength(RailwayConfig.get().cant_transition_length);
		return network;
	}

	private static void markDirty(ServerWorld world) {
		TrackNetworkState.get(world).markDirty();
	}

	/**
	 * 測量ツールからの配置要求を処理する。モードのplan()をサーバーの線路データで実行し直し、
	 * 問題点・建築限界を検証してから、ブロック撤去と線路グラフへの登録を行う。
	 */
	public static void placeLayout(ServerPlayerEntity player, String modeId, SurveyInput input) {
		if (!player.getMainHandStack().isOf(ModItems.SURVEY_TOOL)) {
			return;
		}
		SurveyMode mode = SurveyModes.byId(modeId);
		if (mode == null || !mode.placesTrack()) {
			return;
		}
		ServerWorld world = (ServerWorld) player.getEntityWorld();
		TrackNetwork network = network(world);
		RailwayConfig.Values config = RailwayConfig.get().values();

		// 接続先の既存ノードは、クライアントの申告ではなくサーバー側のノード位置を使う
		List<SurveyPoint> points = new ArrayList<>(input.points().size());
		for (SurveyPoint p : input.points()) {
			if (p.snapped()) {
				TrackNode node = network.node(p.nodeId());
				if (node == null) {
					fail(player, "snap_invalid");
					return;
				}
				points.add(new SurveyPoint(new Vec3d(node.x(), node.y(), node.z()), node.id()));
			} else if (p.onTrack()) {
				// 線路上の点は、サーバー側の線路データで位置を求め直す
				TrackNetwork.Nearest nearest = network.nearestPoint(p.pos().x, p.pos().y, p.pos().z, TRACK_SNAP_TOLERANCE);
				if (nearest == null) {
					fail(player, "snap_invalid");
					return;
				}
				TrackPoint tp = nearest.point();
				points.add(new SurveyPoint(new Vec3d(tp.x(), tp.y(), tp.z()), -1L, nearest.segmentId(), nearest.s()));
			} else {
				points.add(p);
			}
		}
		if (points.size() < mode.minPoints()) {
			return;
		}
		// 経由点がプレイヤーから極端に離れている要求は拒否する(改造クライアント対策)
		double limit = config.maxRouteLength() + 64.0;
		for (SurveyPoint p : points) {
			if (p.pos().squaredDistanceTo(player.getEntityPos()) > limit * limit) {
				fail(player, "too_far");
				return;
			}
		}
		int param = Math.max(mode.minParam(), Math.min(mode.maxParam(), input.param()));
		LayoutPlan plan = mode.plan(network, new SurveyInput(points, input.closed() && mode.supportsClose(), param), config);
		if (!plan.isValid()) {
			if (!plan.issues().isEmpty()) {
				RoutePlanner.Issue issue = plan.issues().get(0);
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.issue." + issue.key(),
						issue.formattedValue()), false);
			}
			return;
		}
		java.util.Set<BlockPos> extra = new java.util.HashSet<>();
		for (LayoutPlan.FeatureSpec spec : plan.features()) {
			extra.addAll(spec.clearance(config));
		}
		ClearanceScanner.ScanResult scan = ClearanceScanner.scan(world, plan.previewSegments(config.designSpeedKmh()), extra, config, true);
		if (scan.hasBlocking()) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.place_blocked",
					scan.count(BlockCategory.BLOCKED_FLUID), scan.count(BlockCategory.BLOCKED_HARD),
					scan.count(BlockCategory.UNLOADED)), false);
			return;
		}

		int removed = clearBlocks(world, player, scan.blocks());

		// 区間の分割(線路の途中からの分岐・渡り線)
		List<Long> splitRemoved = new ArrayList<>();
		List<TrackNode> newNodes = new ArrayList<>();
		List<TrackSegment> newSegments = new ArrayList<>();
		long[] splitIds = new long[plan.splits().size()];
		for (int i = 0; i < splitIds.length; i++) {
			LayoutPlan.Split split = plan.splits().get(i);
			TrackNetwork.SplitResult result = network.splitSegment(split.segmentId(), split.s());
			if (result == null) {
				fail(player, "snap_invalid");
				return;
			}
			splitIds[i] = result.nodeId();
			splitRemoved.add(split.segmentId());
			newNodes.add(network.node(result.nodeId()));
			newSegments.add(network.segment(result.firstSegment()));
			newSegments.add(network.segment(result.secondSegment()));
		}
		long[] createdIds = new long[plan.newNodes().size()];
		for (int i = 0; i < createdIds.length; i++) {
			Vec3d v = plan.newNodes().get(i);
			TrackNode node = new TrackNode(network.allocateId(), v.x, v.y, v.z);
			network.putNode(node);
			newNodes.add(node);
			createdIds[i] = node.id();
		}
		List<TrackSegment> edgeSegments = new ArrayList<>();
		for (LayoutPlan.Edge edge : plan.edges()) {
			RoutePlanner.PlannedSegment geometry = edge.geometry();
			TrackSegment segment = new TrackSegment(network.allocateId(), resolve(edge.a(), createdIds, splitIds),
					resolve(edge.b(), createdIds, splitIds), geometry.plan(), geometry.profile(), config.designSpeedKmh());
			network.putSegment(segment);
			edgeSegments.add(segment);
		}
		newSegments.addAll(edgeSegments);
		Map<Long, Long> switches = new HashMap<>();
		for (LayoutPlan.SwitchDefault def : plan.switchDefaults()) {
			if (def.edgeIndex() >= 0 && def.edgeIndex() < edgeSegments.size()) {
				long node = resolve(def.node(), createdIds, splitIds);
				long segment = edgeSegments.get(def.edgeIndex()).id();
				network.setSwitchState(node, segment);
				switches.put(node, segment);
			}
		}
		// 分割で開通方向が付け替わった分岐器も送る
		network.switchStates().forEach(switches::putIfAbsent);
		List<TrackFeature> newFeatures = new ArrayList<>();
		LayoutPlan.IdResolver ids = new LayoutPlan.IdResolver() {
			@Override
			public long node(NodeRef ref) {
				return resolve(ref, createdIds, splitIds);
			}

			@Override
			public long segment(int edgeIndex) {
				return edgeSegments.get(edgeIndex).id();
			}
		};
		for (LayoutPlan.FeatureSpec spec : plan.features()) {
			TrackFeature feature = spec.create(network.allocateId(), ids);
			network.putFeature(feature);
			newFeatures.add(feature);
		}
		markDirty(world);
		if (!splitRemoved.isEmpty()) {
			broadcast(world, new TrackRemovePayload(splitRemoved, List.of()));
		}
		broadcast(world, new TrackSyncPayload(false, config, newNodes, newSegments, switches));
		if (!newFeatures.isEmpty()) {
			broadcast(world, new FeatureSyncPayload(false, newFeatures, List.of()));
		}
		player.sendMessage(Text.translatable("message.railwayvehicleaddon.placed",
				newSegments.size() - 2 * splitIds.length, String.format("%.1f", plan.totalLength()), removed), false);
	}

	private static long resolve(NodeRef ref, long[] createdIds, long[] splitIds) {
		if (ref.isExisting()) {
			return ref.existingId();
		}
		return ref.isSplit() ? splitIds[ref.splitIndex()] : createdIds[ref.newIndex()];
	}

	/** 分岐器の開通方向を切り替える。 */
	public static void toggleSwitch(ServerPlayerEntity player, long nodeId) {
		if (!player.getMainHandStack().isOf(ModItems.SURVEY_TOOL)) {
			return;
		}
		ServerWorld world = (ServerWorld) player.getEntityWorld();
		TrackNetwork network = network(world);
		TrackNode node = network.node(nodeId);
		if (node == null || player.getEyePos().squaredDistanceTo(node.x(), node.y(), node.z()) > REMOVE_REACH * REMOVE_REACH) {
			return;
		}
		long active = network.cycleSwitch(nodeId);
		if (active < 0) {
			return;
		}
		markDirty(world);
		broadcast(world, new TrackSyncPayload(false, RailwayConfig.get().values(), List.of(), List.of(), Map.of(nodeId, active)));
	}

	/**
	 * 建築限界内のブロックを撤去する。上から順に消して、砂利などの落下が
	 * 撤去中の範囲に連鎖しにくいようにする。
	 */
	private static int clearBlocks(ServerWorld world, ServerPlayerEntity player, Map<BlockPos, BlockCategory> blocks) {
		String dropMode = RailwayConfig.get().drop_removed_blocks;
		boolean creative = player.isCreative();
		List<Map.Entry<BlockPos, BlockCategory>> ordered = new ArrayList<>(blocks.entrySet());
		ordered.sort(Comparator.comparingInt((Map.Entry<BlockPos, BlockCategory> e) -> e.getKey().getY()).reversed());
		int count = 0;
		for (Map.Entry<BlockPos, BlockCategory> entry : ordered) {
			BlockCategory category = entry.getValue();
			if (category.blocksPlacement()) {
				continue;
			}
			BlockPos pos = entry.getKey();
			BlockState state = world.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}
			boolean drop = !creative && ("all".equals(dropMode)
					|| ("player_placed".equals(dropMode) && category == BlockCategory.PLAYER_PLACED));
			if (drop) {
				BlockEntity blockEntity = world.getBlockEntity(pos);
				Block.dropStacks(state, world, pos, blockEntity, player, ItemStack.EMPTY);
			}
			world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
			count++;
		}
		return count;
	}

	public static void removeSegments(ServerPlayerEntity player, List<Long> segmentIds, List<Long> featureIds) {
		if (!player.getMainHandStack().isOf(ModItems.SURVEY_TOOL)) {
			return;
		}
		ServerWorld world = (ServerWorld) player.getEntityWorld();
		TrackNetwork network = network(world);
		Vec3d eye = player.getEyePos();
		java.util.Set<Long> features = new java.util.LinkedHashSet<>();
		java.util.Set<Long> segments = new java.util.LinkedHashSet<>();
		for (long segmentId : segmentIds) {
			TrackSegment segment = network.segment(segmentId);
			if (segment == null || !withinReach(segment, eye)) {
				continue;
			}
			// 設備の一部(桁・接続用の線路)を選んだ場合は、設備ごと撤去する
			TrackFeature owner = network.featureOfSegment(segmentId);
			if (owner != null) {
				features.add(owner.id());
			} else {
				segments.add(segmentId);
			}
		}
		for (long featureId : featureIds) {
			TrackFeature feature = network.feature(featureId);
			if (feature != null && eye.squaredDistanceTo(feature.center()) <= Math.pow(REMOVE_REACH + feature.radius(), 2)) {
				features.add(featureId);
			}
		}
		List<Long> removedFeatures = new ArrayList<>();
		for (long featureId : features) {
			TrackFeature feature = network.removeFeature(featureId);
			if (feature != null) {
				removedFeatures.add(featureId);
				segments.addAll(feature.ownedSegments());
			}
		}
		List<Long> removedSegments = new ArrayList<>();
		List<Long> removedNodes = new ArrayList<>();
		for (long segmentId : segments) {
			if (network.segment(segmentId) != null) {
				removedNodes.addAll(network.removeSegment(segmentId));
				removedSegments.add(segmentId);
			}
		}
		if (removedSegments.isEmpty() && removedFeatures.isEmpty()) {
			return;
		}
		markDirty(world);
		if (!removedFeatures.isEmpty()) {
			broadcast(world, new FeatureSyncPayload(false, List.of(), removedFeatures));
		}
		broadcast(world, new TrackRemovePayload(removedSegments, removedNodes));
		player.sendMessage(Text.translatable("message.railwayvehicleaddon.removed_all",
				removedSegments.size(), removedFeatures.size()), true);
	}

	// ------------------------------------------------------------------ 設備の動作

	/** 転車台・遷車台を次の停止位置へ動かし始める。 */
	public static void featureAction(ServerPlayerEntity player, long featureId, int step) {
		if (!player.getMainHandStack().isOf(ModItems.SURVEY_TOOL)) {
			return;
		}
		ServerWorld world = (ServerWorld) player.getEntityWorld();
		TrackNetwork network = network(world);
		if (!(network.feature(featureId) instanceof MovingDeckFeature deck)) {
			return;
		}
		if (player.getEyePos().squaredDistanceTo(deck.center()) > Math.pow(REMOVE_REACH + deck.radius(), 2)) {
			return;
		}
		if (deck.isMoving()) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.deck_busy"), true);
			return;
		}
		// 桁と周りの線路にまたがっている車両があれば動かさない
		Box area = new Box(deck.center(), deck.center()).expand(deck.radius() + 24.0, 16.0, deck.radius() + 24.0);
		for (RailVehicleEntity vehicle : world.getEntitiesByClass(RailVehicleEntity.class, area, e -> true)) {
			if (vehicle.straddles(network, deck.deckSegment())) {
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.deck_straddled"), true);
				return;
			}
		}
		deck.selectNext(step);
		if (deck.isMoving()) {
			deck.apply(network);
			sendFeatureState(world, network, deck);
		}
	}

	/** 毎tick: 動いている設備を進め、変化をクライアントへ送る。 */
	public static void tickFeatures(ServerWorld world) {
		TrackNetworkState state = TrackNetworkState.get(world);
		TrackNetwork network = state.network();
		if (network.features().isEmpty()) {
			return;
		}
		for (TrackFeature feature : new ArrayList<>(network.features())) {
			if (feature.tick(network)) {
				sendFeatureState(world, network, feature);
				state.markDirty();
			}
		}
	}

	/** 設備の状態と、桁の区間・端ノード(可動桁の場合)を送る。 */
	private static void sendFeatureState(ServerWorld world, TrackNetwork network, TrackFeature feature) {
		if (feature instanceof MovingDeckFeature deck) {
			List<TrackNode> nodes = new ArrayList<>();
			TrackNode a = network.node(deck.deckNodeA());
			TrackNode b = network.node(deck.deckNodeB());
			if (a != null) {
				nodes.add(a);
			}
			if (b != null) {
				nodes.add(b);
			}
			TrackSegment segment = network.segment(deck.deckSegment());
			broadcast(world, new TrackSyncPayload(false, RailwayConfig.get().values(), nodes,
					segment == null ? List.of() : List.of(segment), Map.of()));
		}
		broadcast(world, new FeatureSyncPayload(false, List.of(feature), List.of()));
	}

	/** 区間のどこかがプレイヤーの手の届く範囲(撤去の選択可能距離)にあるか。 */
	private static boolean withinReach(TrackSegment segment, Vec3d eye) {
		double length = segment.length();
		int steps = Math.max(1, (int) Math.ceil(length / 2.0));
		for (int i = 0; i <= steps; i++) {
			TrackPoint p = segment.sample(length * i / steps);
			if (eye.squaredDistanceTo(p.x(), p.y(), p.z()) <= REMOVE_REACH * REMOVE_REACH) {
				return true;
			}
		}
		return false;
	}

	/** プレイヤーが今いるディメンションの線路を全量送る(ログイン・ディメンション移動・リスポーン時)。 */
	public static void sendFullSync(ServerPlayerEntity player) {
		ServerWorld world = (ServerWorld) player.getEntityWorld();
		TrackNetwork network = network(world);
		RailwayConfig.Values config = RailwayConfig.get().values();
		List<TrackNode> nodes = new ArrayList<>(network.nodes());
		List<TrackSegment> segments = new ArrayList<>(network.segments());
		// ノードは小さいので1通目にまとめ、区間は分割する
		int index = 0;
		boolean first = true;
		do {
			int end = Math.min(segments.size(), index + SYNC_BATCH);
			ServerPlayNetworking.send(player, new TrackSyncPayload(first, config,
					first ? nodes : List.of(), new ArrayList<>(segments.subList(index, end)),
					first ? new HashMap<>(network.switchStates()) : Map.of()));
			first = false;
			index = end;
		} while (index < segments.size());
		ServerPlayNetworking.send(player, new FeatureSyncPayload(true, new ArrayList<>(network.features()), List.of()));
	}

	private static void broadcast(ServerWorld world, net.minecraft.network.packet.CustomPayload payload) {
		for (ServerPlayerEntity player : PlayerLookup.world(world)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static void fail(ServerPlayerEntity player, String key) {
		player.sendMessage(Text.translatable("message.railwayvehicleaddon." + key), false);
	}
}
