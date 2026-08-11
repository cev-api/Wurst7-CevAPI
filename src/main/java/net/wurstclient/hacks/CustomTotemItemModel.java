/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState.FoilType;
import net.minecraft.client.renderer.item.ItemStackRenderState.LayerRenderState;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3fc;

/**
 * Item model for the totem of undying that swaps between the vanilla totem and
 * the 3D skin totem at render time, depending on whether the
 * {@link CustomTotemHack} is enabled.
 *
 * <p>
 * This model is baked once at startup (wrapping the vanilla totem model). Every
 * frame the renderer asks it for the current render state, and at that point we
 * pick either the vanilla look or the 3D skin look - so toggling the hack
 * changes the totem instantly, with no resource reload and no loading overlay.
 */
public final class CustomTotemItemModel implements ItemModel
{
	private final ItemModel vanilla;
	private final CustomTotemSpecialRenderer renderer;
	private final ItemTransforms transforms;
	private final Vector3fc[] extents;
	
	public static ItemModel wrap(ItemModel vanilla)
	{
		CustomTotemModel model = CustomTotemModel.getInstance();
		if(model == null)
			return vanilla;
		
		return new CustomTotemItemModel(vanilla,
			new CustomTotemSpecialRenderer(model), model);
	}
	
	private CustomTotemItemModel(ItemModel vanilla,
		CustomTotemSpecialRenderer renderer, CustomTotemModel model)
	{
		this.vanilla = vanilla;
		this.renderer = renderer;
		this.transforms = model.transforms();
		this.extents = model.extents();
	}
	
	@Override
	public void update(ItemStackRenderState output, ItemStack item,
		ItemModelResolver resolver, ItemDisplayContext displayContext,
		ClientLevel level, ItemOwner owner, int seed)
	{
		if(!CustomTotemHack.isActive() || !CustomTotemHack.isSkinReady())
		{
			vanilla.update(output, item, resolver, displayContext, level, owner,
				seed);
			return;
		}
		
		output.appendModelIdentityElement(this);
		LayerRenderState layer = output.newLayer();
		
		if(item.hasFoil())
		{
			layer.setFoilType(FoilType.STANDARD);
			output.setAnimated();
			output.appendModelIdentityElement(FoilType.STANDARD);
		}
		
		layer.setExtents(() -> extents);
		layer.setupSpecialModel(renderer, null);
		layer.setUsesBlockLight(false);
		layer.setTransform(transforms.getTransform(displayContext));
	}
}
