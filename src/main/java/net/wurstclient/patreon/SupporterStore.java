/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import java.io.IOException;

/**
 * Persistence boundary for supporter sessions. Keeping this separate from the
 * encrypted file implementation makes the authentication state machine
 * testable without Minecraft or native cryptography.
 */
interface SupporterStore
{
	default boolean isPersistent()
	{
		return true;
	}
	
	SupporterData load() throws IOException;
	
	void save(SupporterData data) throws IOException;
	
	void delete() throws IOException;
}
