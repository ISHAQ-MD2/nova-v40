# NOVA ULTRA v5  (PriyaAI + NOVA PLUS v3 ka milaap)

## Install / Build (Termux)
1. `pkg install unzip && unzip NOVA-ULTRA.zip && cd NOVA-ULTRA`
2. `termux-setup-storage`
3. `bash build-termux.sh`  -> APK: /sdcard/Download/NOVA-ULTRA.apk
4. Purani NOVA par hi install kar do (package same hai). Agar "App not installed" aaye to pehle purani uninstall karo - AB MEMORY NAHI JAYEGI (neeche dekho).

**Termux DNS fix (if `pkg` or `curl` cannot resolve hosts):**
```bash
echo "nameserver 8.8.8.8" > $PREFIX/etc/resolv.conf
```

## Pehli baar
App kholo > Setup: API key, naam, sab permissions (**Files/Gallery access zaroor** - memory backup ke liye) > Accessibility ON.
"Internet / Google server test" button se check karo ke Google server mil raha hai.

## Jo bugs theek kiye
- **"Unable to resolve host generativelanguage.googleapis.com"**: ab phone DNS fail ho to app khud Google/Cloudflare DNS-over-HTTPS use karti hai, 4 baar dobara koshish karti hai, aur saaf Urdu/Hinglish error batati hai (Airplane/Private DNS wagaira).
  Phir bhi na chale to: Settings > Network > Private DNS = Automatic/Off, aur Airplane mode OFF.
- Purana model naam `gemini-3.8-live` hata diya (wo exist hi nahi karta tha). Ab: `gemini-3.1-flash-live-preview`, backup `gemini-2.5-flash-native-audio-preview-12-2025`. Setup mein apna model bhi de sakte ho.
- **Memory bhoolna**: API key badalne se kuch delete nahi hota. Memory + notes + description + reminders ab `/sdcard/NOVA/` mein bhi mirror hote hain; reinstall ke baad app khud wapas le aati hai. Memory limit 30 MB. "Yaad rakhna" wali notes kabhi auto-delete nahi hotin, aur pending reminders har baar NOVA ko dikhte hain.
- Reminders: reboot, app update (MY_PACKAGE_REPLACED) aur reinstall ke baad bhi dobara lag jaate hain. Simple reminder (`notify_user:`) bina internet ke bhi notification + awaaz deta hai.
- Crash hone par wajah `crash.log` mein (app files + /sdcard/NOVA/crash.log).
- Beech call mein net toote to NOVA khud wapas jud jaati hai.

## Naye features
- Text chat box (likh kar NOVA se baat), 📷 camera aur 🖼 gallery se photo bhej kar "isme kya hai?" poochna
- "App khulte hi khud sunna shuru" checkbox
- Naye tools: set_alarm, set_timer, open_settings, navigate (Maps), fetch_page (article summary/translate), get_news (khabar/sports/stocks), read_clipboard
- WhatsApp message/call/photo/video/file/status auto-send (pehle jaisa), naam se contact (Sajid) - description ya phone contacts se

## Zaroori sachchai (honest limits)
- Lock screen par ya screen band hone par app automation (WhatsApp Send dabana) nahi ho sakti.
- Smart home, wearable, travel booking: sirf unki apni app kholkar screen se chalane ki koshish; guarantee nahi.
- Battery optimize: sirf Settings kholti hai, Android khud battery control karne nahi deta.
- Offline/local-only processing: awaaz aur samajh Gemini server par hoti hai (internet chahiye); memory aur contacts sirf phone mein rehte hain.
- Ye code is chat mein compile/test nahi ho saka (yahan Android SDK/net nahi). Build error aaye to `build.log` ka aakhri hissa bhejo.


## v5 - naya kya hai
- **404 model error theek**: scheduled task ab model khud dhoondhta hai (gemini-3.8-flash, 2.5... aur Google ListModels se sabse naya flash). Purana model band ho to agla chalta hai.
- **WhatsApp voice message**: tool `whatsapp_voice`. NOVA apna mic + call-mode band karti hai (isi se "Can't record voice messages during a phone call" aa raha tha), WhatsApp mic dabakar record/send karti hai, phir bolti hai "Sir, voice bhej di". `speak_text` do to NOVA khud bol kar record karwati hai (speaker se, approx).
- **Reminder**: time par phone bolta hai "Sir, reminder time ho gaya", home par aata hai, kaam screen par karta hai (WhatsApp khol kar message), phir home aur "Sir, kaam ho gaya".
- **Floating NOVA button**: green bubble. Tap = bulao/sulao, lamba dabao = screen dekho, kheench kar kahin rakho. Setup mein "Display over other apps" ON zaroori.
- **Sirf "NOVA" bolne par jawab** (checkbox, default ON): baaki waqt Gemini ko kuch nahi jata (data bachta hai). 25 sec chup rehne par khud so jaati hai. Phone ka offline speech pack download karo (Settings > Google > Voice / Offline speech recognition: Hindi + English) warna wake-word ke liye bhi net lagega.
- **Screen sirf zaroorat par**: NOVA ab screen tabhi dekhti hai jab aap kaho ya kaam mein zaroori ho.
- **Girlfriend/boyfriend style baat**, battery 15% se kam aur charge nahi to pyar se yaad dilati hai.
- **Trading analysis**: `analyze_candles` (BTCUSDT jaise crypto pair: EMA, RSI, candle patterns, support/resistance -> UP/DOWN/WAIT), `analyze_chart` (screen ka chart), `watch_candles` / `watch_screen_chart` (har N minute khud, notification + awaaz). Sirf analysis, guarantee nahi, trade khud nahi karti.
- 3D character: ab hai (v6) - neeche dekho.


## v6 - 3D NOVA (naya)
- `assets/avatar.html` (three.js): 3D Girl/Boy. Bolte waqt **hont hilte hain** (lip-sync), hasne par smile, haath hilakar **wave**, aankhen jhapakna, saans, sar hilna.
- **Emotions**: 😀 happy, 😠 angry (chehra laal + sar kaanpta), 😢 sad/emotional (aansu), 😮 surprise, 😍 love, 👋 wave. Avatar par tap karo to random emote.
- **3 dot (⋮)** upar right: API Setup, Profile, emotes, Girl/Boy, Terminal on/off.
- **Terminal** (neeche): Tasks/Reminders (TaskStore), Messages (Memory), Live status.
- Auto emotion ke liye jahan NOVA ka jawab text milta hai wahan `ch.emoteText(text)` bulao.
- Offline 3D: three.js r128 `three.min.js` ko `app/src/main/assets/` mein rakh do. Na ho to internet se load hota hai.
- Ye bhi chat mein compile/test nahi hua. Build error aaye to build.log ka aakhri hissa bhejo.

## v7
- Avatar ab **asli photo** se 3D (parallax depth): hont bolte waqt khulte-band hote hain, smile, aankh jhapakna, gussa (chehra laal + sar kaanpta), udaas (aansu), hairaan, pyar (hearts).
- **Reaction khud**: aapke message aur NOVA ke jawab ke lafzon se emotion chalta hai (Memory.onLog -> avatar).
- 3-dot (⋮) mein sirf **API Setup**. Girl/Boy badalne ke liye avatar par **double-tap**.
- Neeche ka panel saaf: Start/Stop, likhne ka box, aur "Aur options" (checkboxes + Description) kholne par dikhte hain.
- WhatsApp voice: chat khulne ka intezar, zyada button labels, label na mile to message box ke saath mic par fallback, aur wake-word ka mic bhi chhoda jata hai.

## v8
- Chehra kaala dikhne ka bug theek: shader ka pow() negative par NaN deta tha, ab x*x. Photo poori chaudai mein fit (chehra saaf).

## v9
- **WhatsApp voice**: mic dabao -> pakdo -> upar swipe (recording lock) -> bolo/NOVA bole -> Send. NOVA tab poori band (connection, mic, speaker, call-mode, wake-word), phir apne aap chalu. "Can't record ... during a phone call" aaye to OK dabakar audio-mode reset karke ek baar dobara try.
- **Volume slider** (🔉 ━ 🔊): 0-100 phone ki awaaz, 100-200 poori awaaz + NOVA ki awaaz ka boost.
- **Termux screen se step-by-step**: kisi bhi app (Claude/Chrome) mein dikhi command ko "ye command Termux mein run karo" kehne par NOVA read_screen_text se padh kar ek-ek karke termux_run karti hai.

## v10
- Chehre par kaala dhabba theek: 3D depth ki wajah se photo ka hissa camera ke far-plane se kat jata tha (ab limit hata di).
- Neeche teen button: **Only NOVA** (sirf naam lene par), **Start NOVA** (hamesha sunegi), **Off NOVA** (poori band). Stop/Start ab seedhe service band-chalu karte hain.
- Floating **N** button tap = menu: Bulao/Sulao, Screen share, Volume kam/zyada, College mode, Off NOVA.
- **College mode**: sirf "NOVA" bolne par jagegi, ek chhoti line mein jawab.
- **Yaaddaasht**: "yaad rakhna mujhe aam pasand hai / Sajid mera dost hai" -> PASAND:/DOST: notes; Profile screen mein "NOVA ko ye yaad hai" dikhta hai.
- **Backup/Export/Import** (Setup screen): ek JSON file banao, naye app mein Import karo - memory, description, reminders, settings wapas (API key nahi).
- **Games**: PUBG/BGMI/Free Fire kholna, lobby mein madad, screen dekh kar salah. Khud match khelna/character chalana nahi (ban + bahut slow).

## v11
- Chehra: library ke bina seedha WebGL (internet ke bina bhi dikhta hai). Muh kam khulta hai, nakli aansu hata diye, tilt/skew kam.
- WhatsApp voice: mic dabake rakho (swipe nahi), screen se confirm ki record chal rahi hai, 3 koshish, phir bolo aur chhodo. Jhooth nahi: tool result "OK/FAIL" batata hai.
- Neeche Live tab mein reminders + voice message ka text dikhta hai. Msgs tab mein chat.
- Mic ON/OFF aur NOVA awaaz ON/OFF buttons (aur floating menu mein). Mic OFF = awaaz NOVA tak nahi jati.
- Ads: YouTube Skip / "Skip ad" / "Close ad" apne aap dabta hai (checkbox se band). skip_ad tool bhi.
- hold_tap: kisi jagah N second ungli dabaye rakho. Screen share button hata diya - NOVA khud look_screen chalati hai.
- Naye tools: calendar_events / calendar_add, email_compose, show_emotion (awaaz ke tone par chehra badle).

## v12 - HUD UI + reminder fix
- **Reminder sirf 1 baar**: ek hi waqt+text ka reminder dobara add nahi hota, bajne ke baad 2 minute tak dobara nahi bajta, aur daily tabhi jab aap "roz/har din" kahen.
- **UI**: neon cyan HUD (Good evening header, command card, System/Network/Battery cards, neeche nav: Home / Terminal / Orb / Offline / Setup).
- **Offline baat** (nav mein 📴): internet ke bina waqt, tareekh, battery, volume, salam. Phone ka offline Urdu/Hindi speech pack chahiye.
- **Clipboard**: read 3 baar try, copy ke baad verify, khali ho to saaf hidayat.
- **Ishaq profile** (Urdu only, namaz, Quran, memory rules, error rules) Persona mein jod diya.
- Voice cloning: is version mein NAHI (phone par mumkin nahi; bahar ki paid service chahiye).
- Multi-device sync: abhi Backup Export/Import file se. Calendar/Email: pehle se (calendar_events, calendar_add, email_compose).
- Compile/test yahan nahi ho saka. Build error aaye to build.log ka aakhri hissa bhejo.

---
## v13 changes
- Native UI (no HTML): human-style animated face (CharacterView), bottom bar = only Start/Stop, Mute NOVA, Mute Mic; everything else in the 3-dot menu.
- RobotPet: small overlay robot (camera spot / wandering / girl mode), lip-syncs with NOVA, pops up on reminders & azan.
- Screen lock: PIN saved encrypted; `unlock_screen` / `lock_screen` tools; reminders auto-wake + unlock.
- Reminders: no repeat sends (agent stops after a successful WhatsApp/call), expired reminders auto-deleted.
- Prayer times offline for Dera Ghazi Khan (Karachi method, Hanafi Asr) + azan alert.
- NOVA clipboard (ClipStore), local music player + playlists (MusicPlayer), backup to Download/NOVA.
- NOT compiled in the authoring environment - run `./gradlew assembleDebug` and report any error.

## v13.1 - Offline (Siri-style)
- OfflineIntent: commands without internet or model (open app, call, WhatsApp msg/voice, reminders, music, azan, torch, screen text, games).
- OfflineBrain: on-device LLM via MediaPipe tasks-genai. Put a Gemma-3 1B `.task` file in `/sdcard/NOVA/models/` (or Download/).
- Start NOVA auto-switches to offline when there is no internet.
- Reminders created offline use `do:` instructions and run without Gemini.

## v14
- New native UI (reference style): image character (hair sway only), falling petals/leaves/sparks, 5 real info cards
  (Weather via Open-Meteo, Time, Azan, Music, Reminder), camera / mic / chat + mute buttons, side drawer.
- 13 themes in res/drawable-nodpi (theme_tXX.jpg / thumb_tXX.jpg); pick from the palette button.
- Teach: "yaad rakho ki ..." then ask later; chat log older than 3 days auto-removed, notes kept.
- Offline model: put Qwen2.5-1.5B ...q8_ekv1280.task in Download/ (needs All-files access) and check menu > Settings > Offline AI.
- Not compiled by the author - build with `./gradlew assembleDebug` and send any error.

## v15
- Full-screen wallpaper UI (13 themes): wallpaper + girl cutout (hair sway) + live Jarvis HUD (rings, radar, data columns, real battery/time/status).
- Background blur / girl blur sliders (Theme dialog). Buttons & cards animate continuously.
- /storage/emulated/0/NOVA-FILES: backups/, exports/, chat_history.txt, model (.task) search path.
- Chat History viewer (permanent log, works offline).
- Offline: Urdu/Hindi script commands, offline translate (ML Kit), Urdu pipeline (translate -> Qwen -> translate), wake word 'NOVA',
  OfflineService (background), speech-language fallback with visible errors, offline voice picker.
- ClipGrabActivity for background clipboard capture. Not compiled by the author.

## v16
- Clean wallpapers only (no baked buttons): 8 girl + 9 robot pictures. Add your own pictures (girl or any background) from Settings; saved inside the app and in NOVA-FILES/wallpapers.
- Android LIVE WALLPAPER (NovaWallpaperService): same picture + hair sway + Jarvis rings on home/lock screen; style follows the app's choice.
- New live Settings panel (animated cards, toggles). Offline voice loop fixed (heartbeat, model preload, instant replies). Not compiled by the author.

## v17
- Reminder/task fix: tasks wait for unlock (PendingTasks + USER_PRESENT), AgentRunner finishes only on TASK_DONE, stops on repeated app opens / 4 min,
  failed tasks get a Retry notification + Failed Tasks list.
- copy_link_send tool (Share -> Copy link -> WhatsApp), many extra offline commands (timer, alarm, calculator, nav, settings...).
- Depth clock wallpaper (time behind the girl), auto cutout for your own pictures (ML Kit selfie segmentation), Animation Studio (layers/presets),
  Home/Lock static wallpaper separately + live wallpaper, UI Edit mode (size/position/hide/front-back), Macros (add features inside the APK),
  new launcher icon. Not compiled by the author.

## v18
- Build fixes: duplicate `object Net` removed (Net.kt is the only one), `&` -> `and`; import audit passed.
- Memory: auto-learns "mera naam X hai" / "meri ... X hai", answers "mera naam kya hai"; offline paths are crash-safe (background thread crashes no longer close the app).
- Voice output through the MEDIA stream (volume keys work, Bluetooth gets the sound). Optional custom AI voice (ElevenLabs) for reminders/offline replies.
- youtube_play (auto-plays the best non-ad match), OfflineAgent (reminders still run when the net is off), Terminal (Termux output inside NOVA),
  Code Studio (AI writes -> runs -> auto-fixes -> LAN link / WhatsApp), Decode/Encrypt (base64/zlib/hex layers, AES-GCM vault), Fast mode.
- Not compiled by the author.

## v30 (is update)
- **OFFLINE MODE PURI TARAH NIKAAL DI** - sab kaam ab seedha live Gemini/DeepSeek/Claude se; dead `OfflineService` manifest entry bhi hata di.
- **Live hacker wallpaper (naya `HackerWall.kt`)**: 21 live widgets (status, clock, battery, storage, RAM, net, CPU, sensors, reminders, namaz, voice text,
  live event feed, nayi files ka naam+size, clipboard, Termux output, WiFi+data, agla calendar event, NOVA stats, last action, matrix rain, touch-worms).
  Sab asli app data se (`WallBus`/`WallData`), koi fake/static nahi. Settings > NOVA V30 FEATURES > "Wallpaper widgets" se har widget ON/OFF kar sakte ho.
  Ungli se chhuo to worm particles follow karte hain (Fish Wallpaper jaisa effect).
- **WhatsApp voice - 2 modes**: `whatsapp_voice(who, mode, ...)`.
  - `mode='real'`: aap khud boltay ho, NOVA WhatsApp ka asli mic button **hold** karti hai (swipe/tap nahi) - ab kisi pehle se aayi hui voice bubble par galti se tap nahi hoti.
  - `mode='tts'`: NOVA apni awaaz ek FILE mein bana kar (naya `TtsFile.kt`) WhatsApp par attachment ki tarah bhejti hai - mic bilkul nahi chhuti.
  - Group/naam se chat kholna (`openChatByName`) theek kiya - ab group ki PHOTO/INFO nahi, seedha CHAT khulti hai. Same fallback `whatsapp_call` mein bhi.
- **Share/Copy-link (`copy_link_send`)**: ab purana clipboard pehle note karke rakhte hain, Copy ke baad 3 second tak check karte hain ke NAYA link aaya ya nahi -
  purana (cached) link kabhi dobara nahi bheja jata; na mile to saaf FAIL bolti hai, chup chaap purana nahi bhejti.
- **Clipboard link auto-download (naya `ClipWatch.kt`)**: TikTok/YouTube/Instagram/Facebook link copy karte hi (VidMate jaisa) notification deti hai, Termux ke
  yt-dlp se download karti hai - hamesha NAYA link pakadti hai.
- **Code sirf DeepSeek/Claude se (naya `AiCode.kt`)**: `code_task` ab Gemini use nahi karta - naam bolo to wahi app khulti hai, warna default DeepSeek.
  Code `NOVA-FILES/code` mein backup hota hai. `deploy_code` (naya `DeployHub` object) Termux ke zariye GitHub par push aur Vercel/Netlify par live deploy
  karke URL wapas deta hai.
- **Tone**: apni cybersecurity-student ke normal coding/tool requests par NOVA ab "illegal" nahi kehti ya lecture nahi deti - bas jo manga wo banati hai
  (Persona.kt mein naya instruction; ye sirf NOVA ki apni baat-cheet ka tareeqa hai, DeepSeek/Claude khud jo allow karte hain wo nahi badalta).
- **5 minute ke baad chup ho jana - fix**: `LiveClient.kt` mein naya connection watchdog - agar user bol raha ho aur server 20 second chup rahe to khud
  reconnect kar leti hai; session-resumption bhi add kiya.
- **Reminders reliable**: `AlarmManager.setAlarmClock()` (sab se pakka tareeqa) pehle try hota hai, plus naya `Guard.kt` watchdog jo miss hui reminder ko
  khud dobara fire karta hai.
- **Scheduled task + live baat-cheet ka takrao - fix**: naya `Busy.kt` - scheduled task chalte waqt live voice session ek pal ke liye pause ho jata hai,
  phir khud resume, isliye NOVA "confuse" ho kar band nahi hoti.
- **2nd API key (naya `Keys.kt`)**: Settings > NOVA V30 FEATURES > "2nd Gemini API key" se lagao - pehli key ka quota (429) khatam hote hi khud dusri par switch.
- Sab naye settings ek jagah: Settings > **NOVA V30 FEATURES** section (naya `FeatureUi.kt`).
- **Aagay se sirf chhoti files badalni hain**: har naya feature apni ALAG file mein hai (`HackerWall.kt`, `TtsFile.kt`, `AiCode.kt`, `ClipWatch.kt`, `Keys.kt`,
  `Guard.kt`, `Busy.kt`, `FeatureUi.kt`...) - agli dafa koi aur feature chahiye ho to poori zip dobara nahi, bas usi feature ki file replace karni hogi.
- Not compiled by the author (no Android SDK/network here) - checked with a standalone Kotlin structural compile for duplicate/unresolved-symbol errors;
  run `./gradlew assembleDebug` and send any error.

## v30.1 (chhota update)
- Wallpaper CLOCK STYLE: Settings > NOVA V30 FEATURES > "Wallpaper clock style" - 5 options: normal grid, center (bada, alag font),
  upar-left corner (chhota, alag font), upar-right corner (chhota, alag font), ya clock bilkul nahi.
- Naya "3D code-face" wallpaper layer: hex/code characters (0-9A-F) se bana chehra (sphere jaisi shading + aankhein/muskan) - Settings >
  Wallpaper widgets > "3D code-face" se ON/OFF.
- DeepSeek ki ASLI developer API key (naya `DeepSeekRest.kt`) - Settings > NOVA V30 FEATURES > "DeepSeek API key": lagane ke baad
  code_task DeepSeek app khole/type kiye bina seedha HTTP se jawab laata hai (zyada fast/reliable); key na ho to purana UI-automation
  tareeqa chalta rehta hai.
- `.github/workflows/build-apk.yml` add kiya (pehli zip mein zip-command ki galti se reh gaya tha - `.github` folder `.git` exclude
  pattern se galti se match ho raha tha).


## v36 - NOVA BROWSER (naya: NOVA ka apna asli browser)
NOVA ab Chrome / YouTube app nahi kholti - apne **andar ke browser** (WebView) mein kaam karti hai. Aap bhi use Chrome ki tarah chala sakte ho.

**Kya mil gaya**
- **Gaana/video**: "palpal song play karo" -> `youtube_play` -> NOVA browser khud `m.youtube.com` kholta hai, search karta hai, ad skip karta hai, pehla sahi result play karta hai. YouTube app / Chrome nahi khulta.
- **Game khelte waqt**: browser *chhupa hua* (2x2 overlay) background mein chalta hai, gaana chalta rehta hai. Foreground notification "NOVA Browser chal raha hai" se band bhi kar sakte ho.
- **Floating window**: Settings > "Floating browser window" ON (ya NOVA se kaho "browser floating karo"). Kisi bhi app/game ke upar tairti window; title bar se kheencho (jagah), neeche-right kone `◢` se size, buttons `⌨` typing ON/OFF, `▁` chhupao, `⤢` poori screen, `✕` band. NOVA jo browser mein kar rahi hai wo isi mein live dikhta hai.
- **Wallpaper par live**: Settings > "Wallpaper par browser dikhao" ON - NOVA browser mein jo kar rahi hai uski halki live tasveer hacker wallpaper par (browser khula ho tabhi dikhti hai).
- **Download**: MediaFire / Google Drive / direct link do -> `browser_download` link khud kholta hai, Download button khud dabata hai (Drive ka "Download anyway" bhi), file **Download/NOVA-Downloads** mein aati hai. blob:/data: downloads bhi (60 MB tak). Long-press se link/image bhi download.
- **Login hamesha save**: Google, GitHub, kuch bhi - ek baar browser mein khud login karo, cookies save rehti hain. NOVA aapka password kabhi nahi maangti/likhti.
- **2 profile ("2 new")**: *Mera profile* (aapke apne accounts) aur *Nova ka profile* (NOVA ke alag accounts, jaise alag GitHub). Browser menu > Profile ya `browser_profile`. Badalne par tabs band hote hain taake login mix na hon. (Agar aap ka matlab kuch aur tha to batao.)
- **GitHub / kisi bhi site par kaam**: tools `browser_open / read / click / type / scroll / nav / upload / tabs / window / look / status / media`. NOVA page padhti hai (`[number]` wali list), click/type karti hai, phir dobara padh kar check karti hai. Upload: `browser_upload(file)` + "choose your files" click. Delete/edit: file page > "..." / pencil > Commit changes.
- **Chrome jaisa**: tabs, URL bar + Google search, back/forward/reload, bookmarks, history, desktop site, fullscreen video, file upload, popup block, long-press menu, "Open with" mein NOVA Browser (http/https links).
- Scheduled tasks (AgentRunner) bhi browser tools use karte hain.

**Settings**: NOVA V30 FEATURES section mein upar naye rows (browser kholo, links/YouTube browser mein, ad skip, floating, overlay permission, profile, wallpaper, download folder, data saaf).
**Zaroori**: floating + background gaane ke liye "Display over other apps" ON hona chahiye (Setup).

**Honest limits**
- **Google login** kabhi kabhi app ke browser mein block hota hai ("This browser or app may not be secure") - naye WebView mein X-Requested-With header chhupaya nahi ja sakta. GitHub aur zyada tar sites theek. Browser menu > "Google login madad".
- Screen band hone par WebView video/awaaz Android rok sakta hai; "game ke saath background gaana" screen ON rehte hue pakka chalta hai.
- Wallpaper par video wali jagah kaali ho sakti hai (page ka baaki hissa dikhta hai).
- Floating window mein typing ke liye window focus leti hai (page par touch par khud, 20 sec baad wapas) - us dauran game ka back button kaam nahi karega.
- Extensions, password manager, Chrome sync, DRM wali sites nahi.
- GitHub par bade kaam (poora project push) ke liye `deploy_code` (Termux) zyada pakka hai; browser tools chhote kaam (file upload/delete/edit) ke liye.
- Ye code is chat mein compile/test nahi ho saka (Android SDK nahi); Kotlin syntax check pass hai. Build error aaye to `build.log` ka aakhri hissa bhejo.
- androidx.webkit 1.12.1 pin hai (1.15+ ko compileSdk 35 chahiye).
