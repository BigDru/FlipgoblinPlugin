package com.flipgoblin;

/**
 * Stacks the panels in the column right of the GE window: the top graph's readout, the info
 * panel, then the bottom graph's readout. On a short client the top graph is pushed against the
 * screen's top edge, so its readout can reach down into the info panel. Each panel then moves
 * down just far enough to clear the one above it.
 */
final class GeColumn
{
	/** The graphs are at least this wide, even beside a narrow GE window. */
	static final int MIN_GRAPH_WIDTH = 460;

	/**
	 * The column's x, shared by all three panels so they line up: right of the graphs, but
	 * pulled left when the client is too narrow to fit the panel on the canvas.
	 */
	static int columnX(int geX, int geWidth, int canvasWidth, int panelWidth, int gap)
	{
		return Math.min(geX + Math.max(MIN_GRAPH_WIDTH, geWidth) + gap, canvasWidth - panelWidth - 4);
	}

	/** The info panel's y: the top of the GE window, or just below the top readout. */
	static int infoY(int usualY, int topReadoutBottom, int gap)
	{
		return Math.max(usualY, topReadoutBottom + gap);
	}

	/** The bottom readout's y: beside its graph, or just below the info panel. */
	static int bottomReadoutY(int graphY, int infoBottom, int gap)
	{
		return Math.max(graphY, infoBottom + gap);
	}

	private GeColumn()
	{
	}
}
