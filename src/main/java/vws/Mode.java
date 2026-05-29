package vws;

// 3つのフィルタモード値を巡回切替で提供する
public enum Mode {
    EXACT,
    CURRENT_OR_LOWER,
    ALL;

    // 次のモード値を返す（ALLの次はEXACT）
    public Mode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
