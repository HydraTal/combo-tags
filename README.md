# Combo Tags

Customizable priority-based placeholders for slots in your bank tag layouts.

A combo cell stands in for an ordered group of items — say every melee helm from Rune to Torva. Set the order, including cosmetics, and the combo tag placeholder will return your highest priority item it can find.

![Basic Melee Helm Progression](images/MeleeHelmProgression.png)
Note the Arrows on all but Faceguard, they expand to cosmetic and other variants;
 Using in my Tabs resolves in order, Torva Full Helm | Serpentine Helm | Nezi: 
![](images/Torva-Helm.png) ![](images/Serp-Helm.png) ![](images/Nezi-Helm.png)

---

## Overlays are optional,

So when a screenshot below shows a highlighted cell, remember **None** is an option. Find them in the Combo Tags plugin settings.

| None | Box outline | Dot | Underline | Highlight |
|:---:|:---:|:---:|:---:|:---:|
| ![None](images/Overlay-None.png) | ![Box outline](images/Overlay-Box.png) | ![Dot](images/Overlay-Dot.png) | ![Underline](images/Overlay-Underline.png) | ![Highlight](images/Overlay-Highlight.png) |

---

## Setup

Combo cells live inside **RuneLite's built-in bank tag layouts**:

1. Open the built-in **Bank Tags** side panel and create a tag tab.
2. Right-click the tab and choose **Enable layout**.
3. Open the **Combo Tags** side panel (this plugin) from the toolbar, build a combo, and add it to the open tab OR replace existing gear sets with Combo Tag placeholders

## Using Combo Tags

### Open the panel
![alt text](src/main/resources/com/combotags/combo_icon.png)
Click the **Combo Tags** button in the RuneLite sidebar. The panel lists your combos,
organized into collapsible **groups**.

![The Combo Tags side panel with a group expanded](images/GroupContent.png)

### Build a combo

Type a name and click **Create**, then **click the combo to open its builder**. Search for items in the **Add items** box and add them in priority order — the top of the list is the most preferred. Drag members to reorder; the cell always shows the highest one you own. Star a member to use it as the combo's icon, and pick a color for its bank highlight.

![A combo's builder: members in priority order, item search, color, and favorite](images/ComboBuilder-WithFavorite.png)

**Variants are automatic.** Add a single item and the plugin folds in its cosmetic, ornament, and charged variants — add a Scythe of Vitur and the cell shows your Holy or Sanguine one; a Dragon pickaxe shows whichever recolor you own. Expand a member to choose which variant it displays, or to set a preference order.

![Expanding a member to choose and reorder its variants](images/VariationReorder.png)

### Organize into groups

Use **New group** to create a collapsible category, then file combos under it. A group shares one color across its combos (the 🎨 button) and has a one-click **Replace group in tab** (the ⇄ button) that drops every combo in the group into the open tab at once.

### Right-click menus

Most actions live on right-click in the side panel:

- **Right-click a group header** → Move up / down, Hide or Show its bank highlight, Copy the whole group to your clipboard, or Delete it.
![Group header right-click menu](images/RightClickAndToolTip.png)

- **Right-click a combo** → Replace in tab, Move up / down, Copy to clipboard, or Delete.
![Combo right-click menu](images/ComboRightClick.png)

### Add it to a bank tab

With a layout-enabled tag tab open, use **Add to Tab** from a combo's builder — or **Replace in Tab** to consolidate loose member items already sitting in the tab into one cell. Use a group's ⇄ button to drop a whole group in at once.

**See it on real tabs.** Expand an example to compare the **before** (a tab full of loose gear) with the **after** (each group collapsed into one combo cell that tracks your best item, colored per group). The first is open by default:

<details open>
<summary><b>Gear progression tab</b></summary>

| Before — loose gear | After — combo cells |
|:---:|:---:|
| <img src="images/Before.png" width="420"> | <img src="images/After.png" width="420"> |

</details>

<details>
<summary><b>Duke Sucellus tab</b></summary>

| Before — loose gear | After — combo cells |
|:---:|:---:|
| <img src="images/duke-before.png" width="420"> | <img src="images/duke-after.png" width="420"> |

</details>

<details>
<summary><b>Inferno tab</b></summary>

| Before — loose gear | After — combo cells |
|:---:|:---:|
| <img src="images/inferno-before.png" width="420"> | <img src="images/inferno-after.png" width="420"> |

</details>

- Turn the highlight **off** for one combo (the builder's **Highlight in bank** checkbox) or a whole group (right-click → **Hide bank highlight**) — the item still shows, just unmarked.
- Right-click a cell in the bank for **Edit** (open its builder), **Set Placeholder** (replace the cell with the item it currently shows), or **Remove Layout**.

### Share & import combos

Right-click a combo or group → **Copy to clipboard** for a share string, and use **Import from clipboard** to paste them back. You can paste **several at once** — even a whole file of them.

**Ready-made combo sets:** a starter Range / Melee / Mage + cosmetics pack lives in [Preset Combos](https://github.com/HydraTal/combo-tags/blob/master/presets/combos.md) — copy the block, then hit **Import from clipboard** to load them all in one go.
