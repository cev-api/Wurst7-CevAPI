/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.IsPlayerInLavaListener;
import net.wurstclient.events.IsPlayerInLavaListener.IsPlayerInLavaEvent;
import net.wurstclient.events.VelocityFromFluidListener;
import net.wurstclient.events.VelocityFromFluidListener.VelocityFromFluidEvent;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"no slowdown", "no slow down"})
public final class NoSlowdownHack extends Hack
	implements IsPlayerInLavaListener, VelocityFromFluidListener
{
	private final CheckboxSetting lavaSpeed = new CheckboxSetting(
		"No lava slowdown", "Removes lava movement penalties.\n"
			+ "Some servers treat this like a speedhack.",
		false);
	
	private boolean bypassingLava;
	
	public NoSlowdownHack()
	{
		super("NoSlowdown");
		setCategory(Category.MOVEMENT);
		addSetting(lavaSpeed);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(IsPlayerInLavaListener.class, this);
		EVENTS.add(VelocityFromFluidListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(IsPlayerInLavaListener.class, this);
		EVENTS.remove(VelocityFromFluidListener.class, this);
		bypassingLava = false;
	}
	
	@Override
	public void onIsPlayerInLava(IsPlayerInLavaEvent event)
	{
		if(!lavaSpeed.isChecked())
		{
			bypassingLava = false;
			return;
		}
		
		if(event.isNormallyInLava())
		{
			bypassingLava = true;
			event.setInLava(false);
			return;
		}
		
		bypassingLava = false;
	}
	
	@Override
	public void onVelocityFromFluid(VelocityFromFluidEvent event)
	{
		if(!lavaSpeed.isChecked() || !bypassingLava)
			return;
		
		if(event.getEntity() == MC.player)
			event.cancel();
	}
	
	// See BlockMixin.onGetVelocityMultiplier() and
	// ClientPlayerEntityMixin.wurstIsUsingItem()
}
