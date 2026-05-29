package vws;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

// SelectWorldScreen の WorldSelectionList を ModeState の現モードで絞り込み、モード切替ボタンを設置する
public final class WorldListFilter {

    private final Names names;
    private final String currentMcVersion;
    private Class<?> selectWorldScreenClass;

    private volatile Object lastWrap;
    private volatile List<?> capturedOriginalList;
    private volatile Object toggleButton;
    private volatile boolean initialModeChosen;

    public WorldListFilter(Names names, String currentMcVersion) {
        this.names = names;
        this.currentMcVersion = currentMcVersion;
    }

    public boolean isSelectWorldScreen(Object screen) {
        if (screen == null) {
            return false;
        }
        try {
            if (selectWorldScreenClass == null) {
                selectWorldScreenClass = Class.forName(names.selectWorldScreenClass);
            }
            return selectWorldScreenClass.isInstance(screen);
        } catch (Throwable t) {
            Log.log("isSelectWorldScreen failed", t);
            return false;
        }
    }

    // pendingLevels を thenApply で包み、vanilla の表示処理にフィルタ適用後の list だけ渡す
    @SuppressWarnings("unchecked")
    public void installLoadIntercept(Object screen) {
        if (!isSelectWorldScreen(screen)) {
            return;
        }
        try {
            Object worldList = getWorldList(screen);
            if (worldList == null) {
                return;
            }
            Field pendingField = findField(worldList.getClass(), names.pendingLevelsField);
            pendingField.setAccessible(true);
            Object current = pendingField.get(worldList);
            if (current == null || current == lastWrap) {
                return;
            }
            CompletableFuture<List<Object>> wrapped = ((CompletableFuture<List<?>>) current).thenApply(list -> {
                capturedOriginalList = list;
                chooseInitialMode(list);
                return filterList(list, ModeState.current());
            });
            pendingField.set(worldList, wrapped);
            lastWrap = wrapped;
            Log.log("installLoadIntercept ok");
        } catch (Throwable t) {
            Log.log("installLoadIntercept failed", t);
        }
    }

    // モード切替時、pendingLevels を新モードでフィルタした完了済 future に差し替える
    public void onModeChanged(Object screen) {
        if (!isSelectWorldScreen(screen)) {
            return;
        }
        List<?> source = capturedOriginalList;
        if (source == null) {
            return;
        }
        try {
            Object worldList = getWorldList(screen);
            if (worldList == null) {
                return;
            }
            Field pendingField = findField(worldList.getClass(), names.pendingLevelsField);
            pendingField.setAccessible(true);
            List<Object> filtered = filterList(source, ModeState.current());
            CompletableFuture<List<Object>> newCf = CompletableFuture.completedFuture(filtered);
            pendingField.set(worldList, newCf);
            lastWrap = newCf;
        } catch (Throwable t) {
            Log.log("onModeChanged failed", t);
        }
    }

    public void installToggleButton(Object screen) {
        if (!isSelectWorldScreen(screen)) {
            return;
        }
        try {
            Class<?> componentClass = Class.forName(names.componentClass);
            Method literal = componentClass.getMethod(names.componentLiteralMethod, String.class);

            Class<?> buttonClass = Class.forName(names.buttonClass);
            Class<?> onPressInterface = Class.forName(names.buttonOnPressClass);

            Object initialLabel = literal.invoke(null, buttonLabel(ModeState.current()));

            Object[] buttonHolder = new Object[1];

            InvocationHandler handler = (proxy, method, args) -> {
                ModeState.set(nextNonEmptyMode());
                if (buttonHolder[0] != null) {
                    setButtonMessage(buttonHolder[0], ModeState.current());
                }
                onModeChanged(screen);
                return null;
            };
            Object onPress = Proxy.newProxyInstance(onPressInterface.getClassLoader(),
                    new Class<?>[]{onPressInterface}, handler);

            Method builderStatic = buttonClass.getMethod(names.buttonBuilderMethod, componentClass, onPressInterface);
            Object builder = builderStatic.invoke(null, initialLabel, onPress);

            int bx = 8;
            int by = 8;
            int bw = 80;
            int bh = 20;
            int[] pos = buttonPosition(screen);
            if (pos != null) {
                bx = pos[0];
                by = pos[1];
            }
            Method bounds = builder.getClass().getMethod(names.builderBoundsMethod, int.class, int.class, int.class, int.class);
            bounds.invoke(builder, bx, by, bw, bh);

            Method build = builder.getClass().getMethod(names.builderBuildMethod);
            Object button = build.invoke(builder);
            buttonHolder[0] = button;
            this.toggleButton = button;

            Class<?> guiEventListenerClass = Class.forName(names.guiEventListenerClass);
            Method addWidget = findMethod(screen.getClass(), names.addRenderableWidgetMethod, guiEventListenerClass);
            addWidget.setAccessible(true);
            addWidget.invoke(screen, button);
            Log.log("installToggleButton ok");
        } catch (Throwable t) {
            Log.log("installToggleButton failed", t);
        }
    }

    // 検索ボックス右隣の座標 {x, y} を返す。取得できなければ null。
    private int[] buttonPosition(Object screen) {
        try {
            Field searchBoxField = findField(screen.getClass(), names.searchBoxField);
            searchBoxField.setAccessible(true);
            Object searchBox = searchBoxField.get(screen);
            if (searchBox == null) {
                return null;
            }
            Method getX = findMethod(searchBox.getClass(), names.getXMethod);
            Method getY = findMethod(searchBox.getClass(), names.getYMethod);
            Method getWidth = findMethod(searchBox.getClass(), names.getWidthMethod);
            int x = (int) getX.invoke(searchBox) + (int) getWidth.invoke(searchBox) + 4;
            int y = (int) getY.invoke(searchBox);
            return new int[]{x, y};
        } catch (Throwable t) {
            return null;
        }
    }

    // リサイズ・GUIスケール変更で検索ボックスが動いた時、ボタンを隣へ追従させる。毎ポーリングで呼ぶ。
    public void layoutToggleButton(Object screen) {
        Object button = toggleButton;
        if (button == null || !isSelectWorldScreen(screen)) {
            return;
        }
        int[] pos = buttonPosition(screen);
        if (pos == null) {
            return;
        }
        try {
            Method setX = findMethod(button.getClass(), names.setXMethod, int.class);
            Method setY = findMethod(button.getClass(), names.setYMethod, int.class);
            setX.setAccessible(true);
            setY.setAccessible(true);
            setX.invoke(button, pos[0]);
            setY.invoke(button, pos[1]);
        } catch (Throwable ignore) {
        }
    }

    private Object getWorldList(Object screen) throws ReflectiveOperationException {
        Field listField = findField(screen.getClass(), names.listField);
        listField.setAccessible(true);
        return listField.get(screen);
    }

    private List<Object> filterList(List<?> sourceList, Mode mode) {
        if (sourceList == null) {
            return null;
        }
        if (mode == Mode.ALL) {
            return new ArrayList<>(sourceList);
        }
        List<Object> kept = new ArrayList<>(sourceList.size());
        for (Object summary : sourceList) {
            try {
                if (passes(summary, mode)) {
                    kept.add(summary);
                }
            } catch (Throwable ignore) {
            }
        }
        return kept;
    }

    private boolean passes(Object summary, Mode mode) throws ReflectiveOperationException {
        if (mode == Mode.CURRENT_OR_LOWER) {
            Method m = findMethodNoArg(summary.getClass(), names.isDowngradeMethod);
            return !(boolean) m.invoke(summary);
        }
        if (currentMcVersion == null) {
            return false;
        }
        Method levelVersion = findMethodNoArg(summary.getClass(), names.levelVersionMethod);
        Object lv = levelVersion.invoke(summary);
        Method versionName = findMethodNoArg(lv.getClass(), names.minecraftVersionNameMethod);
        String summaryVersion = (String) versionName.invoke(lv);
        return currentMcVersion.equals(summaryVersion);
    }

    // 初回読込時、EXACT→CURRENT_OR_LOWER→ALL の順で最初に件数が0でないモードを開始モードにする。セッション中一度だけ。
    private void chooseInitialMode(List<?> list) {
        if (initialModeChosen) {
            return;
        }
        initialModeChosen = true;
        if (list == null || list.isEmpty()) {
            return;
        }
        for (Mode mode : new Mode[]{Mode.EXACT, Mode.CURRENT_OR_LOWER, Mode.ALL}) {
            if (countKept(list, mode) > 0) {
                if (mode != ModeState.current()) {
                    ModeState.set(mode);
                    Object button = toggleButton;
                    if (button != null) {
                        setButtonMessage(button, mode);
                    }
                }
                Log.log("initial mode=" + mode);
                return;
            }
        }
    }

    private int countKept(List<?> list, Mode mode) {
        List<Object> kept = filterList(list, mode);
        return kept == null ? 0 : kept.size();
    }

    // 現モードから巡回方向に進め、最初に件数が0でないモードを返す。全て0なら現モードのまま。
    private Mode nextNonEmptyMode() {
        List<?> source = capturedOriginalList;
        Mode current = ModeState.current();
        Mode candidate = current.next();
        for (int i = 0; i < Mode.values().length - 1; i++) {
            if (source == null || countKept(source, candidate) > 0) {
                return candidate;
            }
            candidate = candidate.next();
        }
        return current;
    }

    private void setButtonMessage(Object button, Mode mode) {
        try {
            Class<?> componentClass = Class.forName(names.componentClass);
            Method literal = componentClass.getMethod(names.componentLiteralMethod, String.class);
            Object label = literal.invoke(null, buttonLabel(mode));
            Method setMessage = findMethod(button.getClass(), names.setMessageMethod, componentClass);
            setMessage.setAccessible(true);
            setMessage.invoke(button, label);
        } catch (Throwable ignore) {
        }
    }

    private static String buttonLabel(Mode mode) {
        switch (mode) {
            case EXACT:
                return "Exact";
            case CURRENT_OR_LOWER:
                return "Compatible";
            case ALL:
            default:
                return "All";
        }
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignore) {
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Method findMethodNoArg(Class<?> cls, String name) throws NoSuchMethodException {
        return findMethod(cls, name);
    }

    public static final class Names {
        final String selectWorldScreenClass;
        final String listField;
        final String pendingLevelsField;
        final String isDowngradeMethod;
        final String levelVersionMethod;
        final String minecraftVersionNameMethod;
        final String buttonClass;
        final String buttonOnPressClass;
        final String buttonBuilderMethod;
        final String builderBoundsMethod;
        final String builderBuildMethod;
        final String setMessageMethod;
        final String componentClass;
        final String componentLiteralMethod;
        final String guiEventListenerClass;
        final String addRenderableWidgetMethod;
        final String searchBoxField;
        final String getXMethod;
        final String getYMethod;
        final String getWidthMethod;
        final String setXMethod;
        final String setYMethod;

        public Names(
                String selectWorldScreenClass,
                String listField,
                String pendingLevelsField,
                String isDowngradeMethod,
                String levelVersionMethod,
                String minecraftVersionNameMethod,
                String buttonClass,
                String buttonOnPressClass,
                String buttonBuilderMethod,
                String builderBoundsMethod,
                String builderBuildMethod,
                String setMessageMethod,
                String componentClass,
                String componentLiteralMethod,
                String guiEventListenerClass,
                String addRenderableWidgetMethod,
                String searchBoxField,
                String getXMethod,
                String getYMethod,
                String getWidthMethod,
                String setXMethod,
                String setYMethod
        ) {
            this.selectWorldScreenClass = selectWorldScreenClass;
            this.listField = listField;
            this.pendingLevelsField = pendingLevelsField;
            this.isDowngradeMethod = isDowngradeMethod;
            this.levelVersionMethod = levelVersionMethod;
            this.minecraftVersionNameMethod = minecraftVersionNameMethod;
            this.buttonClass = buttonClass;
            this.buttonOnPressClass = buttonOnPressClass;
            this.buttonBuilderMethod = buttonBuilderMethod;
            this.builderBoundsMethod = builderBoundsMethod;
            this.builderBuildMethod = builderBuildMethod;
            this.setMessageMethod = setMessageMethod;
            this.componentClass = componentClass;
            this.componentLiteralMethod = componentLiteralMethod;
            this.guiEventListenerClass = guiEventListenerClass;
            this.addRenderableWidgetMethod = addRenderableWidgetMethod;
            this.searchBoxField = searchBoxField;
            this.getXMethod = getXMethod;
            this.getYMethod = getYMethod;
            this.getWidthMethod = getWidthMethod;
            this.setXMethod = setXMethod;
            this.setYMethod = setYMethod;
        }

        public static Names mojmap() {
            return new Names(
                    "net.minecraft.client.gui.screens.worldselection.SelectWorldScreen",
                    "list",
                    "pendingLevels",
                    "isDowngrade",
                    "levelVersion",
                    "minecraftVersionName",
                    "net.minecraft.client.gui.components.Button",
                    "net.minecraft.client.gui.components.Button$OnPress",
                    "builder",
                    "bounds",
                    "build",
                    "setMessage",
                    "net.minecraft.network.chat.Component",
                    "literal",
                    "net.minecraft.client.gui.components.events.GuiEventListener",
                    "addRenderableWidget",
                    "searchBox",
                    "getX",
                    "getY",
                    "getWidth",
                    "setX",
                    "setY"
            );
        }

        public static Names intermediary() {
            return new Names(
                    "net.minecraft.class_526",
                    "field_3218",
                    "field_39739",
                    "method_54550",
                    "method_29586",
                    "method_29025",
                    "net.minecraft.class_4185",
                    "net.minecraft.class_4185$class_4241",
                    "method_46430",
                    "method_46434",
                    "method_46431",
                    "method_25355",
                    "net.minecraft.class_2561",
                    "method_43470",
                    "net.minecraft.class_364",
                    "method_37063",
                    "field_3220",
                    "method_46426",
                    "method_46427",
                    "method_25368",
                    "method_46421",
                    "method_46419"
            );
        }
    }
}
