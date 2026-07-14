package com.pbtracker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads boss personal best times that RuneLite's built-in Chat Commands
 * plugin already tracks (RSProfile config group "personalbest") and syncs
 * them to a custom backend so they can be shown on a public leaderboard
 * website, looked up by player name.
 * <p>
 * Three sync paths:
 *  - Live: on every ConfigChanged event in the "personalbest" group (i.e.
 *    the moment you get a new PB), we push just that one value.
 *  - Bulk: on login (and via the "Sync all PBs now" checkbox in the plugin's
 *    own Configuration panel) we ask ConfigManager for every key that
 *    actually exists under the "personalbest" group for this account and
 *    push all of them, rather than matching against a hardcoded boss name
 *    list. This is what fixes bosses silently going missing due to a name
 *    not matching RuneLite's internal key exactly.
 *  - Adventure Log: RuneLite's core Chat Commands plugin captures data from
 *    your Adventure Log's Counters page too, but it collapses "fastest room
 *    time" records into the same key as full-completion times, which is
 *    ambiguous. We separately read that same in-game page ourselves so we
 *    can label "fastest room" times distinctly (e.g. "Theatre of Blood -
 *    Fastest Room") instead of losing that distinction.
 */
@Slf4j
@PluginDescriptor(
	name = "PB Tracker Sync",
	description = "Reads your boss personal best times and syncs them to a custom PB leaderboard website",
	tags = {"pb", "personal best", "boss", "records", "leaderboard"}
)
public class PbTrackerPlugin extends Plugin
{
	private static final String CONFIG_GROUP = "personalbest";

	// Our own plugin's config group (matches @ConfigGroup on PbTrackerConfig) -
	// used to persist a per-install secret that isn't exposed as a visible
	// setting.
	private static final String SETTINGS_GROUP = "pbtracker";
	private static final String INSTALL_SECRET_KEY = "installSecret";
	private static final String SYNC_NOW_KEY = "syncNow";
	private static final String SYNC_STATUS_KEY = "syncStatus";
	private static final String DUMP_RAW_KEY = "dumpRawPbs";
	private static final String OPEN_PROFILE_KEY = "openProfile";
	private static final String PROFILE_SITE_URL = "https://osrs-pb-tracker-frontend.vercel.app";
	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	// Matches any "Fastest <descriptor>: <value>" line on the Adventure Log
	// Counters page, e.g. "Fastest kill: 3:34", "Fastest run: -",
	// "Fastest Room time - (Team size: 3 player): 18:34". The descriptor is
	// classified afterwards rather than baked into the regex, since the exact
	// wording (kill/run/Room time/Overall time/Wave time, plus inconsistent
	// parenthesis usage) varies more than expected.
	private static final Pattern RECORD_PATTERN = Pattern.compile(
		"^Fastest (?<descriptor>.+): (?<value>-|[0-9:]+(?:\\.[0-9]+)?)$"
	);

	// Matches the Journalscroll TITLE widget's text (distinct from the
	// TEXTLAYER widget the Counters records themselves live in). Confirmed
	// live: on the Counters sub-page specifically it's a bare account name
	// ("Blitzen", or a friend's "Dad" when reading their POH copy) - the
	// "The Exploits of <name>" form is what the Adventure Log's main MENU
	// page shows instead, kept here defensively in case that page is ever
	// reached through this same code path.
	private static final Pattern JOURNAL_TITLE_PATTERN = Pattern.compile("^(?:The Exploits of )?(?<name>.+)$");

	// Generic page labels the Adventure Log itself uses (its own menu entries
	// plus the Counters page's own un-personalized heading) - if the TITLE
	// widget ever reads one of these instead of an actual account name, that
	// is NOT evidence the log belongs to someone else, so this must not
	// block syncing over it. Addresses the concern that any unrecognized
	// nonempty title would otherwise be treated as a mismatched owner.
	private static final Set<String> GENERIC_JOURNAL_TITLES = new HashSet<>(Arrays.asList(
		"counters", "adventure log", "slayer log", "boss log", "champions log", "collection log"
	));

	// A few activities are stored under a different internal name in
	// RuneLite's raw "personalbest" config than the heading the Adventure Log
	// actually displays for them, so they'd otherwise show up as duplicate
	// rows under two different labels for the same record. Live/bulk sync
	// skips the raw key and waits for the Adventure Log parser to supply the
	// properly-labelled version instead - mapped here to the heading text so
	// we can tell whether that's actually happened yet, and nudge the user to
	// open the Adventure Log if not (see syncedAdventureLogHeadings below).
	private static final Map<String, String> KNOWN_DUPLICATE_RAW_KEYS = new HashMap<>();
	static
	{
		KNOWN_DUPLICATE_RAW_KEYS.put("tztok-jad", "TzHaar Fight Cave");
		KNOWN_DUPLICATE_RAW_KEYS.put("tzkal-zuk", "Inferno");
		KNOWN_DUPLICATE_RAW_KEYS.put("sol heredit", "Fortis Colosseum");
		KNOWN_DUPLICATE_RAW_KEYS.put("hueycoatl", "The Hueycoatl");
		KNOWN_DUPLICATE_RAW_KEYS.put("gauntlet", "The Gauntlet");
		KNOWN_DUPLICATE_RAW_KEYS.put("corrupted gauntlet", "The Corrupted Gauntlet");
		KNOWN_DUPLICATE_RAW_KEYS.put("nightmare", "The Nightmare");
	}

	private static final String PBR_COMMAND_STRING = "!pbr";

	// Explicit colors for !pbr's chat output rather than ChatColorType.NORMAL/
	// HIGHLIGHT - those defer to the player's own configured chat colors,
	// which on some setups render nearly identically (e.g. both a similar
	// blue), making the response hard to read at a glance. These give three
	// clearly distinct colors regardless of client theme - white for labels,
	// the PB Tracker site's own gold for the time, green for rank.
	private static final Color PBR_LABEL_COLOR = new Color(255, 255, 255);
	private static final Color PBR_TIME_COLOR = new Color(255, 152, 31);
	private static final Color PBR_RANK_COLOR = new Color(0, 200, 83);
	private static final Color PBR_ERROR_COLOR = new Color(255, 255, 255);

	// Matches the part of a synced boss key after the raid's bare prefix has
	// been stripped, e.g. for "theatre of blood - hard - fastest overall (4
	// player hard mode)" this matches against " - hard - fastest overall (4
	// player hard mode)", capturing mode="hard" and paren="4 player hard
	// mode". The mode group is optional since Normal-mode entries skip
	// straight to " - fastest overall (...)" with no mode segment at all.
	// Deliberately only matches "fastest overall" (not "room"/"wave") -
	// that's the single completion time a player means by "record" when
	// they ask for a specific team size.
	private static final Pattern OVERALL_LABEL_PATTERN = Pattern.compile(
		"^ - (?:(?<mode>.+?) - )?fastest overall \\((?<paren>.+)\\)$"
	);

	// Common shorthand -> the exact lowercase boss key the backend stores
	// (i.e. what buildKey()/canonicalBossKey() above actually produce, once
	// lowercased server-side). Anything not listed here just falls through
	// to the raw, lowercased argument as typed, so "!pbr zulrah" or "!pbr
	// vorkath" work without needing an entry - this map only exists for
	// content whose stored key doesn't match what a player would naturally
	// type (raid abbreviations, "the X" bosses, etc).
	static final Map<String, String> BOSS_ALIASES = new HashMap<>();
	static
	{
		BOSS_ALIASES.put("tob", "theatre of blood");
		BOSS_ALIASES.put("cox", "chambers of xeric");
		BOSS_ALIASES.put("toa", "tombs of amascut");
		BOSS_ALIASES.put("jad", "tzhaar fight cave");
		BOSS_ALIASES.put("fc", "tzhaar fight cave");
		BOSS_ALIASES.put("fightcave", "tzhaar fight cave");
		BOSS_ALIASES.put("fightcaves", "tzhaar fight cave");
		BOSS_ALIASES.put("fight caves", "tzhaar fight cave");
		BOSS_ALIASES.put("zuk", "inferno");
		BOSS_ALIASES.put("colo", "fortis colosseum");
		BOSS_ALIASES.put("colosseum", "fortis colosseum");
		BOSS_ALIASES.put("sol", "fortis colosseum");
		BOSS_ALIASES.put("gaunt", "the gauntlet");
		BOSS_ALIASES.put("gauntlet", "the gauntlet");
		BOSS_ALIASES.put("cgaunt", "the corrupted gauntlet");
		BOSS_ALIASES.put("cg", "the corrupted gauntlet");
		BOSS_ALIASES.put("corrupted gauntlet", "the corrupted gauntlet");
		BOSS_ALIASES.put("nm", "the nightmare");
		BOSS_ALIASES.put("nightmare", "the nightmare");
		BOSS_ALIASES.put("pnm", "phosani's nightmare");
		BOSS_ALIASES.put("phosani", "phosani's nightmare");
		BOSS_ALIASES.put("phosanis", "phosani's nightmare");
		BOSS_ALIASES.put("huey", "the hueycoatl");
		BOSS_ALIASES.put("hueycoatl", "the hueycoatl");
		BOSS_ALIASES.put("levi", "leviathan");
		BOSS_ALIASES.put("whisp", "whisperer");
		BOSS_ALIASES.put("wisp", "whisperer");
		BOSS_ALIASES.put("duke", "duke sucellus");
		BOSS_ALIASES.put("vard", "vardorvis");
	}

	// Shorthand that names a specific raid *mode*, not just the raid itself
	// (e.g. "hmt" = Hard Mode Theatre of Blood). Each entry is {raid prefix,
	// required mode} - unlike BOSS_ALIASES, resolving one of these means the
	// match must come from that exact mode, not just prefer it when
	// ambiguous. Add more here as needed (e.g. a Challenge Mode CoX or
	// Entry Mode ToB shorthand) - same shape.
	private static final Map<String, String[]> MODE_SPECIFIC_ALIASES = new HashMap<>();
	static
	{
		MODE_SPECIFIC_ALIASES.put("hmt", new String[] { "theatre of blood", "hard" });
		MODE_SPECIFIC_ALIASES.put("cm", new String[] { "chambers of xeric", "challenge mode" });
	}

	@Inject
	private Client client;

	@Inject
	private PbTrackerConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private SyncClient syncClient;

	@Inject
	private ChatCommandManager chatCommandManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private net.runelite.client.ui.ClientToolbar clientToolbar;

	@Inject
	private net.runelite.client.game.SpriteManager spriteManager;

	private PbTrackerSidePanel sidePanel;
	private net.runelite.client.ui.NavigationButton navButton;

	private String accountHash;
	private String installSecret;
	private boolean journalScrollLoaded;

	// Adventure Log headings (lowercased) we've successfully parsed a record
	// for this session - lets us tell whether a KNOWN_DUPLICATE_RAW_KEYS boss
	// has actually been synced yet, so we can nudge the user instead of
	// silently dropping it forever.
	private final Set<String> syncedAdventureLogHeadings = new LinkedHashSet<>();

	@Provides
	PbTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PbTrackerConfig.class);
	}

	@Override
	protected void startUp()
	{
		installSecret = getOrCreateInstallSecret();
		chatCommandManager.registerCommandAsync(PBR_COMMAND_STRING, this::pbrLookup);

		sidePanel = new PbTrackerSidePanel(syncClient, spriteManager);
		navButton = net.runelite.client.ui.NavigationButton.builder()
			.tooltip("PB Tracker")
			.icon(buildNavIcon())
			.priority(5)
			.panel(sidePanel)
			.build();
		clientToolbar.addNavigation(navButton);

		refreshBossList();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			onLoggedIn();
		}
	}

	/**
	 * The nav button needs some icon; drawn at runtime (a gold "PB" on a dark
	 * square, matching the website's own accent color) rather than shipping
	 * a separate image asset for something this small.
	 */
	private static java.awt.image.BufferedImage buildNavIcon()
	{
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(24, 24, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = image.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x1f, 0x19, 0x12));
		g.fillRoundRect(0, 0, 24, 24, 6, 6);
		g.setColor(new Color(0xFF, 0x98, 0x1F));
		g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 10));
		java.awt.FontMetrics metrics = g.getFontMetrics();
		String text = "PB";
		int x = (24 - metrics.stringWidth(text)) / 2;
		int y = (24 - metrics.getHeight()) / 2 + metrics.getAscent();
		g.drawString(text, x, y);
		g.dispose();
		return image;
	}

	/** Fetched once on startup - the side panel's Bosses tab needs the full boss key list to build its picker. */
	private void refreshBossList()
	{
		executor.execute(() ->
		{
			List<String> bosses = syncClient.getBosses();
			javax.swing.SwingUtilities.invokeLater(() -> sidePanel.setBosses(bosses));
		});
	}

	/**
	 * RuneLite gives plugins no way to prove account identity to a
	 * third-party server, so this doesn't authenticate the player - it's a
	 * per-install credential the backend uses to bind (on first sync) and
	 * verify (on every sync after) that updates for a given account are
	 * coming from the same install, rather than accepting anyone who guesses
	 * or observes that account's hash.
	 */
	private String getOrCreateInstallSecret()
	{
		String existing = configManager.getConfiguration(SETTINGS_GROUP, INSTALL_SECRET_KEY);
		if (existing != null && !existing.isEmpty())
		{
			return existing;
		}

		byte[] randomBytes = new byte[32];
		new SecureRandom().nextBytes(randomBytes);
		String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
		configManager.setConfiguration(SETTINGS_GROUP, INSTALL_SECRET_KEY, generated);
		return generated;
	}

	@Override
	protected void shutDown()
	{
		chatCommandManager.unregisterCommand(PBR_COMMAND_STRING);
		clientToolbar.removeNavigation(navButton);
	}

	/**
	 * Everything the plugin has to say lives in its Configuration panel
	 * rather than a separate sidebar panel - this just persists the given
	 * text as the read-only-ish "Last sync status" field.
	 */
	private void setStatus(String text)
	{
		configManager.setConfiguration(SETTINGS_GROUP, SYNC_STATUS_KEY, text);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			onLoggedIn();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN && sidePanel != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> sidePanel.onLocalPlayerChanged(null));
		}
	}

	private void onLoggedIn()
	{
		accountHash = String.valueOf(client.getAccountHash());
		if (config.syncOnLogin())
		{
			executor.schedule(this::syncAll, 5, TimeUnit.SECONDS);
		}
		if (sidePanel != null)
		{
			loadLocalPlayerPanelWhenReady(0);
		}
	}

	private void loadLocalPlayerPanelWhenReady(int attempt)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null && localPlayer.getName() != null)
		{
			String displayName = localPlayer.getName();
			log.debug("Loading My PBs sidebar for {} on attempt {}", displayName, attempt + 1);
			javax.swing.SwingUtilities.invokeLater(() -> sidePanel.onLocalPlayerChanged(displayName));
			return;
		}
		if (attempt < 4)
		{
			executor.schedule(() -> loadLocalPlayerPanelWhenReady(attempt + 1), 1, TimeUnit.SECONDS);
		}
	}

	/**
	 * Adds a "Search PB" right-click option on other players,
	 * matching RuneLite's own built-in hiscore lookup plugin (see
	 * HiscorePlugin.onMenuEntryAdded, which this mirrors) - covers players
	 * in the world/nearby list (anchored on "Follow", present on every
	 * player's menu there) as well as names in Friends, Friends Chat, the
	 * chatbox, the ignore list, and private messages (anchored on whichever
	 * option those widgets already show, since they're text entries with no
	 * attached Player object to check).
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.rightClickLookup() || sidePanel == null)
		{
			return;
		}

		String option = event.getOption();
		MenuEntry entry = event.getMenuEntry();

		if ("Follow".equals(option))
		{
			Player player = entry.getPlayer();
			if (player != null && player.getName() != null)
			{
				addLookupMenuEntry(entry, player.getName());
			}
			return;
		}

		if (event.getType() != MenuAction.CC_OP.getId() && event.getType() != MenuAction.CC_OP_LOW_PRIORITY.getId())
		{
			return;
		}

		int componentId = event.getActionParam1();
		int groupId = net.runelite.api.widgets.WidgetUtil.componentToInterface(componentId);

		boolean isPlayerNameMenu =
			groupId == InterfaceID.FRIENDS && option.equals("Delete")
				|| groupId == InterfaceID.CHATCHANNEL_CURRENT && (option.equals("Add ignore") || option.equals("Remove friend"))
				|| groupId == InterfaceID.CHATBOX && (option.equals("Add ignore") || option.equals("Message"))
				|| groupId == InterfaceID.IGNORE && option.equals("Delete")
				|| groupId == InterfaceID.PM_CHAT && (option.equals("Add ignore") || option.equals("Message"));

		if (!isPlayerNameMenu)
		{
			return;
		}

		addLookupMenuEntry(entry, Text.removeTags(entry.getTarget()));
	}

	private void addLookupMenuEntry(MenuEntry entry, String playerName)
	{
		client.getMenu().createMenuEntry(-1)
			.setOption("Search PB")
			.setTarget(entry.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> javax.swing.SwingUtilities.invokeLater(() -> sidePanel.lookupPlayer(playerName)));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (SETTINGS_GROUP.equals(event.getGroup()))
		{
			// The config panel has no notion of a "button" - toggling a
			// checkbox to trigger an action is the usual RuneLite idiom for
			// this. We deliberately don't reset it back programmatically:
			// the config panel doesn't repaint from a programmatic change
			// until the panel is closed and reopened, which made the box
			// look "stuck" checked. Triggering on either direction of the
			// toggle avoids that entirely - whatever you clicked is what's
			// actually stored, so there's nothing for the UI to get stale on.
			if (SYNC_NOW_KEY.equals(event.getKey()) && shouldTriggerSyncNow(event.getNewValue()))
			{
				executor.execute(this::syncAll);
			}
			else if (DUMP_RAW_KEY.equals(event.getKey()) && shouldTriggerSyncNow(event.getNewValue()))
			{
				executor.execute(this::dumpRawPersonalBests);
			}
			else if (OPEN_PROFILE_KEY.equals(event.getKey()) && shouldTriggerSyncNow(event.getNewValue()))
			{
				executor.execute(this::openProfile);
			}
			return;
		}

		if (!config.autoSync() || !CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		String boss = event.getKey();
		String rawValue = event.getNewValue();

		if (rawValue == null)
		{
			return;
		}

		if (!shouldSyncRawPersonalBest(boss))
		{
			// Raid/team-size records (e.g. "chambers of xeric 2 players") get
			// synced with proper Room/Overall labels from the Adventure Log
			// parser instead - skip the raw, differently-named version here
			// so the two don't show up as duplicate entries on the site.
			String heading = KNOWN_DUPLICATE_RAW_KEYS.get(boss.toLowerCase());
			if (heading != null && !syncedAdventureLogHeadings.contains(heading.toLowerCase()))
			{
				setStatus("New " + heading + " PB detected but not synced yet - open Adventure Log > Counters once to sync it.");
			}
			return;
		}

		try
		{
			double seconds = Double.parseDouble(rawValue);
			Map<String, Double> single = new HashMap<>();
			single.put(canonicalBossKey(boss), seconds);
			syncPbs(single);
		}
		catch (NumberFormatException ex)
		{
			log.debug("Ignoring non-numeric personalbest value for {}: {}", boss, rawValue);
		}
	}

	/**
	 * True for any raw personalbest key that's better sourced from the
	 * Adventure Log parser instead - either because it's a raid/team-size
	 * variant (e.g. "chambers of xeric 2 players", "theatre of blood entry
	 * mode solo"), which gets proper Room/Overall labels there, or because
	 * it's one of the few activities stored under a different internal name
	 * than the Adventure Log's own heading for it.
	 */
	static boolean shouldSyncRawPersonalBest(String key)
	{
		return !looksLikeRaidVariant(key);
	}

	private static boolean looksLikeRaidVariant(String key)
	{
		String lower = key.toLowerCase();
		if (KNOWN_DUPLICATE_RAW_KEYS.containsKey(lower))
		{
			return true;
		}

		return lower.matches("^(chambers of xeric|theatre of blood|tombs of amascut)(?: .*)? (solo|\\d+ players|\\d\\+ players|\\d+-\\d+ players)$")
			|| lower.matches("^chambers of xeric challenge mode (solo|\\d+ players|\\d\\+ players|\\d+-\\d+ players)$")
			|| lower.matches("^theatre of blood (entry mode|hard mode) (solo|\\d+ players)$")
			|| lower.matches("^tombs of amascut (entry mode|expert mode) (solo|\\d+ players)$")
			|| lower.matches("^nightmare (solo|\\d+ players|\\d\\+ players|\\d+-\\d+ players)$");
	}

	static boolean shouldTriggerSyncNow(String newValue)
	{
		return newValue != null;
	}

	static String canonicalBossKey(String key)
	{
		String lower = key.toLowerCase();
		switch (lower)
		{
			case "the leviathan":
			case "levi":
				return "Leviathan";
			case "duke":
				return "Duke Sucellus";
			case "the whisperer":
			case "whisp":
			case "wisp":
				return "Whisperer";
			case "vard":
				return "Vardorvis";
			case "leviathan awakened":
			case "the leviathan awakened":
			case "levi awakened":
				return "Leviathan (awakened)";
			case "duke sucellus awakened":
			case "duke awakened":
				return "Duke Sucellus (awakened)";
			case "whisperer awakened":
			case "the whisperer awakened":
			case "whisp awakened":
			case "wisp awakened":
				return "Whisperer (awakened)";
			case "vardorvis awakened":
			case "vard awakened":
				return "Vardorvis (awakened)";
			default:
				return key;
		}
	}

	/**
	 * Resolves what a player types after "!pbr" (e.g. "tob", "ToB", "Zulrah")
	 * to the exact lowercase boss key the backend stores it under. Falls
	 * back to the trimmed, lowercased input unchanged when there's no
	 * shorthand entry for it, so any boss's real name always works even if
	 * it's not in BOSS_ALIASES.
	 */
	static String resolveBossAlias(String input)
	{
		String normalized = input.trim().toLowerCase();
		return BOSS_ALIASES.getOrDefault(normalized, normalized);
	}

	/**
	 * Same formatting as the website's formatTime() (frontend/src/lib/format.ts)
	 * so a time read out in chat matches what's shown on the leaderboard.
	 */
	static String formatTime(double totalSeconds)
	{
		long h = (long) (totalSeconds / 3600);
		long m = (long) ((totalSeconds % 3600) / 60);
		double s = totalSeconds % 60;
		boolean hasFraction = Math.abs(s - Math.round(s)) > 0.001;
		String secStr = hasFraction
			? String.format("%05.2f", s)
			: String.format("%02d", Math.round(s));

		if (h > 0)
		{
			return h + ":" + String.format("%02d", m) + ":" + secStr;
		}
		return m + ":" + secStr;
	}

	/**
	 * Same as the website's titleCase() (frontend/src/lib/format.ts) - used
	 * so !pbr's response names the exact record it's showing (e.g. "Theatre
	 * Of Blood - Hard - Fastest Overall (4 Player Hard Mode)") instead of a
	 * generic "Personal best" with no label.
	 */
	static String titleCase(String str)
	{
		StringBuilder result = new StringBuilder(str.length());
		boolean capitalizeNext = true;
		for (int i = 0; i < str.length(); i++)
		{
			char c = str.charAt(i);
			if (Character.isWhitespace(c))
			{
				capitalizeNext = true;
				result.append(c);
			}
			else if (Character.isLetterOrDigit(c))
			{
				result.append(capitalizeNext ? Character.toUpperCase(c) : c);
				capitalizeNext = false;
			}
			else
			{
				// Punctuation like "(" or "'" - matches the website's
				// titleCase() (a regex that finds each "word start"): it
				// doesn't consume a pending capitalization, so "(former)"
				// correctly becomes "(Former)" instead of "(former)" with
				// the capitalization wasted on "(" itself.
				result.append(c);
			}
		}
		return result.toString();
	}

	/**
	 * JTextArea's word-wrap only breaks at whitespace, not at hyphens - a
	 * long hyphenated compound like "Tzhaar-Ket-Rak's" has no spaces at all
	 * in its first 16 characters, so on the sidebar's narrow width it
	 * doesn't fit on one line and gets forced into an ugly mid-syllable
	 * character break ("Tzhaar-ke" / "t-rak's") instead. Inserting a space
	 * after each hyphen gives the wrapper a natural place to break instead.
	 */
	static String wrapFriendly(String text)
	{
		return text.replace("-", "- ");
	}

	/**
	 * Swing delivers a mouse click to whichever component is directly under
	 * the cursor, not to its ancestors - there's no automatic bubbling like
	 * in a browser DOM. A listener attached only to a row's outer container
	 * therefore only fires when a click lands on a sliver of uncovered
	 * background; since icon/text/label children typically fill nearly the
	 * whole row, most clicks on a "clickable row" were silently doing
	 * nothing. Attaching the same listener to every descendant fixes that.
	 */
	static void addRowClickListener(java.awt.Component component, Runnable onClick)
	{
		component.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		component.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				onClick.run();
			}
		});
		if (component instanceof java.awt.Container)
		{
			for (java.awt.Component child : ((java.awt.Container) component).getComponents())
			{
				addRowClickListener(child, onClick);
			}
		}
	}

	// Explicit mode keyword -> the exact mode text OVERALL_LABEL_PATTERN
	// captures for it, for the "<boss> <mode> [size]" form (e.g. "tob entry
	// 2", "cox challenge 3"). Separate from MODE_SPECIFIC_ALIASES, which is
	// for single-word shortcuts naming both the raid and mode at once
	// ("hmt", "cm") - this is for spelling the mode out after the boss.
	private static final Map<String, String> MODE_KEYWORDS = new HashMap<>();
	static
	{
		MODE_KEYWORDS.put("entry", "entry");
		MODE_KEYWORDS.put("hard", "hard");
		MODE_KEYWORDS.put("expert", "expert");
		MODE_KEYWORDS.put("challenge", "challenge mode");
	}

	/**
	 * Splits a !pbr argument into the boss part, an optional trailing
	 * team-size token (a plain number, or "solo"), and an optional mode
	 * keyword (entry/hard/expert/challenge) that comes right before the
	 * size, if any - e.g. "tob entry 2" -> ("tob", "2", "entry"), "tob 4" ->
	 * ("tob", "4", null), "fight caves" -> ("fight caves", null, null). Only
	 * the trailing tokens are ever consumed this way, so multi-word boss
	 * names/aliases aren't mistaken for a size or mode.
	 */
	static String[] splitBossSizeAndMode(String rawArgument)
	{
		String[] tokens = rawArgument.trim().split("\\s+");
		int end = tokens.length;
		String sizeArg = null;
		String modeArg = null;

		if (end > 1)
		{
			String last = tokens[end - 1].toLowerCase();
			if (last.matches("\\d+") || last.equals("solo"))
			{
				sizeArg = last;
				end--;
			}
		}

		if (end > 1)
		{
			String mapped = MODE_KEYWORDS.get(tokens[end - 1].toLowerCase());
			if (mapped != null)
			{
				modeArg = mapped;
				end--;
			}
		}

		String bossPart = String.join(" ", Arrays.copyOfRange(tokens, 0, end));
		return new String[] { bossPart, sizeArg, modeArg };
	}

	private static boolean parenMatchesSize(String paren, String sizeArg)
	{
		if ("solo".equals(sizeArg))
		{
			return paren.equals("solo") || paren.startsWith("solo ");
		}
		return paren.equals(sizeArg + " player") || paren.startsWith(sizeArg + " player");
	}

	/**
	 * Picks which synced PB entry !pbr should report for a resolved boss key,
	 * optional team size, and optional mode.
	 * <p>
	 * requiredMode is null unless the player asked for a specific mode
	 * (either via a MODE_SPECIFIC_ALIASES shorthand like "hmt"/"cm", or the
	 * explicit "<boss> <mode> [size]" form like "tob entry 2"). Whichever it
	 * is - null or a specific mode - only entries with THAT EXACT mode
	 * segment are considered; there's no falling back to a different mode
	 * than what was asked for. null specifically means "no mode segment at
	 * all", i.e. true Normal mode - not "any mode", so "!pbr tob 1" (Normal
	 * ToB has no solo/duo size) returns null rather than silently
	 * substituting Entry mode's "1 player" record.
	 * <p>
	 * Only when NEITHER a size NOR a mode was given at all (a bare "!pbr
	 * <boss>") does this fall back further: first to the single fastest
	 * Normal-mode "Fastest Overall" entry across every team size, and only
	 * if there's none of those either, to an exact match on the bare boss
	 * key (non-raid bosses, or a raid the player only has a legacy/
	 * unlabeled record for).
	 */
	static SyncClient.PbEntryDto findPbrMatch(List<SyncClient.PbEntryDto> pbs, String boss, String sizeArg, String requiredMode)
	{
		List<SyncClient.PbEntryDto> candidates = new ArrayList<>();

		for (SyncClient.PbEntryDto pb : pbs)
		{
			if (pb.boss == null || !pb.boss.startsWith(boss))
			{
				continue;
			}

			Matcher matcher = OVERALL_LABEL_PATTERN.matcher(pb.boss.substring(boss.length()));
			if (!matcher.matches())
			{
				continue;
			}

			if (sizeArg != null && !parenMatchesSize(matcher.group("paren"), sizeArg))
			{
				continue;
			}

			String mode = matcher.group("mode");
			boolean modeMatches = requiredMode == null ? mode == null : mode != null && mode.equalsIgnoreCase(requiredMode);
			if (!modeMatches)
			{
				continue;
			}

			candidates.add(pb);
		}

		SyncClient.PbEntryDto best = fastest(candidates);
		if (best != null)
		{
			return best;
		}

		if (sizeArg != null || requiredMode != null)
		{
			return null;
		}

		for (SyncClient.PbEntryDto pb : pbs)
		{
			if (pb.boss != null && pb.boss.equalsIgnoreCase(boss))
			{
				return pb;
			}
		}
		return null;
	}

	private static SyncClient.PbEntryDto fastest(List<SyncClient.PbEntryDto> candidates)
	{
		if (candidates.isEmpty())
		{
			return null;
		}
		SyncClient.PbEntryDto fastest = candidates.get(0);
		for (SyncClient.PbEntryDto pb : candidates)
		{
			if (pb.timeSeconds < fastest.timeSeconds)
			{
				fastest = pb;
			}
		}
		return fastest;
	}

	/**
	 * Chat command handler for "!pbr <boss>[ <size>]" - looks up the local
	 * player's synced personal best and leaderboard rank for that boss (and,
	 * for raids, optionally a specific team size) from the PB tracker
	 * backend and prints it in chat. Registered via registerCommandAsync,
	 * which RuneLite already runs off the client thread, so the blocking
	 * SyncClient.lookupPlayer() call below is safe here the same way
	 * RuneLite's own built-in "!lvl" hiscore lookup does a blocking network
	 * call directly in its handler.
	 */
	private void pbrLookup(ChatMessage chatMessage, String message)
	{
		if (!config.pbrCommand())
		{
			return;
		}

		if (message.length() <= PBR_COMMAND_STRING.length())
		{
			respondPbr(chatMessage, "Usage: !pbr <boss> [mode] [team size], e.g. !pbr tob, !pbr tob 4, !pbr tob entry 2, or !pbr hmt");
			return;
		}

		String rawArgument = message.substring(PBR_COMMAND_STRING.length() + 1).trim();
		if (rawArgument.isEmpty())
		{
			respondPbr(chatMessage, "Usage: !pbr <boss> [mode] [team size], e.g. !pbr tob, !pbr tob 4, !pbr tob entry 2, or !pbr hmt");
			return;
		}

		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			respondPbr(chatMessage, "Not logged in yet.");
			return;
		}

		String[] parsed = splitBossSizeAndMode(rawArgument);
		String bossArg = parsed[0];
		String sizeArg = parsed[1];
		String parsedMode = parsed[2];

		String[] modeAlias = MODE_SPECIFIC_ALIASES.get(bossArg.trim().toLowerCase());
		String boss = modeAlias != null ? modeAlias[0] : resolveBossAlias(bossArg);
		String requiredMode = modeAlias != null ? modeAlias[1] : parsedMode;

		// A chat command handler runs independently on every client that sees
		// the message, not just the one who typed it - client.getLocalPlayer()
		// here would always be "whoever is viewing this", making !pbr show
		// the VIEWER's own PB for everyone else's command too. The message's
		// own sender name is what actually identifies whose PB to look up.
		String playerName = Text.removeTags(chatMessage.getName());

		SyncClient.PlayerLookupResult result = syncClient.lookupPlayer(playerName);
		switch (result.kind)
		{
			case NOT_FOUND:
				respondPbr(chatMessage, "No synced PB data found for " + playerName + " yet.");
				return;
			case AMBIGUOUS:
				respondPbr(chatMessage, "Multiple synced accounts share this name - check the website directly.");
				return;
			case ERROR:
				respondPbr(chatMessage, "PB Tracker lookup failed - try again later.");
				return;
			case FOUND:
				break;
		}

		SyncClient.PbEntryDto match = findPbrMatch(result.player.pbs, boss, sizeArg, requiredMode);
		if (match == null)
		{
			String label = sizeArg != null ? (titleCase(bossArg) + " (" + sizeArg + ")") : titleCase(bossArg);
			respondPbr(chatMessage, "No personal best recorded for " + label + ".");
			return;
		}

		String response = new ChatMessageBuilder()
			.append(PBR_LABEL_COLOR, titleCase(match.boss) + " personal best: ")
			.append(PBR_TIME_COLOR, formatTime(match.timeSeconds))
			.append(PBR_LABEL_COLOR, "  Rank: ")
			.append(PBR_RANK_COLOR, "#" + match.rank)
			.build();

		MessageNode messageNode = chatMessage.getMessageNode();
		messageNode.setRuneLiteFormatMessage(response);
		client.refreshChat();
	}

	private void respondPbr(ChatMessage chatMessage, String text)
	{
		String formatted = new ChatMessageBuilder()
			.append(PBR_ERROR_COLOR, text)
			.build();
		MessageNode messageNode = chatMessage.getMessageNode();
		messageNode.setRuneLiteFormatMessage(formatted);
		client.refreshChat();
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.JOURNALSCROLL)
		{
			// Adventure Log "Counters" page just opened - the actual widget text
			// isn't populated until the following game tick.
			journalScrollLoaded = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!journalScrollLoaded)
		{
			return;
		}
		journalScrollLoaded = false;

		Widget titleWidget = client.getWidget(InterfaceID.Journalscroll.TITLE);
		String ownerName = titleWidget == null ? null : extractAdventureLogOwnerName(Text.removeTags(titleWidget.getText()));
		String localPlayerName = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();

		if (ownerName != null && localPlayerName != null && !ownerName.equalsIgnoreCase(localPlayerName))
		{
			// Guests can right-click and read another player's Adventure Log
			// in their POH - the widget then shows the HOUSE OWNER's data,
			// not the guest's. Confirmed live: the local player's own
			// Counters page TITLE widget reads the bare account name (e.g.
			// "Blitzen"), and a friend's POH Counters page reads THEIR bare
			// name instead (e.g. "Dad") - with the parser below still
			// happily producing PBs for it. Without this check those would
			// get synced under the local player's account instead.
			log.debug("Adventure Log belongs to {}, not the local player {} - skipping sync", ownerName, localPlayerName);
			return;
		}

		Widget parent = client.getWidget(InterfaceID.Journalscroll.TEXTLAYER);
		if (parent == null)
		{
			return;
		}

		Widget[] children = parent.getStaticChildren();
		if (children == null || children.length == 0)
		{
			return;
		}

		// Raw widget text, one entry per line as OSRS renders it.
		List<String> rawLines = new ArrayList<>(children.length);
		for (Widget child : children)
		{
			rawLines.add(Text.removeTags(child.getText()));
		}

		// Long lines wrap: a label ending in ":" with nothing after it means
		// the actual value is sitting by itself on the next line. Merge those
		// back into a single "label: value" line before parsing.
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < rawLines.size(); i++)
		{
			String line = rawLines.get(i);
			if (line.endsWith(":") && i + 1 < rawLines.size() && isBareValue(rawLines.get(i + 1)))
			{
				lines.add(line + " " + rawLines.get(i + 1));
				i++;
			}
			else
			{
				lines.add(line);
			}
		}

		Map<String, Double> pbs = new HashMap<>();
		String currentHeading = null;

		for (String line : lines)
		{
			if (line.isEmpty())
			{
				currentHeading = null;
				continue;
			}

			Matcher matcher = RECORD_PATTERN.matcher(line);
			if (!matcher.find())
			{
				// Not a record line - treat it as the heading (activity/boss
				// name) that the following record lines belong to.
				currentHeading = line;
				continue;
			}

			if (currentHeading == null)
			{
				continue;
			}

			String descriptor = matcher.group("descriptor");
			String valueStr = matcher.group("value");
			if ("-".equals(valueStr))
			{
				// No time recorded for this stat yet.
				continue;
			}

			Double seconds = parseTimeString(valueStr);
			if (seconds == null)
			{
				continue;
			}

			pbs.put(buildKey(currentHeading, descriptor), seconds);
			syncedAdventureLogHeadings.add(currentHeading.toLowerCase());
		}

		if (!pbs.isEmpty())
		{
			log.debug("Parsed {} PB(s) from Adventure Log Counters page", pbs.size());
			syncPbs(pbs);
		}
	}

	private static boolean isBareValue(String line)
	{
		return line.equals("-") || line.matches("[0-9:]+(?:\\.[0-9]+)?");
	}

	/**
	 * Extracts the account name from the Journalscroll TITLE widget's text -
	 * "The Exploits of Blitzen" or a bare "Blitzen" both yield "Blitzen".
	 * Returns null for blank/empty input, or for a known generic Adventure
	 * Log page label (GENERIC_JOURNAL_TITLES) that isn't actually an account
	 * name - both cases mean "couldn't determine an owner", which callers
	 * treat as "don't block the sync" rather than a mismatch.
	 */
	static String extractAdventureLogOwnerName(String titleText)
	{
		if (titleText == null)
		{
			return null;
		}
		String trimmed = titleText.trim();
		if (trimmed.isEmpty())
		{
			return null;
		}
		Matcher matcher = JOURNAL_TITLE_PATTERN.matcher(trimmed);
		if (!matcher.matches())
		{
			return null;
		}
		String name = matcher.group("name").trim();
		if (GENERIC_JOURNAL_TITLES.contains(name.toLowerCase()))
		{
			return null;
		}
		return name;
	}

	/**
	 * Turns a heading ("Theatre of Blood") and a raw descriptor ("Room time -
	 * (Team size: 3 player)") into a clean, unambiguous sync key like
	 * "Theatre of Blood - Fastest Room (3 player)". Bare "kill"/"run" (no
	 * team size, single-stat bosses) just use the heading as-is.
	 */
	private static String buildKey(String heading, String descriptor)
	{
		String normalizedHeading = canonicalBossKey(heading);
		if (descriptor.equals("kill") || descriptor.equals("run"))
		{
			return normalizedHeading;
		}

		String label;
		String remainder;
		if (descriptor.startsWith("Room time"))
		{
			label = "Fastest Room";
			remainder = descriptor.substring("Room time".length());
		}
		else if (descriptor.startsWith("Wave time"))
		{
			label = "Fastest Wave";
			remainder = descriptor.substring("Wave time".length());
		}
		else if (descriptor.startsWith("Overall time"))
		{
			label = "Fastest Overall";
			remainder = descriptor.substring("Overall time".length());
		}
		else if (descriptor.startsWith("kill"))
		{
			label = "Fastest Overall";
			remainder = descriptor.substring("kill".length());
		}
		else if (descriptor.startsWith("run"))
		{
			label = "Fastest Overall";
			remainder = descriptor.substring("run".length());
		}
		else
		{
			// Unrecognized shape - keep the raw descriptor rather than
			// silently dropping the record.
			label = descriptor;
			remainder = "";
		}

		String detail = remainder
			.replace("- (Team size:", "")
			.replaceAll("[()]", "")
			.trim();

		return normalizedHeading + " - " + label + (detail.isEmpty() ? "" : " (" + detail + ")");
	}

	private static Double parseTimeString(String timeString)
	{
		try
		{
			String[] parts = timeString.split(":");
			if (parts.length == 2)
			{
				return Integer.parseInt(parts[0]) * 60 + Double.parseDouble(parts[1]);
			}
			else if (parts.length == 3)
			{
				return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Double.parseDouble(parts[2]);
			}
			return Double.parseDouble(timeString);
		}
		catch (NumberFormatException ex)
		{
			return null;
		}
	}

	private void syncAll()
	{
		String profileKey = configManager.getRSProfileKey();
		if (profileKey == null)
		{
			setStatus("Not logged in yet - log in, then try again.");
			return;
		}

		List<String> bossKeys = configManager.getRSProfileConfigurationKeys(CONFIG_GROUP, profileKey, "");

		Map<String, Double> raw = new HashMap<>();
		for (String boss : bossKeys)
		{
			Double seconds = configManager.getRSProfileConfiguration(CONFIG_GROUP, boss, double.class);
			if (seconds != null)
			{
				raw.put(boss, seconds);
			}
		}

		// Skip raid/team-size variants (e.g. "chambers of xeric 2 players") -
		// those are synced with proper Room/Overall labels by the Adventure
		// Log parser instead, so sending the raw version here would just
		// create a duplicate, differently-named row for the same record.
		Map<String, Double> pbs = new HashMap<>();
		for (Map.Entry<String, Double> entry : raw.entrySet())
		{
			String key = entry.getKey();
			if (shouldSyncRawPersonalBest(key))
			{
				pbs.put(canonicalBossKey(key), entry.getValue());
			}
		}

		String unsyncedNote = buildUnsyncedDuplicatesNote(raw);

		if (pbs.isEmpty())
		{
			setStatus(unsyncedNote != null
				? "No personal bests found yet. " + unsyncedNote
				: "No personal bests found yet - go kill something!");
			return;
		}

		setStatus("Syncing " + pbs.size() + " PB(s)...");
		syncPbs(pbs, unsyncedNote);
	}

	/**
	 * Jad/Zuk/Colosseum/Hueycoatl are deliberately excluded from live and bulk
	 * sync (see looksLikeRaidVariant) so the Adventure Log parser can supply
	 * them with a properly-labelled name instead. But that parser only runs
	 * when the player manually opens the in-game Adventure Log Counters page
	 * - if they never do, those records would otherwise just never sync.
	 * Returns a status note listing any such records that are sitting unsynced
	 * right now, or null if there's nothing to flag.
	 */
	private String buildUnsyncedDuplicatesNote(Map<String, Double> raw)
	{
		List<String> unsynced = new ArrayList<>();
		for (Map.Entry<String, String> entry : KNOWN_DUPLICATE_RAW_KEYS.entrySet())
		{
			String heading = entry.getValue();
			if (raw.containsKey(entry.getKey()) && !syncedAdventureLogHeadings.contains(heading.toLowerCase()))
			{
				unsynced.add(heading);
			}
		}

		if (unsynced.isEmpty())
		{
			return null;
		}

		return "Not synced yet: " + String.join(", ", unsynced) + " - open Adventure Log > Counters once to sync.";
	}

	/**
	 * Diagnostic dump of every raw personalbest.* value RuneLite has cached,
	 * independent of whether our own sync logic would forward it - lets a
	 * player prove what RuneLite actually has locally without relying on
	 * per-boss !pb chat commands. Copies the report to the clipboard rather
	 * than syncing anything.
	 */
	private void dumpRawPersonalBests()
	{
		String profileKey = configManager.getRSProfileKey();
		if (profileKey == null)
		{
			setStatus("Not logged in yet - log in, then try again.");
			return;
		}

		List<String> bossKeys = configManager.getRSProfileConfigurationKeys(CONFIG_GROUP, profileKey, "");

		Map<String, Double> raw = new HashMap<>();
		for (String boss : bossKeys)
		{
			Double seconds = configManager.getRSProfileConfiguration(CONFIG_GROUP, boss, double.class);
			if (seconds != null)
			{
				raw.put(boss, seconds);
			}
		}

		String report = buildRawPbReport(raw);
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(report), null);
		setStatus("Copied " + raw.size() + " raw PB value(s) to clipboard.");
	}

	/**
	 * Opens the current account's PB tracker profile page in the system
	 * browser - mirrors Wise Old Man's "open my profile" feature.
	 */
	private void openProfile()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			setStatus("Not logged in yet - log in, then try again.");
			return;
		}

		String url = buildProfileUrl(client.getLocalPlayer().getName());
		LinkBrowser.browse(url);
	}

	/**
	 * Pure formatting of a raw personalbest.* map into a diagnostic report,
	 * annotating each entry with whether shouldSyncRawPersonalBest() would
	 * forward it as-is or is gating it (and why) - kept static and free of
	 * ConfigManager/AWT so it's directly unit-testable.
	 */
	static String buildRawPbReport(Map<String, Double> raw)
	{
		Map<String, Double> sorted = new TreeMap<>(raw);
		StringBuilder report = new StringBuilder();
		for (Map.Entry<String, Double> entry : sorted.entrySet())
		{
			String key = entry.getKey();
			report.append("personalbest.").append(key).append(" = ").append(entry.getValue()).append(" -> ");

			if (shouldSyncRawPersonalBest(key))
			{
				report.append("synced as \"").append(canonicalBossKey(key)).append('"');
			}
			else
			{
				String heading = KNOWN_DUPLICATE_RAW_KEYS.get(key.toLowerCase());
				if (heading != null)
				{
					report.append("SKIPPED (gated, waiting on Adventure Log Counters -> \"").append(heading).append("\")");
				}
				else
				{
					report.append("SKIPPED (raid/team-size variant, waiting on Adventure Log Counters)");
				}
			}

			report.append('\n');
		}

		return report.toString();
	}

	/**
	 * Pure URL-building for the "open my profile" action - kept static and
	 * side-effect-free (no Client, no LinkBrowser) so it's directly
	 * unit-testable, same pattern as buildRawPbReport.
	 */
	static String buildProfileUrl(String playerName)
	{
		// URLEncoder implements HTML form encoding (encodes a space as "+"),
		// but the frontend decodes the path segment with JavaScript's
		// decodeURIComponent, which does NOT treat "+" as a space - only
		// %-escapes are decoded. Replacing "+" with "%20" after encoding
		// matches what JavaScript's encodeURIComponent produces for a space,
		// so the two sides agree.
		String encoded = URLEncoder.encode(playerName, StandardCharsets.UTF_8).replace("+", "%20");
		return PROFILE_SITE_URL + "/player/" + encoded;
	}

	private void syncPbs(Map<String, Double> pbs)
	{
		syncPbs(pbs, null);
	}

	private void syncPbs(Map<String, Double> pbs, String statusNote)
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return;
		}

		String name = client.getLocalPlayer().getName();
		String hash = accountHash != null ? accountHash : String.valueOf(client.getAccountHash());
		String suffix = statusNote != null ? " " + statusNote : "";

		syncClient.sync(hash, name, pbs, installSecret, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("PB sync failed", e);
				setStatus("Sync failed: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (response.isSuccessful())
					{
						setStatus("Last updated: " + TIMESTAMP_FORMAT.format(LocalDateTime.now()) + suffix);
					}
					else if (response.code() == 409)
					{
						setStatus("Sync rejected: this account is already synced from a different install.");
					}
					else
					{
						setStatus("Server responded with error " + response.code());
					}
				}
				finally
				{
					response.close();
				}
			}
		});
	}
}
