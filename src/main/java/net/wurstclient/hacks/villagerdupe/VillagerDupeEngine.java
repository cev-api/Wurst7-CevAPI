/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.villagerdupe;

import net.wurstclient.WurstClient;
import net.wurstclient.mixinterface.IMultiPlayerGameMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

public final class VillagerDupeEngine
{
	private static final int NEVER = Integer.MIN_VALUE;
	private static final int FORGET_TICKS = 200;
	private static final int RELEARN_TICKS = 600;
	private static final int MAX_SILENT = 3;
	private static final int SILENT_RETRY_TICKS = 100;
	private static final long FLICK_HOLD_NANOS = 50_000_000L;
	private static final long FLICK_HOLD_MAX_NANOS = 150_000_000L;
	private static final int HOLD_STEP_DOWN = 8;
	private final Map<Integer, Track> tracks = new HashMap<>();
	private final ArrayDeque<int[]> shearLog = new ArrayDeque<>();
	private ItemStack loggedShears = ItemStack.EMPTY;
	private int loggedDamage;
	private ClientLevel level;
	private boolean flickPending;
	private int flickFrom = -1;
	private boolean flickEmpty;
	private boolean answering;
	private int answerStart = Integer.MIN_VALUE;
	private int quietTicks;
	private boolean flickSettled;
	private long flickNanos;
	private long flickHold = FLICK_HOLD_NANOS;
	private boolean flickAnswered;
	private int answeredStreak;
	private int hotbarTick = Integer.MIN_VALUE;
	private int lastBurstTick = Integer.MIN_VALUE;
	private int lastInteractTick = Integer.MIN_VALUE;
	private int strayScreensClosed;
	private final int[] stats = new int[6];
	
	private int ticks;
	
	public void advanceTick()
	{
		++ticks;
	}
	
	public Result tick(Minecraft mc, Opts opts)
	{
		this.stats[0] = this.stats[0] + 1;
		return this.run(mc, opts, Mode.SHEAR);
	}
	
	public Result hold(Minecraft mc, Opts opts)
	{
		return this.run(mc, opts, Mode.HOLD);
	}
	
	public Result watch(Minecraft mc, Opts opts)
	{
		return this.run(mc, opts, Mode.WATCH);
	}
	
	public boolean flickPending()
	{
		return this.flickPending;
	}
	
	public String takeStats()
	{
		String line = "passes " + this.stats[0] + ", quiet " + this.stats[1]
			+ ", held " + this.stats[2] + ", bursts " + this.stats[3]
			+ ", flicks " + this.stats[4] + ", settled " + this.stats[5];
		Arrays.fill(this.stats, 0);
		return line;
	}
	
	public int strayScreensClosed()
	{
		return this.strayScreensClosed;
	}
	
	public void standDown(Minecraft mc)
	{
		if(this.flickPending && mc != null && mc.player != null
			&& mc.level == this.level && mc.gameMode != null
			&& this.flickFrom >= 0 && this.flickFrom < 9
			&& WurstClient.INSTANCE.isEnabled())
		{
			selectHotbarSlot(mc, this.flickFrom);
		}
		this.flickPending = false;
		this.flickFrom = -1;
		this.endAnswers();
		this.lastInteractTick = NEVER;
	}
	
	public Status readiness(Minecraft mc, Opts opts)
	{
		if(!VillagerDupeEngine.usable(mc))
		{
			return Status.NO_WORLD;
		}
		LocalPlayer player = mc.player;
		Status emerald = this.emeraldStatus(player, opts);
		if(emerald != null)
		{
			return emerald;
		}
		return this.shearsStatus(mc, player, opts,
			player.hasInfiniteMaterials(), ticks);
	}
	
	public static boolean anyTarget(Minecraft mc, DupeFilter filter,
		boolean traders)
	{
		if(!VillagerDupeEngine.usable(mc))
		{
			return false;
		}
		LocalPlayer player = mc.player;
		double reach = Math.max(0.0, player.entityInteractionRange());
		if(reach <= 0.0)
		{
			return false;
		}
		DupeFilter wanted = filter == null ? DupeFilter.ALL : filter;
		for(AbstractVillager villager : mc.level.getEntitiesOfClass(
			AbstractVillager.class, player.getBoundingBox().inflate(reach),
			Entity::isAlive))
		{
			if(!VillagerDupeEngine.reachable(player, villager, traders)
				|| !wanted.matches(villager.getMainHandItem()))
				continue;
			return true;
		}
		return false;
	}
	
	public static boolean shearable(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
		{
			return false;
		}
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && equippable.canBeSheared()
			&& !EnchantmentHelper.has(stack,
				EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
	}
	
	private Result run(Minecraft mc, Opts opts, Mode mode)
	{
		if(!VillagerDupeEngine.usable(mc))
		{
			return new Result(0, Status.NO_WORLD);
		}
		LocalPlayer player = mc.player;
		int now = ticks;
		this.syncLevel(mc.level);
		this.observe(mc, player, opts, now);
		if(mc.gui.screen() != null)
		{
			if(!this.closeStrayScreen(mc)
				&& player.containerMenu instanceof MerchantMenu)
			{
				this.forgetFirstItems();
			}
			return new Result(0, Status.SCREEN);
		}
		if(mode == Mode.WATCH)
		{
			return new Result(0, Status.WAITING);
		}
		if(player.isSpectator())
		{
			return new Result(0, Status.SPECTATOR);
		}
		if(!player.isAlive() || player.containerMenu != player.inventoryMenu
			|| !player.inventoryMenu.getCarried().isEmpty()
			|| player.isUsingItem() || mc.gameMode.isDestroying()
			|| !WurstClient.INSTANCE.isEnabled())
		{
			return new Result(0, Status.BUSY);
		}
		// All inventory and interaction operations run on the client thread.
		return this.pass(mc, player, opts, now, mode);
	}
	
	private Result pass(Minecraft mc, LocalPlayer player, Opts opts, int now,
		Mode mode)
	{
		this.settleShearLog(player.getOffhandItem());
		Status emerald = this.ensureEmerald(mc, player, opts, now);
		if(emerald != null)
		{
			return new Result(0, emerald);
		}
		if(mode == Mode.HOLD)
		{
			return new Result(0, Status.WAITING);
		}
		boolean creative = player.hasInfiniteMaterials();
		Status shears = this.shearsStatus(mc, player, opts, creative, now);
		List<AbstractVillager> targets = this.targets(mc, player, opts);
		if(targets.isEmpty())
		{
			if(shears == null)
			{
				this.refresh(mc, player, opts, now);
			}
			return new Result(0, shears == null ? Status.WAITING : shears);
		}
		if(player.isShiftKeyDown())
		{
			return new Result(0, Status.SNEAKING);
		}
		if(shears != null)
		{
			return new Result(0, shears);
		}
		if(this.quiet(now) || VillagerDupeEngine.since(now,
			this.lastBurstTick) < opts.interval())
		{
			this.stats[1] = this.stats[1] + 1;
			return new Result(0, Status.WAITING);
		}
		targets.removeIf(villager -> this.held(mc, villager, now));
		if(targets.isEmpty())
		{
			this.stats[2] = this.stats[2] + 1;
			return new Result(0, Status.WAITING);
		}
		Status ready = this.ensureShears(mc, player, opts, creative, now);
		if(ready != null)
		{
			return new Result(0, ready);
		}
		int budget = creative ? Integer.MAX_VALUE : VillagerDupeEngine
			.usesLeft(player.getOffhandItem(), this.inflight(mc, now));
		int count = Math.min(targets.size(),
			opts.nearestOnly() ? Math.min(1, budget) : budget);
		if(count <= 0)
		{
			return new Result(0, Status.SHEARS_WORN);
		}
		if(!this.handsAvailable())
		{
			return new Result(0, Status.BUSY);
		}
		boolean swing = false;
		for(int i = 0; i < count; ++i)
		{
			AbstractVillager villager2 = targets.get(i);
			swing |= VillagerDupeEngine.interact(mc, player, villager2);
			Track track = this.tracks.get(villager2.getId());
			if(track == null)
				continue;
			track.hand = villager2.getMainHandItem();
		}
		this.stats[3] = this.stats[3] + 1;
		if(swing)
		{
			player.swing(InteractionHand.OFF_HAND,
				player.getOffhandItem().getInteractAnimation(), false);
		}
		this.lastBurstTick = now;
		this.lastInteractTick = now;
		if(!creative)
		{
			if(this.shearLog.isEmpty())
			{
				this.restartShearLog(player.getOffhandItem());
			}
			this.shearLog.addLast(new int[]{now, count});
		}
		this.refresh(mc, player, opts, now);
		return new Result(count, Status.SHEARED);
	}
	
	private Status ensureEmerald(Minecraft mc, LocalPlayer player, Opts opts,
		int now)
	{
		int slot;
		Inventory inventory = player.getInventory();
		if(player.getMainHandItem().is(Items.EMERALD))
		{
			if(this.flickPending)
			{
				this.backFromFlick(mc, opts, now);
			}
			return null;
		}
		if(this.flickPending
			&& System.nanoTime() - this.flickNanos < this.flickHold)
		{
			return Status.WAITING;
		}
		if(VillagerDupeEngine.since(now, this.hotbarTick) == 0)
		{
			return Status.BUSY;
		}
		slot = this.flickPending
			&& VillagerDupeEngine.isEmerald(inventory, this.flickFrom)
				? this.flickFrom
				: VillagerDupeEngine.emeraldHotbarSlot(inventory);
		if(slot < 0)
		{
			int from = VillagerDupeEngine.emeraldInventorySlot(inventory);
			if(from >= 0)
			{
				if(!opts.autoEmerald())
				{
					return Status.EMERALD_NOT_IN_HOTBAR;
				}
				int into = VillagerDupeEngine.emptyHotbarSlot(inventory);
				if(into < 0)
				{
					into = inventory.getSelectedSlot();
				}
				if(!this.handsAvailable()
					|| !swapInventoryWithHotbar(mc, from, into))
				{
					return Status.BUSY;
				}
				slot = into;
			}else
			{
				if(player.getOffhandItem().is(Items.EMERALD))
				{
					return this.emeraldFromOffhand(mc, player, opts);
				}
				return Status.NO_EMERALD;
			}
		}
		if(inventory.getSelectedSlot() != slot)
		{
			if(!this.handsAvailable())
			{
				return Status.BUSY;
			}
			selectHotbarSlot(mc, slot);
			if(inventory.getSelectedSlot() != slot)
			{
				return Status.BUSY;
			}
			this.hotbarTick = now;
		}
		if(!player.getMainHandItem().is(Items.EMERALD))
		{
			return Status.BUSY;
		}
		if(this.flickPending)
		{
			this.backFromFlick(mc, opts, now);
		}
		return null;
	}
	
	private Status emeraldFromOffhand(Minecraft mc, LocalPlayer player,
		Opts opts)
	{
		if(!opts.autoShears() || offhandClaimedByOther())
		{
			return Status.EMERALD_IN_OFFHAND;
		}
		int slot = VillagerDupeEngine.bestShearsSlot(player,
			player.hasInfiniteMaterials());
		if(slot < 0)
		{
			return Status.EMERALD_IN_OFFHAND;
		}
		if(!this.handsAvailable()
			|| !VillagerDupeEngine.swapWithOffhand(mc, player, slot))
		{
			return Status.BUSY;
		}
		this.restartShearLog(player.getOffhandItem());
		return Status.BUSY;
	}
	
	private Status emeraldStatus(LocalPlayer player, Opts opts)
	{
		Inventory inventory = player.getInventory();
		if(player.getMainHandItem().is(Items.EMERALD)
			|| VillagerDupeEngine.emeraldHotbarSlot(inventory) >= 0)
		{
			return null;
		}
		if(VillagerDupeEngine.emeraldInventorySlot(inventory) >= 0)
		{
			return opts.autoEmerald() ? null : Status.EMERALD_NOT_IN_HOTBAR;
		}
		if(player.getOffhandItem().is(Items.EMERALD))
		{
			boolean freed = opts.autoShears() && VillagerDupeEngine
				.bestShearsSlot(player, player.hasInfiniteMaterials()) >= 0;
			return freed ? null : Status.EMERALD_IN_OFFHAND;
		}
		return Status.NO_EMERALD;
	}
	
	private Status shearsStatus(Minecraft mc, LocalPlayer player, Opts opts,
		boolean creative, int now)
	{
		ItemStack offhand = player.getOffhandItem();
		boolean offhandShears = offhand.is(Items.SHEARS);
		if(offhandShears && (creative || VillagerDupeEngine.usesLeft(offhand,
			this.inflight(mc, now)) > 0))
		{
			return null;
		}
		boolean settling = offhandShears && !creative
			&& VillagerDupeEngine.usesLeft(offhand, 0) > 0;
		if(!opts.autoShears())
		{
			if(settling)
			{
				return Status.BUSY;
			}
			if(offhandShears)
			{
				return Status.SHEARS_WORN;
			}
			return VillagerDupeEngine.anyShears(player)
				? Status.SHEARS_NOT_IN_OFFHAND : Status.NO_SHEARS;
		}
		if(VillagerDupeEngine.bestShearsSlot(player, creative) < 0)
		{
			if(settling)
			{
				return Status.BUSY;
			}
			return offhandShears || VillagerDupeEngine.anyShears(player)
				? Status.SHEARS_WORN : Status.NO_SHEARS;
		}
		if(opts.keepTotem() && offhand.is(Items.TOTEM_OF_UNDYING))
		{
			return Status.TOTEM;
		}
		if(offhandClaimedByOther())
		{
			return Status.OFFHAND_TAKEN;
		}
		return null;
	}
	
	private Status ensureShears(Minecraft mc, LocalPlayer player, Opts opts,
		boolean creative, int now)
	{
		Status status = this.shearsStatus(mc, player, opts, creative, now);
		if(status != null)
		{
			return status;
		}
		ItemStack offhand = player.getOffhandItem();
		if(offhand.is(Items.SHEARS) && (creative || VillagerDupeEngine
			.usesLeft(offhand, this.inflight(mc, now)) > 0))
		{
			return null;
		}
		int slot = VillagerDupeEngine.bestShearsSlot(player, creative);
		if(slot < 0)
		{
			return Status.NO_SHEARS;
		}
		if(!this.handsAvailable()
			|| !VillagerDupeEngine.swapWithOffhand(mc, player, slot))
		{
			return Status.BUSY;
		}
		this.restartShearLog(player.getOffhandItem());
		return player.getOffhandItem().is(Items.SHEARS) ? null : Status.BUSY;
	}
	
	static int usesLeft(ItemStack shears, int inflight)
	{
		if(!shears.isDamageableItem())
		{
			return Integer.MAX_VALUE;
		}
		return shears.getMaxDamage() - shears.getDamageValue() - 1 - inflight;
	}
	
	void settleShearLog(ItemStack offhand)
	{
		int done = offhand.getDamageValue() - this.loggedDamage;
		if(!offhand.is(Items.SHEARS) || !this.loggedShears.is(Items.SHEARS)
			|| done < 0)
		{
			this.restartShearLog(offhand);
			return;
		}
		this.loggedShears = offhand;
		this.loggedDamage = offhand.getDamageValue();
		while(done > 0 && !this.shearLog.isEmpty())
		{
			int[] entry = this.shearLog.peekFirst();
			int take = Math.min(done, entry[1]);
			entry[1] -= take;
			done -= take;
			if(entry[1] == 0)
				this.shearLog.pollFirst();
		}
	}
	
	void restartShearLog(ItemStack offhand)
	{
		this.shearLog.clear();
		this.loggedShears = offhand;
		this.loggedDamage = this.loggedShears.getDamageValue();
	}
	
	private int inflight(Minecraft mc, int now)
	{
		int window = VillagerDupeEngine.rttTicks(mc) + 3;
		int sum = 0;
		Iterator<int[]> it = this.shearLog.iterator();
		while(it.hasNext())
		{
			int[] entry = it.next();
			if(VillagerDupeEngine.since(now, entry[0]) > window)
			{
				it.remove();
				continue;
			}
			sum += entry[1];
		}
		return sum;
	}
	
	private List<AbstractVillager> targets(Minecraft mc, LocalPlayer player,
		Opts opts)
	{
		ArrayList<AbstractVillager> out = new ArrayList<>();
		double reach = Math.max(0.0, player.entityInteractionRange());
		if(reach <= 0.0)
		{
			return out;
		}
		for(AbstractVillager villager2 : mc.level.getEntitiesOfClass(
			AbstractVillager.class, player.getBoundingBox().inflate(reach),
			Entity::isAlive))
		{
			if(!VillagerDupeEngine.reachable(player, villager2, opts.traders())
				|| !opts.filter().matches(villager2.getMainHandItem()))
				continue;
			out.add(villager2);
		}
		out.sort(Comparator
			.comparingDouble(villager -> villager.distanceToSqr(player)));
		return out;
	}
	
	private static boolean reachable(LocalPlayer player,
		AbstractVillager villager, boolean traders)
	{
		return villager.isAlive() && !villager.isRemoved() && !villager.isBaby()
			&& !villager.isVehicle()
			&& (traders || villager instanceof Villager)
			&& player.isWithinEntityInteractionRange(villager, 0.0);
	}
	
	private static boolean interact(Minecraft mc, LocalPlayer player,
		AbstractVillager villager)
	{
		AABB box = villager.getBoundingBox();
		Vec3 eye = player.getEyePosition();
		Vec3 hit = new Vec3(Math.clamp(eye.x, box.minX, box.maxX),
			Math.clamp(eye.y, box.minY, box.maxY),
			Math.clamp(eye.z, box.minZ, box.maxZ));
		InteractionResult result = mc.gameMode.interact(player, villager,
			new EntityHitResult(villager, hit), InteractionHand.OFF_HAND);
		return result instanceof InteractionResult.Success success
			&& success.swingSource() == InteractionResult.SwingSource.PREDICTED;
	}
	
	private void refresh(Minecraft mc, LocalPlayer player, Opts opts, int now)
	{
		if(!opts.refresh() || this.flickPending || this.answering
			|| VillagerDupeEngine.since(now, this.hotbarTick) == 0)
		{
			return;
		}
		if(!player.getMainHandItem().is(Items.EMERALD)
			|| player.isShiftKeyDown())
		{
			return;
		}
		if(!this.targets(mc, player, opts).isEmpty()
			|| !this.worthRefreshing(opts, now))
		{
			return;
		}
		Inventory inventory = player.getInventory();
		int away = VillagerDupeEngine.awaySlot(inventory);
		if(away < 0 || !this.handsAvailable())
		{
			return;
		}
		int from = inventory.getSelectedSlot();
		boolean empty = inventory.getItem(away).isEmpty();
		selectHotbarSlot(mc, away);
		if(inventory.getSelectedSlot() != away)
		{
			return;
		}
		this.hotbarTick = now;
		this.flickFrom = from;
		this.flickEmpty = empty;
		this.flickPending = true;
		this.flickSettled = false;
		this.flickNanos = System.nanoTime();
		this.flickAnswered = false;
		this.stats[4] = this.stats[4] + 1;
		for(Track track : this.tracks.values())
		{
			track.clearedSinceFlick = false;
		}
	}
	
	private boolean worthRefreshing(Opts opts, int now)
	{
		for(Track track : this.tracks.values())
		{
			if(track.lastSeen != now || !track.inReach
				|| !VillagerDupeEngine.askable(track, now)
				|| track.first != null && !opts.filter().matches(track.first)
					&& VillagerDupeEngine.since(now,
						track.firstTick) <= RELEARN_TICKS)
				continue;
			return true;
		}
		return false;
	}
	
	private static boolean askable(Track track, int now)
	{
		return track.silent < MAX_SILENT || VillagerDupeEngine.since(now,
			track.silentTick) >= SILENT_RETRY_TICKS;
	}
	
	private void backFromFlick(Minecraft mc, Opts opts, int now)
	{
		this.flickPending = false;
		this.flickFrom = -1;
		this.quietTicks = VillagerDupeEngine.rttTicks(mc) + 1;
		this.answering = false;
		for(Track track : this.tracks.values())
		{
			boolean asked = track.lastSeen == now && track.inReach
				&& VillagerDupeEngine.askable(track, now);
			if(asked)
			{
				track.askedTick = now;
			}
			track.expected = asked && (this.flickEmpty || track.first == null
				|| opts.filter().matches(track.first));
			this.answering |= track.expected;
		}
		this.answerStart = now;
	}
	
	private boolean quiet(int now)
	{
		return this.flickPending || VillagerDupeEngine.since(now,
			this.answerStart) < this.quietTicks;
	}
	
	private boolean held(Minecraft mc, AbstractVillager villager, int now)
	{
		if(!this.flickPending && (this.flickSettled || VillagerDupeEngine
			.since(now, this.answerStart) > VillagerDupeEngine.window(mc)))
		{
			return false;
		}
		Track track = this.tracks.get(villager.getId());
		if(track == null || track.cycler || track.first == null)
		{
			return true;
		}
		return !ItemStack.isSameItem(villager.getMainHandItem(), track.first);
	}
	
	private void endAnswers()
	{
		this.answering = false;
		for(Track track : this.tracks.values())
		{
			track.expected = false;
		}
	}
	
	private void observe(Minecraft mc, LocalPlayer player, Opts opts, int now)
	{
		double reach = Math.max(0.0, player.entityInteractionRange());
		for(AbstractVillager villager : mc.level.getEntitiesOfClass(
			AbstractVillager.class,
			player.getBoundingBox().inflate(reach + 2.0), Entity::isAlive))
		{
			if(villager.isBaby()
				|| !opts.traders() && !(villager instanceof Villager))
				continue;
			ItemStack hand = villager.getMainHandItem();
			Track track2 = this.tracks.get(villager.getId());
			if(track2 == null)
			{
				track2 = new Track(hand);
				this.tracks.put(villager.getId(), track2);
			}else if(hand != track2.hand)
			{
				track2.hand = hand;
				boolean sinceFlick =
					this.flickPending || VillagerDupeEngine.since(now,
						this.answerStart) <= VillagerDupeEngine.window(mc);
				if(hand.isEmpty())
				{
					if(sinceFlick)
					{
						track2.clearedSinceFlick = true;
					}
				}else
				{
					if(track2.lastItem != null
						&& hand.getItem() != track2.lastItem)
					{
						track2.cycler = true;
					}
					track2.lastItem = hand.getItem();
					if(sinceFlick && track2.clearedSinceFlick
						&& !this.flickSettled)
					{
						this.flickSettled = true;
						this.flickAnswered = true;
						this.stats[5] = this.stats[5] + 1;
					}
					track2.silent = 0;
					if(VillagerDupeEngine.since(now,
						track2.askedTick) <= VillagerDupeEngine.window(mc))
					{
						track2.first = hand;
						track2.firstTick = now;
					}
					if(track2.expected
						&& (this.flickEmpty || opts.filter().matches(hand)))
					{
						track2.expected = false;
						this.flickAnswered = true;
					}
				}
			}
			track2.lastSeen = now;
			track2.inReach =
				VillagerDupeEngine.reachable(player, villager, opts.traders());
		}
		this.tracks.values().removeIf(track -> VillagerDupeEngine.since(now,
			track.lastSeen) > FORGET_TICKS);
		if(!this.answering)
		{
			return;
		}
		boolean waiting = false;
		for(Track track3 : this.tracks.values())
		{
			waiting |= track3.expected;
		}
		if(waiting && VillagerDupeEngine.since(now,
			this.answerStart) <= VillagerDupeEngine.window(mc))
		{
			return;
		}
		boolean unanswered = false;
		for(Track track2 : this.tracks.values())
		{
			if(track2.expected)
			{
				unanswered = true;
				if(++track2.silent >= MAX_SILENT)
				{
					track2.silentTick = now;
				}
			}
			track2.expected = false;
		}
		this.answering = false;
		this.adaptHold(unanswered && !this.flickAnswered);
	}
	
	private void adaptHold(boolean lost)
	{
		if(lost)
		{
			this.flickHold = Math.min(FLICK_HOLD_MAX_NANOS,
				this.flickHold + FLICK_HOLD_NANOS);
			this.answeredStreak = 0;
		}else if(this.flickHold > FLICK_HOLD_NANOS
			&& ++this.answeredStreak >= HOLD_STEP_DOWN)
		{
			this.flickHold -= FLICK_HOLD_NANOS;
			this.answeredStreak = 0;
		}
	}
	
	private void forgetFirstItems()
	{
		for(Track track : this.tracks.values())
		{
			track.first = null;
			track.firstTick = Integer.MIN_VALUE;
			track.askedTick = Integer.MIN_VALUE;
		}
	}
	
	public boolean closeStrayScreen(Minecraft mc)
	{
		if(this.lastInteractTick == Integer.MIN_VALUE || mc == null
			|| mc.player == null || mc.gui.screen() == null)
		{
			return false;
		}
		if(!(mc.player.containerMenu instanceof MerchantMenu))
		{
			return false;
		}
		if(VillagerDupeEngine.since(ticks,
			this.lastInteractTick) > VillagerDupeEngine.window(mc))
		{
			return false;
		}
		mc.player.closeContainer();
		++this.strayScreensClosed;
		return true;
	}
	
	private void syncLevel(ClientLevel current)
	{
		if(current == this.level)
		{
			return;
		}
		this.level = current;
		this.flickPending = false;
		this.flickFrom = -1;
		this.flickSettled = false;
		this.answerStart = NEVER;
		this.quietTicks = 0;
		this.hotbarTick = NEVER;
		this.lastBurstTick = NEVER;
		this.tracks.clear();
		this.shearLog.clear();
		this.answering = false;
		this.lastInteractTick = Integer.MIN_VALUE;
		this.flickHold = FLICK_HOLD_NANOS;
		this.answeredStreak = 0;
	}
	
	private boolean handsAvailable()
	{
		return WurstClient.INSTANCE.isEnabled();
	}
	
	private static void selectHotbarSlot(Minecraft mc, int slot)
	{
		mc.player.getInventory().setSelectedSlot(slot);
		// Flush vanilla's selected-slot state now, including refreshes without
		// a shear.
		((IMultiPlayerGameMode)mc.gameMode).syncSelectedSlot();
	}
	
	private static boolean swapInventoryWithHotbar(Minecraft mc, int from,
		int into)
	{
		if(!WurstClient.INSTANCE.isEnabled()
			|| mc.player.containerMenu != mc.player.inventoryMenu
			|| !mc.player.inventoryMenu.getCarried().isEmpty() || from < 9
			|| from >= 36 || into < 0 || into >= 9)
			return false;
		mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,
			from, into, ContainerInput.SWAP, mc.player);
		return true;
	}
	
	private static boolean offhandClaimedByOther()
	{
		return WurstClient.INSTANCE.getHax().autoTotemHack.isEnabled();
	}
	
	private static int window(Minecraft mc)
	{
		return 2 * VillagerDupeEngine.rttTicks(mc) + 10;
	}
	
	private static int rttTicks(Minecraft mc)
	{
		PlayerInfo info = mc.getConnection() == null || mc.player == null ? null
			: mc.getConnection().getPlayerInfo(mc.player.getUUID());
		int ms = info == null ? 0 : Math.max(0, info.getLatency());
		return Math.min(40, (ms + 49) / 50);
	}
	
	private static int since(int now, int then)
	{
		if(then == Integer.MIN_VALUE)
		{
			return Integer.MAX_VALUE;
		}
		int delta = now - then;
		return delta < 0 ? Integer.MAX_VALUE : delta;
	}
	
	private static boolean usable(Minecraft mc)
	{
		return mc != null && mc.player != null && mc.level != null
			&& mc.gameMode != null && mc.getConnection() != null;
	}
	
	private static boolean isEmerald(Inventory inventory, int slot)
	{
		return slot >= 0 && slot < 9
			&& inventory.getItem(slot).is(Items.EMERALD);
	}
	
	private static int emeraldHotbarSlot(Inventory inventory)
	{
		int selected = inventory.getSelectedSlot();
		if(VillagerDupeEngine.isEmerald(inventory, selected))
		{
			return selected;
		}
		for(int slot = 0; slot < 9; ++slot)
		{
			if(!VillagerDupeEngine.isEmerald(inventory, slot))
				continue;
			return slot;
		}
		return -1;
	}
	
	private static int emeraldInventorySlot(Inventory inventory)
	{
		for(int slot = 9; slot < 36; ++slot)
		{
			if(!inventory.getItem(slot).is(Items.EMERALD))
				continue;
			return slot;
		}
		return -1;
	}
	
	private static int emptyHotbarSlot(Inventory inventory)
	{
		for(int slot = 0; slot < 9; ++slot)
		{
			if(!inventory.getItem(slot).isEmpty())
				continue;
			return slot;
		}
		return -1;
	}
	
	private static int awaySlot(Inventory inventory)
	{
		int slot;
		int step;
		int selected = inventory.getSelectedSlot();
		for(step = 1; step < 9; ++step)
		{
			slot = (selected + step) % 9;
			if(!inventory.getItem(slot).isEmpty())
				continue;
			return slot;
		}
		for(step = 1; step < 9; ++step)
		{
			slot = (selected + step) % 9;
			if(inventory.getItem(slot).is(Items.EMERALD))
				continue;
			return slot;
		}
		return -1;
	}
	
	private static boolean swapWithOffhand(Minecraft mc, LocalPlayer player,
		int slot)
	{
		if(!WurstClient.INSTANCE.isEnabled()
			|| player.containerMenu != player.inventoryMenu || slot < 0
			|| slot >= 36)
		{
			return false;
		}
		if(!player.inventoryMenu.getCarried().isEmpty())
		{
			return false;
		}
		int handlerSlot = slot < 9 ? 36 + slot : slot;
		mc.gameMode.handleContainerInput(player.inventoryMenu.containerId,
			handlerSlot, 40, ContainerInput.SWAP, player);
		return true;
	}
	
	private static boolean anyShears(LocalPlayer player)
	{
		for(int slot = 0; slot < 36; ++slot)
		{
			if(!player.getInventory().getItem(slot).is(Items.SHEARS))
				continue;
			return true;
		}
		return false;
	}
	
	private static int bestShearsSlot(LocalPlayer player, boolean creative)
	{
		Inventory inventory = player.getInventory();
		int best = -1;
		int bestUses = 0;
		for(int i = 0; i < 36; ++i)
		{
			int uses;
			int slot = (i + 9) % 36;
			ItemStack stack = inventory.getItem(slot);
			if(!stack.is(Items.SHEARS))
				continue;
			uses = creative ? Integer.MAX_VALUE
				: VillagerDupeEngine.usesLeft(stack, 0);
			if(uses <= bestUses)
				continue;
			best = slot;
			bestUses = uses;
		}
		return best;
	}
	
	private static enum Mode
	{
		SHEAR,
		HOLD,
		WATCH;
	
	}
	
	public record Opts(DupeFilter filter, boolean traders, boolean autoEmerald,
		boolean autoShears, boolean keepTotem, boolean nearestOnly,
		boolean refresh, int interval)
	{
		public Opts
		{
			if(filter == null)
			{
				filter = DupeFilter.ALL;
			}
			interval = Math.max(1, interval);
		}
	}
	
	public record Result(int sent, Status status)
	{}
	
	public static enum Status
	{
		SHEARED("", ""),
		WAITING("", ""),
		NO_WORLD("No world", "Not in a world"),
		SCREEN("Screen open", "Screen open"),
		BUSY("Busy", "Hands busy"),
		SPECTATOR("Spectator", "In spectator"),
		SNEAKING("Sneaking", "Stop sneaking to shear"),
		OFFHAND_TAKEN("Offhand busy", "Offhand in use"),
		NO_EMERALD("No emerald", "Need an emerald"),
		EMERALD_NOT_IN_HOTBAR("No emerald", "Emerald not in hotbar"),
		EMERALD_IN_OFFHAND("No emerald", "Emerald in offhand"),
		NO_SHEARS("No shears", "Need shears"),
		SHEARS_NOT_IN_OFFHAND("No shears", "Shears not in offhand"),
		SHEARS_WORN("Shears worn", "Shears worn out"),
		TOTEM("Totem", "Totem in offhand");
		
		private final String info;
		private final String message;
		
		private Status(String info, String message)
		{
			this.info = info;
			this.message = message;
		}
		
		public String info()
		{
			return this.info;
		}
		
		public String message()
		{
			return this.message;
		}
		
		public boolean missing()
		{
			return switch(this)
			{
				case NO_EMERALD, EMERALD_NOT_IN_HOTBAR, EMERALD_IN_OFFHAND, NO_SHEARS, SHEARS_NOT_IN_OFFHAND, SHEARS_WORN, TOTEM -> true;
				default -> false;
			};
		}
		
		public boolean working()
		{
			return this == SHEARED || this == WAITING;
		}
	}
	
	public static enum DupeFilter
	{
		ALL("All"),
		SADDLE("Saddles"),
		HORSE_ARMOR("Horse Armor"),
		CARPET("Carpets");
		
		private static volatile Set<Item> carpets;
		private final String label;
		
		private DupeFilter(String label)
		{
			this.label = label;
		}
		
		@Override
		public String toString()
		{
			return label;
		}
		
		public String label()
		{
			return this.label;
		}
		
		public boolean matches(ItemStack stack)
		{
			if(!VillagerDupeEngine.shearable(stack))
			{
				return false;
			}
			return switch(this)
			{
				case ALL -> true;
				case SADDLE -> stack.is(Items.SADDLE);
				case HORSE_ARMOR ->
				{
					if(stack.is(Items.LEATHER_HORSE_ARMOR)
						|| stack.is(Items.COPPER_HORSE_ARMOR)
						|| stack.is(Items.IRON_HORSE_ARMOR)
						|| stack.is(Items.GOLDEN_HORSE_ARMOR)
						|| stack.is(Items.DIAMOND_HORSE_ARMOR)
						|| stack.is(Items.NETHERITE_HORSE_ARMOR))
					{
						yield true;
					}
					yield false;
				}
				case CARPET -> DupeFilter.isCarpet(stack.getItem());
			};
		}
		
		public static DupeFilter fromToken(String token)
		{
			if(token == null)
			{
				return ALL;
			}
			String key = token.trim().toLowerCase(Locale.ROOT);
			int colon = key.indexOf(58);
			if(colon >= 0)
			{
				key = key.substring(colon + 1);
			}
			if((key = key.replace(' ', '_').replace('-', '_')).endsWith("s"))
			{
				key = key.substring(0, key.length() - 1);
			}
			if(key.equals("saddle"))
			{
				return SADDLE;
			}
			if(key.equals("horse_armor") || key.equals("horse_armour")
				|| key.endsWith("_horse_armor"))
			{
				return HORSE_ARMOR;
			}
			if(key.equals("carpet") || key.endsWith("_carpet"))
			{
				return CARPET;
			}
			return ALL;
		}
		
		private static boolean isCarpet(Item item)
		{
			Set<Item> set = carpets;
			if(set == null)
			{
				carpets = set = Set.copyOf(Items.CARPET.asList());
			}
			return set.contains(item);
		}
	}
	
	private static final class Track
	{
		ItemStack hand;
		int lastSeen;
		boolean inReach;
		ItemStack first;
		int firstTick = Integer.MIN_VALUE;
		int askedTick = Integer.MIN_VALUE;
		int silent;
		int silentTick = Integer.MIN_VALUE;
		boolean expected;
		Item lastItem;
		boolean cycler;
		boolean clearedSinceFlick;
		
		Track(ItemStack hand)
		{
			this.hand = hand;
			if(!hand.isEmpty())
			{
				this.lastItem = hand.getItem();
			}
		}
	}
}
