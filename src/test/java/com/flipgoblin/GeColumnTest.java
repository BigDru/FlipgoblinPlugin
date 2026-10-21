package com.flipgoblin;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GeColumnTest
{
	private static final int GAP = 4;

	@Test
	public void infoPanelKeepsItsSpotWhenThereIsRoom()
	{
		// Top readout ends at 200, well above the info panel's usual 300.
		assertEquals(300, GeColumn.infoY(300, 200, GAP));
	}

	@Test
	public void shortClientPushesTheColumnDownInOrder()
	{
		// The top graph is clamped to the screen top, so its readout ends at 340, below the
		// info panel's usual 100. The info panel moves below it.
		int infoY = GeColumn.infoY(100, 340, GAP);
		assertEquals(344, infoY);
		// The 360px info panel now ends at 704, past the bottom graph at 580: its readout moves too.
		assertEquals(708, GeColumn.bottomReadoutY(580, infoY + 360, GAP));
	}

	@Test
	public void bottomReadoutStaysBesideItsGraphWhenClear()
	{
		assertEquals(580, GeColumn.bottomReadoutY(580, 500, GAP));
	}

	@Test
	public void columnSitsRightOfTheGeWindowWhenItFits()
	{
		// GE window at x 10, 488 wide, on a wide canvas: the column starts just right of it.
		assertEquals(502, GeColumn.columnX(10, 488, 1600, 300, GAP));
	}

	@Test
	public void narrowClientPullsTheWholeColumnLeft()
	{
		// Only 700 wide: the column is pulled left to fit, and every panel uses that x.
		assertEquals(396, GeColumn.columnX(10, 488, 700, 300, GAP));
	}
}
