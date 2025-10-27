/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.widgets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.util.Window;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * A reusable list widget that keeps selection and scroll state when entries are
 * reloaded, supports shift-range selection, and allows ctrl-click multi
 * selection. Subclasses only need to provide entries that expose a stable key.
 */
public abstract class MultiSelectEntryListWidget<E extends MultiSelectEntryListWidget.Entry<E>>
	extends AlwaysSelectedEntryListWidget<E>
{
	private final LinkedHashSet<String> selectedKeys = new LinkedHashSet<>();
	private String anchorKey;
	private Runnable selectionListener = () -> {};
	
	protected MultiSelectEntryListWidget(MinecraftClient client, int width,
		int height, int top, int itemHeight)
	{
		super(client, width, height, top, itemHeight);
	}
	
	public void setSelectionListener(Runnable listener)
	{
		selectionListener = listener != null ? listener : () -> {};
	}
	
	protected void notifySelectionChanged()
	{
		selectionListener.run();
	}
	
	public List<E> getSelectedEntries()
	{
		Map<String, E> byKey = buildEntryMap(children());
		List<E> entries = new ArrayList<>();
		for(String key : selectedKeys)
		{
			E entry = byKey.get(key);
			if(entry != null)
				entries.add(entry);
		}
		return List.copyOf(entries);
	}
	
	public List<String> getSelectedKeys()
	{
		return List.copyOf(selectedKeys);
	}
	
	public boolean hasSelection()
	{
		if(selectedKeys.isEmpty())
			return false;
		
		return resolveAnchorEntry() != null;
	}
	
	protected boolean isEntrySelected(E entry)
	{
		return selectedKeys.contains(getSelectionKey(entry));
	}
	
	protected abstract String getSelectionKey(E entry);
	
	protected void onEntryClicked(E entry, boolean shiftDown, boolean ctrlDown)
	{
		boolean entrySelected;
		String entryKey = getSelectionKey(entry);
		
		if(shiftDown && anchorKey != null)
		{
			entrySelected = selectRange(anchorKey, entry, ctrlDown);
		}else if(ctrlDown)
		{
			entrySelected = toggleEntry(entry);
		}else
		{
			entrySelected = selectSingle(entry);
		}
		
		E anchorEntry;
		if(entrySelected)
		{
			anchorKey = entryKey;
			anchorEntry = entry;
		}else
		{
			anchorEntry = resolveAnchorEntry();
		}
		
		if(anchorEntry == null && !children().isEmpty())
		{
			anchorEntry = children().get(0);
			String key = getSelectionKey(anchorEntry);
			if(selectedKeys.isEmpty())
				selectedKeys.add(key);
			anchorKey = key;
		}
		
		super.setSelected(anchorEntry);
		notifySelectionChanged();
	}
	
	private boolean selectSingle(E entry)
	{
		selectedKeys.clear();
		selectedKeys.add(getSelectionKey(entry));
		return true;
	}
	
	private boolean toggleEntry(E entry)
	{
		String key = getSelectionKey(entry);
		
		if(selectedKeys.contains(key))
		{
			if(selectedKeys.size() > 1)
			{
				selectedKeys.remove(key);
				return false;
			}
			
			return true;
		}
		
		selectedKeys.add(key);
		return true;
	}
	
	private boolean selectRange(String fromKey, E to, boolean additive)
	{
		E from = findEntryByKey(fromKey);
		if(from == null)
			return selectSingle(to);
		
		if(!additive)
			selectedKeys.clear();
		
		List<E> children = children();
		int start = children.indexOf(from);
		int end = children.indexOf(to);
		
		if(start == -1 || end == -1)
		{
			return selectSingle(to);
		}
		
		if(start > end)
		{
			int tmp = start;
			start = end;
			end = tmp;
		}
		
		for(int i = start; i <= end; i++)
			selectedKeys.add(getSelectionKey(children.get(i)));
		
		return true;
	}
	
	private E findEntryByKey(String key)
	{
		if(key == null)
			return null;
		
		for(E entry : children())
			if(Objects.equals(getSelectionKey(entry), key))
				return entry;
			
		return null;
	}
	
	private Map<String, E> buildEntryMap(List<E> entries)
	{
		return entries.stream().collect(Collectors.toMap(this::getSelectionKey,
			Function.identity(), (a, b) -> a, LinkedHashMap::new));
	}
	
	private E resolveAnchorEntry()
	{
		if(selectedKeys.isEmpty())
		{
			anchorKey = null;
			return null;
		}
		
		List<E> entries = children();
		if(entries.isEmpty())
		{
			selectedKeys.clear();
			anchorKey = null;
			return null;
		}
		
		Map<String, E> byKey = buildEntryMap(entries);
		
		if(selectedKeys.removeIf(key -> !byKey.containsKey(key)))
		{
			if(selectedKeys.isEmpty())
			{
				anchorKey = null;
				return null;
			}
		}
		
		if(anchorKey != null)
		{
			E entry = byKey.get(anchorKey);
			if(entry != null)
				return entry;
		}
		
		String firstKey = selectedKeys.iterator().next();
		anchorKey = firstKey;
		return byKey.get(firstKey);
	}
	
	protected boolean isSelectedEntry(int index)
	{
		E entry = children().get(index);
		return selectedKeys.contains(getSelectionKey(entry));
	}
	
	protected boolean isShiftDown()
	{
		return isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)
			|| isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
	}
	
	protected boolean isControlDown()
	{
		boolean control = isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL)
			|| isKeyDown(GLFW.GLFW_KEY_RIGHT_CONTROL);
		if(Util.getOperatingSystem() == Util.OperatingSystem.OSX)
			control |= isKeyDown(GLFW.GLFW_KEY_LEFT_SUPER)
				|| isKeyDown(GLFW.GLFW_KEY_RIGHT_SUPER);
		return control;
	}
	
	private boolean isKeyDown(int keyCode)
	{
		Window window = MinecraftClient.getInstance().getWindow();
		return GLFW.glfwGetKey(window.getHandle(), keyCode) == GLFW.GLFW_PRESS;
	}
	
	public SelectionState captureState()
	{
		E anchorEntry = resolveAnchorEntry();
		List<String> keys = new ArrayList<>(selectedKeys);
		String anchorKey = this.anchorKey;
		int anchorIndex =
			anchorEntry != null ? children().indexOf(anchorEntry) : -1;
		return new SelectionState(new ArrayList<>(keys), anchorKey,
			getScrollY(), anchorIndex);
	}
	
	public void restoreState(SelectionState state)
	{
		selectedKeys.clear();
		anchorKey = null;
		
		List<E> entries = children();
		if(entries.isEmpty())
		{
			super.setSelected(null);
			notifySelectionChanged();
			return;
		}
		
		Map<String, E> byKey = buildEntryMap(entries);
		
		double targetScroll = state != null
			? MathHelper.clamp(state.scrollAmount(), 0, getMaxScrollY())
			: getScrollY();
		
		if(state != null && !state.selectedKeys().isEmpty())
		{
			for(String key : state.selectedKeys())
			{
				E entry = byKey.get(key);
				if(entry != null)
					selectedKeys.add(key);
			}
			if(!selectedKeys.isEmpty())
			{
				if(state.anchorKey() != null)
					anchorKey = state.anchorKey();
				if(anchorKey == null || !byKey.containsKey(anchorKey))
					anchorKey = selectedKeys.iterator().next();
			}
		}
		
		selectedKeys.removeIf(key -> !byKey.containsKey(key));
		
		if(selectedKeys.isEmpty())
		{
			int index = state != null ? state.anchorIndex() : -1;
			index = MathHelper.clamp(index, 0, entries.size() - 1);
			E fallback = entries.get(index);
			String key = getSelectionKey(fallback);
			selectedKeys.add(key);
			anchorKey = key;
		}
		
		E anchorEntry = resolveAnchorEntry();
		super.setSelected(anchorEntry);
		setScrollY(targetScroll);
		notifySelectionChanged();
	}
	
	public void ensureSelection()
	{
		if(!selectedKeys.isEmpty() || children().isEmpty())
		{
			if(selectedKeys.isEmpty() && children().isEmpty())
			{
				super.setSelected(null);
				notifySelectionChanged();
			}
			return;
		}
		
		E first = children().get(0);
		String key = getSelectionKey(first);
		selectedKeys.add(key);
		anchorKey = key;
		super.setSelected(first);
		notifySelectionChanged();
	}
	
	public void setSelection(Collection<String> keys, double scrollAmount)
	{
		List<String> list = new ArrayList<>(keys);
		String anchorKey = list.isEmpty() ? null : list.get(0);
		restoreState(new SelectionState(list, anchorKey, scrollAmount, -1));
	}
	
	public record SelectionState(List<String> selectedKeys, String anchorKey,
		double scrollAmount, int anchorIndex)
	{}
	
	public abstract static class Entry<E extends Entry<E>>
		extends AlwaysSelectedEntryListWidget.Entry<E>
	{
		private final MultiSelectEntryListWidget<E> parent;
		
		protected Entry(MultiSelectEntryListWidget<E> parent)
		{
			this.parent = parent;
		}
		
		protected MultiSelectEntryListWidget<E> parent()
		{
			return parent;
		}
		
		public abstract String selectionKey();
		
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button)
		{
			if(button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
				return false;
			
			boolean shiftDown = Screen.hasShiftDown() || parent.isShiftDown();
			boolean ctrlDown =
				Screen.hasControlDown() || parent.isControlDown();
			
			parent.onEntryClicked(self(), shiftDown, ctrlDown);
			return true;
		}
		
		@SuppressWarnings("unchecked")
		private E self()
		{
			return (E)this;
		}
	}
}
