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

/** Exposes the protocol azimuth (stored by vanilla as radians). */
@Mixin(
	targets = "net.minecraft.world.waypoints.TrackedWaypoint$AzimuthWaypoint")
public interface TrackedWaypointAzimuthAccessor
{
	@Accessor("angle")
	float wurst$getAngle();
}
