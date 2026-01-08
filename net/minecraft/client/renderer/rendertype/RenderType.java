package net.minecraft.client.renderer.rendertype;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Map.Entry;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;

@Environment(EnvType.CLIENT)
public class RenderType {
	private static final int MEGABYTE = 1048576;
	public static final int BIG_BUFFER_SIZE = 4194304;
	public static final int SMALL_BUFFER_SIZE = 786432;
	public static final int TRANSIENT_BUFFER_SIZE = 1536;
	private final RenderSetup state;
	private final Optional<RenderType> outline;
	protected final String name;

	private RenderType(String string, RenderSetup renderSetup) {
		this.name = string;
		this.state = renderSetup;
		this.outline = renderSetup.outlineProperty == RenderSetup.OutlineProperty.AFFECTS_OUTLINE
			? renderSetup.textures
				.values()
				.stream()
				.findFirst()
				.map(textureBinding -> (RenderType)RenderTypes.OUTLINE.apply(textureBinding.location(), renderSetup.pipeline.isCull()))
			: Optional.empty();
	}

	public static RenderType create(String string, RenderSetup renderSetup) {
		return new RenderType(string, renderSetup);
	}

	public String toString() {
		return "RenderType[" + this.name + ":" + this.state + "]";
	}

	public void draw(MeshData meshData) {
		Matrix4fStack matrix4fStack = RenderSystem.getModelViewStack();
		Consumer<Matrix4fStack> consumer = this.state.layeringTransform.getModifier();
		if (consumer != null) {
			matrix4fStack.pushMatrix();
			consumer.accept(matrix4fStack);
		}

		GpuBufferSlice gpuBufferSlice = RenderSystem.getDynamicUniforms()
			.writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), this.state.textureTransform.getMatrix());
		Map<String, RenderSetup.TextureAndSampler> map = this.state.getTextures();
		MeshData var6 = meshData;

		try {
			GpuBuffer gpuBuffer = this.state.pipeline.getVertexFormat().uploadImmediateVertexBuffer(meshData.vertexBuffer());
			GpuBuffer gpuBuffer2;
			VertexFormat.IndexType indexType;
			if (meshData.indexBuffer() == null) {
				RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(meshData.drawState().mode());
				gpuBuffer2 = autoStorageIndexBuffer.getBuffer(meshData.drawState().indexCount());
				indexType = autoStorageIndexBuffer.type();
			} else {
				gpuBuffer2 = this.state.pipeline.getVertexFormat().uploadImmediateIndexBuffer(meshData.indexBuffer());
				indexType = meshData.drawState().indexType();
			}

			RenderTarget renderTarget = this.state.outputTarget.getRenderTarget();
			GpuTextureView gpuTextureView = RenderSystem.outputColorTextureOverride != null
				? RenderSystem.outputColorTextureOverride
				: renderTarget.getColorTextureView();
			GpuTextureView gpuTextureView2 = renderTarget.useDepth
				? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : renderTarget.getDepthTextureView())
				: null;

			try (RenderPass renderPass = RenderSystem.getDevice()
					.createCommandEncoder()
					.createRenderPass(() -> "Immediate draw for " + this.name, gpuTextureView, OptionalInt.empty(), gpuTextureView2, OptionalDouble.empty())) {
				renderPass.setPipeline(this.state.pipeline);
				ScissorState scissorState = RenderSystem.getScissorStateForRenderTypeDraws();
				if (scissorState.enabled()) {
					renderPass.enableScissor(scissorState.x(), scissorState.y(), scissorState.width(), scissorState.height());
				}

				RenderSystem.bindDefaultUniforms(renderPass);
				renderPass.setUniform("DynamicTransforms", gpuBufferSlice);
				renderPass.setVertexBuffer(0, gpuBuffer);

				for (Entry<String, RenderSetup.TextureAndSampler> entry : map.entrySet()) {
					renderPass.bindTexture(
						(String)entry.getKey(), ((RenderSetup.TextureAndSampler)entry.getValue()).textureView(), ((RenderSetup.TextureAndSampler)entry.getValue()).sampler()
					);
				}

				renderPass.setIndexBuffer(gpuBuffer2, indexType);
				renderPass.drawIndexed(0, 0, meshData.drawState().indexCount(), 1);
			}
		} catch (Throwable var20) {
			if (meshData != null) {
				try {
					var6.close();
				} catch (Throwable var17) {
					var20.addSuppressed(var17);
				}
			}

			throw var20;
		}

		if (meshData != null) {
			meshData.close();
		}

		if (consumer != null) {
			matrix4fStack.popMatrix();
		}
	}

	public int bufferSize() {
		return this.state.bufferSize;
	}

	public VertexFormat format() {
		return this.state.pipeline.getVertexFormat();
	}

	public VertexFormat.Mode mode() {
		return this.state.pipeline.getVertexFormatMode();
	}

	public Optional<RenderType> outline() {
		return this.outline;
	}

	public boolean isOutline() {
		return this.state.outlineProperty == RenderSetup.OutlineProperty.IS_OUTLINE;
	}

	public RenderPipeline pipeline() {
		return this.state.pipeline;
	}

	public boolean affectsCrumbling() {
		return this.state.affectsCrumbling;
	}

	public boolean canConsolidateConsecutiveGeometry() {
		return !this.mode().connectedPrimitives;
	}

	public boolean sortOnUpload() {
		return this.state.sortOnUpload;
	}
}
