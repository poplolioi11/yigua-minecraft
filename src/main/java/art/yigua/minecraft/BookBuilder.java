package art.yigua.minecraft;

import art.yigua.core.CastResult;
import art.yigua.core.CastingEngine;
import art.yigua.core.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * 書頁以原版可閱讀的書呈現教學、圖鑑與起卦結果。
 * 中文每行保守限制九個 Unicode codepoint，每頁十行正文，末行只保留目錄入口。
 */
public final class BookBuilder {
    private static final int CODEPOINTS_PER_LINE = 9;
    private static final int CONTENT_LINES_PER_PAGE = 10;
    private static final int MAX_PAGES = 99;
    private static final String AUTHOR = "poplolioi11";
    private static final String WEBSITE = "https://yigua.art/";
    private static final String DATA_REPOSITORY = "https://github.com/poplolioi11/yigua-wuyingdian-lines";
    private static final String[] LINE_NAMES = {"初爻", "二爻", "三爻", "四爻", "五爻", "上爻"};

    private BookBuilder() {}

    /** 右鍵總覽書的短首頁；完整規則與設定各有獨立入口，無須翻閱規則冊。 */
    public static WrittenBookContent menu() {
        List<Filterable<Component>> pages = new ArrayList<>();
        addActionPage(pages, "一卦目錄", List.of(
            command("傳統卜筮選項", "/yigua traditional"),
            command("多人與共享", "/yigua sharing"),
            command("起卦模式", "/yigua operation"),
            command("閱讀規則", "/yigua guide"),
            command("查六十四卦", "/yigua catalog"),
            command("個人圖鑑", "/yigua atlas"),
            command("最近起卦", "/yigua result"),
            command("太極工具", "/yigua taiji")
        ));
        return book("一卦目錄", pages);
    }

    public static WrittenBookContent operationOptions() {
        List<Filterable<Component>> pages = new ArrayList<>();
        addOperationOptions(pages, false);
        return book("起卦模式", pages);
    }

    public static WrittenBookContent traditionalOptions() {
        List<Filterable<Component>> pages = new ArrayList<>();
        addTraditionalOptions(pages, false);
        return book("傳統卜筮選項", pages);
    }

    /** 群組碼只顯示為文字；加入必須由玩家自己輸入，不能在書頁偷偷替玩家加入。 */
    public static WrittenBookContent sharingOptions(boolean send, boolean receive, String group, int members,
            boolean enabled) {
        String code = group == null ? "" : group;
        if (!code.isEmpty() && !code.matches("[A-Za-z0-9]{8}")) {
            throw new IllegalArgumentException("群組代碼須為八個英數字");
        }
        if (members < 0 || members > 16) throw new IllegalArgumentException("群組人數須介於零至十六人");
        List<Filterable<Component>> pages = new ArrayList<>();
        addActionPage(pages, "多人與共享", List.of(
            command(send ? "送出開（已選）" : "開啟送出共享", "/yigua sharing send on"),
            command(send ? "關閉送出共享" : "送出關（已選）", "/yigua sharing send off"),
            command(receive ? "接收開（已選）" : "開啟接收共享", "/yigua sharing receive on"),
            command(receive ? "關閉接收共享" : "接收關（已選）", "/yigua sharing receive off"),
            command("建立自己的群組", "/yigua group create"),
            command("離開目前群組", "/yigua group leave"),
            Component.literal(code.isEmpty() ? "尚未加入群組" : code),
            Component.literal(enabled ? "成員：" + members + "/16" : "伺服器停用共享")
        ));
        addSection(pages, "加入群組", "自行在聊天輸入：\n"
            + "/yigua group join CODE\n"
            + "CODE換成群組碼\n"
            + "預設個人不分享。\n"
            + "完整規則在閱讀規則");
        return book("多人與共享", pages);
    }

    /** 與規則冊分開的八頁查卦目錄。 */
    public static WrittenBookContent catalog() {
        List<Filterable<Component>> pages = new ArrayList<>();
        addCatalog(pages, null);
        return book("六十四卦總目", pages);
    }

    public static WrittenBookContent guide() {
        List<Filterable<Component>> pages = new ArrayList<>();
        addActionPage(pages, "閱讀規則", List.of(
            Component.literal("用途：教學與規則"),
            Component.literal("查卦與設定回目錄"),
            Component.literal("想讀規則再慢慢看"),
            command("打開個人圖鑑", "/yigua atlas"),
            command("查看最近起卦", "/yigua result"),
            command("查看原生成就", "/yigua-achievements"),
            command("查六十四卦", "/yigua catalog")
        ));
        addSection(pages, "占筮入門", "首次進入可取得總覽書，也能自行合成；右鍵先打開一頁目錄。\n"
            + "目錄直接提供起卦選項、多人共享、查卦與個人圖鑑，不用先翻完整規則。\n"
            + "圖鑑記錄你實際起卦取得的卦象；閱讀規則或查卦不會解鎖圖鑑。");
        addSection(pages, "合成與材料", "一枚銅錠可製作三枚銅錢。\n"
            + "三枚銅錢製作三錢筮具，不需要線。\n"
            + "木錢、鐵錢、褐銅錢、綠銅錢與金錢的製法請看合成表。\n"
            + "二根竹子搭配一條線或一條藤蔓，可製作竹籌筮具。\n"
            + "二根木棒加一條線製作木籌；兩份長條植物加一條線製作草籌。\n"
            + "草籌可用甘蔗、高草或大型蕨，實際材料請看合成表。\n"
            + "所有筮具都可在背包2×2合成區製作。\n"
            + "兩份植物材料可製作一條線，竹子、甘蔗、藤蔓、蕨、草與樹葉可混用。\n"
            + "先用二根竹子製作一條線，再用二根竹子加線製作竹籌；總共四根竹子即可，不需要蜘蛛。\n"
            + "綁線或綁藤蔓，各有原色與烤製色，共四種外觀。\n"
            + "熔爐烤製既有竹籌會保留綁材，只改變外觀，不影響機率與效果。\n"
            + "木籌與草籌沒有烤製版本。\n"
            + "書與銅錢製作總覽；書與竹子製作圖鑑。\n"
            + "實際格位及材料數量請看遊戲合成表。");
        addSection(pages, "材質與效果倍率", "基礎效果以褐銅錢或木籌為一倍。\n"
            + "木錢0.5倍、鐵錢0.75倍、褐銅錢1倍、綠銅錢1.25倍、金錢1.5倍。\n"
            + "草籌0.5倍、木籌1倍、竹籌1.5倍；竹籌在籌具中最強。\n"
            + "竹籌原色與烤製、綁線與綁藤蔓，都是1.5倍。\n"
            + "材質影響在基礎效果上下五成內。\n"
            + "不同錢材質仍用相同三錢法；材質不改變爻值機率。\n"
            + "增益與減益都按材質倍率縮放，負面效果也會一起放大。\n"
            + "無動爻時一般中性卦不增加效果；動爻可另加效果，六爻皆變等特殊卦勢仍可有額外效果。\n"
            + "效果總時長仍為12000遊戲刻。\n"
            + "起卦紀錄保存當時材質，換手持工具不會改變過往結果。\n"
            + "舊版三錢與竹籌紀錄維持1倍，標為「舊版三錢」或「舊版竹籌」。");
        addSection(pages, "身心與環境", "身心、生態域、天氣、附近生物、世界時間、看向方位、天象與雲象，會微調正面與負面效果強度。\n"
            + "每項影響不超過3%，總影響不超過10%；各項權重留給玩家自行探索。\n"
            + "環境不改變爻值機率或效果時長；本次起卦的倍率隨正式紀錄保存。");
        addVisibleEffectTable(pages);
        addSection(pages, "順方與移動", "順方依玩家實際水平移動方向判定，不使用鏡頭朝向。\n"
            + "朝西南移動時增速，朝東北移動時減速，其他方位不變；靜止時不增減速。\n"
            + "順方仍隨本卦或變卦的剩餘階段倒數，不另延長時長。\n"
            + "這只取特定處難意象作遊戲規則，不把所有方位固化為吉凶。");
        addSection(pages, "屬性適用界線", "生命上限不是補血；燃燒時間不是火焰免疫。\n"
            + "方塊與實體互動距離不是視野距離，也不保證攻擊穿牆。\n"
            + "擊退抗性使用加值，並非相同比例的擊退距離變化。\n"
            + "氧氣加成與額外耗氧不代表固定氣泡數，仍依原版氧氣機制。\n"
            + "水下挖掘速度只影響水下操作，不等於水下移動速度。");
        addSection(pages, "原生成就", "點選「查看原生成就」，開啟原版成就畫面。\n"
            + "卦象與效果分為兩個分頁，可查看收集進度。\n"
            + "極少數項目需要親自發現，總覽不列出提示。");
        addSection(pages, "太極工具", "創造模式可直接使用太極工具。\n"
            + "生存模式須收齊六十四卦圖鑑、六十四卦成就、三十二個一般效果與全部神秘成就；達成後會獲贈一件。\n"
            + "工具可指定由初爻往上的六爻、直接選卦象或效果。指定結果明確標示，不冒充隨機起卦。\n"
            + "直接選效果不增加卦象圖鑑；太極效果不共享。\n"
            + "解除一卦狀態會取消目前卜筮、效果與冷卻，並關閉接收共享；圖鑑與成就仍保留。\n"
            + "遺失工具可用/yigua taiji claim重新領取；資格由伺服器驗證。");
        addSection(pages, "開始起卦", "預設採按鈕入口與自動方式：手持筮具，按右鍵開啟角落面板。\n"
            + "面板提供開始、練習與關閉，也能切換玩法、操作頁面與動畫。\n"
            + "面板可切換傳統、自動、馬上三種起卦方式，再選正式或練習。\n"
            + "自動方式回到遊戲，在眼前播放約三秒的六爻動畫。\n"
            + "傳統方式先完成逐枚投錢或十八變分堆，再播放揭曉動畫。\n"
            + "馬上方式略過動畫，直接在聊天列顯示結果；正式起卦仍有相同效果與冷卻。\n"
            + "改為直接入口後，右鍵工具依選定方式開始正式起卦，無須再按面板按鈕；直接入口不等於馬上方式。\n"
            + "正式起卦會產生效果並記入圖鑑；練習不生效，也不記入圖鑑。\n"
            + "六爻由下往上生成：初爻最先，上爻最後。\n"
            + "眼前有錢幣或籌具的像素動畫，另搭配六個節拍、粒子與音效；可在起卦設定開關眼前動畫。\n"
            + "竹籌動畫是蓍草法的表意演示，不是完整十八變操作。\n"
            + "動畫完成後，聊天列顯示本卦、變卦、動爻與效果。\n"
            + "結果書不會自動開啟；想讀正式結果，可用/yigua result，或點目錄的「最近起卦」。");
        addOperationOptions(pages, true);
        addTraditionalOptions(pages, true);
        addSharingRules(pages);
        addSection(pages, "傳統手動蓍草", "每爻先取四十九根竹籌，五十策中的一策不用。\n"
            + "每變輸入左堆根數，範圍為1至剩餘根數減2；右堆是餘下的竹籌，兩邊都不空。\n"
            + "掛一：右堆取一根。\n"
            + "揲四：兩堆各以四根一組，整除四時留四根。\n"
            + "歸奇：收起兩堆餘數與掛一的一根。\n"
            + "第一變去五或九根；第二、三變各去四或八根。\n"
            + "每爻做三變，餘二十四、二十八、三十二或三十六根，除四得六、七、八或九。\n"
            + "由初爻往上做六爻，共十八變；右側顯示每爻紀錄。\n"
            + "每完成一爻，新一爻重取四十九根，不能沿用上一爻餘數。\n"
            + "尚未完成可取消，不記正式起卦，也不套用效果。\n"
            + "全部完成後仍播放約三秒揭曉動畫，結果顯示於聊天。");
        addSection(pages, "三錢逐枚投擲", "每次只投一枚錢，正反兩面分別記二或三。\n"
            + "每三次的面額相加，得六、七、八或九，形成一爻。\n"
            + "由初爻往上記六爻，共投十八次；右側顯示由下往上的六爻紀錄。\n"
            + "未完成可取消，不記正式起卦或效果；完成後播放約三秒揭曉動畫，再以聊天顯示結果。");
        addSection(pages, "手動與機率", "手動蓍草由玩家選擇分堆，不是均勻隨機抽取；玩家選擇會改變卦象機率。\n"
            + "稀有卦勢的機率數字只作標準隨機法參考，不是手動分堆的固定機率。\n"
            + "三錢逐枚投擲仍以每枚公平的二或三相加成爻。");
        addSection(pages, "效果與冷卻", "遊戲效果及起卦冷卻預設為一萬二千遊戲刻。\n"
            + "僅玩家在線時倒數；標準20TPS約十分鐘。\n"
            + "有變卦時，前五分鐘使用本卦效果，後五分鐘改用變卦效果；沒有變卦則同組效果持續十分鐘。\n"
            + "兩階段共用同一次冷卻，不會在中途重抽或刷新起卦紀錄。\n"
            + "正式起卦冷卻未結束時，按「開始起卦」只顯示冷卻提示，不會重抽或刷新效果。\n"
            + "等待期間仍可選「練習起卦」。實際效果以聊天結果或正式結果書為準。");
        addSection(pages, "動爻與效果", "無動爻維持一組基礎效果。\n"
            + "一至二個動爻另加一組；三至五個動爻最多另加兩組；六爻皆變維持特殊卦勢的兩至三組效果。\n"
            + "追加依動爻位置、六或九的爻值與完整六爻紀錄確定，同效果類型不重複，不是再次抽籤。\n"
            + "追加沿用本次材質及環境倍率，不延長時長與冷卻。\n"
            + "這是遊戲規則，並非384爻都完成傳統解釋。");
        addSection(pages, "三錢法", "每枚錢的兩面分別記二與三。\n"
            + "三枚一起擲，數值相加，得到六、七、八或九。\n"
            + "如此六擲，從初爻往上記錄，形成一卦。\n"
            + "遊戲使用三枚公平錢幣的八種等可能結果。");
        addSection(pages, "蓍草與竹籌", "傳統蓍草法用五十策，先取出一策不用，餘四十九策。\n"
            + "遊戲用竹籌替代蓍草材料，一般起卦使用傳統三變的理想化餘數機率；手動起卦依玩家分堆結果。\n"
            + "這不是把每次分堆位置視為均勻。");
        addSection(pages, "四營與三變", "四營是分二、掛一、揲四、歸奇。\n"
            + "分二：分成左右兩堆。\n"
            + "掛一：從右堆取一策。\n"
            + "揲四：兩堆各以四策一組計數，餘零作四。\n"
            + "歸奇：收起兩堆餘數與掛一之策。\n"
            + "將剩餘策再次分堆，連做三變，得到一爻。");
        addSection(pages, "十八變成卦", "第一變收起五或九策；第二、三變各收起四或八策。\n"
            + "三變後所餘策數除以四，得到六、七、八或九。\n"
            + "一爻三變，六爻合計十八變。\n"
            + "三錢與蓍草的四種爻值機率不同，不應混為一談。");
        addSection(pages, "爻值與動爻", "六是老陰，陰爻，動而變陽。\n"
            + "七是少陽，陽爻，不動。\n"
            + "八是少陰，陰爻，不動。\n"
            + "九是老陽，陽爻，動而變陰。\n"
            + "先讀原有六爻構成本卦，只翻轉動爻便得變卦。\n"
            + "無動爻時標示無變卦。全九另列用九，全六另列用六。");
        addSection(pages, "閱讀經文", "從目錄選「查六十四卦」，依文王卦序列出六十四卦；點擊卦名先看基礎遊戲效果，再翻頁閱讀完整六爻。\n"
            + "完整六爻使用武英殿版爻辭，含用九與用六。\n"
            + "遊戲轉譯另參考文淵閣四庫本《伊川易傳》的卦辭與短段程傳，兩種版本分開。\n"
            + "伊川資料為初錄稿，未經逐字影像二校；恒僅有局部核對，不代表全文已校對。\n"
            + "原文與遊戲效果分開呈現。增益與減益是遊戲改編，不是經文的唯一解釋。");
        addLinks(pages);
        return book("易經占筮規則", pages);
    }

    private static void addOperationOptions(List<Filterable<Component>> pages, boolean includeRules) {
        addActionPage(pages, "起卦操作設定", List.of(
            command("傳統逐步卜筮", "/yigua-ritual-mode traditional"),
            command("自動動畫起卦", "/yigua-ritual-mode auto"),
            command("馬上顯示結果", "/yigua-ritual-mode instant"),
            command("按鈕入口", "/yigua-mode button"),
            command("直接入口", "/yigua-mode direct"),
            Component.literal("玩法與入口分開選"),
            command("開啟眼前動畫", "/yigua-animation on"),
            command("關閉眼前動畫", "/yigua-animation off")
        ));
        if (!includeRules) return;
        addSection(pages, "起卦方式說明", "傳統：依手持工具，逐枚投錢或做十八變分堆。\n"
            + "自動：以標準隨機法起卦，播放約三秒動畫。\n"
            + "馬上：以相同標準隨機法起卦，略過動畫直接顯示聊天結果。\n"
            + "三種方式都能正式起卦或練習，正式效果與冷卻規則相同。\n"
            + "錢與籌的方式各自保存，角落切換只更動目前工具；書中選方式會同時設定兩種工具。\n"
            + "按鈕入口先開角落面板；直接入口右鍵即依選定方式開始。\n"
            + "直接入口仍可選傳統或自動，不等於馬上起卦。\n"
            + "聊天顯示結果，不會自動開書；眼前動畫可開關。\n"
            + "選擇僅儲存在本機，每位玩家自行選。");
    }

    private static void addTraditionalOptions(List<Filterable<Component>> pages, boolean includeRules) {
        addActionPage(pages, "傳統操作設定", List.of(
            command("開傳統蓍草", "/yigua-ritual yarrow on"),
            command("關傳統蓍草", "/yigua-ritual yarrow off"),
            command("開逐枚投錢", "/yigua-ritual coins on"),
            command("關逐枚投錢", "/yigua-ritual coins off"),
            command("文字操作頁面", "/yigua-ritual-ui text"),
            command("拖曳互動頁面", "/yigua-ritual-ui interactive"),
            Component.literal("預設自動起卦"),
            Component.literal("各玩家本機保存。")
        ));
        if (!includeRules) return;
        addSection(pages, "傳統操作介面", "傳統起卦可選文字操作頁面或拖曳互動頁面，預設互動拖曳。\n"
            + "錢與籌預設自動方式；開傳統蓍草或逐枚投錢，將該工具改為傳統；關閉則改回自動。\n"
            + "文字頁面以輸入左堆根數或按鍵投錢操作。\n"
            + "互動頁面拖曳竹籌分堆，或逐枚拖曳錢幣投擲。\n"
            + "兩種頁面都依相同三變、十八變及逐枚投錢規則計算結果。\n"
            + "手動時開角落區；完成後播放動畫，聊天顯示結果。\n"
            + "介面切換不重置目前步驟；各玩家本機保存選擇。\n"
            + "未完成可取消，不記正式起卦或效果。");
    }

    private static void addSharingRules(List<Filterable<Component>> pages) {
        addSection(pages, "群組共享規則", "預設個人模式，送出與接收共享都關閉。\n"
            + "同群組最多十六人，需在同一維度、彼此三十二格範圍內。\n"
            + "送出者開啟送出、接收者開啟接收，雙方同意才共享一般正面效果。\n"
            + "負面、稀有事件與順方效果不共享；本人原有負面效果也不會被移除。\n"
            + "同類正面效果取最高強度，自己與多人帶來的同類效果不重複相加。\n"
            + "收到共享不會解鎖個人圖鑑或成就，也不新增或刷新起卦冷卻。\n"
            + "離開範圍、換維度、離線、退出群組或關閉接收，收到的共享效果會失效；送出者關閉送出亦然。\n"
            + "共享沿用送出者目前卦象階段，不延長原有時長。\n"
            + "伺服器可停用共享，受保護的效果與屬性同樣不共享。");
    }

    private static void addVisibleEffectTable(List<Filterable<Component>> pages) {
        StringBuilder text = new StringBuilder("以下32種一般效果為褐銅錢／木籌的一倍基準；其他材質與環境會縮放效果。\n");
        for (String profile : Library.generalProfiles()) {
            Library.Rule example = new Library.Rule("guide_" + profile, 1, 0, profile, Library.baseAmount(profile),
                12000, 0, "", "");
            text.append(Library.effectSummary(example)).append('\n');
        }
        text.append("平和：基礎效果為中性，不計入32種。\n")
            .append("這些是遊戲比喻，幸運與下落加速度不是古籍本義。");
        addSection(pages, "可見效果總表", text.toString());
    }

    public static WrittenBookContent atlas(Set<Integer> discovered, Map<Integer, Integer> counts) {
        Set<Integer> saved = Set.copyOf(Objects.requireNonNull(discovered, "圖鑑紀錄不得為空"));
        Map<Integer, Integer> savedCounts = Map.copyOf(Objects.requireNonNull(counts, "圖鑑次數不得為空"));
        saved.forEach(BookBuilder::requireNumber);
        savedCounts.forEach((number, count) -> {
            requireNumber(number);
            if (count < 0) {
                throw new IllegalArgumentException("圖鑑紀錄次數不得為負值");
            }
        });
        List<Filterable<Component>> pages = new ArrayList<>();
        addActionPage(pages, "個人卦象圖鑑", List.of(
            Component.literal("用途：收集卦象"),
            Component.literal("已發現 " + saved.size() + "/64"),
            Component.literal("已：你起卦取得"),
            Component.literal("未：尚未取得"),
            Component.literal("點卦名看遊戲效果"),
            Component.literal("閱讀不解鎖或計次"),
            Component.literal("紀錄次數見後頁")
        ));
        addCatalog(pages, saved);
        StringBuilder record = new StringBuilder();
        for (int number = 1; number <= 64; number++) {
            if (saved.contains(number) || savedCounts.getOrDefault(number, 0) > 0) {
                if (!record.isEmpty()) {
                    record.append('\n');
                }
                record.append(number).append(' ').append(CastingEngine.nameForNumber(number))
                    .append('\n').append("紀錄：").append(savedCounts.getOrDefault(number, 0)).append("次");
            }
        }
        addSection(pages, "卦象紀錄次數", record.isEmpty() ? "尚無起卦紀錄。\n手持筮具按右鍵起卦。" : record.toString());
        return book("個人易經圖鑑", pages);
    }

    public static WrittenBookContent result(CastResult result, Library.Rule rule, int remainingTicks) {
        return result(result, List.of(Objects.requireNonNull(rule, "效果規則不得為空")), remainingTicks, "", "");
    }

    /** 直接指定效果不顯示過往卦象，避免把選擇結果誤認為隨機占筮。 */
    public static WrittenBookContent controlledEffect(String profile, int remainingTicks) {
        Objects.requireNonNull(profile, "效果名稱不得為空");
        String summary;
        if (Library.generalProfiles().contains(profile)) {
            summary = Library.effectSummary(new Library.Rule("taiji_" + profile, 0, 0, profile,
                Library.baseAmount(profile), 12000, 0, "", ""));
        } else {
            summary = switch (profile) {
                case "divine" -> "天降神光"; case "thunder" -> "天雷：可能致命";
                case "undying" -> "短暫不死護持"; case "five_qi" -> "五氣朝元";
                case "flowers" -> "三花聚頂"; case "birds" -> "萬鳥朝拜";
                case "rapport" -> "萬物親和"; case "sea" -> "分海";
                default -> throw new IllegalArgumentException("未知太極效果");
            };
        }
        List<Filterable<Component>> pages = new ArrayList<>();
        addSection(pages, "太極指定效果", "由玩家直接選擇，並非隨機起卦。\n" + summary
            + "\n不新增卦象圖鑑，不向群組共享。\n"
            + (remainingTicks > 0 ? "剩餘：標準20TPS約" + seconds(remainingTicks) + "秒。" : "目前已結束。")
            + "\n特殊事件的即時結果不會因翻書重複觸發。");
        return book("太極指定效果", pages);
    }

    public static WrittenBookContent result(CastResult result, List<Library.Rule> rules, int remainingTicks,
            String fortuneName, String contextSummary) {
        return result(result, rules, List.of(), remainingTicks, fortuneName, contextSummary);
    }

    public static WrittenBookContent result(CastResult result, List<Library.Rule> primaryRules,
            List<Library.Rule> changedRules, int remainingTicks, String fortuneName, String contextSummary) {
        Objects.requireNonNull(result, "起卦結果不得為空");
        List<Library.Rule> effects = List.copyOf(Objects.requireNonNull(primaryRules, "本卦效果規則不得為空"));
        List<Library.Rule> changedEffects = List.copyOf(Objects.requireNonNull(changedRules, "變卦效果規則不得為空"));
        if (effects.isEmpty()) throw new IllegalArgumentException("結果書至少需要一條效果規則");
        if (!changedEffects.isEmpty() && result.changedNumber() == 0) {
            throw new IllegalArgumentException("無變卦時不能顯示變卦階段效果");
        }
        Objects.requireNonNull(fortuneName, "卦勢名稱不得為空");
        Objects.requireNonNull(contextSummary, "材料與環境說明不得為空");
        boolean practice = remainingTicks == -1;
        boolean phased = !changedEffects.isEmpty();
        Library.Rule rule = phased && !practice && remainingTicks <= 6000 ? changedEffects.getFirst() : effects.getFirst();
        String phase = !phased ? "" : practice ? "練習：兩階段示例"
            : remainingTicks <= 0 ? "目前：兩階段已結束"
            : remainingTicks > 6000 ? "目前：前半本卦" : "目前：後半變卦";
        List<Filterable<Component>> pages = new ArrayList<>();
        addSection(pages, practice ? "練習起卦結果" : "起卦結果",
            "用途：本卦與效果\n本卦 " + result.primaryNumber() + " "
            + CastingEngine.nameForNumber(result.primaryNumber())
            + "\n" + (result.changedNumber() == 0 ? "變卦：無變卦"
                : "變卦 " + result.changedNumber() + " " + CastingEngine.nameForNumber(result.changedNumber()))
            + (hasText(fortuneName) ? "\n卦勢：" + fortuneName : "")
            + "\n" + (practice ? "正式效果示例" : "本次遊戲效果")
            + "\n" + Library.effectSummary(rule)
            + (phased ? "\n" + phase : "")
            + "\n" + (practice ? hasText(fortuneName) || phased ? "練習：無效果與紀錄" : "練習不生效、不記圖鑑。"
                : "時長與冷卻見後頁"), !hasText(fortuneName) && !phased
                    && codepoints(Library.effectSummary(rule)) <= CODEPOINTS_PER_LINE * 2);
        List<Component> diagram = new ArrayList<>();
        diagram.add(Component.literal("本卦六爻"));
        diagram.add(Component.literal("由上往下，動爻標動"));
        for (int line = 5; line >= 0; line--) {
            int value = result.lineValues().get(line);
            boolean moving = value == 6 || value == 9;
            diagram.add(Component.literal(LINE_NAMES[line] + value + (moving ? "動 " : " ") + symbol((value & 1) == 1)));
        }
        addPage(pages, diagram);
        if (hasText(contextSummary)) {
            addSection(pages, "材料與環境", contextSummary);
        }
        String cooldown = remainingTicks > 0
            ? "冷卻剩餘：標準20TPS約" + seconds(remainingTicks) + "秒。"
            : "冷卻已結束，可再次起卦。";
        addSection(pages, practice ? "正式效果示例" : "本次遊戲效果",
            (practice ? "練習，不產生效果、不記入圖鑑。\n正式起卦的效果示例：\n" : "")
            + "方法：" + (result.method() == Method.COINS ? "三錢法" : "竹籌蓍草法")
            + (phased ? "\n" + phase + "\n前五分鐘：本卦；後五分鐘：變卦。\n兩階段共用十分鐘冷卻。"
                : "\n" + effectText(effects, practice))
            + (practice ? "\n練習不新增或刷新冷卻。" : "\n" + cooldown)
            + "\n正式效果按玩家在線遊戲刻倒數。\n遊戲效果是經文意象的改編。");
        if (phased) {
            addSection(pages, "前五分鐘：本卦", effectText(effects, practice));
            addSection(pages, "後五分鐘：變卦", effectText(changedEffects, practice));
        }
        addRuleNotes(pages, effects, phased ? "本卦" : "");
        if (phased) addRuleNotes(pages, changedEffects, "變卦");
        addHexText(pages, Library.hex(result.primaryNumber()), result.changingLines(), result.special());
        if (result.changedNumber() != 0) {
            addActionPage(pages, "變卦閱讀入口", List.of(
                Component.literal("變卦：" + CastingEngine.nameForNumber(result.changedNumber())),
                command("閱讀變卦原文", "/yigua read " + result.changedNumber())
            ));
        }
        return book((practice ? "練習：" : "起卦：") + CastingEngine.nameForNumber(result.primaryNumber()), pages);
    }

    private static String effectText(List<Library.Rule> effects, boolean practice) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < effects.size(); index++) {
            Library.Rule effect = effects.get(index);
            if (!text.isEmpty()) text.append('\n');
            if (effects.size() > 1) text.append("效果").append(index + 1).append("：\n");
            text.append(Library.effectSummary(effect)).append('\n');
            text.append(effect.durationTicks() > 0
                ? (practice ? "示例時長：" : "效果時長：") + "標準20TPS約" + seconds(effect.durationTicks()) + "秒。"
                : "本次沒有持續效果。");
        }
        return text.toString();
    }

    private static void addRuleNotes(List<Filterable<Component>> pages, List<Library.Rule> effects, String phasePrefix) {
        for (int index = 0; index < effects.size(); index++) {
            Library.Rule effect = effects.get(index);
            if (hasText(effect.rationale())) {
                addSection(pages, phasePrefix + (effects.size() == 1 ? "效果設計說明" : "效果" + (index + 1) + "設計說明"), effect.rationale());
            }
            if (hasText(effect.origText())) {
                addSection(pages, phasePrefix + (effects.size() == 1 ? "本次效果依據" : "效果" + (index + 1) + "依據"), effect.origText());
            }
        }
    }

    public static WrittenBookContent reading(int number) {
        requireNumber(number);
        Library.HexText hex = Library.hex(number);
        List<Filterable<Component>> pages = new ArrayList<>();
        int mask = CastingEngine.maskForNumber(number);
        List<Integer> stableLines = new ArrayList<>();
        for (int line = 0; line < 6; line++) {
            stableLines.add((mask & (1 << line)) == 0 ? 8 : 7);
        }
        // 無動爻時只會選到本卦基礎規則，查閱本身不會套用效果。
        Library.Rule base = Library.rule(CastingEngine.fromLines(Method.COINS, stableLines));
        addSection(pages, "第" + number + "卦 " + hex.name(),
            "卦象閱讀：查效果\n基礎遊戲效果\n褐銅錢／木籌為1倍\n" + Library.effectSummary(base)
            + "\n動爻可能改變效果\n遊戲改編非經文本意\n查閱不會生效。", false);
        if (hasText(base.origText())) {
            addSection(pages, "遊戲轉譯依據", base.origText());
        }
        List<Component> diagram = new ArrayList<>();
        diagram.add(Component.literal("第" + number + "卦 " + hex.name()));
        diagram.add(Component.literal("卦象由上往下"));
        diagram.add(Component.literal(""));
        for (int line = 5; line >= 0; line--) {
            diagram.add(Component.literal(LINE_NAMES[line] + " " + symbol((mask & (1 << line)) != 0)));
        }
        addPage(pages, diagram);
        addHexText(pages, hex, List.of(), hex.specialLabel());
        return book("第" + number + "卦 " + hex.name(), pages);
    }

    private static void addHexText(List<Filterable<Component>> pages, Library.HexText hex,
            List<Integer> moving, String special) {
        for (int line = 0; line < 6; line++) {
            String label = LINE_NAMES[line] + (moving.contains(line + 1) ? "（動爻）" : "原文");
            String original = line < hex.lines().size() ? hex.lines().get(line) : "此爻原文尚未收錄。";
            String source = line < hex.lineSources().size() ? hex.lineSources().get(line) : "";
            // 不刪掉原文的爻名、標點、空白或尾字，長文由 addSection 接續分頁。
            addSection(pages, label, original + (hasText(source) ? "\n逐爻影像見公開資料。" : ""));
        }
        if (hasText(special) && hasText(hex.specialText())) {
            addSection(pages, special, hex.specialText());
        }
        if (hasText(hex.sourceNote())) {
            addSection(pages, "版本與資料說明", hex.sourceNote());
        }
    }

    /** 每頁八筆查卦目錄，保留標題、統計及目錄入口；不因是否解鎖而限制閱讀。 */
    private static void addCatalog(List<Filterable<Component>> pages, Set<Integer> discovered) {
        for (int start = 1; start <= 64; start += 8) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(discovered == null ? "六十四卦總目" : "個人圖鑑總目"));
            lines.add(Component.literal(discovered == null ? "點卦名看遊戲效果" : "已發現 " + discovered.size() + "/64"));
            for (int number = start; number < start + 8; number++) {
                String status = discovered == null ? "" : discovered.contains(number) ? " 已" : " 未";
                String label = (number < 10 ? "0" : "") + number + " " + CastingEngine.nameForNumber(number) + status;
                lines.add(command(label, "/yigua read " + number));
            }
            addPage(pages, lines);
        }
    }

    private static void addLinks(List<Filterable<Component>> pages) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("來源與延伸閱讀"));
        lines.add(Component.literal("古籍正文公有領域。"));
        lines.add(Component.literal("原創說明採"));
        lines.add(Component.literal("CC BY 4.0"));
        wrap("作者：" + AUTHOR).forEach(line -> lines.add(Component.literal(line)));
        lines.add(Component.literal("本模組開源。"));
        lines.add(Component.literal("程式碼採MIT。"));
        lines.add(external("一卦網站", WEBSITE));
        lines.add(external("武英殿公開資料", DATA_REPOSITORY));
        // 此頁省略標題後空白行，讓署名、兩種授權及連結仍完整放在末頁。
        addPage(pages, lines);
    }

    /** 將獨立段落拆成連續書頁，不讓下一爻混入上一爻的頁面。 */
    private static void addSection(List<Filterable<Component>> pages, String heading, String body) {
        addSection(pages, heading, body, true);
    }

    /** 可省略首頁的標題空白行，讓卦勢與首條效果在原版行限內完整顯示。 */
    private static void addSection(List<Filterable<Component>> pages, String heading, String body, boolean blankAfterHeading) {
        List<String> bodyLines = wrap(Objects.requireNonNull(body));
        int offset = 0;
        boolean continuation = false;
        do {
            List<Component> lines = new ArrayList<>();
            for (String line : wrap(heading + (continuation ? "（續）" : ""))) {
                lines.add(Component.literal(line));
            }
            if (blankAfterHeading) lines.add(Component.literal(""));
            int capacity = CONTENT_LINES_PER_PAGE - lines.size();
            if (capacity <= 0) {
                throw new IllegalArgumentException("書頁標題過長，無法放入正文");
            }
            int end = Math.min(bodyLines.size(), offset + capacity);
            while (offset < end) {
                lines.add(Component.literal(bodyLines.get(offset++)));
            }
            addPage(pages, lines);
            continuation = true;
        } while (offset < bodyLines.size());
    }

    private static void addActionPage(List<Filterable<Component>> pages, String heading, List<Component> actions) {
        List<Component> lines = new ArrayList<>();
        for (String line : wrap(heading)) {
            lines.add(Component.literal(line));
        }
        lines.add(Component.literal(""));
        for (Component action : actions) {
            // 連結文案都短於九個 codepoint；保留完整 Component 的原版點擊行為。
            if (codepoints(action.getString()) > CODEPOINTS_PER_LINE) {
                throw new IllegalArgumentException("書頁連結或入口文案過長");
            }
            lines.add(action);
        }
        addPage(pages, lines);
    }

    private static void addPage(List<Filterable<Component>> pages, List<Component> content) {
        if (content.size() > CONTENT_LINES_PER_PAGE) {
            throw new IllegalArgumentException("書頁正文超過十行");
        }
        if (pages.size() >= MAX_PAGES) {
            throw new IllegalArgumentException("書籍頁數必須少於一百頁");
        }
        List<Component> lines = new ArrayList<>(content);
        lines.add(command("目錄", "/yigua menu"));
        MutableComponent page = Component.literal("");
        for (int index = 0; index < lines.size(); index++) {
            Component line = lines.get(index);
            if (line.getString().contains("\n") || codepoints(line.getString()) > CODEPOINTS_PER_LINE) {
                throw new IllegalArgumentException("書頁行長超過九個 codepoint");
            }
            if (index > 0) {
                page.append("\n");
            }
            page.append(line);
        }
        pages.add(Filterable.passThrough(page));
    }

    /** 只新增視覺換行，不修剪或改寫原文字元，也不切斷代理對。 */
    private static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        for (String existingLine : text.split("\n", -1)) {
            if (existingLine.isEmpty()) {
                lines.add("");
                continue;
            }
            int offset = 0;
            while (offset < existingLine.length()) {
                int remaining = existingLine.codePointCount(offset, existingLine.length());
                int end = existingLine.offsetByCodePoints(offset, Math.min(CODEPOINTS_PER_LINE, remaining));
                lines.add(existingLine.substring(offset, end));
                offset = end;
            }
        }
        return lines;
    }

    private static Component command(String label, String command) {
        return Component.literal(label).withStyle(style -> style.withColor(ChatFormatting.BLUE)
            .withUnderlined(true).withClickEvent(new ClickEvent.RunCommand(command)));
    }

    private static Component external(String label, String url) {
        return Component.literal(label).withStyle(style -> style.withColor(ChatFormatting.BLUE)
            .withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
    }

    private static WrittenBookContent book(String title, List<Filterable<Component>> pages) {
        return new WrittenBookContent(Filterable.passThrough(title), AUTHOR, 0, List.copyOf(pages), true);
    }

    private static String symbol(boolean yang) {
        return yang ? "━━━" : "━ ━";
    }

    private static long seconds(int ticks) {
        return (Math.max(0L, ticks) + 19L) / 20L;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static int codepoints(String text) {
        return text.codePointCount(0, text.length());
    }

    private static void requireNumber(int number) {
        CastingEngine.maskForNumber(number);
    }
}
