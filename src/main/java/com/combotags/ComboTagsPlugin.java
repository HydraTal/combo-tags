package com.combotags;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.bank.BankSearch;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import net.runelite.client.plugins.banktags.tabs.TabInterface;
import net.runelite.client.plugins.banktags.tabs.TabManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

/**
 * Embeds combo "smart cells" in RuneLite's built-in bank tag layouts. A cell stands in for an ordered combo
 * group and renders the highest-priority member you currently own (an unowned group draws a faded ghost).
 *
 * <p>This is the standalone, CORE-only descendant of the combo feature once carried in a fork of
 * geheur/bank-tag-custom-layouts. It depends only on RuneLite's native bank tag layouts: membership for a
 * host tab is supplied by a per-tab {@link ComboBankTag} (OR'd into the tab filter), and each cell's winner
 * is pinned into the live core layout before the bank rebuilds. There is no custom layout engine and no
 * per-winner tagging.
 *
 * @see ComboResolver
 */
@Slf4j
@PluginDescriptor(
	name = "Combo Tags",
	description = "Smart \"combo\" cells for bank tag layouts: one slot shows the best item you own from an ordered group.",
	tags = {"bank", "tag", "layout", "gear", "combo", "smart"}
)
@PluginDependency(BankTagsPlugin.class)
public class ComboTagsPlugin extends Plugin implements MouseListener
{
	public static final String CONFIG_GROUP = "combotags";

	// Bank menu option strings for the combo cell actions (reuse natural bank-menu wording).
	public static final String REMOVE_COMBO = "Remove Layout";
	public static final String REPLACE_COMBO_WITH_ITEM = "Set Placeholder";
	public static final String OPEN_COMBO = "Edit";
	/** RuneLite's own per-placeholder option, stripped from a ghost cell's menu. */
	public static final String REMOVE_FROM_LAYOUT_MENU_OPTION = "Remove-layout";

	// Bank grid geometry (canvas-space), shared with ComboOverlay.
	public static final int BANK_ITEM_WIDTH = 36;
	public static final int BANK_ITEM_HEIGHT = 32;
	public static final int ROW_HEIGHT = 36;
	public static final int COLUMN_WIDTH = 48;

	public static final Color itemTooltipColor = new Color(0xFF9040);

	// Side-panel position. Toolbar buttons sort by priority ascending (lower = higher up); core buttons are
	// small positives (Configuration 0, XP Tracker 2, Notes 7). A positive value keeps us BELOW them rather than
	// jumping above the core UI with a negative.
	private static final int COMBO_PANEL_PRIORITY = 10;

	// RuneLite's banktags item-tag config (its CONFIG_GROUP) item key prefix, for removeBankTag.
	private static final String BANKTAGS_ITEM_KEY_PREFIX = "item_";
	private static final int PLACEHOLDER_TEMPLATE_ID = 14401;

	@Inject public Client client;
	@Inject public ItemManager itemManager;
	@Inject public ConfigManager configManager;
	@Inject public ClientThread clientThread;
	@Inject public TabInterface tabInterface;
	@Inject TabManager tabManager;
	@Inject public TagManager tagManager;
	@Inject public BankTagsService bankTagsService;
	@Inject public LayoutManager layoutManager;
	@Inject public BankSearch bankSearch;
	@Inject public Gson gson;
	@Inject public ComboTagsConfig config;
	@Inject public ColorPickerManager colorPickerManager;
	@Inject private OverlayManager overlayManager;
	@Inject private MouseManager mouseManager;
	@Inject private ClientToolbar clientToolbar;

	private final ComboResolver comboResolver = new ComboResolver(this);

	// One registered ComboBankTag per combo-host tag, supplying live owned-winner membership.
	// Concurrent: put/get/remove on the client thread vs iterate+clear on the EDT (shutDown).
	private final Map<String, ComboBankTag> comboBankTags = new ConcurrentHashMap<>();

	// Cached combo group name -> ARGB color and memoized Color instances, for the overlay/menu hot paths.
	private volatile Map<String, Integer> comboColorCache;
	private volatile Map<String, Color> comboColorObjCache;

	// Combo group names whose bank overlay highlight is turned off (hideHighlight), for the overlay hot path.
	private volatile Set<String> comboHideHighlightCache;

	// Cached per host tag: cell index -> combo group, so the overlay doesn't re-read config every paint.
	private volatile Map<String, Map<Integer, String>> comboCellGroupsCache = new ConcurrentHashMap<>();

	// Per host tag, the cell index -> winner id last pinned into its INACTIVE core layout, to skip churn.
	private final Map<String, Map<Integer, Integer>> lastCorePinnedWinners = new ConcurrentHashMap<>();

	// Host tags whose combo-member items have already been untagged this session.
	private final Set<String> comboMembersUntagged = ConcurrentHashMap.newKeySet();

	// Mouse position + scroll at the last left-button press (canvas space). Lets ComboOverlay make a combo box
	// follow a RuneLite-dragged cell in core tabs (where RuneLite, not this plugin, owns the drag).
	public int comboDragPressX = 0;
	public int comboDragPressY = 0;
	public int comboDragPressScroll = 0;

	private ComboPanel comboPanel;
	private NavigationButton comboNavButton;
	private boolean navButtonAdded = false;
	private ComboOverlay comboOverlay;
	private ConfigProfile lastProfile = null;

	@Provides
	ComboTagsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ComboTagsConfig.class);
	}

	@Override
	protected void startUp()
	{
		migrateLegacyComboData();
		lastProfile = configManager.getProfile();

		comboOverlay = new ComboOverlay(this, client, config);
		overlayManager.add(comboOverlay);
		mouseManager.registerMouseListener(this);

		comboPanel = new ComboPanel(this);
		comboNavButton = NavigationButton.builder()
			.tooltip("Combo Tags")
			.icon(ImageUtil.loadImageResource(getClass(), "/com/combotags/combo_icon.png"))
			.priority(COMBO_PANEL_PRIORITY)
			.panel(comboPanel)
			.build();
		updateSidePanel(); // adds the nav button if the "Show side panel" config is on

		comboMembersUntagged.clear();
		lastCorePinnedWinners.clear();
		clientThread.invokeLater(() -> {
			registerComboBankTags();
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				maintainAllComboCoreTabs();
				recaptureActiveComboCoreTab();
				bankSearch.layoutBank();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(comboOverlay);
		mouseManager.unregisterMouseListener(this);
		if (comboNavButton != null && navButtonAdded)
		{
			clientToolbar.removeNavigation(comboNavButton);
			navButtonAdded = false;
		}
		// Tag (un)registration mutates RuneLite's unsynchronized customTags map that the client-thread
		// register path also writes to; do the remove on the client thread to avoid corrupting it.
		clientThread.invokeLater(() ->
		{
			unregisterComboBankTags();
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				bankSearch.layoutBank();
			}
		});
	}

	/** Reopens the active tab if it is a combo-host core tab, so a newly-registered ComboBankTag takes effect. */
	private void recaptureActiveComboCoreTab()
	{
		String active = bankTagsService.getActiveTag();
		if (active != null && comboBankTags.containsKey(active) && isVanillaLayoutEnabled(active))
		{
			bankTagsService.openBankTag(active, BankTagsService.OPTION_ALLOW_MODIFICATIONS);
		}
	}

	/** Adds or removes the Combo Tags side-panel nav button to match the "Show side panel" config. EDT-safe. */
	private void updateSidePanel()
	{
		boolean show = config.showSidePanel();
		SwingUtilities.invokeLater(() ->
		{
			if (comboNavButton == null)
			{
				return;
			}
			if (show && !navButtonAdded)
			{
				clientToolbar.addNavigation(comboNavButton);
				navButtonAdded = true;
			}
			else if (!show && navButtonAdded)
			{
				clientToolbar.removeNavigation(comboNavButton);
				navButtonAdded = false;
			}
		});
	}

	// ======================================================================================================
	// Events
	// ======================================================================================================

	@Subscribe
	public void onProfileChanged(ProfileChanged e)
	{
		lastProfile = configManager.getProfile();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (CONFIG_GROUP.equals(event.getGroup()))
		{
			if (ComboStore.CONFIG_KEY.equals(event.getKey()))
			{
				comboColorCache = null;
				comboColorObjCache = null;
				comboHideHighlightCache = null;
				invalidateComboCellGroupsCache();
				clientThread.invokeLater(() ->
				{
					if (client.getGameState() == GameState.LOGGED_IN)
					{
						maintainAllComboCoreTabs();
						bankSearch.layoutBank();
					}
				});
			}
			if ("showSidePanel".equals(event.getKey()))
			{
				updateSidePanel();
			}
		}
		else if (BankTagsPlugin.CONFIG_GROUP.equals(event.getGroup())
			&& BankTagsPlugin.TAG_TABS_CONFIG.equals(event.getKey()))
		{
			handlePotentialTagRename(event);
		}
	}

	/** Detects a tab rename/delete from the bank-tags tab list and moves/drops the per-tab combo data. */
	private void handlePotentialTagRename(ConfigChanged event)
	{
		// Profile changes can look like tag renames; don't mutate config in that case (it can lose data).
		if (lastProfile.getId() != configManager.getProfile().getId())
		{
			return;
		}
		Set<String> oldTags = new HashSet<>(Text.fromCSV(event.getOldValue() == null ? "" : event.getOldValue()));
		Set<String> newTags = new HashSet<>(Text.fromCSV(event.getNewValue() == null ? "" : event.getNewValue()));
		Iterator<String> iter = oldTags.iterator();
		while (iter.hasNext())
		{
			if (newTags.remove(iter.next()))
			{
				iter.remove();
			}
		}
		// A single tab deleted with nothing renamed in: drop its combo data. RuneLite deletes tabs one at a
		// time, so an empty newTags with more than one removed tag is a bulk rewrite, not real deletions.
		if (newTags.isEmpty())
		{
			if (oldTags.size() == 1)
			{
				clientThread.invokeLater(() -> removeComboDataForTab(oldTags.iterator().next()));
			}
			return;
		}
		if (oldTags.size() != 1 || newTags.size() != 1)
		{
			return;
		}
		String oldName = oldTags.iterator().next();
		String newName = newTags.iterator().next();
		clientThread.invokeLater(() -> migrateComboDataForTab(oldName, newName));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// The bank changed → combo winners may have rotated. Refresh each host tab's membership + pinned
		// winners NOW, before the bank rebuilds, so RuneLite renders the new winners in place.
		if (event.getContainerId() == InventoryID.BANK.getId())
		{
			comboResolver.invalidateBankCache();
			List<String> hostTags = comboHostTags();
			registerComboBankTags(hostTags);
			maintainAllComboCoreTabs(hostTags);
		}
	}

	@Subscribe(priority = -1f) // run after the Bank Tags plugin's own post-build handling
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.BANKMAIN_BUILD)
		{
			return;
		}
		// If RuneLite moved a combo winner (e.g. the user dragged it), make the cell's index follow it so the
		// box stays on the item instead of snapping back on the next maintain.
		String tag = getCurrentComboTag();
		if (tag != null && isVanillaLayoutEnabled(tag))
		{
			reconcileComboCoreCells(tag);
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened e)
	{
		customizeComboCellMenu(e);
	}

	@Subscribe
	public void onPostMenuSort(PostMenuSort e)
	{
		relabelComboGhostHover();
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		Widget bank = client.getWidget(ComponentID.BANK_CONTAINER);
		if (bank == null || bank.isHidden())
		{
			return;
		}
		if (event.getMenuAction() != MenuAction.RUNELITE_OVERLAY && event.getMenuAction() != MenuAction.RUNELITE)
		{
			return;
		}
		String option = event.getMenuOption();
		if (REMOVE_COMBO.equals(option))
		{
			removeComboCell(event.getParam0());
			event.consume();
		}
		else if (REPLACE_COMBO_WITH_ITEM.equals(option))
		{
			replaceComboCellWithItem(event.getParam0());
			event.consume();
		}
	}

	// ---- MouseListener (only the press capture matters; the rest are pass-through) ----

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (e.getButton() == MouseEvent.BUTTON1)
		{
			comboDragPressX = e.getX();
			comboDragPressY = e.getY();
			Widget items = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
			comboDragPressScroll = items != null ? items.getScrollY() : 0;
		}
		return e;
	}

	@Override public MouseEvent mouseClicked(MouseEvent e) { return e; }
	@Override public MouseEvent mouseReleased(MouseEvent e) { return e; }
	@Override public MouseEvent mouseEntered(MouseEvent e) { return e; }
	@Override public MouseEvent mouseExited(MouseEvent e) { return e; }
	@Override public MouseEvent mouseDragged(MouseEvent e) { return e; }
	@Override public MouseEvent mouseMoved(MouseEvent e) { return e; }

	// ======================================================================================================
	// CORE (built-in) layout combo cells.
	// ======================================================================================================

	/** Tags currently hosting at least one combo cell (from the {@code comboslots_<tag>} config keys). */
	private List<String> comboHostTags()
	{
		List<String> tags = new ArrayList<>();
		String prefix = CONFIG_GROUP + "." + ComboSlots.CONFIG_KEY_PREFIX;
		for (String key : configManager.getConfigurationKeys(CONFIG_GROUP))
		{
			if (key.startsWith(prefix))
			{
				tags.add(key.substring(prefix.length()));
			}
		}
		return tags;
	}

	private void registerComboBankTags()
	{
		registerComboBankTags(comboHostTags());
	}

	private void registerComboBankTags(List<String> hostTags)
	{
		for (String hostTag : hostTags)
		{
			ensureComboBankTagRegistered(hostTag);
		}
	}

	/** Registers a {@link ComboBankTag} for the host tag if it has none yet; returns true if newly registered. */
	private boolean ensureComboBankTagRegistered(String hostTag)
	{
		if (comboBankTags.containsKey(hostTag))
		{
			return false;
		}
		ComboBankTag t = new ComboBankTag();
		comboBankTags.put(hostTag, t);
		tagManager.registerTag(hostTag, t);
		return true;
	}

	private void unregisterComboBankTags()
	{
		for (String hostTag : comboBankTags.keySet())
		{
			tagManager.unregisterTag(hostTag);
		}
		comboBankTags.clear();
	}

	/** Drops every per-tab combo trace of a deleted host tab (cells + registered ComboBankTag). Client thread. */
	private void removeComboDataForTab(String hostTag)
	{
		boolean hadCells = !ComboSlots.read(configManager, hostTag).isEmpty();
		ComboSlots.write(configManager, hostTag, Collections.emptyList());
		invalidateComboCellGroupsCache();
		lastCorePinnedWinners.remove(hostTag);
		comboMembersUntagged.remove(hostTag);
		if (comboBankTags.remove(hostTag) != null)
		{
			tagManager.unregisterTag(hostTag);
		}
		if (hadCells)
		{
			log.debug("removed combo cells for deleted tab '{}'", hostTag);
		}
	}

	/** Moves a renamed host tab's per-tab combo data (cells + ComboBankTag) to the new name. Client thread. */
	private void migrateComboDataForTab(String oldTag, String newTag)
	{
		if (oldTag.equalsIgnoreCase(newTag))
		{
			return;
		}
		List<ComboSlots.Slot> slots = ComboSlots.read(configManager, oldTag);
		boolean wasHost = comboBankTags.containsKey(oldTag);
		if (slots.isEmpty() && !wasHost)
		{
			return;
		}
		ComboSlots.write(configManager, newTag, slots);
		ComboSlots.write(configManager, oldTag, Collections.emptyList());
		invalidateComboCellGroupsCache();
		lastCorePinnedWinners.remove(oldTag);
		comboMembersUntagged.remove(oldTag);
		if (comboBankTags.remove(oldTag) != null)
		{
			tagManager.unregisterTag(oldTag);
		}
		if (!slots.isEmpty())
		{
			ensureComboBankTagRegistered(newTag);
			maintainComboCoreTab(newTag);
		}
		log.debug("migrated combo data on rename '{}' -> '{}'", oldTag, newTag);
	}

	/** The Layout to mutate for a host tag: the LIVE active one (if that tab is open) else the saved copy. */
	private net.runelite.client.plugins.banktags.tabs.Layout coreLayoutFor(String hostTag)
	{
		boolean active = hostTag.equalsIgnoreCase(bankTagsService.getActiveTag());
		net.runelite.client.plugins.banktags.tabs.Layout live = active ? bankTagsService.getActiveLayout() : null;
		return live != null ? live : layoutManager.loadLayout(hostTag);
	}

	private void maintainAllComboCoreTabs()
	{
		maintainAllComboCoreTabs(comboHostTags());
	}

	private void maintainAllComboCoreTabs(List<String> hostTags)
	{
		// Skip while the bank container hasn't loaded yet: with a null BANK container every winner resolves as a
		// ghost, so we'd pin ghosts / clear ownedWinners. The real first BANK change runs maintain properly.
		if (client.getItemContainer(InventoryID.BANK) == null)
		{
			return;
		}
		for (String hostTag : hostTags)
		{
			maintainComboCoreTab(hostTag);
		}
	}

	/**
	 * Makes RuneLite render a host tab's combo cells from our live resolution: refreshes owned-winner
	 * membership, pins each cell's winner into the core layout, and scrubs stray member copies. Client thread.
	 */
	private void maintainComboCoreTab(String hostTag)
	{
		if (!isVanillaLayoutEnabled(hostTag))
		{
			return;
		}
		List<ComboSlots.Slot> slots = ComboSlots.read(configManager, hostTag);

		Map<Integer, Integer> cellToWinner = new HashMap<>();
		Set<Integer> ownedWinners = new HashSet<>();
		Set<Integer> memberBases = new HashSet<>();
		for (ComboSlots.Slot s : slots)
		{
			memberBases.addAll(comboResolver.orderedMemberBases(s.getGroup()));
			int winner = comboResolver.resolveWinner(s.getGroup());
			if (winner > 0)
			{
				cellToWinner.put(s.getIndex(), winner);
				if (comboResolver.isOwnedReal(winner))
				{
					ownedWinners.add(winner);
				}
			}
		}

		ComboBankTag bankTag = comboBankTags.get(hostTag);
		if (bankTag != null)
		{
			bankTag.setOwnedWinners(ownedWinners);
		}
		untagComboMembersOnce(hostTag, memberBases);

		boolean active = hostTag.equalsIgnoreCase(bankTagsService.getActiveTag());
		net.runelite.client.plugins.banktags.tabs.Layout live = active ? bankTagsService.getActiveLayout() : null;
		if (live != null)
		{
			// Active tab: mutate the LIVE layout object RuneLite is rendering (config-only edits don't take
			// effect until reopen). Persist the change too: when a tab newly becomes a combo host the caller
			// follows up with recaptureActiveComboCoreTab(), whose openBankTag reloads the layout fresh from
			// config (loadLayout) to install the OR'd ComboBankTag filter. An un-saved pin/scrub would be
			// discarded by that reload — the stale layout still lists every member id at its old position
			// (untagging an item does NOT remove it from the layout int[]), so the non-winner members linger
			// as ghosts and the winner can render at its old slot instead of the cell, until a second pass.
			// Saving keeps config in sync so the reopen reloads the cleaned layout (matches removeComboCell /
			// replaceComboCellWithItem, which already save the active layout).
			lastCorePinnedWinners.remove(hostTag);
			if (applyComboCellsToCore(live, cellToWinner, memberBases))
			{
				layoutManager.saveLayout(live);
			}
			return;
		}
		// Inactive tab: operate on the SAVED layout copy; skip the load+save churn when nothing changed.
		if (cellToWinner.equals(lastCorePinnedWinners.get(hostTag)))
		{
			return;
		}
		net.runelite.client.plugins.banktags.tabs.Layout core = layoutManager.loadLayout(hostTag);
		if (core == null)
		{
			return;
		}
		if (applyComboCellsToCore(core, cellToWinner, memberBases))
		{
			layoutManager.saveLayout(core);
		}
		lastCorePinnedWinners.put(hostTag, new HashMap<>(cellToWinner));
	}

	/** Pins each cell's winner at its position and removes any combo-member item sitting OUTSIDE its cell. */
	private boolean applyComboCellsToCore(net.runelite.client.plugins.banktags.tabs.Layout core,
		Map<Integer, Integer> cellToWinner, Set<Integer> memberBases)
	{
		boolean changed = false;
		int[] l = core.getLayout();
		for (int pos = 0; pos < l.length; pos++)
		{
			int id = l[pos];
			if (id > 0 && !cellToWinner.containsKey(pos) && memberBases.contains(comboBaseOf(id)))
			{
				core.removeItemAtPos(pos);
				changed = true;
			}
		}
		for (Map.Entry<Integer, Integer> e : cellToWinner.entrySet())
		{
			if (core.getItemAtPos(e.getKey()) != e.getValue())
			{
				core.setItemAtPos(e.getValue(), e.getKey());
				changed = true;
			}
		}
		return changed;
	}

	/** Makes each combo cell's stored index FOLLOW its winner after RuneLite moves it (a drag). Client thread. */
	private boolean reconcileComboCoreCells(String hostTag)
	{
		if (!isVanillaLayoutEnabled(hostTag))
		{
			return false;
		}
		List<ComboSlots.Slot> slots = ComboSlots.read(configManager, hostTag);
		if (slots.isEmpty())
		{
			return false;
		}
		net.runelite.client.plugins.banktags.tabs.Layout core = coreLayoutFor(hostTag);
		if (core == null)
		{
			return false;
		}
		int[] l = core.getLayout();
		boolean changed = false;
		Set<Integer> claimed = new HashSet<>();
		for (ComboSlots.Slot s : slots)
		{
			int winner = comboResolver.resolveWinner(s.getGroup());
			if (winner <= 0)
			{
				continue;
			}
			int idx = s.getIndex();
			if (idx >= 0 && idx < l.length && l[idx] == winner)
			{
				claimed.add(idx);
				continue;
			}
			int found = -1;
			for (int pos = 0; pos < l.length; pos++)
			{
				if (l[pos] == winner && !claimed.contains(pos))
				{
					found = pos;
					break;
				}
			}
			if (found >= 0 && found != idx)
			{
				s.setIndex(found);
				claimed.add(found);
				changed = true;
			}
		}
		if (changed)
		{
			ComboSlots.write(configManager, hostTag, slots);
			invalidateComboCellGroupsCache();
		}
		return changed;
	}

	/**
	 * One-shot-per-session cleanup: in the core model a cell's membership comes entirely from the
	 * {@link ComboBankTag}, so NO member of the tab's combos should be individually tagged into the host tab.
	 * A stray tag makes RuneLite pull the item in and float it once its cell rotates/moves. So this untags
	 * EVERY currently-tagged member of the tab's combos (robust to position, by item id). Deferred a tick —
	 * tag mutation during the build crashes the client. Idempotent across sessions.
	 */
	private void untagComboMembersOnce(String hostTag, Set<Integer> memberBases)
	{
		if (!comboMembersUntagged.add(hostTag))
		{
			return;
		}
		List<Integer> toUntag = new ArrayList<>();
		if (!memberBases.isEmpty())
		{
			for (int id : tagManager.getItemsForTag(Text.standardize(hostTag)))
			{
				if (memberBases.contains(comboBaseOf(Math.abs(id))))
				{
					toUntag.add(id);
				}
			}
		}
		if (toUntag.isEmpty())
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			for (int id : toUntag)
			{
				removeBankTag(id, hostTag);
			}
			bankSearch.layoutBank();
		});
	}

	/** For a host tab, cell layout index → combo group name. Cached; the returned map must only be READ. */
	public Map<Integer, String> getComboCellGroups(String hostTag)
	{
		if (hostTag == null)
		{
			return Collections.emptyMap();
		}
		Map<String, Map<Integer, String>> cache = comboCellGroupsCache;
		Map<Integer, String> cached = cache.get(hostTag);
		if (cached != null)
		{
			return cached;
		}
		Map<Integer, String> result = new HashMap<>();
		for (ComboSlots.Slot slot : ComboSlots.read(configManager, hostTag))
		{
			result.put(slot.getIndex(), slot.getGroup());
		}
		cache.put(hostTag, result);
		return result;
	}

	private void invalidateComboCellGroupsCache()
	{
		comboCellGroupsCache.clear();
	}

	/** The combo group whose current (live-resolved) winner equals the given item id in the host tab, or null. */
	public String getComboGroupForItem(String hostTag, int itemId)
	{
		if (hostTag == null || itemId <= 0)
		{
			return null;
		}
		for (ComboSlots.Slot slot : ComboSlots.read(configManager, hostTag))
		{
			if (comboResolver.resolveWinner(slot.getGroup()) == itemId)
			{
				return slot.getGroup();
			}
		}
		return null;
	}

	/** First position empty in the CORE layout AND not occupied by another combo cell. */
	private int firstEmptyCorePos(net.runelite.client.plugins.banktags.tabs.Layout core, List<ComboSlots.Slot> comboSlots)
	{
		Set<Integer> comboIndices = new HashSet<>();
		for (ComboSlots.Slot s : comboSlots)
		{
			comboIndices.add(s.getIndex());
		}
		int[] l = core != null ? core.getLayout() : new int[0];
		for (int i = 0; ; i++)
		{
			boolean empty = i >= l.length || l[i] <= 0;
			if (empty && !comboIndices.contains(i))
			{
				return i;
			}
		}
	}

	/** The single per-group color (used for both the box and the item name), from the group's JSON. */
	public Color getComboColor(String comboGroup)
	{
		Map<String, Integer> colors = comboColorCache;
		if (colors == null)
		{
			colors = new HashMap<>();
			for (ComboGroup g : ComboStore.all(configManager, gson))
			{
				colors.put(g.name, g.color);
			}
			comboColorCache = colors;
			comboColorObjCache = new ConcurrentHashMap<>();
		}
		Map<String, Color> objCache = comboColorObjCache;
		Color cached = objCache.get(comboGroup);
		if (cached != null)
		{
			return cached;
		}
		Integer c = colors.get(comboGroup);
		Color color = new Color(c != null ? c : ComboGroup.DEFAULT_COLOR);
		objCache.put(comboGroup, color);
		return color;
	}

	/** Whether the combo group's bank overlay highlight is turned off (its cells render with no box/dot/etc.). */
	public boolean isComboHighlightHidden(String comboGroup)
	{
		Set<String> hidden = comboHideHighlightCache;
		if (hidden == null)
		{
			hidden = new HashSet<>();
			for (ComboGroup g : ComboStore.all(configManager, gson))
			{
				if (g.hideHighlight)
				{
					hidden.add(g.name);
				}
			}
			comboHideHighlightCache = hidden;
		}
		return hidden.contains(comboGroup);
	}

	// ======================================================================================================
	// Side-panel API (called on the EDT; everything that touches the client hops to clientThread).
	// ======================================================================================================

	/** Embeds a combo group as a smart cell in the currently-open bank tag tab. */
	public void addComboToOpenTab(String comboGroup)
	{
		clientThread.invokeLater(() ->
		{
			String hostTag = activeHostTagOrWarn("add the combo to it");
			if (hostTag == null)
			{
				return;
			}
			if (!ensureCoreLayout(hostTag))
			{
				chatMessage("Enable layout on this tab (right-click the tab → Enable layout) first.");
				return;
			}
			boolean wasHost = comboBankTags.containsKey(hostTag);

			// First behave like "Replace in tab": consolidate the combo if it's already (even loosely) present.
			ComboReplaceResult result = replaceComboIntoTab(hostTag, comboGroup);
			if (result != ComboReplaceResult.ABSENT)
			{
				if (!wasHost)
				{
					recaptureActiveComboCoreTab();
				}
				bankSearch.layoutBank();
				chatMessage((result == ComboReplaceResult.ADDED ? "Replaced" : "Cleaned up")
					+ " combo \"" + comboGroup + "\" in tab \"" + hostTag + "\".");
				return;
			}

			// Not in the tab → add a new smart cell at the first empty slot.
			List<ComboSlots.Slot> slots = ComboSlots.read(configManager, hostTag);
			int index = firstEmptyCorePos(coreLayoutFor(hostTag), slots);
			slots.add(new ComboSlots.Slot(index, comboGroup));
			ComboSlots.write(configManager, hostTag, slots);
			invalidateComboCellGroupsCache();
			ensureComboBankTagRegistered(hostTag);
			maintainComboCoreTab(hostTag);
			if (!wasHost)
			{
				recaptureActiveComboCoreTab();
			}
			bankSearch.layoutBank();
			chatMessage("Added combo \"" + comboGroup + "\" to tab \"" + hostTag + "\".");
		});
	}

	/** Consolidates a combo group's loose members already in the open tab into a single smart cell. */
	public void replaceComboInOpenTab(String comboGroup)
	{
		clientThread.invokeLater(() ->
		{
			String hostTag = activeHostTagOrWarn("replace the combo into it");
			if (hostTag == null)
			{
				return;
			}
			if (!isVanillaLayoutEnabled(hostTag))
			{
				chatMessage("Enable layout on this tab (right-click the tab → Enable layout) first.");
				return;
			}
			boolean wasHost = comboBankTags.containsKey(hostTag);
			ComboReplaceResult result = replaceComboIntoTab(hostTag, comboGroup);
			if (result == ComboReplaceResult.ABSENT)
			{
				chatMessage("Combo \"" + comboGroup + "\" isn't in tab \"" + hostTag + "\" — nothing to replace.");
				return;
			}
			if (!wasHost)
			{
				recaptureActiveComboCoreTab();
			}
			bankSearch.layoutBank();
			chatMessage((result == ComboReplaceResult.ADDED ? "Replaced" : "Cleaned up")
				+ " combo \"" + comboGroup + "\" in tab \"" + hostTag + "\".");
		});
	}

	/** Replaces EVERY combo filed under the given category into the open tab, in one client-thread pass. */
	public void replaceCategoryInOpenTab(String category)
	{
		clientThread.invokeLater(() ->
		{
			String hostTag = activeHostTagOrWarn("replace the group into it");
			if (hostTag == null)
			{
				return;
			}
			if (!isVanillaLayoutEnabled(hostTag))
			{
				chatMessage("Enable layout on this tab (right-click the tab → Enable layout) first.");
				return;
			}
			List<String> groups = new ArrayList<>();
			for (ComboGroup g : ComboStore.all(configManager, gson))
			{
				if (category.equals(g.category))
				{
					groups.add(g.name);
				}
			}
			if (groups.isEmpty())
			{
				chatMessage("No combos in group \"" + category + "\".");
				return;
			}
			boolean wasHost = comboBankTags.containsKey(hostTag);

			// One deterministic pass over the whole group (the old per-combo loop dumped any combo whose members
			// weren't pinned in the layout int[] at firstEmptyCorePos, far from the rest). Each present combo keeps
			// its own top-left-most member slot; a combo present only via tags fills the group's FREED slots
			// (member slots not used as some combo's own top-left), top-left-most first, so cells stay within the
			// footprint the group already occupied. maintainComboCoreTab then scrubs the extra member copies
			// (emptying those slots) and pins each winner at its target.
			net.runelite.client.plugins.banktags.tabs.Layout core = coreLayoutFor(hostTag);
			int[] l = core != null ? core.getLayout() : new int[0];
			List<Integer> tagged = tagManager.getItemsForTag(Text.standardize(hostTag));

			List<String> present = new ArrayList<>();
			Map<String, Integer> ownTopLeft = new HashMap<>(); // combo -> its top-left member slot (absent if tag-only)
			java.util.TreeSet<Integer> groupSlots = new java.util.TreeSet<>(); // every layout slot a group member sits in
			for (String comboGroup : groups)
			{
				Set<Integer> mb = new HashSet<>(comboResolver.orderedMemberBases(comboGroup));
				int top = Integer.MAX_VALUE;
				for (int pos = 0; pos < l.length; pos++)
				{
					if (l[pos] > 0 && mb.contains(comboBaseOf(l[pos])))
					{
						groupSlots.add(pos);
						top = Math.min(top, pos);
					}
				}
				if (top != Integer.MAX_VALUE)
				{
					present.add(comboGroup);
					ownTopLeft.put(comboGroup, top);
					continue;
				}
				for (int item : tagged)
				{
					if (mb.contains(comboBaseOf(Math.abs(item))))
					{
						present.add(comboGroup); // tagged but not pinned in the layout
						break;
					}
				}
			}
			if (present.isEmpty())
			{
				chatMessage("No combos from group \"" + category + "\" are in tab \"" + hostTag + "\" — nothing to replace.");
				return;
			}

			// Untag every member of the present combos (both tag forms).
			for (String comboGroup : present)
			{
				Set<Integer> mb = new HashSet<>(comboResolver.orderedMemberBases(comboGroup));
				for (int item : tagged)
				{
					if (mb.contains(comboBaseOf(Math.abs(item))))
					{
						removeBankTag(item, hostTag);
					}
				}
			}

			// Freed group slots = those a member sits in but no combo claims as its own top-left, top-left-most first.
			Set<Integer> claimed = new HashSet<>(ownTopLeft.values());
			List<Integer> freed = new ArrayList<>();
			for (int slot : groupSlots)
			{
				if (!claimed.contains(slot))
				{
					freed.add(slot);
				}
			}
			List<ComboSlots.Slot> slots = ComboSlots.read(configManager, hostTag);
			slots.removeIf(s -> present.contains(s.getGroup())); // re-place these combos' cells from scratch
			int freedIdx = 0;
			for (String comboGroup : present)
			{
				Integer target = ownTopLeft.get(comboGroup);
				if (target == null)
				{
					target = freedIdx < freed.size() ? freed.get(freedIdx++) : firstEmptyCorePos(core, slots);
				}
				slots.add(new ComboSlots.Slot(target, comboGroup));
			}
			ComboSlots.write(configManager, hostTag, slots);
			invalidateComboCellGroupsCache();

			ensureComboBankTagRegistered(hostTag);
			maintainComboCoreTab(hostTag);
			if (!wasHost)
			{
				recaptureActiveComboCoreTab();
			}
			bankSearch.layoutBank();
			chatMessage("Replaced group \"" + category + "\" (" + present.size() + " of " + groups.size()
				+ " combos) in tab \"" + hostTag + "\".");
		});
	}

	/** Outcome of {@link #replaceComboIntoTab}: a cell was newly placed, an existing one refreshed, or absent. */
	private enum ComboReplaceResult
	{
		ADDED, REFRESHED, ABSENT
	}

	/**
	 * Consolidates a group's loose member items already in the tab into a single smart cell: untags them and
	 * (for a new cell) places it at the top-left-most member slot. Does NOT add a combo that isn't present.
	 * Mutates the persisted comboslots and the live layout; the caller relayouts once. Client thread.
	 */
	private ComboReplaceResult replaceComboIntoTab(String hostTag, String comboGroup)
	{
		Set<Integer> memberBases = new HashSet<>(comboResolver.orderedMemberBases(comboGroup));
		List<ComboSlots.Slot> slots = ComboSlots.read(configManager, hostTag);
		ComboSlots.Slot existing = slots.stream()
			.filter(s -> s.getGroup().equals(comboGroup)).findFirst().orElse(null);

		// Find the top-left member slot in the live layout (its removal is left to maintain's scrub).
		int topLeft = Integer.MAX_VALUE;
		net.runelite.client.plugins.banktags.tabs.Layout coreLayout = coreLayoutFor(hostTag);
		if (coreLayout != null)
		{
			int[] l = coreLayout.getLayout();
			for (int pos = 0; pos < l.length; pos++)
			{
				if (l[pos] > 0 && memberBases.contains(comboBaseOf(l[pos])) && pos < topLeft)
				{
					topLeft = pos;
				}
			}
		}

		// Untag every member item from the host tag (both forms), noting whether the combo was present at all.
		boolean memberPresent = topLeft != Integer.MAX_VALUE;
		for (Integer item : tagManager.getItemsForTag(Text.standardize(hostTag)))
		{
			if (memberBases.contains(comboBaseOf(Math.abs(item))))
			{
				removeBankTag(item, hostTag);
				memberPresent = true;
			}
		}

		if (existing == null)
		{
			if (!memberPresent)
			{
				return ComboReplaceResult.ABSENT;
			}
			if (topLeft == Integer.MAX_VALUE)
			{
				topLeft = firstEmptyCorePos(coreLayoutFor(hostTag), slots);
			}
			slots.add(new ComboSlots.Slot(topLeft, comboGroup));
			ComboSlots.write(configManager, hostTag, slots);
			invalidateComboCellGroupsCache();
		}

		ensureComboBankTagRegistered(hostTag);
		maintainComboCoreTab(hostTag);
		return existing == null ? ComboReplaceResult.ADDED : ComboReplaceResult.REFRESHED;
	}

	/** Removes the combo cell at the given layout index from the currently-open tab. */
	private void removeComboCell(int index)
	{
		String hostTag = getCurrentComboTag();
		if (hostTag == null)
		{
			return;
		}
		List<ComboSlots.Slot> slots = ComboSlots.read(configManager, hostTag);
		if (slots.removeIf(s -> s.getIndex() == index))
		{
			ComboSlots.write(configManager, hostTag, slots);
			invalidateComboCellGroupsCache();
			if (isVanillaLayoutEnabled(hostTag))
			{
				net.runelite.client.plugins.banktags.tabs.Layout core = coreLayoutFor(hostTag);
				if (core != null)
				{
					core.removeItemAtPos(index);
					layoutManager.saveLayout(core);
				}
				maintainComboCoreTab(hostTag);
			}
			bankSearch.layoutBank();
		}
	}

	/** Removes the combo cell and tags the item it currently shows into the tab at the cell's spot. */
	private void replaceComboCellWithItem(int index)
	{
		String hostTag = getCurrentComboTag();
		if (hostTag == null)
		{
			return;
		}
		List<ComboSlots.Slot> slots = ComboSlots.read(configManager, hostTag);
		ComboSlots.Slot cell = slots.stream().filter(s -> s.getIndex() == index).findFirst().orElse(null);
		if (cell == null)
		{
			return;
		}
		int winner = comboResolver.resolveWinner(cell.getGroup());
		slots.removeIf(s -> s.getIndex() == index);
		ComboSlots.write(configManager, hostTag, slots);
		invalidateComboCellGroupsCache();
		if (winner > 0)
		{
			// Promote the winner to a normal tagged + laid-out item. It's now individually tagged, so the
			// maintain scrub (which only removes UNtagged member floats) leaves it alone.
			tagManager.addTag(winner, hostTag, false);
			net.runelite.client.plugins.banktags.tabs.Layout coreLayout = coreLayoutFor(hostTag);
			if (coreLayout != null)
			{
				coreLayout.setItemAtPos(winner, index);
				layoutManager.saveLayout(coreLayout);
			}
		}
		maintainComboCoreTab(hostTag);
		bankSearch.layoutBank();
	}

	/** Opens the Combo Tags side panel and shows the given combo's editor (the ghost's "Edit" action). */
	private void openComboInPanel(String comboGroup)
	{
		SwingUtilities.invokeLater(() ->
		{
			clientToolbar.openPanel(comboNavButton);
			comboPanel.openCombo(comboGroup);
		});
	}

	// ======================================================================================================
	// Menu customization
	// ======================================================================================================

	/**
	 * For a combo cell under the cursor: drop RuneLite's per-item tag/layout entries and add the combo actions
	 * (remove the cell / replace it with the item it shows). The actions sit just under the Withdraw block so
	 * Withdraw stays the default left-click. Returns true if the menu was a combo cell's.
	 */
	private boolean customizeComboCellMenu(MenuOpened event)
	{
		Widget bankContainer = client.getWidget(ComponentID.BANK_CONTAINER);
		if (bankContainer == null || bankContainer.isHidden())
		{
			return false;
		}
		String hostTag = getCurrentComboTag();
		if (hostTag == null || !isVanillaLayoutEnabled(hostTag))
		{
			return false;
		}
		int index = getMouseIndex();
		if (index == -1)
		{
			return false;
		}
		String group = getComboCellGroups(hostTag).get(index);
		if (group == null)
		{
			return false;
		}

		MenuEntry[] entries = client.getMenuEntries();
		List<MenuEntry> kept = new ArrayList<>(entries.length);
		for (MenuEntry e : entries)
		{
			String opt = e.getOption();
			if (opt != null && (opt.startsWith("Remove-tag")
				|| opt.startsWith("Edit-tags")
				|| opt.startsWith("Duplicate-item")
				|| opt.equals(OPEN_COMBO)
				|| opt.startsWith(REMOVE_FROM_LAYOUT_MENU_OPTION)))
			{
				continue;
			}
			kept.add(e);
		}

		int insertAt = kept.size();
		for (int i = 0; i < kept.size(); i++)
		{
			String opt = kept.get(i).getOption();
			if (opt != null && opt.startsWith("Withdraw"))
			{
				insertAt = i;
				break;
			}
		}
		client.setMenuEntries(kept.toArray(new MenuEntry[0]));

		int at = insertAt;
		int winner = comboResolver.resolveWinner(group);
		if (winner > 0)
		{
			client.createMenuEntry(at++)
				.setOption(REPLACE_COMBO_WITH_ITEM)
				.setType(MenuAction.RUNELITE_OVERLAY)
				.setTarget(ColorUtil.wrapWithColorTag(itemName(winner), itemTooltipColor))
				.setParam0(index);
		}
		client.createMenuEntry(at++)
			.setOption(REMOVE_COMBO)
			.setType(MenuAction.RUNELITE_OVERLAY)
			.setTarget(ColorUtil.wrapWithColorTag(comboDisplayName(group), itemTooltipColor))
			.setParam0(index);
		client.createMenuEntry(at)
			.setOption(OPEN_COMBO)
			.setType(MenuAction.RUNELITE_OVERLAY)
			.setTarget(ColorUtil.wrapWithColorTag(comboDisplayName(group), itemTooltipColor))
			.setParam0(index)
			.onClick(me -> openComboInPanel(group));
		return true;
	}

	/**
	 * An unowned combo ghost is RuneLite's own placeholder, whose closed-menu hover defaults to
	 * "Duplicate-item". Relabel that to "Edit &lt;name&gt;" in the group color (still deprioritized, so a
	 * left-click opens the menu). Once the menu opens, {@link #customizeComboCellMenu} owns the entries.
	 */
	private void relabelComboGhostHover()
	{
		if (client.isMenuOpen())
		{
			return;
		}
		Widget bankContainer = client.getWidget(ComponentID.BANK_CONTAINER);
		if (bankContainer == null || bankContainer.isHidden())
		{
			return;
		}
		String hostTag = getCurrentComboTag();
		if (hostTag == null || !isVanillaLayoutEnabled(hostTag))
		{
			return;
		}
		int index = getMouseIndex();
		if (index == -1)
		{
			return;
		}
		String group = getComboCellGroups(hostTag).get(index);
		if (group == null)
		{
			return;
		}
		int winner = comboResolver.resolveWinner(group);
		if (winner <= 0 || comboResolver.isOwnedReal(winner))
		{
			return; // only the unowned ghost
		}
		for (MenuEntry e : client.getMenuEntries())
		{
			String opt = e.getOption();
			if (opt != null && opt.startsWith("Duplicate-item"))
			{
				e.setOption(OPEN_COMBO)
					.setTarget(ColorUtil.wrapWithColorTag(comboDisplayName(group), itemTooltipColor))
					.onClick(me -> openComboInPanel(group));
				break;
			}
		}
	}

	// ======================================================================================================
	// Helpers
	// ======================================================================================================

	/** The stats-aware combo base of a raw item id (placeholder-resolved). Client thread. */
	private int comboBaseOf(int itemId)
	{
		return ItemIndex.comboBaseOf(itemManager, itemId);
	}

	/** A combo group's member base ids in priority order (the manual list order). */
	public List<Integer> orderedComboMembers(String comboGroup)
	{
		return comboResolver.orderedMemberBases(comboGroup);
	}

	/** The active bank tag tab name if it's a normal tag tab (not the tab list / an inventory setup), else null. */
	public String getCurrentComboTag()
	{
		String tag = tabInterface.getActiveTag();
		if (tag == null || tag.isEmpty() || tag.equals("tagtabs") || tag.startsWith("_invsetup_"))
		{
			return null;
		}
		return tag;
	}

	private String activeHostTagOrWarn(String action)
	{
		String tag = getCurrentComboTag();
		if (tag == null)
		{
			chatMessage("Open a bank tag tab first, then " + action + ".");
		}
		return tag;
	}

	/** Whether the tab has RuneLite's built-in layout enabled (and the tab still exists). */
	public boolean isVanillaLayoutEnabled(String tag)
	{
		return tag != null && tabManager.find(tag) != null && hasRuneliteLayout(tag);
	}

	private boolean hasRuneliteLayout(String tag)
	{
		return configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP, "layout_" + Text.standardize(tag)) != null;
	}

	/** Ensures the tab has a built-in layout (creates an empty one if absent). Returns whether it's now enabled. */
	private boolean ensureCoreLayout(String tag)
	{
		if (hasRuneliteLayout(tag))
		{
			return true;
		}
		if (tabManager.find(tag) == null)
		{
			return false;
		}
		layoutManager.saveLayout(new net.runelite.client.plugins.banktags.tabs.Layout(tag));
		return true;
	}

	public boolean isPlaceholder(int id)
	{
		ItemComposition itemComposition = itemManager.getItemComposition(id);
		return itemComposition.getPlaceholderTemplateId() == PLACEHOLDER_TEMPLATE_ID;
	}

	public String itemName(Integer itemId)
	{
		return (itemId == null) ? "null" : itemManager.getItemComposition(itemId).getName();
	}

	/** A combo group's display name with the storage brackets stripped (e.g. "[MeleeLegs]" → "MeleeLegs"). */
	private static String comboDisplayName(String group)
	{
		return (group.length() >= 2 && group.startsWith("[") && group.endsWith("]"))
			? group.substring(1, group.length() - 1) : group;
	}

	/**
	 * Removes a tag from an item in BOTH the non-variation and variation (negative-base) forms, mirroring the
	 * bank-tags item config. {@code TagManager.removeTag} only clears the non-variation key, so a
	 * variation-tagged item (the default) otherwise survives and gets pulled back into the tab.
	 */
	private void removeBankTag(int itemId, String bankTagName)
	{
		removeBankTagForm(itemId, bankTagName, false);
		removeBankTagForm(itemId, bankTagName, true);
	}

	private void removeBankTagForm(int itemId, String bankTagName, boolean variation)
	{
		int id = itemManager.canonicalize(Math.abs(itemId));
		if (variation)
		{
			id = net.runelite.client.game.ItemVariationMapping.map(id) * -1;
		}
		String key = BANKTAGS_ITEM_KEY_PREFIX + id;
		String config = configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP, key);
		if (config == null || config.isEmpty())
		{
			return;
		}
		String target = Text.standardize(bankTagName);
		List<String> tags = new ArrayList<>(Text.fromCSV(config));
		if (!tags.removeIf(t -> Text.standardize(t).equals(target)))
		{
			return;
		}
		if (tags.isEmpty())
		{
			configManager.unsetConfiguration(BankTagsPlugin.CONFIG_GROUP, key);
		}
		else
		{
			configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, key, Text.toCSV(tags));
		}
	}

	private void chatMessage(String message)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, "");
	}

	int getMouseIndex()
	{
		Widget bankItemContainer = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
		if (bankItemContainer == null)
		{
			return -1;
		}
		Point mouseCanvasPosition = client.getMouseCanvasPosition();
		int mouseX = mouseCanvasPosition.getX();
		int mouseY = mouseCanvasPosition.getY();
		Rectangle bankBounds = bankItemContainer.getBounds();
		if (!bankBounds.contains(new java.awt.Point(mouseX, mouseY)))
		{
			return -1;
		}
		Point canvasLocation = bankItemContainer.getCanvasLocation();
		int relativeY = mouseY - canvasLocation.getY() + bankItemContainer.getScrollY() + 2;
		int row = relativeY / ROW_HEIGHT;
		int relativeX = mouseX - canvasLocation.getX() - 51 + 6;
		int col = relativeX / COLUMN_WIDTH;
		int index = row * 8 + col;
		if (row < 0 || col < 0 || col > 7 || index < 0)
		{
			return -1;
		}
		int xDistanceIntoItem = relativeX % COLUMN_WIDTH;
		int yDistanceIntoItem = relativeY % ROW_HEIGHT;
		if (xDistanceIntoItem < 6 || xDistanceIntoItem >= 42 || yDistanceIntoItem < 2 || yDistanceIntoItem >= 34)
		{
			return -1;
		}
		return index;
	}

	public static int getXForIndex(int index)
	{
		return (index % 8) * COLUMN_WIDTH + 51;
	}

	public static int getYForIndex(int index)
	{
		return (index / 8) * BANK_ITEM_WIDTH;
	}

	// ======================================================================================================
	// One-time migration from the old fork's config group (banktaglayouts).
	// ======================================================================================================

	/**
	 * Copies combo data created by the old bank-tag-custom-layouts fork (under the {@code banktaglayouts}
	 * config group) into this plugin's {@code combotags} group, once, if we have no combos yet. The host tab
	 * names referenced by {@code comboslots_<tag>} still exist in RuneLite's bank tags, so cells re-pin on the
	 * next bank build. Idempotent: after the first copy our {@code combos} key is set, so it never runs again.
	 */
	private void migrateLegacyComboData()
	{
		final String legacyGroup = "banktaglayouts";
		String existing = configManager.getConfiguration(CONFIG_GROUP, ComboStore.CONFIG_KEY);
		if (existing != null && !existing.isEmpty())
		{
			return; // already have combos in the new group — nothing to migrate
		}
		boolean migratedAny = false;
		for (String fullKey : configManager.getConfigurationKeys(legacyGroup))
		{
			// keys come back as "<group>.<key>"; strip the group prefix.
			int dot = fullKey.indexOf('.');
			String key = dot >= 0 ? fullKey.substring(dot + 1) : fullKey;
			if (!isLegacyComboKey(key))
			{
				continue;
			}
			String value = configManager.getConfiguration(legacyGroup, key);
			if (value != null && !value.isEmpty())
			{
				configManager.setConfiguration(CONFIG_GROUP, key, value);
				migratedAny = true;
			}
		}
		if (migratedAny)
		{
			log.info("Migrated combo data from the legacy '{}' config group.", legacyGroup);
		}
	}

	private static boolean isLegacyComboKey(String key)
	{
		return key.equals(ComboStore.CONFIG_KEY)
			|| key.equals(ComboStore.CATEGORIES_KEY)
			|| key.equals(ComboStore.CATEGORY_ORDER_KEY)
			|| key.equals(ComboStore.UNGROUPED_COLLAPSED_KEY)
			|| key.startsWith(ComboSlots.CONFIG_KEY_PREFIX);
	}
}
