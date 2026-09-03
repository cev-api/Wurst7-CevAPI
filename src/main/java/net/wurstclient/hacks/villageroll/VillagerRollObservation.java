/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.villageroll;

import java.util.Objects;
import java.util.UUID;

public record VillagerRollObservation(UUID villagerId, VillagerRollRoll roll)
{
	public VillagerRollObservation
	{
		Objects.requireNonNull(villagerId);
		Objects.requireNonNull(roll);
	}
	
	public VillagerRollNormalizedRoll normalized()
	{
		return roll.normalize();
	}
}
