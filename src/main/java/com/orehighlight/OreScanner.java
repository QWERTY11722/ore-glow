package com.orehighlight;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Scans loaded chunks around the player for the target ores and keeps only the
 * ones that have at least one air block (air, cave air, void air) next to them.
 *
 * Work is spread across ticks: a few chunks are scanned per tick, and once every
 * chunk in range has been visited the sweep starts again, so mining or placing
 * blocks is picked up within about a second.
 */
public final class OreScanner {
	/** Horizontal scan radius in chunks (6 chunks = 96 blocks). */
	public static final int RADIUS_CHUNKS = 6;
	/** How many chunks to scan per client tick. */
	private static final int CHUNKS_PER_TICK = 8;

	/** A highlighted ore: block position plus an ARGB-ish color as floats. */
	public record Hit(int x, int y, int z, float r, float g, float b) { }

	private final Map<Long, List<Hit>> hitsByChunk = new HashMap<>();
	private final ArrayDeque<long[]> queue = new ArrayDeque<>();
	private ClientLevel lastLevel;

	public void clear() {
		hitsByChunk.clear();
		queue.clear();
	}

	public void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null || client.player == null) {
			if (lastLevel != null) clear();
			lastLevel = null;
			return;
		}
		if (level != lastLevel) { // dimension change or new world
			clear();
			lastLevel = level;
		}

		int pcx = client.player.getBlockX() >> 4;
		int pcz = client.player.getBlockZ() >> 4;

		if (queue.isEmpty()) {
			refillQueue(pcx, pcz);
			dropFarChunks(pcx, pcz);
		}

		for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
			long[] c = queue.poll();
			scanChunk(level, (int) c[0], (int) c[1]);
		}
	}

	/** Snapshot of all current hits (called once per frame by the renderer). */
	public List<Hit> snapshot() {
		if (hitsByChunk.isEmpty()) return Collections.emptyList();
		List<Hit> out = new ArrayList<>();
		for (List<Hit> l : hitsByChunk.values()) out.addAll(l);
		return out;
	}

	private void refillQueue(int pcx, int pcz) {
		// Nearest chunks first, so the area around you updates fastest.
		List<long[]> list = new ArrayList<>();
		for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
			for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
				list.add(new long[] {pcx + dx, pcz + dz, (long) dx * dx + (long) dz * dz});
			}
		}
		list.sort((a, b) -> Long.compare(a[2], b[2]));
		queue.addAll(list);
	}

	private void dropFarChunks(int pcx, int pcz) {
		Iterator<Long> it = hitsByChunk.keySet().iterator();
		while (it.hasNext()) {
			long key = it.next();
			int cx = (int) (key >> 32);
			int cz = (int) key;
			if (Math.abs(cx - pcx) > RADIUS_CHUNKS + 1 || Math.abs(cz - pcz) > RADIUS_CHUNKS + 1) {
				it.remove();
			}
		}
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	private static boolean isTarget(BlockState state) {
		return colorFor(state.getBlock()) != null;
	}

	/** Returns {r, g, b} for a target ore, or null if the block isn't one we highlight. */
	private static float[] colorFor(Block block) {
		if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
			return new float[] {0.25f, 0.95f, 1.0f};   // cyan
		}
		if (block == Blocks.DEEPSLATE_EMERALD_ORE) {
			return new float[] {0.15f, 1.0f, 0.35f};   // green
		}
		if (block == Blocks.DEEPSLATE_COAL_ORE) {
			return new float[] {0.55f, 0.55f, 0.55f};  // grey
		}
		return null;
	}

	private void scanChunk(ClientLevel level, int cx, int cz) {
		long k = key(cx, cz);
		ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
		if (chunk == null) {
			hitsByChunk.remove(k);
			return;
		}

		List<Hit> hits = new ArrayList<>();
		BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
		LevelChunkSection[] sections = chunk.getSections();
		int baseX = cx << 4;
		int baseZ = cz << 4;

		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			// Cheap palette check: skip sections that can't contain any target ore.
			if (section == null || section.hasOnlyAir() || !section.maybeHas(OreScanner::isTarget)) {
				continue;
			}
			int baseY = chunk.getSectionYFromSectionIndex(i) << 4;

			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						float[] color = colorFor(state.getBlock());
						if (color == null) continue;

						int wx = baseX + x, wy = baseY + y, wz = baseZ + z;
						if (touchesAir(level, neighbor, wx, wy, wz)) {
							hits.add(new Hit(wx, wy, wz, color[0], color[1], color[2]));
						}
					}
				}
			}
		}

		if (hits.isEmpty()) hitsByChunk.remove(k);
		else hitsByChunk.put(k, hits);
	}

	private static boolean touchesAir(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
		for (Direction d : Direction.values()) {
			int nx = x + d.getStepX(), nz = z + d.getStepZ();
			// Unloaded chunks read as air on the client; don't count those.
			if (!level.hasChunk(nx >> 4, nz >> 4)) continue;
			pos.set(nx, y + d.getStepY(), nz);
			if (level.getBlockState(pos).isAir()) return true;
		}
		return false;
	}
}
