/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public enum TitleScreenShaderPipelines
{
	;
	
	public static final RenderPipeline TITLE_SHADERTOY_BACKGROUND =
		RenderPipelines.register(RenderPipeline
			.builder(RenderPipelines.GUI_SNIPPET)
			.withLocation(
				Identifier.parse("wurst:pipeline/title_shadertoy_background"))
			.withVertexShader(
				Identifier.parse("wurst:core/title_shadertoy_background"))
			.withFragmentShader(
				Identifier.parse("wurst:core/title_shadertoy_background"))
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0).build());
}
