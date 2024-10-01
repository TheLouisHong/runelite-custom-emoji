package com.customemoji;

import static com.customemoji.Result.Error;
import static com.customemoji.Result.Ok;
import static com.customemoji.Result.PartialOk;
import com.google.common.io.Resources;
import com.google.inject.Provides;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
		name="Custom Emoji",
		description="Allows you to use custom emojis in chat messages",
		tags={"emoji", "chat", "message", "custom", "icon", "emote", "text", "clan", "notification"}
)
public class CustomEmojiPlugin extends Plugin
{
	public static final String EMOJI_ERROR_COMMAND = "!emojierror";
	public static final String EMOJI_FOLDER_COMMAND = "!emojifolder";
	public static final String SOUNDOJI_FOLDER_COMMAND = "!soundojifolder";

	public static final File SOUNDOJIS_FOLDER = RuneLite.RUNELITE_DIR.toPath().resolve("soundojis").toFile();
	public static final File EMOJIS_FOLDER = RuneLite.RUNELITE_DIR.toPath().resolve("emojis").toFile();

	public static final URL EXAMPLE_EMOJI = Resources.getResource(CustomEmojiPlugin.class, "checkmark.png");
	public static final URL EXAMPLE_SOUNDOJI = Resources.getResource(CustomEmojiPlugin.class, "customemoji.wav");

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

	@Inject
	private ChatCommandManager chatCommandManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	private final Map<String, Emoji> emojis = new HashMap<>();
	private final Map<String, Soundoji> soundojis = new HashMap<>();

	private List<String> errors = new ArrayList<>();

	private boolean loaded = false;


	private void setup()
	{
		if (EMOJIS_FOLDER.mkdir())
		{
			// copy example emoji
			File exampleEmoji = new File(EMOJIS_FOLDER, "com/customemoji/checkmark.png");
			try (InputStream in = EXAMPLE_EMOJI.openStream())
			{
				Files.copy(in, exampleEmoji.toPath());
			} catch (IOException e)
			{
				log.error("Failed to copy example emoji", e);
			}
		}

		if (SOUNDOJIS_FOLDER.mkdir())
		{
			// copy example soundoji
			File exampleSoundoji = new File(SOUNDOJIS_FOLDER, "com/customemoji/customemoji.wav");
			try (InputStream in = EXAMPLE_SOUNDOJI.openStream())
			{
				Files.copy(in, exampleSoundoji.toPath());
			} catch (IOException e)
			{
				log.error("Failed to copy example soundoji", e);
			}
		}
	}

	@Override
	protected void startUp() throws Exception
	{
		setup();

		if (!loaded)
		{
			loadEmojis();
			loadSoundojis();
			loaded = true;
		}


		chatCommandManager.registerCommandAsync(EMOJI_FOLDER_COMMAND,
				(msg, text) ->
				{
					try
					{
						if (Desktop.isDesktopSupported())
						{
							Desktop.getDesktop().open(EMOJIS_FOLDER);
						}
					} catch (IOException ignored) {}
				});

		chatCommandManager.registerCommandAsync(SOUNDOJI_FOLDER_COMMAND,
				(msg, text) ->
				{
					try
					{
						if (Desktop.isDesktopSupported())
						{
							Desktop.getDesktop().open(SOUNDOJIS_FOLDER);
						}
					} catch (IOException ignored) {}
				});

		chatCommandManager.registerCommand(EMOJI_ERROR_COMMAND,
				(msg, text) ->
				{
					for (String error : errors)
					{
						client.addChatMessage(ChatMessageType.CONSOLE, "", error, null);
					}
				});

		if (!errors.isEmpty())
		{
			clientThread.invokeLater(() ->
			{
				String message =
						"<col=FF0000>Custom Emoji: There were " + errors.size() +
								" errors loading emojis and soundojis.<br><col=FF0000>Use <col=00FFFF>!emojierror <col=FF0000>to see them.";
				client.addChatMessage(ChatMessageType.CONSOLE, "", message, null);
			});
		}
		else
		{
			clientThread.invoke(() ->
			{
				client.addChatMessage(ChatMessageType.CONSOLE, "", "<col=00FF00>Custom Emoji: Loaded " + emojis.size() + soundojis.size() + " emojis and soundojis.", null);
			});
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		chatCommandManager.unregisterCommand(EMOJI_FOLDER_COMMAND);
		chatCommandManager.unregisterCommand(SOUNDOJI_FOLDER_COMMAND);
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
		final String updatedMessage = updateMessage(message, true);

		if (updatedMessage == null)
		{
			return;
		}

		messageNode.setValue(updatedMessage);
	}

	@Subscribe
	public void onOverheadTextChanged(final OverheadTextChanged event)
	{
		if (!(event.getActor() instanceof Player))
		{
			return;
		}

		final String message = event.getOverheadText();
		final String updatedMessage = updateMessage(message, false);

		if (updatedMessage == null)
		{
			return;
		}

		event.getActor().setOverheadText(updatedMessage);
	}

	@Nullable
	String updateMessage(final String message, boolean sound)
	{
		final String[] messageWords = WHITESPACE_REGEXP.split(message);

		boolean editedMessage = false;
		for (int i = 0; i < messageWords.length; i++)
		{
			// Remove tags except for <lt> and <gt>
			final String trigger = Text.removeFormattingTags(messageWords[i]);
			//			final net.runelite.client.plugins.emojis.Emoji emoji = net.runelite.client.plugins.emojis.Emoji.getEmoji(trigger);
			final Emoji emoji = emojis.get(trigger.toLowerCase());
			final Soundoji soundoji = soundojis.get(trigger.toLowerCase());

			if (emoji != null)
			{
				messageWords[i] = messageWords[i].replace(trigger,
						"<img=" + chatIconManager.chatIconIndex(emoji.id) + ">");
				editedMessage = true;
				log.debug("Replacing {} with emoji {}", trigger, emoji.text);
			}

			if (soundoji != null)
			{
				if (sound) {
					soundoji.clip.setFramePosition(0);
					soundoji.clip.loop(0);
					FloatControl control = (FloatControl) soundoji.clip.getControl(FloatControl.Type.MASTER_GAIN);
					control.setValue(volumeToGain(config.volume()));
					soundoji.clip.start();
				}
				messageWords[i] = messageWords[i].replace(trigger, "*" + trigger + "*");
				editedMessage = true;
				log.debug("Playing soundoji {}", trigger);
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
		File emojiFolder = EMOJIS_FOLDER;
		if (emojiFolder.mkdir())
		{
			log.error("Created emoji folder");
		}

		var result = loadEmojisFolder(emojiFolder);
		result.ifOk(list ->
		{
			list.forEach(e -> emojis.put(e.text, e));
			log.info("Loaded {} emojis", result.unwrap().size());
		});
		result.ifError(e ->
		{
			e.forEach(t ->
			{
				log.error("Failed to load emoji", t);
				errors.add(String.format("Failed to load emoji %s", t.getMessage()));
			});
		});
	}

	private void loadSoundojis()
	{
		File soundojiFolder = SOUNDOJIS_FOLDER;
		if (soundojiFolder.mkdir())
		{
			log.error("Created soundoji folder");
		}

		var result = loadSoundojisFolder(soundojiFolder);
		result.ifOk(list ->
		{
			list.forEach(e -> soundojis.put(e.text, e));
			log.info("Loaded {} soundojis", result.unwrap().size());
		});
		result.ifError(e ->
		{
			e.forEach(t ->
			{
				log.error("Failed to load soundoji", t);
				errors.add(String.format("Failed to load audio %s", t.getMessage()));
			});
		});
	}

	private Result<List<Soundoji>, List<Throwable>> loadSoundojisFolder(File soundojiFolder)
	{
		// recursively flattenFolder files in the folder
		List<File> files = flattenFolder(soundojiFolder);

		if (!soundojiFolder.isDirectory())
		{
			return Error(List.of(new IllegalArgumentException("Not a folder " + soundojiFolder)));
		}

		List<Soundoji> loaded = new ArrayList<>();
		List<Throwable> errors = new ArrayList<>();

		for (File file : files)
		{
			Result<Soundoji, Throwable> result = loadSoundoji(file);
			result.ifOk(loaded::add);
			result.ifError(errors::add);
		}

		if (errors.isEmpty())
		{
			return Ok(loaded);
		}
		else
		{
			return PartialOk(loaded, errors);
		}
	}

	private Result<List<Emoji>, List<Throwable>> loadEmojisFolder(File folder)
	{
		// recursively flattenFolder files in the folder
		List<File> files = flattenFolder(folder);

		if (!folder.isDirectory())
		{
			return Error(List.of(new IllegalArgumentException("Not a folder " + folder)));
		}

		List<Emoji> loaded = new ArrayList<>();
		List<Throwable> errors = new ArrayList<>();

		for (File file : files)
		{
			Result<Emoji, Throwable> result = loadEmoji(file);
			result.ifOk(loaded::add);
			result.ifError(errors::add);
		}

		if (errors.isEmpty())
		{
			return Ok(loaded);
		}
		else
		{
			return PartialOk(loaded, errors);
		}

	}

	private Result<Soundoji, Throwable> loadSoundoji(File file)
	{
		int extension = file.getName().lastIndexOf('.');

		if (extension < 0)
		{
			return Error(new IllegalArgumentException("Illegal file name " + file));
		}

		Result<Clip, Throwable> clip = loadClip(file);

		if (clip.isOk())
		{
			String text = file.getName().substring(0, extension).toLowerCase();
			return Ok(new Soundoji(text, clip.unwrap()));
		}
		else
		{
			return Error(clip.unwrapError());
		}
	}

	private Result<Clip, Throwable> loadClip(File file)
	{
		try (InputStream in = new FileInputStream(file))
		{
			Clip clip = AudioSystem.getClip();
			clip.open(AudioSystem.getAudioInputStream(new BufferedInputStream(in)));
			return Ok(clip);
		} catch (IllegalArgumentException | IOException | LineUnavailableException | UnsupportedAudioFileException e)
		{
			return Error(
					new RuntimeException("<col=FF0000>" + file.getName() + "</col> failed because <col=FF0000>" + e.getMessage(),
							e));
		}
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

	private Result<Emoji, Throwable> loadEmoji(File file)
	{
		int extension = file.getName().lastIndexOf('.');

		if (extension < 0)
		{
			return Error(new IllegalArgumentException("Illegal file name <col=00FFFF>" + file));
		}

		Result<BufferedImage, Throwable> image = loadImage(file);

		if (image.isOk())
		{
			int id = chatIconManager.registerChatIcon(image.unwrap());
			String text = file.getName().substring(0, extension).toLowerCase();
			return Ok(new Emoji(id, text, file));
		}
		else
		{
			Throwable throwable = image.unwrapError();
			return Error(new RuntimeException(
					"<col=FF0000>" + file.getName() + "</col> failed because <col=FF0000>" + throwable.getMessage(),
					throwable));
		}
	}

	private static Result<BufferedImage, Throwable> loadImage(final File file)
	{
		try (InputStream in = new FileInputStream(file))
		{
			synchronized (ImageIO.class)
			{
				BufferedImage read = ImageIO.read(in);
				if (read == null)
				{
					return Error(new IOException("image format not supported. (PNG,JPG only)"));
				}
				return Ok(read);
			}
		} catch (IllegalArgumentException | IOException e)
		{
			return Error(e);
		}
	}

	public static float volumeToGain(int volume100)
	{
		// range[NOISE_FLOOR, 0]
		float gainDB;

		// Graph of the function
		// https://www.desmos.com/calculator/wdhsfbxgeo

		// clamp to 0-100
		float volume = Math.min(100, volume100);
		// convert linear volume 0-100 to log control
		if (volume <= 0.1)
		{
			gainDB = NOISE_FLOOR;
		}
		else
		{
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
