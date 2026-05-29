package vws.neoforge;

import net.neoforged.fml.common.Mod;
import vws.Log;
import vws.ScreenPoller;
import vws.WorldListFilter;

import java.nio.file.Path;

// NeoForge用エントリポイント
@Mod("version_world_sorter")
public class VersionWorldSorterNeoForge {

    public VersionWorldSorterNeoForge() {
        Path gameDir = gameDir();
        if (gameDir != null) {
            Log.setFile(gameDir.resolve("logs").resolve("version-world-sorter.log"));
        }
        Log.log("VersionWorldSorterNeoForge constructor");
        try {
            String mcVersion = mcVersion();
            Log.log("mcVersion=" + mcVersion);
            WorldListFilter filter = new WorldListFilter(WorldListFilter.Names.mojmap(), mcVersion);
            ScreenPoller.start(filter, false);
        } catch (Throwable t) {
            Log.log("constructor failed", t);
        }
    }

    private static Path gameDir() {
        try {
            Class<?> fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths");
            for (Object constant : fmlPaths.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals("GAMEDIR")) {
                    return (Path) fmlPaths.getMethod("get").invoke(constant);
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static String mcVersion() {
        try {
            Class<?> fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader");
            Object versionInfo = fmlLoader.getMethod("versionInfo").invoke(null);
            return (String) versionInfo.getClass().getMethod("mcVersion").invoke(versionInfo);
        } catch (Throwable ignore) {
            return null;
        }
    }
}
