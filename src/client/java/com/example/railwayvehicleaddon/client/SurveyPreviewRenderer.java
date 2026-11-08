package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.block.DeviceLinks;
import com.example.railwayvehicleaddon.block.TrackLinkable;
import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.survey.SurveyPoint;
import com.example.railwayvehicleaddon.track.BlockCategory;
import com.example.railwayvehicleaddon.track.RoutePlanner;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackNode;
import com.example.railwayvehicleaddon.track.TrackPoint;
import com.example.railwayvehicleaddon.track.TrackSegment;
import com.example.railwayvehicleaddon.track.feature.TrackFeature;
import com.example.railwayvehicleaddon.survey.LayoutPlan;
import com.example.railwayvehicleaddon.survey.SurveyModes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 測量プレビューの表示。前提MODのMortarMarkerRenderer等と同じく、毎tickギズモを
 * 登録し直す方式(ワールド描画のたびに常に描かれ、デバッグ表示の設定には依存しない)。
 * 地形の中を通る区間や埋まったブロックも見えるよう、遮蔽を無視して描く。
 */
public final class SurveyPreviewRenderer {
	private static final int COLOR_OK = 0xFF34C759;
	private static final int COLOR_ISSUE = 0xFFFF3B30;
	private static final int COLOR_POINT = 0xFF0A84FF;
	private static final int COLOR_SNAPPED = 0xFFBF5AF2;
	private static final int COLOR_SELECTED = 0xFFFFFFFF;
	private static final int COLOR_TARGET = 0xFFFF9500;
	private static final int COLOR_SWITCH = 0xFF64D2FF;
	private static final int COLOR_REMOVE = 0xFFFF3B30;
	private static final int COLOR_BLOCKED_STROKE = 0xFFFF3B30;
	private static final int COLOR_BLOCKED_FILL = 0x55FF3B30;
	private static final int COLOR_PLAYER_STROKE = 0xFFFFCC00;
	private static final int COLOR_FORCE_STROKE = 0xFFFF2DAA;
	private static final int COLOR_FORCE_FILL = 0x55FF2DAA;
	private static final int COLOR_PLAYER_FILL = 0x44FFCC00;
	private static final float LINE_WIDTH = 2.0f;
	/** 一度に枠を描く問題ブロックの上限(プレイヤーに近い順) */
	private static final int MAX_BLOCK_BOXES = 400;
	/** 分岐器の表示範囲 */
	private static final double SWITCH_DISPLAY_RANGE = 64.0;

	private SurveyPreviewRenderer() {
	}

	public static void tick(Minecraft client) {
		if (client.player == null || client.level == null || !SurveySession.isHoldingTool(client.player)) {
			return;
		}
		SurveySession session = SurveySession.INSTANCE;
		RailwayConfig.Values config = ClientTrackData.config();
		TrackNetwork network = ClientTrackData.network();
		Vec3 eye = client.player.getEyePosition();
		try (var scope = client.collectPerTickGizmos()) {
			drawSwitches(network, session, eye, config);
			drawDeviceLinks(client, network, session);

			// 狙っている設備(敷設モードでは転車台・遷車台。右クリックで動く)
			TrackFeature targetFeature = network.feature(session.targetFeature());
			if (targetFeature != null && !session.removeFeatureSelection().contains(targetFeature.id())) {
				drawFeatureMarker(targetFeature, COLOR_TARGET);
			}
			for (long id : session.removeFeatureSelection()) {
				TrackFeature feature = network.feature(id);
				if (feature != null) {
					drawFeatureMarker(feature, COLOR_REMOVE);
				}
			}

			if (!session.mode().placesTrack()) {
				// 撤去モード: 選択済みは赤、狙っている区間はオレンジ(電化モードでは選択済みは水色)
				int selectedColor = session.mode() == SurveyModes.ELECTRIFY ? COLOR_SWITCH : COLOR_REMOVE;
				for (long id : session.removeSelection()) {
					TrackSegment segment = network.segment(id);
					if (segment != null) {
						drawRails(segment, config.gauge(), selectedColor);
					}
				}
				TrackSegment target = network.segment(session.targetSegment());
				if (target != null && !session.removeSelection().contains(target.id())) {
					drawRails(target, config.gauge(), COLOR_TARGET);
				}
				return;
			}

			// 点(選択中は白、狙っている点はオレンジ、既存線路へ接続する点は紫)
			List<SurveyPoint> points = session.points();
			for (int i = 0; i < points.size(); i++) {
				SurveyPoint p = points.get(i);
				int color = session.selection().contains(i) ? COLOR_SELECTED
						: i == session.targetPoint() ? COLOR_TARGET
						: p.snapped() ? COLOR_SNAPPED : COLOR_POINT;
				Vec3 w = p.pos();
				double size = i == 0 ? 0.35 : 0.25;
				Gizmos.cuboid(new AABB(w.x - size, w.y, w.z - size, w.x + size, w.y + 0.5 + (i == 0 ? 0.25 : 0.0), w.z + size),
						GizmoStyle.stroke(color, LINE_WIDTH)).setAlwaysOnTop();
			}

			SurveyPreviewRenderer.drawPreview(session, config, eye, client);
		}
	}

	private static void drawPreview(SurveySession session, RailwayConfig.Values config, Vec3 eye, Minecraft client) {
		SurveySession.Preview preview = session.preview();
		List<SurveyPoint> points = session.points();
		if (preview == null || (preview.segments().isEmpty() && preview.plan().features().isEmpty())) {
			// 線形がまだ作れない(点が1つだけ等)ときは、最後の点からプレイヤー位置へ仮の線で方向を示す
			if (!points.isEmpty()) {
				Vec3 w = points.get(points.size() - 1).pos();
				Gizmos.line(w.add(0.0, 0.25, 0.0), client.player.position().add(0.0, 0.25, 0.0), COLOR_POINT, 1.0f);
			}
			return;
		}

		// 線形(問題がある場合は赤)
		List<RoutePlanner.Issue> issues = preview.plan().issues();
		int color = issues.isEmpty() ? COLOR_OK : COLOR_ISSUE;
		for (TrackSegment segment : preview.segments()) {
			drawRails(segment, config.gauge(), color);
		}
		for (LayoutPlan.FeatureSpec spec : preview.plan().features()) {
			for (Vec3[] line : spec.outline()) {
				Gizmos.line(line[0], line[1], color, LINE_WIDTH).setAlwaysOnTop();
			}
		}
		for (RoutePlanner.Issue issue : issues) {
			Vec3 p = issue.position();
			Gizmos.cuboid(new AABB(p.x - 0.5, p.y, p.z - 0.5, p.x + 0.5, p.y + 2.0, p.z + 0.5),
					GizmoStyle.stroke(COLOR_ISSUE, LINE_WIDTH)).setAlwaysOnTop();
		}

		// 警告対象のブロック(撤去不可=赤、設置物=黄)
		List<Map.Entry<BlockPos, BlockCategory>> warned = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockCategory> entry : preview.scan().blocks().entrySet()) {
			if (entry.getValue().warns()) {
				warned.add(entry);
			}
		}
		if (warned.size() > MAX_BLOCK_BOXES) {
			warned.sort(Comparator.comparingDouble(e -> e.getKey().distToCenterSqr(eye)));
			warned = warned.subList(0, MAX_BLOCK_BOXES);
		}
		for (Map.Entry<BlockPos, BlockCategory> entry : warned) {
			BlockCategory category = entry.getValue();
			GizmoStyle style;
			if (category.blocksPlacement() && preview.force() && category != BlockCategory.UNLOADED) {
				// 強制置換で撤去されるブロック(本来は撤去できない)
				style = GizmoStyle.strokeAndFill(COLOR_FORCE_STROKE, LINE_WIDTH, COLOR_FORCE_FILL);
			} else if (category.blocksPlacement()) {
				style = GizmoStyle.strokeAndFill(COLOR_BLOCKED_STROKE, LINE_WIDTH, COLOR_BLOCKED_FILL);
			} else {
				style = GizmoStyle.strokeAndFill(COLOR_PLAYER_STROKE, LINE_WIDTH, COLOR_PLAYER_FILL);
			}
			Gizmos.cuboid(entry.getKey(), style).setAlwaysOnTop();
		}
	}

	/**
	 * 近くの分岐器を表示する。ノード上の枠と、開通している分岐側の区間の最初の数ブロックを線で示す。
	 * 狙っている分岐器はオレンジ(右クリックで切替)。
	 */
	private static void drawSwitches(TrackNetwork network, SurveySession session, Vec3 eye, RailwayConfig.Values config) {
		for (TrackNode node : network.nodes()) {
			if (eye.distanceToSqr(node.x(), node.y(), node.z()) > SWITCH_DISPLAY_RANGE * SWITCH_DISPLAY_RANGE
					|| !network.isSwitch(node.id())) {
				continue;
			}
			int color = node.id() == session.targetSwitch() ? COLOR_TARGET : COLOR_SWITCH;
			Gizmos.cuboid(new AABB(node.x() - 0.4, node.y(), node.z() - 0.4, node.x() + 0.4, node.y() + 1.0, node.z() + 0.4),
					GizmoStyle.stroke(color, LINE_WIDTH)).setAlwaysOnTop();
			TrackSegment active = network.segment(network.activeBranch(node.id()));
			if (active == null) {
				continue;
			}
			boolean fromA = active.nodeA() == node.id();
			double length = Math.min(6.0, active.length());
			Vec3 prev = null;
			for (int i = 0; i <= 12; i++) {
				double s = length * i / 12.0;
				TrackPoint p = active.sample(fromA ? s : active.length() - s);
				Vec3 cur = new Vec3(p.x(), p.y() + RailVehicleEntity.RAIL_TOP + 0.3, p.z());
				if (prev != null) {
					Gizmos.line(prev, cur, color, 3.0f).setAlwaysOnTop();
				}
				prev = cur;
			}
		}
	}

	/** 狙っている装置(と連結モードで選択中の装置)から連結先へ線を引く。 */
	private static void drawDeviceLinks(Minecraft client, TrackNetwork network, SurveySession session) {
		BlockPos looked = SurveySession.lookedAtDevice(client.player);
		for (BlockPos pos : new BlockPos[]{looked, session.linkDevice()}) {
			if (pos == null || !(client.level.getBlockEntity(pos) instanceof TrackLinkable entity)) {
				continue;
			}
			int color = pos.equals(session.linkDevice()) ? COLOR_SELECTED : COLOR_SWITCH;
			Gizmos.cuboid(pos, GizmoStyle.stroke(color, LINE_WIDTH)).setAlwaysOnTop();
			Vec3 target = DeviceLinks.targetPosition(network, entity.linkTarget(), entity.targetId());
			if (target != null) {
				Gizmos.line(Vec3.atCenterOf(pos), target.add(0.0, 0.5, 0.0), color, LINE_WIDTH).setAlwaysOnTop();
			}
		}
	}

	/** 設備の範囲(中心から半径の円)を示す。 */
	private static void drawFeatureMarker(TrackFeature feature, int color) {
		Vec3 c = feature.center();
		double r = feature.radius();
		Vec3 prev = null;
		for (int i = 0; i <= 32; i++) {
			double a = Math.PI * 2.0 * i / 32.0;
			Vec3 cur = new Vec3(c.x + Math.cos(a) * r, c.y + 0.35, c.z + Math.sin(a) * r);
			if (prev != null) {
				Gizmos.line(prev, cur, color, LINE_WIDTH).setAlwaysOnTop();
			}
			prev = cur;
		}
		Gizmos.line(c, c.add(0.0, 2.0, 0.0), color, LINE_WIDTH).setAlwaysOnTop();
	}

	/** 左右のレール位置に線を引く(1ブロック間隔の折れ線)。 */
	private static void drawRails(TrackSegment segment, float gauge, int color) {
		double length = segment.length();
		int steps = Math.max(1, (int) Math.ceil(length));
		double half = gauge / 2.0;
		Vec3 prevLeft = null;
		Vec3 prevRight = null;
		for (int i = 0; i <= steps; i++) {
			TrackPoint p = segment.sample(length * i / steps);
			double rise = half * Math.sin(p.cantRad());
			double y = p.y() + RailVehicleEntity.RAIL_TOP;
			Vec3 left = new Vec3(p.x() + p.lateralX() * half, y + rise, p.z() + p.lateralZ() * half);
			Vec3 right = new Vec3(p.x() - p.lateralX() * half, y - rise, p.z() - p.lateralZ() * half);
			if (prevLeft != null) {
				Gizmos.line(prevLeft, left, color, LINE_WIDTH).setAlwaysOnTop();
				Gizmos.line(prevRight, right, color, LINE_WIDTH).setAlwaysOnTop();
			}
			prevLeft = left;
			prevRight = right;
		}
	}
}
