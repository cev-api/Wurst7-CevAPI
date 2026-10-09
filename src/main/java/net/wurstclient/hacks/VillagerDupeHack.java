/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.villagerdupe.VillagerDupeEngine;
import net.wurstclient.hacks.villagerdupe.VillagerDupeEngine.DupeFilter;
import net.wurstclient.hacks.villagerdupe.VillagerDupeEngine.Opts;
import net.wurstclient.hacks.villagerdupe.VillagerDupeEngine.Result;
import net.wurstclient.hacks.villagerdupe.VillagerDupeEngine.Status;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.mixinterface.IMultiPlayerGameMode;

@SearchTags({"villager dupe", "saddle dupe", "horse armor", "carpet dupe"})
public final class VillagerDupeHack extends Hack
{
	private final EnumSetting<DupeFilter> filter = new EnumSetting<>("Items",
		"Items to dupe", DupeFilter.values(), DupeFilter.ALL);
	private final CheckboxSetting refresh =
		new CheckboxSetting("Refresh", "Re-raise items faster", true);
	private final SliderSetting interval = new SliderSetting("Interval",
		"Ticks between bursts", 1, 1, 20, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting traders =
		new CheckboxSetting("Traders", "Include wandering traders", true);
	private final CheckboxSetting autoEmerald = new CheckboxSetting(
		"Auto Emerald", "Fetch emerald from inventory", true);
	private final CheckboxSetting autoShears =
		new CheckboxSetting("Auto Shears", "Swap shears to offhand", true)
		{
			@Override
			public void update()
			{
				keepTotem.setVisibleInGui(isChecked());
			}
		};
	private final CheckboxSetting keepTotem =
		new CheckboxSetting("Keep Totem", "Never replace offhand totem", true);
	private final CheckboxSetting restoreSlot = new CheckboxSetting(
		"Restore Slot", "Reselect old slot on disable", true);
	
	private VillagerDupeEngine engine = new VillagerDupeEngine();
	private ClientLevel level;
	private int slotBefore = -1;
	private int sheared;
	private int passes;
	private int finishTicks;
	private boolean finishing;
	private Status status = Status.WAITING;
	private Status toasted;
	private Run run;
	private String outcome = "IDLE";
	
	public record Run(Opts opts, boolean waitForItem, boolean holdEmerald,
		int timeout, int passes, int limit, boolean stopWhenMissing)
	{}
	
	public VillagerDupeHack()
	{
		super("VillagerDupe", "Shears trade items off villagers.", false);
		setCategory(Category.TOOLS);
		addSetting(filter);
		addSetting(refresh);
		addSetting(interval);
		addSetting(traders);
		addSetting(autoEmerald);
		addSetting(autoShears);
		addSetting(keepTotem);
		addSetting(restoreSlot);
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if(isEnabled() && WURST.isEnabled())
				tick();
		});
	}
	
	public Opts options()
	{
		return new Opts(filter.getSelected(), traders.isChecked(),
			autoEmerald.isChecked(), autoShears.isChecked(),
			keepTotem.isChecked(), false, refresh.isChecked(),
			interval.getValueI());
	}
	
	@Override
	protected void onEnable()
	{
		resetSession();
		outcome = "RUNNING";
		Status ready =
			engine.readiness(MC, run == null ? options() : run.opts());
		if(ready != null && (run == null || !run.waitForItem()))
			report(ready);
	}
	
	@Override
	protected void onDisable()
	{
		engine.standDown(MC);
		if(restoreSlot.isChecked() && slotBefore >= 0 && MC.player != null
			&& MC.level == level && WURST.isEnabled() && MC.gameMode != null)
		{
			MC.player.getInventory().setSelectedSlot(slotBefore);
			((IMultiPlayerGameMode)MC.gameMode).syncSelectedSlot();
		}
		slotBefore = -1;
		run = null;
		if(outcome.equals("RUNNING"))
			outcome = "STOPPED";
	}
	
	private void resetSession()
	{
		engine = new VillagerDupeEngine();
		level = MC.level;
		slotBefore = -1;
		sheared = passes = finishTicks = 0;
		finishing = false;
		status = Status.WAITING;
		toasted = null;
	}
	
	public boolean start(Run next)
	{
		setEnabled(false);
		run = next;
		setEnabled(true);
		if(!isEnabled())
			run = null;
		return isEnabled();
	}
	
	private void tick()
	{
		if(MC.level != level)
		{
			if(run != null)
			{
				outcome = "WORLD_CHANGED";
				setEnabled(false);
				return;
			}
			resetSession();
		}
		if(MC.player == null || MC.level == null)
			return;
		if(slotBefore < 0)
			slotBefore = MC.player.getInventory().getSelectedSlot();
		engine.advanceTick();
		Opts opts = run == null ? options() : run.opts();
		if(finishing)
		{
			if(engine.flickPending() && finishTicks++ < 10)
				engine.hold(MC, opts);
			if(!engine.flickPending() || finishTicks >= 10)
				finish();
			return;
		}
		if(run != null && run.waitForItem())
		{
			Result result = run.holdEmerald() ? engine.hold(MC, opts)
				: engine.watch(MC, opts);
			report(result.status());
			++passes;
			if(VillagerDupeEngine.anyTarget(MC, opts.filter(), opts.traders()))
			{
				outcome = "FOUND";
				finish();
			}else if(run.timeout() > 0 && passes >= run.timeout())
			{
				outcome = "TIMEOUT";
				finish();
			}
			return;
		}
		Result result = engine.tick(MC, opts);
		++passes;
		sheared += result.sent();
		report(result.status());
		if(run == null)
			return;
		if(run.stopWhenMissing() && status.missing())
		{
			outcome = "MISSING_ITEMS";
			finishing = true;
		}else if(run.limit() > 0 && sheared >= run.limit()
			|| run.passes() > 0 && passes >= run.passes())
		{
			outcome = sheared > 0 ? "SENT"
				: status.working() ? "NO_TARGET" : "BLOCKED";
			finishing = true;
		}
	}
	
	private void finish()
	{
		ChatUtils.message("Villager Dupe: " + outcome + " (" + sheared
			+ " interactions sent).");
		setEnabled(false);
	}
	
	public void onPacketFrame()
	{
		if(isEnabled() && WURST.isEnabled() && MC.level == level)
			engine.closeStrayScreen(MC);
	}
	
	public String resultLine()
	{
		return outcome + ": " + sheared + " interactions sent, " + passes
			+ " passes" + (status.working() ? "" : " (" + status.info() + ")");
	}
	
	@Override
	public String getStatusText()
	{
		if(status.missing() || status == Status.SNEAKING
			|| status == Status.SPECTATOR || status == Status.OFFHAND_TAKEN)
			return status.info();
		return sheared > 0 ? Integer.toString(sheared) : null;
	}
	
	private void report(Status next)
	{
		status = next;
		if(next.working())
		{
			toasted = null;
			return;
		}
		if((!next.missing() && next != Status.SNEAKING) || next == toasted)
			return;
		toasted = next;
		if(next.missing())
			ChatUtils.error(next.message());
		else
			ChatUtils.warning(next.message());
	}
}
