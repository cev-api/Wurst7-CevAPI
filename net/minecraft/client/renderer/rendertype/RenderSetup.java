package net.minecraft.client.renderer.rendertype;

import com.google.common.base.Suppliers;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class RenderSetup {
	final RenderPipeline pipeline;
	final Map<String, RenderSetup.TextureBinding> textures;
	final TextureTransform textureTransform;
	final OutputTarget outputTarget;
	final RenderSetup.OutlineProperty outlineProperty;
	final boolean useLightmap;
	final boolean useOverlay;
	final boolean affectsCrumbling;
	final boolean sortOnUpload;
	final int bufferSize;
	final LayeringTransform layeringTransform;

	RenderSetup(
		RenderPipeline renderPipeline,
		Map<String, RenderSetup.TextureBinding> map,
		boolean bl,
		boolean bl2,
		LayeringTransform layeringTransform,
		OutputTarget outputTarget,
		TextureTransform textureTransform,
		RenderSetup.OutlineProperty outlineProperty,
		boolean bl3,
		boolean bl4,
		int i
	) {
		this.pipeline = renderPipeline;
		this.textures = map;
		this.outputTarget = outputTarget;
		this.textureTransform = textureTransform;
		this.useLightmap = bl;
		this.useOverlay = bl2;
		this.outlineProperty = outlineProperty;
		this.layeringTransform = layeringTransform;
		this.affectsCrumbling = bl3;
		this.sortOnUpload = bl4;
		this.bufferSize = i;
	}

	public String toString() {
		return "RenderSetup[layeringTransform="
			+ this.layeringTransform
			+ ", textureTransform="
			+ this.textureTransform
			+ ", textures="
			+ this.textures
			+ ", outlineProperty="
			+ this.outlineProperty
			+ ", useLightmap="
			+ this.useLightmap
			+ ", useOverlay="
			+ this.useOverlay
			+ "]";
	}

	public static RenderSetup.RenderSetupBuilder builder(RenderPipeline renderPipeline) {
		return new RenderSetup.RenderSetupBuilder(renderPipeline);
	}

	public Map<String, RenderSetup.TextureAndSampler> getTextures() {
		if (this.textures.isEmpty() && !this.useOverlay && !this.useLightmap) {
			return Collections.emptyMap();
		} else {
			Map<String, RenderSetup.TextureAndSampler> map = new HashMap();
			if (this.useOverlay) {
				map.put(
					"Sampler1",
					new RenderSetup.TextureAndSampler(
						Minecraft.getInstance().gameRenderer.overlayTexture().getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
					)
				);
			}

			if (this.useLightmap) {
				map.put(
					"Sampler2",
					new RenderSetup.TextureAndSampler(
						Minecraft.getInstance().gameRenderer.lightTexture().getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
					)
				);
			}

			TextureManager textureManager = Minecraft.getInstance().getTextureManager();

			for (Entry<String, RenderSetup.TextureBinding> entry : this.textures.entrySet()) {
				AbstractTexture abstractTexture = textureManager.getTexture(((RenderSetup.TextureBinding)entry.getValue()).location);
				GpuSampler gpuSampler = (GpuSampler)((RenderSetup.TextureBinding)entry.getValue()).sampler().get();
				map.put(
					(String)entry.getKey(),
					new RenderSetup.TextureAndSampler(abstractTexture.getTextureView(), gpuSampler != null ? gpuSampler : abstractTexture.getSampler())
				);
			}

			return map;
		}
	}

	@Environment(EnvType.CLIENT)
	public static enum OutlineProperty {
		NONE("none"),
		IS_OUTLINE("is_outline"),
		AFFECTS_OUTLINE("affects_outline");

		private final String name;

		private OutlineProperty(final String string2) {
			this.name = string2;
		}

		public String toString() {
			return this.name;
		}
	}

	@Environment(EnvType.CLIENT)
	public static class RenderSetupBuilder {
		private final RenderPipeline pipeline;
		private boolean useLightmap = false;
		private boolean useOverlay = false;
		private LayeringTransform layeringTransform = LayeringTransform.NO_LAYERING;
		private OutputTarget outputTarget = OutputTarget.MAIN_TARGET;
		private TextureTransform textureTransform = TextureTransform.DEFAULT_TEXTURING;
		private boolean affectsCrumbling = false;
		private boolean sortOnUpload = false;
		private int bufferSize = 1536;
		private RenderSetup.OutlineProperty outlineProperty = RenderSetup.OutlineProperty.NONE;
		private final Map<String, RenderSetup.TextureBinding> textures = new HashMap();

		RenderSetupBuilder(RenderPipeline renderPipeline) {
			this.pipeline = renderPipeline;
		}

		public RenderSetup.RenderSetupBuilder withTexture(String string, Identifier identifier) {
			this.textures.put(string, new RenderSetup.TextureBinding(identifier, () -> null));
			return this;
		}

		public RenderSetup.RenderSetupBuilder withTexture(String string, Identifier identifier, @Nullable Supplier<GpuSampler> supplier) {
			this.textures.put(string, new RenderSetup.TextureBinding(identifier, Suppliers.memoize(() -> supplier == null ? null : (GpuSampler)supplier.get())));
			return this;
		}

		public RenderSetup.RenderSetupBuilder useLightmap() {
			this.useLightmap = true;
			return this;
		}

		public RenderSetup.RenderSetupBuilder useOverlay() {
			this.useOverlay = true;
			return this;
		}

		public RenderSetup.RenderSetupBuilder affectsCrumbling() {
			this.affectsCrumbling = true;
			return this;
		}

		public RenderSetup.RenderSetupBuilder sortOnUpload() {
			this.sortOnUpload = true;
			return this;
		}

		public RenderSetup.RenderSetupBuilder bufferSize(int i) {
			this.bufferSize = i;
			return this;
		}

		public RenderSetup.RenderSetupBuilder setLayeringTransform(LayeringTransform layeringTransform) {
			this.layeringTransform = layeringTransform;
			return this;
		}

		public RenderSetup.RenderSetupBuilder setOutputTarget(OutputTarget outputTarget) {
			this.outputTarget = outputTarget;
			return this;
		}

		public RenderSetup.RenderSetupBuilder setTextureTransform(TextureTransform textureTransform) {
			this.textureTransform = textureTransform;
			return this;
		}

		public RenderSetup.RenderSetupBuilder setOutline(RenderSetup.OutlineProperty outlineProperty) {
			this.outlineProperty = outlineProperty;
			return this;
		}

		public RenderSetup createRenderSetup() {
			return new RenderSetup(
				this.pipeline,
				this.textures,
				this.useLightmap,
				this.useOverlay,
				this.layeringTransform,
				this.outputTarget,
				this.textureTransform,
				this.outlineProperty,
				this.affectsCrumbling,
				this.sortOnUpload,
				this.bufferSize
			);
		}
	}

	@Environment(EnvType.CLIENT)
	public record TextureAndSampler(GpuTextureView textureView, GpuSampler sampler) {
	}

	@Environment(EnvType.CLIENT)
	record TextureBinding(Identifier location, Supplier<GpuSampler> sampler) {
	}
}
