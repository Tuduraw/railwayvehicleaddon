package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.item.ModItems;
import com.example.railwayvehicleaddon.item.SurveyToolItem;
import com.example.railwayvehicleaddon.block.DeviceKind;
import com.example.railwayvehicleaddon.block.TrackLinkable;
import com.example.railwayvehicleaddon.network.ElectrifyPayload;
import com.example.railwayvehicleaddon.network.FeatureActionPayload;
import com.example.railwayvehicleaddon.network.LinkDevicePayload;
import com.example.railwayvehicleaddon.network.PlaceLayoutPayload;
import com.example.railwayvehicleaddon.network.RemoveSegmentsPayload;
import com.example.railwayvehicleaddon.network.ToggleSwitchPayload;
import com.example.railwayvehicleaddon.survey.LayoutPlan;
import com.example.railwayvehicleaddon.survey.SurveyInput;
import com.example.railwayvehicleaddon.survey.SurveyMode;
import com.example.railwayvehicleaddon.survey.SurveyModes;
import com.example.railwayvehicleaddon.survey.SurveyPoint;
import com.example.railwayvehicleaddon.track.BallastType;
import com.example.railwayvehicleaddon.track.ElectrificationType;
import com.example.railwayvehicleaddon.track.BlockCategory;
import com.example.railwayvehicleaddon.track.ClearanceScanner;
import com.example.railwayvehicleaddon.track.RoutePlanner;
import com.example.railwayvehicleaddon.track.TrackManager;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackNode;
import com.example.railwayvehicleaddon.track.TrackPoint;
import com.example.railwayvehicleaddon.track.TrackSegment;
import com.example.railwayvehicleaddon.track.feature.MovingDeckFeature;
import com.example.railwayvehicleaddon.track.feature.TrackFeature;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 測量ツールの操作状態(クライアント専用)。モード・点・選択・プレビュー結果を持つ。
 *
 * <p>右クリックの解釈(上から順に優先):
 * <ol>
 *   <li>スニーク中: 選択の解除 → 無ければ最後の点の取り消し(環状なら環状の解除)</li>
 *   <li>撤去モード: 狙った区間を撤去対象に追加/解除</li>
 *   <li>点を狙っている: その点を選択/解除(最大2点)</li>
 *   <li>分岐器を狙っている: 開通方向を切り替え</li>
 *   <li>ブロックを狙っている: 1点選択中ならその点を移動、隣り合う2点選択中なら間に挿入、
 *       それ以外は末尾に追加(最初の点を狙えば環状に閉じる)</li>
 * </ol>
 * プレビューはサーバーと同じSurveyMode.plan()/ClearanceScannerで計算するため、確定時に
 * サーバー側で判定が変わるのは、その間にブロックが変化した場合などに限られる。
 */
public final class SurveySession implements SurveyToolItem.ClientHandler {
	public static final SurveySession INSTANCE = new SurveySession();

	/** ブロックの変化を拾うため、点が変わらなくても一定間隔で走査し直す */
	private static final int RESCAN_INTERVAL = 20;
	private static final int STATUS_INTERVAL = 10;
	/** 経由点を置ける距離(通常のブロック操作より遠くまで狙えるようにする) */
	private static final double PLACE_REACH = 160.0;
	/** 点・区間・分岐器を狙える距離と、視線からの許容距離 */
	private static final double TARGET_REACH = 64.0;
	private static final double POINT_TOLERANCE = 0.6;
	private static final double SEGMENT_TOLERANCE = 1.2;
	private static final double SWITCH_TOLERANCE = 0.9;
	/** 装置を狙える距離 */
	private static final double DEVICE_REACH = 24.0;

	private SurveyMode mode = SurveyModes.NEW_ROUTE;
	private final Map<String, Integer> params = new HashMap<>();
	private final List<SurveyPoint> points = new ArrayList<>();
	private boolean closed;
	private final List<Integer> selection = new ArrayList<>();
	private final Set<Long> removeSelection = new LinkedHashSet<>();
	private final Set<Long> removeFeatureSelection = new LinkedHashSet<>();

	/** 敷設する線路の道床 */
	private BallastType ballast = BallastType.GRAVEL;
	/** 強制置換(クリエイティブのみ有効) */
	private boolean forceReplace;
	/** 敷設する線路を電化する */
	private ElectrificationType electrification = ElectrificationType.NONE;

	private boolean dirty;
	private int rescanTimer;
	private int statusTimer;
	private int seenRevision = -1;
	private Preview preview;

	private int targetPoint = -1;
	private long targetSegment = -1L;
	private long targetSwitch = -1L;
	private long targetFeature = -1L;
	/** 連結モードで選択中の装置 */
	private BlockPos linkDevice;

	/**
	 * プレビュー結果。segmentsは線形の確認・描画用の仮区間(IDなし)。
	 * forceは強制置換が有効(クリエイティブで有効化)か。
	 */
	public record Preview(LayoutPlan plan, List<TrackSegment> segments, ClearanceScanner.ScanResult scan, boolean force) {
		public boolean canPlace() {
			return this.plan.isValid() && !this.scan.hasBlocking(this.force);
		}
	}

	private SurveySession() {
	}

	// ------------------------------------------------------------------ 参照(描画用)

	public SurveyMode mode() {
		return this.mode;
	}

	public List<SurveyPoint> points() {
		return this.points;
	}

	public boolean isClosed() {
		return this.closed;
	}

	public List<Integer> selection() {
		return this.selection;
	}

	public Set<Long> removeSelection() {
		return this.removeSelection;
	}

	public Preview preview() {
		return this.preview;
	}

	public int targetPoint() {
		return this.targetPoint;
	}

	public long targetSegment() {
		return this.targetSegment;
	}

	public long targetSwitch() {
		return this.targetSwitch;
	}

	public long targetFeature() {
		return this.targetFeature;
	}

	public BlockPos linkDevice() {
		return this.linkDevice;
	}

	/** 連結先の種類に応じた、今狙っている対象のID。 */
	private long linkTargetFor(DeviceKind.TargetType type) {
		return switch (type) {
			case SWITCH -> this.targetSwitch;
			case DECK -> this.targetFeature;
			case SEGMENT -> this.targetSegment;
			default -> -1L;
		};
	}

	/** 視線の先の線路装置(転てつてこ等、変電所を含む。無ければnull)。 */
	public static BlockPos lookedAtDevice(ClientPlayerEntity player) {
		HitResult hit = player.raycast(DEVICE_REACH, 1.0f, false);
		if (hit.getType() != HitResult.Type.BLOCK || player.getEntityWorld() == null) {
			return null;
		}
		BlockPos pos = ((BlockHitResult) hit).getBlockPos();
		return player.getEntityWorld().getBlockEntity(pos) instanceof TrackLinkable ? pos : null;
	}

	public Set<Long> removeFeatureSelection() {
		return this.removeFeatureSelection;
	}

	public BallastType ballast() {
		return this.ballast;
	}

	public ElectrificationType electrification() {
		return this.electrification;
	}

	/** 道床の種類(Iキー)・電化方式(スニーク+Iキー)は同じキーで切り替える。 */
	public void cycleElectrification() {
		this.electrification = this.electrification.next();
		this.dirty = true;
		this.statusTimer = 0;
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player != null) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.electrification_changed",
					Text.translatable("message.railwayvehicleaddon.electrification."
							+ this.electrification.name().toLowerCase(java.util.Locale.ROOT))), true);
		}
	}

	/** 強制置換が実際に効くか(有効化していて、かつクリエイティブ)。 */
	public boolean effectiveForce() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		return this.forceReplace && player != null && player.isCreative();
	}

	public int param() {
		return this.params.getOrDefault(this.mode.id(), this.mode.defaultParam());
	}

	public static boolean isHoldingTool(ClientPlayerEntity player) {
		return player != null && player.getMainHandStack().isOf(ModItems.SURVEY_TOOL);
	}

	// ------------------------------------------------------------------ 右クリック

	@Override
	public void onUse(boolean sneaking) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) {
			return;
		}
		if (this.mode == SurveyModes.LINK) {
			useLinkMode(player, sneaking);
			return;
		}
		if (!this.mode.placesTrack()) {
			if (sneaking) {
				this.removeSelection.clear();
				this.removeFeatureSelection.clear();
			} else if (this.targetFeature >= 0) {
				if (!this.removeFeatureSelection.remove(this.targetFeature)) {
					this.removeFeatureSelection.add(this.targetFeature);
				}
			} else if (this.targetSegment >= 0 && !this.removeSelection.remove(this.targetSegment)) {
				this.removeSelection.add(this.targetSegment);
			}
			return;
		}
		// 転車台・遷車台を狙っていれば動かす(スニーク中は逆方向)
		if (this.targetFeature >= 0 && this.targetPoint < 0) {
			ClientPlayNetworking.send(new FeatureActionPayload(this.targetFeature, sneaking ? -1 : 1));
			return;
		}
		if (sneaking) {
			if (!this.selection.isEmpty()) {
				this.selection.clear();
			} else {
				undo(player);
			}
			return;
		}
		if (this.targetPoint >= 0) {
			toggleSelection(this.targetPoint);
			return;
		}
		if (this.targetSwitch >= 0) {
			ClientPlayNetworking.send(new ToggleSwitchPayload(this.targetSwitch));
			return;
		}
		HitResult hit = player.raycast(PLACE_REACH, 1.0f, true);
		if (hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockPos block = ((BlockHitResult) hit).getBlockPos();
		Vec3d pos = new Vec3d(block.getX() + 0.5, block.getY() + 1.0, block.getZ() + 0.5);

		if (this.selection.size() == 1) {
			movePoint(player, this.selection.get(0), pos);
		} else if (this.selection.size() == 2) {
			insertPoint(player, pos);
		} else {
			appendPoint(player, pos);
		}
	}

	private void useLinkMode(ClientPlayerEntity player, boolean sneaking) {
		if (sneaking) {
			this.linkDevice = null;
			return;
		}
		BlockPos device = lookedAtDevice(player);
		if (device != null) {
			this.linkDevice = device;
			Block block = player.getEntityWorld().getBlockState(device).getBlock();
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.device.selected", block.getName()), true);
			return;
		}
		if (this.linkDevice == null) {
			return;
		}
		if (!(player.getEntityWorld().getBlockEntity(this.linkDevice) instanceof TrackLinkable entity)) {
			this.linkDevice = null;
			return;
		}
		long target = linkTargetFor(entity.linkTarget());
		if (target < 0) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.device.aim_target."
					+ entity.linkTarget().name().toLowerCase(java.util.Locale.ROOT)), true);
			return;
		}
		ClientPlayNetworking.send(new LinkDevicePayload(this.linkDevice, target));
	}

	private void toggleSelection(int index) {
		if (!this.selection.remove(Integer.valueOf(index))) {
			if (this.selection.size() >= 2) {
				this.selection.remove(0);
			}
			this.selection.add(index);
		}
	}

	/** 既存線路の端へ接続する点にするか判定する(接続できなければ位置そのままの点)。 */
	private SurveyPoint makePoint(Vec3d pos, int index, int count) {
		TrackNetwork network = ClientTrackData.network();
		if (this.mode.allowsSnap(index, count)) {
			TrackNode snap = network.findDeadEndNear(pos.x, pos.y, pos.z, TrackManager.SNAP_RADIUS, TrackManager.SNAP_RADIUS);
			if (snap != null) {
				return new SurveyPoint(new Vec3d(snap.x(), snap.y(), snap.z()), snap.id());
			}
		}
		if (this.mode.allowsTrackSnap(index)) {
			// 線路の途中: 近くにノードがあればそのノード、無ければ区間上の点
			TrackNetwork.Nearest nearest = network.nearestPoint(pos.x, pos.y, pos.z, TrackManager.SNAP_RADIUS);
			if (nearest != null) {
				TrackSegment segment = network.segment(nearest.segmentId());
				for (long nodeId : new long[]{segment.nodeA(), segment.nodeB()}) {
					TrackNode node = network.node(nodeId);
					if (node != null && !network.isReserved(nodeId)
							&& Math.hypot(node.x() - nearest.point().x(), node.z() - nearest.point().z()) < TrackManager.SNAP_RADIUS) {
						return new SurveyPoint(new Vec3d(node.x(), node.y(), node.z()), nodeId);
					}
				}
				if (network.featureOfSegment(nearest.segmentId()) == null) {
					TrackPoint p = nearest.point();
					return new SurveyPoint(new Vec3d(p.x(), p.y(), p.z()), -1L, nearest.segmentId(), nearest.s());
				}
			}
		}
		return new SurveyPoint(pos, -1L);
	}

	private void appendPoint(ClientPlayerEntity player, Vec3d pos) {
		RailwayConfig.Values config = ClientTrackData.config();
		if (this.closed) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.already_closed"), true);
			return;
		}
		if (this.points.size() >= config.maxWaypoints()) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.issue.too_many_waypoints", config.maxWaypoints()), true);
			return;
		}
		// 最初の点を狙った → 環状に閉じる
		if (this.mode.supportsClose() && this.points.size() >= 3) {
			Vec3d first = this.points.get(0).pos();
			if (Math.hypot(first.x - pos.x, first.z - pos.z) <= TrackManager.SNAP_RADIUS && Math.abs(first.y - pos.y) <= TrackManager.SNAP_RADIUS) {
				this.closed = true;
				this.dirty = true;
				player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.closed"), true);
				return;
			}
		}
		// 新規生成モードでは、終点を既存線路に接続したらそれ以上は伸ばせない
		if (this.mode.supportsInsert() && this.points.size() >= 2 && this.points.get(this.points.size() - 1).snapped()) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.end_already_snapped"), true);
			return;
		}
		SurveyPoint point = makePoint(pos, this.points.size(), this.points.size());
		this.points.add(point);
		this.dirty = true;
		String key = point.snapped() ? "message.railwayvehicleaddon.survey.snapped"
				: point.onTrack() ? "message.railwayvehicleaddon.survey.on_track" : "message.railwayvehicleaddon.survey.added";
		player.sendMessage(Text.translatable(key, this.points.size()), true);
	}

	private void movePoint(ClientPlayerEntity player, int index, Vec3d pos) {
		if (index < 0 || index >= this.points.size()) {
			this.selection.clear();
			return;
		}
		this.points.set(index, makePoint(pos, index, this.points.size()));
		this.selection.clear();
		this.dirty = true;
		player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.moved", index + 1), true);
	}

	private void insertPoint(ClientPlayerEntity player, Vec3d pos) {
		int a = Math.min(this.selection.get(0), this.selection.get(1));
		int b = Math.max(this.selection.get(0), this.selection.get(1));
		int n = this.points.size();
		boolean adjacent = b - a == 1 || (this.closed && a == 0 && b == n - 1);
		if (!this.mode.supportsInsert() || !adjacent) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.insert_invalid"), true);
			return;
		}
		// 環状線で最後と最初の点の間なら末尾に挿入する
		int at = (b - a == 1) ? b : n;
		this.points.add(at, new SurveyPoint(pos, -1L));
		this.selection.clear();
		this.dirty = true;
		player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.inserted", at + 1), true);
	}

	private void undo(ClientPlayerEntity player) {
		if (this.closed) {
			this.closed = false;
		} else if (!this.points.isEmpty()) {
			this.points.remove(this.points.size() - 1);
		} else {
			return;
		}
		this.dirty = true;
		player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.undone", this.points.size()), true);
	}

	// ------------------------------------------------------------------ キー操作

	/** 削除キー: 狙っている点、無ければ選択中の点を消す。 */
	public void deletePoints() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		List<Integer> targets = new ArrayList<>();
		if (this.targetPoint >= 0) {
			targets.add(this.targetPoint);
		} else {
			targets.addAll(this.selection);
		}
		if (targets.isEmpty()) {
			return;
		}
		targets.sort((x, y) -> Integer.compare(y, x));
		for (int index : targets) {
			if (index >= 0 && index < this.points.size()) {
				this.points.remove(index);
			}
		}
		if (this.points.size() < 3) {
			this.closed = false;
		}
		this.selection.clear();
		this.dirty = true;
		if (player != null) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.deleted", this.points.size()), true);
		}
	}

	public void clear() {
		this.points.clear();
		this.closed = false;
		this.selection.clear();
		this.removeSelection.clear();
		this.removeFeatureSelection.clear();
		this.linkDevice = null;
		this.preview = null;
		this.dirty = false;
	}

	public void cycleMode(int step) {
		clear();
		this.mode = SurveyModes.next(this.mode, step);
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player != null) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.mode_changed",
					Text.translatable("message.railwayvehicleaddon.mode." + this.mode.id())), true);
		}
	}

	public void cycleBallast() {
		this.ballast = this.ballast.next();
		this.statusTimer = 0;
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player != null) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.ballast_changed",
					Text.translatable("message.railwayvehicleaddon.ballast." + this.ballast.name().toLowerCase(java.util.Locale.ROOT))), true);
		}
	}

	public void toggleForce() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) {
			return;
		}
		if (!player.isCreative()) {
			this.forceReplace = false;
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.force_creative_only"), true);
			return;
		}
		this.forceReplace = !this.forceReplace;
		this.dirty = true;
		this.statusTimer = 0;
		player.sendMessage(Text.translatable(this.forceReplace
				? "message.railwayvehicleaddon.survey.force_on" : "message.railwayvehicleaddon.survey.force_off"), true);
	}

	/**
	 * サーバーからの配置結果。成功したときだけ点を消す。失敗(未ロードの範囲を含む・確定までの間に
	 * ブロックが変わった等)のときは点をそのまま残し、手直しして再度確定できるようにする。
	 */
	public void onPlaceResult(boolean success) {
		if (success) {
			clear();
		} else {
			this.dirty = true;
		}
	}

	public void adjustParam(int delta) {
		if (this.mode.maxParam() <= this.mode.minParam()) {
			return;
		}
		int value = Math.max(this.mode.minParam(), Math.min(this.mode.maxParam(), param() + delta));
		this.params.put(this.mode.id(), value);
		this.dirty = true;
		this.statusTimer = 0;
	}

	public void confirm() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) {
			return;
		}
		if (this.mode == SurveyModes.ELECTRIFY) {
			if (!this.removeSelection.isEmpty()) {
				ClientPlayNetworking.send(new ElectrifyPayload(new ArrayList<>(this.removeSelection), this.electrification.id()));
				this.removeSelection.clear();
			}
			return;
		}
		if (!this.mode.placesTrack()) {
			if (!this.removeSelection.isEmpty() || !this.removeFeatureSelection.isEmpty()) {
				ClientPlayNetworking.send(new RemoveSegmentsPayload(new ArrayList<>(this.removeSelection),
						new ArrayList<>(this.removeFeatureSelection)));
				this.removeSelection.clear();
				this.removeFeatureSelection.clear();
			}
			return;
		}
		if (this.points.size() < this.mode.minPoints()) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.need_points", this.mode.minPoints()), true);
			return;
		}
		recompute();
		if (this.preview == null || !this.preview.canPlace()) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.cannot_place"), true);
			return;
		}
		// 点は結果(PlaceResultPayload)を受け取ってから消す
		ClientPlayNetworking.send(new PlaceLayoutPayload(this.mode.id(), currentInput()));
	}

	private SurveyInput currentInput() {
		return new SurveyInput(new ArrayList<>(this.points), this.closed, param(), this.ballast.id(), effectiveForce(), this.electrification.id());
	}

	// ------------------------------------------------------------------ 毎tick

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) {
			return;
		}
		if (!isHoldingTool(player)) {
			this.targetPoint = -1;
			this.targetSegment = -1L;
			this.targetSwitch = -1L;
			this.targetFeature = -1L;
			return;
		}
		TrackNetwork network = ClientTrackData.network();
		// 接続先の端点が撤去された・端点でなくなった点は、接続を外して通常の点に戻す
		for (int i = 0; i < this.points.size(); i++) {
			SurveyPoint p = this.points.get(i);
			if (p.snapped() && !network.isDeadEnd(p.nodeId())) {
				this.points.set(i, new SurveyPoint(p.pos(), -1L));
				this.dirty = true;
			}
		}
		this.removeSelection.removeIf(id -> network.segment(id) == null);
		this.removeFeatureSelection.removeIf(id -> network.feature(id) == null);
		if (ClientTrackData.revision() != this.seenRevision) {
			this.seenRevision = ClientTrackData.revision();
			this.dirty = true;
		}
		if (this.dirty || --this.rescanTimer <= 0) {
			recompute();
		}

		Vec3d eye = player.getCameraPosVec(1.0f);
		Vec3d look = player.getRotationVec(1.0f);
		boolean link = this.mode == SurveyModes.LINK;
		boolean remove = this.mode == SurveyModes.REMOVE;
		this.targetPoint = this.mode.placesTrack() ? findTargetPoint(eye, look) : -1;
		this.targetSegment = this.mode.placesTrack() ? -1L : findTargetSegment(network, eye, look);
		this.targetSwitch = (this.mode.placesTrack() || link) && this.targetPoint < 0 ? findTargetSwitch(network, eye, look) : -1L;
		this.targetFeature = this.targetPoint < 0 && this.mode != SurveyModes.ELECTRIFY ? findTargetFeature(network, eye, look, remove) : -1L;
		if (this.targetFeature >= 0 && remove) {
			this.targetSegment = -1L;
		}
		if (link && this.linkDevice != null && player.getEntityWorld().getBlockEntity(this.linkDevice) instanceof TrackLinkable linkable) {
			// 選んだ装置が連結できる種類の対象だけを狙う
			DeviceKind.TargetType type = linkable.linkTarget();
			if (type != DeviceKind.TargetType.SWITCH) {
				this.targetSwitch = -1L;
			}
			if (type != DeviceKind.TargetType.DECK) {
				this.targetFeature = -1L;
			}
			if (type != DeviceKind.TargetType.SEGMENT) {
				this.targetSegment = -1L;
			}
		}

		if (--this.statusTimer <= 0) {
			this.statusTimer = STATUS_INTERVAL;
			showStatus(player);
		}
	}

	private void recompute() {
		this.dirty = false;
		this.rescanTimer = RESCAN_INTERVAL;
		MinecraftClient client = MinecraftClient.getInstance();
		if (!this.mode.placesTrack() || this.points.isEmpty() || client.world == null) {
			this.preview = null;
			return;
		}
		RailwayConfig.Values config = ClientTrackData.config();
		LayoutPlan plan = this.mode.plan(ClientTrackData.network(), currentInput(), config);
		List<TrackSegment> segments = plan.previewSegments(config.designSpeedKmh());
		Set<BlockPos> extra = new java.util.HashSet<>();
		for (LayoutPlan.FeatureSpec spec : plan.features()) {
			extra.addAll(spec.clearance(config));
		}
		ClearanceScanner.ScanResult scan = ClearanceScanner.scan(client.world, segments, extra, config, true);
		this.preview = new Preview(plan, segments, scan, effectiveForce());
	}

	private void showStatus(ClientPlayerEntity player) {
		Text modeName = Text.translatable("message.railwayvehicleaddon.mode." + this.mode.id());
		if (this.mode == SurveyModes.LINK) {
			player.sendMessage(Text.translatable(this.linkDevice == null
					? "message.railwayvehicleaddon.survey.status_link" : "message.railwayvehicleaddon.survey.status_link_selected", modeName), true);
			return;
		}
		if (this.mode == SurveyModes.ELECTRIFY) {
			Text typeName = Text.translatable("message.railwayvehicleaddon.electrification."
					+ this.electrification.name().toLowerCase(java.util.Locale.ROOT));
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.status_electrify", modeName, typeName,
					this.removeSelection.size()), true);
			return;
		}
		if (!this.mode.placesTrack()) {
			player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.status_remove", modeName,
					this.removeSelection.size(), this.removeFeatureSelection.size()), true);
			return;
		}
		Text paramText = Text.empty();
		if (this.mode.maxParam() > this.mode.minParam()) {
			paramText = paramText.copy().append(Text.translatable("message.railwayvehicleaddon.param." + this.mode.id(), this.mode.paramLabel(param())));
		}
		paramText = paramText.copy().append(Text.translatable("message.railwayvehicleaddon.survey.tag_ballast",
				Text.translatable("message.railwayvehicleaddon.ballast." + this.ballast.name().toLowerCase(java.util.Locale.ROOT))));
		if (this.electrification != ElectrificationType.NONE) {
			paramText = paramText.copy().append(Text.translatable("message.railwayvehicleaddon.survey.tag_electrify",
					Text.translatable("message.railwayvehicleaddon.electrification."
							+ this.electrification.name().toLowerCase(java.util.Locale.ROOT))));
		}
		if (effectiveForce()) {
			paramText = paramText.copy().append(Text.translatable("message.railwayvehicleaddon.survey.tag_force"));
		}
		Preview p = this.preview;
		Text detail;
		if (p == null) {
			detail = Text.translatable("message.railwayvehicleaddon.survey.hint." + this.mode.id());
		} else if (!p.plan().issues().isEmpty()) {
			RoutePlanner.Issue issue = p.plan().issues().get(0);
			detail = Text.translatable("message.railwayvehicleaddon.issue." + issue.key(), issue.formattedValue());
		} else if (p.segments().isEmpty() && p.plan().features().isEmpty()) {
			detail = Text.translatable("message.railwayvehicleaddon.survey.hint." + this.mode.id());
		} else if (p.scan().hasBlocking() && !p.scan().hasBlocking(p.force())) {
			// 強制置換で、本来撤去できないブロックも撤去する
			ClearanceScanner.ScanResult scan = p.scan();
			detail = Text.translatable("message.railwayvehicleaddon.survey.status_force",
					String.format("%.1f", p.plan().totalLength()), scan.count(BlockCategory.BLOCKED_FLUID), scan.count(BlockCategory.BLOCKED_HARD));
		} else if (p.scan().hasBlocking()) {
			ClearanceScanner.ScanResult scan = p.scan();
			detail = Text.translatable("message.railwayvehicleaddon.survey.status_blocked",
					scan.count(BlockCategory.BLOCKED_FLUID), scan.count(BlockCategory.BLOCKED_HARD),
					scan.count(BlockCategory.UNLOADED));
		} else {
			ClearanceScanner.ScanResult scan = p.scan();
			String key = scan.count(BlockCategory.PLAYER_PLACED) > 0
					? "message.railwayvehicleaddon.survey.status_warn" : "message.railwayvehicleaddon.survey.status_ok";
			detail = Text.translatable(key, String.format("%.1f", p.plan().totalLength()),
					scan.count(BlockCategory.NATURAL), scan.count(BlockCategory.PLAYER_PLACED));
		}
		player.sendMessage(Text.translatable("message.railwayvehicleaddon.survey.status_mode", modeName, paramText, detail), true);
	}

	// ------------------------------------------------------------------ 視線判定

	/** 視線上の点の距離(視線からの距離がtolerance以内のとき)。外れていれば-1。 */
	private static double rayDistance(Vec3d eye, Vec3d look, double x, double y, double z, double tolerance, double reach) {
		double dx = x - eye.x;
		double dy = y - eye.y;
		double dz = z - eye.z;
		double along = dx * look.x + dy * look.y + dz * look.z;
		if (along <= 0.0 || along > reach) {
			return -1.0;
		}
		double perpSq = dx * dx + dy * dy + dz * dz - along * along;
		return perpSq <= tolerance * tolerance ? along : -1.0;
	}

	private int findTargetPoint(Vec3d eye, Vec3d look) {
		int best = -1;
		double bestAlong = Double.MAX_VALUE;
		for (int i = 0; i < this.points.size(); i++) {
			Vec3d p = this.points.get(i).pos();
			double along = rayDistance(eye, look, p.x, p.y + 0.25, p.z, POINT_TOLERANCE, TARGET_REACH);
			if (along >= 0.0 && along < bestAlong) {
				bestAlong = along;
				best = i;
			}
		}
		return best;
	}

	private static long findTargetSwitch(TrackNetwork network, Vec3d eye, Vec3d look) {
		long best = -1L;
		double bestAlong = Double.MAX_VALUE;
		for (TrackNode node : network.nodes()) {
			double along = rayDistance(eye, look, node.x(), node.y() + 0.5, node.z(), SWITCH_TOLERANCE, TARGET_REACH);
			if (along >= 0.0 && along < bestAlong && network.isSwitch(node.id())) {
				bestAlong = along;
				best = node.id();
			}
		}
		return best;
	}

	/**
	 * 視線が設備の範囲(中心から半径以内の水平面)に当たっているか。placing(敷設モード中)では
	 * 動かせる設備(転車台・遷車台)だけを対象にし、撤去モードではすべての設備を対象にする。
	 */
	private static long findTargetFeature(TrackNetwork network, Vec3d eye, Vec3d look, boolean all) {
		long best = -1L;
		double bestT = Double.MAX_VALUE;
		for (TrackFeature feature : network.features()) {
			if (!all && !(feature instanceof MovingDeckFeature)) {
				continue;
			}
			Vec3d c = feature.center();
			if (Math.abs(look.y) < 1.0e-4) {
				continue;
			}
			double t = (c.y + 0.3 - eye.y) / look.y;
			if (t <= 0.0 || t > TARGET_REACH || t >= bestT) {
				continue;
			}
			double hx = eye.x + look.x * t - c.x;
			double hz = eye.z + look.z * t - c.z;
			if (hx * hx + hz * hz <= feature.radius() * feature.radius()) {
				bestT = t;
				best = feature.id();
			}
		}
		return best;
	}

	private static long findTargetSegment(TrackNetwork network, Vec3d eye, Vec3d look) {
		long best = -1L;
		double bestAlong = Double.MAX_VALUE;
		for (long id : network.segmentsNear(eye.x, eye.z, TARGET_REACH)) {
			TrackSegment segment = network.segment(id);
			if (segment == null) {
				continue;
			}
			double length = segment.length();
			int steps = Math.max(1, (int) Math.ceil(length / 0.5));
			for (int i = 0; i <= steps; i++) {
				TrackPoint p = segment.sample(length * i / steps);
				double along = rayDistance(eye, look, p.x(), p.y() + 0.2, p.z(), SEGMENT_TOLERANCE, TARGET_REACH);
				if (along >= 0.0 && along < bestAlong) {
					bestAlong = along;
					best = id;
				}
			}
		}
		return best;
	}
}
