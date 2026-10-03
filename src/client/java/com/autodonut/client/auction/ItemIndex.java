package com.autodonut.client.auction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Searchable list of every registered item, built lazily the first time it is needed. */
public final class ItemIndex {
	public record Entry(String id, String name, ItemStack stack) {
		private String lowerName() {
			return name.toLowerCase(Locale.ROOT);
		}
	}

	private static List<Entry> entries;
	private static final Map<String, Entry> BY_ID = new HashMap<>();

	private ItemIndex() {
	}

	private static List<Entry> entries() {
		if (entries == null) {
			List<Entry> list = new ArrayList<>();
			BuiltInRegistries.ITEM.stream().forEach(item -> {
				if (item == Items.AIR) return;
				ItemStack stack = item.getDefaultInstance();
				Entry e = new Entry(idOf(item), stack.getHoverName().getString(), stack);
				list.add(e);
				BY_ID.put(e.id(), e);
			});
			list.sort(Comparator.comparing(Entry::name));
			entries = list;
		}
		return entries;
	}

	public static String idOf(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	public static Entry byId(String id) {
		entries();
		return BY_ID.get(id);
	}

	/** The item whose display name matches exactly (ignoring case), or null. */
	public static Entry byName(String name) {
		String n = name.trim();
		if (n.isEmpty()) return null;
		for (Entry e : entries()) {
			if (e.name().equalsIgnoreCase(n)) return e;
		}
		return null;
	}

	/** Items whose name or id contains the query; names starting with it come first. */
	public static List<Entry> search(String query, int limit) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return List.of();
		List<Entry> starts = new ArrayList<>();
		List<Entry> contains = new ArrayList<>();
		for (Entry e : entries()) {
			String name = e.lowerName();
			if (name.startsWith(q)) starts.add(e);
			else if (name.contains(q) || e.id().contains(q)) contains.add(e);
		}
		starts.sort(Comparator.comparingInt((Entry e) -> e.name().length()));
		contains.sort(Comparator.comparingInt((Entry e) -> e.name().length()));
		List<Entry> result = new ArrayList<>(starts);
		result.addAll(contains);
		return result.size() > limit ? result.subList(0, limit) : result;
	}
}
