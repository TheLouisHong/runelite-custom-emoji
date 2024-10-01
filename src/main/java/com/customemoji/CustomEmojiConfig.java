package com.customemoji;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("custom-emote")
public interface CustomEmojiConfig extends Config
{
	@ConfigItem(
			keyName = "instructions",
			name = "Instructions",
			description = "Link to instruction"
	)
	default String __instructions() {
		return "https://github.com/TheLouisHong/runelite-custom-emoji/blob/master/README.md";
	}

	@ConfigItem(
		keyName = "volume",
		name = "Soundoji Volume",
		description = "Volume of soundojis. [0-100]"
	)
	@Range(min = 0, max = 100)
	default int volume()
	{
		return 70;
	}
}
