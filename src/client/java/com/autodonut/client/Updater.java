package com.autodonut.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import com.autodonut.AutoDonut;
import com.autodonut.client.config.AutoDonutConfig;

/**
 * Checks GitHub Releases for newer AutoDonut versions, downloads the jar and swaps it in for the
 * running one. The swap finishes when the game closes, so the update takes effect on the next start.
 */
public final class Updater {
	private static final String RELEASES = "https://api.github.com/repos/Valcon1910/AutoDonut/releases?per_page=30";

	public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }

	/** One published version and its changelog. */
	public record Release(String version, String changelog, String jarUrl, String date) { }

	private static volatile State state = State.IDLE;
	private static volatile String error = "";
	private static volatile float progress;
	private static volatile List<Release> releases = List.of();
	private static volatile Release latest;
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private Updater() {
	}

	public static State state() {
		return state;
	}

	public static String error() {
		return error;
	}

	public static float progress() {
		return progress;
	}

	/** Every release, newest first. */
	public static List<Release> releases() {
		return releases;
	}

	public static Release latest() {
		return latest;
	}

	/** True when a newer version can be (or is being) installed. */
	public static boolean updateAvailable() {
		return state == State.AVAILABLE || state == State.DOWNLOADING || state == State.READY;
	}

	/** Called once on start. */
	public static void start() {
		if (AutoDonutConfig.get().checkUpdates) check();
	}

	public static void check() {
		if (state == State.CHECKING || state == State.DOWNLOADING || state == State.READY) return;
		state = State.CHECKING;
		Thread t = new Thread(() -> {
			try {
				List<Release> found = fetch();
				releases = found;
				Release newest = null;
				for (Release r : found) {
					if (r.jarUrl() != null && compare(r.version(), AutoDonutClient.version()) > 0
							&& (newest == null || compare(r.version(), newest.version()) > 0)) {
						newest = r;
					}
				}
				latest = newest;
				state = newest == null ? State.UP_TO_DATE : State.AVAILABLE;
				if (newest != null && AutoDonutConfig.get().autoUpdate) install();
			} catch (Exception e) {
				error = "Couldn't reach GitHub";
				state = State.FAILED;
				AutoDonut.LOGGER.warn("[AutoDonut] Update check failed: {}", e.toString());
			}
		}, "AutoDonut update check");
		t.setDaemon(true);
		t.start();
	}

	private static List<Release> fetch() throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(RELEASES))
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", "AutoDonut")
				.timeout(Duration.ofSeconds(15))
				.build();
		HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
		JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
		List<Release> out = new ArrayList<>();
		for (JsonElement el : arr) {
			JsonObject o = el.getAsJsonObject();
			if (o.has("draft") && o.get("draft").getAsBoolean()) continue;
			String tag = str(o, "tag_name");
			String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
			String jar = null;
			if (o.has("assets")) {
				for (JsonElement a : o.getAsJsonArray("assets")) {
					String name = str(a.getAsJsonObject(), "name").toLowerCase(Locale.ROOT);
					if (name.endsWith(".jar") && !name.contains("sources") && !name.contains("dev")) {
						jar = str(a.getAsJsonObject(), "browser_download_url");
						break;
					}
				}
			}
			String date = str(o, "published_at");
			if (date.length() >= 10) date = date.substring(0, 10);
			out.add(new Release(version, str(o, "body").replace("\r", ""), jar, date));
		}
		out.sort((a, b) -> compare(b.version(), a.version()));
		return out;
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	/** Compares dotted version numbers ("1.2.10" > "1.2.9"); anything non-numeric counts as 0. */
	public static int compare(String a, String b) {
		String[] pa = a.split("[.+-]");
		String[] pb = b.split("[.+-]");
		for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
			int x = i < pa.length ? num(pa[i]) : 0;
			int y = i < pb.length ? num(pb[i]) : 0;
			if (x != y) return Integer.compare(x, y);
		}
		return 0;
	}

	private static int num(String s) {
		try {
			return Integer.parseInt(s.replaceAll("\\D", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** The jar AutoDonut is running from, or null in a development setup. */
	private static Path currentJar() {
		return FabricLoader.getInstance().getModContainer(AutoDonut.MOD_ID)
				.flatMap(c -> c.getOrigin().getPaths().stream().findFirst())
				.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
				.orElse(null);
	}

	/** Downloads the newest jar; it replaces the current one when the game closes. */
	public static void install() {
		Release target = latest;
		if (target == null || state == State.DOWNLOADING || state == State.READY) return;
		Path current = currentJar();
		if (current == null) {
			error = "Updates only install from the mod jar";
			state = State.FAILED;
			return;
		}
		state = State.DOWNLOADING;
		progress = 0;
		Thread t = new Thread(() -> {
			Path mods = current.getParent();
			// ".pending" keeps Fabric from loading both jars before the swap is done.
			Path pending = mods.resolve("autodonut-" + target.version() + ".jar.pending");
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(target.jarUrl()))
						.header("User-Agent", "AutoDonut")
						.timeout(Duration.ofMinutes(2))
						.build();
				HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
				if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
				long total = res.headers().firstValueAsLong("Content-Length").orElse(-1);
				try (InputStream in = res.body(); var out = Files.newOutputStream(pending)) {
					byte[] buf = new byte[16384];
					long done = 0;
					int n;
					while ((n = in.read(buf)) > 0) {
						out.write(buf, 0, n);
						done += n;
						if (total > 0) progress = (float) done / total;
					}
				}
				if (Files.size(pending) < 1024) throw new IOException("Download is empty");
				scheduleSwap(current, pending, mods.resolve("autodonut-" + target.version() + ".jar"));
				progress = 1;
				state = State.READY;
			} catch (Exception e) {
				try {
					Files.deleteIfExists(pending);
				} catch (IOException ignored) {
				}
				error = "Download failed";
				state = State.FAILED;
				AutoDonut.LOGGER.warn("[AutoDonut] Update download failed: {}", e.toString());
			}
		}, "AutoDonut update download");
		t.setDaemon(true);
		t.start();
	}

	private static boolean swapScheduled;

	/**
	 * Swaps the jars when the game shuts down. Windows keeps the running jar locked until the
	 * game process ends, so there a small background command waits for that before swapping.
	 */
	private static synchronized void scheduleSwap(Path current, Path pending, Path target) {
		if (swapScheduled) return;
		swapScheduled = true;
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				if (windows) {
					long pid = ProcessHandle.current().pid();
					String script = "while (Get-Process -Id " + pid + " -ErrorAction SilentlyContinue) { Start-Sleep -Milliseconds 300 }; "
							+ "Remove-Item -LiteralPath '" + ps(current) + "' -Force; "
							+ "Move-Item -LiteralPath '" + ps(pending) + "' -Destination '" + ps(target) + "' -Force";
					new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script).start();
				} else {
					Files.deleteIfExists(current);
					Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (Exception e) {
				AutoDonut.LOGGER.warn("[AutoDonut] Update swap failed: {}", e.toString());
			}
		}, "AutoDonut update swap"));
	}

	private static String ps(Path p) {
		return p.toAbsolutePath().toString().replace("'", "''");
	}
}
