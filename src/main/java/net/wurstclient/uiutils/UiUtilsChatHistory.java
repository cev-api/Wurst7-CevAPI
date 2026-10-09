/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import java.util.List;

/** Per-field history cursor that preserves the unsent draft. */
final class UiUtilsChatHistory
{
	private int offset;
	private String draft = "";
	
	String move(List<String> sent, String current, int direction)
	{
		if(offset == 0)
			draft = current;
		offset = Math.max(0, Math.min(sent.size(), offset - direction));
		return offset == 0 ? draft : sent.get(sent.size() - offset);
	}
	
	void reset()
	{
		offset = 0;
		draft = "";
	}
}
