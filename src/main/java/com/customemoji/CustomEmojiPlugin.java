package com.customemoji;

import static com.customemoji.Result.Error;
import static com.customemoji.Result.Ok;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.Control;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
		name="Custom Emoji",
		description = "Allows you to use custom emojis in chat messages",
		tags = {"emoji", "chat", "message", "custom", "icon", "emote", "text", "clan", "notification"}
)
public class CustomEmojiPlugin extends Plugin
{
	public static final float NOISE_FLOOR = -60f;

	private static final Pattern WHITESPACE_REGEXP = Pattern.compile("[\\s\\u00A0]");

	@Value
	private static class Emoji
	{
		int id;
		String text;
		File file;

	}
	@Value
	private static class Soundoji
	{
		String text;
		Clip clip;

	}

	@Inject
	private CustomEmojiConfig config;

	@Inject
	private ChatIconManager chatIconManager;

	private Set<File> files = new HashSet<>();
	private Map<String, Emoji> emojis = new HashMap<>();

	private Map<String, Soundoji> soundojis = new HashMap<>();

	private boolean loaded = false;

	@Override
	protected void startUp() throws Exception
	{
		if (!loaded) {
			loadEmojis();
			loadSoundojis();
			loaded = true;
		}
	}


	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{

		switch (chatMessage.getType())
		{
			case PUBLICCHAT:
			case MODCHAT:
			case FRIENDSCHAT:
			case CLAN_CHAT:
			case CLAN_GUEST_CHAT:
			case CLAN_GIM_CHAT:
			case PRIVATECHAT:
			case PRIVATECHATOUT:
			case MODPRIVATECHAT:
				break;
			default:
				return;
		}

		final MessageNode messageNode = chatMessage.getMessageNode();
		final String message = messageNode.getValue();
		final String updatedMessage = updateMessage(message);

		if (updatedMessage == null)
		{
			return;
		}

		messageNode.setValue(updatedMessage);
	}

	@Nullable
	String updateMessage(final String message)
	{
		final String[] messageWords = WHITESPACE_REGEXP.split(message);

		boolean editedMessage = false;
		for (int i = 0; i < messageWords.length; i++)
		{
			// Remove tags except for <lt> and <gt>
			final String trigger = Text.removeFormattingTags(messageWords[i]);
			//			final net.runelite.client.plugins.emojis.Emoji emoji = net.runelite.client.plugins.emojis.Emoji.getEmoji(trigger);
			final Emoji emoji = emojis.get(trigger);
			final Soundoji soundoji = soundojis.get(trigger);

			if (emoji != null)
			{
				messageWords[i] = messageWords[i].replace(trigger,
						"<img=" + chatIconManager.chatIconIndex(emoji.id) + ">");
				editedMessage = true;
				log.info("Replacing {} with emoji {}", trigger, emoji.text);
			}

			if (soundoji != null)
			{
				soundoji.clip.loop(0);
				FloatControl control = (FloatControl) soundoji.clip.getControl(FloatControl.Type.MASTER_GAIN);
				control.setValue(volumeToGain(config.volume()));
				soundoji.clip.start();
				messageWords[i] = messageWords[i].replace(trigger, "<u>" + trigger + "</u>");
				log.info("Playing soundoji {}", trigger);
			}

		}

		// If we haven't edited the message any, don't update it.
		if (!editedMessage)
		{
			return null;
		}

		return String.join(" ", messageWords);
	}

	private void loadEmojis()
	{
		File emojiFolder = RuneLite.RUNELITE_DIR.toPath().resolve("emojis").toFile();
		if (emojiFolder.mkdir())
		{
			log.error("Created emoji folder");
		}

		var result = loadEmojisFolder(emojiFolder);
		result.ifOk(list -> {
			list.forEach(e -> emojis.put(e.text, e));
			log.info("Loaded {} emojis", result.unwrap().size());
		});
		result.ifError(e -> {
			log.error("Failed to load emojis");
		});
	}

	private void loadSoundojis() {
		File soundojiFolder = RuneLite.RUNELITE_DIR.toPath().resolve("soundojis").toFile();
		if (soundojiFolder.mkdir())
		{
			log.error("Created soundoji folder");
		}

		var result = loadSoundojisFolder(soundojiFolder);
		result.ifOk(list -> {
			list.forEach(e -> soundojis.put(e.text, e));
			log.info("Loaded {} soundojis", result.unwrap().size());
		});
		result.ifError(e -> {
			log.error("Failed to load soundojis");
		});
	}

	private Result<List<Soundoji>, ?> loadSoundojisFolder(File soundojiFolder)
	{
		// recursively flattenFolder files in the folder
		List<File> files = flattenFolder(soundojiFolder);

		if (!soundojiFolder.isDirectory())
		{
			return Error(new IllegalArgumentException("Not a folder " + soundojiFolder));
		}

		List<Soundoji> loaded = new ArrayList<>();

		for (File file : files)
		{
			loadSoundoji(file)
					.ifOk(loaded::add);
		}

		return Ok(loaded);
	}

	private Result<Soundoji, ?> loadSoundoji(File file) {
		int extension = file.getName().lastIndexOf('.');

		if (extension < 0)
		{
			return Error(new IllegalArgumentException("Illegal file name " + file));
		}

		Result<Clip, ?> clip = loadClip(file);

		if (clip.isOk())
		{
			String text = file.getName().substring(0, extension);
			return Ok(new Soundoji(text, clip.unwrap()));
		}
		else
		{
			return Error(clip.unwrapError());
		}
	}

	private Result<Clip,?> loadClip(File file) {
		try (InputStream in = new FileInputStream(file))
		{
			Clip clip = AudioSystem.getClip();
			clip.open(AudioSystem.getAudioInputStream(new BufferedInputStream(in)));
			return Ok(clip);
		} catch (IllegalArgumentException e)
		{
			log.error("Failed to load soundoji. path: {}", file, e);
			return Error(new IllegalArgumentException(file.toString(), e));
		} catch (IOException e)
		{
			log.error("IO Exception when load soundoji. path: {}", file, e);
			return Error(new RuntimeException(file.toString(), e));
		} catch (LineUnavailableException e)
		{
			log.error("Line unavailable when load soundoji. path: {}", file, e);
			return Error(new RuntimeException(file.toString(), e));
		} catch (UnsupportedAudioFileException e)
		{
			log.error("Unsupported audio file when load soundoji. path: {}", file, e);
			return Error(new RuntimeException(file.toString(), e));
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
	}


	private Result<List<Emoji>, ?> loadEmojisFolder(File folder)
	{
		// recursively flattenFolder files in the folder
		List<File> files = flattenFolder(folder);

		if (!folder.isDirectory())
		{
			return Error(new IllegalArgumentException("Not a folder " + folder));
		}

		List<Emoji> loaded = new ArrayList<>();

		for (File file : files)
		{
			Result<Emoji, ?> result = loadEmoji(file);
			result.ifOk(loaded::add);
			result.ifError(e -> log.error("Found an emoji that failed to load {}", file, e));
		}

		return Ok(loaded);
	}

	private List<File> flattenFolder(@NonNull File folder)
	{
		return flattenFolder(folder, 0);
	}

	private List<File> flattenFolder(@NonNull File folder, int depth)
	{
		// sanity guard
		final long MAX_DEPTH = 8;

		if (depth > MAX_DEPTH)
		{
			log.warn("Max depth of {} was reached path:{}", depth, folder);
			return List.of();
		}

		// file found
		if (!folder.isDirectory())
		{
			return List.of(folder);
		}

		// no childs
		File[] childs = folder.listFiles();
		if (childs == null)
		{
			return List.of();
		}

		List<File> flattened = new ArrayList<>();
		for (File child : childs)
		{
			flattened.addAll(flattenFolder(child, depth + 1));
		}

		return flattened;
	}

	private Result<Emoji, ?> loadEmoji(File file)
	{
		int extension = file.getName().lastIndexOf('.');

		if (extension < 0)
		{
			return Error(new IllegalArgumentException("Illegal file name " + file));
		}

		Result<BufferedImage, ?> image = loadImage(file);

		if (image.isOk())
		{
			int id = chatIconManager.registerChatIcon(image.unwrap());
			String text = file.getName().substring(0, extension);
			return Ok(new Emoji(id, text, file));
		}
		else
		{
			return Error(image.unwrapError());
		}
	}

	private static Result<BufferedImage, ?> loadImage(final File file)
	{
		try (InputStream in = new FileInputStream(file))
		{
			synchronized (ImageIO.class)
			{
				BufferedImage read = ImageIO.read(in);
				if (read == null) {
					return Error(new IOException("Failed to read image"));
				}
				return Ok(read);
			}
		} catch (IllegalArgumentException e)
		{
			log.error("Failed to load emoji. path: {}", file);
			return Error(new IllegalArgumentException(file.toString(), e));
		} catch (IOException e)
		{
			log.error("IO Exception when load emoji. path: {}", file);
			return Error(new RuntimeException(file.toString(), e));
		}
	}

	public static float volumeToGain(int volume100) {
		// range[NOISE_FLOOR, 0]
		float gainDB;

		// Graph of the function
		// https://www.desmos.com/calculator/wdhsfbxgeo

		// clamp to 0-100
		float volume = Math.min(100, volume100);
		// convert linear volume 0-100 to log control
		if (volume <= 0.1) {
			gainDB = NOISE_FLOOR;
		}
		else {
			gainDB = (float) (10 * (Math.log(volume / 100)));
		}

		return gainDB;
	}

	@Provides
	CustomEmojiConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CustomEmojiConfig.class);
	}
}
