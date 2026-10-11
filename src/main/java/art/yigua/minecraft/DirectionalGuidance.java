package art.yigua.minecraft;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** 方位卦的行動條件，以相鄰伺服器刻實際位移判定；不把朝向當作行走。 */
public final class DirectionalGuidance {
    private record Sample(ServerPlayer player, Level level, Vec3 position, int tick) {}
    private static final Map<UUID, Sample> LAST = new HashMap<>();
    private static final net.minecraft.resources.Identifier MODIFIER = YiguaMod.id("directional_fortune");
    private DirectionalGuidance() {}

    public static void tick(ServerPlayer player, double strength) {
        if (!Double.isFinite(strength) || strength < 0) throw new IllegalArgumentException("方位強度必須是有限非負值");
        UUID id = player.getUUID();
        var attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null) return;
        if (strength == 0 || !player.isAlive() || player.isSpectator() || YiguaMod.effectProtected("direction")) {
            cancel(id);
            attribute.removeModifier(MODIFIER);
            return;
        }
        Sample before = LAST.put(id, new Sample(player, player.level(), player.position(), player.tickCount));
        double amount = 0;
        if (before != null && before.level == player.level() && player.tickCount == before.tick + 1) {
            Vec3 delta = player.position().subtract(before.position);
            // 傳送或異常瞬移不視為朝某方向行動；停步與其餘方位不調整。
            if (delta.horizontalDistanceSqr() < 16) {
                if (delta.x < -0.00001 && delta.z > 0.00001) amount = strength;
                else if (delta.x > 0.00001 && delta.z < -0.00001) amount = -strength;
            }
        }
        AttributeModifier old = attribute.getModifier(MODIFIER);
        if (amount == 0) attribute.removeModifier(MODIFIER);
        else if (old == null || old.amount() != amount) {
            attribute.removeModifier(MODIFIER);
            attribute.addTransientModifier(new AttributeModifier(MODIFIER, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    public static void cancel(UUID id) {
        Sample sample = LAST.remove(id);
        if (sample != null) {
            var attribute = sample.player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attribute != null) attribute.removeModifier(MODIFIER);
        }
    }
    public static void clear() {
        for (UUID id : java.util.List.copyOf(LAST.keySet())) cancel(id);
    }
}
