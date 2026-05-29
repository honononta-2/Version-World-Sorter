package vws;

// 現モードをプロセス全体で保持し、巡回前進を提供する
public final class ModeState {
    private static volatile Mode current = Mode.EXACT;

    private ModeState() {
    }

    // 現モードを返す
    public static Mode current() {
        return current;
    }

    // 現モードを指定値に設定する
    public static void set(Mode mode) {
        current = mode;
    }
}
