/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.wurstclient.hud.DurabilityHud;

@Mixin(Gui.class)
public class SelectedItemNameMixin
{
	@Inject(method = "renderSelectedItemName",
		at = @At("HEAD"),
		cancellable = true)
	private void onRenderSelectedItemName(GuiGraphics context, CallbackInfo ci)
	{
		if(DurabilityHud.renderSelectedItemNameWithEnchantments(context))
			ci.cancel();
	}
}
