package com.autodonut.client.auction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashSet;


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
			try {
				BuiltInRegistries.ITEM.stream().forEach(item -> {
					if (item == Items.AIR) return;
					ItemStack stack = item.getDefaultInstance();
					list.add(new Entry(idOf(item), stack.getHoverName().getString(), stack));
				});
			} catch (RuntimeException e) {
				// Item data isn't bound yet (e.g. while joining a server). Try again later.
				return List.of();
			}
			list.sort(Comparator.comparing(Entry::name));
			BY_ID.clear();
			for (Entry e : list) BY_ID.put(e.id(), e);
			entries = list;
		}
		return entries;
	}

	/** Tag ids ("c:foods") of a stack, as currently sent by the server. */
	// builtInRegistryHolder() is marked deprecated by Mojang but is the supported way to read an item's tags here.
	@SuppressWarnings("deprecation")
	public static Set<String> tagsOf(ItemStack stack) {
		Set<String> tags = new HashSet<>();
		stack.getItem().builtInRegistryHolder().tags().forEach(t -> tags.add(t.location().toString()));
		return tags;
	}

	/** All item tags with one example item each; rebuilt when the server's tags change. */
	private static Map<String, ItemStack> tagExamples;

	public static void clearTags() {
		tagExamples = null;
	}

	private static Map<String, ItemStack> tagExamples() {
		if (tagExamples == null || tagExamples.isEmpty()) {
			Map<String, ItemStack> map = new TreeMap<>();
			for (Entry e : entries()) {
				for (String t : tagsOf(e.stack())) map.putIfAbsent(t, e.stack());
			}
			tagExamples = map;
		}
		return tagExamples;
	}

	/** Display entry for a rule entry: an item, or a #tag shown with an example item. */
	public static Entry entryFor(String entry) {
		if (!entry.startsWith("#")) return byId(entry);
		for (var e : tagExamples().entrySet()) {
			if (com.autodonut.client.config.AuctionRule.entryMatches(entry, "", Set.of(e.getKey()))) {
				return new Entry(entry, entry, e.getValue());
			}
		}
		return new Entry(entry, entry, ItemStack.EMPTY);
	}

	/** Tags whose id contains the query (without the leading '#'), shortest first. */
	public static List<Entry> searchTags(String query, int limit) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.startsWith("#")) q = q.substring(1);
		List<Entry> out = new ArrayList<>();
		for (var e : tagExamples().entrySet()) {
			if (q.isEmpty() || e.getKey().contains(q)) out.add(new Entry("#" + e.getKey(), "#" + e.getKey(), e.getValue()));
		}
		out.sort(Comparator.comparingInt((Entry e) -> e.id().length()));
		return out.size() > limit ? out.subList(0, limit) : out;
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
