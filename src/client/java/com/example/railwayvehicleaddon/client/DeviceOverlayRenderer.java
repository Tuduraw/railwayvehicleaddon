package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.block.CrossingGateBlock;
import com.example.railwayvehicleaddon.block.SignalBlock;
import com.example.railwayvehicleaddon.block.SignalDeviceBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 信号機の灯火と、踏切の遮断かん・警報灯の描画。灯火は周囲の明るさに関係なく光って見えるよう
 * 最大の明るさで描く。遮断かんはブロックの状態(遮断中か)に向けてクライアント側で滑らかに動かす。
 *
 * <p>対象のブロックは、プレイヤーの周囲のチャンクのブロックエンティティから定期的に探す
 * (ブロックの追加・撤去を個別に追いかけなくて済むようにするため)。
 */
public final class DeviceOverlayRenderer {
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "textures/track/white.png");
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final int SCAN_INTERVAL = 20;
	private static final int SCAN_RADIUS_CHUNKS = 8;
	private static final double RENDER_DISTANCE = 128.0;
	/** 遮断かんの長さ・太さ(ブロック)と、開いたときの角度 */
	private static final double ARM_LENGTH = 4.0;
	private static final double ARM_HALF = 0.06;
	private static final float ARM_OPEN_DEG = 85f;
	/** 遮断かんの動く速さ(度/tick) */
	private static final float ARM_SPEED = 4.5f;

	private static final int GREEN = 0xFF30FF60;
	private static final int YELLOW = 0xFFFFC020;
	private static final int RED = 0xFFFF2A20;
	private static final int ARM_RED = 0xFFD02020;
	private static final int ARM_WHITE = 0xFFF0F0F0;

	private static final Set<BlockPos> DEVICES = new HashSet<>();
	private static final Map<BlockPos, Float> ARM_ANGLES = new HashMap<>();
	private static int scanTimer;

	private DeviceOverlayRenderer() {
	}

	/** 毎tick: 周囲のチャンクから信号機・踏切を探し直す(一定間隔)。 */
	public static void tick(Minecraft client) {
		ClientLevel world = client.level;
		if (world == null || client.player == null) {
			DEVICES.clear();
			ARM_ANGLES.clear();
			return;
		}
		if (--scanTimer > 0) {
			return;
		}
		scanTimer = SCAN_INTERVAL;
		DEVICES.clear();
		int pcx = client.player.blockPosition().getX() >> 4;
		int pcz = client.player.blockPosition().getZ() >> 4;
		for (int cx = pcx - SCAN_RADIUS_CHUNKS; cx <= pcx + SCAN_RADIUS_CHUNKS; cx++) {
			for (int cz = pcz - SCAN_RADIUS_CHUNKS; cz <= pcz + SCAN_RADIUS_CHUNKS; cz++) {
				if (!world.hasChunk(cx, cz)) {
					continue;
				}
				if (!(world.getChunk(cx, cz) instanceof LevelChunk chunk)) {
					continue;
				}
				for (BlockEntity entity : chunk.getBlockEntities().values()) {
					if (entity instanceof SignalDeviceBlockEntity) {
						DEVICES.add(entity.getBlockPos().immutable());
					}
				}
			}
		}
		ARM_ANGLES.keySet().retainAll(DEVICES);
	}

	public static void render(WorldRenderContext context) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel world = client.level;
		if (world == null || DEVICES.isEmpty()) {
			return;
		}
		Vec3 camera = context.worldState().cameraRenderState.pos;
		PoseStack.Pose entry = context.matrices().last();
		Matrix4f pose = entry.pose();
		VertexConsumer consumer = context.consumers().getBuffer(RenderTypes.entityCutoutNoCull(WHITE));
		float frameTicks = client.getDeltaTracker().getGameTimeDeltaTicks();
		long time = world.getGameTime();
		for (BlockPos pos : DEVICES) {
			if (Vec3.atCenterOf(pos).distanceToSqr(camera) > RENDER_DISTANCE * RENDER_DISTANCE) {
				continue;
			}
			BlockState state = world.getBlockState(pos);
			if (state.getBlock() instanceof SignalBlock) {
				renderSignal(consumer, entry, pose, camera, pos, state);
			} else if (state.getBlock() instanceof CrossingGateBlock) {
				renderGate(consumer, entry, pose, camera, world, pos, state, frameTicks, time);
			}
		}
	}

	/** ブロックの向きに合わせた座標: lx, lzは北向きのときのモデル座標(0〜1)。 */
	private static Vec3 local(BlockPos pos, Direction facing, double lx, double ly, double lz) {
		double fx = facing.getStepX();
		double fz = facing.getStepZ();
		// 北向きのときのモデルの+X(東)に当たる方向と、-Z(北=正面)に当たる方向
		double rx = -fz;
		double rz = fx;
		double ox = lx - 0.5;
		double oz = lz - 0.5;
		return new Vec3(pos.getX() + 0.5 + rx * ox - fx * oz, pos.getY() + ly, pos.getZ() + 0.5 + rz * ox - fz * oz);
	}

	private static void renderSignal(VertexConsumer consumer, PoseStack.Pose entry, Matrix4f pose, Vec3 camera,
									 BlockPos pos, BlockState state) {
		Direction facing = state.getValue(SignalBlock.FACING);
		int aspect = state.getValue(SignalBlock.ASPECT);
		// 灯火は上から緑・黄・赤
		double y = aspect == 0 ? 13.0 : aspect == 1 ? 9.0 : 5.0;
		int color = aspect == 0 ? GREEN : aspect == 1 ? YELLOW : RED;
		lamp(consumer, entry, pose, camera, pos, facing, 8.0, y, 5.0 - 0.1, 1.6, color);
	}

	private static void renderGate(VertexConsumer consumer, PoseStack.Pose entry, Matrix4f pose, Vec3 camera,
								   ClientLevel world, BlockPos pos, BlockState state, float frameTicks, long time) {
		Direction facing = state.getValue(CrossingGateBlock.FACING);
		boolean closed = state.getValue(CrossingGateBlock.POWERED);
		float target = closed ? 0f : ARM_OPEN_DEG;
		float angle = ARM_ANGLES.getOrDefault(pos, target);
		float step = ARM_SPEED * frameTicks;
		angle = Math.abs(target - angle) <= step ? target : angle + Math.copySign(step, target - angle);
		ARM_ANGLES.put(pos, angle);

		// 警報灯(遮断中は左右交互に点滅)
		if (closed) {
			boolean left = (time / 10) % 2 == 0;
			lamp(consumer, entry, pose, camera, pos, facing, left ? 13.0 : 4.0, 12.0, 7.0 - 0.1, 1.8, RED);
		}

		// 遮断かん: 支点から右側(北向きのとき東)へ伸び、開くと上へ回る
		Vec3 pivot = local(pos, facing, 10.5 / 16.0, 9.0 / 16.0, 8.0 / 16.0);
		double rx = -facing.getStepZ();
		double rz = facing.getStepX();
		double rad = Math.toRadians(angle);
		Vec3 dir = new Vec3(rx * Math.cos(rad), Math.sin(rad), rz * Math.cos(rad));
		// 遮断かんの断面の2軸(正面方向と、遮断かんに直交する上方向)
		Vec3 front = new Vec3(facing.getStepX(), 0.0, facing.getStepZ());
		Vec3 up = new Vec3(-rx * Math.sin(rad), Math.cos(rad), -rz * Math.sin(rad));
		int light = LevelRenderer.getLightColor(world, pos.above());
		int segments = 8;
		for (int i = 0; i < segments; i++) {
			Vec3 a = pivot.add(dir.scale(ARM_LENGTH * i / segments));
			Vec3 b = pivot.add(dir.scale(ARM_LENGTH * (i + 1) / segments));
			beam(consumer, entry, pose, camera, a, b, front, up, i % 2 == 0 ? ARM_RED : ARM_WHITE, light);
		}
	}

	/** 正面へ向いた四角い灯火(最大の明るさ)。size・座標はモデルの画素単位。 */
	private static void lamp(VertexConsumer consumer, PoseStack.Pose entry, Matrix4f pose, Vec3 camera,
							 BlockPos pos, Direction facing, double px, double py, double pz, double size, int color) {
		double h = size / 16.0;
		Vec3 c = local(pos, facing, px / 16.0, py / 16.0, pz / 16.0);
		double rx = -facing.getStepZ();
		double rz = facing.getStepX();
		Vec3[] v = {
				new Vec3(c.x - rx * h, c.y - h, c.z - rz * h),
				new Vec3(c.x + rx * h, c.y - h, c.z + rz * h),
				new Vec3(c.x + rx * h, c.y + h, c.z + rz * h),
				new Vec3(c.x - rx * h, c.y + h, c.z - rz * h)};
		quad(consumer, entry, pose, camera, v, color, FULL_BRIGHT, facing.getStepX(), 0f, facing.getStepZ());
	}

	/** aからbへの角柱(断面は2軸front・upの方向に±ARM_HALF)。 */
	private static void beam(VertexConsumer consumer, PoseStack.Pose entry, Matrix4f pose, Vec3 camera,
							 Vec3 a, Vec3 b, Vec3 front, Vec3 up, int color, int light) {
		Vec3 f = front.scale(ARM_HALF);
		Vec3 u = up.scale(ARM_HALF);
		Vec3[][] faces = {
				{a.add(f).subtract(u), b.add(f).subtract(u), b.add(f).add(u), a.add(f).add(u)},
				{a.subtract(f).add(u), b.subtract(f).add(u), b.subtract(f).subtract(u), a.subtract(f).subtract(u)},
				{a.add(f).add(u), b.add(f).add(u), b.subtract(f).add(u), a.subtract(f).add(u)},
				{a.subtract(f).subtract(u), b.subtract(f).subtract(u), b.add(f).subtract(u), a.add(f).subtract(u)}};
		Vec3[] normals = {front, front.reverse(), up, up.reverse()};
		for (int i = 0; i < faces.length; i++) {
			quad(consumer, entry, pose, camera, faces[i], color, light, (float) normals[i].x, (float) normals[i].y, (float) normals[i].z);
		}
	}

	private static void quad(VertexConsumer consumer, PoseStack.Pose entry, Matrix4f pose, Vec3 camera,
							 Vec3[] v, int color, int light, float nx, float ny, float nz) {
		int a = (color >>> 24) & 0xFF;
		int r = (color >>> 16) & 0xFF;
		int g = (color >>> 8) & 0xFF;
		int b = color & 0xFF;
		float[][] uv = {{0f, 0f}, {1f, 0f}, {1f, 1f}, {0f, 1f}};
		for (int i = 0; i < 4; i++) {
			consumer.addVertex(pose, (float) (v[i].x - camera.x), (float) (v[i].y - camera.y), (float) (v[i].z - camera.z))
					.setColor(r, g, b, a)
					.setUv(uv[i][0], uv[i][1])
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(light)
					.setNormal(entry, nx, ny, nz);
		}
	}
}
