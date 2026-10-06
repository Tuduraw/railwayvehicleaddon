package com.example.railwayvehicleaddon.track;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.item.ModItems;
import com.example.railwayvehicleaddon.network.TrackRemovePayload;
import com.example.railwayvehicleaddon.network.TrackSyncPayload;
import com.example.railwayvehicleaddon.network.FeatureSyncPayload;
import com.example.railwayvehicleaddon.network.PlaceResultPayload;
import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.vehicle.RailVehicleParams;
import com.example.railwayvehicleaddon.vehicle.RailVehicleParamsLoader;
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
		boolean success = tryPlaceLayout(player, modeId, input);
		ServerPlayNetworking.send(player, new PlaceResultPayload(success));
	}

	private static boolean tryPlaceLayout(ServerPlayerEntity player, String modeId, SurveyInput input) {
		if (!player.getMainHandStack().isOf(ModItems.SURVEY_TOOL)) {
			return false;
		}
		SurveyMode mode = SurveyModes.byId(modeId);
		if (mode == null || !mode.placesTrack()) {
			return false;
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
					return false;
				}
				points.add(new SurveyPoint(new Vec3d(node.x(), node.y(), node.z()), node.id()));
			} else if (p.onTrack()) {
				// 線路上の点は、サーバー側の線路データで位置を求め直す
				TrackNetwork.Nearest nearest = network.nearestPoint(p.pos().x, p.pos().y, p.pos().z, TRACK_SNAP_TOLERANCE);
				if (nearest == null) {
					fail(player, "snap_invalid");
					return false;
				}
				TrackPoint tp = nearest.point();
				points.add(new SurveyPoint(new Vec3d(tp.x(), tp.y(), tp.z()), -1L, nearest.segmentId(), nearest.s()));
			} else {
				points.add(p);
			}
		}
		if (points.size() < mode.minPoints()) {
			return false;
		}
		// 経由点がプレイヤーから極端に離れている要求は拒否する(改造クライアント対策)
		double limit = config.maxRouteLength() + 64.0;
		for (SurveyPoint p : points) {
			if (p.pos().squaredDistanceTo(player.getEntityPos()) > limit * limit) {
				fail(player, "too_far");
				return false;
			}
		}
		int param = Math.max(mode.minParam(), Math.min(mode.maxParam(), input.param()));
		// 強制置換はクリエイティブのみ(クライアントの申告は信用しない)
		boolean force = input.force() && player.isCreative();
		BallastType ballast = BallastType.byId(input.ballast());
		// 曲線半径のしきい値(測量ツールで指定。設定値は下回れない)と、架線の高さに応じた建築限界を反映した設定
		RailwayConfig.Values placeConfig = config.forPlacement(input.minRadius(), input.electrification(), input.wireHeight());
		LayoutPlan plan = mode.plan(network, new SurveyInput(points, input.closed() && mode.supportsClose(), param,
				ballast.id(), force, input.electrification(), input.wireHeight(), input.minRadius()), placeConfig);
		if (!plan.isValid()) {
			if (!plan.issues().isEmpty()) {
				RoutePlanner.Issue issue = plan.issues().get(0);
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.issue." + issue.key(),
						issue.formattedValue()), false);
			}
			return false;
		}
		java.util.Set<BlockPos> extra = new java.util.HashSet<>();
		for (LayoutPlan.FeatureSpec spec : plan.features()) {
			extra.addAll(spec.clearance(config));
		}
		ClearanceScanner.ScanResult scan = ClearanceScanner.scan(world, plan.previewSegments(config.designSpeedKmh()), extra, placeConfig, true);
		if (scan.hasBlocking(force)) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.place_blocked",
					scan.count(BlockCategory.BLOCKED_FLUID), scan.count(BlockCategory.BLOCKED_HARD),
					scan.count(BlockCategory.UNLOADED)), false);
			return false;
		}

		int removed = clearBlocks(world, player, scan.blocks(), force);

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
				return false;
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
					resolve(edge.b(), createdIds, splitIds), geometry.plan(), geometry.profile(), config.designSpeedKmh(),
					ballast.id(), input.electrification(), input.wireHeight());
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
		return true;
	}

	private static long resolve(NodeRef ref, long[] createdIds, long[] splitIds) {
		if (ref.isExisting()) {
			return ref.existingId();
		}
		return ref.isSplit() ? splitIds[ref.splitIndex()] : createdIds[ref.newIndex()];
	}

	/** 測量ツールで分岐器の開通方向を切り替える。 */
	public static void toggleSwitch(ServerPlayerEntity player, long nodeId) {
		if (!player.getMainHandStack().isOf(ModItems.SURVEY_TOOL)) {
			return;
		}
		ServerWorld world = (ServerWorld) player.getEntityWorld();
		TrackNode node = network(world).node(nodeId);
		if (node == null || player.getEyePos().squaredDistanceTo(node.x(), node.y(), node.z()) > REMOVE_REACH * REMOVE_REACH) {
			return;
		}
		cycleSwitch(world, nodeId);
	}

	/** 分岐器を次の分岐側へ切り替える(測量ツール・転てつてこ)。 */
	public static boolean cycleSwitch(ServerWorld world, long nodeId) {
		TrackNetwork network = network(world);
		long active = network.cycleSwitch(nodeId);
		if (active < 0) {
			return false;
		}
		markDirty(world);
		broadcast(world, new TrackSyncPayload(false, RailwayConfig.get().values(), List.of(), List.of(), Map.of(nodeId, active)));
		return true;
	}

	/**
	 * 分岐器を分岐側のindex番目(0始まり。範囲外は最後)へ開通させる(転てつ機)。
	 * 分岐側の順序は、元の線路(分岐元が線路の途中の場合)または最初に作った分岐が0番目。
	 */
	public static boolean setSwitchBranch(ServerWorld world, long nodeId, int index) {
		TrackNetwork network = network(world);
		List<Long> branches = network.switchBranches(nodeId);
		if (branches.isEmpty()) {
			return false;
		}
		long segment = branches.get(Math.max(0, Math.min(branches.size() - 1, index)));
		if (network.activeBranch(nodeId) == segment) {
			return true;
		}
		network.setSwitchState(nodeId, segment);
		markDirty(world);
		broadcast(world, new TrackSyncPayload(false, RailwayConfig.get().values(), List.of(), List.of(), Map.of(nodeId, segment)));
		return true;
	}

	/**
	 * 建築限界内のブロックを撤去する。上から順に消して、砂利などの落下が
	 * 撤去中の範囲に連鎖しにくいようにする。
	 */
	private static int clearBlocks(ServerWorld world, ServerPlayerEntity player, Map<BlockPos, BlockCategory> blocks, boolean force) {
		String dropMode = RailwayConfig.get().drop_removed_blocks;
		boolean creative = player.isCreative();
		List<Map.Entry<BlockPos, BlockCategory>> ordered = new ArrayList<>(blocks.entrySet());
		ordered.sort(Comparator.comparingInt((Map.Entry<BlockPos, BlockCategory> e) -> e.getKey().getY()).reversed());
		int count = 0;
		for (Map.Entry<BlockPos, BlockCategory> entry : ordered) {
			BlockCategory category = entry.getValue();
			// 強制置換では流体・硬いブロックも消す(未ロードの範囲は書き換えられないので除く)
			if (category == BlockCategory.UNLOADED || (category.blocksPlacement() && !force)) {
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

	/**
	 * 電化モード: 選んだ区間へ、要求された電化方式を適用する。選んだ区間が全部すでにその方式なら、
	 * 代わりに非電化へ戻す(トグル)。設備(転車台・遷車台の桁)は対象外。
	 */
	public static void electrify(ServerPlayerEntity player, List<Long> segmentIds, int electrification, float wireHeight) {
		if (!player.getMainHandStack().isOf(ModItems.SURVEY_TOOL)) {
			return;
		}
		ServerWorld world = (ServerWorld) player.getEntityWorld();
		TrackNetwork network = network(world);
		Vec3d eye = player.getEyePos();
		List<TrackSegment> targets = new ArrayList<>();
		for (long id : segmentIds) {
			TrackSegment segment = network.segment(id);
			if (segment != null && network.featureOfSegment(id) == null && withinReach(segment, eye)) {
				targets.add(segment);
			}
		}
		if (targets.isEmpty()) {
			return;
		}
		int requested = ElectrificationType.byId(electrification).id();
		// 架線は高さも同じときだけ「適用済み」とみなす(高さだけ変えたいときは、もう一度Enterで張り直せる)
		boolean overhead = requested == ElectrificationType.OVERHEAD.id();
		boolean allAlreadyRequested = targets.stream().allMatch(s -> s.electrification() == requested
				&& (!overhead || Math.abs(s.wireHeight() - wireHeight) < 0.01f));
		int value = allAlreadyRequested ? ElectrificationType.NONE.id() : requested;
		List<TrackSegment> changed = new ArrayList<>();
		for (TrackSegment segment : targets) {
			TrackSegment updated = segment.withElectrification(value, wireHeight);
			network.putSegment(updated);
			changed.add(updated);
		}
		markDirty(world);
		broadcast(world, new TrackSyncPayload(false, RailwayConfig.get().values(), List.of(), changed, Map.of()));
		Text typeName = Text.translatable("message.railwayvehicleaddon.electrification." + ElectrificationType.byId(value).name().toLowerCase(java.util.Locale.ROOT));
		player.sendMessage(Text.translatable(value != ElectrificationType.NONE.id()
				? "message.railwayvehicleaddon.electrified" : "message.railwayvehicleaddon.deelectrified",
				changed.size(), typeName), true);
	}

	// ------------------------------------------------------------------ 設備の動作

	/** 測量ツールで転車台・遷車台を次(前)の停止位置へ動かす。 */
	public static void featureAction(ServerPlayerEntity player, long featureId, int step) {
		if (!player.getMainHandStack().isOf(ModItems.SURVEY_TOOL)) {
			return;
		}
		ServerWorld world = (ServerWorld) player.getEntityWorld();
		TrackFeature feature = network(world).feature(featureId);
		if (feature == null || player.getEyePos().squaredDistanceTo(feature.center()) > Math.pow(REMOVE_REACH + feature.radius(), 2)) {
			return;
		}
		moveDeck(world, featureId, deck -> deck.selectNext(step), text -> player.sendMessage(text, true));
	}

	/**
	 * 転車台・遷車台を動かし始める(測量ツール・操作盤・制御器の共通処理)。動作中や、桁と周りの
	 * 線路にまたがっている車両がある場合は動かさず、feedbackへ理由を返す(nullなら通知しない)。
	 *
	 * @param select 停止位置を選ぶ処理(selectNext / selectStop)
	 */
	public static boolean moveDeck(ServerWorld world, long featureId, java.util.function.Consumer<MovingDeckFeature> select,
								   java.util.function.Consumer<Text> feedback) {
		TrackNetwork network = network(world);
		if (!(network.feature(featureId) instanceof MovingDeckFeature deck)) {
			return false;
		}
		if (deck.isMoving()) {
			if (feedback != null) {
				feedback.accept(Text.translatable("message.railwayvehicleaddon.deck_busy"));
			}
			return false;
		}
		Box area = new Box(deck.center(), deck.center()).expand(deck.radius() + 24.0, 16.0, deck.radius() + 24.0);
		for (RailVehicleEntity vehicle : world.getEntitiesByClass(RailVehicleEntity.class, area, e -> true)) {
			if (vehicle.straddles(network, deck.deckSegment())) {
				if (feedback != null) {
					feedback.accept(Text.translatable("message.railwayvehicleaddon.deck_straddled"));
				}
				return false;
			}
		}
		select.accept(deck);
		if (deck.isMoving()) {
			deck.apply(network);
			sendFeatureState(world, network, deck);
		}
		return true;
	}

	/** 区間に車両が載っているか(在線検知器)。 */
	public static boolean isOccupied(ServerWorld world, long segmentId) {
		TrackNetwork network = network(world);
		TrackSegment segment = network.segment(segmentId);
		if (segment == null) {
			return false;
		}
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		double length = segment.length();
		for (int i = 0; i <= 16; i++) {
			TrackPoint p = segment.sample(length * i / 16.0);
			minX = Math.min(minX, p.x());
			minY = Math.min(minY, p.y());
			minZ = Math.min(minZ, p.z());
			maxX = Math.max(maxX, p.x());
			maxY = Math.max(maxY, p.y());
			maxZ = Math.max(maxZ, p.z());
		}
		// 車体の長さ分(台車が区間に掛かっている車両の中心は区間の外にあり得る)広げて探す
		Box area = new Box(minX, minY, minZ, maxX, maxY, maxZ).expand(24.0, 4.0, 24.0);
		for (RailVehicleEntity vehicle : world.getEntitiesByClass(RailVehicleEntity.class, area, e -> true)) {
			if (vehicle.occupies(network, segmentId)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ 電化・給電

	private static final Map<net.minecraft.registry.RegistryKey<net.minecraft.world.World>, Integer> ELECTRICAL_TIMERS = new HashMap<>();
	/** 給電の計算をやり直す間隔(tick) */
	private static final int ELECTRICAL_INTERVAL = 10;

	/**
	 * 給電区画(つながった電化区間のまとまり)ごとに、動いている変電所の容量と、力行中の電車の数(需要)を
	 * 集計し、区間ごとの給電係数(容量を需要で割った値。容量が無ければ0)を計算して線路データに書き込む。
	 * 負荷が軽い(数tickに1回)よう、一定間隔でだけ実行する。
	 */
	public static void tickElectricalSupply(ServerWorld world) {
		TrackNetwork network = network(world);
		int timer = ELECTRICAL_TIMERS.merge(world.getRegistryKey(), 1, Integer::sum);
		if (timer % ELECTRICAL_INTERVAL != 0) {
			return;
		}
		Map<Long, Integer> components = network.computeElectrifiedComponents();
		if (components.isEmpty()) {
			network.setElectricFactors(Map.of());
			return;
		}
		Map<Integer, Double> capacity = new HashMap<>();
		for (com.example.railwayvehicleaddon.block.SubstationBlockEntity substation
				: com.example.railwayvehicleaddon.block.SubstationBlockEntity.activeIn(world)) {
			Integer component = components.get(substation.targetId());
			if (component != null) {
				capacity.merge(component, (double) com.example.railwayvehicleaddon.block.SubstationBlockEntity.CAPACITY, Double::sum);
			}
		}
		Map<Integer, Integer> demand = new HashMap<>();
		for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
			if (!(entity instanceof RailVehicleEntity vehicle) || !vehicle.isOnTrack() || vehicle.getThrottle() == 0f) {
				continue;
			}
			RailVehicleParams params = RailVehicleParamsLoader.get(vehicle.getVehicleDefinitionId()).orElse(RailVehicleParams.DEFAULT);
			if (!RailVehicleParams.ELECTRIC.equals(params.powerSource())) {
				continue;
			}
			Integer component = components.get(vehicle.currentSegmentId());
			if (component != null) {
				demand.merge(component, 1, Integer::sum);
			}
		}
		Map<Long, Double> factors = new HashMap<>();
		for (Map.Entry<Long, Integer> entry : components.entrySet()) {
			int component = entry.getValue();
			double cap = capacity.getOrDefault(component, 0.0);
			int dem = demand.getOrDefault(component, 0);
			double factor = cap <= 0.0 ? 0.0 : (dem <= 0 ? 1.0 : Math.min(1.0, cap / dem));
			factors.put(entry.getKey(), factor);
		}
		network.setElectricFactors(factors);
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
