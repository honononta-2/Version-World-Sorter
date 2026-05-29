package vws;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

// Fabric用エントリポイント
public class VersionWorldSorter implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Log.setFile(FabricLoader.getInstance().getGameDir().resolve("logs").resolve("version-world-sorter.log"));
        Log.log("onInitializeClient");

        MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
        String ns = resolver.getCurrentRuntimeNamespace();
        Log.log("runtime namespace=" + ns);
        Log.log("available namespaces=" + resolver.getNamespaces());

        boolean intermediary = "intermediary".equals(ns);
        WorldListFilter.Names filterNames = intermediary
                ? WorldListFilter.Names.intermediary()
                : WorldListFilter.Names.mojmap();
        Log.log("using names: " + (intermediary ? "intermediary" : "mojmap"));

        String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
        Log.log("mcVersion=" + mcVersion);

        WorldListFilter filter = new WorldListFilter(filterNames, mcVersion);
        ScreenPoller.start(filter, intermediary);
    }
}
