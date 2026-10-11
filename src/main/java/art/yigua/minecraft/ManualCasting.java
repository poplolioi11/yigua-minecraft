package art.yigua.minecraft;

import art.yigua.core.CastResult;
import art.yigua.core.CastingEngine;
import art.yigua.core.Method;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家逐步操作的伺服器起卦；未完成六爻前不寫正式紀錄或改變效果。
 * 所有方法須在伺服器執行緒呼叫，封包由可注入的進度接收器處理。
 */
public final class ManualCasting {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static BiConsumer<ServerPlayer, Progress> progressListener = (player, progress) -> {};

    private ManualCasting() {}

    public static void setProgressListener(BiConsumer<ServerPlayer, Progress> listener) {
        progressListener = Objects.requireNonNull(listener, "進度接收器不可為空");
    }

    public static boolean start(ServerPlayer player, InteractionHand hand, boolean practice) {
        Objects.requireNonNull(player, "玩家不可為空");
        Objects.requireNonNull(hand, "手別不可為空");
        Session existing = SESSIONS.get(player.getUUID());
        if (existing != null) {
            if (valid(existing, player)) {
                publish(existing, true, "已有手動起卦，請完成或取消目前這一卦。", false);
                return false;
            }
            SESSIONS.remove(player.getUUID(), existing);
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty() || !(stack.getItem() instanceof YiguaMod.ToolItem tool)) {
            inactive(player, Method.COINS, "請先拿著三錢或蓍草筮具。");
            return false;
        }
        if (!connectedAndAlive(player) || !tool.material().compatibleWith(tool.method())) {
            inactive(player, tool.method(), "目前無法開始手動起卦。");
            return false;
        }
        if (!YiguaMod.canBeginCasting(player, practice)) {
            inactive(player, tool.method(), "目前無法起卦，請查看聊天中的冷卻或起卦提示。");
            return false;
        }
        Session session = new Session(player, hand, stack, tool.method(), tool.material(), practice);
        SESSIONS.put(player.getUUID(), session);
        String hint = session.method == Method.YARROW
            ? "五十策去一，四十九策起算。每爻三變：輸入左堆 1 至 47 根。人工選數會改變分布；傳統機率只供隨機法參考。"
            : "每枚錢公平取二或三；投三枚合成一爻，由初爻至上爻共投十八次。";
        publish(session, true, (practice ? "練習：" : "正式：") + hint, true);
        return true;
    }

    public static boolean split(ServerPlayer player, int left) {
        Session session = sessionFor(player);
        if (session == null) return false;
        if (session.method != Method.YARROW) {
            publish(session, true, "三錢法請投錢；分堆只用於蓍草法。", false);
            return false;
        }
        int line = session.steps.line();
        YarrowChange result;
        try {
            result = session.steps.split(left);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            publish(session, true, invalid.getMessage(), false);
            return false;
        }
        String hint = "第 " + line + " 爻第 " + result.change() + " 變：分二，左 "
            + result.left() + "／右 " + result.rightBeforeHang() + "；掛一後右 "
            + result.rightAfterHang() + "；揲四，左餘 " + result.leftRemainder()
            + "、右餘 " + result.rightRemainder() + "（整除記四）；歸奇共收 "
            + result.removed() + " 根，餘 " + result.remainingAfter() + " 根。";
        if (result.change() == 3) {
            hint += "成第 " + line + " 爻：" + result.lineValue() + "（"
                + lineName(result.lineValue()) + "）。"
                + (session.steps.complete() ? "六爻已成。" : "下一爻重新以四十九策起算。");
        }
        if (session.steps.complete()) return finish(session, hint);
        publish(session, true, hint, true);
        return true;
    }

    public static boolean toss(ServerPlayer player) {
        Session session = sessionFor(player);
        if (session == null) return false;
        if (session.method != Method.COINS) {
            publish(session, true, "籌束請分堆；投錢只用於三錢法。", false);
            return false;
        }
        int line = session.steps.line();
        int number = session.steps.coins().size() + 1;
        int face = RANDOM.nextBoolean() ? 3 : 2;
        CoinToss result = session.steps.toss(face);
        String hint = "第 " + line + " 爻第 " + number + " 枚錢：" + face + "。";
        if (result.lineCompleted()) {
            hint += "三錢 " + result.coins() + " 合計 " + result.sum() + "（"
                + lineName(result.sum()) + "），成第 " + line + " 爻。";
        }
        if (session.steps.complete()) return finish(session, hint + "六爻已成。");
        publish(session, true, hint, true);
        return true;
    }

    /** 已無手動 session 時不送新封包，也不取消完成後的揭曉動畫。 */
    public static boolean cancel(UUID playerId) {
        Session session = SESSIONS.remove(playerId);
        if (session == null) return false;
        publish(session, false, "手動起卦已取消；未完成的六爻不記錄、不套用效果。", false);
        return true;
    }

    public static boolean isActive(UUID playerId) {
        return SESSIONS.containsKey(playerId);
    }

    /** 登出、死亡、重生、換維度或原手道具變更時清除未完成操作。 */
    public static void tick(MinecraftServer server) {
        for (Map.Entry<UUID, Session> entry : new ArrayList<>(SESSIONS.entrySet())) {
            Session session = entry.getValue();
            if (session.level.getServer() != server || SESSIONS.get(entry.getKey()) != session) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (!valid(session, player)) cancel(entry.getKey());
        }
    }

    /** 清除暫存 session；保留初始化時註冊的進度接收器。 */
    public static void clear() {
        SESSIONS.clear();
    }

    private static Session sessionFor(ServerPlayer player) {
        Objects.requireNonNull(player, "玩家不可為空");
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            inactive(player, heldMethod(player), "目前沒有手動起卦，請先開始。");
            return null;
        }
        if (!valid(session, player)) {
            SESSIONS.remove(player.getUUID(), session);
            Progress progress = session.progress(false, "手動起卦已取消：請保持原筮具與手別，並留在同一維度。");
            send(player, progress);
            return null;
        }
        return session;
    }

    private static boolean valid(Session session, ServerPlayer player) {
        return player == session.player && connectedAndAlive(player)
            && player.level() == session.level && player.getItemInHand(session.hand) == session.stack
            && !session.stack.isEmpty();
    }

    private static boolean connectedAndAlive(ServerPlayer player) {
        return player != null && player.isAlive() && !player.isRemoved() && !player.hasDisconnected()
            && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private static boolean finish(Session session, String hint) {
        CastResult result = session.steps.result();
        // 先移除，完成回呼才可使用正式起卦 guard；取消舊面板不會碰到新動畫。
        SESSIONS.remove(session.player.getUUID(), session);
        publish(session, false, hint + "正在揭曉卦象。", true);
        return YiguaMod.completeManualCast(session.player, session.hand, session.practice, result, session.material);
    }

    private static Method heldMethod(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).getItem() instanceof YiguaMod.ToolItem tool) return tool.method();
        }
        return Method.COINS;
    }

    private static void inactive(ServerPlayer player, Method method, String hint) {
        String visualItemId = "";
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof YiguaMod.ToolItem tool && tool.method() == method) {
                visualItemId = CastingProgressPayload.visualItemId(held);
                break;
            }
        }
        send(player, new Progress(methodId(method), true, 1, 1, method == Method.YARROW ? 49 : 0,
            List.of(), List.of(), false, hint, visualItemId));
    }

    private static void publish(Session session, boolean active, String hint, boolean chat) {
        Progress progress = session.progress(active, hint);
        send(session.player, progress);
        if (chat && !session.player.hasDisconnected()) session.player.sendSystemMessage(Component.literal(hint));
    }

    private static void send(ServerPlayer player, Progress progress) {
        if (!player.hasDisconnected() && player.connection != null) progressListener.accept(player, progress);
    }

    private static String methodId(Method method) {
        return method == Method.COINS ? "coins" : "yarrow";
    }

    private static String lineName(int value) {
        return switch (value) {
            case 6 -> "老陰，動爻";
            case 7 -> "少陽";
            case 8 -> "少陰";
            case 9 -> "老陽，動爻";
            default -> throw new IllegalArgumentException("爻值必須介於六與九");
        };
    }

    public record Progress(String method, boolean manual, int line, int change, int remaining,
                           List<Integer> lines, List<Integer> coins, boolean active, String hint, String visualItemId) {
        public Progress(String method, boolean manual, int line, int change, int remaining,
                        List<Integer> lines, List<Integer> coins, boolean active, String hint) {
            this(method, manual, line, change, remaining, lines, coins, active, hint, "");
        }
        public Progress {
            Objects.requireNonNull(method, "筮法不可為空");
            lines = List.copyOf(lines);
            coins = List.copyOf(coins);
            Objects.requireNonNull(hint, "提示不可為空");
            visualItemId = visualItemId == null ? "" : visualItemId;
        }
    }

    /** 一次分二、掛一、揲四、歸奇；先完整驗證輸入，呼叫端才改進度。 */
    public static YarrowChange yarrowChange(int remaining, int change, int left) {
        boolean validCount = switch (change) {
            case 1 -> remaining == 49;
            case 2 -> remaining == 40 || remaining == 44;
            case 3 -> remaining == 32 || remaining == 36 || remaining == 40;
            default -> false;
        };
        if (!validCount) throw new IllegalArgumentException("策數與變次不符：每爻由四十九策開始，依序操作三變。");
        if (left < 1 || left > remaining - 2) {
            throw new IllegalArgumentException("左堆須為 1 至 " + (remaining - 2) + " 根，右堆須保留掛一後至少一根。");
        }
        int right = remaining - left;
        int rightAfterHang = right - 1;
        int leftRemainder = remainderFour(left);
        int rightRemainder = remainderFour(rightAfterHang);
        int removed = 1 + leftRemainder + rightRemainder;
        int after = remaining - removed;
        if (change == 1 ? removed != 5 && removed != 9 : removed != 4 && removed != 8) {
            throw new IllegalStateException("揲四後歸奇的策數不符");
        }
        if (after % 4 != 0 || (change == 3 && (after < 24 || after > 36))) {
            throw new IllegalStateException("三變後必須得到六、七、八或九");
        }
        return new YarrowChange(remaining, change, left, right, rightAfterHang,
            leftRemainder, rightRemainder, removed, after);
    }

    private static int remainderFour(int number) {
        int remainder = number % 4;
        return remainder == 0 ? 4 : remainder;
    }

    public record YarrowChange(int remainingBefore, int change, int left, int rightBeforeHang,
                              int rightAfterHang, int leftRemainder, int rightRemainder,
                              int removed, int remainingAfter) {
        public int lineValue() { return change == 3 ? remainingAfter / 4 : 0; }
    }

    public static int coinLine(List<Integer> coins) {
        Objects.requireNonNull(coins, "三錢不可為空");
        if (coins.size() != 3) throw new IllegalArgumentException("三枚錢才能成一爻");
        int sum = 0;
        for (Integer face : coins) {
            if (face == null || (face != 2 && face != 3)) throw new IllegalArgumentException("每枚錢只可取二或三");
            sum += face;
        }
        return sum;
    }

    public record CoinToss(int face, int sum, List<Integer> coins, boolean lineCompleted) {
        public CoinToss { coins = List.copyOf(coins); }
    }

    /** 與遊戲實體分離的操作進度，便於驗證人工分堆與非法輸入不改狀態。 */
    static final class Steps {
        private final Method method;
        private final List<Integer> lines = new ArrayList<>(6);
        private final List<Integer> coins = new ArrayList<>(3);
        private int remaining = 49;
        private int change = 1;

        Steps(Method method) { this.method = Objects.requireNonNull(method); }
        int line() { return Math.min(6, lines.size() + 1); }
        int remaining() { return method == Method.YARROW ? remaining : 0; }
        int change() { return method == Method.YARROW ? change : coins.size() + 1; }
        List<Integer> lines() { return List.copyOf(lines); }
        List<Integer> coins() { return List.copyOf(coins); }
        boolean complete() { return lines.size() == 6; }

        YarrowChange split(int left) {
            if (method != Method.YARROW || complete()) throw new IllegalStateException("目前不能分堆");
            YarrowChange step = yarrowChange(remaining, change, left);
            remaining = step.remainingAfter();
            if (change == 3) {
                lines.add(step.lineValue());
                if (!complete()) { remaining = 49; change = 1; }
            } else {
                change++;
            }
            return step;
        }

        CoinToss toss(int face) {
            if (method != Method.COINS || complete()) throw new IllegalStateException("目前不能投錢");
            if (face != 2 && face != 3) throw new IllegalArgumentException("每枚錢只可取二或三");
            List<Integer> thrown = new ArrayList<>(coins);
            thrown.add(face);
            boolean lineCompleted = thrown.size() == 3;
            int sum = lineCompleted ? coinLine(thrown) : thrown.stream().mapToInt(Integer::intValue).sum();
            if (lineCompleted) {
                lines.add(sum);
                coins.clear();
            } else {
                coins.add(face);
            }
            return new CoinToss(face, sum, thrown, lineCompleted);
        }

        CastResult result() {
            if (!complete()) throw new IllegalStateException("六爻尚未完成");
            return CastingEngine.fromLines(method, lines);
        }
    }

    private static final class Session {
        final ServerPlayer player;
        final ServerLevel level;
        final InteractionHand hand;
        final ItemStack stack;
        final Method method;
        final CastingMaterial material;
        final String visualItemId;
        final boolean practice;
        final Steps steps;

        Session(ServerPlayer player, InteractionHand hand, ItemStack stack, Method method,
                CastingMaterial material, boolean practice) {
            this.player = player;
            this.level = player.level();
            this.hand = hand;
            this.stack = stack;
            this.method = method;
            this.material = material;
            this.visualItemId = CastingProgressPayload.visualItemId(stack);
            this.practice = practice;
            this.steps = new Steps(method);
        }

        Progress progress(boolean active, String hint) {
            return new Progress(methodId(method), true, steps.line(),
                steps.complete() ? 3 : steps.change(), steps.remaining(), steps.lines(), steps.coins(), active, hint, visualItemId);
        }
    }
}
