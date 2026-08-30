package com.flipgoblin;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class FlipGoblinPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FlipGoblinPlugin.class);
		RuneLite.main(args);
	}
}
