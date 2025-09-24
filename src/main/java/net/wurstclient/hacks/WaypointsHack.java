/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.DeathListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.ChatInputListener.ChatInputEvent;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.waypoints.Waypoint;
import net.wurstclient.waypoints.WaypointDimension;
import net.wurstclient.waypoints.WaypointsManager;

@SearchTags({"waypoint", "waypoints", "marker"})
public final class WaypointsHack extends Hack implements RenderListener,
	DeathListener, net.wurstclient.events.UpdateListener, ChatInputListener
{
	private final WaypointsManager manager;
	private static final DateTimeFormatter TIME_FMT =
		DateTimeFormatter.ofPattern("HH:mm:ss");
	
	private String worldId = "default";
	private BlockPos lastDeathAt;
	private long lastDeathCreatedMs;
	// Track recent death times to avoid duplicates per player
	private final Map<UUID, Long> otherDeathCooldown = new HashMap<>();
	// Track current dead state for edge detection
	private final Set<UUID> knownDead = new HashSet<>();
	// Guard to avoid handling our own injected chat messages
	private boolean sendingOwnChat = false;
	
	private final SliderSetting textRenderDistance = new SliderSetting(
		"Text render distance", 127, 0, 5000, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting alwaysRenderText =
		new CheckboxSetting("Always render text", false);
	private final SliderSetting fadeDistance = new SliderSetting(
		"Waypoint fade distance", 20, 0, 1000, 1, ValueDisplay.INTEGER);
	private final SliderSetting maxDeathPositions = new SliderSetting(
		"Max death positions", 4, 0, 20, 1, ValueDisplay.INTEGER);
	private final SliderSetting labelScale = new SliderSetting("Label scale",
		2.0, 0.5, 5.0, 0.1, ValueDisplay.DECIMAL);
	private final CheckboxSetting chatOnDeath =
		new CheckboxSetting("Chat", true);
	private final CheckboxSetting createDeathWaypoints =
		new CheckboxSetting("Create death waypoints", true);
	private final CheckboxSetting trackOtherDeaths =
		new CheckboxSetting("Track other players' deaths", false);
	private final CheckboxSetting deathWaypointLines =
		new CheckboxSetting("Death waypoint lines", true);
	private final ColorSetting deathColor =
		new ColorSetting("Death waypoint color", new java.awt.Color(0xFF4444));
	
	public WaypointsHack()
	{
		super("Waypoints");
		setCategory(Category.RENDER);
		this.manager = new WaypointsManager(WURST.getWurstFolder());
		addSetting(new net.wurstclient.settings.WaypointsSetting(
			"Manage waypoints", this.manager));
		
		addSetting(textRenderDistance);
		addSetting(alwaysRenderText);
		addSetting(fadeDistance);
		addSetting(maxDeathPositions);
		addSetting(chatOnDeath);
		addSetting(createDeathWaypoints);
		addSetting(deathWaypointLines);
		addSetting(trackOtherDeaths);
		addSetting(labelScale);
		addSetting(deathColor);
	}
	
	@Override
	protected void onEnable()
	{
		worldId = resolveWorldId();
		manager.load(worldId);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(net.wurstclient.events.UpdateListener.class, this);
		EVENTS.add(DeathListener.class, this);
		EVENTS.add(ChatInputListener.class, this);
		otherDeathCooldown.clear();
		knownDead.clear();
	}
	
	@Override
	protected void onDisable()
	{
		manager.save(worldId);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(net.wurstclient.events.UpdateListener.class, this);
		EVENTS.remove(DeathListener.class, this);
		EVENTS.remove(ChatInputListener.class, this);
		otherDeathCooldown.clear();
		knownDead.clear();
	}
	
	@Override
	public void onUpdate()
	{
		String wid = resolveWorldId();
		if(!wid.equals(worldId))
		{
			manager.save(worldId);
			worldId = wid;
			manager.load(worldId);
		}
		// Detect deaths of other players
		if(trackOtherDeaths.isChecked() && MC.world != null
			&& MC.player != null)
		{
			long now = System.currentTimeMillis();
			for(var p : MC.world.getPlayers())
			{
				if(p == MC.player)
					continue;
				UUID id = p.getUuid();
				boolean deadNow =
					p.getHealth() <= 0 || p.isDead() || p.isRemoved();
				boolean wasDead = knownDead.contains(id);
				if(deadNow && !wasDead)
				{
					knownDead.add(id);
					long last = otherDeathCooldown.getOrDefault(id, 0L);
					if(now - last >= 10000)
					{
						BlockPos at = p.getBlockPos().up(2);
						Waypoint w = new Waypoint(UUID.randomUUID(), now);
						String name = p.getName().getString();
						w.setName("Death of " + name + " "
							+ TIME_FMT.format(LocalTime.now()));
						w.setIcon("skull");
						w.setColor(deathColor.getColorI());
						w.setPos(at);
						w.setDimension(currentDim());
						w.setActionWhenNear(Waypoint.ActionWhenNear.DELETE);
						w.setActionWhenNearDistance(4);
						w.setLines(deathWaypointLines.isChecked());
						// Always save and prune
						manager.addOrUpdate(w);
						pruneDeaths();
						manager.save(worldId);
						// Announce in chat if enabled (guard recursion)
						if(chatOnDeath.isChecked())
						{
							sendingOwnChat = true;
							try
							{
								MC.player.sendMessage(
									Text.literal(name + " died at " + at.getX()
										+ ", " + at.getY() + ", " + at.getZ()),
									false);
							}finally
							{
								sendingOwnChat = false;
							}
						}
						otherDeathCooldown.put(id, now);
					}
				}else if(!deadNow && wasDead)
				{
					knownDead.remove(id);
				}
			}
			// removed: pruneTempOtherDeaths();
		}
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		if(sendingOwnChat)
			return; // don't react to our own injected messages
		if(!trackOtherDeaths.isChecked() || MC.world == null
			|| MC.player == null)
			return;
		String msg = event.getComponent().getString();
		if(msg == null || msg.isEmpty())
			return;
		long now = System.currentTimeMillis();
		// Try to match standard death messages: "<name> ..."
		for(var p : MC.world.getPlayers())
		{
			if(p == MC.player)
				continue;
			String name = p.getName().getString();
			if(name == null || name.isEmpty())
				continue;
			if(!msg.startsWith(name + " "))
				continue;
			UUID id = p.getUuid();
			long last = otherDeathCooldown.getOrDefault(id, 0L);
			if(now - last < 10000)
				return; // cooldown
			BlockPos at = p.getBlockPos().up(2);
			Waypoint w = new Waypoint(UUID.randomUUID(), now);
			w.setName(
				"Death of " + name + " " + TIME_FMT.format(LocalTime.now()));
			w.setIcon("skull");
			w.setColor(deathColor.getColorI());
			w.setPos(at);
			w.setDimension(currentDim());
			w.setActionWhenNear(Waypoint.ActionWhenNear.DELETE);
			w.setActionWhenNearDistance(4);
			w.setLines(deathWaypointLines.isChecked());
			// Always save and prune
			manager.addOrUpdate(w);
			pruneDeaths();
			manager.save(worldId);
			otherDeathCooldown.put(id, now);
			
			// If chat is enabled, append coordinates to the incoming line
			if(chatOnDeath.isChecked())
			{
				// Safe-append to avoid immutable siblings crash
				MutableText oldText = (MutableText)event.getComponent();
				MutableText newText = MutableText.of(oldText.getContent());
				newText.setStyle(oldText.getStyle());
				oldText.getSiblings().forEach(newText::append);
				newText.append(
					" at " + at.getX() + ", " + at.getY() + ", " + at.getZ());
				event.setComponent(newText);
			}
			return;
		}
	}
	
	@Override
	public void onRender(MatrixStack matrices, float partialTicks)
	{
		if(MC.player == null || MC.world == null)
			return;
		
		var list = new ArrayList<>(manager.all());
		// removed: tempOtherDeathWps rendering since we always save now
		for(Waypoint w : list)
		{
			if(!w.isVisible())
				continue;
			
			BlockPos wp = worldSpace(w);
			if(wp == null)
				continue;
			
			double distSq = MC.player.squaredDistanceTo(wp.getX() + 0.5,
				wp.getY() + 0.5, wp.getZ() + 0.5);
			if(distSq > (double)(w.getMaxVisible() * w.getMaxVisible()))
				continue;
			
			// Near action with 1s cooldown after creation
			if(System.currentTimeMillis() - w.getCreatedAt() >= 1000
				&& distSq <= (double)(w.getActionWhenNearDistance()
					* w.getActionWhenNearDistance()))
			{
				switch(w.getActionWhenNear())
				{
					case HIDE -> w.setVisible(false);
					case DELETE -> manager.remove(w);
					default ->
						{
						}
				}
			}
			
			// draw tracer + box if enabled
			if(w.isLines())
			{
				RenderUtils.drawTracer(matrices, partialTicks,
					new Vec3d(wp.getX() + 0.5, wp.getY() + 0.5,
						wp.getZ() + 0.5),
					applyFade(w.getColor(), distSq), false);
				RenderUtils.drawOutlinedBoxes(matrices,
					java.util.List.of(new Box(wp)),
					applyFade(w.getColor(), distSq), false);
			}
			
			// labels near distance
			double dist = Math.sqrt(distSq);
			double trd = textRenderDistance.getValue();
			boolean always = alwaysRenderText.isChecked();
			boolean allowBySlider = trd > 0 && dist <= trd;
			boolean renderLabel = always || allowBySlider;
			if(renderLabel)
			{
				String title = w.getName() == null ? "" : w.getName();
				String icon = iconChar(w.getIcon());
				if(!icon.isEmpty())
					title = icon + (title.isEmpty() ? "" : " " + title);
				String distanceText = (int)dist + " blocks";
				double baseY = wp.getY() + 1.2;
				double lx = wp.getX() + 0.5;
				double ly = baseY;
				double lz = wp.getZ() + 0.5;
				// Anchor at a near, fixed distance when either:
				// - Always-render is on (override distance completely), or
				// - The label is extremely far away (to avoid engine culling),
				// even if we're still within the slider distance.
				boolean needAnchor = always || dist > 256.0;
				if(needAnchor)
				{
					Vec3d cam = RenderUtils.getCameraPos();
					Vec3d target = new Vec3d(lx, ly, lz);
					Vec3d dir = target.subtract(cam);
					double len = dir.length();
					if(len > 1e-3)
					{
						double anchor = Math.min(len, 12.0);
						Vec3d anchored = cam.add(dir.multiply(anchor / len));
						lx = anchored.x;
						ly = anchored.y;
						lz = anchored.z;
					}
				}
				float scale = (float)labelScale.getValue();
				boolean anchored = needAnchor;
				// When not anchored, compensate by distance so on-screen size
				// remains approximately constant.
				if(!anchored)
				{
					Vec3d cam2 = RenderUtils.getCameraPos();
					double dLabel = cam2.distanceTo(new Vec3d(lx, ly, lz));
					double compensate = Math.max(1.0, dLabel * 0.1);
					scale *= (float)compensate;
				}
				// Keep a constant 10px separation using local pixel offset
				float sepPx = 10.0f;
				drawWorldLabel(matrices, title, lx, ly, lz,
					applyFade(w.getColor(), distSq), scale, -sepPx);
				drawWorldLabel(matrices, distanceText, lx, ly, lz,
					applyFade(w.getColor(), distSq), (float)(scale * 0.9f), 0f);
			}
		}
	}
	
	@Override
	public void onDeath()
	{
		if(MC.player == null)
			return;
		
		BlockPos at = MC.player.getBlockPos().up(2);
		long now = System.currentTimeMillis();
		if(lastDeathAt != null && lastDeathAt.equals(at)
			&& now - lastDeathCreatedMs < 10000)
			return; // avoid multiple waypoints per death screen
			
		// Optional death chat, regardless of creating a waypoint
		if(chatOnDeath.isChecked())
			MC.player.sendMessage(Text.literal(
				"Died at " + at.getX() + ", " + at.getY() + ", " + at.getZ()),
				false);
		
		if(createDeathWaypoints.isChecked())
		{
			Waypoint w = new Waypoint(UUID.randomUUID(), now);
			w.setName("Death " + TIME_FMT.format(LocalTime.now()));
			w.setIcon("skull");
			w.setColor(deathColor.getColorI());
			w.setPos(at);
			w.setDimension(currentDim());
			w.setActionWhenNear(Waypoint.ActionWhenNear.DELETE);
			w.setActionWhenNearDistance(4);
			w.setLines(deathWaypointLines.isChecked());
			manager.addOrUpdate(w);
			pruneDeaths();
			manager.save(worldId);
		}
		
		lastDeathAt = at;
		lastDeathCreatedMs = now;
	}
	
	private WaypointDimension currentDim()
	{
		if(MC.world == null)
			return WaypointDimension.OVERWORLD;
		String key = MC.world.getRegistryKey().getValue().getPath();
		return switch(key)
		{
			case "the_nether" -> WaypointDimension.NETHER;
			case "the_end" -> WaypointDimension.END;
			default -> WaypointDimension.OVERWORLD;
		};
	}
	
	private String resolveWorldId()
	{
		ServerInfo s = MC.getCurrentServerEntry();
		if(s != null && s.address != null && !s.address.isEmpty())
			return s.address.replace(':', '_');
		return "singleplayer";
	}
	
	private BlockPos worldSpace(Waypoint w)
	{
		WaypointDimension pd = currentDim();
		WaypointDimension wd = w.getDimension();
		BlockPos p = w.getPos();
		if(pd == wd)
			return p;
		if(!w.isOpposite())
			return null;
		// Convert between Overworld and Nether
		if(pd == WaypointDimension.OVERWORLD && wd == WaypointDimension.NETHER)
			return new BlockPos(p.getX() * 8, p.getY(), p.getZ() * 8);
		if(pd == WaypointDimension.NETHER && wd == WaypointDimension.OVERWORLD)
			return new BlockPos(p.getX() / 8, p.getY(), p.getZ() / 8);
		return null;
	}
	
	private void pruneDeaths()
	{
		var all = manager.all();
		var deaths = new ArrayList<Waypoint>();
		for(Waypoint w : all)
			if(w.getName() != null && w.getName().startsWith("Death "))
				deaths.add(w);
		if(deaths.size() <= maxDeathPositions.getValueI())
			return;
		deaths.sort((a, b) -> Long.compare(a.getCreatedAt(), b.getCreatedAt()));
		int remove = deaths.size() - maxDeathPositions.getValueI();
		for(int i = 0; i < remove; i++)
			manager.remove(deaths.get(i));
	}
	
	private void drawWorldLabel(MatrixStack matrices, String text, double x,
		double y, double z, int argb, float scale, float offsetPx)
	{
		matrices.push();
		Vec3d cam = RenderUtils.getCameraPos();
		matrices.translate(x - cam.x, y - cam.y, z - cam.z);
		matrices.multiply(MC.getEntityRenderDispatcher().getRotation());
		float s = 0.025F * scale;
		matrices.scale(s, -s, s);
		// After scaling, translate by pixel offset to separate lines
		// consistently.
		matrices.translate(0, offsetPx, 0);
		TextRenderer tr = MC.textRenderer;
		VertexConsumerProvider.Immediate vcp = RenderUtils.getVCP();
		float w = tr.getWidth(text) / 2F;
		int bg = (int)(MC.options.getTextBackgroundOpacity(0.25F) * 255) << 24;
		var matrix = matrices.peek().getPositionMatrix();
		tr.draw(text, -w, 0, argb, false, matrix, vcp,
			TextRenderer.TextLayerType.SEE_THROUGH, bg, 0xF000F0);
		vcp.draw();
		matrices.pop();
	}
	
	private int applyFade(int argb, double distSq)
	{
		double fade = fadeDistance.getValue();
		if(fade <= 0)
			return argb;
		double dist = Math.sqrt(distSq);
		if(dist >= fade)
			return argb;
		int a = (argb >>> 24) & 0xFF;
		int r = (argb >>> 16) & 0xFF;
		int g = (argb >>> 8) & 0xFF;
		int b = argb & 0xFF;
		int na = (int)Math.max(0, Math.min(a, a * (dist / fade)));
		return (na << 24) | (r << 16) | (g << 8) | b;
	}
	
	private String iconChar(String icon)
	{
		if(icon == null)
			return "";
		switch(icon.toLowerCase())
		{
			case "square":
			return "■";
			case "circle":
			return "●";
			case "triangle":
			return "▲";
			case "star":
			return "★";
			case "diamond":
			return "♦";
			case "skull":
			return "☠";
			default:
			return "";
		}
	}
	
	public void openManager()
	{
		MC.setScreen(new net.wurstclient.clickgui.screens.WaypointsScreen(
			MC.currentScreen, manager));
	}
}
