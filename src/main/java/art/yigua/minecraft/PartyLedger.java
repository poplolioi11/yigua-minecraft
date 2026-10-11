package art.yigua.minecraft;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** 自有群組不占用原版隊伍；持有邀請碼的玩家才可自行加入。 */
public final class PartyLedger {
    public static final int MAX_MEMBERS = 16;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<String, Set<UUID>> groups = new TreeMap<>();
    private final Map<UUID, String> membership = new HashMap<>();
    public PartyLedger(MinecraftServer server) {
        this(server.getWorldPath(LevelResource.ROOT).resolve("data/yigua/groups.json"));
    }
    PartyLedger(Path file) {
        this.file = Objects.requireNonNull(file);
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("schemaVersion") || !root.get("schemaVersion").isJsonPrimitive()
                    || !root.getAsJsonPrimitive("schemaVersion").isNumber()
                    || root.get("schemaVersion").getAsBigDecimal().intValueExact() != 1) {
                throw new IllegalArgumentException("未知群組資料版本");
            }
            JsonObject saved = root.getAsJsonObject("groups");
            for (var entry : saved.entrySet()) {
                String code = normalize(entry.getKey());
                if (!code.equals(entry.getKey()) || !validCode(code)) throw new IllegalArgumentException("群組代碼無效");
                Set<UUID> members = new LinkedHashSet<>();
                for (JsonElement value : entry.getValue().getAsJsonArray()) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("群組成員無效");
                    UUID uuid = UUID.fromString(value.getAsString());
                    if (!uuid.toString().equals(value.getAsString()) || !members.add(uuid)
                            || membership.putIfAbsent(uuid, code) != null) throw new IllegalArgumentException("群組成員重複或 UUID 無效");
                }
                if (members.isEmpty() || members.size() > MAX_MEMBERS) throw new IllegalArgumentException("群組人數無效");
                groups.put(code, members);
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("一卦群組資料無法讀取，原始檔案保留：" + file, e);
        }
    }
    public static String normalize(String code) { return Objects.requireNonNull(code).toUpperCase(Locale.ROOT); }
    private static boolean validCode(String code) {
        return code.length() == 8 && code.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0);
    }
    public String group(UUID player) { return membership.get(Objects.requireNonNull(player)); }
    public Set<UUID> members(UUID player) {
        String code = group(player);
        return code == null ? Set.of() : Set.copyOf(groups.get(code));
    }
    public String create(UUID player) {
        Objects.requireNonNull(player);
        if (membership.containsKey(player)) throw new IllegalArgumentException("請先離開目前群組");
        String code;
        do {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 8; i++) text.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            code = text.toString();
        } while (groups.containsKey(code));
        groups.put(code, new LinkedHashSet<>(Set.of(player)));
        membership.put(player, code);
        save();
        return code;
    }
    public void join(UUID player, String invite) {
        Objects.requireNonNull(player);
        String code = normalize(invite);
        if (membership.containsKey(player)) throw new IllegalArgumentException("請先離開目前群組");
        Set<UUID> members = groups.get(code);
        if (!validCode(code) || members == null) throw new IllegalArgumentException("找不到此邀請碼");
        if (members.size() >= MAX_MEMBERS) throw new IllegalArgumentException("群組已滿，最多十六人");
        members.add(player); membership.put(player, code); save();
    }
    public boolean leave(UUID player) {
        String code = membership.remove(Objects.requireNonNull(player));
        if (code == null) return false;
        Set<UUID> members = groups.get(code);
        members.remove(player);
        if (members.isEmpty()) groups.remove(code);
        save(); return true;
    }
    public void save() {
        Path pending = file.resolveSibling("groups.json.pending");
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject(); root.addProperty("schemaVersion", 1);
            JsonObject saved = new JsonObject();
            groups.forEach((code, members) -> {
                JsonArray values = new JsonArray();
                members.stream().map(UUID::toString).sorted().forEach(values::add);
                saved.add(code, values);
            });
            root.add("groups", saved);
            Files.writeString(pending, GSON.toJson(root), StandardCharsets.UTF_8);
            try { Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("一卦群組資料寫入失敗：" + file, e); }
    }
}
