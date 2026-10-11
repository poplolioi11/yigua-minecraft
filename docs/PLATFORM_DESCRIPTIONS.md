# 平台發布文案

此檔為 **0.2.0 模組平台提交文案**。GitHub 提供公開測試版；CurseForge、Modrinth、Nexus 尚未送審，待作者完成登入與條款流程。發布者應在提交後更新各平台連結與狀態；測試範圍以 [VALIDATION.md](VALIDATION.md) 為準。

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
- Discover **32 ordinary effects and 8 hidden rare events**. Moving lines change the effect set. When a changed hexagram exists, the first five minutes use the original phase and the next five use the changed phase; an unchanged cast lasts ten minutes at normal tick rate.
- Explore material strengths and small environmental influences. Stronger tools amplify both benefits and penalties. Visuals reflect the selected materials; the central tool animation can be disabled.
- Collect the **64 original hexagrams** and view separate hexagram and effect advancement tabs. Formal casting records your collection; reading and practice do not.
- Play individually by default. Parties support **up to 16 players**; ordinary positive buffs are shared only within **32 blocks**, in the same dimension, with the sender and recipient separately opting in. Matching buffs use the strongest value. Negative effects, rare hexagram bundles and rare events are not shared.
- Unlock **Taiji** after completing the full collection and all ordinary and hidden advancements, or use it in Creative mode. Choose six lines, a hexagram or one of 40 effects, with confirmation, or clear your own Yigua state while keeping collection and advancements.

Server owners can disable gameplay effects, rare events, party sharing or temporary block-changing events, and protect selected attributes and effects for their modpack. Block changes default to disabled on dedicated servers unless explicitly enabled. There is no universal land-claim permission integration, and compatibility with every modpack is not guaranteed.

**Release status:** 0.2.0 is a public beta on GitHub. Two real TCP clients passed a 29-case regression with 13 external mods on the host. Dedicated-server gameplay and arbitrary modpacks remain unverified. Read the [validation record](https://github.com/poplolioi11/yigua-minecraft/blob/main/docs/VALIDATION.md). Other platform submissions await author sign-in and terms acceptance.

**AI disclosure:** Generative AI produced most code, interface implementation and descriptive text under poplolioi11's gameplay direction. Eleven base pixel-art assets were created with an image-generation model; hexagram diagrams are rendered programmatically. The classical-text transcription also includes AI assistance and is not a fully proofread edition. No generative AI service is required while playing.

**Credits and licensing:** Maintained by **poplolioi11 / Yigua yigua.art**. New mod code is MIT-licensed. Ancient text is public domain; original data annotations and gameplay explanations use CC BY 4.0 with attribution. Gameplay effects are adaptations, not definitive interpretations or real-world advice. See the included provenance and license documents.

Planned official links, not yet confirmed published:

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
- 32 種一般效果、8 種隱藏稀有內容。動爻決定追加效果，有變卦時前五分鐘本卦、後五分鐘變卦；沒有變卦則維持十分鐘，以標準遊戲刻計算。
- 材質同時放大增益與減益，環境小幅影響強度。動畫保留材質外觀，中央筮具動畫可關閉。
- 收集 64 個正式本卦，查看卦象與效果兩個原生成就頁籤；閱讀與練習不額外解鎖。
- 預設個人模式。群組最多 16 人；同維度、32 格內，來源與接收者各自同意才分享一般正面效果，同類只取最強。負面、整組罕見卦局與稀有事件不分享。
- 完成全收藏及所有一般／隱藏成就後解鎖太極，創造模式也可使用。選六爻、六十四卦或 40 種效果，確認後套用；解除自身一卦狀態仍保留收藏與成就。

服主可關閉效果、稀有事件、共享或暫時方塊改動，並保護整合包指定的屬性與效果。獨立伺服器預設不改方塊，需明確開啟；沒有通用領地權限掛鉤，不保證任意整合包相容。

**目前為 GitHub 0.2.0 公開測試版。** 兩名真正 TCP 玩家在主機載入 13 個外部模組時，通過 29 個案例的回歸；獨立伺服器與任意整合包尚未驗證。其他模組平台尚未送審，實際範圍以驗收紀錄為準。

**AI 製作披露：**依 poplolioi11 的玩法需求，大部分程式碼、介面與說明由生成式 AI 編寫；11 張基礎像素素材由影像生成模型製作，六爻圖示由程式繪製。古籍初錄含 AI 協助，未完成全文二校。遊戲執行不需 AI 服務或 API 金鑰。

作者與維護者為 **poplolioi11 / 一卦 yigua.art**。新撰程式碼採 MIT；古籍原文屬公有領域，原創資料說明與遊戲改編說明採 CC BY 4.0 並保留署名。效果是遊戲改編，不是唯一經文解釋或現實行動建議。來源與授權範圍另附說明。

[官方原始碼](https://github.com/poplolioi11/yigua-minecraft)、[0.2.0 下載](https://github.com/poplolioi11/yigua-minecraft/releases/tag/v0.2.0) 與 [問題回報](https://github.com/poplolioi11/yigua-minecraft/issues)。

## 提交平台時的必要註記

以下為發布者使用的提交註記，不需全部貼入玩家介紹。政策核對日期為 2026-10-11；最後以各平台審核結果為準。

### Modrinth

This project uses substantial AI-generated code, assets and page text. Apply the corresponding content disclosures. Because most implementation is generated, request **unlisted** availability rather than claiming eligibility for public discovery. Unlisted acceptance is still subject to review. Do not use generated images as the project icon, banner or gallery artwork. [Official AI policy](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai), [content disclosures](https://support.modrinth.com/en/articles/16567675-content-disclosures).

本專案須勾選 AI 程式碼、素材及文字披露。按大量 AI 製作的實際情況申請 **unlisted**，不宣稱可公開搜尋；是否接受仍由平台判斷。頁面圖示、橫幅與展示圖不得使用影像生成素材，不以改名稱或隱藏披露繞過限制。[官方政策](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai)。

### CurseForge

Use the full AI disclosure above and show actual gameplay. Any AI-edited or generated showcase that could misrepresent the mod must carry a visible disclaimer. Submission and file review are not confirmation of publication. [Official moderation policies](https://support.curseforge.com/support/solutions/articles/9000197279-moderation-policies).

保留完整 AI 製作披露，使用忠實遊戲畫面；若 AI 展示圖可能讓玩家誤認內容，必須清楚標示。送審與檔案上傳不等於已公開。[官方審核政策](https://support.curseforge.com/support/solutions/articles/9000197279-moderation-policies)。

### Nexus Mods

Apply **AI-Generated Content** for the generated mod implementation/assets and **AI Media** for generated page descriptions or other page media. Do not label this project only as limited AI assistance. Include the gameplay requirements, functioning mod file, credits and separate data licenses. [Official submission guidelines](https://help.nexusmods.com/article/28-file-submission-guidelines).

使用 **AI-Generated Content** 與 **AI Media** 標籤，反映模組內容及這份頁面文案的製作方式；不得只標成少量 AI 輔助。附上可用模組檔、環境需求、作者及資料授權。[官方投稿規範](https://help.nexusmods.com/article/28-file-submission-guidelines)。

### GitHub

Publish the reviewed mod source and release artifacts under `poplolioi11/yigua-minecraft`, preserving `LICENSE`, `PROVENANCE.md`, AI disclosure and attribution. After publication, update these drafts with the verified release URL and validation status. GitHub source availability is separate from acceptance by a mod-sharing platform.

GitHub 開源與下載頁不代表其他平台已核准；各平台登入、送審與公開狀態須分開核對。
