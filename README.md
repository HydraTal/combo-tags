# Combo Tags

A RuneLite plugin that embeds **smart "combo" cells** in your bank tag layouts.

A combo cell stands in for an ordered group of items — say every melee helm from a
Coif up to a Neitiznot Faceguard. The cell automatically shows the **best item you
currently own** from that group, so one slot in your bank tab follows your gear
progression instead of leaving you a row of upgrades to tidy up by hand.

## Requirements

Combo cells live in **RuneLite's built-in bank tag layouts**. To use one:

1. Open the Bank Tags side panel (built into RuneLite) and make a tag tab.
2. Right-click the tab and choose **Enable layout**.
3. Open the **Combo Tags** side panel (this plugin), build a group, and drop it into
   the open tab.

## How it works

- Create a combo **group** in the side panel: give it a name, then search for and add
  the member items in priority order (top = best). Pick a per-member variant and a
  group color if you like.
- Embed the group as a cell in a layout-enabled bank tab. The cell renders the highest
  member you own; if you own none it shows a faded ghost of the top member.
- Members are **not** individually tagged — membership is driven live from the group
  definition — so non-winners and leftover placeholders never clutter the tab.

## Credits

The RuneLite bank-tag-layout integration is based on
[geheur/bank-tag-custom-layouts](https://github.com/geheur/bank-tag-custom-layouts)
(BSD 2-Clause). See [LICENSE](LICENSE).
