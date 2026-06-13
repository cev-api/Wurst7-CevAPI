/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.LinkedHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.wurstclient.WurstClient;

@Mixin(
	targets = "net.minecraft.client.gui.screens.reporting.AbstractReportScreen")
public class ChatReportScreenMixin
{
	@Inject(method = "sendReport()V", at = @At("HEAD"))
	private void wurst$logReportSubmit(CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getOtfs() == null)
			return;
		
		LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
		fields.put("stage", "sendReport");
		fields.put("screen", "AbstractReportScreen");
		WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.logVerboseExternalEvent("ChatReportFlow", fields);
	}
}
