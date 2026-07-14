package com.pbtracker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.FontManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Renders a player's synced PBs as two collapsible sections: "Top Bosses"
 * (their 5 best-ranked performances) and "All Bosses" (every boss this
 * plugin knows about - with a dash for anything the player hasn't recorded
 * a time for yet, not just the ones they have). Reused by both the "My PBs"
 * and "Player Search" tabs.
 * <p>
 * Implements Scrollable so the enclosing JScrollPane's JViewport clamps this
 * panel's width to the actual visible width instead of using its raw (and,
 * because of the wrapped JTextArea labels, misleadingly wide) preferred
 * size - without this, a plain JPanel isn't Scrollable-aware, so the
 * viewport just takes the view's inflated preferred width verbatim, which is
 * what let long headings force the whole panel wider instead of wrapping.
 */
@Slf4j
class PbListPanel extends JPanel implements Scrollable
{
	private final SpriteManager spriteManager;
	private final JPanel rowsContainer = new JPanel();

	private List<String> allBosses = List.of();
	private final Set<String> expandedHeadings = new HashSet<>();
	private boolean topBossesSectionExpanded = true;
	private boolean allBossesSectionExpanded = true;

	private SyncClient.PlayerLookupResponse lastPlayer;
	private BiConsumer<String, String> lastOnBossClick;

	PbListPanel(SpriteManager spriteManager)
	{
		this.spriteManager = spriteManager;
		setLayout(new BorderLayout());
		setBackground(PbTrackerTheme.BG);

		rowsContainer.setLayout(new GridBagLayout());
		rowsContainer.setBackground(PbTrackerTheme.BG);
		add(rowsContainer, BorderLayout.NORTH);
	}

	/** Every boss key this plugin knows about system-wide - used to show a dash row for anything this player hasn't done yet. */
	void setAllBosses(List<String> allBosses)
	{
		this.allBosses = allBosses;
		rerender();
	}

	@Override
	public Dimension getPreferredScrollableViewportSize()
	{
		return getPreferredSize();
	}

	@Override
	public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return 16;
	}

	@Override
	public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return visibleRect.height;
	}

	@Override
	public boolean getScrollableTracksViewportWidth()
	{
		return true;
	}

	@Override
	public boolean getScrollableTracksViewportHeight()
	{
		return false;
	}

	void showMessage(String text)
	{
		lastPlayer = null;
		rowsContainer.removeAll();
		JLabel label = new JLabel("<html><center>" + escapeHtml(text) + "</center></html>");
		label.setForeground(PbTrackerTheme.TEXT_DIM);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		label.setBorder(BorderFactory.createEmptyBorder(16, 8, 16, 8));
		addFullWidthRow(label);
		revalidate();
		repaint();
	}

	static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;");
	}

	/** One row's worth of display data, merging the global "every known boss" template with this player's actual recorded times. */
	private static final class DisplayRow
	{
		final String heading;
		final String iconKey;
		final String primaryName;
		final String subtitle;
		final boolean hasData;
		final double timeSeconds;
		final int rank;
		final String clickKey;
		final List<BossGroups.PlayerRaidVariant> variants;
		final double topTimeSeconds;
		final int topRank;
		final String topClickKey;

		DisplayRow(String heading, String iconKey, String primaryName, String subtitle, boolean hasData,
			double timeSeconds, int rank, String clickKey, List<BossGroups.PlayerRaidVariant> variants)
		{
			this(heading, iconKey, primaryName, subtitle, hasData, timeSeconds, rank, clickKey, variants,
				timeSeconds, rank, clickKey);
		}

		DisplayRow(String heading, String iconKey, String primaryName, String subtitle, boolean hasData,
			double timeSeconds, int rank, String clickKey, List<BossGroups.PlayerRaidVariant> variants,
			double topTimeSeconds, int topRank, String topClickKey)
		{
			this.heading = heading;
			this.iconKey = iconKey;
			this.primaryName = primaryName;
			this.subtitle = subtitle;
			this.hasData = hasData;
			this.timeSeconds = timeSeconds;
			this.rank = rank;
			this.clickKey = clickKey;
			this.variants = variants;
			this.topTimeSeconds = topTimeSeconds;
			this.topRank = topRank;
			this.topClickKey = topClickKey;
		}

		DisplayRow forTopBosses()
		{
			return new DisplayRow(heading, iconKey, primaryName, subtitle, hasData,
				topTimeSeconds, topRank, topClickKey, variants);
		}
	}

	/**
	 * @param onBossClick called with (bossKey, displayName) when a row is
	 *                     clicked - callers use this to jump to that boss's
	 *                     leaderboard, highlighted on the clicked player.
	 */
	void showPlayer(SyncClient.PlayerLookupResponse player, BiConsumer<String, String> onBossClick)
	{
		lastPlayer = player;
		lastOnBossClick = onBossClick;
		rowsContainer.removeAll();

		if (player.pbs == null || player.pbs.isEmpty())
		{
			showMessage(player.displayName + " has synced, but has no recorded PBs yet.");
			return;
		}

		List<BossGroups.PlayerPb> pbs = new ArrayList<>();
		for (SyncClient.PbEntryDto pb : player.pbs)
		{
			pbs.add(new BossGroups.PlayerPb(pb.boss, pb.timeSeconds, pb.rank, pb.updatedAt));
		}
		BossGroups.GroupedPlayerPbs grouped = BossGroups.groupPlayerRaidPbs(pbs);

		List<DisplayRow> allRows = buildAllRows(grouped);

		List<DisplayRow> ranked = new ArrayList<>();
		for (DisplayRow row : allRows)
		{
			if (row.hasData)
			{
				ranked.add(row);
			}
		}
		ranked.sort(Comparator.comparingInt(r -> r.topRank));

		addSectionHeader("Top Bosses", topBossesSectionExpanded, () -> topBossesSectionExpanded = !topBossesSectionExpanded);
		if (topBossesSectionExpanded)
		{
			for (int i = 0; i < Math.min(5, ranked.size()); i++)
			{
				addDisplayRow(ranked.get(i).forTopBosses(), player.displayName, onBossClick);
			}
		}

		addSectionHeader("All Bosses", allBossesSectionExpanded, () -> allBossesSectionExpanded = !allBossesSectionExpanded);
		if (allBossesSectionExpanded)
		{
			List<DisplayRow> sorted = new ArrayList<>(allRows);
			sorted.sort(Comparator.comparing(r -> r.primaryName.toLowerCase()));
			for (DisplayRow row : sorted)
			{
				addDisplayRow(row, player.displayName, onBossClick);
			}
		}

		revalidate();
		repaint();
	}

	private void rerender()
	{
		if (lastPlayer != null)
		{
			showPlayer(lastPlayer, lastOnBossClick);
		}
	}

	/** Every raid heading + flat boss key this plugin knows about, merged with this player's actual PBs (dash if they have none). */
	private List<DisplayRow> buildAllRows(BossGroups.GroupedPlayerPbs grouped)
	{
		java.util.Map<String, BossGroups.PlayerRaidGroup> playerGroupsByHeading = new java.util.LinkedHashMap<>();
		for (BossGroups.PlayerRaidGroup g : grouped.groups)
		{
			playerGroupsByHeading.put(g.heading, g);
		}
		java.util.Map<String, BossGroups.PlayerPb> playerFlatByKey = new java.util.LinkedHashMap<>();
		for (BossGroups.PlayerPb pb : grouped.flat)
		{
			playerFlatByKey.put(pb.boss.trim().toLowerCase(), pb);
		}

		List<DisplayRow> rows = new ArrayList<>();

		List<BossGroups.RaidGroup> templateGroups = BossGroups.groupedRaidGroups(allBosses);
		Set<String> templateHeadings = new HashSet<>();
		for (BossGroups.RaidGroup template : templateGroups)
		{
			templateHeadings.add(template.heading);
			rows.add(buildRaidRow(template.heading, template.variants.get(0).key, playerGroupsByHeading.get(template.heading)));
		}
		// A player might have a PB for a heading the global list hasn't caught up to yet - don't drop it.
		for (BossGroups.PlayerRaidGroup playerGroup : grouped.groups)
		{
			if (!templateHeadings.contains(playerGroup.heading))
			{
				rows.add(buildRaidRow(playerGroup.heading, playerGroup.summary.key, playerGroup));
			}
		}

		Set<String> templateFlatKeys = new HashSet<>();
		for (String key : BossGroups.getFlatBossKeys(allBosses))
		{
			String norm = key.trim().toLowerCase();
			templateFlatKeys.add(norm);
			rows.add(buildFlatRow(key, playerFlatByKey.get(norm)));
		}
		for (BossGroups.PlayerPb pb : grouped.flat)
		{
			if (!templateFlatKeys.contains(pb.boss.trim().toLowerCase()))
			{
				rows.add(buildFlatRow(pb.boss, pb));
			}
		}

		return rows;
	}

	private DisplayRow buildRaidRow(String heading, String templateClickKey, BossGroups.PlayerRaidGroup playerGroup)
	{
		int dashIdx = heading.indexOf(" - ");
		String primaryName = dashIdx >= 0 ? heading.substring(0, dashIdx) : heading;
		String subtitle = dashIdx >= 0 ? heading.substring(dashIdx + 3) : null;

		if (playerGroup == null)
		{
			return new DisplayRow(heading, heading, primaryName, subtitle, false, 0, 0, templateClickKey, null);
		}
		List<BossGroups.PlayerRaidVariant> variants = playerGroup.variants.size() > 1 ? playerGroup.variants : null;
		BossGroups.PlayerRaidVariant bestRanked = BossGroups.pickBestRanked(playerGroup.variants);
		return new DisplayRow(heading, heading, primaryName, subtitle, true,
			playerGroup.summary.timeSeconds, playerGroup.summary.rank, playerGroup.summary.key, variants,
			bestRanked.timeSeconds, bestRanked.rank, bestRanked.key);
	}

	private DisplayRow buildFlatRow(String key, BossGroups.PlayerPb pb)
	{
		String primaryName = PbTrackerPlugin.titleCase(key);
		if (pb == null)
		{
			return new DisplayRow(key, key, primaryName, null, false, 0, 0, key, null);
		}
		return new DisplayRow(key, key, primaryName, null, true, pb.timeSeconds, pb.rank, pb.boss, null);
	}

	private void addSectionHeader(String text, boolean expanded, Runnable onToggle)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(PbTrackerTheme.BG);
		row.setBorder(BorderFactory.createEmptyBorder(10, 8, 6, 8));

		JLabel label = new JLabel(text);
		label.setForeground(PbTrackerTheme.TEXT);
		label.setFont(FontManager.getRunescapeBoldFont().deriveFont(FontManager.getRunescapeBoldFont().getSize2D() + 2f));
		row.add(label, BorderLayout.WEST);

		JLabel chevron = new JLabel(expanded ? "v" : ">");
		chevron.setForeground(PbTrackerTheme.TEXT_DIM);
		chevron.setFont(label.getFont());
		row.add(chevron, BorderLayout.EAST);
		PbTrackerPlugin.addRowClickListener(row, () ->
		{
			onToggle.run();
			rerender();
		});

		addFullWidthRow(row);
	}

	/**
	 * The whole row is the click target - if it has a team-size breakdown,
	 * clicking toggles that breakdown open beneath it in place (matching
	 * "collapsable to see the other PBs under the boss"); otherwise it jumps
	 * straight to that boss's leaderboard, same as before.
	 */
	private void addDisplayRow(DisplayRow row, String displayName, BiConsumer<String, String> onBossClick)
	{
		boolean expandable = row.variants != null;
		boolean expanded = expandable && expandedHeadings.contains(row.heading);

		JPanel outer = new JPanel(new BorderLayout(8, 0));
		outer.setBackground(PbTrackerTheme.PANEL);
		outer.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, PbTrackerTheme.PANEL_BORDER),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)
		));
		outer.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(28, 28));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		BossIcons.get(spriteManager, row.iconKey, icon::setIcon);
		outer.add(icon, BorderLayout.WEST);

		JPanel textBlock = new JPanel();
		textBlock.setLayout(new BoxLayout(textBlock, BoxLayout.Y_AXIS));
		textBlock.setBackground(PbTrackerTheme.PANEL);
		// Bold at the normal (not "small") size - readability won out over
		// avoiding wraps, so a long name like "Theatre Of Blood" may now
		// take 2 lines rather than fitting on 1.
		JTextArea name = wrappedLabel(PbTrackerPlugin.wrapFriendly(row.primaryName), PbTrackerTheme.TEXT, true);
		textBlock.add(name);
		if (row.subtitle != null)
		{
			JTextArea subtitle = wrappedLabel(PbTrackerPlugin.wrapFriendly(row.subtitle), PbTrackerTheme.TEXT_DIM, false);
			textBlock.add(subtitle);
		}
		outer.add(textBlock, BorderLayout.CENTER);

		JPanel statsBlock = new JPanel();
		statsBlock.setLayout(new BoxLayout(statsBlock, BoxLayout.Y_AXIS));
		statsBlock.setBackground(PbTrackerTheme.PANEL);
		if (row.hasData)
		{
			JLabel time = new JLabel(PbTrackerPlugin.formatTime(row.timeSeconds));
			time.setForeground(PbTrackerTheme.GOLD_LIGHT);
			time.setFont(FontManager.getRunescapeBoldFont());
			time.setAlignmentX(java.awt.Component.RIGHT_ALIGNMENT);
			JLabel rank = new JLabel("#" + row.rank);
			rank.setForeground(PbTrackerTheme.TEXT_DIM);
			rank.setFont(FontManager.getRunescapeFont());
			rank.setAlignmentX(java.awt.Component.RIGHT_ALIGNMENT);
			statsBlock.add(time);
			statsBlock.add(rank);
		}
		else
		{
			// Plain ASCII "-" rather than an en dash - RuneLite's bitmap OSRS
			// font doesn't have a glyph for "–" and silently falls back to a
			// "tofu" box character instead (same issue as the old chevron).
			JLabel dash = new JLabel("-");
			dash.setForeground(PbTrackerTheme.TEXT_DIM);
			dash.setFont(FontManager.getRunescapeBoldFont());
			statsBlock.add(dash);
		}
		if (expandable)
		{
			// A visible arrow, not just an unmarked clickable row - a hidden
			// affordance here was the exact "where are my other team sizes?"
			// problem from before. Stacked below the rank rather than beside
			// it, so an expandable row costs no extra horizontal width and
			// doesn't force the name text to wrap any harder than a plain row.
			JLabel chevron = new JLabel(expanded ? "v" : ">");
			chevron.setForeground(PbTrackerTheme.GOLD_LIGHT);
			chevron.setFont(FontManager.getRunescapeBoldFont());
			chevron.setAlignmentX(java.awt.Component.RIGHT_ALIGNMENT);
			statsBlock.add(chevron);
		}
		outer.add(statsBlock, BorderLayout.EAST);

		PbTrackerPlugin.addRowClickListener(outer, () ->
		{
			if (expandable)
			{
				if (expandedHeadings.contains(row.heading))
				{
					expandedHeadings.remove(row.heading);
				}
				else
				{
					expandedHeadings.add(row.heading);
				}
				rerender();
			}
			else
			{
				activateBoss(row.clickKey, displayName, onBossClick);
			}
		});
		addFullWidthRow(outer);

		if (expanded)
		{
			for (BossGroups.PlayerRaidVariant variant : row.variants)
			{
				addVariantSubRow(variant, displayName, onBossClick);
			}
		}
	}

	/** An indented, smaller row for one team-size/mode variant beneath its expanded raid+mode heading. */
	private void addVariantSubRow(BossGroups.PlayerRaidVariant variant, String displayName, BiConsumer<String, String> onBossClick)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(PbTrackerTheme.ROW_BG);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, PbTrackerTheme.PANEL_BORDER),
			BorderFactory.createEmptyBorder(6, 20, 6, 8)
		));
		row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

		JTextArea line = wrappedLabel(variant.label + "   " + PbTrackerPlugin.formatTime(variant.timeSeconds) + "   #" + variant.rank, PbTrackerTheme.TEXT, false);
		row.add(line, BorderLayout.CENTER);
		PbTrackerPlugin.addRowClickListener(row, () -> activateBoss(variant.key, displayName, onBossClick));
		addFullWidthRow(row);
	}

	/**
	 * A plain JLabel never wraps and just keeps growing wider, which is what
	 * caused the sidebar's horizontal scrollbar on long headings. A
	 * non-editable, unstyled JTextArea wraps at whatever width its
	 * container actually gives it, like the rest of the panel.
	 */
	private JTextArea wrappedLabel(String text, java.awt.Color color, boolean bold)
	{
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setFocusable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		area.setForeground(color);
		area.setFont(bold ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont());
		area.setBorder(null);
		area.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		return area;
	}

	/**
	 * GridBagLayout with horizontal fill avoids BoxLayout shrinking each card
	 * to its preferred text width, which left a large blank gutter beside
	 * shorter boss names in RuneLite's narrow sidebar.
	 */
	private void addFullWidthRow(Component component)
	{
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 0;
		constraints.gridy = rowsContainer.getComponentCount();
		constraints.weightx = 1.0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.anchor = GridBagConstraints.FIRST_LINE_START;
		rowsContainer.add(component, constraints);
	}

	private static void activateBoss(String bossKey, String displayName, BiConsumer<String, String> onBossClick)
	{
		log.debug("PB row activated: boss={}, player={}", bossKey, displayName);
		onBossClick.accept(bossKey, displayName);
	}
}
