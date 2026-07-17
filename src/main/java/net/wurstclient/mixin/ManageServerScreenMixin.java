/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.wurstclient.mixinterface.IServerDataExt;

@Mixin(ManageServerScreen.class)
public class ManageServerScreenMixin extends Screen
{
	@Shadow
	@Final
	private ServerData serverData;
	
	private ManageServerScreenMixin(Component title)
	{
		super(title);
	}
	
	@Inject(method = "init()V", at = @At("TAIL"))
	private void addBypassToggle(CallbackInfo ci)
	{
		boolean enabled =
			((IServerDataExt)(Object)serverData).wurst$getBypassMojangBlock();
		// Vanilla places the resource-pack button at height / 4 + 72.
		// Keep this toggle directly above it, regardless of window size.
		CycleButton<Boolean> toggle = CycleButton.onOffBuilder(enabled).create(
			width / 2 - 100, height / 4 + 50, 200, 20,
			Component.literal("Bypass Mojang block"),
			(button, value) -> ((IServerDataExt)(Object)serverData)
				.wurst$setBypassMojangBlock(value));
		addRenderableWidget(toggle);
	}
}
