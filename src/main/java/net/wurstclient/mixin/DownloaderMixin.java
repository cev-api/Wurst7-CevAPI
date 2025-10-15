/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.nio.file.Path;
import java.util.UUID;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import net.cevapi.security.ResourcePackProtector;
import net.minecraft.util.Downloader;

@Mixin(Downloader.class)
public abstract class DownloaderMixin
{
	@Shadow
	@Final
	private Path directory;
	
	@ModifyExpressionValue(method = "method_55485",
		at = @At(value = "INVOKE",
			target = "Ljava/nio/file/Path;resolve(Ljava/lang/String;)Ljava/nio/file/Path;"))
	private Path cevapi$rewriteDownloadPath(Path original,
		@Local(argsOnly = true) UUID packId)
	{
		return ResourcePackProtector.remapDownloadPath(directory, original,
			packId);
	}
}
