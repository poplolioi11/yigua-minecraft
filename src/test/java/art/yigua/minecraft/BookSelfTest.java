package art.yigua.minecraft;

import art.yigua.core.CastResult;
import art.yigua.core.CastingEngine;
import art.yigua.core.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.component.WrittenBookContent;

/** 使用實際原版書頁與實際古籍資源驗收排版、導覽、原文及練習標示。 */
public final class BookSelfTest {
    private static final Set<URI> EXTERNAL_LINKS = Set.of(
        URI.create("https://yigua.art/"),
        URI.create("https://github.com/poplolioi11/yigua-wuyingdian-lines")
    );
    private static int checks;
    private static int books;
    private static int maxPages;
    private static int longTextPages;

    private BookSelfTest() {}

    public static void main(String[] arguments) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Library.load();
        boolean focused = arguments.length == 1 && arguments[0].equals("--focused");
        if (arguments.length > 0 && !focused) throw new IllegalArgumentException("書頁自測只接受 --focused 選項");
        menuAndOptions();
        guideAndAtlas();
        readingSources();
        visibleResultBooks();
        movingAdditionBooks();
        if (!focused) {
            allReadings();
            allMovingResults();
            nonMovingResult();
            scaledResultBooks();
        }
        multiEffectResults();
        phasedResults();
        losslessLongText();
        System.out.println((focused ? "聚焦書頁驗收通過：" : "書頁驗收通過：") + checks + " 項檢查，" + books
            + " 本實際資料書籍，最多 " + maxPages + " 頁；長文與補充字元測試 " + longTextPages + " 頁。");
    }

    private static void menuAndOptions() {
        WrittenBookContent menu = BookBuilder.menu();
        Set<String> commands = inspect(menu, false);
        check(menu.pages().size() == 1, "常用目錄須直接放在一頁，不能要求玩家先翻完整規則");
        String home = firstPageText(menu);
        check(home.startsWith("一卦目錄"), "總覽書的短首頁須清楚標為目錄");
        for (Map.Entry<String, String> entry : Map.of(
                "傳統卜筮選項", "/yigua traditional", "多人與共享", "/yigua sharing",
                "起卦模式", "/yigua operation", "閱讀規則", "/yigua guide",
                "查六十四卦", "/yigua catalog", "個人圖鑑", "/yigua atlas",
                "最近起卦", "/yigua result", "太極工具", "/yigua taiji").entrySet()) {
            check(home.contains(entry.getKey()) && commands.contains(entry.getValue()),
                "目錄須提供可點擊的直接入口：" + entry.getKey());
        }
        WrittenBookContent traditional = BookBuilder.traditionalOptions();
        Set<String> traditionalCommands = inspect(traditional, false);
        check(traditional.pages().size() == 1, "傳統選項須只有一頁控制，長說明另放規則冊");
        Set<String> firstTraditionalCommands = pageCommands(traditional.pages().getFirst().raw());
        for (String command : List.of("/yigua-ritual yarrow on", "/yigua-ritual yarrow off",
                "/yigua-ritual coins on", "/yigua-ritual coins off", "/yigua-ritual-ui text",
                "/yigua-ritual-ui interactive")) {
            check(traditionalCommands.contains(command) && firstTraditionalCommands.contains(command),
                "傳統頁第一頁須直接開關及切換文字、拖曳介面：" + command);
        }
        WrittenBookContent operation = BookBuilder.operationOptions();
        inspect(operation, false);
        check(operation.pages().size() == 1, "起卦選項須只有一頁控制，長說明另放規則冊");
        Set<String> firstOperationCommands = pageCommands(operation.pages().getFirst().raw());
        for (String command : List.of("/yigua-ritual-mode traditional", "/yigua-ritual-mode auto",
                "/yigua-ritual-mode instant", "/yigua-mode button", "/yigua-mode direct",
                "/yigua-animation on", "/yigua-animation off")) {
            check(firstOperationCommands.contains(command), "起卦模式第一頁須直接切換玩法與入口：" + command);
        }

        for (String profile : Library.generalProfiles()) {
            WrittenBookContent chosen = BookBuilder.controlledEffect(profile, 12000);
            inspect(chosen, false);
            check(flatText(chosen).contains("並非隨機起卦") && flatText(chosen).contains(Library.profileName(profile)),
                "指定效果明示來源與實際效果");
        }
        for (String profile : List.of("divine", "thunder", "undying", "five_qi", "flowers", "birds", "rapport", "sea")) {
            inspect(BookBuilder.controlledEffect(profile, 0), false);
        }

        WrittenBookContent catalog = BookBuilder.catalog();
        Set<String> catalogCommands = inspect(catalog, false);
        check(catalog.pages().size() == 8, "查卦入口須使用獨立八頁目錄，不能先塞入七十五頁規則");
        for (int number = 1; number <= 64; number++) {
            check(catalogCommands.contains("/yigua read " + number), "獨立查卦目錄須保留六十四個閱讀入口");
        }
        for (boolean send : List.of(false, true)) {
            for (boolean receive : List.of(false, true)) {
                for (boolean enabled : List.of(false, true)) {
                    for (String group : List.of("", "ABCD2345")) {
                        int members = group.isEmpty() ? 0 : 16;
                        WrittenBookContent sharing = BookBuilder.sharingOptions(send, receive, group, members, enabled);
                        inspect(sharing, false);
                        check(sharing.pages().size() == 2, "共享選項只保留一頁控制與一頁加入方法，完整規則另讀");
                        String first = firstPageText(sharing);
                        check(first.contains(send ? "送出開（已選）" : "送出關（已選）")
                            && first.contains(receive ? "接收開（已選）" : "接收關（已選）"),
                            "共享頁須顯示送出與接收目前選擇");
                        check(first.contains(group.isEmpty() ? "尚未加入群組" : group), "共享首頁須保留完整群組代碼或未加入狀態");
                        check(first.contains(enabled ? "成員：" + members + "/16" : "伺服器停用共享"), "共享首頁須顯示人數或停用狀態");
                        Set<String> firstSharing = pageCommands(sharing.pages().getFirst().raw());
                        for (String action : List.of("/yigua sharing send on", "/yigua sharing send off",
                                "/yigua sharing receive on", "/yigua sharing receive off",
                                "/yigua group create", "/yigua group leave")) {
                            check(firstSharing.contains(action), "共享第一頁須提供直接設定與群組操作：" + action);
                        }
                        check(flatText(sharing).contains("/yigua group join CODE")
                            && firstSharing.stream().noneMatch(command -> command.startsWith("/yigua group join ")),
                            "加入群組須說明自行輸入代碼，不能由書頁自動代玩家加入");
                    }
                }
            }
        }
        inspect(BookBuilder.sharingOptions(false, false, null, 0, true), false);
        expectReject(() -> BookBuilder.sharingOptions(false, false, "ABCD23456", 1, true), IllegalArgumentException.class, "超長群組代碼須拒絕，不能截掉尾字");
        expectReject(() -> BookBuilder.sharingOptions(false, false, "ABCD\n345", 1, true), IllegalArgumentException.class, "群組代碼不得注入換行");
        expectReject(() -> BookBuilder.sharingOptions(false, false, "ABCD2345", 17, true), IllegalArgumentException.class, "群組人數不得超過十六人");
        expectReject(() -> BookBuilder.sharingOptions(false, false, "", -1, true), IllegalArgumentException.class, "群組人數不得為負值");
    }

    private static void guideAndAtlas() {
        WrittenBookContent guide = BookBuilder.guide();
        Set<String> commands = inspect(guide, true);
        String guideFirst = firstPageText(guide);
        check(guideFirst.startsWith("閱讀規則") && guideFirst.contains("用途：教學與規則"), "規則冊首頁須明示書名與用途");
        check(guideFirst.contains("查卦與設定回目錄") && guideFirst.contains("想讀規則再慢慢看"), "規則冊須說明常用功能在短目錄，全文留給自願閱讀");
        check(commands.contains("/yigua catalog"), "規則冊須能回到獨立六十四卦入口，完整查卦另有驗收");
        check(commands.contains("/yigua result"), "總覽需有結果入口");
        check(commands.contains("/yigua-achievements") && guideFirst.contains("查看原生成就"), "總覽首頁須有原版成就畫面的實際點擊入口");
        check(commands.contains("/yigua-mode button") && commands.contains("/yigua-mode direct"), "總覽設定須能點擊切換兩種本機操作模式");
        for (String command : List.of("/yigua-ritual yarrow on", "/yigua-ritual yarrow off",
                "/yigua-ritual coins on", "/yigua-ritual coins off")) {
            check(commands.contains(command), "傳統操作設定須具備可點擊的四種開關命令");
        }
        String guideText = flatText(guide);
        String achievements = sectionBody(guide, "原生成就");
        check(achievements.contains("卦象與效果分為兩個分頁")
            && achievements.contains("極少數項目需要親自發現"), "成就教學須說明兩分頁與少量探索項目");
        check(!achievements.contains("%") && !achievements.contains("獄髓")
            && !achievements.contains("死亡") && !achievements.contains("不死"), "教學不得揭露秘密事件機率、獎勵或觸發細節");
        String visibleEffects = sectionBody(guide, "可見效果總表");
        for (String summary : List.of(
                "行旅：移動速度增加10%", "行路受阻：移動速度降低10%",
                "專注：挖掘速度增加10%", "休整：挖掘速度降低10%",
                "健行：攻擊傷害+1", "收斂：攻擊傷害－1",
                "守護：護甲+1", "謹慎：護甲－1",
                "振作：攻擊恢復速度增加10%", "遲滯：攻擊恢復速度降低10%",
                "得宜：幸運+1（限含幸運判定的戰利品）", "失宜：幸運－1（限含幸運判定的戰利品）",
                "輕身：下落加速度降低10%（也影響跳躍）", "沉重：下落加速度增加10%（也影響跳躍）",
                "安落：摔落傷害降低10%", "失足：摔落傷害增加10%", "涉水受阻：水下氧氣額外消耗",
                "生機：生命上限+2（不補血）", "衰耗：生命上限－2（不補血）",
                "躍升：跳躍強度增加10%", "重足：跳躍強度降低10%",
                "穩立：擊退抗性+0.1", "易退：擊退抗性－0.1",
                "避灼：燃燒時間降低10%（非免火）", "餘燼：燃燒時間增加10%（非免火）",
                "延展：方塊互動距離+0.5格（非視野）", "近取：方塊互動距離－0.5格（非視野）",
                "深息：水下氧氣加成+1", "潛作：水下挖掘速度增加10%", "潛阻：水下挖掘速度降低10%",
                "近接：實體互動距離+0.5格（非視野）", "順方：移動西南+10%、東北－10%、其他0%")) {
            check(visibleEffects.contains(summary), "可見效果總表須完整保留每項一倍數值與適用限制：" + summary);
        }
        check(visibleEffects.contains("褐銅錢／木籌的一倍基準")
            && visibleEffects.contains("其他材質與環境會縮放效果"), "可見效果總表須明示一倍材質基準與縮放範圍");
        check(visibleEffects.contains("涉水受阻：水下氧氣額外消耗")
            && !visibleEffects.contains("氣泡") && !visibleEffects.contains("氧氣降低10%"), "水下效果只承諾額外耗氧，不能冒稱固定氣泡或百分比");
        check(visibleEffects.contains("幸運與下落加速度不是古籍本義"), "新增屬性須明示遊戲比喻的解釋邊界");
        check(visibleEffects.contains("以下32種一般效果") && visibleEffects.contains("不計入32種"), "一般效果種類不得混算中性");
        String direction = sectionBody(guide, "順方與移動");
        check(direction.contains("實際水平移動方向") && direction.contains("不使用鏡頭朝向")
            && direction.contains("其他方位不變") && direction.contains("靜止時不增減速"), "順方教學須明示移動、靜止與其他方位條件");
        check(direction.contains("剩餘階段倒數") && direction.contains("不把所有方位固化為吉凶"), "方向效果須保留時長與解釋界線");
        String scope = sectionBody(guide, "屬性適用界線");
        for (String boundary : List.of("生命上限不是補血", "燃燒時間不是火焰免疫", "不是視野距離", "擊退抗性使用加值", "不代表固定氣泡數")) {
            check(scope.contains(boundary), "屬性教學須明示適用限制：" + boundary);
        }
        String additions = sectionBody(guide, "動爻與效果");
        for (String rule : List.of("無動爻維持一組基礎效果", "一至二個動爻另加一組", "三至五個動爻最多另加兩組",
                "六爻皆變維持特殊卦勢的兩至三組效果", "六或九的爻值", "同效果類型不重複", "不是再次抽籤",
                "不延長時長與冷卻", "並非384爻都完成傳統解釋")) {
            check(additions.contains(rule), "動爻教學須明示實際追加規則與界線：" + rule);
        }
        String readingGuide = sectionBody(guide, "閱讀經文");
        check(readingGuide.contains("武英殿版爻辭") && readingGuide.contains("四庫本《伊川易傳》")
            && readingGuide.contains("兩種版本分開"), "讀經教學須分開六爻底本與轉譯卦辭、程傳底本");
        check(readingGuide.contains("初錄稿") && readingGuide.contains("未經逐字影像二校")
            && readingGuide.contains("恒僅有局部核對") && !readingGuide.contains("尚未收錄卦辭"), "伊川初稿狀態與局部校核範圍須如實顯示");
        check(guideText.contains("一枚銅錠可製作三枚銅錢"), "銅錢配方須為一枚銅錠製三枚銅錢");
        check(!guideText.contains("三枚銅錠"), "不得保留錯誤的三枚銅錠配方");
        String materials = sectionBody(guide, "合成與材料");
        check(materials.contains("三枚銅錢製作三錢筮具，不需要線。"), "三錢筮具教學須符合不需要線的簡化配方");
        check(materials.contains("二根竹子搭配一條線或一條藤蔓，可製作竹籌筮具。"), "竹籌教學須說明二竹搭配線或藤蔓的配方");
        check(materials.contains("所有筮具都可在背包2×2合成區製作。"), "教學須讓玩家知道背包即可製作各種筮具");
        check(materials.contains("二根木棒加一條線製作木籌；兩份長條植物加一條線製作草籌。"), "木籌與草籌須說明容易取得的合成材料");
        check(materials.contains("甘蔗、高草或大型蕨") && materials.contains("木籌與草籌沒有烤製版本。"), "草籌植物與木草籌無烤製版本須明確");
        check(!materials.contains("三枚銅錢與線") && !materials.contains("四根竹子與線製作竹籌筮具。"), "不得留下舊工具配方");
        check(materials.contains("兩份植物材料可製作一條線，竹子、甘蔗、藤蔓、蕨、草與樹葉可混用。"), "製線教學須說明兩份植物與混用材料");
        check(materials.contains("先用二根竹子製作一條線，再用二根竹子加線製作竹籌；總共四根竹子即可，不需要蜘蛛。"), "竹籌教學須提供四竹即可完成且不需蜘蛛的路徑");
        check(materials.contains("綁線或綁藤蔓，各有原色與烤製色，共四種外觀。"), "竹籌須說明四種綁材與烤製外觀");
        check(materials.contains("熔爐烤製既有竹籌會保留綁材，只改變外觀，不影響機率與效果。"), "烤製教學須保留綁材且不改起卦機率與效果");
        String multipliers = sectionBody(guide, "材質與效果倍率");
        check(multipliers.contains("基礎效果以褐銅錢或木籌為一倍。"), "倍率教學須說明褐銅錢與木籌的一倍基準");
        check(multipliers.contains("木錢0.5倍、鐵錢0.75倍、褐銅錢1倍、綠銅錢1.25倍、金錢1.5倍。"), "五種錢材質倍率須跨頁完整保留");
        check(multipliers.contains("草籌0.5倍、木籌1倍、竹籌1.5倍；竹籌在籌具中最強。"), "三種籌材質倍率與竹籌上限須完整保留");
        check(multipliers.contains("都是1.5倍") && multipliers.contains("材質不改變爻值機率"), "竹籌四種外觀倍率一致且材質不能被教成改變卦象機率");
        check(multipliers.contains("材質影響在基礎效果上下五成內。"), "材質倍率須符合正負百分之五十界線");
        check(multipliers.contains("負面效果也會一起放大") && multipliers.contains("中性卦不增加效果"), "倍率教學須包含負面一起縮放與中性維持無效果");
        check(multipliers.contains("六爻皆變等特殊卦勢仍可有額外效果"), "中性基礎規則不能被說成排除特殊卦勢的額外效果");
        check(multipliers.contains("效果總時長仍為12000遊戲刻") && multipliers.contains("換手持工具不會改變過往結果"), "倍率不改總時長與歷史結果");
        check(multipliers.contains("舊版三錢與竹籌紀錄維持1倍") && multipliers.contains("「舊版三錢」或「舊版竹籌」"), "舊三錢及竹籌紀錄都須保持歷史一倍來源");
        String environment = sectionBody(guide, "身心與環境");
        for (String factor : List.of("身心", "生態域", "天氣", "附近生物", "世界時間", "看向方位")) {
            check(environment.contains(factor), "環境教學須列出六種探索因素");
        }
        check(environment.contains("每項影響不超過3%") && environment.contains("總影響不超過10%"), "環境教學須明示每項與總修正上限");
        check(!environment.contains("2%") && !environment.contains("4%") && !environment.contains("0.96")
            && environment.contains("權重留給玩家自行探索"), "環境教學不得公開各項權重與實際倍率");
        check(environment.contains("環境不改變爻值機率或效果時長"), "環境修正不得冒稱改變卦象機率或時長");
        String start = sectionBody(guide, "開始起卦");
        check(start.contains("預設採按鈕入口與自動方式：手持筮具，按右鍵開啟角落面板。")
            && start.contains("面板提供開始、練習與關閉，也能切換玩法、操作頁面與動畫。"), "工具教學須說明右鍵面板及可用動作");
        check(start.contains("面板可切換傳統、自動、馬上三種起卦方式，再選正式或練習。")
            && start.contains("自動方式回到遊戲，在眼前播放約三秒的六爻動畫。"), "正式與練習須共用三種方式，自動才承諾三秒動畫");
        check(start.contains("傳統方式先完成逐枚投錢或十八變分堆")
            && start.contains("馬上方式略過動畫") && start.contains("正式起卦仍有相同效果與冷卻"), "傳統與馬上須明示操作差別及正式冷卻一致");
        check(start.contains("正式起卦會產生效果並記入圖鑑；練習不生效，也不記入圖鑑。"), "面板教學須區分正式與練習的效果及紀錄");
        check(start.contains("動畫完成後，聊天列顯示本卦、變卦、動爻與效果。"), "動畫後的聊天結果須明列卦名、變卦、動爻與效果");
        check(start.contains("結果書不會自動開啟") && start.contains("/yigua result")
            && start.contains("目錄的「最近起卦」"), "正式結果書須說明手動命令與目錄入口");
        check(!start.contains("潛行鍵") && !start.contains("完成後開結果書。"), "不得保留潛行練習或動畫後自動開書的舊教學");
        check(start.contains("右鍵工具依選定方式開始正式起卦，無須再按面板按鈕")
            && start.contains("直接入口不等於馬上方式"), "直接入口須說明右鍵開始且不冒稱跳過動畫");
        String settings = sectionBody(guide, "起卦操作設定");
        String settingsDetails = sectionBody(guide, "起卦方式說明");
        check(settingsDetails.contains("播放約三秒動畫") && settingsDetails.contains("略過動畫直接顯示聊天結果")
            && settingsDetails.contains("正式效果與冷卻規則相同"), "自動與馬上須明示相同規則及動畫差別");
        check(settingsDetails.contains("直接入口仍可選傳統或自動，不等於馬上起卦"), "直接右鍵入口不得被教成略過動畫");
        check(settings.contains("按鈕入口") && settings.contains("直接入口")
            && settingsDetails.contains("僅儲存在本機") && settingsDetails.contains("每位玩家自行選"), "設定頁須說明模式選擇與本機儲存範圍");
        check(settings.contains("傳統逐步卜筮") && settings.contains("自動動畫起卦")
            && settings.contains("馬上顯示結果") && settingsDetails.contains("聊天顯示結果，不會自動開書")
            && settings.contains("開啟眼前動畫") && settings.contains("關閉眼前動畫"), "三種起卦方式須能直接選、共用聊天結果並提供動畫開關");
        String traditionalSettings = sectionBody(guide, "傳統操作設定");
        check(traditionalSettings.contains("預設自動起卦") && traditionalSettings.contains("各玩家本機保存。")
            && sectionBody(guide, "傳統操作介面").contains("手動時開角落區"), "傳統設定須說明預設、個人儲存與互動操作區");
        String interfaces = sectionBody(guide, "傳統操作介面");
        check(interfaces.contains("輸入左堆根數") && interfaces.contains("拖曳竹籌分堆")
            && interfaces.contains("逐枚拖曳錢幣投擲") && interfaces.contains("相同三變、十八變及逐枚投錢規則"),
            "兩種傳統介面須保持相同計算規則並說明不同操作");
        check(interfaces.contains("預設互動拖曳") && interfaces.contains("關閉則改回自動")
            && interfaces.contains("介面切換不重置目前步驟"), "傳統選項須說明預設、舊快捷命令與切換不重置進度");
        String sharingRules = sectionBody(guide, "群組共享規則");
        for (String boundary : List.of("預設個人模式", "送出與接收共享都關閉", "最多十六人",
                "同一維度", "三十二格", "雙方同意", "一般正面效果", "負面、稀有事件與順方效果不共享",
                "本人原有負面效果也不會被移除", "同類正面效果取最高強度", "不重複相加",
                "不會解鎖個人圖鑑或成就", "不新增或刷新起卦冷卻", "收到的共享效果會失效",
                "沿用送出者目前卦象階段", "不延長原有時長", "受保護的效果與屬性同樣不共享")) {
            check(sharingRules.contains(boundary), "共享規則須明示範圍與界線：" + boundary);
        }
        String manual = sectionBody(guide, "傳統手動蓍草");
        check(manual.contains("範圍為1至剩餘根數減2") && manual.contains("兩邊都不空"), "手動蓍草分堆範圍須維持兩堆非空");
        check(manual.contains("右堆取一根") && manual.contains("整除四時留四根")
            && manual.contains("兩堆餘數與掛一的一根"), "手動蓍草掛一、揲四與歸奇須教學完整");
        check(manual.contains("第一變去五或九根；第二、三變各去四或八根。")
            && manual.contains("二十四、二十八、三十二或三十六根，除四得六、七、八或九"), "手動蓍草三變餘數與爻值須正確");
        check(manual.contains("共十八變") && manual.contains("新一爻重取四十九根")
            && manual.contains("不能沿用上一爻餘數"), "六爻十八變每爻必須重取四十九根");
        check(manual.contains("尚未完成可取消，不記正式起卦，也不套用效果。")
            && manual.contains("約三秒揭曉動畫，結果顯示於聊天"), "手動蓍草取消與完成流程須明確");
        String coins = sectionBody(guide, "三錢逐枚投擲");
        check(coins.contains("每次只投一枚錢") && coins.contains("兩面分別記二或三")
            && coins.contains("每三次的面額相加") && coins.contains("共投十八次"), "逐枚投錢須教學三枚加總、六爻十八次");
        check(coins.contains("由下往上的六爻紀錄") && coins.contains("未完成可取消，不記正式起卦或效果"), "逐枚投錢須保留下往上紀錄及取消說明");
        String manualProbability = sectionBody(guide, "手動與機率");
        check(manualProbability.contains("玩家選擇會改變卦象機率") && manualProbability.contains("只作標準隨機法參考")
            && manualProbability.contains("不是手動分堆的固定機率"), "手動分堆與稀有卦勢機率不得混為均勻隨機");
        String cooldown = sectionBody(guide, "效果與冷卻");
        check(cooldown.contains("按「開始起卦」只顯示冷卻提示，不會重抽或刷新效果。")
            && cooldown.contains("等待期間仍可選「練習起卦」。"), "冷卻教學須說明提示而非重抽，且仍能練習");
        check(cooldown.contains("前五分鐘使用本卦效果，後五分鐘改用變卦效果")
            && cooldown.contains("沒有變卦則同組效果持續十分鐘") && cooldown.contains("兩階段共用同一次冷卻"), "變卦教學須明確區分兩個五分鐘與靜卦十分鐘");
        check(start.contains("像素動畫") && start.contains("六個節拍、粒子與音效") && start.contains("開關眼前動畫"), "動畫教學須說明六節拍、像素圖示及開關");
        check(start.contains("竹籌動畫是蓍草法的表意演示，不是完整十八變操作。"), "竹籌演示不得冒稱完整十八變操作");
        check(sectionBody(guide, "四營與三變").contains("分二、掛一、揲四、歸奇")
            && sectionBody(guide, "十八變成卦").contains("一爻三變，六爻合計十八變。"), "動畫教學之外仍須保留完整蓍草步驟教學");
        check(guideText.contains("古籍正文公有領域。"), "古籍原文權利須明確");
        check(guideText.contains("原創說明採CC BY 4.0"), "CC BY 須對應原創說明");
        check(!guideText.contains("爻辭資料採CC BY"), "不得將古籍原文誤標為 CC BY");
        Component lastPage = guide.pages().getLast().raw();
        String lastPageText = lastPage.getString().replace("\n", "");
        check(lastPageText.contains("作者：poplolioi11"), "總覽末頁須完整署名作者");
        check(lastPageText.contains("本模組開源。"), "總覽末頁須有簡短開源說明");
        check(lastPageText.contains("程式碼採MIT。"), "總覽末頁須明示程式碼採 MIT 授權");
        List<ClickEvent> lastPageClicks = new ArrayList<>();
        collectClicks(lastPage, lastPageClicks);
        Set<URI> lastPageLinks = new HashSet<>();
        for (ClickEvent click : lastPageClicks) {
            if (click instanceof ClickEvent.OpenUrl link) {
                lastPageLinks.add(link.uri());
            }
        }
        check(lastPageLinks.equals(EXTERNAL_LINKS), "總覽末頁須保留網站與公開資料的兩個實際連結");

        WrittenBookContent empty = BookBuilder.atlas(Set.of(), Map.of());
        Set<String> emptyCommands = inspect(empty, false);
        String emptyText = flatText(empty);
        check(emptyText.contains("已發現 0/64"), "空圖鑑進度");
        check(firstPageText(empty).startsWith("個人卦象圖鑑")
            && firstPageText(empty).contains("用途：收集卦象") && firstPageText(empty).contains("已發現 0/64"), "空圖鑑首頁須有獨立名稱、用途與進度");
        check(!empty.pages().getFirst().raw().getString().equals(guide.pages().getFirst().raw().getString()), "總覽與圖鑑首頁不能讓玩家以為未切換");
        for (int number = 1; number <= 64; number++) {
            check(emptyCommands.contains("/yigua read " + number), "未解鎖的卦也可閱讀");
            check(emptyText.contains(label(number, "未")), "空圖鑑六十四卦皆未解鎖");
        }

        Set<Integer> discovered = new HashSet<>();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int number = 1; number <= 64; number++) {
            discovered.add(number);
            counts.put(number, number == 64 ? Integer.MAX_VALUE : number);
        }
        WrittenBookContent full = BookBuilder.atlas(discovered, counts);
        Set<String> fullCommands = inspect(full, false);
        String fullText = flatText(full);
        check(fullText.contains("已發現 64/64"), "完整圖鑑進度");
        check(firstPageText(full).contains("已發現 64/64") && firstPageText(full).contains("閱讀不解鎖或計次"), "完整圖鑑首頁須保留進度與查閱規則");
        check(sectionBody(full, "卦象紀錄次數").contains("紀錄：2147483647次"), "大次數也不能被截斷");
        for (int number = 1; number <= 64; number++) {
            check(fullCommands.contains("/yigua read " + number), "完整圖鑑的六十四卦入口");
            check(fullText.contains(label(number, "已")), "完整圖鑑六十四卦皆已解鎖");
        }
    }

    /** 聚焦模式也核對逐卦引用，避免新增依據後只驗總覽。 */
    private static void readingSources() {
        for (int number = 1; number <= 64; number++) {
            List<Integer> stableValues = new ArrayList<>();
            int mask = CastingEngine.maskForNumber(number);
            for (int line = 0; line < 6; line++) {
                stableValues.add((mask & (1 << line)) == 0 ? 8 : 7);
            }
            Library.Rule base = Library.rule(CastingEngine.fromLines(Method.COINS, stableValues));
            WrittenBookContent reading = BookBuilder.reading(number);
            inspect(reading, false);
            String source = sectionBody(reading, "遊戲轉譯依據");
            check(source.equals(base.origText().replace("\n", "")), "轉譯依據須逐字保留該卦基礎規則引用：第" + number + "卦");
            if (!base.origText().isEmpty()) {
                check(sectionPage(reading, "遊戲轉譯依據") == 1, "遊戲轉譯依據須緊接首頁效果後顯示");
            } else {
                check(sectionPage(reading, "遊戲轉譯依據") == -1, "無引用時不得產生空依據頁");
            }
            requireOriginals(flatText(reading), Library.hex(number));
        }
    }

    private static void visibleResultBooks() {
        CastResult cast = CastingEngine.fromLines(Method.COINS, List.of(7, 8, 7, 8, 7, 8));
        for (String profile : Library.generalProfiles()) {
            Library.Rule rule = new Library.Rule("visible_" + profile, cast.primaryNumber(), 0, profile,
                Library.baseAmount(profile), 12000, 0, "", "");
            for (int remaining : List.of(12000, -1)) {
                WrittenBookContent book = BookBuilder.result(cast, rule, remaining);
                inspect(book, false);
                requireResultHome(book, cast, rule, remaining == -1);
                check(firstPageText(book).contains(Library.effectSummary(rule)), "所有可見效果的完整適用限制須在結果首頁可讀");
            }
        }
    }

    /** 使用實際動爻追加器，驗證中性主要規則與追加也能完整列出。 */
    private static void movingAdditionBooks() {
        for (int number : List.of(22, 27, 30, 35, 39, 59, 64)) {
            int mask = CastingEngine.maskForNumber(number);
            for (int movingCount = 1; movingCount <= 5; movingCount++) {
                List<Integer> values = new ArrayList<>();
                for (int line = 0; line < 6; line++) {
                    boolean yang = (mask & (1 << line)) != 0;
                    values.add(line < movingCount ? yang ? 9 : 6 : yang ? 7 : 8);
                }
                CastResult cast = CastingEngine.fromLines(Method.COINS, values);
                List<Library.Rule> rules = MovingFortune.resolve(cast, Library.rule(cast), CastingMaterial.GREEN_COPPER);
                check(rules.size() > 1, "實際動爻須有可展示的追加效果");
                for (int remaining : List.of(12000, -1)) {
                    WrittenBookContent book = BookBuilder.result(cast, rules, remaining, "", "");
                    inspect(book, false);
                    requireResultHome(book, cast, rules.getFirst(), remaining == -1);
                    String effects = sectionBody(book, remaining == -1 ? "正式效果示例" : "本次遊戲效果");
                    for (int index = 0; index < rules.size(); index++) {
                        Library.Rule rule = rules.get(index);
                        check(effects.contains(Library.effectSummary(rule)), "實際動爻追加的每項數值與限制須完整可讀");
                        check(sectionBody(book, "效果" + (index + 1) + "依據").equals(rule.origText().replace("\n", "")),
                            "實際動爻追加書須完整保留主要引用與每條原爻");
                    }
                    if (rules.getFirst().profile().equals("neutral")) {
                        check(firstPageText(book).contains("平和：基礎效果為中性"), "中性主要規則不能冒稱整份結果沒有追加效果");
                    }
                }
            }
        }
    }

    private static void allReadings() {
        for (int number = 1; number <= 64; number++) {
            WrittenBookContent reading = BookBuilder.reading(number);
            inspect(reading, false);
            String text = flatText(reading);
            Library.HexText hex = Library.hex(number);
            check(text.contains("第" + number + "卦 " + hex.name()), "正文卦序與卦名");
            String first = firstPageText(reading);
            List<Integer> stableValues = new ArrayList<>();
            int mask = CastingEngine.maskForNumber(number);
            for (int line = 0; line < 6; line++) {
                stableValues.add((mask & (1 << line)) == 0 ? 8 : 7);
            }
            CastResult stable = CastingEngine.fromLines(Method.YARROW, stableValues);
            Library.Rule base = Library.rule(stable);
            check(stable.primaryNumber() == number && stable.changedNumber() == 0
                && base.number() == number && base.line() == 0, "查閱效果須取對應卦的無動爻基礎規則");
            check(first.startsWith("第" + number + "卦 " + hex.name()) && first.contains("卦象閱讀：查效果"), "卦象閱讀首頁須明示卦名與查閱用途");
            check(first.contains("基礎遊戲效果") && first.contains(Library.effectSummary(base)), "每卦的基礎效果與數值須完整顯示在首頁");
            check(first.contains("動爻可能改變效果"), "閱讀首頁須說明動爻可能改變實際效果");
            check(first.contains("褐銅錢／木籌為1倍"), "卦象閱讀首頁須明示效果為褐銅錢或木籌的一倍基準");
            check(first.contains("遊戲改編非經文本意") && first.contains("查閱不會生效。"), "閱讀不能將遊戲效果冒稱古籍本意或已套用效果");
            String[] diagram = reading.pages().stream().map(page -> page.raw().getString().split("\n", -1))
                .filter(lines -> lines.length > 1 && lines[1].equals("卦象由上往下"))
                .findFirst().orElseThrow(() -> new AssertionError("閱讀須保留六爻圖頁"));
            for (int line = 5; line >= 0; line--) {
                check(diagram[8 - line].equals(lineName(line) + " "
                    + ((mask & (1 << line)) == 0 ? "━ ━" : "━━━")), "查閱六爻圖仍完整依上爻至初爻呈現");
            }
            requireOriginals(text, hex);
            if (!hex.specialText().isEmpty()) {
                check(text.contains(hex.specialText()), "正文需保留用九或用六全文");
            }
            requireSourceNote(reading, hex);
        }
    }

    private static void allMovingResults() {
        for (int number = 1; number <= 64; number++) {
            int mask = CastingEngine.maskForNumber(number);
            List<Integer> values = new ArrayList<>();
            for (int line = 0; line < 6; line++) {
                values.add((mask & (1 << line)) == 0 ? 6 : 9);
            }
            CastResult cast = CastingEngine.fromLines(Method.YARROW, values);
            Library.Rule rule = Library.rule(cast);
            WrittenBookContent normal = BookBuilder.result(cast, rule, 12000);
            Set<String> commands = inspect(normal, false);
            requireResultHome(normal, cast, rule, false);
            String normalText = flatText(normal);
            requireOriginals(normalText, Library.hex(number));
            check(normalText.contains("本卦 " + number + " " + CastingEngine.nameForNumber(number)), "結果須告知本卦");
            check(normalText.contains("變卦 " + cast.changedNumber() + " "
                + CastingEngine.nameForNumber(cast.changedNumber())), "全部動爻須告知變卦");
            check(commands.contains("/yigua read " + cast.changedNumber()), "結果須有變卦閱讀入口");
            check(sectionBody(normal, "本次遊戲效果").contains("冷卻剩餘：標準20TPS約600秒。"), "在線遊戲刻應按標準 20 TPS 表述時間");
            requireSourceNote(normal, Library.hex(number));
            if (!cast.special().isEmpty()) {
                check(normalText.contains(Library.hex(number).specialText()), "全九或全六需保留特殊用辭");
            }

            WrittenBookContent practice = BookBuilder.result(cast, rule, -1);
            inspect(practice, false);
            requireResultHome(practice, cast, rule, true);
            String practiceText = flatText(practice);
            requireOriginals(practiceText, Library.hex(number));
            check(practiceText.contains("練習起卦結果"), "練習首頁須明示練習");
            check(practiceText.contains("練習，不產生效果、不記入圖鑑。"), "練習不產生效果或圖鑑紀錄");
            check(practiceText.contains("正式起卦的效果示例："), "練習規則只能稱效果示例");
            check(!practiceText.contains("冷卻已結束"), "練習不應宣稱正式起卦冷卻已結束");
            check(!practiceText.contains("冷卻剩餘："), "練習不應假裝新增冷卻");
            check(!practiceText.contains("本次遊戲效果"), "練習效果標題須與正式起卦區別");
        }
    }

    private static void nonMovingResult() {
        CastResult cast = CastingEngine.fromLines(Method.COINS, List.of(7, 8, 7, 8, 7, 8));
        WrittenBookContent result = BookBuilder.result(cast, Library.rule(cast), 0);
        Set<String> commands = inspect(result, false);
        requireResultHome(result, cast, Library.rule(cast), false);
        String text = flatText(result);
        check(text.contains("變卦：無變卦"), "靜卦須明示無變卦");
        check(commands.stream().noneMatch(command -> command.startsWith("/yigua read ")), "靜卦不得虛構變卦入口");
        check(sectionBody(result, "本次遊戲效果").contains("冷卻已結束，可再次起卦。"), "冷卻結束文字");
        requireOriginals(text, Library.hex(cast.primaryNumber()));

        Library.Rule disabled = new Library.Rule("disabled", cast.primaryNumber(), 0, "neutral", 0,
            0, 0, "伺服器已關閉遊戲效果。", "");
        WrittenBookContent noEffects = BookBuilder.result(cast, disabled, 12000);
        inspect(noEffects, false);
        requireResultHome(noEffects, cast, disabled, false);
        String disabledText = flatText(noEffects);
        check(disabledText.contains("基礎效果為中性"), "停用效果使用中性展示");
        check(disabledText.contains("伺服器已關閉遊戲效果。"), "停用設定應向玩家說明");
        check(disabledText.contains("本次沒有持續效果。"), "停用時不能宣稱持續增減益");
    }

    private static void losslessLongText() throws Exception {
        java.lang.reflect.Method section = BookBuilder.class.getDeclaredMethod("addSection", List.class, String.class, String.class);
        section.setAccessible(true);
        List<Filterable<Component>> pages = new ArrayList<>();
        String original = " 甲𠀀🌿乙，古籍原文。 ".repeat(120);
        section.invoke(null, pages, "原文測試", original);
        longTextPages = pages.size();
        check(longTextPages > 1, "長文測試須實際產生接續頁");
        StringBuilder recovered = new StringBuilder();
        for (Filterable<Component> page : pages) {
            String[] lines = page.raw().getString().split("\n", -1);
            check(lines.length <= 11, "接續頁仍須遵守十行正文加一行目錄");
            check(lines[1].isEmpty(), "接續頁標題後空白行");
            for (int index = 2; index < lines.length - 1; index++) {
                check(codepoints(lines[index]) <= 9, "補充平面字元按 codepoint 計算");
                check(noBrokenSurrogates(lines[index]), "不能切斷補充漢字或符號的代理對");
                recovered.append(lines[index]);
            }
        }
        check(recovered.toString().equals(original), "跨頁還原長文須逐字相同，包含空白與補充字元");

        try {
            section.invoke(null, new ArrayList<Filterable<Component>>(), "超長測試", "古籍".repeat(5000));
            throw new AssertionError("超過九十九頁應拒絕，不能默默截掉原文");
        } catch (InvocationTargetException failure) {
            check(failure.getCause() instanceof IllegalArgumentException, "超長書籍須明確拒絕");
        }
    }

    private static void scaledResultBooks() {
        List<String> profiles = new ArrayList<>(Library.generalProfiles());
        profiles.add("neutral");
        for (CastingMaterial material : CastingMaterial.values()) {
            CastResult cast = CastingEngine.fromLines(material.compatibleWith(Method.YARROW) ? Method.YARROW : Method.COINS,
                List.of(7, 8, 7, 8, 7, 8));
            for (String profile : profiles) {
                Library.Rule base = new Library.Rule("material_display", cast.primaryNumber(), 0, profile, Library.baseAmount(profile),
                    12000, 0, "材質倍率顯示驗收。", "");
                Library.Rule scaled = material.scaleRule(base);
                for (boolean practice : List.of(false, true)) {
                    WrittenBookContent book = BookBuilder.result(cast, scaled, practice ? -1 : 12000);
                    inspect(book, false);
                    requireResultHome(book, cast, scaled, practice);
                    requireOriginals(flatText(book), Library.hex(cast.primaryNumber()));
                }
            }
        }
    }

    private static void multiEffectResults() {
        CastResult cast = CastingEngine.fromLines(Method.COINS, List.of(9, 9, 9, 9, 9, 9));
        List<Library.Rule> rules = List.of(
            displayRule("multi_travel", 1, "travel", 0.125, 12000, "行旅補充 𠀀 🌿。", "原文甲：空白 與標點。"),
            displayRule("multi_frail", 1, "frail", -1.25, 12000, "護甲減益也套用倍率。", "原文乙：完整保留。"),
            displayRule("multi_neutral", 1, "neutral", 0, 0, "中性不新增持續效果。", "原文丙：末尾字。")
        );
        String context = ("材料：綠銅錢，倍率1.25。\n環境微幅修正，權重自行探索。\n 𠀀🌿空白保留。 ").repeat(18);
        for (int remaining : List.of(12000, -1)) {
            boolean practice = remaining == -1;
            WrittenBookContent book = BookBuilder.result(cast, rules, remaining, "特殊卦勢", context);
            inspect(book, false);
            requireResultHome(book, cast, rules.getFirst(), practice);
            check(firstPageText(book).contains("卦勢：特殊卦勢"), "稀有卦勢名稱須在結果首頁呈現");
            String effects = sectionBody(book, practice ? "正式效果示例" : "本次遊戲效果");
            for (int index = 0; index < rules.size(); index++) {
                Library.Rule rule = rules.get(index);
                check(effects.contains("效果" + (index + 1) + "：") && effects.contains(Library.effectSummary(rule)), "多效果列表須完整顯示每條規則的數值");
                check(sectionBody(book, "效果" + (index + 1) + "設計說明").equals(rule.rationale()), "多效果設計說明須跨頁逐字保留");
                check(sectionBody(book, "效果" + (index + 1) + "依據").equals(rule.origText()), "多效果原文依據須跨頁逐字保留");
            }
            check(sectionBody(book, "材料與環境").equals(context.replace("\n", "")), "長材料環境說明與補充字元須逐字保留");
            requireOriginals(flatText(book), Library.hex(cast.primaryNumber()));
            check(flatText(book).contains(Library.hex(1).specialText()), "多效果結果仍須保留全九用辭");
            if (practice) {
                check(!effects.contains("冷卻剩餘") && effects.contains("練習，不產生效果、不記入圖鑑"), "多效果練習不能假裝效果或正式冷卻已生效");
            }
        }
        expectReject(() -> BookBuilder.result(cast, List.of(), 12000, "", ""), IllegalArgumentException.class, "空效果列表須拒絕");
        expectReject(() -> BookBuilder.result(cast, (List<Library.Rule>) null, 12000, "", ""), NullPointerException.class, "空效果列表參照須拒絕");
        expectReject(() -> BookBuilder.result(cast, java.util.Arrays.asList(rules.getFirst(), null), 12000, "", ""), NullPointerException.class, "空效果規則須拒絕");
        expectReject(() -> BookBuilder.result(cast, rules, 12000, null, ""), NullPointerException.class, "空卦勢名稱參照須拒絕");
        expectReject(() -> BookBuilder.result(cast, rules, 12000, "", null), NullPointerException.class, "空材料環境參照須拒絕");
    }

    private static void phasedResults() {
        CastResult cast = CastingEngine.fromLines(Method.YARROW, List.of(9, 9, 9, 9, 9, 9));
        List<Library.Rule> first = List.of(
            displayRule("first_travel", 1, "travel", 0.15, 6000, "本卦行旅設計。", "前半原文： 一字不漏。"),
            displayRule("first_guard", 1, "guard", 1.5, 6000, "本卦護甲設計。", "前半原文二：末字。")
        );
        List<Library.Rule> second = List.of(
            displayRule("second_caution", 2, "caution", -0.15, 6000, "變卦行路設計。", "後半原文： 空白保留。"),
            displayRule("second_restraint", 2, "restraint", -1.5, 6000, "變卦攻擊設計。", "後半原文二：𠀀🌿。")
        );
        String context = "材料：竹籌，倍率1.5。\n環境微幅修正，權重自行探索。";
        for (int remaining : List.of(12000, 6001, 6000, 1, 0, -1)) {
            boolean practice = remaining == -1;
            Library.Rule active = practice || remaining > 6000 ? first.getFirst() : second.getFirst();
            WrittenBookContent book = BookBuilder.result(cast, first, second, remaining, "大吉", context);
            inspect(book, false);
            requireResultHome(book, cast, active, practice);
            String home = firstPageText(book);
            check(home.contains("卦勢：大吉"), "分階段結果首頁須保留卦勢名稱");
            check(home.contains(practice ? "練習：兩階段示例" : remaining <= 0 ? "目前：兩階段已結束"
                : remaining > 6000 ? "目前：前半本卦" : "目前：後半變卦"), "剩餘刻數六千的前後邊界須正確標示目前階段");
            String firstText = sectionBody(book, "前五分鐘：本卦");
            String secondText = sectionBody(book, "後五分鐘：變卦");
            for (Library.Rule rule : first) check(firstText.contains(Library.effectSummary(rule)), "前半本卦全部效果須完整呈現");
            for (Library.Rule rule : second) check(secondText.contains(Library.effectSummary(rule)), "後半變卦全部效果須完整呈現");
            check(firstText.contains("標準20TPS約300秒") && secondText.contains("標準20TPS約300秒"), "兩階段各六千在線遊戲刻須表述為標準五分鐘");
            check(sectionBody(book, practice ? "正式效果示例" : "本次遊戲效果").contains("兩階段共用十分鐘冷卻。"), "階段切換須與整次冷卻區分");
            for (int index = 0; index < first.size(); index++) {
                check(sectionBody(book, "本卦效果" + (index + 1) + "設計說明").equals(first.get(index).rationale()), "本卦階段設計說明須逐字保留");
                check(sectionBody(book, "本卦效果" + (index + 1) + "依據").equals(first.get(index).origText()), "本卦階段依據須逐字保留");
            }
            for (int index = 0; index < second.size(); index++) {
                check(sectionBody(book, "變卦效果" + (index + 1) + "設計說明").equals(second.get(index).rationale()), "變卦階段設計說明須逐字保留");
                check(sectionBody(book, "變卦效果" + (index + 1) + "依據").equals(second.get(index).origText()), "變卦階段依據須逐字保留");
            }
            check(sectionBody(book, "材料與環境").equals(context.replace("\n", "")), "階段結果不得遺漏材料與探索說明");
            requireOriginals(flatText(book), Library.hex(1));
            check(flatText(book).contains(Library.hex(1).specialText()), "分階段結果須保留全九用辭");
        }
        CastResult stable = CastingEngine.fromLines(Method.COINS, List.of(7, 8, 7, 8, 7, 8));
        Library.Rule base = Library.rule(stable);
        WrittenBookContent old = BookBuilder.result(stable, base, 12000);
        WrittenBookContent onePhase = BookBuilder.result(stable, List.of(base), List.of(), 12000, "", "");
        inspect(onePhase, false);
        requireResultHome(onePhase, stable, base, false);
        check(flatText(old).equals(flatText(onePhase)), "原三參數與無第二階段介面須保持相同書文");
        check(sectionBody(onePhase, "本次遊戲效果").contains("標準20TPS約600秒"), "沒有變卦的同組效果須持續標準十分鐘");
        check(sectionBody(onePhase, "前五分鐘：本卦").isEmpty() && sectionBody(onePhase, "後五分鐘：變卦").isEmpty(), "靜卦結果不能虛構兩階段");
        expectReject(() -> BookBuilder.result(stable, first, second, 12000, "", ""), IllegalArgumentException.class, "靜卦不能帶入變卦階段");
        expectReject(() -> BookBuilder.result(cast, first, null, 12000, "", ""), NullPointerException.class, "空變卦效果參照須拒絕");
    }

    private static Library.Rule displayRule(String id, int number, String profile, double amount,
            int duration, String rationale, String original) {
        return new Library.Rule(id, number, 0, profile, amount, duration, 0, rationale, original);
    }

    private static void expectReject(Runnable action, Class<? extends Throwable> expected, String message) {
        try {
            action.run();
            check(false, message);
        } catch (RuntimeException failure) {
            check(expected.isInstance(failure), message);
        }
    }

    private static Set<String> inspect(WrittenBookContent book, boolean guide) {
        books++;
        maxPages = Math.max(maxPages, book.pages().size());
        check(!book.pages().isEmpty() && book.pages().size() < 100, "書籍需有內容且少於一百頁");
        check(book.author().equals("poplolioi11"), "所有總覽、圖鑑、正文與結果書均須署名 poplolioi11");
        Set<String> allCommands = new HashSet<>();
        Set<URI> external = new HashSet<>();
        int externalCount = 0;
        for (Filterable<Component> filtered : book.pages()) {
            Component page = filtered.raw();
            String text = page.getString();
            String[] lines = text.split("\n", -1);
            check(lines.length <= 11, "每頁最多十一行，十行正文加一行目錄");
            check(lines[lines.length - 1].equals("目錄"), "每頁最後一行只有目錄入口");
            check(!text.contains("返回總覽") && !text.contains("查看圖鑑"), "書頁不得殘留舊的雙導航文案");
            for (String line : lines) {
                check(codepoints(line) <= 9, "每行最多九個 Unicode codepoint");
                check(noBrokenSurrogates(line), "書頁不得切斷代理對");
            }
            check(!text.contains("http://") && !text.contains("https://"), "書頁不得堆放逐爻影像 URL");
            Set<String> pageCommands = new HashSet<>();
            List<ClickEvent> clicks = new ArrayList<>();
            collectClicks(page, clicks);
            for (ClickEvent click : clicks) {
                if (click instanceof ClickEvent.RunCommand run) {
                    String command = run.command();
                    check(validCommand(command), "書籍點擊只能使用已知的一卦命令");
                    allCommands.add(command);
                    pageCommands.add(command);
                } else if (click instanceof ClickEvent.OpenUrl link) {
                    check(EXTERNAL_LINKS.contains(link.uri()), "外部連結只允許兩個固定公開來源");
                    external.add(link.uri());
                    externalCount++;
                } else {
                    throw new AssertionError("書籍不得出現其他點擊行為：" + click);
                }
            }
            long menuClicks = clicks.stream().filter(click -> click instanceof ClickEvent.RunCommand run
                && run.command().equals("/yigua menu")).count();
            check(pageCommands.contains("/yigua menu") && menuClicks == 1, "每頁須且只能有一個實際可點擊的目錄導航");
        }
        if (guide) {
            check(external.equals(EXTERNAL_LINKS) && externalCount == 2, "總覽需各有一次網站和公開資料連結");
        } else {
            check(externalCount == 0, "正文、圖鑑與結果使用總覽連結入口");
        }
        return allCommands;
    }

    private static void requireOriginals(String text, Library.HexText hex) {
        check(hex.lines().size() == 6, "每卦應有六條來源原文");
        for (String original : hex.lines()) {
            check(text.contains(original), "書頁不能遺漏或改寫任何爻辭字元：第" + hex.number() + "卦");
        }
    }

    private static void requireResultHome(WrittenBookContent book, CastResult cast, Library.Rule rule, boolean practice) {
        String first = firstPageText(book);
        check(first.startsWith(practice ? "練習起卦結果" : "起卦結果")
            && first.contains("用途：本卦與效果"), "結果首頁須辨識正式或練習書與用途");
        check(first.contains("本卦 " + cast.primaryNumber() + " " + CastingEngine.nameForNumber(cast.primaryNumber())), "結果首頁須完整顯示本卦");
        check(first.contains(cast.changedNumber() == 0 ? "變卦：無變卦"
            : "變卦 " + cast.changedNumber() + " " + CastingEngine.nameForNumber(cast.changedNumber())), "結果首頁須完整顯示變卦");
        check(first.contains(Library.effectSummary(rule)), "結果首頁須使用本次實際規則，不得誤用本卦基礎效果");
        if (practice) {
            check(first.contains("正式效果示例") && (first.contains("練習不生效、不記圖鑑。")
                || first.contains("練習：無效果與紀錄")), "練習首頁須立即標明示例與不生效");
        } else {
            check(first.contains("本次遊戲效果") && first.contains("時長與冷卻見後頁"), "正式首頁須指引效果時間與冷卻頁");
        }
        String[] diagram = book.pages().stream().map(page -> page.raw().getString().split("\n", -1))
            .filter(lines -> lines[0].equals("本卦六爻")).findFirst().orElseThrow(() -> new AssertionError("結果須有六爻圖頁"));
        check(diagram[0].equals("本卦六爻") && diagram[1].equals("由上往下，動爻標動"), "結果六爻另頁須有明確爻序");
        for (int line = 5; line >= 0; line--) {
            int value = cast.lineValues().get(line);
            check(diagram[7 - line].equals(lineName(line) + value + (value == 6 || value == 9 ? "動 " : " ")
                + ((value & 1) == 1 ? "━━━" : "━ ━")), "結果六爻值、動爻與陰陽圖不可因首頁改版而遺失");
        }
    }

    private static String lineName(int index) {
        return List.of("初爻", "二爻", "三爻", "四爻", "五爻", "上爻").get(index);
    }

    private static void requireSourceNote(WrittenBookContent book, Library.HexText hex) {
        if (hex.sourceNote().isEmpty()) {
            return;
        }
        check(sectionBody(book, "版本與資料說明").equals(hex.sourceNote().replace("\n", "")), "版本缺口說明須逐字保留");
    }

    private static void collectClicks(Component component, List<ClickEvent> clicks) {
        ClickEvent click = component.getStyle().getClickEvent();
        if (click != null) {
            clicks.add(click);
        }
        for (Component sibling : component.getSiblings()) {
            collectClicks(sibling, clicks);
        }
    }

    private static boolean validCommand(String command) {
        if (Set.of("/yigua menu", "/yigua traditional", "/yigua sharing", "/yigua operation", "/yigua catalog",
                "/yigua guide", "/yigua atlas", "/yigua result", "/yigua taiji", "/yigua-mode button", "/yigua-mode direct",
                "/yigua-animation on", "/yigua-animation off",
                "/yigua-ritual-mode traditional", "/yigua-ritual-mode auto", "/yigua-ritual-mode instant",
                "/yigua-ritual-ui text", "/yigua-ritual-ui interactive", "/yigua sharing send on",
                "/yigua sharing send off", "/yigua sharing receive on", "/yigua sharing receive off",
                "/yigua group create", "/yigua group leave",
                "/yigua-achievements", "/yigua-ritual yarrow on", "/yigua-ritual yarrow off",
                "/yigua-ritual coins on", "/yigua-ritual coins off").contains(command)) {
            return true;
        }
        if (!command.startsWith("/yigua read ")) {
            return false;
        }
        try {
            int number = Integer.parseInt(command.substring("/yigua read ".length()));
            return number >= 1 && number <= 64;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    private static Set<String> pageCommands(Component page) {
        List<ClickEvent> clicks = new ArrayList<>();
        collectClicks(page, clicks);
        Set<String> commands = new HashSet<>();
        for (ClickEvent click : clicks) {
            if (click instanceof ClickEvent.RunCommand run) commands.add(run.command());
        }
        return commands;
    }

    private static String flatText(WrittenBookContent book) {
        StringBuilder text = new StringBuilder();
        book.pages().forEach(page -> text.append(page.raw().getString().replace("\n", "")));
        return text.toString();
    }

    private static String firstPageText(WrittenBookContent book) {
        return book.pages().getFirst().raw().getString().replace("\n", "");
    }

    private static int sectionPage(WrittenBookContent book, String heading) {
        for (int index = 0; index < book.pages().size(); index++) {
            StringBuilder title = new StringBuilder();
            for (String line : book.pages().get(index).raw().getString().split("\n", -1)) {
                if (line.isEmpty()) break;
                title.append(line);
            }
            if (title.toString().equals(heading)) return index;
        }
        return -1;
    }

    /** 去掉分頁時重複的標題與導覽，才能驗證跨頁句子仍完整。 */
    private static String sectionBody(WrittenBookContent book, String heading) {
        StringBuilder body = new StringBuilder();
        for (Filterable<Component> page : book.pages()) {
            String[] lines = page.raw().getString().split("\n", -1);
            int separator = 0;
            StringBuilder title = new StringBuilder();
            while (separator < lines.length && !lines[separator].isEmpty()) {
                title.append(lines[separator++]);
            }
            if (title.toString().equals(heading) || title.toString().equals(heading + "（續）")) {
                for (int index = separator + 1; index < lines.length - 1; index++) {
                    body.append(lines[index]);
                }
            }
        }
        return body.toString();
    }

    private static String label(int number, String status) {
        return (number < 10 ? "0" : "") + number + " " + CastingEngine.nameForNumber(number) + " " + status;
    }

    private static int codepoints(String text) {
        return text.codePointCount(0, text.length());
    }

    private static boolean noBrokenSurrogates(String text) {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(value)) {
                return false;
            }
        }
        return true;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
