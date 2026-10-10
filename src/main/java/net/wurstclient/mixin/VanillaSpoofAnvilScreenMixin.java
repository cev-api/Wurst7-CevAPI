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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;

/** Protect only automatic server-to-client item-name feedback. */
@Mixin(AnvilScreen.class)
public abstract class VanillaSpoofAnvilScreenMixin
{
	@WrapOperation(method = "slotChanged",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"))
	private String wurst$vanillaAnvilFeedback(Component name,
		Operation<String> original)
	{
		var spoof = WurstClient.INSTANCE.getOtfs().vanillaSpoofOtf;
		return spoof.isAnvilFeedbackProtectionEnabled()
			? spoof.getAnvilFeedbackName(name) : original.call(name);
	}
}
