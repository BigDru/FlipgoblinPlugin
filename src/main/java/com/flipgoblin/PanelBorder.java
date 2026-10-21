package com.flipgoblin;

import java.awt.Color;
import java.awt.Graphics2D;

/** The frame around the GE graphs and panels. */
final class PanelBorder
{
	/**
	 * A 2px frame, the same width on every side. RuneLite's own panel outline stops a pixel
	 * short on the right and bottom, so those edges looked different. The frame is the
	 * background a little more opaque, like RuneLite's.
	 */
	static void draw(Graphics2D g, int x, int y, int w, int h, Color bg)
	{
		if (w < 4 || h < 4)
		{
			return;
		}
		g.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), Math.min(255, bg.getAlpha() * 7 / 5)));
		// Filled strips, not drawRect: with anti-aliasing on, drawn lines straddle pixel edges.
		g.fillRect(x, y, w, 2);
		g.fillRect(x, y + h - 2, w, 2);
		g.fillRect(x, y + 2, 2, h - 4);
		g.fillRect(x + w - 2, y + 2, 2, h - 4);
	}

	private PanelBorder()
	{
	}
}
