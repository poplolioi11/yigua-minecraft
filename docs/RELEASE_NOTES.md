# 一卦 Minecraft 發布紀錄

## 0.2.1 公開測試版

### English

Release notes for the **0.2.1 public beta**. Download availability and each platform’s publication/review status are determined by the [official release page](https://github.com/poplolioi11/yigua-minecraft/releases) and platform listings. The [0.2.0 release](https://github.com/poplolioi11/yigua-minecraft/releases/tag/v0.2.0) remains a historical baseline.

- Show all **64 hexagrams** in an **8 × 8 King Wen order advancement grid**, from Qian to Wei Ji. Uncollected slots keep their individual six-line icons; any hexagram can unlock out of order. There are **107 advancement nodes** and **41 effect/event choices**: 32 ordinary effects and 9 hidden rare events.
- Extend positive rare auras to **two minutes** with looping colored currents and flower light. Five Qi grants eligible missing Regeneration, Resistance, Speed, Haste and Strength; Three Flowers grants Night Vision, Luck and Slow Falling, all at level I. Existing states and server protection rules take priority.
- Add translucent **non-player mob time echoes**: Five Qi shows recorded positions over the past three seconds; Three Flowers estimates the next three seconds from current motion and AI navigation. Predictions update when actions change. Each view is limited to 24 nearby mobs and six samples per mob. Prediction stops before body collision or eye-ray queries enter unloaded chunks, without loading or generating terrain.
- Five Qi world interactions now support bounded oak, birch, spruce, cherry, acacia, dark oak, mangrove, jungle and pale oak trees, including branches and eligible four-trunk forms. Natural non-persistent leaves provide evidence, not a perfect provenance guarantee; uncertain, oversized, merged-root or waterlogged structures are rejected or skipped, with short feedback. Crops and berry bushes can return to seedlings, fruiting stems to young stems, glow-berry vines to an unfruiting state, and several soils to dirt. Six non-mechanical copper shapes can step back one oxidation stage while keeping wax and shape. Restore only owned unchanged temporary states; preserve foreign growth, waxing and replacement without healing, inventory rewinds or extra loot.
- Widen the water corridor to **nine blocks**, covering the player and eleven blocks ahead, opening each column from player depth to its water surface. Retain previously opened sections during movement until the original two-minute deadline, then restore in batches. Dry-land activation can wait for entry into water without resetting the clock. Unloaded/unsafe columns and the **32,768-block / 32-chunk** limit stop new expansion while preserving opened sections. Natural-flow interception remains limited to owned air; direct bucket placement and foreign fluids stay outside that scope. Any replacement block or water, including the original water LEVEL, relinquishes ownership and is preserved. Water restoration and regression reserve mutually exclusive positions with pending obstacle construction; permanent completed obstacles use normal chunk storage.
- Retain world-space clouds, a 120-tick divine scene with a beam descending from 32 blocks over 100 ticks, and **six client lightning flashes followed by one damage attempt**. These local sky visuals do not change server weather. Ordinary ten-minute and changed-hexagram five-plus-five-minute phases are unchanged.

The guidebook still keeps secret triggers and probabilities hidden. Dedicated servers default world changes to off unless explicitly enabled; this is not universal land-claim integration. See [compatibility limits](COMPATIBILITY.md).

- Rising obstacles now leave **permanent vanilla terrain**. Each land wave can cover seven blocks wide, three deep and three high (up to 63 blocks). Underwater columns grow from the seabed, searching at most 128 blocks down and adding a layer every two ticks. Water and replaceable, collision-free vegetation can be replaced; solid buildings, block entities, protection vetoes and reserved positions take priority. Materials follow the biome: sand in deserts/oceans/beaches, snow blocks in cold areas, packed mud in swamps, mossy cobblestone in forests, basalt in the Nether, end stone in the End and stone elsewhere. The one-minute event deadline or Taiji clear stops new placement; completed blocks remain and can be mined normally.
- Rename the bird event **Hundred Birds Pay Homage to the Phoenix**. A flying phoenix and animated pixel wings accompany two-minute movement: hold Jump to ascend, Sneak to descend, and release to glide down. The effect does not grant Creative flight or change player abilities. Existing flight, elytra gliding and levitation take priority; protected gravity/fall settings can disable it.

**Validation:** The latest full build and self-tests passed in 10 seconds across 28 tasks (26 executed, 2 up-to-date). Checks include 31,393 obstruction, 8,543 regression, 24,290 corridor and 9,650 flight-rule checks; the flight entry also runs 269,609 geometry checks, counted once. Native IntegratedServer testing on 2026-10-12 at 06:02 Taiwan time passed all 12 cases / 537,671 assertions with 13 external mods loaded. This includes permanent land/ocean obstacles, six genuinely generated tree species, the listed crop/copper/surface interactions, and real client/server phoenix ascent, descent, glide, expiry and simulated foreign-flight priority. Original world/player state and input keys were restored. The connection was local in-memory, not a new real-TCP or dedicated-server test. The earlier **10-case / 20,900-assertion IntegratedServer result belongs to the snapshot before those changes**, with original state restored; it is not final validation of the latest code. A separate moving-cow fixture at 00:09 confirmed six separated translucent echoes in both FUTURE and PAST native GPU screenshots; particles partly occlude them. Original world/player state was restored. The first-person phoenix frame visibly includes pixel wings and birds; third-person appearance and full animation aesthetics were not exhaustively assessed. Local memory connections do not replace dedicated-server or real-TCP testing. The 0.2.0 **29-case / 1,576-assertion real-TCP regression** remains a separate historical baseline. See [VALIDATION.md](VALIDATION.md).

Requires Minecraft Java 26.3, Java 25, Fabric Loader 0.19.5 and Fabric API 0.162.0+26.3 on clients and server. By **poplolioi11 / Yigua yigua.art**; AI production and separate MIT / CC BY 4.0 scopes remain in [PROVENANCE.md](../PROVENANCE.md). Download, listing and approval status for Modrinth and Nexus Mods should be checked on their official pages; prepared drafts and uploaded files do not imply approval. CurseForge submissions are paused at the author’s request, with no further sign-in attempts.

### 繁體中文

此為 **0.2.1 公開測試版**變更紀錄；下載及各平台上架／審核狀態以 [官方下載頁](https://github.com/poplolioi11/yigua-minecraft/releases) 與各平台頁面為準。[0.2.0](https://github.com/poplolioi11/yigua-minecraft/releases/tag/v0.2.0) 保留為歷史版本。

- 原版「進度」完整顯示 **64 卦八乘八網格**，依文王卦序乾至未濟；未取得也保留六爻圖示，亂序卜到即可解鎖。共 **107個節點、41項效果／事件**：32種一般效果與9種隱藏稀有事件。
- 正向稀有狀態延長為 **兩分鐘**，五色氣流與頭頂光花持續循環。五氣可補恢復、抗性、速度、挖掘加速及力量；三花可補夜視、幸運與緩降，皆第一級，既有同名與受保護項目略過。
- 增加非玩家生物半透明時間虛影：五氣看過去三秒真實位置；三花依當下速度／AI路徑推估未來三秒，會隨行動變更更新。最多顯示32格內24個生物、各6筆樣本；身體或眼位射線涉及未載入區塊便停止，不載入新地形。
- 五氣可回溯九種有界原版樹：橡木、樺木、杉木、櫻花、相思木、黑橡木、紅樹林、叢林木與蒼白橡木；支援天然枝幹與適用的四幹結構。非 persistent 冠葉作為辨識依據，仍無法百分之百區別自然與人工來源；無自然樹冠的木柱、合併多根、過大結構、水浸根與含資料方塊會拒絕或略過。成功或拒絕時會顯示短提示。成熟作物與莓果叢可回到幼苗、已結果瓜藤可回到幼藤、發光漿果藤可回到未結果狀態；草地、菌絲土、灰壤、泥土路、耕地、扎根泥土與粗泥可回到泥土。六種非機關銅材可倒退一個氧化階段，保留蠟層、方向及台階形狀；不處理容器、銅門或銅燈等機關。到期只復原仍屬自身的年齡及方塊，保留外部生長、上蠟及換材，不補血、不回復背包、不增加掉落物。
- 分海改為**九格寬**、含腳下至前方共十二格，各列從玩家深度開至水面；移動累積並保留後方水道，原兩分鐘期限到達時分批復原，乾地等待入水不重設期限。未載入／不安全列及**32,768格／32區塊**上限只停止新擴張並告知，保留已有水道。自然水流攔截限自有空氣，不攔桶水直接放置或第三方流體；重新放入的方塊／水包含相同LEVEL都撤權並保留。分海、回溯與障路待放置位置互斥；障路落成後按原版永久方塊保存。
- 保留世界空間雲影、神光120刻演出及32格光束100刻下降；**六道雷光後只有一次原版傷害判定**，不改伺服器天氣。一般十分鐘與變卦前後五分鐘沒有改動。

遊戲書仍不預告秘效觸發條件與機率。獨立伺服器預設停用世界變更，需明確開啟；不是通用領地權限保障，詳見 [相容性界線](COMPATIBILITY.md)。

- 障路生成期約一分鐘，每跨到新格或轉向便在前方升起，原地每 60 刻再起一輪。陸地每輪最多 7 格寬、3 格深、3 格高，共 63 格；水中沿 7×3 列從最深 128 格內的海床開始，每兩刻上升一層，直到玩家頭頂。可取代原版水、落葉、花草、藤蔓及其他無碰撞且可替換的植物方塊；不覆寫完整實體建築、方塊實體或既有回溯／分海持有位置。生成物按生態選材：沙漠、海洋、海灘用沙；寒冷地用雪塊；沼澤用泥坯；森林用青苔石；地獄用玄武岩；終界用終界石；其他地區用石頭。**落成的方塊永久保留，可正常挖掘與掉落；到期或太極解除只停止後續生成，不清除已建障壁。**
- 「萬鳥朝拜」改名為「百鳥朝鳳」：兩分鐘鳳凰飛舞與像素振翅，按空白鍵上升、蹲下鍵下降，放開後緩降。飛行只採臨時移動與緩降，不開啟玩家的創造飛行權限；已具有飛行、鞘翅滑翔、漂浮等狀態時讓原有能力優先，重力或摔落傷害受保護時不啟用。

**驗收狀態：**最新完整建置與自測通過：10 秒、28 項工作（26 項執行、2 項沿用）。最新原生單人測試於 2026-10-12 06:02（臺灣時間）完成 12 個案例／537,671 項斷言，涵蓋永久障路、六種真正生成樹／方塊前態，以及鳳翼升降、緩降、外來能力優先與到期；原世界、玩家狀態與測試按鍵已復原。這是載入 13 個外部模組的 IntegratedServer 本機記憶體連線，不能代替最新真正 TCP 或獨立伺服器驗收。較早 10 案例／20,900 項斷言保留為上述修改前基線。正式自測包括障路 31,393、回溯 8,543、分海 24,290、飛行規則 9,650 項；飛行入口另執行 269,609 項幾何檢查，只計一次。獨立移動牛 fixture 已確認六筆分離虛影與 FUTURE／PAST 原生 GPU 畫面，粒子會部分遮擋，本機記憶體連線也不是真正 TCP 或獨立伺服器驗收。0.2.0 的 29 案例／1,576 斷言真正 TCP 基線與先前失敗紀錄保留，詳見 [驗收紀錄](VALIDATION.md)。

環境維持 Minecraft Java 26.3、Java 25、Fabric Loader 0.19.5、Fabric API 0.162.0+26.3；主機及玩家端須相同模組版本。作者為 **poplolioi11 / 一卦 yigua.art**；AI披露與MIT／CC BY 4.0範圍維持 [PROVENANCE.md](../PROVENANCE.md)。Modrinth 與 Nexus Mods 的可下載版本及上架／審核狀態以各平台官方頁面為準；草稿或檔案上傳不等於核准。使用者指定暫緩 CurseForge，不再嘗試登入。

## 0.2.0 Beta：已公開的歷史紀錄

The first public beta is available on GitHub. Version 0.1.0 was an internal development build; other mod-sharing platforms have not yet approved or published the project.

### English

Bring I Ching casting, classical texts and a 64-hexagram collection into Minecraft.

**Requires Minecraft Java 26.3, Java 25, Fabric Loader 0.19.5 and Fabric API 0.162.0+26.3. Install the same mod version on every client and the server. The in-game interface is currently Traditional Chinese.**

- Craft reusable coin or stalk tools. Choose Traditional, Automatic or Instant casting; traditional casting supports text controls and real drag-and-drop piles or coin throws. The central tool animation is optional.
- Receive the original hexagram, moving lines, changed hexagram and effects in chat. Explore 32 ordinary effects and 8 rare events; changed hexagrams switch effect phases after five minutes at normal tick rate.
- Use the guidebook's one-page menu, read classical sources and track personal hexagrams and advancements. Practice grants no effects or collection progress.
- Play individually by default, or opt into nearby party buffs with separate sender and recipient consent. Unlock Taiji through full collection and advancements, or use it in Creative mode.

Start by opening the guidebook given on your first visit, craft a tool, then right-click it to open the casting panel. Server owners can disable effects, sharing or temporary block changes. Compatibility with every modpack is not guaranteed; there is no universal land-claim permission integration.

**Validation:** The full build after the new coin-interface labels passed in 11 seconds across 19 tasks. The earlier real-TCP baseline remains 12 cases / 398 assertions. On October 11 at 20:07:18 Taiwan time, the expanded IntegratedServer suite passed all 29 cases / 1,576 assertions and restored the original test state; a 20:09:12 checkpoint confirmed two real TCP clients.

The `full-rei` host loaded 13 external mods. New terrain loaded, Terralith biome registry and locate results were checked, Instant practice worked, and the REI item panel was visible. This does not certify REI recipe/search controls or arrival at the exact located lavender biome. JEI-only and other prepared profiles have not been run.

The eight-entry, one-page menu was seen in game. An earlier real mouse-drag sequence produced 3 + 2 + 2 = 7 for the first line. The new coin labels and keyboard throws were then confirmed through the first line (2 + 2 + 2 = 6, old yin) and into the second; switching from text to interactive controls preserved its 1/3 progress. These observations do not replace a complete mouse-drag or six-line flow check. The final build passed again after the Creative-tab and animation updates. The “易經” Creative tab visibly contains all 19 items. The reloaded Taiji animates in the held item and selection panel; its reversed direction was checked frame by frame. All three selection tabs, the Travel confirmation/application and the clear button were exercised with keyboard input. Dedicated-server gameplay remains unverified. This beta is available on GitHub; other platform submissions await author sign-in and terms acceptance. See [the validation record](VALIDATION.md).

By **poplolioi11 / Yigua yigua.art**. New code and project artwork use MIT; ancient text is public domain, while original data annotations and gameplay explanations use CC BY 4.0. Most implementation and descriptive text were written with generative AI; eleven base item assets used image generation, while the Taiji and listing icon are rendered programmatically by AI-authored generators. No AI service is required during play. See [provenance](../PROVENANCE.md), [artwork credits](ASSETS.md) and [the player's guide](GETTING_STARTED.md).

### 繁體中文

把《易經》起卦、古籍閱讀與六十四卦收藏帶進 Minecraft。

**需要 Minecraft Java 26.3、Java 25、Fabric Loader 0.19.5 與 Fabric API 0.162.0+26.3。伺服器與所有玩家端須安裝相同版本。遊戲介面目前僅繁體中文。**

- 製作可重複使用的錢幣或籌策筮具，選擇傳統、自動或馬上起卦。傳統玩法提供文字控制與實際拖曳分堆／投錢，中央筮具動畫可關閉。
- 聊天揭曉本卦、動爻、變卦及效果。探索 32 種一般效果與 8 種罕見事件；有變卦時，正常遊戲刻下前後五分鐘分別套用兩段效果。
- 總覽書預設一頁目錄，另選古籍閱讀、個人卦象收藏與成就。練習不套效果或增加收藏。
- 預設個人模式；來源與接收者各自同意後，可分享附近隊友的一般增益。全收藏及成就解鎖太極，創造模式亦可使用。

第一次進世界會取得總覽書。先開目錄、製作筮具，再右鍵打開起卦面板。服主可關閉效果、共享或暫時方塊改動；沒有所有領地模組共通的權限掛鉤，不保證任意整合包相容。

**驗收狀態：**新增三錢介面文字後，完整建置以 11 秒、19 項工作通過。既有真正 TCP 基線維持 12 案例／398 項斷言；10 月 11 日 20:07:18（臺灣時間），新版 IntegratedServer 測試完成 29 案例／1,576 項斷言並還原測試前狀態，20:09:12 再確認兩名真正 TCP 玩家在線。

`full-rei` 主機實際載入 13 個外部模組，已載入新地形、核對 Terralith 生態域 registry 與 locate 結果、完成馬上練習起卦並看到 REI 物品旁欄；尚未驗收 REI 配方／搜尋操作或實際抵達指定薰衣草生態域。JEI 單獨組合與其他僅備妥組合尚未執行。

已看到一頁八入口目錄。先前真實滑鼠拖曳完成第一爻 3＋2＋2＝7；新三錢文字另已實機確認，鍵盤投擲完成第一爻 2＋2＋2＝6、老陰動爻，再進入第二爻。文字切換到互動拖曳後保留 1／3 進度；此證據不代替新版完整滑鼠拖曳或六爻流程。創造分頁與動畫更新後，最終完整建置再次通過。「易經」分頁已實際顯示全部 19 件物品，太極持物與面板圖示已載入反向動畫，方向另以逐格核對確認；三個選擇分頁、行旅確認／套用及解除按鈕已用鍵盤實際操作。獨立伺服器遊玩仍待驗證；此測試版由 GitHub 提供下載，其他平台待作者登入及完成條款流程後送審。詳見 [驗收紀錄](VALIDATION.md)。

作者與維護者為 **poplolioi11 / 一卦 yigua.art**。新撰程式與專案圖示採 MIT；古籍原文屬公有領域，原創資料與遊戲改編說明採 CC BY 4.0。大部分程式及說明由生成式 AI 編寫；11 張基礎物品素材使用影像生成，太極及發布圖示則由 AI 編寫的程式繪製。遊戲執行不需 AI 服務。來源與限制見 [PROVENANCE.md](../PROVENANCE.md)、[素材說明](ASSETS.md) 與 [玩家入門](GETTING_STARTED.md)。

本檔供首個 GitHub 公開測試版使用；其他模組平台尚未送審，待作者完成登入與條款流程。0.1.0 為內部開發版。
