package com.flipgoblin;

/**
 * Builds the hover-tooltip text for items in the GE window, using RuneLite's tooltip markup
 * ({@code <col>} tags and {@code </br>} line breaks). No client classes are imported, so
 * the class stays pure and unit-testable.
 */
final class GeTooltip
{
	private static final String GOLD = "d4af37";
	private static final String PROFIT = "3fb950";
	private static final String LOSS = "f85149";
	private static final String MUTED = "9e9e9e";

	private GeTooltip()
	{
	}

	/** Multi-line tooltip for one item's market data. */
	static String build(PriceClient.ItemPrices p)
	{
		return build(p, null, null);
	}

	/**
	 * Same as {@link #build(PriceClient.ItemPrices)}, plus the session's 4-hour buy-limit
	 * usage when a window is active. {@code resetsAt} is formatted by the caller so this
	 * class stays free of clocks and timezones.
	 */
	static String build(PriceClient.ItemPrices p, GeLimits.Usage usage, String resetsAt)
	{
		StringBuilder sb = new StringBuilder();
		if (p.name != null)
		{
			sb.append(col(GOLD, p.name)).append("</br>");
		}
		sb.append("Buy ").append(Gp.shortOrDash(p.ask)).append(" · Sell ").append(Gp.shortOrDash(p.bid)).append("</br>");
		String marginColor = p.margin == null ? MUTED : p.margin > 0 ? PROFIT : LOSS;
		sb.append("Margin ").append(col(marginColor, Gp.shortOrDash(p.margin)
			+ (p.roi == null ? "" : String.format(" (%.1f%%)", p.roi * 100)))).append(" after tax")
			.append(p.tax == null ? "" : " (" + Gp.shortOrDash(p.tax) + ")").append("</br>");
		sb.append(col(MUTED, "24h vol " + Gp.shortOrDash(p.askVolume) + "/" + Gp.shortOrDash(p.bidVolume)
			+ " · Limit " + (p.buyLimit == null ? "—" : String.valueOf(p.buyLimit))));
		if (usage != null)
		{
			boolean capped = p.buyLimit != null && usage.used >= p.buyLimit;
			// The bought count is a lower bound, except when the whole window was watched
			// live. Then the count and "left" are exact.
			sb.append("</br>").append(col(capped ? LOSS : MUTED,
				"Bought " + (usage.exact ? "" : "≥") + usage.used + (p.buyLimit == null ? ""
					: "/" + p.buyLimit + " · left " + (usage.exact ? "" : "≤")
						+ Math.max(0, p.buyLimit - usage.used))
					+ " · limit resets " + resetsAt));
		}
		return sb.toString();
	}

	/**
	 * Same as {@link #build(PriceClient.ItemPrices, long[], String)}, plus the session's
	 * most recent fill per side. Either fill may be null. The times are formatted by the
	 * caller, for the same reason as {@code resetsAt}.
	 */
	static String build(PriceClient.ItemPrices p, GeLimits.Usage usage, String resetsAt,
		TradeRecord lastBuy, String buyAt, TradeRecord lastSell, String sellAt)
	{
		StringBuilder sb = new StringBuilder(build(p, usage, resetsAt));
		if (lastBuy != null || lastSell != null)
		{
			sb.append("</br>").append(col(MUTED, "Last"
				+ (lastBuy == null ? "" : " buy " + Gp.shortOrDash(lastBuy.price) + " ×" + lastBuy.quantity + " " + buyAt)
				+ (lastBuy != null && lastSell != null ? " ·" : "")
				+ (lastSell == null ? "" : " sell " + Gp.shortOrDash(lastSell.price) + " ×" + lastSell.quantity + " " + sellAt)));
		}
		return sb.toString();
	}

	/**
	 * The flipper's unrealized margin line for a hovered SELL offer: the offer price net of tax
	 * minus the last buy-in, per item and across the unsold remainder. Sign-colored.
	 */
	static String flipLine(long each, long total)
	{
		return col(each > 0 ? PROFIT : each < 0 ? LOSS : MUTED,
			"Flip " + Gp.exact(each) + "/ea · " + Gp.shortOrDash(total) + " unrealized");
	}

	private static String col(String hex, String text)
	{
		return "<col=" + hex + ">" + text + "</col>";
	}

}
