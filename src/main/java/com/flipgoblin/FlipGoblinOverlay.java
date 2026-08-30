package com.flipgoblin;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The over-the-game panel: session P/L plus the live positions board, rendered with
 * RuneLite's stock overlay components (translucent dark panel, movable with Alt-drag, position
 * remembered by the overlay system).
 *
 * ALWAYS visible — no config toggle — so a fresh session is verifiable at a glance; the
 * positions rows appear only when they exist. Reads plugin state on the client thread.
 */
public class FlipGoblinOverlay extends OverlayPanel
{
	private static final Color GOLD = new Color(0xd4, 0xaf, 0x37);
	private static final Color PROFIT = new Color(0x3f, 0xb9, 0x50);
	private static final Color LOSS = new Color(0xf8, 0x51, 0x49);
	private static final Color WORKING = new Color(0xe8, 0xa0, 0x33);
	private static final Color MUTED = Color.LIGHT_GRAY;

	private final FlipGoblinPlugin plugin;

	@Inject
	FlipGoblinOverlay(FlipGoblinPlugin plugin)
	{
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<GePositions.Position> active = plugin.overlayPositions();
		long realized = plugin.overlayRealized();

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Flip Goblin " + FlipGoblinPlugin.BUILD).color(GOLD).build());
		// Custody banner: overlays can't draw pre-LOGGED_IN, so the welcome-screen verdict
		// also shows HERE for the first minute in-world — the guaranteed-visible surface.
		if (plugin.custodyBannerActive())
		{
			LoginCustody.Verdict v = plugin.custodyOverlayVerdict();
			Color vc = v == LoginCustody.Verdict.ACQUITTED ? PROFIT
				: v == LoginCustody.Verdict.CONVICTED ? LOSS : WORKING;
			panelComponent.getChildren().add(LineComponent.builder()
				.left(plugin.custodyOverlayDetail()).leftColor(Color.WHITE)
				.right("→ " + v).rightColor(vc)
				.build());
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left("P/L (7d)")
			.right(Gp.shortForm(realized))
			.rightColor(realized > 0 ? PROFIT : realized < 0 ? LOSS : MUTED)
			.build());

		for (GePositions.Position p : active)
		{
			Color phaseColor = p.phase == GePositions.Phase.WORKING ? WORKING
				: p.phase == GePositions.Phase.COMPLETE ? PROFIT : LOSS;
			panelComponent.getChildren().add(LineComponent.builder()
				.left((p.side == TradeRecord.Side.BUY ? "Buy " : "Sell ") + plugin.overlayName(p.itemId))
				.leftColor(Color.WHITE)
				.right(p.quantitySold + "/" + p.totalQuantity + " @ " + Gp.exact(p.price))
				.rightColor(phaseColor)
				.build());
		}

		panelComponent.setPreferredSize(new Dimension(200, 0));
		return super.render(graphics);
	}

}
