/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.minecraft.report.AbuseReport;

import net.minecraft.client.multiplayer.chat.report.ReportType;
import net.wurstclient.WurstClient;

@Mixin(
	targets = "net.minecraft.client.multiplayer.chat.report.AbuseReportSender")
public abstract class AbuseReportSenderMixin
{
	@Inject(
		method = "send(Ljava/util/UUID;Lnet/minecraft/client/multiplayer/chat/report/ReportType;Lcom/mojang/authlib/minecraft/report/AbuseReport;)Ljava/util/concurrent/CompletableFuture;",
		at = @At("HEAD"))
	private void wurst$logChatReport(UUID reportedProfileId,
		ReportType reportType, AbuseReport report,
		CallbackInfoReturnable<CompletableFuture<?>> cir)
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getOtfs() == null)
			return;
		
		WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.logVerboseChatReport(reportedProfileId, reportType, report);
	}
}
