package io.github.pseudodistant.provider.services;

import io.github.pseudodistant.provider.patch.MiniEntrypointPatch;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.GameProviderHelper;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.ContactInformationImpl;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.log.LogLevel;
import net.fabricmc.loader.impl.util.version.StringVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MinicraftGameProvider implements GameProvider {

	private static final String[] ENTRYPOINTS = new String[]{"com.mojang.ld22.Game", "com.mojang.ld22.GameControl", "minicraft.core.Game", "minicraft.Game"};
	private static final Set<String> SENSITIVE_ARGS = new HashSet<>(Arrays.asList(
			// all lowercase without --
			"savedir",
			"debug",
			"localclient"));
	
	private Arguments arguments;
	private String entrypoint;
	private Path launchDir;
	private Path libDir;
	private Path gameJar;
	private boolean development = false;
	private static int gameType = 0;
	private final List<Path> miscGameLibraries = new ArrayList<>();
	private static Version gameVersion;
	
	private static final GameTransformer TRANSFORMER = new GameTransformer(
			new MiniEntrypointPatch());
	
	@Override
	public String getGameId() {
		return switch (gameType) {
			default -> "minicraft";
			case 1 -> "minicraft-delux";
			case 2 -> "minicraftplus";
		}; // isPlus ? "minicraftplus" : "minicraft";
	}

	@Override
	public String getGameName() {
		return switch (gameType) {
			default -> "Minicraft";
			case 1 -> "Minicraft Delux";
			case 2 -> "MinicraftPlus";
		};//isPlus ? "MinicraftPlus" : "Minicraft";
	}

	@Override
	public String getRawGameVersion() {
		return getGameVersion().getFriendlyString();
	}

	@Override
	public String getNormalizedGameVersion() {
		return getRawGameVersion();
	}

	@Override
	public Collection<BuiltinMod> getBuiltinMods() {
		
		HashMap<String, String> minicraftContactInformation = new HashMap<>();
		minicraftContactInformation.put("homepage", "https://en.wikipedia.org/wiki/Minicraft");

		HashMap<String, String> minicraftDeluxContactInformation = new HashMap<>();
		minicraftDeluxContactInformation.put("homepage", "https://playminicraft.com/");
		minicraftDeluxContactInformation.put("wiki", "Lost to time and space...");

		HashMap<String, String> minicraftPlusContactInformation = new HashMap<>();
		minicraftPlusContactInformation.put("homepage", "https://playminicraft.com/");
		minicraftPlusContactInformation.put("wiki", "https://github.com/chrisj42/minicraft-plus-revived/wiki");
		minicraftPlusContactInformation.put("discord", "https://discord.com/invite/nvyd3Mrj");
		minicraftPlusContactInformation.put("issues", "https://github.com/MinicraftPlus/minicraft-plus-revived/issues");

		BuiltinModMetadata.Builder minicraftMetaData =
				new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion())
				.setName(getGameName())
				.addAuthor("Notch", minicraftContactInformation)
				.setContact(new ContactInformationImpl(minicraftContactInformation))
				.setDescription("A 2D top-down action game designed and programmed by Markus Persson, the creator of Minecraft, for a Ludum Dare, a 48-hour game programming competition.");

		BuiltinModMetadata.Builder minicraftDeluxMetaData =
				new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion())
						.setName(getGameName())
						.addAuthor("Samuel Werder", minicraftDeluxContactInformation)
						.setContact(new ContactInformationImpl(minicraftDeluxContactInformation))
						.setDescription("A modded version of Minicraft made by Samuel Werder, adding a saves system, a respawn mechanic, an in-game map, working terrain height, stairs, new monsters, a day/night cycle, and more!");

		BuiltinModMetadata.Builder minicraftPlusMetaData =
				new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion())
				.setName(getGameName())
				.addAuthor("Minicraft+ Contributors", minicraftPlusContactInformation)
				.setContact(new ContactInformationImpl(minicraftPlusContactInformation))
				.setDescription("Minicraft+ is a modded version of Minicraft that adds many more features to the original version. The original Minicraft game was made by Markus 'Notch' Persson in the Ludum Dare 22 contest.");

		return switch (gameType) {
			default -> Collections.singletonList(new BuiltinMod(Collections.singletonList(gameJar), minicraftMetaData.build()));
			case 1 -> Collections.singletonList(new BuiltinMod(Collections.singletonList(gameJar), minicraftDeluxMetaData.build()));
			case 2 -> Collections.singletonList(new BuiltinMod(Collections.singletonList(gameJar), minicraftPlusMetaData.build()));
		};
		// isPlus ? Collections.singletonList(new BuiltinMod(Collections.singletonList(gameJar), minicraftPlusMetaData.build())) : Collections.singletonList(new BuiltinMod(Collections.singletonList(gameJar), minicraftMetaData.build()));
	}

	@Override
	public String getEntrypoint() {
		return entrypoint;
	}

	@Override
	public Path getLaunchDirectory() {
		if (arguments == null) {
			return Paths.get(".");
		}
		
		return getLaunchDirectory(arguments);
	}

	@Override
	public boolean isObfuscated() {
		return false;
	}

	@Override
	public boolean requiresUrlClassLoader() {
		return false;
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	@Override
	public boolean locateGame(FabricLauncher launcher, String[] args) {
		this.arguments = new Arguments();
		arguments.parse(args);
		
		Map<Path, ZipFile> zipFiles = new HashMap<>();
		
		if(Objects.equals(System.getProperty(SystemProperties.DEVELOPMENT), "true")) {
			development = true;
		}
		
		try {
			String gameJarProperty = System.getProperty(SystemProperties.GAME_JAR_PATH);
			GameProviderHelper.FindResult result = null;
			if(gameJarProperty == null) {
				gameJarProperty = "./jars/minicraft.jar";
			}
			if(gameJarProperty != null) {
				Path path = Paths.get(gameJarProperty);
				if (!Files.exists(path)) {
					throw new RuntimeException("Game jar configured through " + SystemProperties.GAME_JAR_PATH + " system property doesn't exist");
				}

				result = GameProviderHelper.findFirst(Collections.singletonList(path), zipFiles, true, ENTRYPOINTS);
			}
			
			if(result == null) {
				return false;
			}
			
			entrypoint = result.name;
			gameJar = result.path;

		} catch (Exception e) {
			e.printStackTrace();
		}
		
		processArgumentMap(arguments);

		try {
			//String Md5 = GetMD5FromJar.getMD5Checksum(gameJar.toString());
			//gameVersion = GetVersionFromHash.getVersionFromHash(Md5);
		} catch (Exception e) {
			e.printStackTrace();
		}

		String version = readVersion();

		if (version != null) {
			try {
				setGameVersion(Version.parse(version));
			} catch (VersionParsingException e) {
				e.printStackTrace();
			}
		}

		return true;
	}

	@Nullable
	private String readVersion() {
		VersionCaptureVisitor captureVisitor = new VersionCaptureVisitor();

		try (ZipFile game = new ZipFile(gameJar.toFile())) {
			setGameType(2);
			ZipEntry entry = game.getEntry("minicraft/core/Game.class");
			if (entry == null) entry = game.getEntry("minicraft/Game.class");
			if (entry == null) {
				entry = game.getEntry("com/mojang/ld22/GameControl.class");
				setGameType(1);
			}
			if (entry == null) {
				entry = game.getEntry("com/mojang/ld22/Game.class");
				setGameType(0);
			}
			if (entry == null) return null;

			InputStream stream = game.getInputStream(entry);
			ClassReader reader = new ClassReader(stream);
			reader.accept(captureVisitor, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		return captureVisitor.version;
	}

	@Override
	public void initialize(FabricLauncher launcher) {
		TRANSFORMER.locateEntrypoints(launcher, Collections.singletonList(gameJar));
	}

	@Override
	public GameTransformer getEntrypointTransformer() {
		return TRANSFORMER;
	}

	@Override
	public void unlockClassPath(FabricLauncher launcher) {
		launcher.addToClassPath(gameJar);
		
		for(Path lib : miscGameLibraries) {
			launcher.addToClassPath(lib);
		}
	}

	@Override
	public void launch(ClassLoader loader) {
		String targetClass = entrypoint;
		
		try {
			Class<?> c = loader.loadClass(targetClass);
			Method m = c.getMethod("main", String[].class);
			m.invoke(null, (Object) arguments.toArray());
		}
		catch(InvocationTargetException e) {
			throw new FormattedException("Minicraft has crashed!", e.getCause());
		}
		catch(ReflectiveOperationException e) {
			throw new FormattedException("Failed to start Minicraft", e);
		}
	}

	@Override
	public Arguments getArguments() {
		return arguments;
	}

	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		if (arguments == null) return new String[0];

		String[] ret = arguments.toArray();
		if (!sanitize) return ret;

		int writeIdx = 0;

		for (int i = 0; i < ret.length; i++) {
			String arg = ret[i];

			if (i + 1 < ret.length
					&& arg.startsWith("--")
					&& SENSITIVE_ARGS.contains(arg.substring(2).toLowerCase(Locale.ENGLISH))) {
				if (arg.substring(2).equals("debug")) {
					Log.shouldLog(LogLevel.DEBUG, LogCategory.GENERAL);
				}
				i++; // skip value
			} else {
				ret[writeIdx++] = arg;
			}
		}

		if (writeIdx < ret.length) ret = Arrays.copyOf(ret, writeIdx);

		return ret;
	}
	
	private void processArgumentMap(Arguments arguments) {
		if (!arguments.containsKey("gameDir")) {
			arguments.put("gameDir", getLaunchDirectory(arguments).toAbsolutePath().normalize().toString());
		}
		
		launchDir = Path.of(arguments.get("gameDir"));
		System.out.println("Launch directory is " + launchDir);
		libDir = launchDir.resolve(Path.of("./lib"));
	}
	
	private static Path getLaunchDirectory(Arguments arguments) {
		return Paths.get(arguments.getOrDefault("gameDir", "."));
	}

	public static void setGameVersion(Version version) {
		if (version != null) {
			gameVersion = version;
		}
	}

	public static void setGameType(int type) {
		gameType = type;
	}

	private Version getGameVersion() {
		if (gameVersion != null) {
			return gameVersion;
		} else {
			return new StringVersion("0.0.0");
		}
	}

	private static class VersionCaptureVisitor extends ClassVisitor {
		String version;

		public VersionCaptureVisitor() {
			super(Opcodes.ASM9);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			if (version == null && name.equals("VERSION") && value instanceof String) {
				version = (String) value;
			}

			return super.visitField(access, name, descriptor, signature, value);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			if (version != null || !name.equals("<clinit>")) {
				return super.visitMethod(access, name, descriptor, signature, exceptions);
			}

			return new MethodVisitor(Opcodes.ASM9) {
				int state;
				String lastLdcString;

				@Override
				public void visitTypeInsn(int opcode, String type) {
					if (version == null
							&& state == 0
							&& opcode == Opcodes.NEW
							&& type.endsWith("/Version")) {
						state = 1;
					}
				}

				@Override
				public void visitLdcInsn(Object value) {
					if (value instanceof String) {
						if (state == 1) {
							version = (String) value;
							state = 2;
						} else {
							lastLdcString = (String) value;
						}
					}
				}

				@Override
				public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
					if (version == null
							&& opcode == Opcodes.PUTSTATIC
							//&& (owner.equals("com/mojang/ld22/GameControl") || owner.contains("minicraft.") )
							&& name.equals("VERSION")
							&& lastLdcString != null) {
						version = lastLdcString;
					}
				}
			};
		}
	}
}
