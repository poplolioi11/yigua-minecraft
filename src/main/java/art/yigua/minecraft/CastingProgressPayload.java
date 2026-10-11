package art.yigua.minecraft;

import com.google.gson.Gson;
import art.yigua.core.Method;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 伺服器決定的起卦進度；爻值依初爻到上爻排序，客戶端不另行抽卦。 */
public record CastingProgressPayload(String method, boolean manual, int line, int change, int remaining,
        List<Integer> lines, List<Integer> coins, boolean active, String hint, String visualItemId) implements CustomPacketPayload {
    public static final Type<CastingProgressPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("yigua", "casting_progress"));
    private static final Gson GSON = new Gson();
    private static final int MAX_JSON_LENGTH = 8192;
    private static boolean initialized;
    public static final StreamCodec<RegistryFriendlyByteBuf, CastingProgressPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> buffer.writeUtf(GSON.toJson(payload), MAX_JSON_LENGTH),
        buffer -> Objects.requireNonNull(GSON.fromJson(buffer.readUtf(MAX_JSON_LENGTH), CastingProgressPayload.class),
            "起卦進度不可為 null"));

    /** 舊呼叫端相容；缺少外觀時不依筮法猜成銅錢或竹籌。 */
    public CastingProgressPayload(String method, boolean manual, int line, int change, int remaining,
            List<Integer> lines, List<Integer> coins, boolean active, String hint) {
        this(method, manual, line, change, remaining, lines, coins, active, hint, "");
    }

    public CastingProgressPayload {
        if (!"coins".equals(method) && !"yarrow".equals(method)) throw new IllegalArgumentException("未知的筮法");
        if (line < 0 || line > 6 || change < 0 || change > 3 || remaining < 0 || remaining > 49) {
            throw new IllegalArgumentException("起卦進度超出範圍");
        }
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        coins = List.copyOf(Objects.requireNonNull(coins, "coins"));
        if (lines.size() > 6 || lines.stream().anyMatch(value -> value < 6 || value > 9)
                || coins.size() > 3 || coins.stream().anyMatch(value -> value != 2 && value != 3)) {
            throw new IllegalArgumentException("爻值或銅錢值超出範圍");
        }
        hint = Objects.requireNonNull(hint, "hint");
        if (hint.length() > 4096) throw new IllegalArgumentException("起卦提示過長");
        visualItemId = visualItemId == null ? "" : visualItemId;
        if (visualItemId.length() > 256 || (!visualItemId.isEmpty() && Identifier.tryParse(visualItemId) == null)) {
            throw new IllegalArgumentException("筮具外觀識別名稱無效");
        }
    }

    /** 由原始手持 stack 擷取外觀，綁纖維／烤製等變體不會被合併。 */
    public static String visualItemId(ItemStack original) {
        Objects.requireNonNull(original, "original");
        return original.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(original.getItem()).toString();
    }

    /** 三錢投擲用同材質單錢；籌束直接保留原本竹、木、草與加工變體。 */
    public static ItemStack animationVisual(ItemStack original) {
        Objects.requireNonNull(original, "original");
        if (original.getItem() instanceof YiguaMod.ToolItem tool && tool.method() == Method.COINS) {
            return new ItemStack(switch (tool.material()) {
                case WOOD -> YiguaMod.WOOD_COIN;
                case IRON -> YiguaMod.IRON_COIN;
                case GOLD -> YiguaMod.GOLD_COIN;
                case GREEN_COPPER -> YiguaMod.GREEN_COIN;
                default -> YiguaMod.COIN;
            });
        }
        return original.copy();
    }

    /** 在共同模組初始化時呼叫；整合伺服器和客戶端可安全重複呼叫。 */
    public static synchronized void init() {
        if (initialized) return;
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        initialized = true;
    }

    public void send(ServerPlayer player) {
        send(player, this);
    }

    public static void send(ServerPlayer player, CastingProgressPayload progress) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(progress, "progress");
        // 缺少相應客戶端時不送未知封包，保留伺服器端聊天結果的相容路徑。
        if (ServerPlayNetworking.canSend(player, TYPE)) ServerPlayNetworking.send(player, progress);
    }

    @Override public Type<CastingProgressPayload> type() { return TYPE; }
}
