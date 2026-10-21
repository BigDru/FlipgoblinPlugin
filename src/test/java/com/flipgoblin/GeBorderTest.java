package com.flipgoblin;

import static org.junit.Assert.assertEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import org.junit.Test;

public class GeBorderTest
{
	@Test
	public void borderIsTwoPixelsOnEverySide()
	{
		check(false);
	}

	/** RuneLite may draw overlays with anti-aliasing on; the frame must not shift with it. */
	@Test
	public void borderIsTwoPixelsOnEverySideWithAntialiasing()
	{
		check(true);
	}

	private static void check(boolean antialias)
	{
		int w = 12;
		int h = 9;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		if (antialias)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		}
		PanelBorder.draw(g, 0, 0, w, h, new Color(0, 0, 0, 100));
		g.dispose();
		int expect = 140;
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				boolean edge = x < 2 || y < 2 || x >= w - 2 || y >= h - 2;
				int alpha = img.getRGB(x, y) >>> 24;
				assertEquals("pixel " + x + "," + y, edge ? expect : 0, alpha);
			}
		}
	}
}
