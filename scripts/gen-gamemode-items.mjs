#!/usr/bin/env node
// Regenerates src/main/resources/com/combotags/gamemode_items.tsv: the game-mode (Leagues, Deadman, beta/speedrun
// worlds), PvP-minigame (LMS, PvP Arena), player-owned-house, interface (guides), Grand Exchange item set and
// unobtainable-copy items the item search excludes (ItemFilters.isExcluded). The same rules are the gm-* / pvp-* / poh
// / guides / ge-set / unobtainable exclude rules in the filter lab's rules.js on the webviewer branch; keep the two
// in step.
//
// Source: RuneLite's net/runelite/api/gameval/ItemID.java, which carries Jagex's internal item names (LEAGUE_*,
// DEADMAN_*, BR_* = Last Man Standing, PVPA_* = PvP Arena) and each item's display name in its javadoc.
// Internal names are the only reliable signal: many of these items share a display name with a normal item
// (an LMS "Rune scimitar") or have a placeholder one (league point counters are all "Cabbage").
//
// It also bakes in the inventory-action rules for items that are neither wearable nor consumable: books, notes and
// maps (Read, Study, Look-at, Inspect, Check steps), lamps and XP rewards (Rub), and quest-only items (Destroy is
// their only action). Actions and equipment stats come from the filter lab's data/items.js (built by its
// build-data.mjs from the game cache dump), so the plugin only does a set lookup; regenerate after a game update.
//
// Usage (after a RuneLite update, and `npm run data` in the filter lab):
//   unzip -p ~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/<ver>/*/runelite-api-<ver>-sources.jar \
//     net/runelite/api/gameval/ItemID.java > /tmp/ItemID.java
//   node scripts/gen-gamemode-items.mjs /tmp/ItemID.java <ver> [--items <path/to/webviewer/data/items.js>]
// --items defaults to the combo-tags-webviewer worktree next to this repo.
//
// Rule of thumb: hide items that only exist inside the mode, never permanent rewards you can keep in a main-game
// bank (Trailblazer outfits, Infernal tools (or), trophies, Victor's capes, PvP Arena imbues, BH gear). The PvP
// Arena's other rewards failed their poll and were never released, so they're excluded with its loadout copies.
import fs from 'fs';
import vm from 'vm';

const args = process.argv.slice(2);
const flag = args.indexOf('--items');
const itemsPath = flag >= 0 ? args.splice(flag, 2)[1]
	: new URL('../../combo-tags-webviewer/webviewer/data/items.js', import.meta.url);
const [itemIdPath, version = 'unknown'] = args;
if (!itemIdPath)
{
	console.error('usage: node scripts/gen-gamemode-items.mjs <path/to/ItemID.java> [runelite-version] [--items <data/items.js>]');
	process.exit(1);
}
if (!fs.existsSync(itemsPath))
{
	console.error(`can't find the filter lab's item data at ${itemsPath}; run \`npm run data\` in the lab or pass --items`);
	process.exit(1);
}
const sandbox = { window: {} };
vm.runInNewContext(fs.readFileSync(itemsPath, 'utf8'), sandbox);
const data = sandbox.window.ITEM_DATA;
if (data.version !== version)
{
	console.warn(`warning: the lab's item data is from RuneLite ${data.version}, not ${version}; rebuild it with \`npm run data\``);
}

// Top-level constants only; the nested Noted/Placeholder classes are indented further and skipped.
const items = [];
let display = null;
for (const line of fs.readFileSync(itemIdPath, 'utf8').split(/\r?\n/))
{
	const doc = line.match(/^\t \* (.+)$/);
	if (doc)
	{
		display = doc[1].trim().replace(/&#38;/g, '&');
		continue;
	}
	const m = line.match(/^\tpublic static final int ([A-Z0-9_]+) = (\d+);/);
	if (m)
	{
		items.push({ id: +m[2], key: m[1], name: display ?? '' });
	}
	if (!/^\t( \*|\/\*\*| \*\/)/.test(line))
	{
		display = null;
	}
}
const named = items.filter(i => i.name && i.name !== 'Null');

// Display names of items outside the PvP Arena, for "is this PVPA_ item a copy of a normal item?".
const normalNames = new Set(named.filter(i => !i.key.startsWith('PVPA_')).map(i => i.name.toLowerCase()));

// Inventory actions and "neither wearable nor consumable", from the lab's item data.
const actionsOf = i => (data.actions[i.id] ?? []).filter(Boolean);
const isConsumable = i => actionsOf(i).some(a => /^(eat|drink)$/i.test(a));
const isMisc = i => !data.stats[i.id]?.equipable && !isConsumable(i);
const READ_ACTIONS = new Set(['Read', 'Study', 'Look-at', 'Inspect', 'Check steps']);
const QUIET_ACTIONS = new Set(['Destroy', 'Use', 'Examine']);

const RULES = [
	// ---- GAMEMODE: league / deadman / beta / speedrun worlds
	['GAMEMODE', 'point counters (display as "Cabbage")', i => /(^|_)(LEAGUE(_\d)?|TRAILBLAZER(_RELOADED)?|TWISTED|DEADMAN|GRIDMASTER|SPEEDRUN)_POINTS(_NEGATIVE)?$|^SPEEDRUN_TROPHIES_/.test(i.key)],
	['GAMEMODE', 'league scroll boxes', i => /^LEAGUE_CLUE_BOX_/.test(i.key)],
	// Shattered Relics sigils (attuned and unattuned); every SIGIL_OF_* key is one, no main-game item shares the prefix.
	['GAMEMODE', 'league sigils', i => /^SIGIL_OF_/.test(i.key)],
	['GAMEMODE', 'league relic / league-world tools', i => new RegExp('^(' + [
		'LEAGUE_TRAILBLAZER_(FAIRYS_FLIGHT|LAST_RECALL)_TELEPORT', 'LEAGUE_SECOND_INVENTORY(_OPEN)?',
		'LEAGUE_TRAILBLAZER_(AXE|PICKAXE|HARPOON)', 'LEAGUE_TOMATO', 'LEAGUE_3_FRAGMENT_.*', 'LEAGUE_3_KEY_AREA_TELEPORT',
		'LEAGUE_3_MAGIC_BOOK', 'LEAGUE_3_CARDTHROW', 'LEAGUE_TRAILBLAZER_GLOBETROTTER_NECKLACE', 'LEAGUE_BANKERS_NOTE',
		'LEAGUE_GUARDIAN_SUMMON', 'LEAGUE_RELIC_AGILITY_BOOTS', 'LEAGUE_EXECUTIONER_WEAPON', 'LEAGUE_RUINOUS_POWERS_BOOK',
		'LEAGUE_BANK_HEIST_TELEPORT', 'LEAGUE_CLUE_COMPASS_TELEPORT', 'LEAGUE_RELIC_POCKET_KINGDOM_ITEM',
		'LEAGUES_MEGARARE_VOUCHER', 'LEAGUE_BARBARIAN_ARM', 'LEAGUE_EVIL_EYE_NECKLACE', 'LEAGUE_TRANSMUTE_BOOK',
		'LEAGUE_SOUL_SHARD', 'LEAGUE_AGILITY_MAP', 'LEAGUE_CLUE_CONTRACT', 'LEAGUE_BUTLERS_BELL',
		'LEAGUE_FLASK_OF_FERVOUR(_EMPTY)?', 'LEAGUE_6_DEMONIC_QUILL', 'LEAGUE_6_THRALL_CONTRACT',
	].join('|') + ')$').test(i.key)],
	// Deadman-world items, except the permanent cosmetics and unlock scrolls you keep after the season, and the
	// "(Deadman)" versions those scrolls make of main-game items ("Voidwaker (Deadman)", "Imbued Zamorak cape
	// (Deadman)"). The deadman-world copies of gear are "Corrupted ..." (DEADMAN_BLIGHTED_*) and stay excluded.
	['GAMEMODE', 'deadman-world items', i => /^DEADMAN/.test(i.key)
		&& !/^DEADMAN_(BODY|LEGS|CAPE|RUG|SKULL)$|^DEADMAN_20\d\d_(WEAPON_ORNAMENT|CAPE_ORNAMENT|POH_ORNAMENT|HOME_TELEPORT)_SCROLL$/.test(i.key)
		&& !/ \(Deadman\)$/.test(i.name)],
	['GAMEMODE', 'beta-world copies', i => /^(BETA_|WBR_)/.test(i.key)],
	['GAMEMODE', 'speedrun / PvP tutorial worlds', i => /^(SPEEDY_TELEPORT_SCROLL|PVPTUT_.*)$/.test(i.key)],

	// ---- MINIGAME: items that never leave their minigame
	// The Gauntlet's crystal and corrupted gear, tools and resources (and the Raging Echoes copy of its perfected
	// dagger, ECHO_GAUNTLET_*). The Gauntlet cape is a reward you keep; the Youngllef pets are GAUNTLETPET, no match.
	['MINIGAME', 'Gauntlet items (GAUNTLET_), not the Gauntlet cape',
		i => /GAUNTLET_/.test(i.key) && i.key !== 'GAUNTLET_CRYSTALLINE_CAPE'],
	// Soul Wars in-game items by id: team capes, bones, bandages, barricades, potions and the 33 team icons. Its
	// rewards come after the range (Ectoplasmator 25340, Spoils of War 25342) and stay.
	['MINIGAME', 'Soul Wars in-game items and team icons (ids 25195-25243)', i => i.id >= 25195 && i.id <= 25243],

	// ---- PVP: Last Man Standing and PvP Arena
	// LMS gear never leaves the minigame; its reward-shop items (Victor's capes, the Deadman's set) are kept.
	['PVP', 'Last Man Standing items', i => /^BR_/.test(i.key) && !/^BR_(CAPE_\d+|DEADMAN_.*)$/.test(i.key)],
	// PvP Arena: its imbues are real ("Slayer helmet (i)" PVPA_SLAYER_HELM_I, "Salve amulet(ei)") and stay, as imbue
	// variants beside their Nightmare Zone / Soul Wars twins. The rest are rewards that failed their poll (Saika's,
	// Koriff's, Maoma's and Calamity armour, Wristbands of the Arena, ornament kits) or loadout copies (Rune pouch).
	['PVP', 'PvP Arena items, except its imbues',
		i => /^PVPA_/.test(i.key) && !(/\([er]?i\d*\)$/i.test(i.name) && normalNames.has(i.name.toLowerCase()))],
	// What the PvP Arena's ornament kits would have made. Not PVPA_ themselves: DRAGON_CLAWS_ORNAMENT,
	// BANDOS_CHESTPLATE_ORNAMENT, ELDERCHAOS_TOP_PRETTY, DAGONHAI_HAT_PRETTY.
	['PVP', 'PvP Arena ornament-kit items (Dragon claws, warhammer, Heavy ballista, Elder maul, Armadyl, Bandos, Elder chaos, Dagon\'hai (or))',
		i => /^(DRAGON_CLAWS|DRAGON_WARHAMMER|HEAVY_BALLISTA|ELDER_MAUL|ARMADYL_(HELMET|CHESTPLATE|SKIRT)|BANDOS_(CHESTPLATE|SKIRT|BOOTS))_ORNAMENT$|^(ELDERCHAOS_(TOP|BOTTOM|HOOD)|DAGONHAI_(HAT|ROBE_TOP|ROBE_BOTTOM))_PRETTY$/.test(i.key)],

	// ---- POH: player-owned-house items: furniture and other interactables, interface items, games-room props, kitchen
	// drinks and single-use blueprints. The heraldry room's helms, kiteshields and banners are real gear and stay.
	['POH', 'player-owned-house items (not the heraldic helms, kiteshields and banners)',
		i => /(^|_)POH(_|$)/.test(i.key) && !/^POH_(HELMET|SHIELD|BANNER)_/.test(i.key)],

	// ---- INTERFACE: guide pictures that only exist inside an interface: the skill guides (SAILING_SKILLGUIDE_*,
	// SEEDS_SKILLGUIDE), SLAYERGUIDE_*, HUNTGUIDE_*, PICKPOCKET_GUIDE_*, plus Recipe for Disaster's guide-cake quest
	// items and their display dummies (_100GUIDE_*: Cake of Guidance, Enchanted egg, the _DUM rune and seed pictures).
	['INTERFACE', 'guide pictures and guide quest items', i => /SKILLGUIDE|GUIDE_/.test(i.key)],

	// ---- GE_SET: Grand Exchange item-set packs (Dharok's armour set, Dwarf cannon set), opened into their pieces
	['GE_SET', 'Grand Exchange item sets', i => /^SET_/.test(i.key)],

	// ---- UNOBTAINABLE: copies of real items players can't own, under the real item's exact name
	// The rest of ROTTEN_* is real rotten food (Rotten tomato, Rotten stymphike tartare), so the copies are listed.
	['UNOBTAINABLE', "ROTTEN_* copies (Scythe of Vitur, Armadyl godsword, Rubber chicken, Dinh's blazing bulwark, Venator bow)",
		i => /^ROTTEN_(SCYTHE|AGS|CHICKEN|DINHS_BULWARK|VENATOR_BOW)$/.test(i.key)],
	// FAKE_* copies of Coins, the runes, the arrows and Wine of Zamorak. Prefix only: "Fake man" (EADGAR_FAKE_MAN),
	// "Fake beak" and the Easter fake eggs are items in their own right.
	['UNOBTAINABLE', 'FAKE_* copies (Coins, runes, arrows, Wine of Zamorak)', i => /^FAKE_/.test(i.key)],

	// ---- LEFTOVERS: quest, event and interface families by internal-name prefix: EYEGLO_ (quest shapes), PATTERN_
	// (interface pictures), RAG_ (Rag and Bone Man bones), EASTER17_ and XM21_ (event items), CERT_ (reused ids shown as
	// diary rewards and other pictures). Anything wearable or consumable stays: the Ram skull helm, Bonesack, Easter egg
	// helm, Festive elf hat and slippers, Snowman ring and Secret santa presents are rewards you keep.
	['LEFTOVERS', 'quest / event / interface prefixes (EYEGLO_ PATTERN_ RAG_ EASTER17_ XM21_ CERT_), not wearable or consumable',
		i => /^(EYEGLO|PATTERN|RAG|EASTER17|XM21|CERT)_/.test(i.key) && isMisc(i)],

	// ---- MISC: by inventory action, only for items that are neither wearable nor consumable (untradeable gear such
	// as the Fire cape also has Destroy instead of Drop). Quest-only means Destroy and nothing else to do with it, so
	// pouches, sacks and keys with other actions stay.
	['BOOK', 'books, notes and maps (Read, Study, Look-at, Inspect, Check steps)',
		i => isMisc(i) && actionsOf(i).some(a => READ_ACTIONS.has(a))],
	['LAMP', 'lamps and XP rewards (Rub)', i => isMisc(i) && actionsOf(i).includes('Rub')],
	['QUEST', 'quest-only items (Destroy is their only action)',
		i => isMisc(i) && actionsOf(i).includes('Destroy') && actionsOf(i).every(a => QUIET_ACTIONS.has(a))],

	// ---- CONSUMABLE: food and potions you can't bank or that never leave their activity. The Nightmare Zone potions
	// (NZONE*: Absorption, Overload, Super magic/ranging) bank, so they stay.
	['CONSUMABLE', 'untradeable consumables (Destroy, no Drop): Sailing bottles, quest and one-off food and potions',
		i => isConsumable(i) && actionsOf(i).includes('Destroy') && !actionsOf(i).includes('Drop')],
	['CONSUMABLE', 'raid and minigame supplies (RAIDS_ TOA_ GAUNTLET_ WINT_ SOUL_WARS_ PEST_ CASTLEWARS)',
		i => isConsumable(i) && /^(RAIDS|TOA|GAUNTLET|WINT|SOUL_WARS|PEST|CASTLEWARS)_|^_\dDOSE_CASTLEWARS/.test(i.key)],
	['CONSUMABLE', 'holiday event treats (HW*, EASTER*, XMAS*, XM*, OS_)',
		i => isConsumable(i) && /^(HW\d*|EASTER\d*|XMAS\d*|XM\d+|OS)_/.test(i.key)],
	// The brewery's ten drinks, every version: the plain drink, mature (m) and the kegs (KEG_ / KEG_MATURE_ ..._1-4).
	// Trouble Brewing's BREW_ items (Rum, the monkeys, naval clothes) aren't ales and stay.
	['CONSUMABLE', 'brewed ales and their mature and keg versions',
		i => /^(KEG_)?(MATURE_)?(ASGARNIAN_ALE|AXEMANS_FOLLY|CHEFS_DELIGHT|CIDER|DRAGON_BITTER|DWARVEN_STOUT|GREENMANS_ALE|WIZARDS_MIND_BOMB|MOONLIGHT_MEAD|SLAYERS_RESPITE)(_\d)?$/.test(i.key)],
];

const out = [];
const counts = new Map();
for (const item of named)
{
	const rule = RULES.find(([, , test]) => test(item));
	if (rule)
	{
		out.push(`${item.id}\t${rule[0]}\t${item.key}\t${item.name}`);
		counts.set(`${rule[0]}: ${rule[1]}`, (counts.get(`${rule[0]}: ${rule[1]}`) ?? 0) + 1);
	}
}

const header = [
	`# Generated by scripts/gen-gamemode-items.mjs from RuneLite ${version} gameval ItemID and the ${data.dump} cache`
		+ ' dump (inventory actions). Do not edit by hand.',
	'# id\tcategory\tinternal name\tdisplay name',
];
const target = new URL('../src/main/resources/com/combotags/gamemode_items.tsv', import.meta.url);
fs.writeFileSync(target, header.concat(out).join('\n') + '\n');
for (const [rule, n] of counts)
{
	console.log(String(n).padStart(4), rule);
}
console.log(String(out.length).padStart(4), 'total ->', target.pathname);
