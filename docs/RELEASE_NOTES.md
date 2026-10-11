# Yigua 0.2.0 Beta / 一卦 0.2.0 測試版

Submission draft for the first public release. Version 0.1.0 was an internal development build. Publication and platform approval have not yet been confirmed.

## English

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

## 繁體中文

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
