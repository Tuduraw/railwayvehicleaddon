package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.survey.SurveyInput;
import com.example.railwayvehicleaddon.survey.SurveyPoint;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * クライアント→サーバー: 測量ツールでの配置確定。モードIDと入力(点・環状・パラメータ)だけを送り、
 * サーバーは同じモードのplan()で配置計画を作り直して検証する(クライアントの計算結果は信用しない)。
 */
public record PlaceLayoutPayload(String modeId, SurveyInput input) implements CustomPayload {

	public static final CustomPayload.Id<PlaceLayoutPayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "place_layout"));

	/** 不正なクライアントが巨大なリストを送ってきた場合の上限(設定値の上限とは別の安全弁) */
	private static final int HARD_LIMIT = 1024;

	public static final PacketCodec<RegistryByteBuf, PlaceLayoutPayload> CODEC = PacketCodec.ofStatic(
			(buf, payload) -> {
				buf.writeString(payload.modeId());
				List<SurveyPoint> points = payload.input().points();
				buf.writeVarInt(points.size());
				for (SurveyPoint p : points) {
					buf.writeDouble(p.pos().x);
					buf.writeDouble(p.pos().y);
					buf.writeDouble(p.pos().z);
					buf.writeLong(p.nodeId());
					buf.writeLong(p.segmentId());
					buf.writeDouble(p.s());
				}
				buf.writeBoolean(payload.input().closed());
				buf.writeVarInt(payload.input().param());
			},
			buf -> {
				String modeId = buf.readString(64);
				int count = Math.min(buf.readVarInt(), HARD_LIMIT);
				List<SurveyPoint> points = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					points.add(new SurveyPoint(new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble()),
							buf.readLong(), buf.readLong(), buf.readDouble()));
				}
				boolean closed = buf.readBoolean();
				int param = buf.readVarInt();
				return new PlaceLayoutPayload(modeId, new SurveyInput(points, closed, param));
			});

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
