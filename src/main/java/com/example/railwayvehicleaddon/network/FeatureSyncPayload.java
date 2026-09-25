package com.example.railwayvehicleaddon.network;

import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import com.example.railwayvehicleaddon.track.feature.FeatureTypes;
import com.example.railwayvehicleaddon.track.feature.TrackFeature;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー→クライアント: 設備(転車台・遷車台・車止めなど)の追加・更新・削除。
 * 設備は種類ごとに形が違うため、保存と同じコーデック(FeatureTypes.CODEC)でNBTにして送る。
 * 転車台・遷車台が動いている間は、状態が変わるたびに送られる。
 */
public record FeatureSyncPayload(boolean reset, List<TrackFeature> features, List<Long> removedIds) implements CustomPayload {

	public static final CustomPayload.Id<FeatureSyncPayload> ID =
			new CustomPayload.Id<>(Identifier.of(RailwayVehicleAddon.MOD_ID, "feature_sync"));

	public static final PacketCodec<RegistryByteBuf, FeatureSyncPayload> CODEC = PacketCodec.ofStatic(
			(buf, payload) -> {
				buf.writeBoolean(payload.reset());
				List<NbtElement> encoded = new ArrayList<>();
				for (TrackFeature feature : payload.features()) {
					FeatureTypes.CODEC.encodeStart(NbtOps.INSTANCE, feature).result().ifPresent(encoded::add);
				}
				buf.writeVarInt(encoded.size());
				for (NbtElement element : encoded) {
					buf.writeNbt(element);
				}
				buf.writeVarInt(payload.removedIds().size());
				payload.removedIds().forEach(buf::writeLong);
			},
			buf -> {
				boolean reset = buf.readBoolean();
				int count = buf.readVarInt();
				List<TrackFeature> features = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					NbtCompound nbt = buf.readNbt();
					if (nbt != null) {
						FeatureTypes.CODEC.parse(NbtOps.INSTANCE, nbt).result().ifPresent(features::add);
					}
				}
				int removedCount = buf.readVarInt();
				List<Long> removed = new ArrayList<>(removedCount);
				for (int i = 0; i < removedCount; i++) {
					removed.add(buf.readLong());
				}
				return new FeatureSyncPayload(reset, features, removed);
			});

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
