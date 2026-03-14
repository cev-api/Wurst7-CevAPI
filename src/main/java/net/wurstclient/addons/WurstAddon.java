/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.addons;

import net.wurstclient.WurstClient;
import net.wurstclient.command.Command;
import net.wurstclient.hack.Hack;
import net.wurstclient.other_feature.OtherFeature;

public abstract class WurstAddon
{
	/** Automatically assigned from fabric.mod.json. */
	public String id;
	
	/** Automatically assigned from fabric.mod.json. */
	public String name;
	
	/** Automatically assigned from fabric.mod.json. */
	public String version;
	
	/** Automatically assigned from fabric.mod.json. */
	public String[] authors;
	
	/**
	 * Called during Wurst startup after hack/cmd/feature registries are
	 * available but before GUI/HUD initialization.
	 *
	 * Register addon hacks, commands, and other features here.
	 */
	public abstract void onInitialize();
	
	public String getWebsite()
	{
		return null;
	}
	
	protected final WurstClient getWurst()
	{
		return WurstClient.INSTANCE;
	}
	
	protected final void addHack(Hack hack)
	{
		WurstClient.INSTANCE.getHax().addHack(hack);
	}
	
	protected final void addCommand(Command command)
	{
		WurstClient.INSTANCE.getCmds().addCmd(command);
	}
	
	protected final void addOtherFeature(OtherFeature otherFeature)
	{
		WurstClient.INSTANCE.getOtfs().addOtf(otherFeature);
	}
}
