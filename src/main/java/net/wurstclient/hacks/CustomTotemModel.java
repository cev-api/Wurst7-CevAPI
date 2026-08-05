/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.cuboid.CuboidModelElement;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.cuboid.UnbakedCuboidGeometry;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Parses the bundled 3D totem box model (skin.json) and caches the individual
 * box elements plus the display transforms, so the renderer can draw them
 * directly at render time without going through the normal model baking
 * pipeline.
 *
 * <p>
 * The geometry is the same <code>UnbakedCuboidGeometry</code> that
 * {@link CuboidModel} would bake into regular quads; we just keep the raw
 * elements so we can emit the exact same vertices ourselves.
 */
public final class CustomTotemModel
{
	private static final String SKIN_MODEL_PATH =
		"/assets/wurst/customtotem/skin.json";
	
	private static volatile CustomTotemModel instance;
	
	private final List<CuboidModelElement> elements;
	private final ItemTransforms transforms;
	private final Vector3fc[] extents;
	
	private CustomTotemModel(List<CuboidModelElement> elements,
		ItemTransforms transforms, Vector3fc[] extents)
	{
		this.elements = elements;
		this.transforms = transforms;
		this.extents = extents;
	}
	
	public static CustomTotemModel getInstance()
	{
		CustomTotemModel cached = instance;
		if(cached != null)
			return cached;
		
		synchronized(CustomTotemModel.class)
		{
			cached = instance;
			if(cached != null)
				return cached;
			
			instance = load();
			return instance;
		}
	}
	
	private static CustomTotemModel load()
	{
		try(InputStream in =
			CustomTotemModel.class.getResourceAsStream(SKIN_MODEL_PATH))
		{
			if(in == null)
				return null;
			
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			CuboidModel model = CuboidModel.fromStream(new StringReader(json));
			UnbakedGeometry geometry = model.geometry();
			if(!(geometry instanceof UnbakedCuboidGeometry cuboid))
				return null;
			
			return new CustomTotemModel(cuboid.elements(), model.transforms(),
				computeExtents(cuboid.elements()));
			
		}catch(IOException e)
		{
			e.printStackTrace();
			return null;
		}
	}
	
	private static Vector3fc[] computeExtents(List<CuboidModelElement> elements)
	{
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float minZ = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		float maxZ = -Float.MAX_VALUE;
		
		for(CuboidModelElement element : elements)
		{
			Vector3fc from = element.from();
			Vector3fc to = element.to();
			
			minX = Math.min(minX, Math.min(from.x(), to.x()));
			minY = Math.min(minY, Math.min(from.y(), to.y()));
			minZ = Math.min(minZ, Math.min(from.z(), to.z()));
			maxX = Math.max(maxX, Math.max(from.x(), to.x()));
			maxY = Math.max(maxY, Math.max(from.y(), to.y()));
			maxZ = Math.max(maxZ, Math.max(from.z(), to.z()));
		}
		
		// Convert from block units (0-16) to item-model units (0-1).
		minX /= 16.0F;
		minY /= 16.0F;
		minZ /= 16.0F;
		maxX /= 16.0F;
		maxY /= 16.0F;
		maxZ /= 16.0F;
		
		Vector3fc[] corners = new Vector3fc[]{new Vector3f(minX, minY, minZ),
			new Vector3f(maxX, minY, minZ), new Vector3f(minX, maxY, minZ),
			new Vector3f(maxX, maxY, minZ), new Vector3f(minX, minY, maxZ),
			new Vector3f(maxX, minY, maxZ), new Vector3f(minX, maxY, maxZ),
			new Vector3f(maxX, maxY, maxZ)};
		return corners;
	}
	
	public List<CuboidModelElement> elements()
	{
		return elements;
	}
	
	public ItemTransforms transforms()
	{
		return transforms;
	}
	
	public Vector3fc[] extents()
	{
		return extents;
	}
}
