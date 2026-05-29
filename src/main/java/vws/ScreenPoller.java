package vws;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

// Minecraft.getInstance().screen をリフレクションで監視し SelectWorldScreen を検出する。ローダー非依存。
public final class ScreenPoller {

    private ScreenPoller() {
    }

    private static final class McNames {
        final String mcClass;
        final String getInstance;
        final String screenField;

        McNames(String mcClass, String getInstance, String screenField) {
            this.mcClass = mcClass;
            this.getInstance = getInstance;
            this.screenField = screenField;
        }
    }

    private static final McNames INTERMEDIARY = new McNames(
            "net.minecraft.class_310",
            "method_1551",
            "field_1755"
    );

    private static final McNames MOJMAP = new McNames(
            "net.minecraft.client.Minecraft",
            "getInstance",
            "screen"
    );

    public static void start(WorldListFilter filter, boolean intermediary) {
        McNames names = intermediary ? INTERMEDIARY : MOJMAP;
        Thread t = new Thread(() -> pollLoop(filter, names), "VWS-Screen-Poll");
        t.setDaemon(true);
        t.start();
        Log.log("polling thread started");
    }

    private static void pollLoop(WorldListFilter filter, McNames names) {
        Method getInstance;
        Field screenField;
        Method execute;
        try {
            Class<?> mcClass = Class.forName(names.mcClass);
            getInstance = mcClass.getMethod(names.getInstance);
            screenField = mcClass.getDeclaredField(names.screenField);
            screenField.setAccessible(true);
            execute = mcClass.getMethod("execute", Runnable.class);
            Log.log("poll setup ok");
        } catch (Throwable t) {
            Log.log("poll setup failed", t);
            return;
        }

        Object lastSeenScreen = null;
        Object lastInstalledScreen = null;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Object mc = getInstance.invoke(null);
                if (mc == null) {
                    Thread.sleep(100);
                    continue;
                }
                Object screen = screenField.get(mc);
                if (screen != lastSeenScreen) {
                    Log.log("screen=" + (screen == null ? "null" : screen.getClass().getName()));
                    lastSeenScreen = screen;
                }
                if (filter.isSelectWorldScreen(screen)) {
                    final Object screenRef = screen;
                    final boolean needsInstall = (screen != lastInstalledScreen);
                    lastInstalledScreen = screen;
                    execute.invoke(mc, (Runnable) () -> {
                        if (needsInstall) {
                            Log.log("installToggleButton");
                            filter.installToggleButton(screenRef);
                        }
                        filter.layoutToggleButton(screenRef);
                        filter.installLoadIntercept(screenRef);
                    });
                } else if (screen == null) {
                    lastInstalledScreen = null;
                }
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                Log.log("poll iter failed", t);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
