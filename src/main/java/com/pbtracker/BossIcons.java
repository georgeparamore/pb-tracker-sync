package com.pbtracker;

import net.runelite.client.game.SpriteManager;

import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Image;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Boss icons sourced from the exact same game-cache sprites RuneLite's own
 * Hiscores side panel uses (net.runelite.client.hiscore.HiscoreSkill's
 * spriteId field, extracted from the client jar), fetched live via
 * SpriteManager instead of bundling separate art - a pixel-perfect match
 * with the client's own boss icons, and no art to maintain as new bosses
 * are added to the game.
 * <p>
 * Raid PB keys carry their mode/team-size as a " - "-delimited suffix (e.g.
 * "theatre of blood - hard - fastest overall (5 player hard mode)"), so only
 * the base name before the first " - " is used to look up the sprite -
 * every mode/size of a raid shares one icon, same as the website.
 */
final class BossIcons
{
	private BossIcons()
	{
	}

	private static final int SIZE = 28;
	private static final Map<Integer, ImageIcon> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Integer> SPRITE_IDS = buildSpriteIds();

	/**
	 * Slugs with no sprite of their own on the official hiscores - the
	 * "awakened" DT2 bosses and Demonic Brutus aren't tracked separately
	 * from their base form there, so they borrow its icon, and the
	 * Tzhaar-Ket-Rak challenges (not on the hiscores at all) borrow Jad's.
	 */
	private static final Map<String, String> ALIASES = Map.ofEntries(
		Map.entry("duke_sucellus_(awakened)", "duke_sucellus"),
		Map.entry("leviathan_(awakened)", "leviathan"),
		Map.entry("vardorvis_(awakened)", "vardorvis"),
		Map.entry("whisperer_(awakened)", "whisperer"),
		Map.entry("demonic_brutus", "brutus"),
		Map.entry("sol_heredit", "fortis_colosseum"),
		Map.entry("tzkal_zuk", "inferno"),
		Map.entry("fight_caves", "tzhaar_fight_cave"),
		Map.entry("tzhaar_ket_raks_first_challenge", "tztok_jad"),
		Map.entry("tzhaar_ket_raks_second_challenge", "tztok_jad"),
		Map.entry("tzhaar_ket_raks_third_challenge", "tztok_jad"),
		Map.entry("tzhaar_ket_raks_fourth_challenge", "tztok_jad"),
		Map.entry("tzhaar_ket_raks_fifth_challenge", "tztok_jad"),
		Map.entry("tzhaar_ket_raks_sixth_challenge", "tztok_jad")
	);

	/**
	 * Fetches (and caches) the sprite for this boss and hands it to
	 * `onLoaded` once available. Does nothing if there's no icon for this
	 * boss - callers should just leave their icon label blank in that case.
	 * SpriteManager invokes cache-miss callbacks on RuneLite's client thread,
	 * not Swing's EDT, so callbacks are explicitly delivered on the EDT before
	 * they touch Swing components. They are still NOT safe to apply to a shared,
	 * transient component like a JList cell renderer's label (it may be
	 * reused for a different row by the time the callback fires); use
	 * `getCached` + a repaint there instead, see PickerEntryRenderer.
	 */
	static void get(SpriteManager spriteManager, String bossKey, Consumer<ImageIcon> onLoaded)
	{
		if (spriteManager == null)
		{
			return;
		}
		Integer spriteId = spriteIdFor(bossKey);
		if (spriteId == null)
		{
			return;
		}

		ImageIcon cached = CACHE.get(spriteId);
		if (cached != null)
		{
			deliverOnEdt(onLoaded, cached);
			return;
		}

		spriteManager.getSpriteAsync(spriteId, 0, image ->
		{
			if (image == null)
			{
				return;
			}
			Image scaled = image.getScaledInstance(SIZE, SIZE, Image.SCALE_SMOOTH);
			ImageIcon icon = new ImageIcon(scaled);
			CACHE.put(spriteId, icon);
			deliverOnEdt(onLoaded, icon);
		});
	}

	static void deliverOnEdt(Consumer<ImageIcon> onLoaded, ImageIcon icon)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			onLoaded.accept(icon);
		}
		else
		{
			SwingUtilities.invokeLater(() -> onLoaded.accept(icon));
		}
	}

	/** Cache-only lookup (no fetch) - null if this boss's icon hasn't loaded yet (or has none). */
	static ImageIcon getCached(String bossKey)
	{
		Integer spriteId = spriteIdFor(bossKey);
		return spriteId == null ? null : CACHE.get(spriteId);
	}

	static Integer spriteIdFor(String bossKey)
	{
		String slug = slugFor(bossKey);
		if (slug == null)
		{
			return null;
		}
		slug = ALIASES.getOrDefault(slug, slug);
		return SPRITE_IDS.get(slug);
	}

	private static String slugFor(String bossKey)
	{
		if (bossKey == null || bossKey.trim().isEmpty())
		{
			return null;
		}
		String base = bossKey.trim().toLowerCase();
		int dash = base.indexOf(" - ");
		if (dash >= 0)
		{
			base = base.substring(0, dash);
		}
		// "the nightmare"/"the gauntlet"/"the corrupted gauntlet"/"the
		// hueycoatl" are stored with a "the " prefix the sprite-id map below
		// doesn't use - mirrors BossGroups.normalize()'s existing convention.
		if (base.startsWith("the "))
		{
			base = base.substring(4);
		}
		return base.replace("'", "").replace(" ", "_").replace("-", "_");
	}

	// Extracted from net.runelite.client.hiscore.HiscoreSkill's bundled
	// spriteId field in the RuneLite client jar - one entry per boss/raid
	// base this plugin tracks.
	private static Map<String, Integer> buildSpriteIds()
	{
		Map<String, Integer> m = new HashMap<>();
		m.put("abyssal_sire", 4276);
		m.put("alchemical_hydra", 4289);
		m.put("amoxliatl", 5639);
		m.put("araxxor", 5638);
		m.put("artio", 5622);
		m.put("brutus", 6352);
		m.put("bryophyta", 4262);
		m.put("callisto", 5622);
		m.put("calvarion", 5623);
		m.put("cerberus", 4280);
		m.put("chambers_of_xeric", 4288);
		m.put("chaos_elemental", 5621);
		m.put("chaos_fanatic", 5625);
		m.put("commander_zilyana", 4284);
		m.put("corporeal_beast", 4287);
		m.put("crazy_archaeologist", 5626);
		m.put("dagannoth_prime", 4294);
		m.put("dagannoth_rex", 4293);
		m.put("dagannoth_supreme", 4292);
		m.put("deranged_archaeologist", 5627);
		m.put("doom_of_mokhaiotl", 6347);
		m.put("duke_sucellus", 5632);
		m.put("general_graardor", 4282);
		m.put("giant_mole", 4263);
		m.put("grotesque_guardians", 4264);
		m.put("hespori", 4271);
		m.put("kalphite_queen", 4270);
		m.put("king_black_dragon", 4274);
		m.put("kraken", 4275);
		m.put("kreearra", 4285);
		m.put("kril_tsutsaroth", 4283);
		m.put("lunar_chest", 5637);
		m.put("maggot_king", 8358);
		m.put("mimic", 4260);
		m.put("nex", 4291);
		m.put("nightmare", 4286);
		m.put("phosanis_nightmare", 4286);
		m.put("obor", 4261);
		m.put("phantom_muspah", 4299);
		m.put("sarachnis", 4269);
		m.put("scorpia", 5628);
		m.put("scurrius", 5635);
		m.put("shellbane_gryphon", 6349);
		m.put("skotizo", 4272);
		m.put("fortis_colosseum", 5636); // "Sol Heredit" on the hiscores
		m.put("spindel", 5624);
		m.put("tempoross", 4265);
		m.put("gauntlet", 4278);
		m.put("corrupted_gauntlet", 4295);
		m.put("hueycoatl", 5640);
		m.put("leviathan", 5633);
		m.put("royal_titans", 6345);
		m.put("whisperer", 5631);
		m.put("theatre_of_blood", 4290);
		m.put("thermonuclear_smoke_devil", 4277);
		m.put("tombs_of_amascut", 4297);
		m.put("inferno", 5630); // TzKal-Zuk
		m.put("tzhaar_fight_cave", 5629); // TzTok-Jad
		m.put("tztok_jad", 5629);
		m.put("vardorvis", 5634);
		m.put("venenatis", 5624);
		m.put("vetion", 5623);
		m.put("vorkath", 4281);
		m.put("wintertodt", 4266);
		m.put("yama", 6346);
		m.put("zalcano", 4273);
		m.put("zulrah", 4279);
		return Collections.unmodifiableMap(m);
	}
}
