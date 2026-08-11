/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Map;
import java.util.function.Consumer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.FaceInfo.VertexInfo;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Renders the 3D totem boxes (from skin.json) as raw geometry, using the exact
 * same vertex math as the vanilla cuboid baker ({@code FaceBakery}), so the
 * result matches the "Spunky Custom Totem - 3D" resource pack 1:1.
 *
 * <p>
 * The texture is the player skin, registered as a plain
 * {@link net.minecraft.client.renderer.texture.DynamicTexture} under
 * {@code wurst:custom_totem_skin}. No resource packs and no resource reloads
 * are ever involved.
 */
public final class CustomTotemSpecialRenderer
	implements NoDataSpecialModelRenderer
{
	private final CustomTotemModel model;
	private RenderType renderType;
	
	public CustomTotemSpecialRenderer(CustomTotemModel model)
	{
		this.model = model;
	}
	
	@Override
	public void submit(ItemDisplayContext displayContext, PoseStack poseStack,
		SubmitNodeCollector submitNodeCollector, int lightCoords,
		int overlayCoords, boolean hasFoil, int outlineColor)
	{
		// Lazily created on the render thread.
		if(renderType == null)
			renderType =
				RenderTypes.entityCutout(CustomTotemHack.getSkinTextureId());
		
		submitNodeCollector.order(0).submitCustomGeometry(poseStack, renderType,
			(pose, vertexConsumer) -> renderElements(pose, vertexConsumer,
				lightCoords, overlayCoords));
	}
	
	@Override
	public void getExtents(Consumer<Vector3fc> output)
	{
		for(Vector3fc corner : model.extents())
			output.accept(corner);
	}
	
	private void renderElements(PoseStack.Pose pose, VertexConsumer consumer,
		int lightCoords, int overlayCoords)
	{
		Matrix4f matrix = pose.pose();
		Matrix3f normalMatrix = pose.normal();
		
		for(BlockElement element : model.elements())
		{
			Vector3fc from = element.from();
			Vector3fc to = element.to();
			
			for(Map.Entry<Direction, BlockElementFace> entry : element.faces()
				.entrySet())
			{
				Direction facing = entry.getKey();
				BlockElementFace face = entry.getValue();
				
				BlockElementFace.UVs uvs = face.uvs();
				if(uvs == null)
					uvs = defaultFaceUV(from, to, facing);
				
				FaceInfo faceInfo = FaceInfo.fromFacing(facing);
				Vector3f normal = new Vector3f(facing.step());
				normalMatrix.transform(normal);
				
				for(int i = 0; i < 4; i++)
				{
					VertexInfo vertexInfo = faceInfo.getVertexInfo(i);
					Vector3f vertex = vertexInfo.select(from, to);
					float x = vertex.x() / 16.0F;
					float y = vertex.y() / 16.0F;
					float z = vertex.z() / 16.0F;
					
					Vector3f pos =
						matrix.transformPosition(x, y, z, new Vector3f());
					
					float u = BlockElementFace.getU(uvs, face.rotation(), i);
					float v = BlockElementFace.getV(uvs, face.rotation(), i);
					
					consumer.addVertex(pos.x(), pos.y(), pos.z(), -1, u, v,
						overlayCoords, lightCoords, normal.x(), normal.y(),
						normal.z());
				}
			}
		}
	}
	
	/**
	 * Default UVs for a face when none are specified (safety net; every face in
	 * skin.json provides explicit UVs).
	 */
	private static BlockElementFace.UVs defaultFaceUV(Vector3fc from,
		Vector3fc to, Direction facing)
	{
		return switch(facing)
		{
			case DOWN -> new BlockElementFace.UVs(from.x(), from.z(), to.x(),
				to.z());
			case UP -> new BlockElementFace.UVs(from.x(), from.z(), to.x(),
				to.z());
			case NORTH -> new BlockElementFace.UVs(to.x(), to.z(), from.x(),
				from.z());
			case SOUTH -> new BlockElementFace.UVs(from.x(), to.z(), to.x(),
				from.z());
			case WEST -> new BlockElementFace.UVs(from.z(), to.z(), to.x(),
				from.z());
			case EAST -> new BlockElementFace.UVs(from.z(), from.z(), to.x(),
				to.z());
		};
	}
}
