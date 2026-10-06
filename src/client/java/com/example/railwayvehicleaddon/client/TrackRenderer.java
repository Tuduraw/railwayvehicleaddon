package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackPoint;
import com.example.railwayvehicleaddon.track.BallastType;
import com.example.railwayvehicleaddon.track.ElectrificationType;
import com.example.railwayvehicleaddon.track.TrackSegment;
import com.example.railwayvehicleaddon.track.feature.MovingDeckFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敷設済みの線路の描画。区間ごとにメッシュ(ワールド座標の四角形列)を作ってキャッシュし、
 * 毎フレームはカメラ相対に直して頂点を流すだけにする。
 *
 * <p>断面(線路基面からの高さ): バラスト上面0.05、枕木0〜0.10、レール0.10〜RAIL_TOP(0.25)。
 * カントは線路中心(基面高さ)を軸に断面ごと回転させる。バラストは上面の両端から45°の法面で
 * 地面(直下の当たり判定のあるブロックの上面)まで下ろし、勾配区間の段差やカントで持ち上がった
 * 側とブロックの間の隙間を埋める。地面が深すぎる(橋など)ときは薄い道床だけにする。
 * 地面の形はメッシュ作成時に調べるため、周囲のブロックの変化は定期的な作り直しで反映する。
 */
public final class TrackRenderer {
	static final Identifier TEXTURE = Identifier.of(RailwayVehicleAddon.MOD_ID, "textures/track/track.png");
	static final double SLEEPER_TOP = 0.10;
	private static final double BALLAST_TOP = 0.05;
	static final double RAIL_HALF_WIDTH = 0.05;
	static final double SLEEPER_SPACING = 0.6;
	static final double SLEEPER_HALF_LENGTH = 0.12;
	static final double SLEEPER_OVERHANG = 0.35;
	private static final double BALLAST_SHOULDER = 0.2;
	/** 法面を地面まで下ろす最大の深さ。これより深い(橋など)ときは薄い道床にする */
	private static final double BALLAST_MAX_DEPTH = 3.0;
	private static final double BALLAST_BRIDGE_DEPTH = 0.3;
	private static final double RAIL_STEP = 0.5;
	/** 架線柱の間隔と、線路中心からの距離(建築限界の片側幅1.5の内側、車体幅の外側) */
	private static final double MAST_SPACING = 12.0;
	private static final double MAST_OFFSET = 1.42;
	/** 近くのメッシュを作り直す間隔(tick)と範囲。地面の変化を反映するため */
	private static final long REBUILD_INTERVAL = 200L;
	private static final long INCOMPLETE_REBUILD_INTERVAL = 20L;
	private static final double REBUILD_RANGE = 96.0;
	private static final int MAX_REBUILDS_PER_FRAME = 2;

	/** UV範囲: レール / 枕木 / 砕石 / 土(テクスチャを横に4等分) */
	static final float[] RAIL_UV = {0.0f, 0.0f, 0.25f, 1.0f};
	static final float[] SLEEPER_UV = {0.25f, 0.0f, 0.5f, 1.0f};
	private static final float[] GRAVEL_UV = {0.5f, 0.0f, 0.75f, 1.0f};
	private static final float[] DIRT_UV = {0.75f, 0.0f, 1.0f, 1.0f};

	private static final Map<Long, Mesh> CACHE = new HashMap<>();
	private static float cachedGauge = Float.NaN;

	private TrackRenderer() {
	}

	public static void invalidate(long segmentId) {
		CACHE.remove(segmentId);
	}

	public static void invalidateAll() {
		CACHE.clear();
	}

	/**
	 * 四角形の集合。verts: 四角形ごとに4頂点×xyz、normals: 四角形ごとにxyz、uv: 四角形ごとの範囲、
	 * lightPos: 四角形ごとの明るさ取得位置。builtAt: 作成時のワールド時刻、incomplete: 未ロードの地面があった
	 */
	private record Mesh(double[] verts, float[] normals, float[][] uv, long[] lightPos, int quads, long builtAt, boolean incomplete) {
	}

	private static final class MeshBuilder {
		final List<double[]> quads = new ArrayList<>();
		final List<float[]> normals = new ArrayList<>();
		final List<float[]> uvs = new ArrayList<>();
		final List<Long> lights = new ArrayList<>();
		boolean incomplete;

		void quad(Vec3d a, Vec3d b, Vec3d c, Vec3d d, float[] uv, Vec3d lightAt) {
			Vec3d n = b.subtract(a).crossProduct(d.subtract(a));
			double len = n.length();
			if (len < 1.0e-9) {
				n = c.subtract(b).crossProduct(a.subtract(b));
				len = n.length();
				if (len < 1.0e-9) {
					return;
				}
			}
			this.quads.add(new double[]{a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z});
			this.normals.add(new float[]{(float) (n.x / len), (float) (n.y / len), (float) (n.z / len)});
			this.uvs.add(uv);
			this.lights.add(BlockPos.ofFloored(lightAt.x, lightAt.y + 0.3, lightAt.z).asLong());
		}

		Mesh build(long time) {
			int count = this.quads.size();
			double[] verts = new double[count * 12];
			float[] normals = new float[count * 3];
			float[][] uv = new float[count][];
			long[] light = new long[count];
			for (int i = 0; i < count; i++) {
				System.arraycopy(this.quads.get(i), 0, verts, i * 12, 12);
				System.arraycopy(this.normals.get(i), 0, normals, i * 3, 3);
				uv[i] = this.uvs.get(i);
				light[i] = this.lights.get(i);
			}
			return new Mesh(verts, normals, uv, light, count, time, this.incomplete);
		}
	}

	/** 断面座標(横方向lat、高さh)をワールド座標へ。カント角だけ線路中心まわりに回す。 */
	private static Vec3d crossSection(TrackPoint p, double lat, double h) {
		double c = p.cantRad();
		double cos = Math.cos(c);
		double sin = Math.sin(c);
		double l = lat * cos - h * sin;
		double u = lat * sin + h * cos;
		return new Vec3d(p.x() + p.lateralX() * l, p.y() + u, p.z() + p.lateralZ() * l);
	}

	/**
	 * (x, z)の真下で、高さfromY以下にある最初の地面の高さ(当たり判定の上面)。
	 * 見つからなければNaN、未ロードならnullの代わりにbuilder.incompleteを立ててNaN。
	 */
	private static double groundBelow(ClientWorld world, double x, double fromY, double z, MeshBuilder builder) {
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
			builder.incomplete = true;
			return Double.NaN;
		}
		int top = (int) Math.floor(fromY);
		int bottom = (int) Math.floor(fromY - BALLAST_MAX_DEPTH);
		for (int y = top; y >= bottom; y--) {
			pos.set(bx, y, bz);
			BlockState state = world.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}
			VoxelShape shape = state.getCollisionShape(world, pos);
			if (shape.isEmpty()) {
				continue;
			}
			double surface = y + shape.getMax(Direction.Axis.Y);
			if (surface <= fromY + 1.0e-3) {
				return surface;
			}
			// 基面より上に出ているブロック(切り通しの壁など)は地面とみなさずその下を探す
		}
		return Double.NaN;
	}

	/** バラストの法面の下端(断面の片側)。 */
	private static Vec3d ballastFoot(ClientWorld world, TrackPoint p, Vec3d topEdge, double side, double topHalf, MeshBuilder builder) {
		double ground = groundBelow(world, topEdge.x, topEdge.y, topEdge.z, builder);
		double depth = Double.isNaN(ground) ? BALLAST_BRIDGE_DEPTH : Math.max(0.02, topEdge.y - ground);
		double spread = Double.isNaN(ground) ? 0.0 : depth;
		double lat = side * (topHalf + spread);
		// 下端は水平に外へ広げ、高さは地面(または道床の厚さ分下)
		Vec3d outward = new Vec3d(p.lateralX() * lat, 0.0, p.lateralZ() * lat);
		return new Vec3d(p.x() + outward.x, topEdge.y - depth, p.z() + outward.z);
	}

	/**
	 * 分岐器で開通していない分岐側の区間について、分岐器から他の分岐と線路が重ならなくなる
	 * (中心線の間隔が軌間以上になる)までの距離。この範囲のレールは描かないので、分岐部では
	 * 開通している方向のレールだけがつながって見える。開通していれば0。
	 */
	private static double turnoutGap(TrackNetwork network, TrackSegment segment, long nodeId, float gauge) {
		List<Long> branches = network.switchBranches(nodeId);
		if (branches.isEmpty() || !branches.contains(segment.id()) || network.activeBranch(nodeId) == segment.id()) {
			return 0.0;
		}
		List<double[]> others = new ArrayList<>();
		for (long id : branches) {
			TrackSegment other = network.segment(id);
			if (other == null || id == segment.id()) {
				continue;
			}
			boolean fromA = other.nodeA() == nodeId;
			double len = other.length();
			for (double d = 0.0; d <= Math.min(len, 48.0); d += 0.5) {
				TrackPoint p = other.sample(fromA ? d : len - d);
				others.add(new double[]{p.x(), p.z()});
			}
		}
		boolean fromA = segment.nodeA() == nodeId;
		double length = segment.length();
		double limit = Math.min(length * 0.5, 40.0);
		for (double d = 0.5; d <= limit; d += 0.5) {
			TrackPoint p = segment.sample(fromA ? d : length - d);
			double min = Double.MAX_VALUE;
			for (double[] o : others) {
				min = Math.min(min, Math.hypot(o[0] - p.x(), o[1] - p.z()));
			}
			if (min >= gauge) {
				return d;
			}
		}
		return limit;
	}

	/** 直方体(中心c、水平の2軸と高さの範囲)。 */
	private static void column(MeshBuilder builder, Vec3d c, double ax, double az, double half, double y0, double y1, float[] uv) {
		double bx = az;
		double bz = -ax;
		Vec3d[] bottom = new Vec3d[4];
		Vec3d[] top = new Vec3d[4];
		double[][] k = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
		for (int i = 0; i < 4; i++) {
			double x = c.x + (ax * k[i][0] + bx * k[i][1]) * half;
			double z = c.z + (az * k[i][0] + bz * k[i][1]) * half;
			bottom[i] = new Vec3d(x, y0, z);
			top[i] = new Vec3d(x, y1, z);
		}
		builder.quad(top[0], top[1], top[2], top[3], uv, c);
		for (int i = 0; i < 4; i++) {
			int n = (i + 1) % 4;
			builder.quad(bottom[n], bottom[i], top[i], top[n], uv, c);
		}
	}

	/** aからbへの細い棒(水平・垂直の2枚の帯で、どの向きから見ても線として見える)。 */
	private static void wire(MeshBuilder builder, Vec3d a, Vec3d b, double half) {
		Vec3d d = b.subtract(a);
		double len = Math.hypot(d.x, d.z);
		double sx = len > 1.0e-6 ? -d.z / len * half : half;
		double sz = len > 1.0e-6 ? d.x / len * half : 0.0;
		builder.quad(a.add(-sx, 0.0, -sz), a.add(sx, 0.0, sz), b.add(sx, 0.0, sz), b.add(-sx, 0.0, -sz), RAIL_UV, a);
		builder.quad(a.add(0.0, -half, 0.0), a.add(0.0, half, 0.0), b.add(0.0, half, 0.0), b.add(0.0, -half, 0.0), RAIL_UV, a);
	}

	/** 第三軌条: 走行レールの外側・一段低い位置に導体レールを這わせ、一定間隔で碍子(支持架)を立てる。 */
	private static final double THIRD_RAIL_OFFSET = 0.55;
	private static final double THIRD_RAIL_TOP = 0.18;
	private static final double THIRD_RAIL_HALF_WIDTH = 0.09;
	private static final double THIRD_RAIL_SUPPORT_SPACING = 3.0;

	/**
	 * 架線(電化区間・架線式): 線路中心の真上にトロリ線を張り、区間に沿って一定間隔で片側に架線柱と
	 * 腕金(カンチレバー)を立てる。架線柱は建築限界の内側(車両に当たらない位置)に置く。
	 */
	private static void buildOverhead(MeshBuilder builder, TrackNetwork network, TrackSegment segment, TrackPoint[] samples, int steps, double wire) {
		for (int i = 1; i <= steps; i++) {
			TrackPoint a = samples[i - 1];
			TrackPoint b = samples[i];
			wire(builder, new Vec3d(a.x(), a.y() + wire, a.z()), new Vec3d(b.x(), b.y() + wire, b.z()), 0.02);
		}
		double length = segment.length();
		int masts = Math.max(1, (int) Math.ceil(length / MAST_SPACING));
		for (int k = 0; k < masts; k++) {
			double s = (k + 0.5) * length / masts;
			TrackPoint p = network.sample(segment, s);
			Vec3d center = new Vec3d(p.x(), p.y(), p.z());
			Vec3d base = center.add(p.lateralX() * MAST_OFFSET, 0.0, p.lateralZ() * MAST_OFFSET);
			column(builder, base, p.dirX(), p.dirZ(), 0.07, p.y(), p.y() + wire + 0.28, RAIL_UV);
			// 腕金: 架線柱の上部から線路中心の上へ
			Vec3d armStart = new Vec3d(base.x, p.y() + wire + 0.25, base.z);
			Vec3d armEnd = new Vec3d(center.x, p.y() + wire + 0.25, center.z);
			wire(builder, armStart, armEnd, 0.035);
			// ハンガー: 腕金からトロリ線へ
			wire(builder, armEnd, new Vec3d(center.x, p.y() + wire, center.z), 0.015);
		}
	}

	private static void buildThirdRail(MeshBuilder builder, TrackNetwork network, TrackSegment segment, TrackPoint[] samples, int steps, double gauge) {
		double lat = gauge / 2.0 + THIRD_RAIL_OFFSET;
		double in = lat - THIRD_RAIL_HALF_WIDTH;
		double out = lat + THIRD_RAIL_HALF_WIDTH;
		for (int i = 1; i <= steps; i++) {
			TrackPoint prev = samples[i - 1];
			TrackPoint cur = samples[i];
			Vec3d lightAt = new Vec3d(cur.x(), cur.y(), cur.z());
			builder.quad(crossSection(prev, in, THIRD_RAIL_TOP), crossSection(prev, out, THIRD_RAIL_TOP),
					crossSection(cur, out, THIRD_RAIL_TOP), crossSection(cur, in, THIRD_RAIL_TOP), RAIL_UV, lightAt);
			builder.quad(crossSection(prev, out, 0.0), crossSection(cur, out, 0.0),
					crossSection(cur, out, THIRD_RAIL_TOP), crossSection(prev, out, THIRD_RAIL_TOP), RAIL_UV, lightAt);
			builder.quad(crossSection(cur, in, 0.0), crossSection(prev, in, 0.0),
					crossSection(prev, in, THIRD_RAIL_TOP), crossSection(cur, in, THIRD_RAIL_TOP), RAIL_UV, lightAt);
		}
		double length = segment.length();
		int supports = Math.max(1, (int) Math.ceil(length / THIRD_RAIL_SUPPORT_SPACING));
		for (int k = 0; k < supports; k++) {
			double s = (k + 0.5) * length / supports;
			TrackPoint p = network.sample(segment, s);
			Vec3d base = new Vec3d(p.x() + p.lateralX() * lat, p.y(), p.z() + p.lateralZ() * lat);
			column(builder, base, p.dirX(), p.dirZ(), 0.05, p.y(), p.y() + THIRD_RAIL_TOP, RAIL_UV);
		}
	}

	private static Mesh buildMesh(ClientWorld world, TrackNetwork network, TrackSegment segment, float gauge) {
		MeshBuilder builder = new MeshBuilder();
		double length = segment.length();
		double half = gauge / 2.0;
		double sleeperHalfLat = half + SLEEPER_OVERHANG;
		double ballastHalf = sleeperHalfLat + BALLAST_SHOULDER;
		BallastType ballast = segment.ballastType();
		float[] ballastUv = ballast == BallastType.DIRT ? DIRT_UV : GRAVEL_UV;
		boolean hasBallast = ballast != BallastType.NONE;
		double gapA = turnoutGap(network, segment, segment.nodeA(), gauge);
		double gapB = turnoutGap(network, segment, segment.nodeB(), gauge);

		int steps = Math.max(1, (int) Math.ceil(length / RAIL_STEP));
		TrackPoint[] samples = new TrackPoint[steps + 1];
		Vec3d[] topL = new Vec3d[steps + 1];
		Vec3d[] topR = new Vec3d[steps + 1];
		Vec3d[] footL = new Vec3d[steps + 1];
		Vec3d[] footR = new Vec3d[steps + 1];
		for (int i = 0; i <= steps; i++) {
			TrackPoint p = network.sample(segment, length * i / steps);
			samples[i] = p;
			if (hasBallast) {
				topL[i] = crossSection(p, ballastHalf, BALLAST_TOP);
				topR[i] = crossSection(p, -ballastHalf, BALLAST_TOP);
				footL[i] = ballastFoot(world, p, topL[i], 1.0, ballastHalf, builder);
				footR[i] = ballastFoot(world, p, topR[i], -1.0, ballastHalf, builder);
			}
		}

		for (int i = 1; i <= steps; i++) {
			TrackPoint prev = samples[i - 1];
			TrackPoint cur = samples[i];
			Vec3d lightAt = new Vec3d(cur.x(), cur.y(), cur.z());
			double s0 = length * (i - 1) / steps;
			double s1 = length * i / steps;
			// レール(左右それぞれ、上面と両側面)。分岐器で開通していない側の分岐部は描かない
			if (s0 >= gapA - 1.0e-6 && s1 <= length - gapB + 1.0e-6) {
				for (double side : new double[]{-half, half}) {
					double in = side - RAIL_HALF_WIDTH;
					double out = side + RAIL_HALF_WIDTH;
					builder.quad(crossSection(prev, in, RailVehicleEntity.RAIL_TOP), crossSection(prev, out, RailVehicleEntity.RAIL_TOP),
							crossSection(cur, out, RailVehicleEntity.RAIL_TOP), crossSection(cur, in, RailVehicleEntity.RAIL_TOP), RAIL_UV, lightAt);
					builder.quad(crossSection(prev, out, SLEEPER_TOP), crossSection(cur, out, SLEEPER_TOP),
							crossSection(cur, out, RailVehicleEntity.RAIL_TOP), crossSection(prev, out, RailVehicleEntity.RAIL_TOP), RAIL_UV, lightAt);
					builder.quad(crossSection(cur, in, SLEEPER_TOP), crossSection(prev, in, SLEEPER_TOP),
							crossSection(prev, in, RailVehicleEntity.RAIL_TOP), crossSection(cur, in, RailVehicleEntity.RAIL_TOP), RAIL_UV, lightAt);
				}
			}
			// 道床(上面・左右の法面・底面)
			if (hasBallast) {
				builder.quad(topR[i - 1], topL[i - 1], topL[i], topR[i], ballastUv, lightAt);
				builder.quad(footL[i - 1], footL[i], topL[i], topL[i - 1], ballastUv, lightAt);
				builder.quad(footR[i], footR[i - 1], topR[i - 1], topR[i], ballastUv, lightAt);
				builder.quad(footL[i], footL[i - 1], footR[i - 1], footR[i], ballastUv, lightAt);
			}
		}
		if (hasBallast) {
			// 道床の端面(区間の両端。接続先がある所では隠れる)
			builder.quad(footR[0], footL[0], topL[0], topR[0], ballastUv, new Vec3d(samples[0].x(), samples[0].y(), samples[0].z()));
			builder.quad(footL[steps], footR[steps], topR[steps], topL[steps], ballastUv,
					new Vec3d(samples[steps].x(), samples[steps].y(), samples[steps].z()));
		}

		switch (segment.electrificationType()) {
			case OVERHEAD -> buildOverhead(builder, network, segment, samples, steps,
					segment.wireHeight() > 0f ? segment.wireHeight() : ClientTrackData.config().catenaryHeight());
			case THIRD_RAIL -> buildThirdRail(builder, network, segment, samples, steps, gauge);
			case NONE, HIDDEN -> {
				// 非電化、または「非表示」(給電の対象だが何も描かない)
			}
		}

		// 枕木(上面と4側面)
		int sleepers = Math.max(1, (int) Math.floor(length / SLEEPER_SPACING));
		double offset = (length - (sleepers - 1) * SLEEPER_SPACING) / 2.0;
		for (int k = 0; k < sleepers; k++) {
			double s = offset + k * SLEEPER_SPACING;
			TrackPoint p = network.sample(segment, s);
			double fx = p.dirX() * SLEEPER_HALF_LENGTH;
			double fz = p.dirZ() * SLEEPER_HALF_LENGTH;
			double fy = p.grade() * SLEEPER_HALF_LENGTH;
			Vec3d lightAt = new Vec3d(p.x(), p.y(), p.z());
			Vec3d[] bottom = new Vec3d[4];
			Vec3d[] top = new Vec3d[4];
			double[][] corners = {{-sleeperHalfLat, -1}, {sleeperHalfLat, -1}, {sleeperHalfLat, 1}, {-sleeperHalfLat, 1}};
			for (int c = 0; c < 4; c++) {
				double lat = corners[c][0];
				double along = corners[c][1];
				bottom[c] = crossSection(p, lat, 0.0).add(fx * along, fy * along, fz * along);
				top[c] = crossSection(p, lat, SLEEPER_TOP).add(fx * along, fy * along, fz * along);
			}
			builder.quad(top[0], top[1], top[2], top[3], SLEEPER_UV, lightAt);
			for (int c = 0; c < 4; c++) {
				int n = (c + 1) % 4;
				builder.quad(bottom[n], bottom[c], top[c], top[n], SLEEPER_UV, lightAt);
			}
		}
		return builder.build(world.getTime());
	}

	public static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		if (world == null) {
			return;
		}
		TrackNetwork network = ClientTrackData.network();
		RailwayConfig.Values config = ClientTrackData.config();
		if (config.gauge() != cachedGauge) {
			cachedGauge = config.gauge();
			CACHE.clear();
		}
		Vec3d camera = context.worldState().cameraRenderState.pos;
		double chunkRange = client.options.getClampedViewDistance() * 16.0;
		double configured = config.trackRenderDistance();
		double radius = configured > 0.0 ? Math.min(configured, chunkRange) : chunkRange;
		long now = world.getTime();
		int rebuilds = 0;

		MatrixStack matrices = context.matrices();
		MatrixStack.Entry entry = matrices.peek();
		Matrix4f pose = entry.getPositionMatrix();
		VertexConsumer consumer = context.consumers().getBuffer(RenderLayers.entityCutoutNoCull(TEXTURE));
		BlockPos.Mutable lightPos = new BlockPos.Mutable();

		for (long id : network.segmentsNear(camera.x, camera.z, radius)) {
			TrackSegment segment = network.segment(id);
			if (segment == null) {
				continue;
			}
			// 転車台・遷車台の桁は、動作を滑らかに見せるためFeatureRendererが補間して描く
			if (network.featureOfSegment(id) instanceof MovingDeckFeature deck && deck.deckSegment() == id) {
				continue;
			}
			Mesh mesh = CACHE.get(id);
			if (mesh == null) {
				mesh = buildMesh(world, network, segment, cachedGauge);
				CACHE.put(id, mesh);
			} else if (rebuilds < MAX_REBUILDS_PER_FRAME) {
				long age = now - mesh.builtAt();
				boolean near = mesh.quads() > 0 && squaredHorizontalDistance(mesh, camera) < REBUILD_RANGE * REBUILD_RANGE;
				if (age < 0 || (mesh.incomplete() && age > INCOMPLETE_REBUILD_INTERVAL) || (near && age > REBUILD_INTERVAL)) {
					mesh = buildMesh(world, network, segment, cachedGauge);
					CACHE.put(id, mesh);
					rebuilds++;
				}
			}
			double[] v = mesh.verts();
			float[] n = mesh.normals();
			long lastLightKey = Long.MIN_VALUE;
			int light = 0;
			for (int q = 0; q < mesh.quads(); q++) {
				int base = q * 12;
				// 近いものだけ描く(区間単位のチャンク索引は粗いので四角形単位でも距離を見る)
				double dx = v[base] - camera.x;
				double dz = v[base + 2] - camera.z;
				if (dx * dx + dz * dz > radius * radius) {
					continue;
				}
				long key = mesh.lightPos()[q];
				if (key != lastLightKey) {
					lastLightKey = key;
					lightPos.set(key);
					light = WorldRenderer.getLightmapCoordinates(world, lightPos);
				}
				float[] uv = mesh.uv()[q];
				float nx = n[q * 3];
				float ny = n[q * 3 + 1];
				float nz = n[q * 3 + 2];
				for (int corner = 0; corner < 4; corner++) {
					int o = base + corner * 3;
					float u = (corner == 1 || corner == 2) ? uv[2] : uv[0];
					float t = (corner >= 2) ? uv[3] : uv[1];
					consumer.vertex(pose, (float) (v[o] - camera.x), (float) (v[o + 1] - camera.y), (float) (v[o + 2] - camera.z))
							.color(255, 255, 255, 255)
							.texture(u, t)
							.overlay(OverlayTexture.DEFAULT_UV)
							.light(light)
							.normal(entry, nx, ny, nz);
				}
			}
		}
	}

	private static double squaredHorizontalDistance(Mesh mesh, Vec3d camera) {
		double[] v = mesh.verts();
		double dx = v[0] - camera.x;
		double dz = v[2] - camera.z;
		return dx * dx + dz * dz;
	}
}
