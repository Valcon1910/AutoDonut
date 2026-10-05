package com.autodonut.client.ui;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix3x2f;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Item icons drawn straight from the resource pack, for screens where item data isn't loaded yet
 * (the title screen): flat items use their sprite, blocks are drawn as a shaded isometric cube from
 * their model's top and side textures, like in an inventory.
 */
public final class OfflineIcons {
	/** One texture: first animation frame is a width x width square at the top. */
	private record Tex(Identifier id, int w, int h) { }

	/** flat != null: flat sprite; otherwise a cube of top/left/right faces. */
	private record Icon(Tex flat, Tex top, Tex left, Tex right) { }

	private static final Map<String, Icon> CACHE = new HashMap<>();
	private static final Icon NONE = new Icon(null, null, null, null);

	private static final String[] FLAT_KEYS = {"layer0", "cross", "plant"};
	private static final String[] TOP_KEYS = {"top", "up", "end", "all", "wall", "texture", "side", "particle"};
	private static final String[] LEFT_KEYS = {"front", "north", "south", "side", "all", "wall", "texture", "particle"};
	private static final String[] RIGHT_KEYS = {"side", "east", "west", "all", "wall", "texture", "particle"};

	private OfflineIcons() {
	}

	public static void draw(GuiGraphicsExtractor g, String itemId, int x, int y) {
		Icon icon = CACHE.computeIfAbsent(itemId, OfflineIcons::load);
		if (icon == NONE) return;
		if (icon.flat() != null) {
			blit(g, icon.flat(), x, y);
			return;
		}
		// Isometric cube in a 16x16 box: top rhombus, darker left face, darkest right face.
		face(g, icon.top(), x, y, new Matrix3x2f(0.5f, -0.25f, 0.5f, 0.25f, 0f, 4f), 0);
		face(g, icon.left(), x, y, new Matrix3x2f(0.5f, 0.25f, 0f, 0.5f, 0f, 4f), 0x40000000);
		face(g, icon.right(), x, y, new Matrix3x2f(0.5f, -0.25f, 0f, 0.5f, 8f, 8f), 0x70000000);
	}

	private static void face(GuiGraphicsExtractor g, Tex tex, int x, int y, Matrix3x2f m, int shade) {
		if (tex == null) return;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().mul(m);
		blit(g, tex, 0, 0);
		if (shade != 0) g.fill(0, 0, 16, 16, shade);
		g.pose().popMatrix();
	}

	/** Draws the texture's first frame at 16x16. */
	private static void blit(GuiGraphicsExtractor g, Tex t, int x, int y) {
		g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0, 0, 16, 16, t.w(), t.w(), t.w(), t.h());
	}

	private static Icon load(String itemId) {
		try {
			ResourceManager rm = Minecraft.getInstance().getResourceManager();
			int c = itemId.indexOf(':');
			String ns = c < 0 ? "minecraft" : itemId.substring(0, c);
			String path = itemId.substring(c + 1);

			// Item definition (assets/<ns>/items/<id>.json) names the model the inventory uses.
			String model = null;
			JsonObject def = json(rm, Identifier.fromNamespaceAndPath(ns, "items/" + path + ".json"));
			if (def != null) model = firstModel(def);
			if (model == null) model = ns + ":item/" + path;

			Map<String, String> textures = modelTextures(rm, model);
			if (textures.isEmpty()) textures = modelTextures(rm, ns + ":block/" + path);

			for (String key : FLAT_KEYS) {
				Tex t = tex(rm, textures, key);
				if (t != null) return new Icon(t, null, null, null);
			}
			Tex top = pick(rm, textures, TOP_KEYS);
			Tex left = pick(rm, textures, LEFT_KEYS);
			Tex right = pick(rm, textures, RIGHT_KEYS);
			if (top != null || left != null) return new Icon(null, top, left != null ? left : top, right != null ? right : left);

			Tex sprite = texture(rm, ns + ":item/" + path);
			if (sprite == null) sprite = texture(rm, ns + ":block/" + path);
			return sprite != null ? new Icon(sprite, null, null, null) : NONE;
		} catch (RuntimeException e) {
			return NONE;
		}
	}

	/** First "model" string anywhere in an item definition (handles selects, conditions, etc.). */
	private static String firstModel(JsonElement el) {
		if (el.isJsonObject()) {
			JsonObject o = el.getAsJsonObject();
			if (o.has("model") && o.get("model").isJsonPrimitive()) return o.get("model").getAsString();
			for (var e : o.entrySet()) {
				String m = firstModel(e.getValue());
				if (m != null) return m;
			}
		} else if (el.isJsonArray()) {
			for (JsonElement e : el.getAsJsonArray()) {
				String m = firstModel(e);
				if (m != null) return m;
			}
		}
		return null;
	}

	/** A model's texture variables, children overriding parents. */
	private static Map<String, String> modelTextures(ResourceManager rm, String model) {
		Map<String, String> textures = new HashMap<>();
		String current = model;
		for (int depth = 0; depth < 8 && current != null; depth++) {
			Identifier id = Identifier.parse(current.contains(":") ? current : "minecraft:" + current);
			JsonObject o = json(rm, Identifier.fromNamespaceAndPath(id.getNamespace(), "models/" + id.getPath() + ".json"));
			if (o == null) break;
			if (o.has("textures")) {
				for (var e : o.getAsJsonObject("textures").entrySet()) {
					if (e.getValue().isJsonPrimitive()) textures.putIfAbsent(e.getKey(), e.getValue().getAsString());
				}
			}
			current = o.has("parent") ? o.get("parent").getAsString() : null;
		}
		return textures;
	}

	private static Tex pick(ResourceManager rm, Map<String, String> textures, String[] keys) {
		for (String key : keys) {
			Tex t = tex(rm, textures, key);
			if (t != null) return t;
		}
		return null;
	}

	private static Tex tex(ResourceManager rm, Map<String, String> textures, String key) {
		String v = textures.get(key);
		for (int i = 0; i < 5 && v != null && v.startsWith("#"); i++) v = textures.get(v.substring(1));
		return v == null || v.startsWith("#") ? null : texture(rm, v);
	}

	/** "block/stone" -> its PNG with its pixel size, or null. */
	private static Tex texture(ResourceManager rm, String name) {
		Identifier n = Identifier.parse(name.contains(":") ? name : "minecraft:" + name);
		Identifier file = Identifier.fromNamespaceAndPath(n.getNamespace(), "textures/" + n.getPath() + ".png");
		var res = rm.getResource(file);
		if (res.isEmpty()) return null;
		try (InputStream in = res.get().open()) {
			byte[] head = in.readNBytes(24);
			if (head.length < 24) return null;
			int w = ((head[16] & 0xFF) << 24) | ((head[17] & 0xFF) << 16) | ((head[18] & 0xFF) << 8) | (head[19] & 0xFF);
			int h = ((head[20] & 0xFF) << 24) | ((head[21] & 0xFF) << 16) | ((head[22] & 0xFF) << 8) | (head[23] & 0xFF);
			if (w <= 0 || h <= 0) return null;
			return new Tex(file, w, h);
		} catch (Exception e) {
			return null;
		}
	}

	private static JsonObject json(ResourceManager rm, Identifier file) {
		var res = rm.getResource(file);
		if (res.isEmpty()) return null;
		try (var reader = res.get().openAsReader()) {
			JsonElement el = JsonParser.parseReader(reader);
			return el.isJsonObject() ? el.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}
}
