/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.shaders.ShaderType;

import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.wurstclient.util.ShadertoyBackgroundManager;

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin
{
	@ModifyVariable(
		method = "loadShader(Lnet/minecraft/resources/Identifier;Lnet/minecraft/server/packs/resources/Resource;Lcom/mojang/blaze3d/shaders/ShaderType;Ljava/util/Map;Lcom/google/common/collect/ImmutableMap$Builder;)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0)
	private static Resource replaceTitleShadertoyShader(Resource resource,
		Identifier id, Resource originalResource, ShaderType shaderType,
		Map<Identifier, Resource> includes, ImmutableMap.Builder<?, ?> builder)
	{
		return ShadertoyBackgroundManager.getCustomShaderResource(id)
			.orElse(resource);
	}
}
