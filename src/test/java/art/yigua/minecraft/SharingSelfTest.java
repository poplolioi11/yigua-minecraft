package art.yigua.minecraft;

import java.nio.file.*;
import java.util.*;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/** 保存、舊檔預設與補差數學；真正玩家屬性另在多人遊戲中驗證。 */
public final class SharingSelfTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("無效操作必須拒絕");
    }
    private static void near(double actual, double expected) {
        check(Math.abs(actual - expected) < 1e-10, actual + " != " + expected);
    }
    public static void main(String[] arguments) throws Exception {
        Path directory = Files.createTempDirectory("yigua-sharing-test-");
        try {
            UUID a = UUID.randomUUID(), b = UUID.randomUUID();
            Path file = directory.resolve("groups.json");
            PartyLedger initial = new PartyLedger(file);
            check(initial.group(a) == null && initial.members(a).isEmpty(), "預設沒有群組");
            String invite = initial.create(a);
            rejects(() -> initial.create(a));
            initial.join(b, invite.toLowerCase(Locale.ROOT));
            check(initial.members(a).equals(Set.of(a, b)), "邀請加入且大小寫一致");
            rejects(() -> initial.join(b, invite));
            Set<UUID> snapshot = initial.members(a);
            rejects(() -> initial.join(UUID.randomUUID(), "UNKNOWN1"));
            PartyLedger parties = new PartyLedger(file);
            check(parties.members(b).equals(Set.of(a, b)), "世界重讀保留獨立群組");
            for (int index = 2; index < PartyLedger.MAX_MEMBERS; index++) parties.join(UUID.randomUUID(), invite);
            final PartyLedger full = parties;
            rejects(() -> full.join(UUID.randomUUID(), invite));
            check(parties.members(a).size() == 16 && snapshot.size() == 2, "群組16人上限與不可變快照");
            check(parties.leave(b) && parties.group(b) == null, "只離開本人");
            check(!parties.leave(b), "重複離開不影響其他人");
            for (UUID id : parties.members(a)) parties.leave(id);
            check(new PartyLedger(file).members(a).isEmpty(), "空群組移除且不殘留");
            String bad = "{\"schemaVersion\":1,\"groups\":{\"ABCDEFGH\":[\"" + a + "\",\"" + a + "\"]}}";
            Files.writeString(file, bad); rejects(() -> new PartyLedger(file));
            check(Files.readString(file).equals(bad), "錯誤群組不得覆蓋原始資料");
            Files.writeString(file, "{\"schemaVersion\":1.1,\"groups\":{}}");
            rejects(() -> new PartyLedger(file));
            Path players = directory.resolve("players.json");
            PlayerLedger ledger = new PlayerLedger(players);
            check(!ledger.state(a).shareSend && !ledger.state(a).shareReceive, "預設個人模式");
            ledger.state(a).shareSend = true; ledger.markDirty(); ledger.save();
            check(new PlayerLedger(players).state(a).shareSend && !new PlayerLedger(players).state(b).shareSend,
                "選項按UUID保存不混用");
            String legacy = Files.readString(players).replaceAll("\\s*\"shareSend\": (true|false),", "")
                .replaceAll("\\s*\"shareReceive\": (true|false),", "");
            Files.writeString(players, legacy);
            check(!new PlayerLedger(players).state(a).shareSend && !new PlayerLedger(players).state(a).shareReceive,
                "舊資料沒有選項也不擅自同意");
            AttributeModifier.Operation add = AttributeModifier.Operation.ADD_VALUE;
            AttributeModifier.Operation mult = AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            for (double own : new double[] {0, .05, .1, .2, .4}) {
                for (double offered : new double[] {.05, .1, .2, .4}) {
                    near(own + GroupSharing.supplement(own, offered, add), Math.max(own, offered));
                    near((1 + own) * (1 + GroupSharing.supplement(own, offered, mult)), 1 + Math.max(own, offered));
                    near((1 - own) * (1 + GroupSharing.supplement(-own, -offered, mult)), 1 - Math.max(own, offered));
                }
            }
            rejects(() -> GroupSharing.supplement(Double.NaN, .1, mult));
            System.out.println("共享保存與補差驗收通過：" + checks + " 項；真正TCP與屬性撤回另有遊戲驗證。");
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
