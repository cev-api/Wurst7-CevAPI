/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.util.text.WText;

public final class CommandPrefixOtf extends OtherFeature
{
	public enum Prefix
	{
		DOT("."),
		COLON(":"),
		SEMICOLON(";"),
		COMMA(","),
		EXCLAMATION("!"),
		AT("@"),
		HASH("#"),
		DOLLAR("$"),
		PERCENT("%"),
		CARET("^"),
		AMPERSAND("&"),
		ASTERISK("*");
		
		private final String symbol;
		
		Prefix(String symbol)
		{
			this.symbol = symbol;
		}
		
		@Override
		public String toString()
		{
			return symbol;
		}
	}
	
	private final EnumSetting<Prefix> prefixSetting =
		new EnumSetting<>("Command Prefix",
			WText.translated("options.command_prefix.description"),
			Prefix.values(), Prefix.DOT);
	
	public CommandPrefixOtf()
	{
		super("Command Prefix",
			"Change the command prefix used to trigger Wurst commands.");
		addSetting(prefixSetting);
	}
	
	public EnumSetting<Prefix> getPrefixSetting()
	{
		return prefixSetting;
	}
}
