package art.yigua.minecraft;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.TreeNodePosition;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.advancements.AdvancementVisibilityEvaluator;

/** 使用真正原生 codec、可見性與進度狀態，核對未獲得卦、亂序解鎖與跨模組界線。 */
public final class AdvancementLayoutSelfTest {
    private static final Identifier ROOT = Identifier.fromNamespaceAndPath("yigua", "hexagrams/root");
    private static int checks;
    private AdvancementLayoutSelfTest() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path data = Path.of("src/main/resources/data/yigua/advancement");
        var ops = RegistryOps.create(JsonOps.INSTANCE,
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        List<AdvancementHolder> holders = new ArrayList<>();
        holders.add(read(data.resolve("hexagrams/root.json"), ROOT, ops));
        for (int number = 1; number <= 64; number++) {
            var holder = read(data.resolve("hexagrams/%02d.json".formatted(number)), id(number), ops);
            require(holder.value().parent().orElseThrow().equals(ROOT), "卦象不是根的直接子節點");
            require(!holder.value().display().orElseThrow().hidden(), "一般卦象被隱藏");
            require(holder.value().criteria().keySet().equals(Set.of("obtained")), "取得條件變更");
            holders.add(holder);
        }
        require(holders.get(1).value().display().orElseThrow().title().getString().equals("第 1 卦・乾"), "首卦錯誤");
        require(holders.get(64).value().display().orElseThrow().title().getString().equals("第 64 卦・未濟"), "末卦錯誤");

        // 原有8條深鏈會只顯示16卦；直接子節點應一次顯示完整64卦。
        List<AdvancementHolder> old = new ArrayList<>();
        old.add(holders.getFirst());
        for (int number = 1; number <= 64; number++) {
            Advancement value = holders.get(number).value();
            Identifier parent = (number - 1) % 8 == 0 ? ROOT : id(number - 1);
            old.add(new AdvancementHolder(id(number), new Advancement(Optional.of(parent), value.display(),
                value.rewards(), value.criteria(), value.requirements(), value.sendsTelemetryEvent())));
        }
        AdvancementTree oldTree = tree(old);
        require(visible(oldTree.get(ROOT), Set.of(ROOT)).size() == 17, "原版可見性回歸案例未重現");

        for (int seed = 0; seed < 12; seed++) {
            List<AdvancementHolder> shuffled = new ArrayList<>(holders);
            Collections.shuffle(shuffled, new Random(seed));
            AdvancementTree tree = tree(shuffled);
            AdvancementNode root = tree.get(ROOT);
            TreeNodePosition.run(root);
            require(HexagramAdvancementLayout.apply(root), "本模組網格未套用");
            require(root.x() == 0 && root.y() == -1, "根與網格重疊");
            require(visible(root, Set.of(ROOT)).size() == 65, "未取得的卦沒有全數顯示");
            Set<String> locations = new HashSet<>();
            for (int number = 1; number <= 64; number++) {
                AdvancementNode node = tree.get(id(number));
                require(node.x() == (number - 1) % 8 && node.y() == (number - 1) / 8,
                    "卦序依載入／雜湊順序而改變");
                require(locations.add(node.x() + ":" + node.y()), "兩卦位置重疊");
            }
        }

        AdvancementTree current = tree(holders);
        Map<Identifier, AdvancementProgress> progress = new HashMap<>();
        for (AdvancementHolder holder : holders) {
            var state = new AdvancementProgress();
            state.update(holder.value().requirements());
            progress.put(holder.id(), state);
        }
        progress.get(ROOT).grantProgress("obtained");
        require(progress.get(id(64)).grantProgress("obtained"), "未濟無法亂序解鎖");
        require(progress.get(id(64)).isDone(), "未濟取得後未完成");
        for (int number = 1; number < 64; number++) {
            require(!progress.get(id(number)).isDone(), "解鎖未濟連帶授予其他卦");
        }
        Set<Identifier> visibleAfter = new HashSet<>();
        AdvancementVisibilityEvaluator.evaluateVisibility(current.get(ROOT),
            node -> progress.get(node.holder().id()).isDone(),
            (node, visible) -> { if (visible) visibleAfter.add(node.holder().id()); });
        require(visibleAfter.size() == 65, "亂序解鎖使其他卦消失");

        // 原版／其他模組的樹不能被改位，隱藏挑戰仍然服從原生隱藏規則。
        var effectRoot = read(data.resolve("effects/root.json"),
            Identifier.fromNamespaceAndPath("yigua", "effects/root"), ops);
        var secret = read(data.resolve("effects/secret_obstruction.json"),
            Identifier.fromNamespaceAndPath("yigua", "effects/secret_obstruction"), ops);
        AdvancementTree effects = tree(List.of(effectRoot, secret));
        AdvancementNode effectNode = effects.get(effectRoot.id());
        TreeNodePosition.run(effectNode);
        float effectX = effectNode.x(), effectY = effectNode.y();
        require(!HexagramAdvancementLayout.apply(effectNode), "效果樹被覆寫");
        require(effectNode.x() == effectX && effectNode.y() == effectY, "效果根被搬動");
        require(!visible(effectNode, Set.of(effectRoot.id())).contains(secret.id()), "未取得的秘密被洩露");
        require(visible(effectNode, Set.of(effectRoot.id(), secret.id())).contains(secret.id()), "秘密取得後未顯示");
        var foreign = new AdvancementHolder(Identifier.fromNamespaceAndPath("another_mod", "hexagrams/root"), holders.getFirst().value());
        AdvancementNode foreignNode = new AdvancementNode(foreign, null);
        foreignNode.setLocation(4, 9);
        require(!HexagramAdvancementLayout.apply(foreignNode), "其他模組同名根被改動");
        require(foreignNode.x() == 4 && foreignNode.y() == 9, "其他模組座標變更");
        for (String invalid : List.of("hexagrams/00", "hexagrams/65", "hexagrams/1", "hexagrams/010", "hexagrams/ab")) {
            require(HexagramAdvancementLayout.number(Identifier.fromNamespaceAndPath("yigua", invalid)) == 0,
                "無效卦號被採用");
        }
        require(HexagramAdvancementLayout.number(Identifier.fromNamespaceAndPath("another_mod", "hexagrams/01")) == 0,
            "其他模組卦號被採用");
        System.out.println("AdvancementLayoutSelfTest：" + checks + " 項原生可見性、卦序、亂序取得與隔離核對通過。");
    }

    private static AdvancementHolder read(Path path, Identifier id, RegistryOps<com.google.gson.JsonElement> ops) throws Exception {
        Advancement value = Advancement.CODEC.parse(ops, JsonParser.parseString(Files.readString(path))).getOrThrow();
        return new AdvancementHolder(id, value);
    }

    private static AdvancementTree tree(List<AdvancementHolder> holders) {
        var tree = new AdvancementTree();
        tree.addAll(holders);
        require(tree.nodes().size() == holders.size(), "原生樹漏載入節點");
        return tree;
    }

    private static Set<Identifier> visible(AdvancementNode root, Set<Identifier> done) {
        Set<Identifier> visible = new HashSet<>();
        AdvancementVisibilityEvaluator.evaluateVisibility(root, node -> done.contains(node.holder().id()),
            (node, shown) -> { if (shown) visible.add(node.holder().id()); });
        return visible;
    }

    private static Identifier id(int number) {
        return Identifier.fromNamespaceAndPath("yigua", "hexagrams/%02d".formatted(number));
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
