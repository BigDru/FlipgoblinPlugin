package com.flipgoblin;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Shows the custody verdict on the welcome screen: the screen's reported duration, our own
 * recorded gap, and the verdict, e.g. "last login 1h01m ago · ours 1h02m → ACQUITTED".
 * Unparseable text is shown too, as "unparsed → AMBIGUOUS", so a parser failure is visible
 * rather than silent. ALWAYS_ON_TOP matters: the game is already logged in while the
 * welcome screen is up, and the fullscreen welcome interface draws over every lower overlay
 * layer. The banner disappears with the screen.
 */
public class CustodyOverlay extends OverlayPanel
{
	private static final Color GOLD = new Color(0xd4, 0xaf, 0x37);
	private static final Color ACQUIT = new Color(0x3f, 0xb9, 0x50);
	private static final Color CONVICT = new Color(0xf8, 0x51, 0x49);
	private static final Color AMBIG = new Color(0xe8, 0xa0, 0x33);

	private final FlipGoblinPlugin plugin;

	@Inject
	CustodyOverlay(FlipGoblinPlugin plugin)
	{
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		String detail = plugin.custodyOverlayDetail();
		LoginCustody.Verdict verdict = plugin.custodyOverlayVerdict();
		if (detail == null || verdict == null || !plugin.welcomeScreenVisible())
		{
			return null;
		}
		Color color = verdict == LoginCustody.Verdict.ACQUITTED ? ACQUIT
			: verdict == LoginCustody.Verdict.CONVICTED ? CONVICT : AMBIG;
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Flip Goblin " + FlipGoblinPlugin.BUILD + " custody").color(GOLD).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(detail).leftColor(Color.WHITE)
			.right("→ " + verdict).rightColor(color)
			.build());
		panelComponent.setPreferredSize(new Dimension(280, 0));
		return super.render(graphics);
	}
}
