# 平台發布文案

此檔提供 **0.2.1 公開測試版**平台文案。最新原生單人套件 12 個案例通過，實際載入 13 個外部模組並復原測試狀態；完整範圍見 [VALIDATION.md](VALIDATION.md)。最新真正 TCP、獨立伺服器及任意整合包仍未驗證。草稿或檔案上傳不等於平台核准；使用者指定暫緩 CurseForge，不再嘗試登入。

| 平台 | 製作時的準備紀錄；最新狀態以官方頁面為準 |
| --- | --- |
| GitHub | 官方原始碼、版本與下載入口；0.2.0歷史版本已公開，0.2.1版本、附件與下載狀態依官方發布頁 |
| Modrinth | Unlisted草稿已保存首版檔、必要Fabric API及AI Code／Assets／Text、公有領域衍生與閃光披露；版本、送審與公開狀態依官方頁面 |
| Nexus Mods | 草稿已準備首版主檔與實機創造分頁截圖；頁面／檔案更新與公開狀態依官方頁面 |
| CurseForge | 使用者指定暫緩 CurseForge，不再嘗試登入；未完成送審或發布 |

## English listing

### Name

Yigua: I Ching for Minecraft

### Short description

Cast with coins or stalks, discover 64 hexagrams, read classical texts, and explore changing fortunes with optional party buffs.

### Description

Bring I Ching divination, classical reading and a personal hexagram collection into Minecraft. Craft reusable coin or stalk tools, cast a hexagram, and see the original hexagram, moving lines, changed hexagram and gameplay effects in chat.

**Requires Minecraft Java 26.3, Java 25, Fabric Loader 0.19.5 and Fabric API 0.162.0+26.3. Install the same mod version on the server and every client. The in-game interface is currently Traditional Chinese.**

Start with the guidebook given on your first visit. Its one-page menu links to casting settings, traditional methods, multiplayer sharing, optional detailed rules, the 64-hexagram index, your collection and your latest result. Copper coins need only one copper ingot; bamboo and common plant fibers provide an accessible stalk tool.

- Choose **Traditional, Automatic or Instant** casting, independently for coins and stalks. Practice uses the same options without granting effects, collection progress or cooldown.
- Use **text or drag-and-drop** for traditional casting. Drag individual or selected groups of stalks between piles, then confirm the real count. Drag a coin into the throwing area to request a fair server-side toss. Changing the interface preserves the current session.
- Discover **32 ordinary effects and 9 hidden rare events**. Moving lines change the effect set. When a changed hexagram exists, the first five minutes use the original phase and the next five use the changed phase; an unchanged cast lasts ten minutes at normal tick rate.
- Explore material strengths and small environmental influences. Stronger tools amplify both benefits and penalties. Visuals reflect the selected materials; the central tool animation can be disabled.
- Discover **two-minute rare positive auras**, looping colored light, and translucent time echoes around non-player mobs. Some echoes show recorded motion, while others estimate the next few seconds and update as actions change; at most 24 nearby mobs and six samples each are shown. Predictions stop at unloaded chunks without generating new terrain. Eligible missing vanilla buffs respect existing states and server protections.
- Five Qi world interactions now support bounded oak, birch, spruce, cherry, acacia, dark oak, mangrove, jungle and pale oak trees, including branches and eligible four-trunk forms. Natural non-persistent leaves provide evidence, not a perfect provenance guarantee; uncertain, oversized, merged-root or waterlogged structures are rejected or skipped, with short feedback. Crops and berry bushes can return to seedlings, fruiting stems to young stems, glow-berry vines to an unfruiting state, and several soils to dirt. Six non-mechanical copper shapes can step back one oxidation stage while keeping wax and shape. Restore only owned unchanged temporary states; preserve foreign growth, waxing and replacement without healing, inventory rewinds or extra loot.
- Rising obstacles now leave **permanent vanilla terrain**. Each land wave can cover seven blocks wide, three deep and three high (up to 63 blocks). Underwater columns grow from the seabed, searching at most 128 blocks down and adding a layer every two ticks. Water and replaceable, collision-free vegetation can be replaced; solid buildings, block entities, protection vetoes and reserved positions take priority. Materials follow the biome: sand in deserts/oceans/beaches, snow blocks in cold areas, packed mud in swamps, mossy cobblestone in forests, basalt in the Nether, end stone in the End and stone elsewhere. The one-minute event deadline or Taiji clear stops new placement; completed blocks remain and can be mined normally.
- Rename the bird event **Hundred Birds Pay Homage to the Phoenix**. A flying phoenix and animated pixel wings accompany two-minute movement: hold Jump to ascend, Sneak to descend, and release to glide down. The effect does not grant Creative flight or change player abilities. Existing flight, elytra gliding and levitation take priority; protected gravity/fall settings can disable it.
- Open nine-block-wide water corridors from player depth to the water surface, retaining opened routes until the original two-minute deadline. Expansion and batch restoration are bounded; foreign water or blocks are preserved.
- Collect the **64 original hexagrams** and view separate hexagram and effect advancement tabs. The 0.2.1 hexagram tab shows all 64 slots in an **8 × 8 King Wen order grid**, even before collection; hexagrams can be unlocked out of order. There are 107 advancement nodes overall. Formal casting records your collection; reading and practice do not.
- Play individually by default. Parties support **up to 16 players**; ordinary positive buffs are shared only within **32 blocks**, in the same dimension, with the sender and recipient separately opting in. Matching buffs use the strongest value. Negative effects, rare hexagram bundles and rare events are not shared.
- Unlock **Taiji** after completing the full collection and all ordinary and hidden advancements, or use it in Creative mode. Choose six lines, a hexagram or one of 41 effects, with confirmation, or clear your own Yigua state while keeping collection and advancements.

Server owners can disable gameplay effects, rare events, party sharing or temporary world-changing interactions, and protect selected attributes and effects for their modpack. Block changes default to disabled on dedicated servers unless explicitly enabled. There is no universal land-claim permission integration, and compatibility with every modpack is not guaranteed.

**Version: 0.2.1 public beta.** The latest native single-player suite passed all 12 cases with 13 external mods loaded, including permanent obstacles, naturally generated trees, block regression and phoenix flight. First-person phoenix wings and separated FUTURE/PAST mob echoes were seen in actual gameplay. This was a local IntegratedServer test; new real-TCP multiplayer, dedicated servers and arbitrary modpacks remain unverified. The earlier 0.2.0 real-TCP baseline and full test scope are recorded in [VALIDATION.md](VALIDATION.md). Download and approval status follow each official page; drafts and uploads do not imply approval. CurseForge is paused at the author’s request.

**AI disclosure:** Generative AI produced most code, interface implementation and descriptive text under poplolioi11's gameplay direction. Eleven base pixel-art assets were created with an image-generation model; hexagram diagrams are rendered programmatically. The classical-text transcription also includes AI assistance and is not a fully proofread edition. No generative AI service is required while playing.

**Credits and licensing:** Maintained by **poplolioi11 / Yigua yigua.art**. New mod code is MIT-licensed. Ancient text is public domain; original data annotations and gameplay explanations use CC BY 4.0 with attribution. Gameplay effects are adaptations, not definitive interpretations or real-world advice. See the included provenance and license documents.

Official version, download and issue links:

- [Source and documentation](https://github.com/poplolioi11/yigua-minecraft)
- [Downloads](https://github.com/poplolioi11/yigua-minecraft/releases)
- [Issues](https://github.com/poplolioi11/yigua-minecraft/issues)
- [Classical-text source](https://github.com/poplolioi11/yigua-wuyingdian-lines)

## 繁體中文介紹

### 名稱與短介

一卦 Minecraft

用錢幣或籌策起卦，蒐集六十四卦、閱讀古籍，讓變卦與動爻為探索帶來變化；可自行開啟附近隊友共享增益。

### 說明

把《易經》起卦、原文閱讀與個人收藏帶進 Minecraft。製作可重複使用的錢幣或籌策筮具，在聊天查看本卦、動爻、變卦與遊戲效果。首次進世界贈送總覽書，先開一頁操作目錄，完整規則需要時再讀。

**需要 Minecraft Java 26.3、Java 25、Fabric Loader 0.19.5 與 Fabric API 0.162.0+26.3。伺服器和所有玩家端都要安裝相同模組版本。遊戲介面目前僅繁體中文。**

- 三錢與籌策各自選「傳統／自動／馬上」；正式與練習都能使用。練習不套效果、不記收藏或冷卻。
- 傳統方式選文字或互動拖曳。竹籌可逐根／框選後整批分堆，確認實際左堆數；錢幣拖到投擲區放手，由伺服器公平決定錢面。中途換介面保留進度。
- 32 種一般效果、9 種隱藏稀有內容。動爻決定追加效果，有變卦時前五分鐘本卦、後五分鐘變卦；沒有變卦則維持十分鐘，以標準遊戲刻計算。
- 材質同時放大增益與減益，環境小幅影響強度。動畫保留材質外觀，中央筮具動畫可關閉。
- 探索**兩分鐘正向稀有狀態**、循環色光與非玩家生物的半透明時間虛影。有的顯示真實記錄，有的依目前行動推估未來幾秒並更新，最多顯示附近24個生物、各6筆樣本；未載入區塊即停止推估，不生成新地形。新原版增益保留既有狀態並尊重伺服器保護。
- 五氣可回溯九種有界原版樹：橡木、樺木、杉木、櫻花、相思木、黑橡木、紅樹林、叢林木與蒼白橡木；支援天然枝幹與適用的四幹結構。非 persistent 冠葉作為辨識依據，仍無法百分之百區別自然與人工來源；無自然樹冠的木柱、合併多根、過大結構、水浸根與含資料方塊會拒絕或略過。成功或拒絕時會顯示短提示。成熟作物與莓果叢可回到幼苗、已結果瓜藤可回到幼藤、發光漿果藤可回到未結果狀態；草地、菌絲土、灰壤、泥土路、耕地、扎根泥土與粗泥可回到泥土。六種非機關銅材可倒退一個氧化階段，保留蠟層、方向及台階形狀；不處理容器、銅門或銅燈等機關。到期只復原自有暫時狀態，不補血、不回復背包、不增加掉落物。
- 障路生成期約一分鐘，每跨到新格或轉向便在前方升起，原地每 60 刻再起一輪。陸地每輪最多 7 格寬、3 格深、3 格高，共 63 格；水中沿 7×3 列從最深 128 格內的海床開始，每兩刻上升一層，直到玩家頭頂。可取代原版水、落葉、花草、藤蔓及其他無碰撞且可替換的植物方塊；不覆寫完整實體建築、方塊實體或既有回溯／分海持有位置。生成物按生態選材：沙漠、海洋、海灘用沙；寒冷地用雪塊；沼澤用泥坯；森林用青苔石；地獄用玄武岩；終界用終界石；其他地區用石頭。**落成的方塊永久保留，可正常挖掘與掉落；到期或太極解除只停止後續生成，不清除已建障壁。**
- 「萬鳥朝拜」改名為「百鳥朝鳳」：兩分鐘鳳凰飛舞與像素振翅，按空白鍵上升、蹲下鍵下降，放開後緩降。飛行只採臨時移動與緩降，不開啟玩家的創造飛行權限；已具有飛行、鞘翅滑翔、漂浮等狀態時讓原有能力優先，重力或摔落傷害受保護時不啟用。
- 九格寬分海從玩家深度開至水面，移動保留已開路徑，原兩分鐘期限到達後分批復原；外部放入的方塊與水保留。
- 收集 64 個正式本卦，查看卦象與效果兩個原版進度頁籤。0.2.1 的六十四卦依文王卦序排成八乘八網格，未取得也保留位置，任意卦可亂序解鎖；總進度節點為 107 個。閱讀與練習不額外解鎖。
- 預設個人模式。群組最多 16 人；同維度、32 格內，來源與接收者各自同意才分享一般正面效果，同類只取最強。負面、整組罕見卦局與稀有事件不分享。
- 完成全收藏及所有一般／隱藏成就後解鎖太極，創造模式也可使用。選六爻、六十四卦或 41 種效果，確認後套用；解除自身一卦狀態仍保留收藏與成就。

服主可關閉效果、稀有事件、共享或暫時世界互動，並保護整合包指定的屬性與效果。獨立伺服器預設不改方塊，需明確開啟；沒有通用領地權限掛鉤，不保證任意整合包相容。

**版本：0.2.1 公開測試版。** 最新原生單人套件 12 個案例全數通過，實際載入 13 個外部模組；涵蓋永久障路、真正生成樹、方塊回溯與鳳翼飛行。第一人稱鳳翼、FUTURE／PAST 分離生物虛影已有實際遊戲畫面確認。這是本機 IntegratedServer 測試，最新真正 TCP 多人、獨立伺服器及任意整合包仍未驗證；先前 0.2.0 真正 TCP 基線與完整範圍見 [VALIDATION.md](VALIDATION.md)。下載與審核以各平台官方頁面為準，草稿或上傳不等於核准；CurseForge 依使用者要求暫緩。

**AI 製作披露：**依 poplolioi11 的玩法需求，大部分程式碼、介面與說明由生成式 AI 編寫；11 張基礎像素素材由影像生成模型製作，六爻圖示由程式繪製。古籍初錄含 AI 協助，未完成全文二校。遊戲執行不需 AI 服務或 API 金鑰。

作者與維護者為 **poplolioi11 / 一卦 yigua.art**。新撰程式碼採 MIT；古籍原文屬公有領域，原創資料說明與遊戲改編說明採 CC BY 4.0 並保留署名。效果是遊戲改編，不是唯一經文解釋或現實行動建議。來源與授權範圍另附說明。

[官方原始碼](https://github.com/poplolioi11/yigua-minecraft)、[官方下載](https://github.com/poplolioi11/yigua-minecraft/releases) 與 [問題回報](https://github.com/poplolioi11/yigua-minecraft/issues)。

## 提交平台時的必要註記

以下為發布者使用的提交註記，不需全部貼入玩家介紹。政策核對日期為 2026-10-11；最後以各平台審核結果為準。

### Modrinth

This project uses substantial AI-generated code, assets and page text. Apply the corresponding content disclosures. Because most implementation is generated, request **unlisted** availability rather than claiming eligibility for public discovery. Unlisted acceptance is still subject to review. Do not use generated images as the project icon, banner or gallery artwork. [Official AI policy](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai), [content disclosures](https://support.modrinth.com/en/articles/16567675-content-disclosures).

本專案須勾選 AI 程式碼、素材及文字披露。按大量 AI 製作的實際情況申請 **unlisted**，不宣稱可公開搜尋；是否接受仍由平台判斷。頁面圖示、橫幅與展示圖不得使用影像生成素材，不以改名稱或隱藏披露繞過限制。[官方政策](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai)。

### CurseForge

**Paused at the author’s request; do not retry sign-in.** The policy notes below are retained for reference if submission is resumed.

**使用者指定暫緩 CurseForge，不再嘗試登入。** 下方政策僅保留供日後恢復投稿時參考，目前不繼續送審。

Use the full AI disclosure above and show actual gameplay. Any AI-edited or generated showcase that could misrepresent the mod must carry a visible disclaimer. Submission and file review are not confirmation of publication. [Official moderation policies](https://support.curseforge.com/support/solutions/articles/9000197279-moderation-policies).

保留完整 AI 製作披露，使用忠實遊戲畫面；若 AI 展示圖可能讓玩家誤認內容，必須清楚標示。送審與檔案上傳不等於已公開。[官方審核政策](https://support.curseforge.com/support/solutions/articles/9000197279-moderation-policies)。

### Nexus Mods

Apply **AI-Generated Content** for the generated mod implementation/assets and **AI Media** for generated page descriptions or other page media. Do not label this project only as limited AI assistance. Include the gameplay requirements, functioning mod file, credits and separate data licenses. [Official submission guidelines](https://help.nexusmods.com/article/28-file-submission-guidelines).

使用 **AI-Generated Content** 與 **AI Media** 標籤，反映模組內容及這份頁面文案的製作方式；不得只標成少量 AI 輔助。附上可用模組檔、環境需求、作者及資料授權。[官方投稿規範](https://help.nexusmods.com/article/28-file-submission-guidelines)。

### GitHub

Use the official `poplolioi11/yigua-minecraft` source and release pages for version and artifact availability. Preserve `LICENSE`, `PROVENANCE.md`, AI disclosure and attribution. GitHub availability is separate from acceptance by a mod-sharing platform.

GitHub 開源與下載頁不代表其他平台已核准；各平台登入、送審與公開狀態須分開核對。
