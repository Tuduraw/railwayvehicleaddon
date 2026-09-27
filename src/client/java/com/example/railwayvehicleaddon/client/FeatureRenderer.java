package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.track.TrackNetwork;
import com.example.railwayvehicleaddon.track.TrackNode;
import com.example.railwayvehicleaddon.track.feature.BufferStopFeature;
import com.example.railwayvehicleaddon.track.feature.MovingDeckFeature;
import com.example.railwayvehicleaddon.track.feature.TrackFeature;
import com.example.railwayvehicleaddon.track.feature.TraverserFeature;
import com.example.railwayvehicleaddon.track.feature.TurntableFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 設備本体の描画(転車台・遷車台のピット、車止め)。可動桁そのものは線路区間として
 * TrackRendererが描く。設備の形は動かないので、同期で変化したときだけ作り直す。
 */
public final class FeatureRenderer {
	private static final Identifier TEXTURE = Identifier.of(RailwayVehicleAddon.MOD_ID, "textures/track/features.png");
	/** UV範囲: コンクリート / 鋼 / 赤 / 警戒色 */
	private static final float[] CONCRETE = {0.0f, 0.0f, 0.25f, 1.0f};
	private static final float[] STEEL = {0.25f, 0.0f, 0.5f, 1.0f};
	private static final float[] RED = {0.5f, 0.0f, 0.75f, 1.0f};
	private static final float[] STRIPE = {0.75f, 0.0f, 1.0f, 1.0f};
	private static final double MAX_DISTANCE = 192.0;

	private static final Map<Long, List<Quad>> CACHE = new HashMap<>();
	/** 桁のメッシュ(桁の座標系: u=A端からの距離、l=横方向、h=高さ)。桁の長さ・軌間が変わらない限り使い回す */
	private static final Map<Long, List<LocalQuad>> DECK_CACHE = new HashMap<>();
	private static float deckGauge = Float.NaN;
	private static final double DECK_DEPTH = 0.6;

	private record LocalQuad(double[][] v, float[] uv, double[] normal) {
	}

	private record Quad(Vec3d a, Vec3d b, Vec3d c, Vec3d d, float[] uv, Vec3d normal, BlockPos light) {
	}

	private FeatureRenderer() {
	}

	public static void invalidate(long id) {
		CACHE.remove(id);
		DECK_CACHE.remove(id);
	}

	public static void invalidateAll() {
		CACHE.clear();
		DECK_CACHE.clear();
	}

	// ------------------------------------------------------------------ 可動桁

	private static void localQuad(List<LocalQuad> out, double[] a, double[] b, double[] c, double[] d, float[] uv) {
		double[] e1 = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
		double[] e2 = {d[0] - a[0], d[1] - a[1], d[2] - a[2]};
		double[] n = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
		double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
		if (len < 1.0e-9) {
			return;
		}
		out.add(new LocalQuad(new double[][]{a, b, c, d}, uv, new double[]{n[0] / len, n[1] / len, n[2] / len}));
	}

	/** 桁の座標系の直方体(u0〜u1、l0〜l1、h0〜h1)。 */
	private static void localBox(List<LocalQuad> out, double u0, double u1, double l0, double l1, double h0, double h1, float[] uv) {
		double[][] b = {{u0, l0, h0}, {u1, l0, h0}, {u1, l1, h0}, {u0, l1, h0}};
		double[][] t = {{u0, l0, h1}, {u1, l0, h1}, {u1, l1, h1}, {u0, l1, h1}};
		localQuad(out, t[0], t[1], t[2], t[3], uv);
		localQuad(out, b[3], b[2], b[1], b[0], uv);
		for (int i = 0; i < 4; i++) {
			int n = (i + 1) % 4;
			localQuad(out, b[i], b[n], t[n], t[i], uv);
		}
	}

	/** 桁(レール・枕木・鋼製の桁)を桁の座標系で作る。 */
	private static List<LocalQuad> buildDeck(double length, float gauge) {
		List<LocalQuad> out = new ArrayList<>();
		double half = gauge / 2.0;
		double sleeperHalfLat = half + TrackRenderer.SLEEPER_OVERHANG;
		// 桁本体
		localBox(out, 0.0, length, -sleeperHalfLat + 0.2, sleeperHalfLat - 0.2, -DECK_DEPTH, -0.001, TrackRenderer.RAIL_UV);
		// 枕木
		int sleepers = Math.max(1, (int) Math.floor(length / TrackRenderer.SLEEPER_SPACING));
		double offset = (length - (sleepers - 1) * TrackRenderer.SLEEPER_SPACING) / 2.0;
		for (int k = 0; k < sleepers; k++) {
			double u = offset + k * TrackRenderer.SLEEPER_SPACING;
			localBox(out, u - TrackRenderer.SLEEPER_HALF_LENGTH, u + TrackRenderer.SLEEPER_HALF_LENGTH,
					-sleeperHalfLat, sleeperHalfLat, 0.0, TrackRenderer.SLEEPER_TOP, TrackRenderer.SLEEPER_UV);
		}
		// レール
		for (double side : new double[]{-half, half}) {
			localBox(out, 0.0, length, side - TrackRenderer.RAIL_HALF_WIDTH, side + TrackRenderer.RAIL_HALF_WIDTH,
					TrackRenderer.SLEEPER_TOP, RailVehicleEntity.RAIL_TOP, TrackRenderer.RAIL_UV);
		}
		return out;
	}

	private static void renderDecks(WorldRenderContext context, TrackNetwork network, ClientWorld world, Vec3d camera, float tickDelta) {
		float gauge = ClientTrackData.config().gauge();
		if (gauge != deckGauge) {
			deckGauge = gauge;
			DECK_CACHE.clear();
		}
		MatrixStack.Entry entry = context.matrices().peek();
		Matrix4f pose = entry.getPositionMatrix();
		VertexConsumer consumer = null;
		for (TrackFeature feature : network.features()) {
			if (!(feature instanceof MovingDeckFeature deck)) {
				continue;
			}
			double reach = MAX_DISTANCE + feature.radius();
			if (feature.center().squaredDistanceTo(camera) > reach * reach) {
				continue;
			}
			double param = ClientTrackData.deckRenderParam(deck, tickDelta);
			Vec3d a = deck.endA(param);
			Vec3d b = deck.endB(param);
			double length = Math.hypot(b.x - a.x, b.z - a.z);
			if (length < 1.0e-6) {
				continue;
			}
			// 桁の座標系: u = A→B方向、l = 線路の横方向(TrackPoint.lateralと同じ向き)、h = 上
			double dx = (b.x - a.x) / length;
			double dz = (b.z - a.z) / length;
			double lx = dz;
			double lz = -dx;
			List<LocalQuad> quads = DECK_CACHE.computeIfAbsent(deck.id(), id -> buildDeck(length, gauge));
			if (consumer == null) {
				consumer = context.consumers().getBuffer(RenderLayers.entityCutoutNoCull(TrackRenderer.TEXTURE));
			}
			int light = WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(feature.center().x, feature.center().y + 0.5, feature.center().z));
			for (LocalQuad q : quads) {
				// (u, l, h)の基底(A→B方向・横方向・上)は右手系なので、法線もそのまま世界座標へ移せる
				double nu = q.normal()[0];
				double nl = q.normal()[1];
				double nh = q.normal()[2];
				float nx = (float) (dx * nu + lx * nl);
				float ny = (float) nh;
				float nz = (float) (dz * nu + lz * nl);
				for (int corner = 0; corner < 4; corner++) {
					double[] v = q.v()[corner];
					double wx = a.x + dx * v[0] + lx * v[1];
					double wy = a.y + v[2];
					double wz = a.z + dz * v[0] + lz * v[1];
					float u = (corner == 1 || corner == 2) ? q.uv()[2] : q.uv()[0];
					float t = (corner >= 2) ? q.uv()[3] : q.uv()[1];
					consumer.vertex(pose, (float) (wx - camera.x), (float) (wy - camera.y), (float) (wz - camera.z))
							.color(255, 255, 255, 255)
							.texture(u, t)
							.overlay(OverlayTexture.DEFAULT_UV)
							.light(light)
							.normal(entry, nx, ny, nz);
				}
			}
		}
	}

	private static void quad(List<Quad> out, Vec3d a, Vec3d b, Vec3d c, Vec3d d, float[] uv) {
		Vec3d n = b.subtract(a).crossProduct(d.subtract(a));
		double len = n.length();
		if (len < 1.0e-9) {
			n = c.subtract(b).crossProduct(a.subtract(b));
			len = n.length();
			if (len < 1.0e-9) {
				return;
			}
		}
		Vec3d center = a.add(c).multiply(0.5);
		out.add(new Quad(a, b, c, d, uv, n.multiply(1.0 / len), BlockPos.ofFloored(center.x, center.y + 0.3, center.z)));
	}

	/** 中心(cx, y, cz)・向きの軸(ux, uz)と(vx, vz)で張る直方体。 */
	private static void box(List<Quad> out, Vec3d c, double ux, double uz, double hu, double vx, double vz, double hv,
							double y0, double y1, float[] uv) {
		Vec3d[] bottom = new Vec3d[4];
		Vec3d[] top = new Vec3d[4];
		double[][] k = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
		for (int i = 0; i < 4; i++) {
			double x = c.x + ux * hu * k[i][0] + vx * hv * k[i][1];
			double z = c.z + uz * hu * k[i][0] + vz * hv * k[i][1];
			bottom[i] = new Vec3d(x, y0, z);
			top[i] = new Vec3d(x, y1, z);
		}
		quad(out, top[3], top[2], top[1], top[0], uv);
		for (int i = 0; i < 4; i++) {
			int n = (i + 1) % 4;
			quad(out, bottom[i], bottom[n], top[n], top[i], uv);
		}
	}

	private static List<Quad> build(TrackNetwork network, TrackFeature feature) {
		List<Quad> out = new ArrayList<>();
		if (feature instanceof TurntableFeature tt) {
			Vec3d c = tt.center();
			double r = tt.radius();
			int n = 48;
			for (int i = 0; i < n; i++) {
				double a0 = Math.PI * 2.0 * i / n;
				double a1 = Math.PI * 2.0 * (i + 1) / n;
				Vec3d p0 = new Vec3d(c.x + Math.cos(a0) * r, c.y - 0.02, c.z + Math.sin(a0) * r);
				Vec3d p1 = new Vec3d(c.x + Math.cos(a1) * r, c.y - 0.02, c.z + Math.sin(a1) * r);
				Vec3d center = new Vec3d(c.x, c.y - 0.02, c.z);
				// ピットの床(扇形を四角形で近似)
				quad(out, center, p1, p0, p0, CONCRETE);
				// 床の縁の周回レール
				double ri = r - 0.35;
				double ro = r - 0.2;
				Vec3d i0 = new Vec3d(c.x + Math.cos(a0) * ri, c.y + 0.03, c.z + Math.sin(a0) * ri);
				Vec3d i1 = new Vec3d(c.x + Math.cos(a1) * ri, c.y + 0.03, c.z + Math.sin(a1) * ri);
				Vec3d o0 = new Vec3d(c.x + Math.cos(a0) * ro, c.y + 0.03, c.z + Math.sin(a0) * ro);
				Vec3d o1 = new Vec3d(c.x + Math.cos(a1) * ro, c.y + 0.03, c.z + Math.sin(a1) * ro);
				quad(out, i0, i1, o1, o0, STEEL);
			}
			// 中心の軸受
			box(out, c, 1, 0, 0.4, 0, 1, 0.4, c.y - 0.02, c.y + 0.05, STEEL);
		} else if (feature instanceof TraverserFeature tr) {
			Vec3d a = tr.baseA();
			Vec3d b = tr.baseB();
			double min = tr.minParam() - 1.0;
			double max = tr.maxParam() + 1.0;
			double ax = tr.axisX();
			double az = tr.axisZ();
			double y = tr.y() - 0.02;
			Vec3d c0 = new Vec3d(a.x + ax * min, y, a.z + az * min);
			Vec3d c1 = new Vec3d(b.x + ax * min, y, b.z + az * min);
			Vec3d c2 = new Vec3d(b.x + ax * max, y, b.z + az * max);
			Vec3d c3 = new Vec3d(a.x + ax * max, y, a.z + az * max);
			quad(out, c0, c3, c2, c1, CONCRETE);
			// 桁を載せて走る横行レール(桁の両端寄り2本)
			for (double t : new double[]{0.15, 0.85}) {
				Vec3d base = a.add(b.subtract(a).multiply(t));
				double dx = b.x - a.x;
				double dz = b.z - a.z;
				double len = Math.hypot(dx, dz);
				Vec3d mid = base.add(ax * (min + max) / 2.0, 0.0, az * (min + max) / 2.0);
				box(out, mid, ax, az, (max - min) / 2.0, dx / len, dz / len, 0.06, y, y + 0.08, STEEL);
			}
		} else if (feature instanceof BufferStopFeature bs) {
			TrackNode node = network.node(bs.node());
			double[] outward = network.outwardDirection(bs.node());
			if (node == null || outward == null) {
				return out;
			}
			double fx = outward[0];
			double fz = outward[1];
			double lx = fz;
			double lz = -fx;
			double y = node.y();
			// 端から少し内側に、柱2本と赤い緩衝梁
			Vec3d c = new Vec3d(node.x() - fx * 0.3, y, node.z() - fz * 0.3);
			for (double side : new double[]{-0.6, 0.6}) {
				Vec3d post = c.add(lx * side, 0.0, lz * side);
				box(out, post, fx, fz, 0.12, lx, lz, 0.12, y, y + 1.1, STEEL);
			}
			Vec3d beam = c.add(-fx * 0.15, 0.0, -fz * 0.15);
			box(out, beam, fx, fz, 0.1, lx, lz, 0.95, y + 0.55, y + 0.9, RED);
			// 梁の正面の警戒色
			Vec3d face = beam.add(-fx * 0.11, 0.0, -fz * 0.11);
			quad(out, new Vec3d(face.x - lx * 0.9, y + 0.6, face.z - lz * 0.9), new Vec3d(face.x + lx * 0.9, y + 0.6, face.z + lz * 0.9),
					new Vec3d(face.x + lx * 0.9, y + 0.85, face.z + lz * 0.9), new Vec3d(face.x - lx * 0.9, y + 0.85, face.z - lz * 0.9), STRIPE);
		}
		return out;
	}

	public static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		if (world == null) {
			return;
		}
		TrackNetwork network = ClientTrackData.network();
		if (network.features().isEmpty()) {
			return;
		}
		Vec3d camera = context.worldState().cameraRenderState.pos;
		// 桁(線路のテクスチャ)を先に描き、そのあと設備本体(設備のテクスチャ)を描く
		renderDecks(context, network, world, camera, client.getRenderTickCounter().getTickProgress(true));
		MatrixStack.Entry entry = context.matrices().peek();
		Matrix4f pose = entry.getPositionMatrix();
		VertexConsumer consumer = context.consumers().getBuffer(RenderLayers.entityCutoutNoCull(TEXTURE));
		for (TrackFeature feature : network.features()) {
			Vec3d c = feature.center();
			double reach = MAX_DISTANCE + feature.radius();
			if (c.squaredDistanceTo(camera) > reach * reach) {
				continue;
			}
			List<Quad> quads = CACHE.computeIfAbsent(feature.id(), id -> build(network, feature));
			for (Quad q : quads) {
				int light = WorldRenderer.getLightmapCoordinates(world, q.light());
				Vec3d[] v = {q.a(), q.b(), q.c(), q.d()};
				for (int corner = 0; corner < 4; corner++) {
					float u = (corner == 1 || corner == 2) ? q.uv()[2] : q.uv()[0];
					float t = (corner >= 2) ? q.uv()[3] : q.uv()[1];
					consumer.vertex(pose, (float) (v[corner].x - camera.x), (float) (v[corner].y - camera.y), (float) (v[corner].z - camera.z))
							.color(255, 255, 255, 255)
							.texture(u, t)
							.overlay(OverlayTexture.DEFAULT_UV)
							.light(light)
							.normal(entry, (float) q.normal().x, (float) q.normal().y, (float) q.normal().z);
				}
			}
		}
	}
}
