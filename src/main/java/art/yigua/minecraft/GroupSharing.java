package art.yigua.minecraft;

import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.*;

/** 雙向同意、同群組、同維度附近的普通增益；不複製狀態、成就或冷卻。 */
public final class GroupSharing {
    public static final int RANGE = 32;
    public record Bonus(String profile, Holder<Attribute> attribute, double amount,
                        AttributeModifier.Operation operation) {
        public Bonus {
            Objects.requireNonNull(profile); Objects.requireNonNull(attribute); Objects.requireNonNull(operation);
            if (!Double.isFinite(amount) || amount == 0) throw new IllegalArgumentException("共享增益必須有有限強度");
        }
        public net.minecraft.resources.Identifier id() { return YiguaMod.id("shared_" + profile); }
    }
    private static final Map<UUID, Map<String, Bonus>> APPLIED = new HashMap<>();
    private static final Map<UUID, Map<String, Bonus>> OFFERS = new HashMap<>();
    private GroupSharing() {}

    /** 同類只取最強；MULTIPLIED_TOTAL 以乘法補差，維持其他模組的乘數。 */
    static double supplement(double own, double offered, AttributeModifier.Operation operation) {
        if (!Double.isFinite(own) || !Double.isFinite(offered)) throw new IllegalArgumentException("強度無效");
        boolean inverse = offered < 0;
        if (inverse ? own <= offered : own >= offered) return 0;
        return operation == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
            ? (1 + offered) / (1 + own) - 1 : offered - own;
    }
    private static Bonus stronger(Bonus a, Bonus b) {
        return Math.abs(a.amount()) >= Math.abs(b.amount()) ? a : b;
    }
    private static boolean eligible(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.hasDisconnected();
    }
    public static void tick(MinecraftServer server, PlayerLedger ledger, PartyLedger parties) {
        if (!YiguaMod.sharingEnabled() || ledger == null || parties == null) { clear(server); return; }
        Map<UUID, ServerPlayer> online = new HashMap<>();
        Map<UUID, Map<String, Bonus>> sources = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.put(player.getUUID(), player);
            PlayerLedger.State state = ledger.state(player.getUUID());
            if (eligible(player) && state.shareSend && state.latest != null && state.remainingTicks > 0
                    && state.controlledEffect == null && !state.taijiSelected
                    && !RareFortune.resolve(state.latest, state.latestMaterial).rare()
                    && parties.group(player.getUUID()) != null) {
                sources.put(player.getUUID(), YiguaMod.shareableBonuses(player, state));
            }
        }
        for (ServerPlayer recipient : online.values()) {
            Map<String, Bonus> desired = new HashMap<>();
            PlayerLedger.State state = ledger.state(recipient.getUUID());
            if (eligible(recipient) && state.shareReceive) {
                for (UUID sourceId : parties.members(recipient.getUUID())) {
                    if (sourceId.equals(recipient.getUUID())) continue;
                    ServerPlayer source = online.get(sourceId);
                    if (source == null || source.level() != recipient.level()
                            || source.distanceToSqr(recipient) > RANGE * RANGE) continue;
                    sources.getOrDefault(sourceId, Map.of()).forEach((profile, bonus) -> desired.merge(profile, bonus, GroupSharing::stronger));
                }
            }
            Map<String, Bonus> applied = APPLIED.computeIfAbsent(recipient.getUUID(), ignored -> new HashMap<>());
            OFFERS.put(recipient.getUUID(), Map.copyOf(desired));
            for (String profile : List.copyOf(applied.keySet())) {
                if (!desired.containsKey(profile) || YiguaMod.effectProtected(profile)) {
                    remove(recipient, applied.remove(profile));
                }
            }
            for (Bonus offer : desired.values()) {
                if (YiguaMod.effectProtected(offer.profile())) continue;
                AttributeInstance attribute = recipient.getAttribute(offer.attribute());
                if (attribute == null) continue;
                double own = YiguaMod.ownBonusAmount(recipient, offer.profile(), offer.attribute(), offer.operation());
                double amount = supplement(own, offer.amount(), offer.operation());
                if (amount == 0) {
                    Bonus previous = applied.remove(offer.profile());
                    if (previous != null) remove(recipient, previous);
                } else {
                    Bonus bonus = new Bonus(offer.profile(), offer.attribute(), amount, offer.operation());
                    AttributeModifier existing = attribute.getModifier(bonus.id());
                    if (existing == null || existing.amount() != amount || existing.operation() != offer.operation()) {
                        attribute.addOrUpdateTransientModifier(new AttributeModifier(bonus.id(), amount, offer.operation()));
                    }
                    applied.put(bonus.profile(), bonus);
                }
            }
        }
        APPLIED.keySet().removeIf(id -> !online.containsKey(id));
        OFFERS.keySet().removeIf(id -> !online.containsKey(id));
    }

    /** 先補將降低的自身生命上限，再讓原生狀態移除舊倍率；不直接補血。 */
    static void beforeOwnEffectsChanged(ServerPlayer player, double nextVitality) {
        if (!YiguaMod.sharingEnabled() || YiguaMod.effectProtected("vitality")) return;
        Bonus offer = OFFERS.getOrDefault(player.getUUID(), Map.of()).get("vitality");
        if (offer == null) return;
        AttributeInstance attribute = player.getAttribute(offer.attribute());
        if (attribute == null) return;
        double own = YiguaMod.ownBonusAmount(player, offer.profile(), offer.attribute(), offer.operation());
        if (nextVitality >= own) return;
        double amount = supplement(nextVitality, offer.amount(), offer.operation());
        AttributeModifier previous = attribute.getModifier(offer.id());
        if (amount > 0 && (previous == null || previous.amount() < amount)) {
            Bonus adjusted = new Bonus(offer.profile(), offer.attribute(), amount, offer.operation());
            attribute.addOrUpdateTransientModifier(new AttributeModifier(adjusted.id(), amount, adjusted.operation()));
            APPLIED.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>()).put(adjusted.profile(), adjusted);
        }
    }
    private static void remove(ServerPlayer player, Bonus bonus) {
        AttributeInstance attribute = player.getAttribute(bonus.attribute());
        if (attribute != null) attribute.removeModifier(bonus.id());
    }
    public static void clear(ServerPlayer player) {
        Map<String, Bonus> bonuses = APPLIED.remove(player.getUUID());
        OFFERS.remove(player.getUUID());
        if (bonuses != null) bonuses.values().forEach(bonus -> remove(player, bonus));
    }
    public static void clear(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(GroupSharing::clear);
        APPLIED.clear();
        OFFERS.clear();
    }
}
