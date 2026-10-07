package com.orehighlight;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public class OreHighlightClient implements ClientModInitializer {
	public static final String MOD_ID = "orehighlight";

	/** Glow strength: 0 = invisible, 1 = solid. */
	private static final float GLOW_ALPHA = 0.45f;
	/** Max boxes drawn per frame (nearest ones win). */
	private static final int MAX_BOXES = 4096;

	// Translucent filled boxes with NO depth test, so they show through every block.
	private static final RenderPipeline GLOW_THROUGH_WALLS = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
					.withLocation(id("pipeline/ore_glow_through_walls"))
					.withDepthStencilState(Optional.empty())
					.build());

	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
	// 1 MiB is ~2,700 boxes of POSITION_COLOR quads.
	private static final StagedVertexBuffer BUFFER = new StagedVertexBuffer(() -> "Ore Glow Buffer", 1 << 20);

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(id("main"));

	private final OreScanner scanner = new OreScanner();
	private boolean enabled = true;
	private List<OreScanner.Hit> frameHits = Collections.emptyList();
	private KeyMapping toggleKey;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.orehighlight.toggle",
				InputConstants.Type.KEYSYM,
				InputConstants.KEY_H,
				CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.consumeClick()) {
				enabled = !enabled;
				if (client.player != null) {
					client.player.sendOverlayMessage(Component.literal("Ore glow: " + (enabled ? "ON" : "OFF")));
				}
			}
			if (enabled) {
				scanner.tick(client);
			}
		});

		LevelExtractionEvents.END_EXTRACTION.register(this::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::render);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> BUFFER.close());
	}

	// --- Extraction phase: copy what we need into an immutable per-frame list ---
	private void extract(LevelExtractionContext context) {
		if (!enabled) {
			frameHits = Collections.emptyList();
			return;
		}
		List<OreScanner.Hit> hits = scanner.snapshot();
		Minecraft mc = Minecraft.getInstance();
		if (hits.size() > MAX_BOXES && mc.player != null) {
			double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
			hits.sort((a, b) -> Double.compare(dist2(a, px, py, pz), dist2(b, px, py, pz)));
			hits = hits.subList(0, MAX_BOXES);
		}
		frameHits = List.copyOf(hits);
	}

	private static double dist2(OreScanner.Hit h, double x, double y, double z) {
		double dx = h.x() + 0.5 - x, dy = h.y() + 0.5 - y, dz = h.z() + 0.5 - z;
		return dx * dx + dy * dy + dz * dz;
	}

	// --- Drawing phase ---
	private void render(LevelRenderContext context) {
		List<OreScanner.Hit> hits = frameHits;
		if (hits.isEmpty()) return;

		RenderPipeline pipeline = GLOW_THROUGH_WALLS;
		VertexFormat format = pipeline.getVertexFormatBinding(0);
		if (format == null) return;

		PrimitiveTopology topology = pipeline.getPrimitiveTopology();
		StagedVertexBuffer.Draw draw = BUFFER.appendDraw(format, topology,
				topology == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

		PoseStack poses = context.poseStack();
		Vec3 cam = context.levelState().cameraRenderState.pos;
		poses.pushPose();
		poses.translate(-cam.x, -cam.y, -cam.z);
		Matrix4fc pose = poses.last().pose();
		VertexConsumer vc = BUFFER.getVertexBuilder(draw);

		final float e = 0.002f; // tiny inflation so the glow sits just outside the block faces
		for (OreScanner.Hit h : hits) {
			box(pose, vc, h.x() - e, h.y() - e, h.z() - e, h.x() + 1 + e, h.y() + 1 + e, h.z() + 1 + e,
					h.r(), h.g(), h.b(), GLOW_ALPHA);
		}
		poses.popPose();

		BUFFER.upload();
		StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
		if (info != null) {
			submit(Minecraft.getInstance(), info, pipeline);
		}
		BUFFER.endFrame();
	}

	private static void submit(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
				.writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

		RenderTarget target = client.gameRenderer.mainRenderTarget();
		GpuTextureView color = target.getColorTextureView();
		if (color == null) return;

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> MOD_ID + " ore glow", color, Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.setVertexBuffer(0, info.vertexBuffer().slice());
			pass.setIndexBuffer(info.indexBuffer(), info.indexType());
			pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
		}
	}

	private static void box(Matrix4fc m, VertexConsumer b, float x0, float y0, float z0, float x1, float y1, float z1,
			float r, float g, float bl, float a) {
		// south (+Z)
		b.addVertex(m, x0, y0, z1).setColor(r, g, bl, a);
		b.addVertex(m, x1, y0, z1).setColor(r, g, bl, a);
		b.addVertex(m, x1, y1, z1).setColor(r, g, bl, a);
		b.addVertex(m, x0, y1, z1).setColor(r, g, bl, a);
		// north (-Z)
		b.addVertex(m, x1, y0, z0).setColor(r, g, bl, a);
		b.addVertex(m, x0, y0, z0).setColor(r, g, bl, a);
		b.addVertex(m, x0, y1, z0).setColor(r, g, bl, a);
		b.addVertex(m, x1, y1, z0).setColor(r, g, bl, a);
		// west (-X)
		b.addVertex(m, x0, y0, z0).setColor(r, g, bl, a);
		b.addVertex(m, x0, y0, z1).setColor(r, g, bl, a);
		b.addVertex(m, x0, y1, z1).setColor(r, g, bl, a);
		b.addVertex(m, x0, y1, z0).setColor(r, g, bl, a);
		// east (+X)
		b.addVertex(m, x1, y0, z1).setColor(r, g, bl, a);
		b.addVertex(m, x1, y0, z0).setColor(r, g, bl, a);
		b.addVertex(m, x1, y1, z0).setColor(r, g, bl, a);
		b.addVertex(m, x1, y1, z1).setColor(r, g, bl, a);
		// up (+Y)
		b.addVertex(m, x0, y1, z1).setColor(r, g, bl, a);
		b.addVertex(m, x1, y1, z1).setColor(r, g, bl, a);
		b.addVertex(m, x1, y1, z0).setColor(r, g, bl, a);
		b.addVertex(m, x0, y1, z0).setColor(r, g, bl, a);
		// down (-Y)
		b.addVertex(m, x0, y0, z0).setColor(r, g, bl, a);
		b.addVertex(m, x1, y0, z0).setColor(r, g, bl, a);
		b.addVertex(m, x1, y0, z1).setColor(r, g, bl, a);
		b.addVertex(m, x0, y0, z1).setColor(r, g, bl, a);
	}
}
