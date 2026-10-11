package com.example.railwayvehicleaddon.client;

import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.network.BoardPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 長い鉄道車両に、車体のどこを右クリックしても乗れるようにする(クライアント)。
 * 前提MODの当たり判定は車両の中心付近の小さな箱だけなので、素手で使用キーを押した瞬間に、視線と車体全体の箱
 * (車体の長さ×幅×高さ、車体の向きに合わせて回した箱)の交点を求め、他のブロックより手前ならサーバーへ送る。
 * サーバーはその位置に最も近い空いた座席に座らせる(運転席が空いていて、そのそばを押したなら運転席)。
 */
public final class RailBoarding {
	private static boolean wasDown;

	private RailBoarding() {
	}

	public static void tick(MinecraftClient client) {
		boolean down = client.options.useKey.isPressed();
		boolean edge = down && !wasDown;
		wasDown = down;
		if (!edge || client.player == null || client.world == null || client.currentScreen != null
				|| client.player.hasVehicle() || !client.player.getMainHandStack().isEmpty() || client.player.isSneaking()) {
			return;
		}
		Vec3d eye = client.player.getCameraPosVec(1.0f);
		Vec3d dir = client.player.getRotationVec(1.0f);
		double reach = client.player.getEntityInteractionRange() + 1.0;
		// 手前にブロックがあればそちらが優先(壁越しには乗らない)
		double limit = reach;
		HitResult target = client.crosshairTarget;
		Entity targetEntity = null;
		if (target != null && target.getType() == HitResult.Type.BLOCK) {
			limit = Math.min(limit, target.getPos().distanceTo(eye));
		} else if (target instanceof EntityHitResult eh) {
			targetEntity = eh.getEntity();
		}
		RailVehicleEntity best = null;
		double bestT = Double.MAX_VALUE;
		for (Entity e : client.world.getEntities()) {
			if (!(e instanceof RailVehicleEntity car) || e.squaredDistanceTo(client.player) > 60.0 * 60.0) {
				continue;
			}
			double t = intersect(car, eye, dir);
			if (t >= 0.0 && t <= limit && t < bestT) {
				bestT = t;
				best = car;
			}
		}
		if (best == null) {
			return;
		}
		if (targetEntity != null && targetEntity != best && targetEntity.getEntityPos().distanceTo(eye) < bestT) {
			return;
		}
		Vec3d hit = eye.add(dir.multiply(bestT));
		ClientPlayNetworking.send(new BoardPayload(best.getId(), hit.x, hit.y, hit.z));
	}

	/** 視線と車体の箱の交点までの距離。当たらなければ-1。 */
	private static double intersect(RailVehicleEntity car, Vec3d eye, Vec3d dir) {
		float scale = car.getScale() > 0f ? car.getScale() : 1f;
		double hw = car.getDefinition().width() * scale / 2.0;
		double h = car.getDefinition().height() * scale;
		double[] ext = car.getBodyExtent();
		Quaternionf inv = new Quaternionf()
				.rotationY((float) Math.toRadians(-car.getYaw()))
				.rotateX((float) Math.toRadians(car.getPitch()))
				.conjugate();
		Vector3f o = new Vector3f((float) (eye.x - car.getX()), (float) (eye.y - car.getY()), (float) (eye.z - car.getZ()));
		Vector3f d = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
		inv.transform(o);
		inv.transform(d);
		double[] lo = {-hw, 0.0, ext[0]};
		double[] hi = {hw, h, ext[1]};
		double[] oo = {o.x, o.y, o.z};
		double[] dd = {d.x, d.y, d.z};
		double tmin = 0.0;
		double tmax = Double.MAX_VALUE;
		for (int a = 0; a < 3; a++) {
			if (Math.abs(dd[a]) < 1.0e-9) {
				if (oo[a] < lo[a] || oo[a] > hi[a]) {
					return -1.0;
				}
				continue;
			}
			double t1 = (lo[a] - oo[a]) / dd[a];
			double t2 = (hi[a] - oo[a]) / dd[a];
			tmin = Math.max(tmin, Math.min(t1, t2));
			tmax = Math.min(tmax, Math.max(t1, t2));
			if (tmin > tmax) {
				return -1.0;
			}
		}
		return tmin;
	}
}
