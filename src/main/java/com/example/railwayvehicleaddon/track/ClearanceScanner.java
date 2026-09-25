package com.example.railwayvehicleaddon.track;

import com.example.railwayvehicleaddon.RailwayConfig;
import com.example.railwayvehicleaddon.RailwayVehicleAddon;
import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 線路の建築限界(線路中心から左右clearance_half_width、線路基面からclearance_heightまで)に
 * 入るブロックを列挙し、分類する。
 *
 * <p>分類の優先順:
 * <ol>
 *   <li>空気 → 対象外</li>
 *   <li>流体を含む(水・溶岩・水没ブロック) → BLOCKED_FLUID(敷設不可)</li>
 *   <li>硬度が負(岩盤等)または unbreakable_hardness 以上 → BLOCKED_HARD(敷設不可)</li>
 *   <li>タグ railwayvehicleaddon:natural_terrain に含まれる、または置換可能(草・雪等) → NATURAL</li>
 *   <li>それ以外 → PLAYER_PLACED(警告のみ、敷設可)</li>
 * </ol>
 * 自然地形の判定をデータタグにしているのは、利用者がデータパックで調整できるようにするため。
 */
public final class ClearanceScanner {
	public static final TagKey<net.minecraft.block.Block> NATURAL_TERRAIN =
			TagKey.of(RegistryKeys.BLOCK, Identifier.of(RailwayVehicleAddon.MOD_ID, "natural_terrain"));

	private static final double ALONG_STEP = 0.5;
	private static final double CROSS_STEP = 0.5;
	/** 基面ちょうどの位置は地面ブロックの上面なので、わずかに上から判定を始める */
	private static final double BASE_EPSILON = 0.01;

	private ClearanceScanner() {
	}

	/** 建築限界に入るブロック位置(重複なし、線路に沿った順)。 */
	public static Set<BlockPos> clearanceBlocks(List<TrackSegment> segments, RailwayConfig.Values config) {
		Set<BlockPos> result = new LinkedHashSet<>();
		double halfWidth = Math.max(0.0, config.clearanceHalfWidth());
		double height = Math.max(0.5, config.clearanceHeight());
		for (TrackSegment segment : segments) {
			double length = segment.length();
			int alongSteps = Math.max(1, (int) Math.ceil(length / ALONG_STEP));
			int crossSteps = Math.max(1, (int) Math.ceil(2.0 * halfWidth / CROSS_STEP));
			int upSteps = Math.max(1, (int) Math.ceil(height / CROSS_STEP));
			for (int i = 0; i <= alongSteps; i++) {
				TrackPoint p = segment.sample(length * i / alongSteps);
				for (int c = 0; c <= crossSteps; c++) {
					// 両端はわずかに内側へ寄せ、ちょうど境界上の隣接ブロックを拾わないようにする
					double lateral = -halfWidth + (2.0 * halfWidth) * c / crossSteps;
					lateral = Math.max(-halfWidth + BASE_EPSILON, Math.min(halfWidth - BASE_EPSILON, lateral));
					double x = p.x() + p.lateralX() * lateral;
					double z = p.z() + p.lateralZ() * lateral;
					for (int u = 0; u <= upSteps; u++) {
						double up = BASE_EPSILON + (height - 2.0 * BASE_EPSILON) * u / upSteps;
						result.add(BlockPos.ofFloored(x, p.y() + up, z));
					}
				}
			}
		}
		return result;
	}

	public static BlockCategory classify(World world, BlockPos pos, RailwayConfig.Values config, boolean treatUnloadedAsBlocked) {
		if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
			return treatUnloadedAsBlocked ? BlockCategory.UNLOADED : null;
		}
		BlockState state = world.getBlockState(pos);
		if (state.isAir()) {
			return null;
		}
		if (!state.getFluidState().isEmpty()) {
			return BlockCategory.BLOCKED_FLUID;
		}
		float hardness = state.getHardness(world, pos);
		if (hardness < 0.0f || hardness >= config.unbreakableHardness()) {
			return BlockCategory.BLOCKED_HARD;
		}
		if (state.isIn(NATURAL_TERRAIN) || state.isReplaceable()) {
			return BlockCategory.NATURAL;
		}
		return BlockCategory.PLAYER_PLACED;
	}

	/** 建築限界を走査した結果。 */
	public record ScanResult(Map<BlockPos, BlockCategory> blocks, Map<BlockCategory, Integer> counts) {
		public boolean hasBlocking() {
			for (Map.Entry<BlockCategory, Integer> e : this.counts.entrySet()) {
				if (e.getKey().blocksPlacement() && e.getValue() > 0) {
					return true;
				}
			}
			return false;
		}

		public int count(BlockCategory category) {
			return this.counts.getOrDefault(category, 0);
		}
	}

	public static ScanResult scan(World world, List<TrackSegment> segments, RailwayConfig.Values config, boolean treatUnloadedAsBlocked) {
		return scan(world, segments, Set.of(), config, treatUnloadedAsBlocked);
	}

	/** 区間の建築限界に加えて、extra(転車台のピットなど)の範囲も判定する。 */
	public static ScanResult scan(World world, List<TrackSegment> segments, Set<BlockPos> extra, RailwayConfig.Values config,
								  boolean treatUnloadedAsBlocked) {
		Map<BlockPos, BlockCategory> blocks = new LinkedHashMap<>();
		Map<BlockCategory, Integer> counts = new EnumMap<>(BlockCategory.class);
		Set<BlockPos> positions = clearanceBlocks(segments, config);
		positions.addAll(extra);
		for (BlockPos pos : positions) {
			BlockCategory category = classify(world, pos, config, treatUnloadedAsBlocked);
			if (category != null) {
				blocks.put(pos, category);
				counts.merge(category, 1, Integer::sum);
			}
		}
		return new ScanResult(blocks, counts);
	}
}
