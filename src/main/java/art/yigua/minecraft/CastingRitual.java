package art.yigua.minecraft;

import art.yigua.core.CastResult;
import art.yigua.core.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.InteractionHand;

/**
 * 伺服器排定的短起卦呈現；不抽卦、不寫圖鑑，也不改變冷卻或效果。
 * 粒子與音效僅送給當事玩家，使用原版封包，不建立實體。
 * 所有方法由伺服器執行緒呼叫。
 */
public final class CastingRitual {
    public static final int DURATION_TICKS = 60;
    private static final int TICKS_PER_LINE = DURATION_TICKS / 6;
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private CastingRitual() {}

    /**
     * 正式卦必須先由呼叫端記錄；動畫只延後完成回呼。
     * 重入時回傳 false，呼叫端不得再次抽卦或刷新效果。
     * 完成回呼只揭曉訊息，不寫入手持物品。
     */
    public static boolean start(ServerPlayer player, CastResult result, Runnable onComplete) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(result, "result");
        ItemStack actualTool = ItemStack.EMPTY;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof YiguaMod.ToolItem tool && tool.method() == result.method()) {
                actualTool = held;
                break;
            }
        }
        return start(player, result, CastingProgressPayload.animationVisual(actualTool), actualTool, onComplete);
    }

    public static boolean start(ServerPlayer player, CastResult result, ItemStack visual, Runnable onComplete) {
        return start(player, result, visual, visual, onComplete);
    }

    /** 外觀粒子與實際原筮具分開保存，避免單枚錢顯示成三錢工具或猜錯另一手的材質。 */
    public static boolean start(ServerPlayer player, CastResult result, ItemStack visual, ItemStack actualTool, Runnable onComplete) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(visual, "visual");
        Objects.requireNonNull(actualTool, "actualTool");
        Objects.requireNonNull(onComplete, "onComplete");
        if (!player.isAlive() || player.isRemoved() || player.hasDisconnected()
                || PENDING.containsKey(player.getUUID())) return false;
        Pending pending = new Pending(player, result, visual, actualTool, onComplete);
        PENDING.put(player.getUUID(), pending);
        progress(player, pending, 0, true);
        Component name = pending.toolVisual.isEmpty() ? Component.literal(methodName(result.method())) : pending.toolVisual.getHoverName();
        player.sendSystemMessage(Component.empty().append(name)
            .append("起卦中：六爻由下而上，約三秒完成。"), true);
        return true;
    }

    public static boolean isActive(UUID playerId) {
        return PENDING.containsKey(playerId);
    }

    /** 每一個 END_SERVER_TICK 呼叫一次；離線、死亡、重生或換維度即取消。 */
    public static void tick(MinecraftServer server) {
        // 快照避免完成回呼觸發新的操作時改變正在遍歷的集合。
        for (Map.Entry<UUID, Pending> entry : new ArrayList<>(PENDING.entrySet())) {
            UUID id = entry.getKey();
            Pending pending = entry.getValue();
            if (pending.level.getServer() != server || PENDING.get(id) != pending) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != pending.player || player.level() != pending.level
                    || !player.isAlive() || player.isRemoved() || player.hasDisconnected()) {
                PENDING.remove(id, pending);
                cancelled(pending);
                continue;
            }
            render(player, pending);
            pending.elapsed++;
            if (pending.elapsed >= DURATION_TICKS) {
                PENDING.remove(id, pending);
                player.sendSystemMessage(Component.literal("六爻已成，卦象揭曉。"), true);
                progress(player, pending, 6, false);
                pending.onComplete.run();
            }
        }
    }

    /** 登出時可立即移除；已記錄的卦與冷卻由 PlayerLedger 保留。 */
    public static void cancel(UUID playerId) {
        Pending pending = PENDING.remove(playerId);
        if (pending != null) cancelled(pending);
    }

    /** 伺服器啟動／停止時清理暫存，不會觸及世界存檔。 */
    public static void clear() {
        PENDING.clear();
    }

    private static void render(ServerPlayer player, Pending pending) {
        int line = pending.elapsed / TICKS_PER_LINE;
        int phase = pending.elapsed % TICKS_PER_LINE;
        Method method = pending.result.method();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 right = new Vec3(-look.z, 0, look.x).normalize();
        if (right.lengthSqr() < 0.01) right = new Vec3(1, 0, 0);
        // 隨玩家視線移動，近距離顯示於準星下方，仍可行走。
        Vec3 center = player.getEyePosition().add(look.scale(1.0)).add(0, -0.22, 0);
        if (phase == 0) {
            progress(player, pending, line + 1, true);
            int value = pending.result.lineValues().get(line);
            player.sendSystemMessage(Component.literal(methodName(method) + "　由下而上 第 "
                + (line + 1) + "/6 爻：" + lineName(value) + " "
                + (value == 7 || value == 9 ? "━━━" : "━ ━")), true);
            sound(player, center, method == Method.COINS ? SoundEvents.ITEM_PICKUP : SoundEvents.BAMBOO_HIT,
                0.22F, 0.9F + line * 0.05F);
        }
        // 每兩 ticks 一張像素畫面；每玩家每張最多 10 顆，無全域廣播。
        if ((phase & 1) == 0) {
            double progress = phase / 8.0;
            if (method == Method.COINS) {
                double arc = Math.sin(Math.PI * progress) * 0.32;
                for (int coin = -1; coin <= 1; coin++) {
                    Vec3 point = center.add(right.scale(coin * 0.19))
                        .add(look.scale((progress - 0.5) * 0.12)).add(0, arc - 0.1, 0);
                    if (pending.itemParticle != null) particle(player, pending.itemParticle, point, 1, 0.015, 0.01, 0.015, 0.015);
                }
            } else {
                // 兩束分開再收攏；各束的四點呼應「揲之以四」的教學。
                double spread = 0.08 + Math.sin(Math.PI * progress) * 0.28;
                for (int side : new int[] {-1, 1}) {
                    for (int stalk = 0; stalk < 4; stalk++) {
                        Vec3 point = center.add(right.scale(side * spread))
                            .add(0, (stalk - 1.5) * 0.05, 0);
                        if (pending.itemParticle != null) particle(player, pending.itemParticle, point, 1, 0.005, 0.005, 0.005, 0.005);
                    }
                }
            }
            if (phase == 8) {
                particle(player, ParticleTypes.CRIT, center.add(0, -0.1, 0), 2,
                    0.1, 0.025, 0.025, 0.01);
                sound(player, center, method == Method.COINS ? SoundEvents.COPPER_HIT : SoundEvents.BAMBOO_PLACE,
                    0.28F, 1.05F + line * 0.04F);
            }
        }
    }

    private static void particle(ServerPlayer player, ParticleOptions particle, Vec3 position,
            int count, double spreadX, double spreadY, double spreadZ, double speed) {
        player.level().sendParticles(player, particle, false, false,
            position.x, position.y, position.z, count, spreadX, spreadY, spreadZ, speed);
    }

    private static void progress(ServerPlayer player, Pending pending, int completed, boolean active) {
        progress(player, pending, completed, active, active ? "六爻由下而上" : "六爻已成，結果在聊天框");
    }

    private static void progress(ServerPlayer player, Pending pending, int completed, boolean active, String hint) {
        CastResult result = pending.result;
        new CastingProgressPayload(result.method() == Method.COINS ? "coins" : "yarrow", false,
            Math.min(6, completed + 1), 0, 0, result.lineValues().subList(0, completed), List.of(), active,
            hint, pending.visualItemId).send(player);
    }

    private static void cancelled(Pending pending) {
        if (pending.player.connection == null || pending.player.hasDisconnected()) return;
        int completed = Math.min(6, (pending.elapsed + TICKS_PER_LINE - 1) / TICKS_PER_LINE);
        progress(pending.player, pending, completed, false, "起卦動畫已取消。");
    }

    private static void sound(ServerPlayer player, Vec3 position, SoundEvent sound, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
            SoundSource.PLAYERS, position.x, position.y, position.z, volume, pitch,
            player.level().getGameTime()));
    }

    private static String methodName(Method method) {
        return method == Method.COINS ? "三錢" : "蓍草法";
    }

    private static String lineName(int value) {
        return switch (value) {
            case 6 -> "老陰（變）";
            case 7 -> "少陽";
            case 8 -> "少陰";
            case 9 -> "老陽（變）";
            default -> throw new IllegalArgumentException("爻值必須是 6、7、8、9");
        };
    }

    private static final class Pending {
        final ServerPlayer player;
        final ServerLevel level;
        final CastResult result;
        final Runnable onComplete;
        final ItemParticleOption itemParticle;
        final ItemStack toolVisual;
        final String visualItemId;
        int elapsed;

        Pending(ServerPlayer player, CastResult result, ItemStack visual, ItemStack actualTool, Runnable onComplete) {
            this.player = player;
            this.level = player.level();
            this.result = result;
            this.onComplete = onComplete;
            this.toolVisual = actualTool.copy();
            this.visualItemId = CastingProgressPayload.visualItemId(actualTool);
            this.itemParticle = visual.isEmpty() ? null
                : new ItemParticleOption(ParticleTypes.ITEM, ItemStackTemplate.fromNonEmptyStack(visual));
        }
    }
}
