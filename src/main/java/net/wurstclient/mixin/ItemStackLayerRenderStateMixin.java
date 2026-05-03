/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.wurstclient.WurstClient;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public class ItemStackLayerRenderStateMixin
{
	@Shadow
	private ItemStackRenderState.FoilType foilType;
	
	@Shadow
	private List<BakedQuad> quads;
	
	@Shadow
	public native IntList tintLayers();
	
	@Shadow
	@Final
	ItemStackRenderState this$0;
	
	@Unique
	private java.util.ArrayList<BakedQuad> wurst$originalQuads;
	@Unique
	private IntArrayList wurst$originalTintLayers;
	@Unique
	private boolean wurst$statePatched;
	
	@Inject(
		method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
		at = @At("HEAD"))
	private void onSubmit(PoseStack matrices, SubmitNodeCollector collector,
		int light, int overlay, int seed, CallbackInfo ci)
	{
		wurst$statePatched = false;
		wurst$originalQuads = null;
		wurst$originalTintLayers = null;
		
		ItemDisplayContext context =
			((ItemStackRenderStateAccessor)this$0).wurst$getDisplayContext();
		if(!context.firstPerson())
			return;
		
		HumanoidArm arm =
			context.leftHand() ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
		if(WurstClient.INSTANCE.getHax().viewmodelHack.shouldForceGlint(arm))
			foilType = ItemStackRenderState.FoilType.STANDARD;
		
		float opacity =
			WurstClient.INSTANCE.getHax().viewmodelHack.getOpacity(arm);
		int tint =
			WurstClient.INSTANCE.getHax().viewmodelHack.getOverlayColor(arm);
		if(tint != 0)
			applyTint(tint, opacity < 0.999F);
	}
	
	@Inject(method = "clear()V", at = @At("RETURN"))
	private void onClear(CallbackInfo ci)
	{
		if(!wurst$statePatched)
			return;
		
		for(int i = 0; i < quads.size() && i < wurst$originalQuads.size(); i++)
			quads.set(i, wurst$originalQuads.get(i));
		
		IntList tints = tintLayers();
		tints.clear();
		tints.addAll(wurst$originalTintLayers);
		
		wurst$statePatched = false;
		wurst$originalQuads = null;
		wurst$originalTintLayers = null;
	}
	
	private void applyTint(int tint, boolean forceTranslucent)
	{
		wurst$originalQuads = new java.util.ArrayList<>(quads);
		wurst$originalTintLayers = new IntArrayList(tintLayers());
		wurst$statePatched = true;
		
		IntList tints = tintLayers();
		if(tints.isEmpty())
			tints.add(tint);
		else
			tints.set(0, tint);
		
		for(int i = 0; i < quads.size(); i++)
		{
			BakedQuad quad = quads.get(i);
			MaterialInfo info = quad.materialInfo();
			ChunkSectionLayer layer =
				forceTranslucent ? ChunkSectionLayer.TRANSLUCENT : info.layer();
			RenderType renderType = forceTranslucent
				? RenderTypes.itemTranslucent(info.sprite().atlasLocation())
				: info.itemRenderType();
			
			quads.set(i,
				new BakedQuad(quad.position0(), quad.position1(),
					quad.position2(), quad.position3(), quad.packedUV0(),
					quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
					quad.direction(), new MaterialInfo(info.sprite(), layer,
						renderType, 0, info.shade(), info.lightEmission())));
		}
	}
}
