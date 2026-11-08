package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.survey.SurveyInput;
import com.example.railwayvehicleaddon.survey.SurveyPoint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * クライアント→サーバー: 測量ツールでの配置確定。モードIDと入力(点・環状・パラメータ)だけを送り、
 * サーバーは同じモードのplan()で配置計画を作り直して検証する(クライアントの計算結果は信用しない)。
 */
public record PlaceLayoutPayload(String modeId, SurveyInput input) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<PlaceLayoutPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(RailwayVehicleAddon.MOD_ID, "place_layout"));

	/** 不正なクライアントが巨大なリストを送ってきた場合の上限(設定値の上限とは別の安全弁) */
	private static final int HARD_LIMIT = 1024;

	public static final StreamCodec<RegistryFriendlyByteBuf, PlaceLayoutPayload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeUtf(payload.modeId());
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
				buf.writeVarInt(payload.input().ballast());
				buf.writeBoolean(payload.input().force());
				buf.writeVarInt(payload.input().electrification());
				buf.writeFloat(payload.input().wireHeight());
				buf.writeFloat(payload.input().minRadius());
			},
			buf -> {
				String modeId = buf.readUtf(64);
				int count = Math.min(buf.readVarInt(), HARD_LIMIT);
				List<SurveyPoint> points = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					points.add(new SurveyPoint(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
							buf.readLong(), buf.readLong(), buf.readDouble()));
				}
				boolean closed = buf.readBoolean();
				int param = buf.readVarInt();
				int ballast = buf.readVarInt();
				boolean force = buf.readBoolean();
				int electrification = buf.readVarInt();
				float wireHeight = buf.readFloat();
				float minRadius = buf.readFloat();
				return new PlaceLayoutPayload(modeId, new SurveyInput(points, closed, param, ballast, force, electrification, wireHeight, minRadius));
			});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
