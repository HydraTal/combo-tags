package com.combotags;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the combo "smart cell" highlights over a built-in bank tag layout. The cell index IS the core layout
 * position, so {@link ComboTagsPlugin#getComboCellGroups} gives each box's grid slot. RuneLite renders the
 * winner widget and the unowned ghost (its own faded layout placeholder); this overlay only draws the box.
 */
public class ComboOverlay extends Overlay
{
	private final ComboTagsPlugin plugin;
	private final Client client;
	private final ComboTagsConfig config;

	ComboOverlay(ComboTagsPlugin plugin, Client client, ComboTagsConfig config)
	{
		this.plugin = plugin;
		this.client = client;
		this.config = config;
		drawAfterLayer(ComponentID.BANK_ITEM_CONTAINER);
		setLayer(OverlayLayer.MANUAL);
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (config.comboHighlightStyle() == ComboTagsConfig.ComboHighlight.NONE)
		{
			return null;
		}
		String tag = plugin.getCurrentComboTag();
		if (tag == null)
		{
			return null;
		}
		Map<Integer, String> comboCellGroups = plugin.getComboCellGroups(tag);
		if (comboCellGroups.isEmpty())
		{
			return null;
		}
		Widget bankItemContainer = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
		if (bankItemContainer == null)
		{
			return null;
		}

		int scrollY = bankItemContainer.getScrollY();
		Point canvasLocation = bankItemContainer.getCanvasLocation();

		int yOffset = 0;
		Widget widget = bankItemContainer;
		while (widget.getParent() != null)
		{
			yOffset += widget.getRelativeY();
			widget = widget.getParent();
		}

		Rectangle bankItemArea = new Rectangle(canvasLocation.getX() + 51 - 6, yOffset,
			bankItemContainer.getWidth() - 51 + 6, bankItemContainer.getHeight());
		graphics.clip(bankItemArea);

		// When RuneLite is dragging the cell's item widget, offset the highlight by the same amount so it
		// follows the item under the cursor instead of staying behind at the cell's grid slot.
		Widget draggedWidget = client.getDraggedWidget();
		int draggedIndex = (draggedWidget != null && draggedWidget.getId() == ComponentID.BANK_ITEM_CONTAINER)
			? draggedWidget.getIndex() : -1;

		ComboTagsConfig.ComboHighlight style = config.comboHighlightStyle();
		for (Map.Entry<Integer, String> cell : comboCellGroups.entrySet())
		{
			if (plugin.isComboHighlightHidden(cell.getValue()))
			{
				continue; // this combo's highlight is turned off — render the item but draw no box/dot/etc.
			}
			int index = cell.getKey();
			int dragDeltaX = 0;
			int dragDeltaY = 0;
			if (index == draggedIndex)
			{
				dragDeltaX = client.getMouseCanvasPosition().getX() - plugin.comboDragPressX;
				dragDeltaY = client.getMouseCanvasPosition().getY() - plugin.comboDragPressY;
				dragDeltaY += scrollY - plugin.comboDragPressScroll;
			}
			int x = ComboTagsPlugin.getXForIndex(index) + canvasLocation.getX() + dragDeltaX;
			int y = ComboTagsPlugin.getYForIndex(index) + yOffset - scrollY + dragDeltaY;
			if (y + ComboTagsPlugin.BANK_ITEM_HEIGHT > bankItemArea.getMinY() && y < bankItemArea.getMaxY())
			{
				drawComboHighlight(graphics, style, plugin.getComboColor(cell.getValue()), x, y, bankItemArea);
			}
		}
		return null;
	}

	/** Draws one combo cell's highlight in the configured style, at the cell's top-left item position (x, y). */
	private void drawComboHighlight(Graphics2D graphics, ComboTagsConfig.ComboHighlight style, Color color,
		int x, int y, Rectangle clip)
	{
		int left = x - 2;
		int top = Math.max(y - 2, (int) clip.getMinY()); // clamp so a partially-scrolled cell isn't off-screen
		int bottom = y + ComboTagsPlugin.BANK_ITEM_HEIGHT;
		int width = ComboTagsPlugin.BANK_ITEM_WIDTH;
		switch (style)
		{
			case OUTLINE:
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
				graphics.setColor(color);
				// 2px stroke so the box reads as boldly as the underline (and matches owned vs ghost cells,
				// which both draw this same outline on top of RuneLite's render).
				java.awt.Stroke oldStroke = graphics.getStroke();
				graphics.setStroke(new java.awt.BasicStroke(2f));
				graphics.drawRect(left, top, width, bottom - top);
				graphics.setStroke(oldStroke);
				break;
			case DOT:
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
				graphics.setColor(color);
				int dot = 7;
				graphics.fillOval(left, top, dot, dot);
				break;
			case UNDERLINE:
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
				graphics.setColor(color);
				int lineWidth = (int) (width * 0.8);
				graphics.fillRect(left + (width - lineWidth) / 2, bottom - 2, lineWidth, 2);
				break;
			case BACKGROUND:
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
				graphics.setColor(color);
				graphics.fillRect(left, top, width, bottom - top);
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
				break;
			default:
				break;
		}
	}
}
