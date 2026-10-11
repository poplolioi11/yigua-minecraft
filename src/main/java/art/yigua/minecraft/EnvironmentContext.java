package art.yigua.minecraft;

import java.math.BigDecimal;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;

/** 起卦完成當刻的遊戲情境；各項修正有限，總幅度不超過正負百分之十。 */
public record EnvironmentContext(double multiplier, String description) {
    public static EnvironmentContext capture(ServerPlayer player) {
        double healthRatio = player.getHealth() / player.getMaxHealth();
        int health = healthRatio >= 0.75 ? 1 : healthRatio <= 0.25 ? -1 : 0;
        var biome = player.level().getBiome(player.blockPosition());
        int place = biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_JUNGLE) ? 1
            : biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(BiomeTags.IS_OCEAN) ? -1 : 0;
        int weather = player.level().isThundering() ? -1 : player.level().isRaining() ? 0 : 1;
        long nearby = player.level().getEntitiesOfClass(LivingEntity.class,
            player.getBoundingBox().inflate(8), entity -> entity != player && entity.isAlive()).size();
        int creatures = nearby == 0 ? -1 : nearby >= 8 ? 1 : 0;
        long time = Math.floorMod(player.level().getOverworldClockTime(), 24000);
        int daylight = time >= 1000 && time < 12000 ? 1 : -1;
        Direction looking = player.getDirection();
        int direction = looking == Direction.NORTH ? 1 : looking == Direction.SOUTH ? -1 : 0;
        ServerLevel level = (ServerLevel)player.level();
        var environment = level.environmentAttributes();
        int phase = environment.getValue(EnvironmentAttributes.MOON_PHASE, player.blockPosition()).index();
        int celestial = !level.dimensionType().hasSkyLight() || daylight > 0 ? 0
            : phase == 0 ? 1 : phase == 4 ? -1 : 0;
        float cloudHeight = environment.getValue(EnvironmentAttributes.CLOUD_HEIGHT, player.blockPosition());
        var cloudColor = environment.getValue(EnvironmentAttributes.CLOUD_COLOR, player.blockPosition());
        float brightness = (cloudColor.x() + cloudColor.y() + cloudColor.z()) / 3f;
        int clouds = !level.dimensionType().hasSkyLight() || !Float.isFinite(cloudHeight) || cloudColor.w() <= 0 ? 0
            : player.getY() >= cloudHeight ? 1 : brightness < 0.45f ? -1 : 0;
        int score = Math.clamp(health + place + weather + creatures + daylight + direction + celestial + clouds, -5, 5);
        double multiplier = BigDecimal.ONE.add(BigDecimal.valueOf(score).multiply(new BigDecimal("0.02"))).doubleValue();
        String description = "生命值、生態域、天氣、附近生物、遊戲時間、朝向、天象與雲象會微幅修正效果，每項不超過3%、合計不超過10%；權重請自行探索。";
        return new EnvironmentContext(multiplier, description);
    }

    public static Library.Rule scale(Library.Rule rule, double multiplier) {
        double amount = BigDecimal.valueOf(rule.amount()).multiply(BigDecimal.valueOf(multiplier)).doubleValue();
        return new Library.Rule(rule.id(), rule.number(), rule.line(), rule.profile(), amount,
            rule.durationTicks(), rule.priority(), rule.rationale(), rule.origText());
    }
}
