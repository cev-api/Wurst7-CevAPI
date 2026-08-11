/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

/**
 * States owned by {@link LootSorterController}; no asynchronous work leaks
 * between states.
 */
public enum LootSorterState
{
	DISABLED,
	SELECTING_SOURCES,
	SELECTING_DESTINATIONS,
	PLANNING,
	NAVIGATING_TO_SOURCE,
	OPENING_SOURCE,
	WITHDRAWING,
	CLOSING_SOURCE,
	NAVIGATING_TO_DESTINATION,
	OPENING_DESTINATION,
	DEPOSITING,
	RETURNING_REMAINDER,
	RESCANNING,
	PAUSED,
	COMPLETED,
	ERROR
}
