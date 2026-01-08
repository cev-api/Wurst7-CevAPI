package com.mojang.blaze3d.vertex;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.util.ARGB;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

@Environment(EnvType.CLIENT)
public interface VertexConsumer {
	VertexConsumer addVertex(float f, float g, float h);

	VertexConsumer setColor(int i, int j, int k, int l);

	VertexConsumer setColor(int i);

	VertexConsumer setUv(float f, float g);

	VertexConsumer setUv1(int i, int j);

	VertexConsumer setUv2(int i, int j);

	VertexConsumer setNormal(float f, float g, float h);

	VertexConsumer setLineWidth(float f);

	default void addVertex(float f, float g, float h, int i, float j, float k, int l, int m, float n, float o, float p) {
		this.addVertex(f, g, h);
		this.setColor(i);
		this.setUv(j, k);
		this.setOverlay(l);
		this.setLight(m);
		this.setNormal(n, o, p);
	}

	default VertexConsumer setColor(float f, float g, float h, float i) {
		return this.setColor((int)(f * 255.0F), (int)(g * 255.0F), (int)(h * 255.0F), (int)(i * 255.0F));
	}

	default VertexConsumer setLight(int i) {
		return this.setUv2(i & 65535, i >> 16 & 65535);
	}

	default VertexConsumer setOverlay(int i) {
		return this.setUv1(i & 65535, i >> 16 & 65535);
	}

	default void putBulkData(PoseStack.Pose pose, BakedQuad bakedQuad, float f, float g, float h, float i, int j, int k) {
		this.putBulkData(pose, bakedQuad, new float[]{1.0F, 1.0F, 1.0F, 1.0F}, f, g, h, i, new int[]{j, j, j, j}, k);
	}

	default void putBulkData(PoseStack.Pose pose, BakedQuad bakedQuad, float[] fs, float f, float g, float h, float i, int[] is, int j) {
		Vector3fc vector3fc = bakedQuad.direction().getUnitVec3f();
		Matrix4f matrix4f = pose.pose();
		Vector3f vector3f = pose.transformNormal(vector3fc, new Vector3f());
		int k = bakedQuad.lightEmission();

		for (int l = 0; l < 4; l++) {
			Vector3fc vector3fc2 = bakedQuad.position(l);
			long m = bakedQuad.packedUV(l);
			float n = fs[l];
			int o = ARGB.colorFromFloat(i, n * f, n * g, n * h);
			int p = LightTexture.lightCoordsWithEmission(is[l], k);
			Vector3f vector3f2 = matrix4f.transformPosition(vector3fc2, new Vector3f());
			float q = UVPair.unpackU(m);
			float r = UVPair.unpackV(m);
			this.addVertex(vector3f2.x(), vector3f2.y(), vector3f2.z(), o, q, r, j, p, vector3f.x(), vector3f.y(), vector3f.z());
		}
	}

	default VertexConsumer addVertex(Vector3fc vector3fc) {
		return this.addVertex(vector3fc.x(), vector3fc.y(), vector3fc.z());
	}

	default VertexConsumer addVertex(PoseStack.Pose pose, Vector3f vector3f) {
		return this.addVertex(pose, vector3f.x(), vector3f.y(), vector3f.z());
	}

	default VertexConsumer addVertex(PoseStack.Pose pose, float f, float g, float h) {
		return this.addVertex(pose.pose(), f, g, h);
	}

	default VertexConsumer addVertex(Matrix4fc matrix4fc, float f, float g, float h) {
		Vector3f vector3f = matrix4fc.transformPosition(f, g, h, new Vector3f());
		return this.addVertex(vector3f.x(), vector3f.y(), vector3f.z());
	}

	default VertexConsumer addVertexWith2DPose(Matrix3x2fc matrix3x2fc, float f, float g) {
		Vector2f vector2f = matrix3x2fc.transformPosition(f, g, new Vector2f());
		return this.addVertex(vector2f.x(), vector2f.y(), 0.0F);
	}

	default VertexConsumer setNormal(PoseStack.Pose pose, float f, float g, float h) {
		Vector3f vector3f = pose.transformNormal(f, g, h, new Vector3f());
		return this.setNormal(vector3f.x(), vector3f.y(), vector3f.z());
	}

	default VertexConsumer setNormal(PoseStack.Pose pose, Vector3f vector3f) {
		return this.setNormal(pose, vector3f.x(), vector3f.y(), vector3f.z());
	}
}
