/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.core.MappedRegistry;

/**
 * Allows temporarily unfreezing a registry. Wurst needs this to spoof registry
 * entries for servers that have mods the client doesn't have. See
 * {@link net.wurstclient.util.RegistrySyncBypass}.
 */
@Mixin(MappedRegistry.class)
public interface MappedRegistryFrozenAccessor
{
	@Accessor("frozen")
	boolean isFrozen();
	
	@Accessor("frozen")
	void setFrozen(boolean frozen);
}
