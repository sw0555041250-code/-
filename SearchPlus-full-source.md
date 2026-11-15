# חיפוש פלוס – כל קוד המקור בקובץ אחד

> נוצר אוטומטית מהמאגר. לפרטים, החלטות ומה פתוח – ראו `HANDOFF.md` (גם הוא כלול כאן).


## `.github/workflows/build-apk.yml`

```yaml
name: Build Search Plus APK

on:
  push:
    branches: [main, 'claude/**']
    paths-ignore: ['**.md', 'docs/**', 'tools/**', 'core/src/test/**', '.github/workflows/test-core.yml']
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'

      - name: Bundle the Hebrew OCR model inside the APK
        run: |
          mkdir -p app/src/main/assets/tessdata
          curl -fsSL -o app/src/main/assets/tessdata/heb.traineddata https://github.com/tesseract-ocr/tessdata_fast/raw/main/heb.traineddata
          ls -la app/src/main/assets/tessdata

      - name: Version number
        run: echo "VER=$(( ${{ github.run_number }} + 200 ))" >> $GITHUB_ENV

      - name: Build release APK
        run: |
          set -o pipefail
          gradle :app:assembleRelease --no-daemon --stacktrace 2>&1 | tee build.log
          cp app/build/outputs/apk/release/app-release.apk SearchPlus.apk
          ls -la SearchPlus.apk

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: SearchPlus-apk
          path: SearchPlus.apk

      - name: Publish release
        if: github.ref == 'refs/heads/main'
        uses: softprops/action-gh-release@v2
        with:
          tag_name: v1.0.${{ env.VER }}
          name: חיפוש פלוס 1.0.${{ env.VER }}
          body: |
            קובץ ההתקנה של חיפוש פלוס – חיפוש מעמיק בתוך ספרי PDF במכשיר.
            הורידו את SearchPlus.apk והתקינו. מי שכבר מותקן אצלו – העדכון נשמר על האינדקס וההגדרות.
            ${{ github.event.head_commit.message }}
          files: SearchPlus.apk
          make_latest: true

      - name: Show compile errors
        if: failure()
        run: |
          grep -E "^e: |error:|What went wrong" -A2 build.log | head -80 || true

      - name: Publish build log on failure
        if: failure() && github.ref == 'refs/heads/main'
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release delete build-log -y --cleanup-tag || true
          (grep -E "^e: |error:|FAILURE|What went wrong" -A3 build.log | head -c 50000; echo; echo ----; tail -c 20000 build.log) > build-log.txt 2>/dev/null || echo "no log" > build-log.txt
          gh release create build-log build-log.txt --prerelease --title "build log" --notes "failed build log"
```

## `.github/workflows/make-media.yml`

```yaml
name: Make guide PDF and video

on:
  push:
    branches: [main]
    paths: ['docs/video/**', 'docs/guide/**', '.github/workflows/make-media.yml']
  workflow_dispatch:

permissions:
  contents: write

jobs:
  media:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-python@v5
        with:
          python-version: '3.11'

      - uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Tools
        run: |
          sudo apt-get install -y -qq ffmpeg > /dev/null
          pip install -q edge-tts
          npm init -y > /dev/null
          npm install playwright@1.47.2 > /dev/null
          npx playwright install --with-deps chromium > /dev/null

      - name: Narration (Hebrew voice)
        run: python docs/video/make_audio.py out

      - name: Record the screens
        run: |
          cp -r node_modules docs/video/ 2>/dev/null || true
          node docs/video/record.js out/durations.json out

      - name: Video = screens + narration
        run: |
          LEAD=$(cat out/lead_ms.txt)
          WEBM=$(ls out/*.webm | head -1)
          ffmpeg -v error -y -ss $(python -c "print($LEAD/1000)") -i "$WEBM" -i out/narration.wav \
            -c:v libx264 -preset slow -crf 20 -pix_fmt yuv420p -r 30 -c:a aac -b:a 160k -shortest -movflags +faststart SearchPlus-Video.mp4
          ls -la SearchPlus-Video.mp4

      - name: Guide PDF
        run: |
          cat > pdf.js <<'EOF'
          const { chromium } = require('playwright');
          (async () => {
            const b = await chromium.launch();
            const p = await b.newPage();
            await p.goto('file://' + process.cwd() + '/docs/guide/guide.html');
            await p.evaluate(() => document.fonts.ready);
            await p.pdf({ path: 'SearchPlus-Guide.pdf', format: 'A4', printBackground: true, preferCSSPageSize: true });
            await b.close();
          })();
          EOF
          node pdf.js

      - uses: actions/upload-artifact@v4
        with:
          name: media
          path: |
            SearchPlus-Video.mp4
            SearchPlus-Guide.pdf

      - name: Publish (separate from the app updates – not "latest")
        uses: softprops/action-gh-release@v2
        with:
          tag_name: media
          name: מדריך וסרטון – חיפוש פלוס
          body: |
            📄 SearchPlus-Guide.pdf – מדריך מלא לתוכנה
            🎬 SearchPlus-Video.mp4 – סרטון הסבר
            להורדת התוכנה: https://github.com/sw0555041250-code/Search-Plus-/releases/latest/download/SearchPlus.apk
          files: |
            SearchPlus-Video.mp4
            SearchPlus-Guide.pdf
          prerelease: true
          make_latest: false
```

## `.github/workflows/site.yml`

```yaml
name: Website (GitHub Pages)

on:
  push:
    branches: [main]
    paths: ['docs/site/**', 'docs/guide/fonts/**', '.github/workflows/site.yml']
  workflow_run:
    workflows: ['Make guide PDF and video']
    types: [completed]
  workflow_dispatch:

permissions:
  contents: write

concurrency:
  group: site
  cancel-in-progress: true

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Build the site
        run: |
          mkdir -p _site/fonts
          cp docs/site/index.html _site/
          cp docs/guide/fonts/*.woff2 _site/fonts/
          cp docs/icon.png _site/icon.png
          # the video and a poster frame, from the media release
          curl -sSL -f -o _site/SearchPlus-Video.mp4 https://github.com/sw0555041250-code/Search-Plus-/releases/download/media/SearchPlus-Video.mp4 || true
          if [ -s _site/SearchPlus-Video.mp4 ]; then sudo apt-get install -y -qq ffmpeg > /dev/null && ffmpeg -v error -y -ss 30 -i _site/SearchPlus-Video.mp4 -frames:v 1 -q:v 3 _site/poster.jpg; fi
          ls -la _site

      # the site goes to the gh-pages branch; in Settings › Pages choose "Deploy from a branch" › gh-pages › Save
      - uses: peaceiris/actions-gh-pages@v4
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: ./_site
          publish_branch: gh-pages
          force_orphan: true
```

## `.github/workflows/test-core.yml`

```yaml
name: Test core (speech, meaning, references)

on:
  push:
    paths: ['core/**', 'tools/**', '.github/workflows/test-core.yml']
  workflow_dispatch:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: actions/setup-python@v5
        with:
          python-version: '3.11'

      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'

      - name: Cache models
        uses: actions/cache@v4
        with:
          path: models/_hf
          key: models-v3

      - name: Download models and make reference values
        run: |
          sudo apt-get install -y -qq ffmpeg > /dev/null
          pip install -q numpy huggingface_hub tokenizers onnxruntime edge-tts
          python tools/make_reference.py models 2>&1 | tee models_log.txt

      - name: Run tests
        env:
          MODELS_DIR: ${{ github.workspace }}/models
        run: |
          # only the core module (no Android SDK needed)
          sed -i 's/^include(":app")$//' settings.gradle.kts
          gradle :core:test --no-daemon --stacktrace

      - name: Summary (results of the model tests)
        if: always()
        run: |
          for f in core/build/test-results/test/*.xml; do
            grep -h -E "cos=|understood|\] (OK|BAD)|quiet|peak|good=|load |failure message|skipped|AssertionError|Exception" "$f" | sed 's/&quot;/"/g; s/&lt;/</g; s/&gt;/>/g' | cut -c1-300 | head -60
          done
          grep -h -E "bge|skipped" models_log.txt 2>/dev/null || true
```

## `HANDOFF.md`

```markdown
# חיפוש פלוס – כל מה שצריך לדעת כדי להמשיך

> **ל-Claude Code / למפתח שממשיך:** קרא את כל המסמך לפני שאתה נוגע בקוד. יש בו את הרקע, ההחלטות ולמה התקבלו, מה נבדק ומה לא,
> מה עוד פתוח, ואיך לעבוד מול בעל הפרויקט. משפט הפתיחה המומלץ: "קרא את HANDOFF.md והמשך מכאן: <מה רוצים>".

---

## 1. מה זה, ולמי

**חיפוש פלוס** – אפליקציית אנדרואיד שמחפשת בתוך כל ספרי ה-PDF שבמכשיר (זיכרון פנימי, כרטיס זיכרון, וגם ספרים שהובאו מ-Google Drive):
- **חיפוש מילים** בתוך כל העמודים (FTS4), עם אותיות שימוש, ביטוי מדויק, כוכבית, מיקוד לפי תיקיות.
- **קפיצה למקום** – "ברכות דף כ״ז ע״א", "לקו״ש חלק כ״ד עמוד 358", "שו״ע אדה״ז סימן רע״ח" – בכתיבה (מעבר מהיר) או בקול.
- **חיפוש לפי משמעות / שאלה ותשובה** – מודל AI בתוך המכשיר (e5-small), ואופציונלי מודל הבנה עמוק (bge-m3).
- **חיפוש קולי** – Whisper בתוך המכשיר (בלי אינטרנט), או שירות הדיבור של המכשיר.
- **קורא מובנה** שנפתח בעמוד ומסמן את המילים (גם בספרים סרוקים, דרך OCR), או פתיחה ב-ReadEra וכל קורא אחר.
- סידור המאגר (כפולים, סידור תיקיות, שמות חכמים), ספרים מ-Google Drive, רעיונות יומיים לחיפוש, ערכות נושא.

**בעל הפרויקט:** שניאור וילהלם. לא מתכנת – מתאר בעברית מה הוא רוצה (לרוב בהקלטה מתומללת, עם שגיאות תמלול), בודק על המכשיר ומחזיר צילומי מסך.
**המכשיר שלו:** BOOX Go 6 – מסך E-ink שחור-לבן, Android 11, **2GB RAM**, אחסון גדול (כרטיס 256GB), קושחה 4.2, NeoReader V3.0.
יש לו **ReadEra** (הקורא שהוא אוהב) ואפליקציית פרדס. **כ-12,000 קבצי PDF** (ספריית חב״ד: לקוטי שיחות, אגרות קודש, תורת מנחם, ש״ס בכמה מהדורות, שו״ע אדה״ז…).
**הקהל הרחב:** כל מי שיש לו ספרייה תורנית ב-PDF – BOOX, טלפון, טאבלט.

**בקשות קבועות של בעל הפרויקט (לשמור!):**
- עברית בכל מקום, ניסוח חם וידידותי ("✓ מצאתי לך 3 אפשרויות – באיזו לפתוח?"), לא רק "מחפש…" – תמיד להראות התקדמות.
- **"יחי אדוננו מורנו ורבנו מלך המשיח לעולם ועד"** מופיע: באודות (בתחתית ההגדרות), בתחתית התפריט הצדדי, בתחתית מסך הבית, במדריך, בדף הנחיתה, במודעה ובסרטון.
- **לא** לכתוב "לקראת הגאולה" (ביקש להוריד).
- **לא** תכונת יידיש (ביקש להסיר לגמרי). **לא** מסך מילון ראשי תיבות (ביקש להסיר; ראשי התיבות נשארו רק בתוך מנוע החיפוש).
- מייל ליצירת קשר: `sw0555041250@gmail.com` (כך הוא כתב "s.w.0555041250").
- הוא אוהב שמוסיפים יוזמות ("תגדיל ראש"), אבל הכי חשוב לו: **שהחיפוש המהיר והקולי באמת מגיעים למקום**, ושהתוכנה לא נתקעת ולא נסגרת.

---

## 2. מאגרים, קישורים וגרסאות

- **המאגר הפעיל:** https://github.com/sw0555041250-code/Search-Plus- (פומבי). השם בעברית "חיפוש פלוס" הפך אצל GitHub ל-`Search-Plus-`.
- **הורדה (תמיד האחרונה):** https://github.com/sw0555041250-code/Search-Plus-/releases/latest/download/SearchPlus.apk
- **מדריך / סרטון:** Release בשם `media` (prerelease, לא משפיע על העדכונים):
  `.../releases/download/media/SearchPlus-Guide.pdf`, `.../releases/download/media/SearchPlus-Video.mp4`
- **דף נחיתה פעיל:** https://claude.ai/artifact/JvCXrKjW65UB8FB3ymjUMs (Artifact של claude.ai, משותף "לכל מי שיש קישור").
  המקור שלו = `docs/site/index.html` אחרי התאמה (ראה סעיף 9).
- **היסטוריה:** הפרויקט התחיל ב-`chdv770-ctrl/Search-Plus` (גרסאות עד 1.0.4), עבר ל-`sw0555041250-code/-` (עד 1.0.106), ומשם ל-`Search-Plus-`.
  המאגרים הישנים לא בשימוש. מי שהתקין מהם צריך להתקין פעם אחת ידנית מהקישור החדש (העדכון האוטומטי בודק רק את המאגר שממנו הותקן).
- **גרסה נוכחית בזמן כתיבת המסמך:** 1.0.230.

---

## 3. בנייה, פרסום ובדיקות

- **אין Android Studio.** כל push ל-`main` מפעיל `.github/workflows/build-apk.yml`: מוריד את `heb.traineddata` (OCR) לנכסים, בונה APK חתום, ומפרסם Release `v1.0.<run+200>` עם `SearchPlus.apk` כ-latest.
- **גרסה:** `versionCode = GITHUB_RUN_NUMBER + 200`, `versionName = 1.0.<אותו מספר>` – ה-+200 כדי שכל גרסה חדשה תהיה גבוהה מגרסאות המאגרים הקודמים. לא לשנות.
- **חתימה:** `app/searchplus.p12` (PKCS12), סיסמה `searchplus123`, alias `searchplus`. **אסור להחליף** – אחרת עדכונים לא יותקנו מעל.
  SHA-1: `71:8B:76:ED:F4:A4:4E:34:9B:F6:19:A4:98:C4:C2:8F:53:63:4B:D4` (נחוץ לחיבור Google Drive).
- **ענפים:** push לענף `claude/**` בונה APK לבדיקה בלבד (בלי Release). רק `main` מפרסם. דרך העבודה עד היום: עובדים על `claude/voice-search-fixes-w4dhmg`, ו-fast-forward ל-`main` כשמוכן.
- **שגיאות קומפילציה:** בסוף ריצה שנכשלה יש שלב "Show compile errors" שמדפיס את שורות `e:`.
- **בדיקות `core`** (`.github/workflows/test-core.yml`, רץ כשמשנים `core/`, `tools/`):
  `tools/make_reference.py` מוריד את המודלים האמיתיים (Whisper base/small, e5-small, bge-m3), מייצר ערכי ייחוס בספריות Python הרשמיות
  והקלטות קול עבריות (edge-tts, he-IL-AvriNeural), ו-`gradle :core:test` משווה אליהם. בסוף יש שלב "Summary" שמדפיס את התוצאות (מה Whisper שמע, cos של e5/bge, זמנים).
- **ABI:** `arm64-v8a` + `armeabi-v7a` (נוסף כי ייתכן שה-BOOX רץ במצב 32 ביט). APK ≈ 31MB.
- **ספריות:** androidx core/appcompat/recyclerview, `com.tom-roush:pdfbox-android`, `com.microsoft.onnxruntime:onnxruntime-android:1.19.2`,
  `cz.adaptech.tesseract4android` (JitPack), `com.google.android.gms:play-services-auth` (חיבור Drive).
- **מדיה:** `.github/workflows/make-media.yml` – בונה מחדש את המדריך (PDF מ-`docs/guide/guide.html` ב-Playwright) והסרטון
  (`docs/video/`: `script.json` = תסריט וקריינות, `video.html` = סצנות, `make_audio.py` = קריינות edge-tts, `record.js` = הקלטת מסך) ומעלה ל-Release `media`.
- **אתר GitHub Pages:** `.github/workflows/site.yml` דוחף את `docs/site` לענף `gh-pages`. **עדיין לא עובד** – בעל המאגר צריך:
  Settings › Pages › Source: **Deploy from a branch** › `gh-pages` › `/(root)` › Save. עד אז משתמשים ב-Artifact (סעיף 2).

### עבודה בסביבה בלי Android SDK (כמו בסשן הקודם)
- `core` נבדק מקומית עם `kotlinc` + JUnit מ-Gradle + `onnxruntime-1.19.2.jar` (מ-Maven Central).
- את קוד האפליקציה אפשר לבדוק לקומפילציה בלי SDK: `kotlinc` מול `org.robolectric:android-all:11-robolectric-6757853` (android.jar של API 30),
  `pdfbox-android` (classes.jar מה-aar), ו-stubs קטנים ל-androidx/RecyclerView/tesseract/gms. זה תופס כמעט את כל השגיאות לפני push.
- HuggingFace חסום ברשת של הסשן – את המודלים בודקים רק ב-CI.

---

## 4. מבנה הקוד

כל הממשק בנוי בקוד (בלי XML layouts), RTL. חבילה: `com.chdv.searchplus`.

### `core/` – Kotlin טהור (נבדק בלי אנדרואיד)
| קובץ | תפקיד |
|---|---|
| `HebRef.kt` | `HebNum` (גימטריה, שמות אותיות "כף זין"/"חבזין", מספרים במילים), `RefParser` (גמרא דף/עמוד, ליקוטי שיחות חלק/פרשה/שיחה/עמוד, פרק/סימן/סעיף; התאמה פונטית למסכתות), `Works` (שמות חלופיים לספרים), `PageFinder` (סימני דף בראש העמוד, מספרי עמודים מודפסים, כותרות, מספר כרך משם קובץ) |
| `Abbr.kt` | מילון ראשי תיבות (אדה״ז, צ״צ, לקו״ש, אג״ק, שליט״א…) – `expand()` לבקשות ולשמות תיקיות |
| `Names.kt` | **מנוע ההתאמה לשמות הספרייה**: לכל ספר – מילות השם ומילות התיקיות (עם ראשי תיבות פתוחים וכתיב חלופי). `find(request, ref)` מחזיר את הספרים המתאימים. נבדק על ספרייה מדומה של 12,000 ספרים: ~10ms לבקשה |
| `Whisper.kt` | `WhisperMel` (זהה ל-OpenAI), `WhisperTokenizer`, `Whisper` (encoder + merged decoder עם KV-cache; `lowMemory` = טוען חצי-חצי), `E5Tokenizer`/`E5Model` (גם bge-m3 עם `cls=true`) |
| `Json.kt` | קורא JSON קטן + קריאה זורמת של tokenizer.json |

### `app/`
| קובץ | תפקיד |
|---|---|
| `Ui.kt` | `App` (+ שמירת קריסות, שחרור מודלים בלחץ זיכרון), `Prefs`, `Col` (ערכות נושא: auto/blue/green/purple/warm/eink; E-ink מזוהה לפי ONYX/BOOX), עזרי UI, `BaseActivity`, `Sheet`, `hebrewDate()` |
| `MainActivity.kt` | מסך הבית/חיפוש: מסך "לומד את הספרייה" בסריקה הראשונה, ברכה + תאריך עברי, מיקרופון גדול, אריחי כל המסכים, "על מה נחפש היום?" (`Topics`), "להמשיך מאיפה שעצרת", תוצאות עם קטגוריות; מצבי מסך `screen=meaning|words|home`; אבחון סגירה אחרונה (ApplicationExitInfo) |
| `Nav.kt` | התפריט הצדדי (☰ / לחיצה על השם): מעבר מהיר, קולי, שאלה ותשובה, מילים | עיון, סידור, Drive, הגדרות | עדכונים, עזרה |
| `VoiceActivity.kt` | מסך קולי **ו**מסך "⚡ מעבר מהיר" (`mode=type`): שמעתי › הבנתי › אפשרויות ממוספרות ①②③; חיפוש חי בזמן הקלדה (בקשה ישנה נזרקת); התקדמות שלב-שלב; "אולי התכוונת ל…"; "אחרונים" |
| `Jump.kt` | בקשה → ספרים (`Catalog`) → המקום בכל ספר (`locate`: סימני דף, עמוד מודפס, פרשה, פרק/סימן, תיקון משתמש `dafCal`) |
| `Catalog.kt` | עוטף את `core/Names` על ספרי ה-DB; נבנה ברקע בפתיחה ואחרי כל שינוי בספרייה (בלי לקרוא עמודים!) |
| `Voice.kt` | `VoiceModels` (base "מהיר" / small "מדויק", נשמרים באחסון עם הכי הרבה מקום), `Recorder`, `VoiceSearch` (מנוע: Whisper כברירת מחדל, או שירות המכשיר; טיפול בכישלון) |
| `VoiceService.kt` | Whisper רץ בתהליך נפרד `:voice`, כשירות חזית וקשור למסך הגלוי (לא נהרג ראשון); `lowMemory` ב-<3GB; `VoiceRemote` מזהה מוות של התהליך |
| `Semantic.kt` | e5-small, אינדקס `vectors.bin`, חיפוש היברידי (אינדקס + השוואה במקום של עמודים עם מילות השאלה), משפט-תשובה; `Deep` = bge-m3 אופציונלי לבחירת התשובה |
| `Search.kt` | FTS4: ביטוי/כל המילים/קרובות/אחת, אותיות שימוש, snippets |
| `ReaderActivity.kt` | קורא: הדגשה ממוסגרת ופועמת עד נגיעה, ✨ להדגיש שוב, סימון משפט שלם (תשובה), OCR לעמודים סרוקים (`Ocr.wordBoxes`), תצוגת טקסט מסודרת, פס "✓ זה המקום" לגמרא |
| `Excerpt.kt` | ל-ReadEra וקוראים חיצוניים: PDF קטן מהעמוד שנמצא + 4, עם סימון צהוב בתוך ה-PDF (אין ל-ReadEra API לפתיחה בעמוד) |
| `Library.kt` | תיקיות, הרשאות, `pdfApps()` (כל האפליקציות שפותחות PDF; QUERY_ALL_PACKAGES), `chooseDefault()` בפתיחה הראשונה, `openExternal()` |
| `Indexer.kt` | סריקה: קבצים → טקסט (ספרים קטנים קודם) → משמעות → OCR → משמעות; `Progress` (עם זמן שנותר), `Ocr`, `Net`, `IndexService` |
| `Db.kt` | SQLite: `files`, `pages` (kind 0 טקסט, 1 OCR, 2 ממתין ל-OCR, 3 OCR ריק), `fts` (FTS4) |
| `Text.kt` | `Heb`: תיקון עברית הפוכה/קידודים ישנים, ניקוי ניקוד, אותיות שימוש |
| `CloudActivity.kt`, `DriveSync.kt` | ספרים מהענן: בחירה ידנית (SAF), או חיבור חשבון Google (drive.readonly) – סעיף 7 |
| `Topics.kt` | רעיונות יומיים לחיפוש (רשימה + נושאי החודש העברי), מוצגים רק אם מופיעים בספרים, עם מספר מקומות |
| `SettingsActivity.kt` | כל ההגדרות (ספרייה, סריקה, OCR, קולי, משמעות, מודל עמוק, פתיחת ספרים, ערכת נושא, עדכונים, אודות) |
| `Updater.kt` | עדכון עצמי מ-`releases/latest` של `Search-Plus-`; בודק מיד אחרי עדכון ואחר כך כל 6 שעות |
| `Organizer.kt`, `OrganizeActivity.kt`, `BrowseActivity.kt` | סידור המאגר ועיון בתיקיות (מהגרסה המקורית) |
| `AbbrActivity.kt` | מסך מילון ראשי תיבות – **לא מקושר מהתפריט** (בעל הפרויקט ביקש להסיר); אפשר למחוק |

---

## 5. החלטות חשובות – ולמה

1. **שמות מהמכשיר, לא רשימה קבועה** – הבקשה מותאמת לשמות התיקיות והקבצים שנמצאו בסריקה (`Names`). שינוי שם תיקייה + סריקה = מוכר מיד. הרשימות הקבועות (`Works`, `Abbr`) רק עוזרות.
2. **המעבר המהיר נתקע כשבנה קטלוג מדפי שער** (24,000 קריאות DB) ותור של בקשות מכל הקשה. עכשיו: בלי קריאת עמודים, בנייה ברקע, בקשה ישנה נזרקת, `locate` רק ל-6 ספרים (10 בסימן/פרק).
3. **Whisper בתהליך נפרד, שירות חזית, קשור למסך** – ב-2GB אנדרואיד הרג את התהליך ("נגמר הזיכרון") וזה סגר את כל התוכנה. עכשיו רק הזיהוי נופל, ובמכשיר <3GB הוא טוען encoder ואז decoder (לא ביחד).
4. **Whisper small, לא turbo** – בבדיקות turbo (large-v3-turbo) זיהה פחות טוב, נתקע בחזרות והיה איטי פי 4. "רמז" (prompt) ארוך יותר פגע בזיהוי – לא לשנות את `Whisper.PROMPT` בלי להריץ את בדיקות ה-CI.
5. **אינדקס משמעות לפני OCR** – OCR של מאגר גדול לוקח ימים; קודם המשמעות חיכתה לו ולא נבנתה אף פעם.
6. **סריקה מלאה פעם אחת** (מסך חוסם עם התקדמות), אחר כך רק בדיקה יומית לספרים חדשים.
7. **ReadEra** – אין דרך מתועדת לפתוח בעמוד. לכן `Excerpt` שולח PDF קטן מסומן. אפשר לכבות בהגדרות (ואז נפתח הספר המלא).
8. **הדגשה** – שחור עבה ב-E-ink (צהוב שקוף לא נראה במסך שחור-לבן); חזקה עד נגיעה; ✨ מחזיר אותה.

---

## 6. מה נבדק – ומה לא

**נבדק (ב-CI או מקומית):**
- המנתח (`HebRefTest`), מנוע השמות על 12,000 ספרים מדומים (`NamesTest`), ספקטרוגרמה זהה ל-OpenAI, טוקנייזרים זהים ל-HF,
  e5 ו-bge-m3 זהים לגרסה הרשמית (cos>0.99), Whisper על הקלטות TTS עבריות (small: 3/5 נכון; "כ״ז" לפעמים נשמע "כ׳"; base חלש מאוד).
- קומפילציה של האפליקציה ובניית APK.

**לא נבדק על המכשיר האמיתי (חשוב!):**
- האם הזיהוי הקולי עובד על ה-BOOX אחרי תיקוני הזיכרון (1.0.230) וכמה זמן לוקח.
- האם המעבר המהיר מוצא את הספרים בשמות האמיתיים שלו (נבדק רק על ספרייה מדומה).
- פתיחה מסומנת ב-ReadEra, הדגשה בעמודים סרוקים (OCR), חיבור Drive, מודל ההבנה העמוק ב-2GB.

---

## 7. דברים שבעל הפרויקט צריך לעשות (עדיין פתוח)

- **GitHub Pages:** Settings › Pages › Deploy from a branch › `gh-pages` › `/(root)` › Save. (ניסה פעם אחת וקיבל 404 – כנראה לא נשמר.)
- **Google Drive (חיבור חשבון):** רישום חד-פעמי ב-Google Cloud – ההוראות ב-`docs/GOOGLE_DRIVE_SETUP.md` (Package `com.chdv.searchplus` + SHA-1 למעלה, Drive API, OAuth consent עם Test users). בלי זה הכפתור יחזיר שגיאה; הבחירה הידנית עובדת.
- **פרסום רחב:** חנות Play (25$) תמנע את חסימת ה-APK; לא נעשה.

---

## 8. רעיונות ומשימות פתוחות

- לבדוק עם בעל הפרויקט שמות תיקיות אמיתיים (צילומי מסך) ולכוונן את `Names` לפיהם; להוסיף לבדיקה `NamesTest`.
- סעיף בתוך סימן (seif) – כרגע נפתח תחילת הסימן.
- שיפור זיהוי מספרים בדיבור ("כ״ז") – אולי פענוח עם כמה מועמדים ובחירה לפי מה שקיים בספרייה.
- הדגשה בעמודי OCR מהירה יותר (לשמור מיקומי מילים בזמן ה-OCR במקום לחשב מחדש).
- גיבוי האינדקס לכרטיס הזיכרון; תמיכה ב-EPUB/DOCX.
- קובץ `SearchPlus-full-source.md` בשורש הוא מהגרסה המקורית ו**אינו מעודכן** – אפשר למחוק או לייצר מחדש.

---

## 9. מסמכים, מדיה ושיווק

- `docs/guide/guide.html` → `SearchPlus-Guide.pdf` (6 עמודים). `docs/video/` → סרטון ~3 דק׳ עם קריינות עברית.
- `docs/ad/` – מודעה (`SearchPlus-Ad.png`, 1080×1350 עם QR להורדה) והודעת וואטסאפ (`message.txt`). ליצירה מחדש: Playwright על `ad.html`.
- `docs/site/index.html` – דף הנחיתה. בגרסת ה-Artifact: בלי תגיות head, גופן Heebo מ-Google Fonts, ערכי צבע ל-dark mode, הסרטון כקובץ מצורף, מייל כטקסט עם כפתור העתקה.
- המסכים במדריך, בסרטון ובדף הם **המחשה** (HTML), לא צילומי מסך אמיתיים; המספרים בהם לדוגמה.

---

## 10. מפתחות Prefs
`roots`, `semanticRoots`, `match`, `prefixes`, `sort`, `lastSemantic`, `semantic`, `semError`, `semBusy`, `deep`, `ocrAuto`, `autoScan`, `lastAutoScan`, `firstScanDone`,
`sortByPosition`, `openWith` (internal/external), `readerPkg`/`readerCls`, `readerChosen`, `excerpt`, `copyTerms`, `fontSize`, `theme`,
`autoUpdate`, `lastUpdateCheck`, `checkedForVersion`, `favs`, `history`, `recent`, `moveLog`, `dafCal` (`path|page|amudIndex`),
`voiceEngine` (whisper/auto/system), `whisperModel` (base/small), `whisperDir`, `voiceSearchAuto`, `voiceLowMem`, `voiceBusy`, `voiceLast`, `systemFailed`, `slowHinted`,
`lastExitSeen`, `cloudDir`, `driveLastSync`, `topics`, `topicsDay`.

---

## 11. איך לעבוד מול בעל הפרויקט

- לכתוב לו בעברית, קצר וברור, עם כותרות. בסוף – קישור להורדה. להגיד בגלוי מה **לא** נבדק על המכשיר.
- כל שינוי: לבדוק קומפילציה, לדחוף ל-`main`, לחכות ל-Release, ולשלוח לו את הקישור. הוא מעדכן מהתוכנה (הגדרות › בדיקת עדכונים) או מהקישור.
- כשמשהו "לא עובד" – לבקש צילום מסך; המסכים מציגים עכשיו התקדמות ושגיאות בדיוק כדי שאפשר יהיה לאבחן מהצילום.
```

## `README.md`

```markdown
<p align="center"><img src="docs/icon.png" width="120" alt="חיפוש פלוס"></p>

<h1 align="center">חיפוש פלוס</h1>
<p align="center"><b>חיפוש מעמיק בתוך ספרי ה-PDF שבמכשיר – במילים ולפי משמעות</b><br>
פותח על ידי שניאור וילהלם · מופץ בחינם לתועלת הרבים</p>

---

## ⬇️ הורדה

> 📄 **[מדריך מלא (PDF)](https://github.com/sw0555041250-code/Search-Plus-/releases/download/media/SearchPlus-Guide.pdf)** · 🎬 **[סרטון הסבר](https://github.com/sw0555041250-code/Search-Plus-/releases/download/media/SearchPlus-Video.mp4)**

**[להורדת הקובץ SearchPlus.apk מהגרסה האחרונה](https://github.com/sw0555041250-code/Search-Plus-/releases/latest/download/SearchPlus.apk)**

(כל הגרסאות: [עמוד ה-Releases](https://github.com/sw0555041250-code/Search-Plus-/releases))

## מה התוכנה עושה

חיפוש פלוס סורק את כל קובצי ה-PDF בתיקיית הספרים שלך – גם בכרטיס הזיכרון – וקורא כל מילה בכל עמוד. אחר כך אפשר לחפש **בתוך הספרים עצמם**, לא רק בשמות הקבצים.

- **חיפוש מילים** – ביטוי מדויק ("רבי שמעון"), כל המילים בעמוד, מילים קרובות, או אחת המילים. כולל אפשרות למצוא גם עם אותיות שימוש (ורבי, לרבי, שרבי…) וכוכבית להמשך מילה (`ויצ*`).
- **חיפוש לפי משמעות** – כותבים שאלה או רעיון במילים שלך ("מה הטעם שמדליקים נרות חנוכה בלילה") והתוכנה מוצאת את הקטעים שעוסקים בזה, גם כשהמילים שונות. מודל בינה מלאכותית רב-לשוני (multilingual-e5) רץ בתוך המכשיר – בלי אינטרנט ובלי לשלוח שום דבר החוצה. אפשר לכבות אותו לגמרי.
- **קטגוריות לפי תיקיות** – התוצאות מתחלקות לפי התיקיות הראשיות (הלכה, חסידות חב"ד, נגלה…). לוחצים על קטגוריה, ושוב על תת-תיקייה (רבותינו נשיאינו › הרבי…) – וכך ממקדים את החיפוש שלב אחרי שלב.
- **מיון** – לפי רלוונטיות, שם, תיקייה, גודל, תאריך או מספר עמודים.
- **פתיחה בדיוק בעמוד** – הקורא המובנה פותח את הספר בעמוד שבו נמצא הביטוי, **ומסמן את המילים על העמוד**. כפתור "תוצאה הבאה" קופץ לעמוד הבא שבו מופיע הביטוי. אפשר גם לפתוח באפליקציה אחרת.
- **ספרים ישנים** – תיקון אוטומטי של טקסט עברי הפוך (סדר ויזואלי) ושל קידודים ישנים (Windows-1255 / DOS-862) שנראים כמו ג'יבריש. עמודים סרוקים בלי שכבת טקסט עוברים **זיהוי אותיות (OCR) עברי** – הרכיב כבר כלול בתוכנה.
- **שאלה ותשובה** – בחיפוש לפי משמעות מופיע כרטיס "התשובה מתוך הספרים שלך": המשפט שעונה על השאלה, עם המקור והעמוד – לחיצה פותחת שם.
- **סידור המאגר** (🗂): **ספרים כפולים** – לפי התוכן שבתוך הקובץ ולא לפי השם (גם סריקה אחרת של אותו ספר); **סידור תיקייה עם תצוגה מקדימה** – בוחרים תיקייה ושיטה (לפי נושא / סדרה / אות / לתיקיות המאגר), עורכים את ההצעה ורק אז מחילים; **שמות חכמים** לקבצים ולתיקיות לפי דף השער והתוכן – עם שאלה על כל שינוי. הכול ניתן לביטול.
- **עיון בספרים** בלי חיפוש, **חיפוש בתוך ספר**, **מועדפים**, **חיפושים אחרונים**, **ספרים שנפתחו לאחרונה** (המשך מהעמוד האחרון), העתקת טקסט של עמוד ושיתוף.
- **עדכון אוטומטי** – התוכנה בודקת פעם ביום אם יש גרסה חדשה כאן ב-GitHub ומציעה להתקין אותה. האינדקס וההגדרות נשמרים.

### מותאם ל-BOOX Go 6 (ומכשירי E-ink אחרים)
שחור-לבן בניגודיות גבוהה, בלי אנימציות, דפדוף בעמודים שלמים (לחיצה בצד המסך, החלקה, מקשי עוצמת הקול או הכפתורים למטה), שימוש חסכוני בזיכרון (2GB RAM): קבצים גדולים נקראים דרך קובץ זמני, והסריקה רצה ברקע בעדיפות נמוכה.

## התקנה

1. במכשיר, פותחים את [קישור ההורדה](https://github.com/sw0555041250-code/Search-Plus-/releases/latest/download/SearchPlus.apk) ומורידים את `SearchPlus.apk`.
2. פותחים את הקובץ שירד. אם מופיעה הודעה על "מקורות לא ידועים" – מאשרים לדפדפן / למנהל הקבצים להתקין אפליקציות, וחוזרים.
3. מתקינים ופותחים את **חיפוש פלוס**.
4. **שלב 1** – לוחצים "מתן הרשאה" ומפעילים "גישה לכל הקבצים" (צריך את זה כדי לקרוא את הספרים, גם בכרטיס הזיכרון).
5. **שלב 2** – לוחצים "בחירת תיקיית הספרים", בוחרים "כרטיס זיכרון" (או זיכרון פנימי) ואת התיקייה הראשית של המאגר.
6. הסריקה מתחילה ברקע. אפשר לחפש מיד בספרים שכבר נסרקו. בפעם הראשונה, עם אלפי ספרים, הסריקה לוקחת זמן (שעות) – מומלץ להשאיר את המכשיר בטעינה. אחר כך כל פתיחה סורקת רק ספרים חדשים או שהשתנו.

### חיפוש לפי משמעות (אופציונלי)
הגדרות › "הורדת מודל המשמעות" (כ-120MB, ב-Wi‑Fi). התוכנה תבנה ברקע אינדקס משמעות. במכשיר חלש זה איטי (בערך שנייה לעמוד), ולכן מומלץ לבחור ב"תיקיות לאינדקס המשמעות" את התיקיות החשובות. לביטול: מכבים את "בניית אינדקס משמעות ברקע", או מוחקים את המודל.

## שאלות נפוצות

- **ספר לא נמצא בחיפוש?** בדקו בהגדרות › מצב הסריקה. ספרים סרוקים (תמונה) נכנסים לחיפוש אחרי ה-OCR, שרץ אחרי שכל הספרים נקראו. אפשר להאיץ לספר מסוים: עיון בספרים › לחיצה ארוכה על הספר › "זיהוי טקסט (OCR)".
- **הטקסט בספר מסוים יוצא מבולגן?** נסו בהגדרות "סדר טקסט לפי מיקום בעמוד", ואז לחיצה ארוכה על הספר › "קריאה מחדש".
- **איפה נשמר האינדקס?** בזיכרון הפנימי של התוכנה. הספרים עצמם לא משתנים ולא זזים.

---

## למפתחים

- Kotlin, בלי Android Studio: כל `push` ל-`main` מפעיל GitHub Actions (`.github/workflows/build-apk.yml`) שבונה APK חתום ומפרסם Release בשם `v1.0.<מספר בנייה>` עם `SearchPlus.apk`. אם הבנייה נכשלת – נוצר Release בשם `build-log` עם השגיאות.
- **חתימה:** `app/searchplus.p12` (סיסמה `searchplus123`, alias `searchplus`). **לא להחליף** – אחרת עדכונים לא יותקנו מעל הגרסה הקיימת.
- הסבר מלא על מבנה הקוד: [HANDOFF.md](HANDOFF.md).

פותח על ידי **שניאור וילהלם** · מופץ לתועלת הרבים
```

## `app/build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The app moved to a new repository whose build numbers start again from 1; +200 keeps every new build above the old ones (two moves: chdv770-ctrl/Search-Plus, then sw0555041250-code/-).
val buildNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt() + 200
val hasKey = file("searchplus.p12").exists()

android {
    namespace = "com.chdv.searchplus"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chdv.searchplus"
        minSdk = 26
        targetSdk = 30
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasKey) {
            create("fixed") {
                storeFile = file("searchplus.p12")
                storeType = "pkcs12"
                storePassword = "searchplus123"
                keyAlias = "searchplus"
                keyPassword = "searchplus123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKey) signingConfigs.getByName("fixed") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // "Connect Google Drive": the user's permission to read their books in Drive (Google sign-in)
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    // PDF text extraction (including the position of every letter, for highlighting)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // On-device AI model for search by meaning
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    // OCR for scanned pages that have no text layer
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")
}
```

## `app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
        tools:ignore="ScopedStorage" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="29"
        tools:ignore="ScopedStorage" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <!-- to list every app that can open a PDF (readers, note apps…) when choosing the default -->
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
        tools:ignore="QueryAllPackagesPermission" />

    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:mimeType="application/pdf" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:mimeType="*/*" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="file" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="content" />
        </intent>
    </queries>

    <application
        android:name=".App"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:largeHeap="true"
        android:requestLegacyExternalStorage="true"
        android:theme="@style/Theme.SearchPlus"
        tools:targetApi="30">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity android:name=".ReaderActivity" android:exported="true"
            android:label="@string/reader_name"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="file" android:mimeType="application/pdf" />
                <data android:scheme="content" android:mimeType="application/pdf" />
            </intent-filter>
        </activity>
        <activity android:name=".BrowseActivity" android:exported="false" />
        <activity android:name=".CloudActivity" android:exported="false" />
        <activity android:name=".AbbrActivity" android:exported="false" android:windowSoftInputMode="adjustResize" />
        <activity android:name=".VoiceActivity" android:exported="false" android:windowSoftInputMode="adjustResize" />
        <activity android:name=".SettingsActivity" android:exported="false" />
        <activity android:name=".OrganizeActivity" android:exported="false" />

        <service
            android:name=".VoiceService"
            android:process=":voice"
            android:exported="false" />
        <service
            android:name=".IndexService"
            android:exported="false" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

## `app/src/main/java/com/chdv/searchplus/AbbrActivity.kt`

```kotlin
package com.chdv.searchplus

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.chdv.searchplus.core.Abbr

/** מילון ראשי תיבות: look up any abbreviation, or by its meaning. The same dictionary the search uses. */
class AbbrActivity : BaseActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = vbox().apply { setPadding(dp(14), dp(10), dp(14), dp(30)) }
        box.addView(vbox().apply {
            background = Col.gradient(Col.TILES[1].first, Col.TILES[1].second, dp(20).toFloat())
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(label("📖 מילון ראשי תיבות", 21f, android.graphics.Color.WHITE, true))
            addView(label("אדה״ז, צ״צ, לקו״ש, שליט״א, עכ״ל… – כותבים, ורואים מה הפירוש. החיפוש בתוכנה מכיר את כולם.", 14f, 0xEEFFFFFF.toInt()).apply { setPadding(0, dp(6), 0, 0) })
        }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(12)))
        val q = EditText(this).apply {
            hint = "ראשי תיבות או מילה…"; setSingleLine(); textDirection = View.TEXT_DIRECTION_RTL
            background = roundBg(Col.CARD, dp(20).toFloat(), dp(1.5f), Col.ACC); setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        box.addView(q, lp(MATCH, WRAP))
        list = vbox()
        box.addView(list, lp(MATCH, WRAP).m(0, dp(10), 0, 0))
        q.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { show(e.toString()) }
        })
        screen("📖 ראשי תיבות", ScrollView(this).apply { addView(box) })
        show("")
    }

    private fun show(q: String) {
        list.removeAllViews()
        val items = Abbr.lookup(q).distinctBy { it.second + "|" + it.first.replace("\"", "").replace("״", "") }.take(200)
        if (items.isEmpty()) { list.addView(label("לא נמצא במילון.", 15f, Col.SUB)); return }
        for ((k, v) in items) list.addView(row(this, k, v))
    }

    companion object {
        fun row(a: Activity, k: String, v: String): View = a.hbox().apply {
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            background = roundBg(Col.CARD, a.dp(12).toFloat(), a.dp(1), Col.LINE)
            addView(a.label(pretty(k), 17f, Col.ACC, true), lp(a.dp(110), WRAP))
            addView(a.label(v, 16f, Col.TEXT), lp(0, WRAP, 1f))
            layoutParams = lp(MATCH, WRAP).m(0, a.dp(3), 0, a.dp(3))
        }

        /** "אדהז" → "אדה״ז" for display. */
        fun pretty(k: String): String =
            if (k.contains('״') || k.contains('׳') || k.contains(' ') || k.length < 2) k else k.dropLast(1) + "״" + k.last()

        /** The abbreviations that appear on a page, with their meaning. */
        fun onPage(text: String): List<Pair<String, String>> {
            val out = LinkedHashMap<String, String>()
            for (w in text.split(Regex("[\\s,.:;()\\[\\]]+"))) {
                if (!(w.contains('"') || w.contains('״') || w.contains('\'') || w.contains('׳'))) continue
                val m = Abbr.of(w) ?: Abbr.of(w.drop(1))?.let { "${w.first()}+ $it" } ?: continue
                out.putIfAbsent(w.trim('\'', '׳'), m)
            }
            return out.entries.map { it.key to it.value }
        }
    }
}
```

## `app/src/main/java/com/chdv/searchplus/BrowseActivity.kt`

```kotlin
package com.chdv.searchplus

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/** Browse the library folders and open books without searching. */
class BrowseActivity : BaseActivity() {
    private var dir: File? = null
    private lateinit var pathLabel: TextView
    private lateinit var list: RecyclerView
    private var items: List<File> = emptyList()
    private var filter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = vbox()
        pathLabel = label("", 13f, Col.SUB).apply { setPadding(dp(14), dp(6), dp(14), dp(6)) }
        body.addView(pathLabel, lp(MATCH, WRAP))
        val search = android.widget.EditText(this).apply {
            hint = "סינון לפי שם…"
            setSingleLine()
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            background = roundBg(Col.CARD, dp(18).toFloat(), dp(1), Col.LINE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { filter = s.toString().trim(); load() }
            })
        }
        body.addView(search, lp(MATCH, WRAP).m(dp(12), 0, dp(12), dp(6)))
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@BrowseActivity); itemAnimator = null }
        body.addView(list, lp(MATCH, 0, 1f))
        screen("עיון בספרים", body)
        list.adapter = Adapter()
        load()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val d = dir
        if (d == null) { super.onBackPressed(); return }
        dir = if (Library.roots().contains(d.absolutePath)) null else d.parentFile
        filter = ""
        load()
    }

    private fun load() {
        val d = dir
        if (d == null) {
            pathLabel.text = "תיקיות הספרים"
            items = Library.roots().map { File(it) }
        } else {
            pathLabel.text = Library.categoryOf(d.absolutePath).joinToString(" › ").ifEmpty { Library.rootName(d.absolutePath) }
            val kids = d.listFiles()?.filter { !it.name.startsWith(".") && (it.isDirectory || it.name.endsWith(".pdf", true)) } ?: emptyList()
            items = kids.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name }))
        }
        if (filter.isNotEmpty()) items = items.filter { it.name.contains(filter, true) }
        list.adapter?.notifyDataSetChanged()
    }

    private fun fileMenu(f: File) {
        val b = Db.get().bookByPath(f.absolutePath)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("פתיחה" to { ReaderActivity.open(this, f.absolutePath, 0) })
        items.add("פתיחה באפליקציה אחרת" to { Library.openExternal(this, f.absolutePath, 1, chooser = true) })
        if (b != null) {
            items.add("קריאה מחדש של הספר (סריקה)" to { Indexer.reindex(this, b); toast("הספר נכנס לתור הסריקה") })
            if (b.emptyPages > 0) items.add("זיהוי טקסט (OCR) ל-${b.emptyPages} העמודים הסרוקים" to {
                if (!Ocr.ready(this)) toast("קודם יש להוריד את רכיב ה-OCR בהגדרות")
                else { Progress.ocrFile = b.id; Indexer.start(this); toast("זיהוי הטקסט התחיל ברקע") }
            })
            items.add("מצב בסריקה" to {
                val st = when (b.status) { St.DONE -> "נסרק ✓"; St.ERROR -> "שגיאה בקריאה"; else -> "ממתין לסריקה" }
                Sheet.info(this, b.title, "$st\nעמודים: ${b.pages}\nעמודים עם טקסט: ${b.textPages}\nעמודים סרוקים בלי טקסט: ${b.emptyPages}\nגודל: ${human(b.size)}")
            })
        }
        Sheet.actions(this, f.nameWithoutExtension, items)
    }

    inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = this@BrowseActivity
            val v = c.vbox().apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = pressBg(Col.BG, Col.ACC_LIGHT, 0)
                addView(c.label("", 16f, Col.TEXT, true).apply { id = 1; maxLines = 2 })
                addView(c.label("", 12.5f, Col.SUB).apply { id = 2 })
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { bottomMargin = 1 }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val f = items[position]
            val v = h.itemView
            if (f.isDirectory) {
                v.findViewById<TextView>(1).text = "📁 " + (if (dir == null) Library.rootName(f.absolutePath) else f.name)
                v.findViewById<TextView>(2).text = if (dir == null) f.absolutePath else ""
                v.findViewById<TextView>(2).visibility = if (dir == null) View.VISIBLE else View.GONE
                v.setOnClickListener { dir = f; filter = ""; load() }
                v.setOnLongClickListener(null)
            } else {
                val b = Db.get().bookByPath(f.absolutePath)
                v.findViewById<TextView>(1).text = "📖 " + f.nameWithoutExtension
                v.findViewById<TextView>(2).visibility = View.VISIBLE
                v.findViewById<TextView>(2).text = human(f.length()) + "  ·  " + when (b?.status) {
                    St.DONE -> "נסרק ✓ (${b.pages} עמ׳" + (if (b.emptyPages > 0) ", ${b.emptyPages} סרוקים" else "") + ")"
                    St.ERROR -> "לא ניתן לקרוא"
                    else -> "ממתין לסריקה"
                }
                v.setOnClickListener { ReaderActivity.open(this@BrowseActivity, f.absolutePath, 0) }
                v.setOnLongClickListener { fileMenu(f); true }
            }
        }
    }
}

/** In-app folder chooser (works with the internal memory and the SD card). */
object FolderPicker {
    fun pick(a: Activity, onPick: (String) -> Unit) {
        val box = a.vbox().apply { setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(12)) }
        val title = a.label("בחירת תיקייה", 18f, Col.TEXT, true)
        val path = a.label("", 13f, Col.SUB).apply { setPadding(0, a.dp(4), 0, a.dp(8)) }
        val inner = a.vbox()
        val scroll = ScrollView(a).apply { addView(inner) }
        box.addView(title); box.addView(path)
        box.addView(scroll, LinearLayout.LayoutParams(MATCH, a.dp(380)))
        lateinit var d: Dialog
        var cur: File? = null
        lateinit var chooseBtn: TextView

        fun show(dir: File?) {
            cur = dir
            inner.removeAllViews()
            if (dir == null) {
                path.text = "בחרו אחסון:"
                Library.volumes(a).forEachIndexed { i, v ->
                    val name = if (i == 0) "💾 זיכרון פנימי" else "💳 כרטיס זיכרון (${v.name})"
                    inner.addView(row(a, name) { show(v) })
                }
                chooseBtn.visibility = View.GONE
                return
            }
            path.text = dir.absolutePath
            chooseBtn.visibility = View.VISIBLE
            inner.addView(row(a, "⬆ למעלה") {
                val vols = Library.volumes(a).map { it.absolutePath }
                show(if (dir.absolutePath in vols) null else dir.parentFile)
            })
            val kids = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
            if (kids.isEmpty()) inner.addView(a.label("(אין תיקיות משנה)", 14f, Col.SUB).apply { setPadding(a.dp(8), a.dp(10), 0, 0) })
            kids.forEach { k ->
                val pdfs = k.listFiles()?.count { it.name.endsWith(".pdf", true) } ?: 0
                inner.addView(row(a, "📁 ${k.name}" + if (pdfs > 0) "   ($pdfs PDF)" else "") { show(k) })
            }
        }

        val btns = a.hbox()
        chooseBtn = a.pill("בחירת התיקייה הזו") { cur?.let { d.dismiss(); onPick(it.absolutePath) } }
        btns.addView(chooseBtn, LinearLayout.LayoutParams(0, WRAP, 1f).m(a.dp(3), 0, a.dp(3), 0))
        btns.addView(a.pill("ביטול", false) { d.dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f).m(a.dp(3), 0, a.dp(3), 0))
        box.addView(btns, LinearLayout.LayoutParams(MATCH, WRAP).m(0, a.dp(10), 0, 0))
        d = Sheet.make(a, box)
        // with an SD card, start at the storage list so the card is easy to find
        show(if (Library.volumes(a).size > 1) null else Library.storageRoot())
        d.show()
    }

    private fun row(c: Context, t: String, onClick: () -> Unit) = c.label(t, 16f, Col.TEXT).apply {
        setPadding(c.dp(8), c.dp(12), c.dp(8), c.dp(12))
        background = c.pressBg(android.graphics.Color.TRANSPARENT, Col.ACC_LIGHT, 10)
        setOnClickListener { onClick() }
    }
}
```

## `app/src/main/java/com/chdv/searchplus/Catalog.kt`

```kotlin
package com.chdv.searchplus

import com.chdv.searchplus.core.Names
import com.chdv.searchplus.core.Ref

/**
 * The library's own names: every file and folder name found by the scan (see core/Names). Built once
 * in memory (no page reading – about a second for 12,000 books) and rebuilt after the library changes,
 * so a renamed or new folder is understood after the next scan.
 */
object Catalog {
    class Match(val book: Book, val score: Float)

    @Volatile private var names: Names? = null
    @Volatile private var books: Map<Long, Book> = emptyMap()

    fun invalidate() { names = null }

    @Synchronized
    private fun get(): Names {
        names?.let { return it }
        val all = Db.get().books()
        val roots = Library.roots()
        val items = all.values.map { b ->
            val root = roots.filter { b.dir == it || b.dir.startsWith("$it/") }.maxByOrNull { it.length } ?: ""
            Names.Item(b.id, b.title, b.dir.removePrefix(root).trim('/').split('/').filter { it.isNotBlank() })
        }
        books = all
        return Names(items).also { names = it }
    }

    /** Build in the background, so the first request is instant. */
    fun warm() { Thread { try { get() } catch (e: Throwable) {} }.start() }

    fun find(request: String, ref: Ref? = null, max: Int = 10): List<Match> {
        val n = get()
        return n.find(request, ref, max).mapNotNull { h -> books[h.id]?.let { Match(it, h.score) } }
    }
}
```

## `app/src/main/java/com/chdv/searchplus/CloudActivity.kt`

```kotlin
package com.chdv.searchplus

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Books from Google Drive (or any cloud app on the phone). The books are brought once into a library
 * folder on the device; from then on they are scanned and searched like every other book – on the
 * device, without internet. The user chooses books (or a folder, where the app allows it) in the
 * system picker, where Google Drive appears after signing in to the Drive app.
 */
class CloudActivity : BaseActivity() {
    companion object {
        private const val PICK_FILES = 51
        private const val PICK_TREE = 52

        /** The library folder for books brought from the cloud: on the storage with the most free space. */
        fun cloudDir(a: Activity): File {
            val saved = Prefs.s("cloudDir", "")
            if (saved.isNotEmpty()) return File(saved)
            val vols = Library.volumes(a).filter { it.canWrite() }
            val base = vols.maxByOrNull { it.usableSpace } ?: a.getExternalFilesDir(null)!!
            return File(base, "חיפוש פלוס – ספרים מהענן").also { Prefs.put("cloudDir", it.absolutePath) }
        }
    }

    private lateinit var info: TextView
    private lateinit var log: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = vbox().apply { setPadding(dp(16), dp(10), dp(16), dp(30)) }
        val head = vbox().apply {
            background = Col.gradient(Col.TILES[2].first, Col.TILES[2].second, dp(20).toFloat())
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        head.addView(label("☁ ספרים מ-Google Drive", 21f, android.graphics.Color.WHITE, true))
        head.addView(label("יש לכם מאגר ספרים בדרייב? מביאים אותו פעם אחת למכשיר – ומאז מחפשים בו כמו בכל ספר, גם בלי אינטרנט.", 14.5f, 0xEEFFFFFF.toInt()).apply { setPadding(0, dp(6), 0, 0) })
        box.addView(head, lp(MATCH, WRAP).m(0, dp(4), 0, dp(14)))

        box.addView(pill("🔗 חיבור לחשבון Google Drive") { DriveSync.run(this, cloudDir(this)) { refresh() } }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(2)))
        box.addView(label("מתחברים פעם אחת עם חשבון Google ומאשרים קריאה. התוכנה מוצאת את כל ספרי ה-PDF ב-Drive, אתם בוחרים תיקיות – והם יורדים למכשיר ונסרקים. רק קריאה: שום דבר ב-Drive לא משתנה.", 13.5f, Col.SUB).apply { setPadding(dp(4), 0, dp(4), dp(12)) })
        box.addView(label("או בלי חיבור חשבון:", 14f, Col.ACC, true).apply { setPadding(0, dp(4), 0, dp(2)) })
        box.addView(pill("📄 בחירת ספרים ידנית", false) { pickFiles() }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
        box.addView(pill("📁 בחירת תיקייה שלמה", false) { pickTree() }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(10)))
        box.addView(label("איך זה עובד", 15f, Col.ACC, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        box.addView(label(
            "בחירה ידנית: 1. לוחצים \"בחירת ספרים ידנית\". בחלון שנפתח לוחצים ☰ ובוחרים Google Drive (צריך שאפליקציית Drive תהיה מותקנת ומחוברת לחשבון).\n" +
            "2. מסמנים ספרים – אפשר הרבה ביחד (לחיצה ארוכה על הראשון, ואז על השאר) – ולוחצים \"בחירה\".\n" +
            "3. הספרים יורדים לתיקייה במכשיר, נכנסים למאגר ונסרקים. זהו – מחפשים.\n\n" +
            "בחירת תיקייה שלמה עובדת בכרטיס הזיכרון ובחלק מאפליקציות הענן. Google Drive מאפשר לבחור קבצים (הרבה ביחד), לא תיקייה שלמה.", 14.5f, Col.SUB).apply { setLineSpacing(0f, 1.2f) })
        info = label("", 14f, Col.TEXT, true).apply { setPadding(0, dp(14), 0, dp(4)) }
        box.addView(info)
        log = vbox()
        box.addView(log)
        screen("☁ ספרים מהענן", ScrollView(this).apply { addView(box) })
        refresh()
    }

    private fun refresh() {
        val d = cloudDir(this)
        val n = d.walkTopDown().count { it.isFile && it.name.endsWith(".pdf", true) }
        info.text = if (n == 0) "עוד לא הובאו ספרים." else "📚 $n ספרים הובאו מהענן · נשמרים ב: ${d.absolutePath}"
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        if (!safeStartForResult(i, PICK_FILES)) toast("לא נמצא בוחר קבצים במכשיר")
    }

    private fun pickTree() {
        if (!safeStartForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_TREE)) toast("המכשיר לא מאפשר בחירת תיקייה")
    }

    private fun safeStartForResult(i: Intent, code: Int) = try { @Suppress("DEPRECATION") startActivityForResult(i, code); true } catch (e: Exception) { false }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DriveSync.REQ) { if (resultCode == RESULT_OK) DriveSync.onResult(this, requestCode, data); return }
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            PICK_FILES -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
                data.data?.let { if (it !in uris) uris.add(it) }
                bring(uris.map { it to "" })
            }
            PICK_TREE -> data.data?.let { tree -> Thread { val l = listTree(tree); ui { if (l.isEmpty()) Sheet.info(this, "לא נמצאו ספרים", "בתיקייה שנבחרה אין קבצי PDF.") else bring(l) } }.start() }
        }
    }

    /** All PDFs under a chosen folder, with their sub-folder path (kept as categories). */
    private fun listTree(tree: Uri): List<Pair<Uri, String>> {
        val out = ArrayList<Pair<Uri, String>>()
        val root = DocumentsContract.getTreeDocumentId(tree)
        val stack = ArrayDeque<Pair<String, String>>().apply { add(root to "") }
        while (stack.isNotEmpty() && out.size < 20000) {
            val (id, rel) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            try {
                contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val cid = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) stack.add(cid to (if (rel.isEmpty()) name else "$rel/$name"))
                        else if (name.endsWith(".pdf", true) || mime == "application/pdf") out.add(DocumentsContract.buildDocumentUriUsingTree(tree, cid) to rel)
                    }
                }
            } catch (e: Exception) {}
        }
        return out
    }

    private fun nameOf(u: Uri): String = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null } ?: (u.lastPathSegment?.substringAfterLast('/') ?: "ספר")

    /** Download the chosen books into the cloud library folder, then add it to the library and scan. */
    private fun bring(items: List<Pair<Uri, String>>) {
        if (items.isEmpty()) return
        val st = label("מתחיל…", 15f, Col.SUB)
        val d = Sheet.make(this, vbox().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); addView(label("מביא ספרים מהענן", 18f, Col.TEXT, true)); addView(st) })
        d.setCancelable(false); d.show()
        val dir = cloudDir(this)
        Thread {
            var ok = 0; var skip = 0; var fail = 0
            items.forEachIndexed { k, (u, rel) ->
                val name = nameOf(u).let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }.replace('/', '_')
                val dest = File(if (rel.isEmpty()) dir else File(dir, rel), name)
                ui { st.text = "${k + 1} / ${items.size}\n$name" }
                try {
                    dest.parentFile?.mkdirs()
                    if (dest.exists() && dest.length() > 0) { skip++; return@forEachIndexed }
                    val tmp = File(dest.parentFile, dest.name + ".part")
                    contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                    if (!tmp.renameTo(dest)) throw java.io.IOException("rename")
                    ok++
                } catch (e: Exception) { fail++ }
            }
            ui {
                d.dismiss()
                if (Library.roots().none { dir.absolutePath == it || dir.absolutePath.startsWith("$it/") }) Library.setRoots(Library.roots() + dir.absolutePath)
                Indexer.start(this)
                refresh()
                Sheet.info(this, "הספרים הובאו ✓", "הובאו $ok ספרים" + (if (skip > 0) " · $skip כבר היו" else "") + (if (fail > 0) " · $fail לא ירדו (בדקו אינטרנט)" else "") +
                    ".\n\nהם נכנסו למאגר ונסרקים עכשיו ברקע – אפשר לחפש בהם בעוד רגע.")
            }
        }.start()
    }
}
```

## `app/src/main/java/com/chdv/searchplus/Db.kt`

```kotlin
package com.chdv.searchplus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** One indexed book. */
data class Book(
    val id: Long,
    val path: String,
    val dir: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val pages: Int,
    val status: Int,
    val textPages: Int,
    val emptyPages: Int,
) {
    val title: String get() = name.removeSuffix(".pdf").removeSuffix(".PDF")
}

object St {
    const val PENDING = 0
    const val DONE = 1
    const val ERROR = 2
}

object Kind {
    const val TEXT = 0
    const val OCR = 1
    const val EMPTY = 2
}

/**
 * The search index: an SQLite database with an FTS4 full-text table. Every PDF page is one row.
 * Kept on the device's shared storage-independent app folder.
 */
class Db private constructor(c: Context) : SQLiteOpenHelper(c, "index.db", null, 3) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE files(id INTEGER PRIMARY KEY, path TEXT UNIQUE, dir TEXT, name TEXT, size INTEGER, mtime INTEGER, " +
                "pages INTEGER DEFAULT 0, status INTEGER DEFAULT 0, textPages INTEGER DEFAULT 0, emptyPages INTEGER DEFAULT 0, " +
                "emb INTEGER DEFAULT 0, err TEXT)"
        )
        db.execSQL("CREATE TABLE pages(id INTEGER PRIMARY KEY, file INTEGER, page INTEGER, kind INTEGER)")
        db.execSQL("CREATE INDEX pages_file ON pages(file, page)")
        db.execSQL("CREATE INDEX pages_kind ON pages(kind)")
        db.execSQL("CREATE VIRTUAL TABLE fts USING fts4(body)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS files")
        db.execSQL("DROP TABLE IF EXISTS pages")
        db.execSQL("DROP TABLE IF EXISTS fts")
        onCreate(db)
    }

    // ---------- books ----------

    @Volatile
    private var cache: Map<Long, Book>? = null

    fun invalidate() { cache = null; Catalog.invalidate() }

    private fun readBook(c: android.database.Cursor) = Book(
        c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5),
        c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9)
    )

    private val COLS = "id,path,dir,name,size,mtime,pages,status,textPages,emptyPages"

    fun books(): Map<Long, Book> {
        cache?.let { return it }
        val m = HashMap<Long, Book>()
        readableDatabase.rawQuery("SELECT $COLS FROM files", null).use { c ->
            while (c.moveToNext()) { val b = readBook(c); m[b.id] = b }
        }
        cache = m
        return m
    }

    fun book(id: Long): Book? = books()[id]

    fun bookByPath(path: String): Book? = books().values.firstOrNull { it.path == path }

    fun addFile(f: File): Long {
        val v = ContentValues().apply {
            put("path", f.absolutePath); put("dir", f.parent ?: ""); put("name", f.name)
            put("size", f.length()); put("mtime", f.lastModified()); put("status", St.PENDING)
        }
        val id = writableDatabase.insertWithOnConflict("files", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        invalidate()
        return id
    }

    fun clearContent(fileId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM fts WHERE docid IN (SELECT id FROM pages WHERE file=?)", arrayOf(fileId))
            db.execSQL("DELETE FROM pages WHERE file=?", arrayOf(fileId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun removeFile(fileId: Long) {
        clearContent(fileId)
        writableDatabase.execSQL("DELETE FROM files WHERE id=?", arrayOf(fileId))
        invalidate()
    }

    fun markChanged(fileId: Long, f: File) {
        clearContent(fileId)
        writableDatabase.execSQL(
            "UPDATE files SET size=?, mtime=?, status=?, pages=0, textPages=0, emptyPages=0, emb=0, err=NULL WHERE id=?",
            arrayOf(f.length(), f.lastModified(), St.PENDING, fileId)
        )
        invalidate()
    }

    fun resetAll() {
        val db = writableDatabase
        db.execSQL("DELETE FROM fts")
        db.execSQL("DELETE FROM pages")
        db.execSQL("UPDATE files SET status=0, pages=0, textPages=0, emptyPages=0, emb=0, err=NULL")
        invalidate()
    }

    fun pendingFiles(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery("SELECT id, path FROM files WHERE status=? ORDER BY dir, name", arrayOf(St.PENDING.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1))
        }
        return out
    }

    /** Save all pages of one book in one transaction. texts[i] == null means the page had no usable text. */
    fun saveBook(fileId: Long, texts: List<String?>, kind: Int = Kind.TEXT) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM fts WHERE docid IN (SELECT id FROM pages WHERE file=?)", arrayOf(fileId))
            db.execSQL("DELETE FROM pages WHERE file=?", arrayOf(fileId))
            val insPage = db.compileStatement("INSERT INTO pages(file, page, kind) VALUES(?,?,?)")
            val insFts = db.compileStatement("INSERT INTO fts(docid, body) VALUES(?,?)")
            var text = 0; var empty = 0
            texts.forEachIndexed { i, t ->
                insPage.bindLong(1, fileId); insPage.bindLong(2, (i + 1).toLong())
                insPage.bindLong(3, if (t == null) Kind.EMPTY.toLong() else kind.toLong())
                val pid = insPage.executeInsert()
                if (t != null) {
                    insFts.bindLong(1, pid); insFts.bindString(2, t); insFts.executeInsert()
                    text++
                } else empty++
            }
            db.execSQL(
                "UPDATE files SET pages=?, textPages=?, emptyPages=?, status=?, err=NULL WHERE id=?",
                arrayOf(texts.size, text, empty, St.DONE, fileId)
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        invalidate()
    }

    fun markError(fileId: Long, err: String) {
        writableDatabase.execSQL("UPDATE files SET status=?, err=? WHERE id=?", arrayOf(St.ERROR, err.take(300), fileId))
        invalidate()
    }

    /** Pages that had no text layer, for OCR. Returns (pageId, fileId, page). */
    fun emptyPages(limit: Int, onlyFile: Long? = null): List<Triple<Long, Long, Int>> {
        val out = ArrayList<Triple<Long, Long, Int>>()
        val q = if (onlyFile != null) "SELECT id, file, page FROM pages WHERE kind=2 AND file=$onlyFile ORDER BY page LIMIT $limit"
        else "SELECT id, file, page FROM pages WHERE kind=2 ORDER BY file, page LIMIT $limit"
        readableDatabase.rawQuery(q, null).use { c ->
            while (c.moveToNext()) out.add(Triple(c.getLong(0), c.getLong(1), c.getInt(2)))
        }
        return out
    }

    fun saveOcr(pageId: Long, fileId: Long, text: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (text != null) {
                db.execSQL("INSERT OR REPLACE INTO fts(docid, body) VALUES(?,?)", arrayOf(pageId, text))
                db.execSQL("UPDATE pages SET kind=1 WHERE id=?", arrayOf(pageId))
                db.execSQL("UPDATE files SET textPages=textPages+1, emptyPages=emptyPages-1 WHERE id=?", arrayOf(fileId))
            } else {
                // nothing readable even with OCR (blank page / picture) - mark as done so it is not retried
                db.execSQL("UPDATE pages SET kind=3 WHERE id=?", arrayOf(pageId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        invalidate()
    }

    fun pageText(fileId: Long, page: Int): String? {
        readableDatabase.rawQuery(
            "SELECT f.body FROM pages p JOIN fts f ON f.docid=p.id WHERE p.file=? AND p.page=?",
            arrayOf(fileId.toString(), page.toString())
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    /** Text of every page of a book that has text: page number → text. */
    fun pageTexts(fileId: Long): Map<Int, String> {
        val m = HashMap<Int, String>()
        readableDatabase.rawQuery("SELECT p.page, f.body FROM pages p JOIN fts f ON f.docid = p.id WHERE p.file=?", arrayOf(fileId.toString())).use { c ->
            while (c.moveToNext()) m[c.getInt(0)] = c.getString(1)
        }
        return m
    }

    fun pageTextById(pageId: Long): String? {
        readableDatabase.rawQuery("SELECT body FROM fts WHERE docid=?", arrayOf(pageId.toString())).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun pageRef(pageId: Long): Pair<Long, Int>? {
        readableDatabase.rawQuery("SELECT file, page FROM pages WHERE id=?", arrayOf(pageId.toString())).use { c ->
            return if (c.moveToFirst()) c.getLong(0) to c.getInt(1) else null
        }
    }

    // ---------- semantic bookkeeping ----------

    fun filesForEmbedding(): List<Book> {
        val ids = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT id FROM files WHERE status=1 AND emb=0 ORDER BY dir, name", null).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        val m = books()
        return ids.mapNotNull { m[it] }
    }

    fun pagesOf(fileId: Long): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery(
            "SELECT p.id, f.body FROM pages p JOIN fts f ON f.docid=p.id WHERE p.file=? ORDER BY p.page",
            arrayOf(fileId.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1)) }
        return out
    }

    fun markEmbedded(fileId: Long, v: Int = 1) {
        writableDatabase.execSQL("UPDATE files SET emb=? WHERE id=?", arrayOf(v, fileId))
    }

    fun resetEmbeddings() { writableDatabase.execSQL("UPDATE files SET emb=0") }

    fun embeddedCount(): Int = count("SELECT COUNT(*) FROM files WHERE emb=1")

    // ---------- stats ----------

    fun count(sql: String): Int = readableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    data class Stats(val books: Int, val done: Int, val pending: Int, val errors: Int, val pages: Int, val textPages: Int, val emptyPages: Int, val embedded: Int)

    fun stats(): Stats = Stats(
        count("SELECT COUNT(*) FROM files"),
        count("SELECT COUNT(*) FROM files WHERE status=1"),
        count("SELECT COUNT(*) FROM files WHERE status=0"),
        count("SELECT COUNT(*) FROM files WHERE status=2"),
        count("SELECT COALESCE(SUM(pages),0) FROM files"),
        count("SELECT COALESCE(SUM(textPages),0) FROM files"),
        count("SELECT COUNT(*) FROM pages WHERE kind=2"),
        count("SELECT COUNT(*) FROM files WHERE emb=1"),
    )

    fun errors(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT name, COALESCE(err,'') FROM files WHERE status=2 ORDER BY name LIMIT 300", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    companion object {
        @Volatile
        private var inst: Db? = null
        fun get(): Db = inst ?: synchronized(this) { inst ?: Db(App.ctx).also { inst = it } }
    }
}
```

## `app/src/main/java/com/chdv/searchplus/DriveSync.kt`

```kotlin
package com.chdv.searchplus

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.CheckBox
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "Connect Google Drive": the app asks the user to sign in with their Google account and allow
 * reading their Drive. Then it finds every PDF there (with its folders), the user chooses what to
 * bring, and the books are downloaded into the cloud library folder on the device and scanned like
 * any other book – so searching and answers happen on the device. Read-only: nothing in Drive is changed.
 *
 * Works when the developer registered the app with Google once (package name + signing fingerprint,
 * Drive API enabled) – see docs/GOOGLE_DRIVE_SETUP.md.
 */
object DriveSync {
    const val REQ = 61
    private const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    private const val API = "https://www.googleapis.com/drive/v3/files"

    class Item(val id: String, val name: String, val folder: String, val size: Long, val modified: String)

    /** Step 1: ask for permission (Google shows its own sign-in / consent screen). */
    fun connect(a: Activity, onToken: (String) -> Unit) {
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        try {
            Identity.getAuthorizationClient(a).authorize(req)
                .addOnSuccessListener { r ->
                    if (r.hasResolution()) {
                        pending = onToken
                        try {
                            @Suppress("DEPRECATION")
                            a.startIntentSenderForResult(r.pendingIntent!!.intentSender, REQ, null, 0, 0, 0, null)
                        } catch (e: Exception) { failed(a, e) }
                    } else r.accessToken?.let(onToken) ?: failed(a, null)
                }
                .addOnFailureListener { e -> failed(a, e) }
        } catch (e: Throwable) { failed(a, e) }
    }

    private var pending: ((String) -> Unit)? = null

    /** Call from onActivityResult. */
    fun onResult(a: Activity, requestCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ) return false
        val cb = pending; pending = null
        try {
            val r = Identity.getAuthorizationClient(a).getAuthorizationResultFromIntent(data)
            val t = r.accessToken
            if (t != null && cb != null) cb(t) else failed(a, null)
        } catch (e: Throwable) { failed(a, e) }
        return true
    }

    private fun failed(a: Activity, e: Throwable?) {
        val msg = e?.message ?: ""
        Sheet.info(a, "החיבור ל-Google Drive לא הצליח",
            "אפשר לנסות שוב. אם זה חוזר: ודאו שיש במכשיר שירותי Google ושאתם מחוברים לחשבון.\n\n" +
                (if (msg.contains("DEVELOPER_ERROR") || msg.contains("10:")) "(החיבור עוד לא הוגדר אצל Google עבור התוכנה – ראו הוראות המפתח.)\n" else "") + msg)
    }

    // ---------- Drive API ----------

    private fun get(url: String, token: String): HttpURLConnection {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Authorization", "Bearer $token")
            val code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect() }
            else if (code >= 400) { val e = c.errorStream?.bufferedReader()?.readText() ?: ""; c.disconnect(); throw java.io.IOException("Drive $code ${e.take(200)}") }
            else return c
        }
        throw java.io.IOException("too many redirects")
    }

    /** Every PDF in the user's Drive (own files and shared with them), with the folder path of each. */
    fun listAll(token: String, progress: (String) -> Unit): List<Item> {
        val folders = HashMap<String, Pair<String, String?>>() // id → (name, parent)
        val pdfs = ArrayList<Triple<JSONObject, String?, Unit>>()
        var page: String? = null
        val q = URLEncoder.encode("(mimeType='application/pdf' or mimeType='application/vnd.google-apps.folder') and trashed=false", "UTF-8")
        do {
            val url = "$API?q=$q&pageSize=1000&fields=" + URLEncoder.encode("nextPageToken,files(id,name,mimeType,parents,size,modifiedTime)", "UTF-8") +
                "&includeItemsFromAllDrives=true&supportsAllDrives=true" + (page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val c = get(url, token)
            val js = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
            val files = js.optJSONArray("files")
            if (files != null) for (k in 0 until files.length()) {
                val f = files.getJSONObject(k)
                val parent = f.optJSONArray("parents")?.optString(0)
                if (f.optString("mimeType") == "application/vnd.google-apps.folder") folders[f.getString("id")] = f.optString("name") to parent
                else pdfs.add(Triple(f, parent, Unit))
            }
            progress("מצאתי ${pdfs.size} ספרים…")
            page = js.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        fun path(id: String?): String {
            val parts = ArrayList<String>()
            var cur = id; var guard = 0
            while (cur != null && guard++ < 30) { val f = folders[cur] ?: break; parts.add(0, f.first); cur = f.second }
            return parts.joinToString("/")
        }
        return pdfs.map { (f, parent, _) -> Item(f.getString("id"), f.optString("name"), path(parent), f.optString("size").toLongOrNull() ?: 0L, f.optString("modifiedTime")) }
    }

    fun download(token: String, it: Item, dest: File) {
        val c = get("$API/${it.id}?alt=media&supportsAllDrives=true", token)
        val tmp = File(dest.parentFile, dest.name + ".part")
        try { c.inputStream.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o, 1 shl 16) } } } finally { c.disconnect() }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw java.io.IOException("rename")
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "ספר" }

    /** The whole flow: connect → list → choose folders → download new/changed books → scan. */
    fun run(a: Activity, dir: File, done: () -> Unit) {
        connect(a) { token ->
            val st = a.label("מתחבר…", 15f, Col.SUB)
            val d = Sheet.make(a, a.vbox().apply { setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(18)); addView(a.label("☁ קורא את ה-Drive שלך", 18f, Col.TEXT, true)); addView(st) })
            d.setCancelable(false); d.show()
            Thread {
                val items = try { listAll(token) { t -> ui { st.text = t } } } catch (e: Throwable) {
                    ui { d.dismiss(); Sheet.info(a, "לא ניתן לקרוא את ה-Drive", e.message ?: e.javaClass.simpleName) }; return@Thread
                }
                ui {
                    d.dismiss()
                    if (items.isEmpty()) { Sheet.info(a, "לא נמצאו ספרים", "ב-Google Drive שלך אין קבצי PDF."); return@ui }
                    choose(a, items) { chosen -> fetch(a, token, chosen, dir, done) }
                }
            }.start()
        }
    }

    /** Folder checklist (top-level folders), with how many books and how big. */
    private fun choose(a: Activity, items: List<Item>, go: (List<Item>) -> Unit) {
        val groups = items.groupBy { it.folder.substringBefore('/').ifEmpty { "(ראשי)" } }.toSortedMap()
        val box = a.vbox().apply { setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(14)) }
        box.addView(a.label("נמצאו ${items.size} ספרים (${human(items.sumOf { it.size })})", 18f, Col.TEXT, true))
        box.addView(a.label("בחרו אילו תיקיות להביא למכשיר. ספרים שכבר הובאו לא יורדים שוב.", 14f, Col.SUB).apply { setPadding(0, a.dp(4), 0, a.dp(8)) })
        val checks = ArrayList<Pair<CheckBox, List<Item>>>()
        val list = a.vbox()
        for ((name, l) in groups) {
            val cb = CheckBox(a).apply {
                text = "📁 $name  ·  ${l.size} ספרים · ${human(l.sumOf { it.size })}"
                isChecked = true; textSize = 15f * scale(); setTextColor(Col.TEXT)
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            list.addView(cb); checks.add(cb to l)
        }
        box.addView(android.widget.ScrollView(a).apply { addView(list) }, lp(MATCH, a.dp(300)))
        lateinit var dlg: android.app.Dialog
        val row = a.hbox()
        row.addView(a.pill("הבאה") { dlg.dismiss(); go(checks.filter { it.first.isChecked }.flatMap { it.second }) }, lp(0, WRAP, 1f).m(a.dp(4), a.dp(10), a.dp(4), 0))
        row.addView(a.pill("ביטול", false) { dlg.dismiss() }, lp(0, WRAP, 1f).m(a.dp(4), a.dp(10), a.dp(4), 0))
        box.addView(row)
        dlg = Sheet.make(a, box); dlg.show()
    }

    private fun fetch(a: Activity, token: String, items: List<Item>, dir: File, done: () -> Unit) {
        if (items.isEmpty()) return
        val st = a.label("", 15f, Col.SUB)
        var stop = false
        lateinit var dlg: android.app.Dialog
        val box = a.vbox().apply { setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(14)); addView(a.label("☁ מביא ספרים מה-Drive", 18f, Col.TEXT, true)); addView(st) }
        box.addView(a.pill("עצירה (מה שירד נשמר)", false) { stop = true }, lp(MATCH, WRAP).m(0, a.dp(12), 0, 0))
        dlg = Sheet.make(a, box); dlg.setCancelable(false); dlg.show()
        Thread {
            var ok = 0; var skip = 0; var fail = 0
            for ((k, it) in items.withIndex()) {
                if (stop) break
                val sub = it.folder.split('/').filter { s -> s.isNotBlank() }.joinToString("/") { s -> safe(s) }
                val name = safe(it.name).let { n -> if (n.endsWith(".pdf", true)) n else "$n.pdf" }
                val dest = File(if (sub.isEmpty()) dir else File(dir, sub), name)
                ui { st.text = "${k + 1} / ${items.size}\n${it.name}" }
                try {
                    if (dest.exists() && (it.size == 0L || dest.length() == it.size)) { skip++; continue }
                    dest.parentFile?.mkdirs()
                    download(token, it, dest); ok++
                } catch (e: Throwable) { fail++ }
            }
            Prefs.put("driveLastSync", System.currentTimeMillis())
            ui {
                dlg.dismiss()
                if (Library.roots().none { dir.absolutePath == it || dir.absolutePath.startsWith("$it/") }) Library.setRoots(Library.roots() + dir.absolutePath)
                Indexer.start(a)
                done()
                Sheet.info(a, "הספרים מה-Drive הובאו ✓", "ירדו $ok ספרים" + (if (skip > 0) " · $skip כבר היו" else "") + (if (fail > 0) " · $fail לא ירדו" else "") +
                    ".\n\nהם בתיקיות כמו ב-Drive, נכנסו למאגר ונסרקים עכשיו ברקע. לסנכרון ספרים חדשים – לוחצים שוב \"חיבור ל-Google Drive\".")
            }
        }.start()
    }
}
```

## `app/src/main/java/com/chdv/searchplus/Excerpt.kt`

```kotlin
package com.chdv.searchplus

import android.content.Context
import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * For reading apps that cannot be told "open at page X and mark these words" (ReadEra, NeoReader…):
 * a small PDF made of the found page and the few pages after it, with the found words (or the
 * answering sentence) marked inside the PDF itself. The app opens it on the right page, marked.
 */
object Excerpt {
    private const val PAGES_AFTER = 4

    fun make(c: Context, path: String, page: Int, terms: List<String>, phrase: String?): File? = try {
        Indexer.initPdfBox(c)
        val dir = File(c.cacheDir, "excerpts").apply { mkdirs() }
        // keep only the last few
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
        val title = File(path).nameWithoutExtension.take(60)
        val out = File(dir, "$title – עמוד $page.pdf")
        Indexer.loadDoc(File(path)).use { src ->
            val n = src.numberOfPages
            val p0 = page.coerceIn(1, n)
            val boxes = find(c, src, path, p0, terms, phrase)
            PDDocument().use { dst ->
                for (k in p0..minOf(n, p0 + PAGES_AFTER)) dst.importPage(src.getPage(k - 1))
                if (boxes.isNotEmpty()) {
                    val pg = dst.getPage(0)
                    val box = pg.cropBox
                    val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.38f; strokingAlphaConstant = 0.9f }
                    PDPageContentStream(dst, pg, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.setGraphicsStateParameters(gs)
                        cs.setNonStrokingColor(1f, 0.75f, 0f)
                        cs.setStrokingColor(0.9f, 0.32f, 0f)
                        cs.setLineWidth(1.6f)
                        for (r in boxes) {
                            // the boxes are measured from the top of the page; PDF counts from the bottom
                            val x = box.lowerLeftX + r.left
                            val y = box.upperRightY - r.bottom
                            cs.addRect(x, y, r.width(), r.height())
                            cs.fillAndStroke()
                        }
                    }
                }
                dst.save(out)
            }
        }
        out
    } catch (e: Throwable) { null }

    /** Boxes (in PDF units, from the top-left) of the sentence, or of the searched words. */
    private fun find(c: Context, d: PDDocument, path: String, p: Int, terms: List<String>, phrase: String?): List<RectF> {
        val raw = PDFTextStripper().apply { startPage = p; endPage = p }.getText(d)
        val method = Heb.repairPage(raw).method
        val words = ArrayList<Pair<String, RectF>>()
        val st = object : PDFTextStripper() {
            override fun writeString(text: String?, tps: MutableList<TextPosition>?) {
                val list = tps ?: return
                val word = ArrayList<TextPosition>()
                fun flush() {
                    if (word.isEmpty()) return
                    val s = Heb.clean(Heb.applyMethod(word.joinToString("") { it.unicode ?: "" }, method)).filter { it.isLetterOrDigit() }
                    if (s.isNotEmpty()) {
                        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -1f; var b = -1f
                        for (tp in word) {
                            val h = tp.heightDir.coerceAtLeast(tp.fontSizeInPt * 0.7f)
                            l = minOf(l, tp.xDirAdj); r = maxOf(r, tp.xDirAdj + tp.widthDirAdj)
                            t = minOf(t, tp.yDirAdj - h); b = maxOf(b, tp.yDirAdj)
                        }
                        words.add(s to RectF(l - 1.5f, t - 1.5f, r + 1.5f, b + 2.5f))
                    }
                    word.clear()
                }
                for (tp in list) { val u = tp.unicode ?: ""; if (u.isBlank() || u == "," || u == "." || u == ":" || u == "-") flush() else word.add(tp) }
                flush()
            }
        }
        st.startPage = p; st.endPage = p
        st.getText(d)
        if (words.size < 5) {
            // a scanned page: OCR it, in PDF units
            val w = d.getPage(p - 1).cropBox.width.toInt().coerceAtLeast(100)
            words.clear(); words.addAll(Ocr.wordBoxes(c, path, p - 1, w))
        }
        val ph = phrase?.let { Heb.words(it).filter { w -> w.length > 1 } } ?: emptyList()
        if (ph.size >= 3) {
            val n = minOf(ph.size, 40); val want = ph.take(n)
            var best = -1; var bestScore = 0
            for (i in words.indices) {
                var sc = 0
                for (k in 0 until n) { val w = words.getOrNull(i + k)?.first ?: break; if (want.any { it == w || (it.length > 2 && (w.endsWith(it) || it.endsWith(w))) }) sc++ }
                if (sc > bestScore) { bestScore = sc; best = i }
            }
            if (best >= 0 && bestScore >= minOf(3, n)) return words.subList(best, minOf(words.size, best + n)).map { it.second }
        }
        // final letters compared as regular ones (מלך / מלכים), so every form of the word is marked
        fun fin(x: String) = x.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ')
        val want = terms.map { fin(it.removeSuffix("*")) }.filter { it.length > 1 }
        return words.filter { (w, _) -> val fw = fin(w); want.any { fw.contains(it) } }.map { it.second }
    }
}
```

## `app/src/main/java/com/chdv/searchplus/Indexer.kt`

```kotlin
package com.chdv.searchplus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

/** Live progress, read by the screens. */
object Progress {
    @Volatile var running = false
    @Volatile var phaseStart = 0L
    @Volatile var phase = ""
        set(v) { if (v != field) { phaseStart = System.currentTimeMillis() }; field = v }
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var current = ""
    @Volatile var cancel = false
    @Volatile var lastFinished = 0L
    /** A single book the user asked to OCR now. */
    @Volatile var ocrFile: Long? = null
    /** Set while the user is speaking / waiting for an answer, so the background work gives them the processor and memory. */
    @Volatile var paused = false

    fun waitWhilePaused() { var n = 0; while (paused && !cancel && n < 1200) { Thread.sleep(100); n++ } }

    fun text(): String = if (!running) "" else buildString {
        append(phase)
        if (total > 0) append("  $done / $total")
        val el = System.currentTimeMillis() - phaseStart
        if (total > 0 && done > 0 && done < total && el > 60_000) {
            val left = el / done * (total - done) / 60_000
            append(if (left >= 60) "  · עוד כ-${left / 60} שעות ${left % 60} דק׳" else "  · עוד כ-${left.coerceAtLeast(1)} דק׳")
        }
        if (current.isNotEmpty()) append("\n").append(current)
    }
}

/** Reads every PDF page by page and stores the words in the index. */
object Indexer {
    private var pdfboxReady = false

    fun initPdfBox(c: Context) {
        if (!pdfboxReady) { PDFBoxResourceLoader.init(c.applicationContext); pdfboxReady = true }
    }

    /** Collects the text of each page in one pass over the document. */
    private class PageStripper(val n: Int) : PDFTextStripper() {
        val pages = arrayOfNulls<String>(n)
        val buf = StringWriter()
        override fun startPage(page: PDPage?) {
            buf.buffer.setLength(0)
            super.startPage(page)
        }
        override fun endPage(page: PDPage?) {
            super.endPage(page)
            val i = currentPageNo - 1
            if (i in 0 until n) pages[i] = buf.toString()
            buf.buffer.setLength(0)
        }
    }

    fun loadDoc(f: File): PDDocument = PDDocument.load(f, "", MemoryUsageSetting.setupTempFileOnly())

    /** Raw text of every page (null where nothing could be read). */
    fun extract(f: File): List<String?> {
        loadDoc(f).use { doc ->
            val n = doc.numberOfPages
            val st = PageStripper(n)
            st.sortByPosition = Prefs.b("sortByPosition", false)
            // much faster on big books: skip the search for duplicated overlapping letters and article beads
            st.suppressDuplicateOverlappingText = false
            st.setShouldSeparateByBeads(false)
            st.lineSeparator = "\n"
            st.wordSeparator = " "
            st.writeText(doc, st.buf)
            return st.pages.map { raw -> raw?.let { prepare(it) } }
        }
    }

    /** Repair + clean the text of one page; null when there is no usable text. */
    fun prepare(raw: String): String? {
        if (raw.isBlank()) return null
        val fixed = Heb.repairPage(raw).text
        val t = Heb.clean(fixed)
        if (Heb.letterCount(t) < 12 || Heb.looksBroken(t)) return null
        return t
    }

    fun start(c: Context) {
        if (!Library.hasAccess(c) || Library.roots().isEmpty()) return
        val i = Intent(c, IndexService::class.java)
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
    }

    /** The whole job: scan folders, read new books, OCR, meaning index. */
    fun runAll(c: Context) {
        val db = Db.get()
        initPdfBox(c)
        Progress.cancel = false
        // 1. find files
        Progress.phase = "סורק את התיקיות…"; Progress.done = 0; Progress.total = 0; Progress.current = ""
        val known = HashMap<String, Book>()
        db.books().values.forEach { known[it.path] = it }
        val found = HashSet<String>()
        Library.walk({ f ->
            val p = f.absolutePath
            found.add(p)
            val b = known[p]
            if (b == null) db.addFile(f)
            else if (b.size != f.length() || b.mtime != f.lastModified()) db.markChanged(b.id, f)
            Progress.done = found.size
        }, { Progress.cancel })
        if (Progress.cancel) return
        // books that were deleted or are outside the library folders now
        known.values.filter { it.path !in found }.forEach { db.removeFile(it.id) }

        // 2. read text
        // small books first: within minutes most of the library is searchable; big ones follow
        val pending = db.pendingFiles().sortedBy { (_, p) -> File(p).length() }
        Progress.phase = "קורא ספרים"; Progress.total = pending.size; Progress.done = 0
        for ((id, path) in pending) {
            if (Progress.cancel) return
            Progress.waitWhilePaused()
            Progress.current = File(path).name
            try {
                val texts = extract(File(path))
                db.saveBook(id, texts)
            } catch (e: OutOfMemoryError) {
                System.gc()
                db.markError(id, "הקובץ גדול מדי לזיכרון המכשיר")
            } catch (e: Throwable) {
                db.markError(id, e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
            Progress.done++
        }

        // the library has been read once – from now on only new or changed books are read
        if (!Progress.cancel) Prefs.put("firstScanDone", true)

        // 3. meaning index (before OCR: OCR of a big library can take days, and must not hold this back)
        if (Prefs.b("semantic", false) && Semantic.ready(c)) {
            try { Semantic.indexPending(c) } catch (e: Throwable) { Semantic.lastError = e.message ?: e.javaClass.simpleName }
        }
        if (Progress.cancel) return

        // 4. OCR of scanned pages
        if (Prefs.b("ocrAuto", true) && Ocr.ready(c)) Ocr.runPending(c, null)
        if (Progress.cancel) return

        // 5. pages that OCR just made readable also go into the meaning index
        if (Prefs.b("semantic", false) && Semantic.ready(c)) {
            try { Semantic.indexPending(c) } catch (e: Throwable) { Semantic.lastError = e.message ?: e.javaClass.simpleName }
        }
    }

    /** Re-read one book now (from the book menu). */
    fun reindex(c: Context, b: Book) {
        val f = File(b.path)
        Db.get().markChanged(b.id, f)
        start(c)
    }
}

/** OCR (text recognition) for scanned pages, with Tesseract and its Hebrew model. */
object Ocr {
    private const val URL_HEB = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/heb.traineddata"
    private const val URL_HEB_BEST = "https://github.com/tesseract-ocr/tessdata_best/raw/main/heb.traineddata"

    fun dir(c: Context) = File(c.filesDir, "tess")
    private fun model(c: Context) = File(dir(c), "tessdata/heb.traineddata")
    /** The Hebrew model ships inside the APK (assets/tessdata); copy it out on first use. */
    fun ready(c: Context): Boolean {
        if (model(c).length() > 100_000) return true
        return try {
            model(c).parentFile?.mkdirs()
            c.assets.open("tessdata/heb.traineddata").use { i -> FileOutputStream(model(c)).use { o -> i.copyTo(o) } }
            model(c).length() > 100_000
        } catch (e: Exception) { model(c).delete(); false }
    }

    fun download(c: Context, best: Boolean, progress: (Int) -> Unit) {
        val f = model(c)
        f.parentFile?.mkdirs()
        Net.download(if (best) URL_HEB_BEST else URL_HEB, f, progress)
    }

    fun delete(c: Context) { dir(c).deleteRecursively() }

    /** OCR all pages that have no text (or only those of one book). */
    fun runPending(c: Context, onlyFile: Long?) {
        val db = Db.get()
        val api = TessBaseAPI()
        if (!api.init(dir(c).absolutePath, "heb")) { api.recycle(); return }
        try {
            Progress.phase = "מזהה טקסט בעמודים סרוקים (OCR)"
            Progress.total = db.emptyPages(1_000_000, onlyFile).size
            Progress.done = 0
            while (!Progress.cancel) {
                val batch = db.emptyPages(50, onlyFile)
                if (batch.isEmpty()) break
                var lastFile = -1L
                var renderer: PdfRenderer? = null
                var pfd: ParcelFileDescriptor? = null
                try {
                    for ((pageId, fileId, page) in batch) {
                        if (Progress.cancel) break
                        Progress.waitWhilePaused()
                        val b = db.book(fileId)
                        if (b == null) { db.saveOcr(pageId, fileId, null); continue }
                        Progress.current = "${b.title} – עמוד $page"
                        if (fileId != lastFile) {
                            renderer?.close(); pfd?.close()
                            pfd = ParcelFileDescriptor.open(File(b.path), ParcelFileDescriptor.MODE_READ_ONLY)
                            renderer = PdfRenderer(pfd)
                            lastFile = fileId
                        }
                        val text = try { ocrPage(api, renderer!!, page - 1) } catch (e: Throwable) { null }
                        db.saveOcr(pageId, fileId, text?.let { Indexer.prepare(it) })
                        Progress.done++
                    }
                } finally {
                    try { renderer?.close() } catch (e: Exception) {}
                    try { pfd?.close() } catch (e: Exception) {}
                }
            }
        } finally {
            api.recycle()
        }
    }

    /**
     * Where each word is on a scanned page (no text layer): the page is read with OCR and every
     * word's box returned, in the coordinates of an image [width] pixels wide – so the reader can
     * mark the searched words even in old scanned books.
     */
    fun wordBoxes(c: Context, path: String, index: Int, width: Int): List<Pair<String, android.graphics.RectF>> {
        if (!ready(c)) return emptyList()
        val out = ArrayList<Pair<String, android.graphics.RectF>>()
        val api = TessBaseAPI()
        if (!api.init(dir(c).absolutePath, "heb")) { api.recycle(); return out }
        try {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    if (index < 0 || index >= r.pageCount) return out
                    r.openPage(index).use { p ->
                        val w = 1700
                        val h = (w.toLong() * p.height / p.width.coerceAtLeast(1)).toInt().coerceIn(100, 3000)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        api.setImage(bmp)
                        api.getUTF8Text()
                        val k = width.toFloat() / w
                        val it = api.resultIterator
                        if (it != null) {
                            it.begin()
                            do {
                                val t = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD) ?: continue
                                val b = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                                val word = Heb.clean(t).filter { ch -> ch.isLetterOrDigit() }
                                if (word.isNotEmpty()) out.add(word to android.graphics.RectF(b.left * k - 3, b.top * k - 3, b.right * k + 3, b.bottom * k + 3))
                            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                            it.delete()
                        }
                        api.clear()
                        bmp.recycle()
                    }
                }
            }
        } catch (e: Throwable) {
        } finally { api.recycle() }
        return out
    }

    private fun ocrPage(api: TessBaseAPI, r: PdfRenderer, index: Int): String? {
        if (index < 0 || index >= r.pageCount) return null
        r.openPage(index).use { p ->
            val w = 1700
            val h = (w.toLong() * p.height / p.width.coerceAtLeast(1)).toInt().coerceIn(100, 3000)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            api.setImage(bmp)
            val t = api.getUTF8Text()
            api.clear()
            bmp.recycle()
            return t
        }
    }
}

/** Small download helper that follows redirects across hosts. */
object Net {
    fun open(url: String): HttpURLConnection {
        var u = url
        repeat(8) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "SearchPlus-Android")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw java.io.IOException("redirect without location")
                u = URL(URL(u), loc).toString()
            } else {
                if (code >= 400) { c.disconnect(); throw java.io.IOException("HTTP $code") }
                return c
            }
        }
        throw java.io.IOException("too many redirects")
    }

    fun text(url: String): String {
        val c = open(url)
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    fun download(url: String, out: File, progress: (Int) -> Unit) {
        val tmp = File(out.parentFile, out.name + ".part")
        val c = open(url)
        try {
            val total = c.contentLength.toLong()
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                FileOutputStream(tmp).use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; progress(pct) }
                        }
                    }
                }
            }
        } finally { c.disconnect() }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) throw java.io.IOException("rename failed")
    }
}

/** Runs the indexing in the background with a notification, so it continues when the screen is off. */
class IndexService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun note(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("idx") == null) {
            nm.createNotificationChannel(NotificationChannel("idx", "סריקת ספרים", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, "idx")
        return b.setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("חיפוש פלוס – סורק ספרים")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, note("מתחיל…"))
        if (Progress.running) return START_NOT_STICKY
        Progress.running = true
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "searchplus:index").apply {
            setReferenceCounted(false); acquire(16 * 3600 * 1000L)
        }
        val nm = getSystemService(NotificationManager::class.java)
        val ticker = object : Runnable {
            override fun run() {
                if (!Progress.running) return
                nm.notify(1, note(Progress.phase + if (Progress.total > 0) "  ${Progress.done}/${Progress.total}" else ""))
                App.main.postDelayed(this, 5000)
            }
        }
        App.main.postDelayed(ticker, 3000)
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                Indexer.runAll(this)
                val one = Progress.ocrFile
                if (one != null) { Progress.ocrFile = null; if (Ocr.ready(this)) Ocr.runPending(this, one) }
            } catch (e: Throwable) {
                // never crash the app because of one bad file
            } finally {
                Progress.running = false
                Progress.phase = ""; Progress.current = ""; Progress.total = 0; Progress.done = 0
                Progress.lastFinished = System.currentTimeMillis()
                try { wake?.release() } catch (e: Exception) {}
                ui { stopForeground(true); stopSelf() }
            }
        }.start()
        return START_NOT_STICKY
    }
}
```

## `app/src/main/java/com/chdv/searchplus/Jump.kt`

```kotlin
package com.chdv.searchplus

import com.chdv.searchplus.core.HebNum
import com.chdv.searchplus.core.PageFinder
import com.chdv.searchplus.core.Ref
import com.chdv.searchplus.core.RefParser

/**
 * Going straight to a place: "ברכות דף כז ע״א" → every edition of Berachos in the library, each
 * opened at that daf. The page is found from the daf marks printed at the top of the pages (read
 * during the scan); where a book has no text, from the user's own correction, or an estimate.
 */
object Jump {
    class Target(val book: Book, val page: Int, val exact: Boolean, val ref: Ref, val amudIndex: Int?) {
        val note: String get() = if (exact) "עמוד $page" else "עמוד $page (משוער – אפשר לתקן בקורא)"
    }

    /** Names of files and folders, with their abbreviations written out (a folder "אדה״ז" = אדמו״ר הזקן). */
    private fun norm(s: String): String {
        val t = s.replace('_', ' ').replace('-', ' ').replace('.', ' ')
        val n = RefParser.normalize(t)
        val e = RefParser.normalize(com.chdv.searchplus.core.Abbr.expand(t))
        return " " + (if (e != n) "$n $e" else n) + " "
    }

    private fun has(hay: String, phrase: String): Boolean {
        val p = RefParser.normalize(phrase)
        return hay.contains(" $p ") || hay.contains(" ב$p ") || hay.contains(" ו$p ") || hay.contains(" ה$p ") || hay.contains(" מ$p ") || hay.contains(" ל$p ")
    }

    /** Letters without the vowel letters – so "ליקוטי" = "לקוטי", "שולחן" = "שלחן". */
    private fun key(w: String) = w.filter { it !in "אהויע" }.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ')

    /**
     * The books for a request (by the names they have on the device), each opened at the place.
     * [stale] lets a newer request stop an older one at once.
     */
    fun resolve(ref: Ref, raw: String? = null, stale: () -> Boolean = { false }, progress: (String) -> Unit = {}): List<Target> {
        val request = raw ?: (listOf(ref.work) + ref.extra).joinToString(" ")
        val manyVolumes = ref.siman != null || ref.chapter != null
        progress("🔎 מחפש בשמות ${Db.get().books().size} הספרים…")
        val cands = Catalog.find(request, ref, if (manyVolumes) 10 else 6)
        if (cands.isNotEmpty()) progress("📚 נמצאו ${cands.size} ספרים מתאימים – מחפש בהם את המקום…")
        val out = ArrayList<Target>()
        for ((k, m) in cands.withIndex()) {
            if (stale()) return out
            progress("📖 ${k + 1}/${cands.size} · מחפש את ${ref.label()} ב„${m.book.title}”…")
            out.add(try { locate(m.book, ref) } catch (e: Throwable) { Target(m.book, 1, false, ref, null) })
        }
        // a siman / chapter: a work in several volumes – keep the volumes where it was really found
        if (manyVolumes && out.any { it.exact }) return out.filter { it.exact }
        return out
    }

    /** No such book: the closest names in the library ("did you mean…"). */
    fun suggest(text: String, max: Int = 5): List<Book> = Catalog.find(text, null, max).map { it.book }


    private fun HebNum0(w: String) = com.chdv.searchplus.core.HebNum.gematria(w) == null || w.length > 3

    private fun locate(b: Book, ref: Ref): Target {
        val pages = Db.get().pageTexts(b.id)
        val count = b.pages.coerceAtLeast(1)
        when (ref.kind) {
            Ref.Kind.GEMARA -> {
                val daf = ref.daf ?: return Target(b, 1, false, ref, null)
                val target = PageFinder.amudIndex(daf, ref.amud ?: 1)
                val anchors = ArrayList<Pair<Int, Int>>()
                for ((p, t) in pages) for (x in PageFinder.dafMarks(t, ref.work)) anchors.add(p to x)
                // the user's own corrections are the most reliable anchors
                calibrations(b.path).forEach { (p, x) -> repeat(3) { anchors.add(p to x) } }
                val exactPage = anchors.filter { it.second == target }.map { it.first }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                if (exactPage != null) return Target(b, exactPage, true, ref, target)
                val est = PageFinder.estimate(anchors, target, count)
                // a book with no daf marks at all: Vilna layout, one amud per page after a few title pages
                return Target(b, est ?: (target + 3).coerceIn(1, count), est != null && anchors.size >= 4, ref, target)
            }
            Ref.Kind.SICHOS -> {
                ref.page?.let { pr -> val (pg, ok) = PageFinder.pageOfPrinted(pages, pr, count); return Target(b, pg, ok, ref, null) }
                val parsha = ref.parsha ?: return Target(b, 1, true, ref, null)
                val words = RefParser.normalize(parsha).split(' ')
                val starts = pages.keys.sorted().filter { PageFinder.headerHas(pages[it]!!, words) }
                if (starts.isEmpty()) {
                    // search the whole page text for the parsha name as a heading word
                    val any = pages.keys.sorted().firstOrNull { p -> RefParser.normalize(pages[p]!!.take(600)).split(' ').containsAll(words) }
                    return Target(b, any ?: 1, any != null, ref, null)
                }
                val first = starts.first()
                val n = ref.sicha
                if (n != null && n > 1) {
                    val mark = com.chdv.searchplus.core.HebNum.toHeb(n)
                    val inRun = starts.filter { it - first < 120 }
                    inRun.firstOrNull { p -> Regex("(^|\\n)\\s*$mark\\s*[.)]").containsMatchIn(pages[p]!!.take(400)) }?.let { return Target(b, it, true, ref, null) }
                }
                return Target(b, first, true, ref, null)
            }
            Ref.Kind.GENERIC -> {
                val sorted = pages.keys.sorted()
                ref.chapter?.let { c -> sorted.firstOrNull { PageFinder.hasNumbered(pages[it]!!, "פרק", c) }?.let { return Target(b, it, true, ref, null) } }
                ref.siman?.let { s ->
                    sorted.firstOrNull { PageFinder.hasNumbered(pages[it]!!, "סימן", s) }?.let { return Target(b, it, true, ref, null) }
                    // the siman starts in the middle of a page
                    val mark = Regex("(^|\\s)סימן\\s+${HebNum.toHeb(s)}(\\s|$)")
                    sorted.firstOrNull { mark.containsMatchIn(RefParser.normalize(pages[it]!!)) }?.let { return Target(b, it, true, ref, null) }
                }
                ref.parsha?.let { pr -> sorted.firstOrNull { PageFinder.headerHas(pages[it]!!, RefParser.normalize(pr).split(' ')) }?.let { return Target(b, it, true, ref, null) } }
                ref.page?.let { pr -> val (pg, ok) = PageFinder.pageOfPrinted(pages, pr, count); return Target(b, pg, ok, ref, null) }
                return Target(b, 1, false, ref, null)
            }
        }
    }

    // ---------- the user's corrections ("this is daf X") ----------

    private fun calibrations(path: String): List<Pair<Int, Int>> =
        Prefs.list("dafCal").filter { it.substringBefore('|') == path }.mapNotNull {
            val p = it.split('|'); if (p.size == 3) (p[1].toIntOrNull() ?: return@mapNotNull null) to (p[2].toIntOrNull() ?: return@mapNotNull null) else null
        }

    fun calibrate(path: String, page: Int, amudIndex: Int) {
        val l = Prefs.list("dafCal").filterNot { it.startsWith("$path|") && it.split('|').getOrNull(2) == amudIndex.toString() }.toMutableList()
        l.add(0, "$path|$page|$amudIndex")
        Prefs.putList("dafCal", l.take(400))
    }
}
```

## `app/src/main/java/com/chdv/searchplus/Library.kt`

```kotlin
package com.chdv.searchplus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Where the books are, how folders become categories, and file access permission. */
object Library {

    fun storageRoot(): File = Environment.getExternalStorageDirectory()

    /** All storage volumes (internal + SD card). */
    fun volumes(c: Context): List<File> {
        val out = ArrayList<File>()
        out.add(storageRoot())
        c.getExternalFilesDirs(null).filterNotNull().forEach { f ->
            val p = f.absolutePath
            val i = p.indexOf("/Android/")
            if (i > 0) {
                val v = File(p.substring(0, i))
                if (out.none { it.absolutePath == v.absolutePath }) out.add(v)
            }
        }
        return out
    }

    /** Library folders chosen by the user. Empty list = not chosen yet. */
    fun roots(): List<String> = Prefs.list("roots")

    fun setRoots(l: List<String>) = Prefs.putList("roots", l.distinct())

    fun rootOf(path: String): String? = roots().filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }

    fun rootName(root: String): String = if (root == storageRoot().absolutePath) "זיכרון פנימי" else File(root).name

    /**
     * Category path of a folder: the folders under the library root. With one library folder the
     * first level is its sub folders (הלכה, חסידות חב"ד ...). With several library folders the
     * first level is the library folder itself.
     */
    fun categoryOf(dir: String): List<String> {
        val root = rootOf(dir) ?: return listOf(File(dir).name)
        val rel = dir.removePrefix(root).trim('/')
        val parts = if (rel.isEmpty()) emptyList() else rel.split('/')
        return if (roots().size > 1) listOf(rootName(root)) + parts else parts
    }

    fun hasAccess(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(c, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun askAccess(a: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.packageName)))
            } catch (e: Exception) {
                a.safeStart(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(a, arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
        }
    }

    private val SKIP = setOf("Android", "LOST.DIR", ".thumbnails", "\$RECYCLE.BIN", "System Volume Information", "_כפולים")

    /** Every PDF under the library folders. */
    fun walk(onFile: (File) -> Unit, cancelled: () -> Boolean) {
        val seen = HashSet<String>()
        for (r in roots()) {
            val stack = ArrayDeque<File>()
            stack.add(File(r))
            while (stack.isNotEmpty()) {
                if (cancelled()) return
                val d = stack.removeLast()
                val canon = try { d.canonicalPath } catch (e: Exception) { d.absolutePath }
                if (!seen.add(canon)) continue
                val kids = d.listFiles() ?: continue
                for (k in kids) {
                    if (k.name.startsWith(".")) continue
                    if (k.isDirectory) {
                        if (k.name !in SKIP) stack.add(k)
                    } else if (k.name.endsWith(".pdf", true) && k.length() > 0) onFile(k)
                }
            }
        }
    }

    fun uriFor(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)

    /** Apps on the device that can open a PDF. */
    /**
     * Every app on the device that can open a PDF in any way: readers (ReadEra, NeoReader…), note apps,
     * file managers. Apps register differently (PDF type, any document, any file, by file name), so all
     * the ways are asked and merged.
     */
    fun pdfApps(c: Context): List<android.content.pm.ResolveInfo> {
        val pm = c.packageManager
        val asks = ArrayList<Intent>()
        for (scheme in listOf("content://x/y.pdf", "file:///sdcard/y.pdf")) {
            for (type in listOf("application/pdf", "application/*", "*/*", null)) {
                val u = Uri.parse(scheme)
                asks.add(Intent(Intent.ACTION_VIEW).apply { if (type == null) data = u else setDataAndType(u, type) })
            }
        }
        val seen = LinkedHashMap<String, android.content.pm.ResolveInfo>()
        for (i in asks) {
            val found = try { pm.queryIntentActivities(i, android.content.pm.PackageManager.MATCH_ALL) } catch (e: Exception) { emptyList() }
            for (r in found) {
                val a = r.activityInfo ?: continue
                if (a.packageName == c.packageName) continue
                val key = a.packageName + "/" + a.name
                if (key !in seen) seen[key] = r
            }
        }
        // one entry per app (the first, most specific activity found)
        return seen.values.distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(pm).toString() }
    }

    fun appName(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    /**
     * The first time a book is opened: show every app that can open it (and the built-in reader),
     * and remember the choice as the default. Can be changed later in the settings.
     */
    fun chooseDefault(a: Activity, then: () -> Unit) {
        val pm = a.packageManager
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("📖 הקורא של חיפוש פלוס – עמוד מדויק + סימון המילים" to {
            Prefs.put("openWith", "internal"); Prefs.put("readerChosen", true); then()
        })
        for (r in pdfApps(a)) {
            items.add(r.loadLabel(pm).toString() to {
                Prefs.put("openWith", "external"); Prefs.put("readerPkg", r.activityInfo.packageName); Prefs.put("readerCls", r.activityInfo.name)
                Prefs.put("readerChosen", true)
                a.toast("מעכשיו ספרים ייפתחו ב-${r.loadLabel(pm)}. אפשר לשנות בהגדרות")
                then()
            })
        }
        Sheet.actions(a, "באיזו אפליקציה לפתוח ספרים? (הבחירה תישמר)", items)
    }

    /**
     * Open a PDF in another app. With a default reader chosen in the settings it opens there directly
     * (no "open with" question). The page number is sent in the ways readers know; the searched words
     * are copied, so they can be pasted into the reader's own search to mark them.
     */
    fun openExternal(a: Activity, path: String, page: Int, terms: List<String> = emptyList(), chooser: Boolean = false, phrase: String? = null) {
        // the app can't be told the page and the words: give it the found page, marked, as a small PDF
        // also for a place without search words (ליקוטי שיחות חלק כד עמוד 358): the app gets that page first
        if ((terms.isNotEmpty() || phrase != null || page > 1) && Prefs.b("excerpt", true)) {
            a.toast("מכין את העמוד המסומן…")
            Thread {
                val ex = Excerpt.make(a, path, page, terms, phrase)
                ui {
                    if (ex != null) openExternal(a, ex.absolutePath, 1, emptyList(), chooser)
                    else openExternal(a, path, page, emptyList(), chooser)
                }
            }.start()
            return
        }
        val f = File(path)
        val uri = uriFor(a, f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra("page", page).putExtra("PAGE", page).putExtra("pageNumber", page).putExtra("page_number", page)
            .putExtra("pageIndex", page - 1).putExtra("start_page", page).putExtra("android.intent.extra.PAGE", page)
            .putExtra("com.onyx.intent.extra.PAGE", page - 1)
        if (terms.isNotEmpty() && Prefs.b("copyTerms", true)) {
            val words = terms.joinToString(" ") { it.removeSuffix("*") }
            (a.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("search", words))
            a.toast("עמוד $page · המילים \"$words\" הועתקו – אפשר להדביק בחיפוש של הקורא")
        }
        val pkg = Prefs.s("readerPkg", ""); val cls = Prefs.s("readerCls", "")
        if (!chooser && pkg.isNotEmpty()) {
            val direct = Intent(i).setClassName(pkg, cls)
            a.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (a.safeStart(direct)) return
            // the chosen app was removed: ask again
            Prefs.put("readerPkg", ""); Prefs.put("readerCls", "")
        }
        val apps = pdfApps(a)
        if (apps.isEmpty()) { if (!a.safeStart(Intent.createChooser(i, "פתיחה באמצעות"))) a.toast("לא נמצאה אפליקציה לפתיחת PDF"); return }
        val pm = a.packageManager
        Sheet.actions(a, if (chooser) "פתיחה באמצעות" else "באיזו אפליקציה לפתוח? (הבחירה תישמר – אפשר לשנות בהגדרות)", apps.map { r ->
            r.loadLabel(pm).toString() to {
                if (!chooser) {
                    Prefs.put("readerPkg", r.activityInfo.packageName); Prefs.put("readerCls", r.activityInfo.name)
                    a.toast("מעכשיו ספרים ייפתחו ב-${r.loadLabel(pm)}. אפשר לשנות בהגדרות")
                }
                a.grantUriPermission(r.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (!a.safeStart(Intent(i).setClassName(r.activityInfo.packageName, r.activityInfo.name))) a.toast("לא ניתן לפתוח")
            }
        } + listOf("עוד אפליקציות… (הרשימה של המכשיר)" to {
            if (!a.safeStart(Intent.createChooser(i, "פתיחה באמצעות"))) a.toast("לא נמצאה אפליקציה")
        }))
    }

    // ---------- favourites, history, recent books ----------

    fun favorites(): List<String> = Prefs.list("favs")
    fun isFavorite(key: String) = key in favorites()
    fun toggleFavorite(key: String): Boolean {
        val l = Prefs.list("favs")
        val on = if (key in l) { l.remove(key); false } else { l.add(0, key); true }
        Prefs.putList("favs", l)
        return on
    }

    fun history(): List<String> = Prefs.list("history")
    fun addHistory(q: String) {
        if (q.isBlank()) return
        val l = Prefs.list("history"); l.remove(q); l.add(0, q)
        Prefs.putList("history", l.take(40))
    }

    fun recent(): List<String> = Prefs.list("recent")
    fun addRecent(path: String, page: Int) {
        val l = Prefs.list("recent").filterNot { it.substringBefore('|') == path }.toMutableList()
        l.add(0, "$path|$page")
        Prefs.putList("recent", l.take(30))
    }
    fun lastPage(path: String): Int = recent().firstOrNull { it.substringBefore('|') == path }?.substringAfter('|')?.toIntOrNull() ?: 1
}
```

## `app/src/main/java/com/chdv/searchplus/MainActivity.kt`

```kotlin
package com.chdv.searchplus

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chdv.searchplus.core.RefParser
import java.io.File
import java.util.concurrent.Executors

class MainActivity : BaseActivity() {

    private val exec = Executors.newSingleThreadExecutor()
    private val snipExec = Executors.newSingleThreadExecutor()
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var optRow: LinearLayout
    private lateinit var crumbs: FlowLayout
    private lateinit var cats: FlowLayout
    private lateinit var summary: TextView
    private lateinit var jumpBar: TextView
    private lateinit var gate: LinearLayout
    private lateinit var gateBar: android.widget.ProgressBar
    private lateinit var gateText: TextView
    private lateinit var gateSkip: TextView
    private var gateSkipped = false
    private var gateShownAt = 0L

    private fun updateGate() {
        val show = !gateSkipped && Library.hasAccess(this) && Library.roots().isNotEmpty() && !Prefs.b("firstScanDone", false)
        if (!show) { gate.visibility = View.GONE; return }
        if (gate.visibility != View.VISIBLE) { gate.visibility = View.VISIBLE; gateShownAt = System.currentTimeMillis() }
        val reading = Progress.phase == "קורא ספרים" && Progress.total > 0
        gateBar.isIndeterminate = !reading
        if (reading) gateBar.progress = (Progress.done * 1000L / Progress.total).toInt()
        gateText.text = if (Progress.running) Progress.text() else "מתחיל…"
        // a way out after a while (e.g. a library so big that it takes all night)
        if (System.currentTimeMillis() - gateShownAt > 60_000) gateSkip.visibility = View.VISIBLE
    }
    private lateinit var optScroll: View

    /** The rows that only matter for results; hidden at home so a small phone screen has room to scroll. */
    private fun searchChrome(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        optScroll.visibility = v; crumbs.visibility = v; cats.visibility = v; summary.visibility = v
        if (!on) jumpBar.visibility = View.GONE
    }
    private lateinit var list: RecyclerView
    private lateinit var home: ScrollView
    private lateinit var homeBox: LinearLayout
    private lateinit var setupCard: LinearLayout
    private val adapter = ResultAdapter()

    private var semantic = false
    private var results: Results? = null
    private var prefix: List<String> = emptyList()
    private var expr = ""
    private val expanded = HashSet<Long>()
    private var searchToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        semantic = Prefs.b("lastSemantic", false)
        build()
        applyScreen(intent)
        Catalog.warm()
    }

    /** Which screen this is: home, "meaning" (question & answer only) or "words" (word search only). */
    private var screenKind = "home"
    private lateinit var titleView: TextView
    private lateinit var subView: TextView

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyScreen(intent)
    }

    private fun applyScreen(i: Intent?) {
        val k = i?.getStringExtra("screen") ?: return
        screenKind = k
        when (k) {
            "meaning" -> {
                if (!Semantic.ready(this)) { semantic = false; setMode(true) } else semantic = true
                titleView.text = "💡 שאלה ותשובה"; subView.text = "שואלים במילים שלך – ומקבלים את המשפט שעונה"
                input.hint = "שאלו שאלה… למשל: מה מברכים על פרי העץ?"
            }
            "words" -> {
                semantic = false
                titleView.text = "🔤 חיפוש מילים"; subView.text = "מילה או ביטוי בתוך כל הספרים"
                input.hint = "מילה או ביטוי… למשל: רבי שמעון"
            }
            else -> { titleView.text = "חיפוש פלוס"; subView.text = "חיפוש מעמיק בתוך הספרים שלך"; input.hint = "מה לחפש? או לחצו 🎤 ודברו" }
        }
        modeRow.visibility = if (k == "home") View.VISIBLE else View.GONE
        buildModes()
        val q = i.getStringExtra("q")
        if (!q.isNullOrBlank()) { input.setText(q); doSearch() } else if (results == null) showHome()
    }

    private fun openVoice() = startActivity(Intent(this, VoiceActivity::class.java).putExtra("listen", true))

    override fun onResume() {
        super.onResume()
        refreshSetup()
        // the library is read once; afterwards only a quick check for new books, once a day
        val first = !Prefs.b("firstScanDone", false)
        val due = System.currentTimeMillis() - Prefs.l("lastAutoScan", 0L) > 24L * 3600 * 1000
        if (Library.hasAccess(this) && Library.roots().isNotEmpty() && !Progress.running && (first || (due && Prefs.b("autoScan", true)))) {
            Prefs.put("lastAutoScan", System.currentTimeMillis())
            Indexer.start(this)
        }
        updateGate()
        Updater.dailyCheck(this)
        checkLastExit()
        val crash = File(filesDir, "crash.txt")
        if (crash.exists()) {
            val t = try { crash.readText() } catch (e: Exception) { "" }
            crash.delete()
            Sheet.confirm(this, "התוכנה נתקלה בשגיאה", "בפעם הקודמת משהו השתבש. הפרטים הטכניים נשמרו – אפשר להעתיק אותם ולשלוח למפתח.", "העתקת הפרטים") {
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("crash", t))
                toast("הועתק")
            }
        }
        tick.run()
        if (results == null) showHome()
    }

    override fun onPause() {
        super.onPause()
        App.main.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val t = Progress.text()
            if (gate.visibility == View.VISIBLE || !Prefs.b("firstScanDone", false)) updateGate()
            if (t.isEmpty()) {
                status.visibility = View.GONE
            } else {
                status.visibility = View.VISIBLE
                status.text = "⟳ $t"
            }
            App.main.postDelayed(this, 2000)
        }
    }

    // ---------------- layout ----------------

    private fun build() {
        val root = vbox().apply { setBackgroundColor(Col.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL }

        // header
        val head = hbox().apply { setPadding(dp(14), dp(10), dp(8), dp(6)) }
        // tapping the name ("פלוס") or ☰ opens the side menu with every part of the app
        head.addView(Nav.button(this))
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); setOnClickListener { Nav.open(this@MainActivity) } }, lp(dp(38), dp(38)).m(0, 0, 0, 0))
        titleView = label("חיפוש פלוס", 22f, Col.TEXT, true)
        subView = label("חיפוש מעמיק בתוך הספרים שלך", 12.5f, Col.SUB)
        head.addView(vbox().apply {
            addView(titleView); addView(subView)
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { Nav.open(this@MainActivity) }
        }, lp(0, WRAP, 1f))
        head.addView(headerButton("🎤") { openVoice() })
        head.addView(headerButton("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(head, lp(MATCH, WRAP))

        status = label("", 13f, Col.ACC).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundBg(Col.ACC_LIGHT, dp(10).toFloat())
            visibility = View.GONE
            setOnClickListener { progressSheet() }
        }
        root.addView(status, lp(MATCH, WRAP).m(dp(12), dp(2), dp(12), dp(4)))

        setupCard = vbox()
        root.addView(setupCard, lp(MATCH, WRAP))

        // search box
        val box = hbox().apply {
            background = roundBg(Col.CARD, dp(26).toFloat(), dp(1.5f), Col.ACC)
            setPadding(dp(14), dp(2), dp(4), dp(2))
        }
        input = EditText(this).apply {
            hint = "מה לחפש? או לחצו 🎤 ודברו"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f * scale())
            setTextColor(Col.TEXT)
            setHintTextColor(0xFF8A8A8A.toInt())
            background = null
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            setOnEditorActionListener { _, a, e ->
                if (a == EditorInfo.IME_ACTION_SEARCH || e?.keyCode == KeyEvent.KEYCODE_ENTER) { doSearch(); true } else false
            }
        }
        box.addView(input, lp(0, WRAP, 1f))
        box.addView(label("✕", 18f, Col.SUB).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { input.setText(""); results = null; jumpBar.visibility = View.GONE; showHome() }
        })
        box.addView(label("🎤", 22f, Col.ACC, true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = pressBg(Color.TRANSPARENT, Col.ACC_LIGHT, 18)
            contentDescription = "חיפוש קולי"
            setOnClickListener { openVoice() }
        })
        box.addView(pill("חיפוש") { doSearch() }, lp(WRAP, WRAP))
        root.addView(box, lp(MATCH, WRAP).m(dp(12), dp(4), dp(12), dp(6)))

        modeRow = hbox().apply { setPadding(dp(12), 0, dp(12), 0) }
        root.addView(modeRow, lp(MATCH, WRAP))
        optRow = hbox().apply { setPadding(dp(12), dp(6), dp(12), 0) }
        optScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(optRow)
        }
        root.addView(optScroll, lp(MATCH, WRAP))
        buildModes()

        crumbs = FlowLayout(this).apply { setPadding(dp(12), dp(8), dp(12), 0); gap = dp(2) }
        root.addView(crumbs, lp(MATCH, WRAP))
        cats = FlowLayout(this).apply { setPadding(dp(12), dp(6), dp(12), dp(2)) }
        root.addView(cats, lp(MATCH, WRAP))
        jumpBar = label("", 15.5f, Color.WHITE, true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = pressBg(Col.ACC, 0xFF0F2540.toInt(), 14)
            visibility = View.GONE
        }
        root.addView(jumpBar, lp(MATCH, WRAP).m(dp(12), dp(6), dp(12), 0))
        summary = label("", 13.5f, Col.SUB).apply { setPadding(dp(14), dp(6), dp(14), dp(4)) }
        root.addView(summary, lp(MATCH, WRAP))
        root.addView(divider())

        val stack = android.widget.FrameLayout(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            itemAnimator = null
        }
        stack.addView(list, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        homeBox = vbox().apply { setPadding(dp(14), dp(8), dp(14), dp(24)) }
        home = ScrollView(this).apply { addView(homeBox) }
        stack.addView(home, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(stack, lp(MATCH, 0, 1f))
        // first time: a full screen until the whole library has been read once
        gate = vbox().apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            background = Col.gradient(Col.ACC, Col.ACC2, 0f)
            isClickable = true
            visibility = View.GONE
        }
        gate.addView(label("📚", 64f, Color.WHITE).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
        gate.addView(label("לומד את הספרייה שלך", 26f, Color.WHITE, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, dp(12), 0, dp(8)) })
        gate.addView(label("פעם אחת בלבד: התוכנה קוראת כל ספר ושומרת את מה שכתוב בו. אחר כך כל חיפוש מיידי, ולא סורקים שוב – רק ספרים חדשים.\nכדאי לחבר לטעינה. אפשר לכבות את המסך – העבודה ממשיכה.", 15.5f, 0xEEFFFFFF.toInt()).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
        gateBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = 0 }
        gate.addView(gateBar, lp(MATCH, dp(18)).m(0, dp(24), 0, dp(10)))
        gateText = label("מתחיל…", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
        gate.addView(gateText, lp(MATCH, WRAP))
        gateSkip = label("להשתמש בינתיים בספרים שכבר נקראו ›", 14f, 0xCCFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, dp(28), 0, 0)
            visibility = View.GONE
            setOnClickListener { gateSkipped = true; updateGate() }
        }
        gate.addView(gateSkip, lp(MATCH, WRAP))
        val frame = android.widget.FrameLayout(this)
        frame.addView(root, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(gate, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(frame)
    }

    private fun buildModes() {
        modeRow.removeAllViews()
        modeRow.addView(chip("🔤 חיפוש מילים", !semantic) { setMode(false) }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
        modeRow.addView(chip("💡 חיפוש לפי משמעות", semantic) { setMode(true) }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))

        optRow.removeAllViews()
        if (!semantic) {
            val m = Match.valueOf(Prefs.s("match", "PHRASE"))
            optRow.addView(chip("התאמה: ${m.title} ▾", false) {
                Sheet.choice(this, "איך המילים צריכות להופיע?", Match.values().map { it.name to it.title }, m.name) {
                    Prefs.put("match", it); buildModes(); if (results != null) doSearch()
                }
            }, lp(WRAP, WRAP).m(dp(3), 0, dp(3), 0))
            val pre = Prefs.b("prefixes", true)
            optRow.addView(chip((if (pre) "☑" else "☐") + " כולל ו׳ ה׳ ב׳ ל׳ ש׳…", pre) {
                Prefs.put("prefixes", !pre); buildModes(); if (results != null) doSearch()
            }, lp(WRAP, WRAP).m(dp(3), 0, dp(3), 0))
        }
        val sort = Prefs.s("sort", "relevance")
        optRow.addView(chip("מיון: ${sortTitle(sort)} ▾", false) {
            Sheet.choice(this, "מיון התוצאות", SORTS, sort) { Prefs.put("sort", it); buildModes(); render() }
        }, lp(WRAP, WRAP).m(dp(3), 0, dp(3), 0))
    }

    private val SORTS = listOf(
        "relevance" to "רלוונטיות (הכי הרבה תוצאות)",
        "name" to "שם הספר (א-ת)",
        "folder" to "תיקייה",
        "size" to "גודל הקובץ",
        "date" to "תאריך הקובץ (חדש קודם)",
        "pages" to "מספר העמודים בספר",
    )

    private fun sortTitle(k: String) = when (k) {
        "name" -> "שם"; "folder" -> "תיקייה"; "size" -> "גודל"; "date" -> "תאריך"; "pages" -> "עמודים"; else -> "רלוונטיות"
    }

    private fun setMode(sem: Boolean) {
        if (sem && !Semantic.ready(this)) {
            Sheet.confirm(this, "חיפוש לפי משמעות",
                "בחיפוש הזה כותבים רעיון או שאלה במילים שלך, והתוכנה מוצאת קטעים שעוסקים בזה – גם אם המילים עצמן לא מופיעים שם.\n\n" +
                    "לשם כך צריך להוריד פעם אחת מודל בינה מלאכותית (כ-120MB), שעובד בתוך המכשיר בלי אינטרנט. " +
                    "אחר כך התוכנה בונה אינדקס משמעות לספרים (זה לוקח זמן, ברקע).",
                "להגדרות והורדה") { startActivity(Intent(this, SettingsActivity::class.java).putExtra("focus", "semantic")) }
            return
        }
        semantic = sem
        Prefs.put("lastSemantic", sem)
        buildModes()
        if (input.text.isNotBlank()) doSearch()
    }

    private fun refreshSetup() {
        setupCard.removeAllViews()
        val card = vbox().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundBg(0xFFFFF7DD.toInt(), dp(14).toFloat(), dp(1), 0xFFE0C060.toInt())
        }
        when {
            !Library.hasAccess(this) -> {
                card.addView(label("שלב 1: הרשאה לקרוא את הקבצים", 17f, Col.TEXT, true))
                card.addView(label("כדי לסרוק את הספרים (גם בכרטיס הזיכרון) צריך לאשר לחיפוש פלוס גישה לכל הקבצים. במסך שייפתח – הפעילו את המתג וחזרו.", 14.5f, Col.SUB).apply { setPadding(0, dp(4), 0, dp(10)) })
                card.addView(pill("מתן הרשאה") { Library.askAccess(this) }, lp(MATCH, WRAP))
            }
            Library.roots().isEmpty() -> {
                card.addView(label("שלב 2: איפה הספרים שלך?", 17f, Col.TEXT, true))
                card.addView(label("בחרו את התיקייה הראשית של מאגר הספרים (למשל בכרטיס הזיכרון). התיקיות שבתוכה – הלכה, חסידות, נגלה… – יהפכו לקטגוריות בחיפוש.", 14.5f, Col.SUB).apply { setPadding(0, dp(4), 0, dp(10)) })
                card.addView(pill("בחירת תיקיית הספרים") {
                    FolderPicker.pick(this) { dir ->
                        Library.setRoots(listOf(dir))
                        refreshSetup()
                        Indexer.start(this)
                        Sheet.info(this, "מתחילים לסרוק", "הסריקה רצה ברקע ואפשר כבר לחפש בספרים שנסרקו. בפעם הראשונה זה לוקח זמן – תלוי בכמות הספרים. אפשר לסגור את התוכנה; הסריקה ממשיכה.")
                    }
                }, lp(MATCH, WRAP))
            }
            else -> return
        }
        setupCard.addView(card, lp(MATCH, WRAP).m(dp(12), dp(4), dp(12), dp(6)))
    }

    private fun progressSheet() {
        Sheet.confirm(this, "סריקת הספרים", Progress.text() + "\n\nהסריקה רצה ברקע. אפשר להמשיך לחפש בינתיים בספרים שכבר נסרקו.", "עצירת הסריקה") {
            Progress.cancel = true
        }
    }

    // ---------------- home ----------------

    private fun showHome() {
        list.visibility = View.GONE
        home.visibility = View.VISIBLE
        searchChrome(false)
        crumbs.removeAllViews(); cats.removeAllViews()
        summary.text = ""
        homeBox.removeAllViews()
        // welcome card
        val hero = vbox().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = Col.gradient(Col.ACC, Col.ACC2, dp(18).toFloat())
        }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hello = when (hour) { in 4..11 -> "בוקר טוב!"; in 12..16 -> "צהריים טובים!"; in 17..20 -> "ערב טוב!"; else -> "לילה טוב!" }
        hebrewDate().takeIf { it.isNotEmpty() }?.let { hero.addView(label(it, 13.5f, 0xDDFFFFFF.toInt(), true)) }
        hero.addView(label("$hello מה נלמד היום?", 21f, android.graphics.Color.WHITE, true))
        val heroStats = label("טוען את נתוני המאגר…", 14f, 0xFFDDE6F2.toInt()).apply { setPadding(0, dp(4), 0, dp(12)) }
        hero.addView(heroStats)
        homeBox.addView(hero, lp(MATCH, WRAP).m(0, dp(6), 0, dp(6)))

        // every part of the app, one tap away (also in the side menu ☰)
        val grid = vbox()
        Nav.ITEMS.chunked(2).forEachIndexed { r, pair ->
            val row = hbox()
            pair.forEachIndexed { c, it ->
                val (c1, c2) = Col.TILES[(r * 2 + c) % Col.TILES.size]
                row.addView(vbox().apply {
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = Col.gradient(c1, c2, dp(16).toFloat())
                    if (!Col.isEink) elevation = dp(3).toFloat()
                    addView(label(it.icon, 24f, android.graphics.Color.WHITE))
                    addView(label(it.title, 16f, android.graphics.Color.WHITE, true))
                    addView(label(it.sub, 11.5f, 0xEEFFFFFF.toInt()).apply { maxLines = 2 })
                    setOnClickListener { _ -> it.go(this@MainActivity) }
                }, lp(0, WRAP, 1f).m(dp(4), dp(4), dp(4), dp(4)))
            }
            grid.addView(row, lp(MATCH, WRAP))
        }
        homeBox.addView(grid, lp(MATCH, WRAP).m(0, dp(2), 0, dp(4)))

        // ideas for today: topics that appear in the user's own books
        val ideas = vbox().apply {
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = roundBg(Col.CARD, dp(16).toFloat(), dp(1), Col.LINE)
            if (!Col.isEink) elevation = dp(1).toFloat()
            visibility = View.GONE
        }
        val hourNow = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        ideas.addView(label((if (hourNow in 4..11) "☀ בוקר טוב! " else "") + "💭 על מה נחפש היום?", 17f, Col.TEXT, true))
        ideas.addView(label("נושאים שמופיעים בספרים שלך – לחיצה, ורואים מה יש עליהם", 13f, Col.SUB).apply { setPadding(0, dp(2), 0, dp(8)) })
        val ideaChips = FlowLayout(this)
        ideas.addView(ideaChips, lp(MATCH, WRAP))
        homeBox.addView(ideas, lp(MATCH, WRAP).m(0, dp(6), 0, dp(6)))
        exec.execute {
            val ts = try { Topics.today() } catch (e: Throwable) { emptyList() }
            ui {
                if (ts.isEmpty() || isFinishing) return@ui
                ts.forEach { t ->
                    ideaChips.addView(chip("${t.text}  ·  ${if (t.places >= 999) "999+" else t.places}", false) {
                        input.setText(t.text)
                        // an idea is best searched by meaning when it is available; otherwise all the words
                        if (Semantic.ready(this) && t.text.split(' ').size >= 2 && !semantic) { semantic = true; buildModes() }
                        doSearch()
                    })
                }
                ideas.visibility = View.VISIBLE
            }
        }

        // continue where you stopped
        Library.recent().firstOrNull()?.let { k ->
            val p = k.substringBefore('|'); val pg = k.substringAfter('|').toIntOrNull() ?: 1
            if (File(p).exists()) homeBox.addView(hbox().apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = pressBg(Col.CARD, Col.ACC_LIGHT, 16, dp(1.5f), Col.ACC)
                if (!Col.isEink) elevation = dp(2).toFloat()
                addView(label("📖", 28f, Col.ACC), lp(WRAP, WRAP))
                addView(vbox().apply {
                    setPadding(dp(12), 0, dp(6), 0)
                    addView(label("להמשיך מאיפה שעצרת", 12.5f, Col.ACC, true))
                    addView(label(File(p).nameWithoutExtension, 16f, Col.TEXT, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(label("עמוד $pg", 12.5f, Col.SUB))
                }, lp(0, WRAP, 1f))
                addView(label("◂", 22f, Col.ACC, true))
                setOnClickListener { ReaderActivity.open(this@MainActivity, p, pg) }
            }, lp(MATCH, WRAP).m(0, dp(6), 0, dp(6)))
        }

        // the big microphone: say what you are looking for
        val mic = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
            background = pressBg(Col.BG, Col.ACC_LIGHT, 22, dp(2), Col.ACC)
            setOnClickListener { openVoice() }
        }
        mic.addView(label("🎤", 46f, Col.ACC, true).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = roundBg(Col.ACC_LIGHT, dp(48).toFloat(), dp(3), Col.ACC)
            setPadding(dp(18), dp(10), dp(18), dp(14))
        }, lp(dp(96), dp(96)))
        mic.addView(label("לחצו ודברו", 19f, Col.TEXT, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, dp(10), 0, dp(2)) }, lp(MATCH, WRAP))
        mic.addView(label("אומרים מה מחפשים – והספר נפתח במקום. עובד בלי אינטרנט", 13.5f, Col.SUB).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }, lp(MATCH, WRAP))
        val ex = FlowLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        listOf("ברכות דף כ״ז ע״א", "לקוטי שיחות חלק ד עמוד 58", "לקוטי שיחות חלק ד נח", "תניא פרק ה", "מה מברכים על פרי העץ").forEach { e ->
            ex.addView(chip("“$e”", false) {
                startActivity(Intent(this, VoiceActivity::class.java).putExtra("q", e))
            })
        }
        mic.addView(ex, lp(MATCH, WRAP))
        homeBox.addView(mic, lp(MATCH, WRAP).m(0, dp(8), 0, dp(8)))
        exec.execute {
            val st = try { Db.get().stats() } catch (e: Exception) { null }
            ui {
                heroStats.text = when {
                    st == null || st.books == 0 -> "עוד אין ספרים במאגר – בחרו את תיקיית הספרים כדי להתחיל."
                    else -> "במאגר ${st.books} ספרים · ${st.textPages} עמודים שאפשר לחפש בהם" +
                        (if (st.pending > 0) "\nעוד ${st.pending} ספרים בתור לסריקה" else "") +
                        (if (Semantic.ready(this)) "\n💡 אינדקס המשמעות: ${st.embedded} מתוך ${st.done} ספרים" + (if (st.embedded >= st.done && st.done > 0) " ✓ מוכן לשאלות" else "") else "")
                }
            }
        }
        val hist = Library.history()
        if (hist.isNotEmpty()) {
            homeBox.addView(section("חיפושים אחרונים"))
            val f = FlowLayout(this)
            hist.take(16).forEach { q -> f.addView(chip(q, false) { input.setText(q); doSearch() }) }
            homeBox.addView(f, lp(MATCH, WRAP).m(0, 0, 0, dp(10)))
        }
        val favs = Library.favorites()
        if (favs.isNotEmpty()) {
            homeBox.addView(section("★ מועדפים"))
            favs.take(30).forEach { k ->
                val p = k.substringBefore('|'); val pg = k.substringAfter('|').toIntOrNull() ?: 1
                homeBox.addView(bookLine(File(p).nameWithoutExtension, "עמוד $pg", p, pg) {
                    Sheet.confirm(this, "הסרה מהמועדפים", File(p).nameWithoutExtension + ", עמוד $pg", "הסרה") { Library.toggleFavorite(k); showHome() }
                })
            }
        }
        val rec = Library.recent()
        if (rec.isNotEmpty()) {
            homeBox.addView(section("נפתחו לאחרונה"))
            rec.take(12).forEach { k ->
                val p = k.substringBefore('|'); val pg = k.substringAfter('|').toIntOrNull() ?: 1
                homeBox.addView(bookLine(File(p).nameWithoutExtension, "המשך מעמוד $pg", p, pg, null))
            }
        }
        homeBox.addView(section("איך מחפשים?"))
        homeBox.addView(label(
            "• חיפוש מילים: כותבים מילה או ביטוי – \"רבי שמעון\", \"שיחת ויצא\". אפשר לבחור ביטוי מדויק, כל המילים, או מילים קרובות.\n" +
                "• כוכבית בסוף מילה מוצאת את כל ההמשכים שלה: ויצ* ימצא ויצא, ויצאו…\n" +
                "• \"כולל ו׳ ה׳ ב׳…\" – מוצא גם \"ורבי שמעון\", \"לרבי שמעון\".\n" +
                "• אחרי החיפוש לוחצים על קטגוריה (תיקייה) כדי למקד – ושוב על תת-תיקייה, וכן הלאה.\n" +
                "• חיפוש לפי משמעות: כותבים רעיון במילים שלך ומקבלים קטעים שעוסקים בו.\n" +
                "• לחיצה על תוצאה פותחת את הספר בדיוק בעמוד, והמילים מסומנות.\n" +
                "• 🎤 חיפוש קולי: לוחצים על המיקרופון ואומרים – \"ברכות דף כ״ז עמוד א\", \"ליקוטי שיחות חלק ג ויצא\", או שאלה. התוכנה פותחת ישר את המקום.", 14.5f, Col.SUB).apply { setLineSpacing(0f, 1.15f) })
        homeBox.addView(label("\nפותח על ידי שניאור וילהלם · מופץ לתועלת הרבים", 12.5f, Col.SUB).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
        homeBox.addView(label("יחי אדוננו מורנו ורבנו מלך המשיח לעולם ועד", 14f, Col.ACC, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, dp(6), 0, dp(10)) })
        if (!Col.isEink) for (i in 0 until minOf(homeBox.childCount, 8)) homeBox.getChildAt(i).apply {
            alpha = 0f; translationY = dp(18).toFloat()
            animate().alpha(1f).translationY(0f).setStartDelay(i * 60L).setDuration(380).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    private fun section(t: String) = label(t, 16.5f, Col.ACC, true).apply { setPadding(0, dp(12), 0, dp(6)) }

    private fun bookLine(title: String, sub: String, path: String, page: Int, onLong: (() -> Unit)?): View = vbox().apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = pressBg(Col.CARD, Col.ACC_LIGHT, 12)
        addView(label("📖 $title", 15.5f, Col.TEXT, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(label(sub, 13f, Col.SUB))
        setOnClickListener { ReaderActivity.open(this@MainActivity, path, page) }
        if (onLong != null) setOnLongClickListener { onLong(); true }
        layoutParams = lp(MATCH, WRAP).m(0, dp(3), 0, dp(3))
    }

    // ---------------- search ----------------

    private fun doSearch(autoOpen: Boolean = false) {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        Library.addHistory(q)
        prefix = emptyList()
        expanded.clear()
        home.visibility = View.GONE
        searchChrome(true)
        list.visibility = View.VISIBLE
        summary.text = if (semantic) "💡 מחפש לפי משמעות… (בפעם הראשונה זה לוקח כמה שניות)" else "🔤 מחפש בכל עמודי ${try { Db.get().books().size } catch (e: Exception) { 0 }} הספרים…"
        crumbs.removeAllViews(); cats.removeAllViews()
        adapter.set(emptyList())
        val token = ++searchToken
        showJump(q, token, auto = autoOpen)
        val sem = semantic
        val m = Match.valueOf(Prefs.s("match", "PHRASE"))
        val pre = Prefs.b("prefixes", true)
        exec.execute {
            val r = if (sem) Search.runSemantic(this, q) { t -> ui { if (token == searchToken) summary.text = t } } else Search.run(q, m, pre)
            val e = if (sem) "" else Search.buildMatch(r.terms, m, pre)
            ui {
                if (token != searchToken) return@ui
                results = r; expr = e
                render()
            }
        }
    }

    /**
     * Android 11 keeps the reason the app last closed (also for crashes of native code and the system
     * killing it for memory, which no error handler can catch). Show it once, with a fix where there is one.
     */
    private fun checkLastExit() {
        val busy = Prefs.l("voiceBusy", 0L)
        Prefs.put("voiceBusy", 0L)
        // the meaning index was being built when the app closed: don't let it close the app again on every start
        val sem = Prefs.l("semBusy", 0L)
        Prefs.put("semBusy", 0L)
        if (sem > 0 && !Progress.running) {
            Prefs.put("semantic", false)
            Semantic.lastError = "בניית אינדקס המשמעות נעצרה כי המכשיר סגר את התוכנה. אפשר להפעיל שוב מההגדרות (מומלץ בטעינה, בלילה)."
            Sheet.confirm(this, "אינדקס המשמעות נעצר", "בזמן בניית אינדקס המשמעות המכשיר סגר את התוכנה (כנראה חוסר זיכרון). כדי שזה לא יחזור – הבנייה הופסקה.\n\nהחיפוש לפי משמעות עדיין עובד על העמודים שמכילים את מילות השאלה.", "לנסות שוב עכשיו") {
                Prefs.put("semantic", true); Indexer.start(this)
            }
            return
        }
        if (android.os.Build.VERSION.SDK_INT < 30) {
            if (busy > 0) voiceCrashSheet("")
            return
        }
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val e = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return
            if (e.timestamp <= Prefs.l("lastExitSeen", 0L)) return
            Prefs.put("lastExitSeen", e.timestamp)
            val r = e.reason
            val bad = r == android.app.ApplicationExitInfo.REASON_CRASH || r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                r == android.app.ApplicationExitInfo.REASON_LOW_MEMORY || r == android.app.ApplicationExitInfo.REASON_ANR ||
                (r == android.app.ApplicationExitInfo.REASON_SIGNALED && busy > 0)
            if (!bad) return
            val why = when (r) {
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY, android.app.ApplicationExitInfo.REASON_SIGNALED -> "המכשיר סגר את התוכנה כי נגמר הזיכרון"
                android.app.ApplicationExitInfo.REASON_ANR -> "התוכנה לא הגיבה זמן רב"
                else -> "שגיאה בתוכנה"
            }
            val details = "$why\n(${e.reason}/${e.status} ${e.description ?: ""}) גרסה ${packageManager.getPackageInfo(packageName, 0).versionName}"
            if (busy > 0 && e.timestamp >= busy) voiceCrashSheet(details)
            else Sheet.confirm(this, "התוכנה נסגרה בפעם הקודמת", details + "\n\nאפשר להעתיק את הפרטים ולשלוח למפתח.", "העתקת הפרטים") {
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("exit", details))
                toast("הועתק")
            }
        } catch (x: Throwable) {}
    }

    private fun voiceCrashSheet(details: String) {
        val cur = VoiceModels.spec()
        val lower = VoiceModels.SPECS.getOrNull(VoiceModels.SPECS.indexOf(cur) - 1)
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (lower != null) items.add("לעבור לזיהוי ${lower.title} (קל יותר למכשיר)" to {
            Prefs.put("whisperModel", lower.key)
            if (!VoiceModels.ready(this, lower.key)) VoiceSearch.downloadNow(this, lower.key) { toast("מוכן – נסו שוב את המיקרופון") } else toast("עבר לזיהוי ${lower.title} – נסו שוב")
        })
        if (VoiceSearch.systemAvailable(this)) items.add("להשתמש בשירות הדיבור של המכשיר (צריך אינטרנט)" to { Prefs.put("voiceEngine", "system"); toast("נסו שוב את המיקרופון") })
        items.add("להעתיק את הפרטים למפתח" to {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("voice", "זיהוי דיבור נסגר: $details"))
            toast("הועתק")
        })
        items.add("להמשיך כרגיל" to {})
        Sheet.actions(this, "זיהוי הדיבור עצר את התוכנה בפעם הקודמת" + if (details.isNotEmpty()) "\n$details" else "", items)
    }

    // ---------------- voice and direct jumps ----------------

    private val questionWords = setOf("מה", "מהו", "מהי", "איך", "כיצד", "למה", "מדוע", "מתי", "מי", "האם", "היכן", "איפה", "מאיפה", "כמה")

    private fun onVoice(text: String) {
        input.setText(text)
        input.setSelection(input.text.length)
        val first = Heb.words(text).firstOrNull()
        if (RefParser.parse(text) == null && first != null && first in questionWords && Semantic.ready(this) && !semantic) {
            semantic = true; buildModes()
        }
        doSearch(autoOpen = true)
    }

    /** When the search is a reference (ברכות דף כז / ליקוטי שיחות חלק ג ויצא), offer to go straight there. */
    private fun showJump(q: String, token: Int, auto: Boolean) {
        jumpBar.visibility = View.GONE
        val ref = try { RefParser.parse(q) } catch (e: Throwable) { null } ?: return
        if (ref.kind == com.chdv.searchplus.core.Ref.Kind.GEMARA && ref.daf == null) {
            jumpBar.text = "⤶ ${ref.work}: לא הבנתי איזה דף. אמרו למשל \"${ref.work} דף כ״ז עמוד א\""
            jumpBar.visibility = View.VISIBLE
            jumpBar.setOnClickListener(null)
            return
        }
        jumpBar.text = "⤶ מחפש את ${ref.label()}…"
        jumpBar.visibility = View.VISIBLE
        jumpBar.setOnClickListener(null)
        exec.execute {
            val targets = try { Jump.resolve(ref, q, { token != searchToken }) { t -> ui { if (token == searchToken) jumpBar.text = "⤶ $t" } } } catch (e: Throwable) { emptyList() }
            ui {
                if (token != searchToken) return@ui
                if (targets.isEmpty()) {
                    jumpBar.text = "⤶ ${ref.label()}: לא נמצא ספר כזה במאגר" + if (Progress.running) " (הסריקה עדיין רצה)" else ""
                    return@ui
                }
                jumpBar.text = if (targets.size == 1) "⤶ פתיחה: ${targets[0].book.title} – ${targets[0].note}"
                else "⤶ ${ref.label()} – נמצא ב-${targets.size} ספרים. לבחירה ◂"
                jumpBar.setOnClickListener { pickTarget(targets) }
                if (auto) pickTarget(targets)
            }
        }
    }

    private fun pickTarget(targets: List<Jump.Target>) {
        if (targets.size == 1) { openTarget(targets[0]); return }
        Sheet.actions(this, "איזה ספר לפתוח? (${targets[0].ref.label()})", targets.map { t ->
            val folder = Library.categoryOf(t.book.dir).joinToString(" › ")
            "📖 ${t.book.title}\n$folder · ${t.note}" to { openTarget(t) }
        })
    }

    private fun openTarget(t: Jump.Target) {
        ReaderActivity.open(this, t.book.path, t.page, refLabel = t.ref.label(), amudIndex = t.amudIndex ?: -1, exact = t.exact)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == VoiceSearch.REQ_MIC) VoiceSearch.onPermission(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    private fun visibleBooks(): List<BookHit> {
        val r = results ?: return emptyList()
        return r.books.filter { h -> h.category.size >= prefix.size && h.category.subList(0, prefix.size) == prefix }
    }

    private fun render() {
        val r = results ?: return
        crumbs.removeAllViews(); cats.removeAllViews()
        if (r.error != null) { summary.text = r.error; adapter.set(emptyList()); return }
        val vis = visibleBooks()

        // breadcrumbs: הכל › חסידות חב"ד › ...
        crumbs.addView(crumb("הכל", prefix.isEmpty()) { prefix = emptyList(); render() })
        prefix.forEachIndexed { i, s ->
            crumbs.addView(label("›", 15f, Col.SUB).apply { setPadding(dp(2), dp(6), dp(2), 0) })
            crumbs.addView(crumb(s, i == prefix.size - 1) { prefix = prefix.subList(0, i + 1); render() })
        }

        // categories at the next level
        val groups = LinkedHashMap<String, IntArray>() // name -> [books, pages]
        for (h in vis) {
            if (h.category.size > prefix.size) {
                val g = groups.getOrPut(h.category[prefix.size]) { IntArray(2) }
                g[0]++; g[1] += h.pages.size
            }
        }
        groups.entries.sortedByDescending { it.value[1] + it.value[0] }.forEach { (name, c) ->
            cats.addView(chip("📁 $name  (${if (c[1] > 0) c[1] else c[0]})", false) { prefix = prefix + name; expanded.clear(); render() })
        }

        if (r.semantic && r.books.isNotEmpty()) findAnswer(r)
        val pages = vis.sumOf { it.pages.size }
        summary.text = when {
            vis.isEmpty() && r.semantic -> "לא נמצאו קטעים. ייתכן שאינדקס המשמעות עדיין נבנה – אפשר לראות את ההתקדמות בהגדרות."
            vis.isEmpty() -> "לא נמצא. נסו \"כל המילים בעמוד\", הוסיפו כוכבית (*) או בדקו את האיות." +
                (if (Progress.running) "\n(הסריקה עדיין רצה – חלק מהספרים עוד לא נסרקו)" else "")
            r.semantic -> "נמצאו ${vis.size} ספרים עם קטעים קרובים במשמעות – הקרובים ביותר ראשונים"
            else -> "נמצאו $pages עמודים ב-${vis.size} ספרים" + (if (r.truncated) " (מוצגות התוצאות הראשונות)" else "")
        }

        val sorted = when (Prefs.s("sort", "relevance")) {
            "name" -> vis.sortedBy { it.book.title }
            "folder" -> vis.sortedWith(compareBy({ it.book.dir }, { it.book.title }))
            "size" -> vis.sortedByDescending { it.book.size }
            "date" -> vis.sortedByDescending { it.book.mtime }
            "pages" -> vis.sortedByDescending { it.book.pages }
            else -> vis.sortedByDescending { it.score }
        }
        if (sorted.size == 1) expanded.add(sorted[0].book.id)
        val rows = ArrayList<Row>()
        if (r.semantic && vis.isNotEmpty() && prefix.isEmpty()) rows.add(Row.Answer(answer))
        for (h in sorted) {
            rows.add(Row.BookRow(h))
            if (h.book.id in expanded) {
                val pp = if (r.semantic) h.pages else h.pages
                val show = pp.take(if (h.book.id in showAll) 5000 else 60)
                show.forEach { rows.add(Row.PageRow(h, it)) }
                if (pp.size > show.size) rows.add(Row.More(h, pp.size - show.size))
            }
        }
        adapter.set(rows)
    }

    private val showAll = HashSet<Long>()
    private var answer: Semantic.Answer? = null
    private var answerFor: Results? = null

    /** For a question: read the closest passages sentence by sentence and pick the sentence that answers it. */
    private fun findAnswer(r: Results) {
        if (answerFor === r) return
        answerFor = r; answer = null
        val top = r.books.flatMap { b -> b.pages.map { b to it } }.sortedByDescending { it.second.score }.take(4)
        exec.execute {
            val a = try { Semantic.answer(this, r.query, top.map { it.second.pageId }) } catch (e: Throwable) { null }
            ui { if (results === r) { answer = a ?: Semantic.Answer("", -1, 0f); render() } }
        }
    }

    private fun crumb(t: String, cur: Boolean, onClick: () -> Unit) = label(t, 15f, if (cur) Col.TEXT else Col.ACC, true).apply {
        setPadding(dp(6), dp(4), dp(6), dp(4))
        if (!cur) { paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG; setOnClickListener { onClick() } }
    }

    private fun openHit(h: BookHit, page: Int, phrase: String? = null) {
        val r = results ?: return
        val hitPages = h.pages.map { it.page }.distinct().sorted().toIntArray()
        ReaderActivity.open(this, h.book.path, page, r.terms, hitPages, phrase = phrase)
    }

    private fun bookMenu(h: BookHit) {
        val b = h.book
        Sheet.actions(this, b.title, listOf(
            "פתיחה בעמוד הראשון שנמצא" to { openHit(h, h.pages.firstOrNull()?.page ?: 1) },
            "פתיחה באפליקציה אחרת" to { Library.openExternal(this, b.path, h.pages.firstOrNull()?.page ?: 1, results?.terms ?: emptyList(), chooser = true) },
            "חיפוש רק בתיקייה של הספר הזה" to { prefix = h.category; render() },
            "פרטי הספר" to {
                Sheet.info(this, b.title, "תיקייה: ${b.dir}\nגודל: ${human(b.size)}\nעמודים: ${b.pages}\nעמודים עם טקסט: ${b.textPages}" +
                    (if (b.emptyPages > 0) "\nעמודים סרוקים בלי טקסט: ${b.emptyPages}" else ""))
            },
        ))
    }

    // ---------------- list ----------------

    sealed class Row {
        class BookRow(val hit: BookHit) : Row()
        class PageRow(val hit: BookHit, val page: PageHit) : Row()
        class More(val hit: BookHit, val rest: Int) : Row()
        class Answer(val a: Semantic.Answer?) : Row()
    }

    private val snippetCache = HashMap<Long, CharSequence>()
    /** Question & answer: the sentence that answers, per page – opened framed in the reader. */
    private val sentenceOf = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private fun marked(s: String): CharSequence {
        val sb = SpannableStringBuilder()
        var i = 0
        while (i < s.length) {
            val a = s.indexOf('\u0001', i)
            if (a < 0) { sb.append(s.substring(i)); break }
            sb.append(s.substring(i, a))
            val b = s.indexOf('\u0002', a).let { if (it < 0) s.length else it }
            val st = sb.length
            sb.append(s.substring(a + 1, b))
            sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(BackgroundColorSpan(0xFFFFE9A8.toInt()), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(UnderlineSpan(), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = b + 1
        }
        return sb
    }

    inner class ResultAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()
        fun set(r: List<Row>) { rows = r; notifyDataSetChanged() }
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) { is Row.BookRow -> 0; is Row.PageRow -> 1; is Row.More -> 2; is Row.Answer -> 3 }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = this@MainActivity
            val v: View = when (viewType) {
                0 -> c.vbox().apply {
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = pressBg(Col.BG, Col.ACC_LIGHT, 0)
                    val top = c.hbox()
                    top.addView(c.label("", 16.5f, Col.TEXT, true).apply { id = 1; maxLines = 2 }, lp(0, WRAP, 1f))
                    top.addView(c.label("", 13.5f, Color.WHITE, true).apply {
                        id = 2; setPadding(dp(9), dp(3), dp(9), dp(3)); background = roundBg(Col.ACC, dp(12).toFloat())
                    })
                    addView(top, lp(MATCH, WRAP))
                    addView(c.label("", 12.5f, Col.SUB).apply { id = 3; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.START }, lp(MATCH, WRAP))
                }
                1 -> c.vbox().apply {
                    setPadding(dp(26), dp(8), dp(14), dp(10))
                    background = pressBg(Col.CARD, Col.ACC_LIGHT, 0)
                    addView(c.label("", 13.5f, Col.ACC, true).apply { id = 1 })
                    addView(c.label("", 15f, Col.TEXT).apply { id = 2; setLineSpacing(0f, 1.12f) })
                }
                3 -> c.vbox().apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = roundBg(0xFFFFF7DD.toInt(), dp(14).toFloat(), dp(1.5f), 0xFFD8B040.toInt())
                    addView(c.label("💡 התשובה מתוך הספרים שלך", 14f, Col.ACC, true).apply { id = 1 })
                    addView(c.label("", 16.5f, Col.TEXT).apply { id = 2; setPadding(0, dp(6), 0, dp(6)); setLineSpacing(0f, 1.15f) })
                    addView(c.label("", 13.5f, Col.ACC, true).apply { id = 3 })
                }
                else -> c.label("", 14.5f, Col.ACC, true).apply {
                    gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setPadding(dp(10), dp(12), dp(10), dp(12)); background = pressBg(Col.CARD, Col.ACC_LIGHT, 0)
                }
            }
            v.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { bottomMargin = 1 }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            when (val row = rows[position]) {
                is Row.BookRow -> {
                    val h = row.hit
                    val open = h.book.id in expanded
                    v.findViewById<TextView>(1).text = (if (open) "▾ " else "◂ ") + h.book.title
                    v.findViewById<TextView>(2).apply {
                        text = when {
                            results?.semantic == true -> "${(h.score * 100).toInt()}%"
                            h.pages.isEmpty() && h.nameMatch -> "שם"
                            else -> "${h.pages.size}"
                        }
                    }
                    v.findViewById<TextView>(3).text = (h.category.joinToString(" › ").ifEmpty { "תיקייה ראשית" }) +
                        "  ·  ${h.book.pages} עמ׳  ·  ${human(h.book.size)}"
                    v.setOnClickListener {
                        if (h.pages.isEmpty()) { ReaderActivity.open(this@MainActivity, h.book.path, 1); return@setOnClickListener }
                        if (open) expanded.remove(h.book.id) else expanded.add(h.book.id)
                        render()
                    }
                    v.setOnLongClickListener { bookMenu(h); true }
                }
                is Row.PageRow -> {
                    val ph = row.page
                    v.findViewById<TextView>(1).text = "עמוד ${ph.page}" + if (results?.semantic == true) "   (התאמה ${(ph.score * 100).toInt()}%)" else ""
                    val tv = v.findViewById<TextView>(2)
                    tv.tag = ph.pageId
                    val cached = snippetCache[ph.pageId]
                    if (cached != null) tv.text = cached else {
                        tv.text = "…"
                        val sem = results?.semantic == true
                        val terms = results?.terms ?: emptyList()
                        val e = expr
                        val q = results?.query ?: ""
                        snipExec.execute {
                            val s = (if (sem) (try { Semantic.sentenceFor(this@MainActivity, q, ph.pageId) } catch (x: Throwable) { null })
                                ?.also { sentenceOf[ph.pageId] = it }?.let { "\u0001$it\u0002" } ?: Search.semanticSnippet(ph.pageId, terms)
                            else Search.snippet(e, ph.pageId))?.replace('\n', ' ') ?: ""
                            val cs = marked(s)
                            ui {
                                snippetCache[ph.pageId] = cs
                                if (tv.tag == ph.pageId) tv.text = cs
                            }
                        }
                    }
                    v.setOnClickListener { openHit(row.hit, ph.page, sentenceOf[ph.pageId]) }
                    v.setOnLongClickListener {
                        val key = "${row.hit.book.path}|${ph.page}"
                        val on = Library.toggleFavorite(key)
                        toast(if (on) "העמוד נוסף למועדפים ★" else "הוסר מהמועדפים")
                        true
                    }
                }
                is Row.Answer -> {
                    val a = row.a
                    val wrap = holder.itemView.layoutParams as RecyclerView.LayoutParams
                    wrap.setMargins(dp(10), dp(8), dp(10), dp(8))
                    val src = a?.let { x -> Db.get().pageRef(x.pageId) }?.let { ref -> Db.get().book(ref.first)?.let { it to ref.second } }
                    v.findViewById<TextView>(2).text = when {
                        a == null -> "קורא את הקטעים הקרובים ומחפש את המשפט שעונה על השאלה…"
                        a.pageId < 0 || src == null -> "לא נמצא משפט שעונה במדויק. הקטעים הקרובים ביותר מופיעים למטה." +
                            if (!Semantic.Deep.ready(this@MainActivity)) "\n\n🧠 לחצו כאן להבנה מתקדמת יותר – מודל שקורא את המשפטים בהקשר ומוצא את התשובה." else ""
                        else -> "‟" + a.text + "”"
                    }
                    v.findViewById<TextView>(3).text = if (src != null && a != null && a.pageId >= 0) "📖 ${src.first.title}, עמוד ${src.second}  ·  לפתיחה ◂" else ""
                    v.setOnClickListener {
                        if ((a == null || a.pageId < 0 || src == null) && !Semantic.Deep.ready(this@MainActivity)) { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)); toast("הגדרות › 🧠 מודל הבנה עמוק"); return@setOnClickListener }
                        if (src != null) ReaderActivity.open(this@MainActivity, src.first.path, src.second, Semantic.keyWords(a!!.text).take(6), phrase = a.text)
                    }
                }
                is Row.More -> {
                    (v as TextView).text = "הצגת עוד ${row.rest} עמודים"
                    v.setOnClickListener { showAll.add(row.hit.book.id); render() }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown(); snipExec.shutdown()
    }
}
```

## `app/src/main/java/com/chdv/searchplus/Nav.kt`

```kotlin
package com.chdv.searchplus

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The side menu (opens from the right): every part of the app as its own screen –
 * voice search, question & answer, word search, browsing, organizing, settings.
 */
object Nav {
    class Item(val icon: String, val title: String, val sub: String, val go: (Activity) -> Unit)

    val ITEMS: List<Item> = listOf(
        Item("⚡", "מעבר מהיר", "כותבים ספר ומקום – והוא נפתח", { a -> a.startActivity(Intent(a, VoiceActivity::class.java).putExtra("mode", "type")) }),
        Item("🎤", "חיפוש קולי", "אומרים – והספר נפתח במקום", { a -> a.startActivity(Intent(a, VoiceActivity::class.java).putExtra("listen", true)) }),
        Item("💡", "שאלה ותשובה", "חיפוש לפי משמעות", { a -> a.startActivity(Intent(a, MainActivity::class.java).putExtra("screen", "meaning").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) }),
        Item("🔤", "חיפוש מילים", "מילה או ביטוי בתוך כל הספרים", { a -> a.startActivity(Intent(a, MainActivity::class.java).putExtra("screen", "words").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) }),
        Item("📚", "עיון בספרים", "תיקיות וקטגוריות", { a -> a.startActivity(Intent(a, BrowseActivity::class.java)) }),
        Item("🗂", "סידור המאגר", "כפולים · סידור תיקיות · שמות חכמים", { a -> a.startActivity(Intent(a, OrganizeActivity::class.java)) }),
        Item("☁", "ספרים מ-Google Drive", "מביאים את המאגר מהענן ומחפשים בו", { a -> a.startActivity(Intent(a, CloudActivity::class.java)) }),
        Item("⚙", "הגדרות", "מודלים · פתיחת ספרים · עדכונים", { a -> a.startActivity(Intent(a, SettingsActivity::class.java)) }),
    )

    fun open(a: Activity) {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = a.vbox().apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Col.BG)
            setPadding(a.dp(14), a.dp(18), a.dp(14), a.dp(14))
        }
        // header
        val head = a.vbox().apply {
            background = Col.gradient(Col.ACC, Col.ACC2, a.dp(18).toFloat())
            setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
        }
        head.addView(a.label("חיפוש פלוס", 24f, Color.WHITE, true))
        head.addView(a.label("כל הספרים שלך – במילה, בשאלה ובקול", 13.5f, 0xFFE2E6FF.toInt()))
        panel.addView(head, lp(MATCH, WRAP).m(0, 0, 0, a.dp(12)))

        val list = a.vbox()
        fun row(icon: String, title: String, sub: String, i: Int, go: () -> Unit) {
            val r = a.hbox().apply {
                setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
                background = a.pressBg(Color.TRANSPARENT, Col.ACC_LIGHT, 14)
                setOnClickListener { d.dismiss(); go() }
            }
            val (c1, c2) = Col.TILES[i % Col.TILES.size]
            r.addView(a.label(icon, 20f, Color.WHITE).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                background = Col.gradient(c1, c2, a.dp(12).toFloat())
            }, lp(a.dp(44), a.dp(44)))
            r.addView(a.vbox().apply {
                setPadding(a.dp(12), 0, a.dp(4), 0)
                addView(a.label(title, 16.5f, Col.TEXT, true))
                addView(a.label(sub, 12.5f, Col.SUB))
            }, lp(0, WRAP, 1f))
            list.addView(r, lp(MATCH, WRAP).m(0, a.dp(2), 0, a.dp(2)))
        }
        row("🏠", "מסך הבית", "חיפוש, היסטוריה, מועדפים", 0) {
            a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("screen", "home"))
        }
        list.addView(a.label("חיפוש", 12.5f, Col.SUB, true).apply { setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(2)) })
        ITEMS.forEachIndexed { i, it ->
            if (i == 4) list.addView(a.label("הספרייה", 12.5f, Col.SUB, true).apply { setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(2)) })
            row(it.icon, it.title, it.sub, i) { it.go(a) }
        }
        list.addView(a.divider(), lp(MATCH, 1).m(0, a.dp(8), 0, a.dp(8)))
        row("⟳", "בדיקת עדכונים", "גרסה " + (try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (e: Exception) { "" }), 5) { Updater.check(a, false) }
        row("❓", "עזרה ואודות", "המדריך המלא, הסרטון והאתר", 1) { help(a) }
        panel.addView(ScrollView(a).apply { addView(list) }, lp(MATCH, 0, 1f))
        panel.addView(a.label("פותח על ידי שניאור וילהלם · לתועלת הרבים", 11.5f, Col.SUB).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, a.dp(8), 0, 0)
        }, lp(MATCH, WRAP))
        panel.addView(a.label("יחי אדוננו מורנו ורבנו מלך המשיח לעולם ועד", 12.5f, Col.ACC, true).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, a.dp(4), 0, 0)
        }, lp(MATCH, WRAP))

        d.setContentView(panel)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            val w = (a.resources.displayMetrics.widthPixels * 0.84f).toInt().coerceAtMost(a.dp(420))
            setLayout(w, WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.RIGHT or Gravity.TOP)
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        d.show()
    }

    const val SITE = "https://sw0555041250-code.github.io/Search-Plus-/"
    const val GUIDE = "https://github.com/sw0555041250-code/Search-Plus-/releases/download/media/SearchPlus-Guide.pdf"
    const val VIDEO = "https://github.com/sw0555041250-code/Search-Plus-/releases/download/media/SearchPlus-Video.mp4"

    fun help(a: Activity) {
        fun web(u: String) { if (!a.safeStart(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)))) a.toast(u) }
        Sheet.actions(a, "עזרה ואודות", listOf(
            "📄 המדריך המלא (PDF)" to { web(GUIDE) },
            "🎬 סרטון הסבר" to { web(VIDEO) },
            "🌐 האתר של חיפוש פלוס" to { web(SITE) },
            "✉ יצירת קשר עם המפתח" to { web("mailto:sw0555041250@gmail.com?subject=" + android.net.Uri.encode("חיפוש פלוס")) },
        ))
    }

    /** The "☰" button for screen headers. */
    fun button(a: Activity) = a.label("☰", 22f, Col.ACC, true).apply {
        setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4))
        background = a.pressBg(Color.TRANSPARENT, Col.ACC_LIGHT, 14)
        contentDescription = "תפריט"
        setOnClickListener { open(a) }
    }
}
```

## `app/src/main/java/com/chdv/searchplus/OrganizeActivity.kt`

```kotlin
package com.chdv.searchplus

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** Tidy the library on the device: duplicates, smart sorting into folders, smart names. Everything asks first and can be undone. */
class OrganizeActivity : BaseActivity() {
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var tabs: LinearLayout
    private lateinit var box: LinearLayout
    private var tab = 0
    private var busy = false

    private var dups: List<Organizer.DupGroup>? = null
    private var suggestions: List<Organizer.Suggestion>? = null
    private var targets: List<Organizer.Target> = emptyList()
    private var renames: List<Organizer.Rename>? = null
    private var plan: Organizer.Plan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = vbox()
        tabs = hbox().apply { setPadding(dp(10), dp(8), dp(10), dp(6)) }
        body.addView(tabs, lp(MATCH, WRAP))
        box = vbox().apply { setPadding(dp(12), dp(4), dp(12), dp(30)) }
        body.addView(ScrollView(this).apply { addView(box) }, lp(MATCH, 0, 1f))
        screen("סידור המאגר", body, listOf(headerButton("↶ ביטול") { undo() }))
        tab = intent.getIntExtra("tab", 0)
        render()
    }

    private fun render() {
        tabs.removeAllViews()
        listOf("ספרים כפולים", "סידור לתיקיות", "שמות חכמים").forEachIndexed { i, t ->
            tabs.addView(chip(t, i == tab) { if (!busy) { tab = i; plan = null; render() } }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
        }
        box.removeAllViews()
        when (tab) { 0 -> renderDups(); 1 -> renderSort(); else -> renderNames() }
    }

    private fun note(t: String) = box.addView(label(t, 14.5f, Col.SUB).apply { setPadding(dp(4), dp(8), dp(4), dp(10)); setLineSpacing(0f, 1.12f) })

    private fun card(): LinearLayout = vbox().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = roundBg(Col.CARD, dp(14).toFloat(), dp(1), Col.LINE)
        box.addView(this, lp(MATCH, WRAP).m(0, dp(5), 0, dp(5)))
    }

    private fun run(title: String, job: ((String) -> Unit) -> Unit, done: () -> Unit) {
        if (busy) return
        busy = true
        box.removeAllViews()
        val st = label(title, 16f, Col.ACC, true).apply { setPadding(dp(6), dp(30), dp(6), dp(30)) }
        box.addView(st)
        bg.execute {
            try { job { s -> ui { st.text = "$title\n$s" } } } catch (e: Throwable) { ui { toast("שגיאה: ${e.message}") } }
            ui { busy = false; done() }
        }
    }

    // ---------------- duplicates ----------------

    private fun renderDups() {
        val d = dups
        if (d == null) {
            note("התוכנה משווה את התוכן של הספרים – לא את שמות הקבצים. כך היא מוצאת את אותו ספר גם כשהוא שמור פעמיים בשמות שונים, בתיקיות שונות, או כסריקה אחרת של אותו ספר.\n\nשום דבר לא נמחק בלי אישור שלך.")
            if (Db.get().count("SELECT COUNT(*) FROM files WHERE status=0") > 0) note("⚠ חלק מהספרים עוד לא נסרקו – ספרים שלא נסרקו נבדקים רק כקובץ זהה.")
            box.addView(pill("חיפוש ספרים כפולים") {
                run("מחפש ספרים כפולים…", { p -> dups = Organizer.findDuplicates(p) }) { render() }
            }, lp(MATCH, WRAP))
            return
        }
        if (d.isEmpty()) { note("לא נמצאו ספרים כפולים 👍"); box.addView(pill("בדיקה שוב", false) { dups = null; render() }); return }
        note("נמצאו ${d.size} קבוצות של ספרים כפולים (${d.sumOf { it.books.size - 1 }} עותקים מיותרים). בכל קבוצה בחרו איזה עותק להשאיר.")
        box.addView(pill("השארת עותק אחד בכל הקבוצות (הקובץ בתיקייה המסודרת ביותר)", false) {
            askRemove(d.flatMap { g -> val keep = best(g.books); g.books.filter { it.id != keep.id } }) { dups = d.map { g -> Organizer.DupGroup(g.kind, g.books.filter { File(it.path).exists() }, g.similarity) }.filter { it.books.size > 1 }; render() }
        }, lp(MATCH, WRAP).m(0, 0, 0, dp(6)))
        d.forEach { g ->
            val c = card()
            c.addView(label(g.kind.title + if (g.kind == Organizer.DupKind.SAME_TEXT) "  ·  דמיון ${g.similarity}%" else "", 13.5f, Col.ACC, true))
            g.books.forEach { b ->
                val row = vbox().apply { setPadding(0, dp(8), 0, dp(6)) }
                row.addView(label("📖 ${b.title}", 15.5f, Col.TEXT, true))
                row.addView(label(Library.categoryOf(b.dir).joinToString(" › ").ifEmpty { b.dir } + "  ·  ${human(b.size)}  ·  ${b.pages} עמ׳", 12.5f, Col.SUB))
                val btns = hbox()
                btns.addView(chip("פתיחה", false) { ReaderActivity.open(this, b.path, 1) }, lp(WRAP, WRAP).m(0, dp(4), dp(6), 0))
                btns.addView(chip("להשאיר רק את זה", false) {
                    askRemove(g.books.filter { it.id != b.id }) { dups = d.map { x -> Organizer.DupGroup(x.kind, x.books.filter { File(it.path).exists() }, x.similarity) }.filter { it.books.size > 1 }; render() }
                }, lp(WRAP, WRAP).m(0, dp(4), 0, 0))
                row.addView(btns)
                c.addView(row)
            }
        }
    }

    /** The copy to keep: deepest folder (most sorted), then more pages with text, then bigger file. */
    private fun best(bs: List<Book>): Book = bs.maxWith(compareBy<Book>({ Library.categoryOf(it.dir).size }, { it.textPages }, { it.size }))

    private fun askRemove(remove: List<Book>, after: () -> Unit) {
        if (remove.isEmpty()) return
        Sheet.choice(this, "מה לעשות עם ${remove.size} העותקים המיותרים?", listOf(
            "move" to "להעביר לתיקייה \"${Organizer.DUP_DIR}\" (מומלץ – אפשר לבדוק ולמחוק אחר כך)",
            "delete" to "למחוק לצמיתות",
        ), null) { how ->
            Sheet.confirm(this, "אישור", (if (how == "delete") "למחוק לצמיתות" else "להעביר") + " ${remove.size} קבצים?\n\n" +
                remove.take(15).joinToString("\n") { "• " + it.title } + if (remove.size > 15) "\n…" else "", "כן") {
                run("מטפל בקבצים…", { p ->
                    Organizer.beginBatch()
                    remove.forEachIndexed { i, b ->
                        p("${i + 1}/${remove.size}")
                        if (how == "delete") { if (File(b.path).delete()) Db.get().removeFile(b.id) }
                        else Organizer.moveBook(b.path, Organizer.dupDir())?.let { Db.get().bookByPath(it.absolutePath)?.let { nb -> Db.get().removeFile(nb.id) } }
                    }
                }) { toast("בוצע"); after() }
            }
        }
    }

    // ---------------- smart sorting ----------------

    private fun renderSort() {
        plan?.let { renderPlan(it); return }
        val s = suggestions
        if (s == null) {
            box.addView(label("סידור תיקייה – עם תצוגה מקדימה", 17f, Col.TEXT, true).apply { setPadding(dp(4), dp(10), dp(4), dp(2)) })
            note("בוחרים תיקייה ושיטת מיון. התוכנה מראה איך התיקייה תיראה אחרי הסידור; אפשר לשנות שמות של תיקיות, להעביר ספר מקבוצה לקבוצה או לבטל קבוצה – ורק כשמאשרים, הקבצים זזים. לא אהבתם? בוחרים שיטה אחרת.")
            box.addView(pill("בחירת תיקייה לסידור") {
                FolderPicker.pick(this) { d -> chooseScheme(File(d)) }
            }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
            box.addView(divider(), lp(MATCH, 1).m(0, dp(14), 0, dp(4)))
            box.addView(label("סידור ספרים לתיקיות המאגר", 17f, Col.TEXT, true).apply { setPadding(dp(4), dp(10), dp(4), dp(2)) })
            note("התוכנה לומדת מה יש בכל תיקייה במאגר (לפי התוכן של הספרים שכבר נמצאים בה ולפי שם התיקייה), ומציעה לכל ספר את התיקייה המתאימה לו – למשל ספר על הלכות שבת → הלכה › שבת.\n\nאתם רואים את כל ההצעות, משנים או מבטלים מה שרוצים, ורק אז הקבצים מועברים. אפשר לבטל את כל הסידור בכפתור \"ביטול\".")
            box.addView(pill("ספרים שלא נמצאים בשום תיקייה") { doSuggest(null, false) }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
            box.addView(pill("ספרים מתיקייה אחרת (למשל הורדות)…", false) {
                FolderPicker.pick(this) { dir -> doSuggest(dir, false) }
            }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
            box.addView(pill("בדיקת ספרים שנראים במקום הלא נכון", false) { doSuggest(null, true) }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
            return
        }
        if (s.isEmpty()) { note("אין הצעות – הכול נראה מסודר 👍"); box.addView(pill("חזרה", false) { suggestions = null; render() }); return }
        val sel = s.count { it.selected && it.dest != null }
        note("${s.size} הצעות. לחיצה על תיקיית היעד משנה אותה. ☑ = יועבר.")
        box.addView(pill("העברת $sel הספרים המסומנים") { applySort(s) }, lp(MATCH, WRAP).m(0, 0, 0, dp(6)))
        s.forEach { g ->
            val c = card()
            val top = hbox()
            top.addView(label(if (g.selected) "☑" else "☐", 20f, Col.ACC, true).apply {
                setPadding(0, 0, dp(10), 0); setOnClickListener { g.selected = !g.selected; render() }
            })
            top.addView(label("📖 " + g.title, 15.5f, Col.TEXT, true), lp(0, WRAP, 1f))
            c.addView(top)
            c.addView(label("עכשיו: " + (g.currentCat.joinToString(" › ").ifEmpty { File(g.path).parent ?: "" }), 12.5f, Col.SUB))
            c.addView(label("← " + (g.dest?.cat?.joinToString(" › ") ?: "לא נבחר") + "   (${(g.score * 100).toInt()}%) ✎", 15f, Col.ACC, true).apply {
                setPadding(0, dp(4), 0, 0)
                setOnClickListener {
                    Sheet.choice(this@OrganizeActivity, "לאיזו תיקייה?", targets.map { it.dir to it.cat.joinToString(" › ") }, g.dest?.dir) { d ->
                        g.dest = targets.firstOrNull { it.dir == d }; g.selected = true; render()
                    }
                }
            })
            c.addView(chip("פתיחה", false) { ReaderActivity.open(this, g.path, 1) }, lp(WRAP, WRAP).m(0, dp(6), 0, 0))
        }
    }

    private fun chooseScheme(dir: File) {
        val n = dir.listFiles()?.count { it.isFile && it.name.endsWith(".pdf", true) } ?: 0
        if (n == 0) { Sheet.info(this, "אין ספרים בתיקייה", "בתיקייה ${dir.name} אין קובצי PDF ישירות (רק תיקיות משנה). בחרו תיקייה שיש בה ספרים."); return }
        Sheet.choice(this, "איך לסדר את \"${dir.name}\" ($n ספרים)?", Organizer.Scheme.values().map { it.name to it.title + "\n" + it.about }, null) { k ->
            makePlan(dir, Organizer.Scheme.valueOf(k))
        }
    }

    private fun makePlan(dir: File, scheme: Organizer.Scheme) {
        run("מכין הצעה: ${scheme.title}…", { p -> plan = Organizer.plan(this, dir, scheme, p) }) { render() }
    }

    private fun renderPlan(p: Organizer.Plan) {
        box.addView(label("תצוגה מקדימה: ${p.dir.name}", 17f, Col.TEXT, true).apply { setPadding(dp(4), dp(8), dp(4), 0) })
        note("שיטה: ${p.scheme.title}. לחיצה על ✎ משנה את שם התיקייה, לחיצה על ספר מעבירה אותו לקבוצה אחרת.")
        val other = FlowLayout(this)
        Organizer.Scheme.values().filter { it != p.scheme }.forEach { sc -> other.addView(chip("נסו: ${sc.title}", false) { makePlan(p.dir, sc) }) }
        box.addView(other, lp(MATCH, WRAP).m(0, 0, 0, dp(8)))
        val moving = p.groups.sumOf { it.books.size }
        val row = hbox()
        row.addView(pill("החלת השינויים ($moving ספרים)") {
            if (moving == 0) return@pill
            Sheet.confirm(this, "החלת הסידור", "ייווצרו ${p.groups.count { it.books.isNotEmpty() && it.dest == null }} תיקיות ויועברו $moving ספרים. אפשר לבטל אחר כך בכפתור \"ביטול\".", "החלה") {
                run("מסדר…", { pr -> val k = Organizer.applyPlan(p, pr); ui { toast("הועברו $k ספרים") } }) { plan = null; render() }
            }
        }, lp(0, WRAP, 1f).m(0, 0, dp(4), 0))
        row.addView(pill("ביטול", false) { plan = null; render() }, lp(WRAP, WRAP))
        box.addView(row, lp(MATCH, WRAP).m(0, 0, 0, dp(6)))

        fun moveDialog(f: File, from: MutableList<File>) {
            val opts = p.groups.mapIndexed { i, g -> i.toString() to "📁 " + g.name } + listOf("stay" to "להשאיר במקום", "new" to "➕ תיקייה חדשה…")
            Sheet.choice(this, f.nameWithoutExtension, opts, null) { k ->
                when (k) {
                    "stay" -> { from.remove(f); p.stay.add(f); render() }
                    "new" -> Sheet.input(this, "שם התיקייה החדשה", "", "שם") { n ->
                        if (n.isNotBlank()) { from.remove(f); p.groups.add(Organizer.PGroup(n.trim(), mutableListOf(f))); render() }
                    }
                    else -> { from.remove(f); p.groups[k.toInt()].books.add(f); render() }
                }
            }
        }

        p.groups.filter { it.books.isNotEmpty() }.forEach { g ->
            val c = card()
            val top = hbox()
            top.addView(label("📁 " + g.name + "  (${g.books.size})", 16f, Col.ACC, true), lp(0, WRAP, 1f))
            if (g.dest == null) top.addView(label("✎", 20f, Col.ACC, true).apply {
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener { Sheet.input(this@OrganizeActivity, "שם התיקייה", g.name, "שם") { n -> if (n.isNotBlank()) { g.name = n.trim(); render() } } }
            })
            top.addView(label("✕", 18f, Col.SUB, true).apply {
                setPadding(dp(10), 0, dp(4), 0)
                setOnClickListener { p.stay.addAll(g.books); g.books.clear(); render() }
            })
            c.addView(top)
            g.books.toList().forEach { f ->
                c.addView(label("   📖 " + f.nameWithoutExtension, 14.5f, Col.TEXT).apply {
                    setPadding(0, dp(6), 0, dp(6))
                    setOnClickListener { moveDialog(f, g.books) }
                })
            }
        }
        if (p.stay.isNotEmpty()) {
            val c = card()
            c.addView(label("נשארים במקום (${p.stay.size})", 15f, Col.SUB, true))
            p.stay.toList().forEach { f ->
                c.addView(label("   📖 " + f.nameWithoutExtension, 14.5f, Col.SUB).apply {
                    setPadding(0, dp(6), 0, dp(6)); setOnClickListener { moveDialog(f, p.stay) }
                })
            }
        }
    }

    private fun doSuggest(dir: String?, checkAll: Boolean) {
        run("מנתח את המאגר…", { p ->
            val r = Organizer.suggest(this, dir, checkAll, p)
            targets = Organizer.buildTargetsCache ?: emptyList()
            suggestions = r
        }) { render() }
    }

    private fun applySort(s: List<Organizer.Suggestion>) {
        val todo = s.filter { it.selected && it.dest != null }
        if (todo.isEmpty()) return
        Sheet.confirm(this, "העברת ספרים", "להעביר ${todo.size} ספרים לתיקיות שנבחרו? (אפשר לבטל אחר כך)", "העברה") {
            run("מעביר…", { p ->
                Organizer.beginBatch()
                var ok = 0
                todo.forEachIndexed { i, g -> p("${i + 1}/${todo.size}"); if (Organizer.moveBook(g.path, File(g.dest!!.dir)) != null) ok++ }
                ui { toast("הועברו $ok ספרים") }
            }) { suggestions = null; render() }
        }
    }

    // ---------------- smart names ----------------

    private fun renderNames() {
        val r = renames
        if (r == null) {
            note("לקבצים עם שמות לא ברורים (scan0012, 45821, HebrewBooks_org…) התוכנה קוראת את דף השער ומציעה את שם הספר. לתיקיות עם שם לא ברור (New folder…) היא מציעה שם לפי הספרים שבתוכן.\n\nעל כל שם תישאלו: לשנות, להשאיר, או לערוך.")
            box.addView(pill("שמות לא ברורים בלבד (מומלץ)") { doNames(false) }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
            box.addView(pill("כל הקבצים והתיקיות (איטי)", false) { doNames(true) }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(4)))
            return
        }
        if (r.isEmpty()) { note("לא נמצאו שמות לשיפור 👍"); box.addView(pill("חזרה", false) { renames = null; render() }); return }
        note("${r.size} הצעות לשמות.")
        r.forEach { x ->
            val c = card()
            c.addView(label((if (x.isDir) "📁 תיקייה: " else "📖 קובץ: ") + x.file.name, 13.5f, Col.SUB))
            c.addView(label("← " + x.newName, 16.5f, Col.TEXT, true).apply { setPadding(0, dp(4), 0, dp(6)) })
            val b = hbox()
            b.addView(chip("לשנות", false) {
                bg.execute { val ok = Organizer.rename(x); ui { toast(if (ok) "השם שונה" else "לא ניתן לשנות את השם"); renames = r - x; render() } }
            }, lp(WRAP, WRAP).m(0, 0, dp(6), 0))
            b.addView(chip("להשאיר", false) { renames = r - x; render() }, lp(WRAP, WRAP).m(0, 0, dp(6), 0))
            b.addView(chip("עריכה ✎", false) {
                Sheet.input(this, "שם חדש", x.newName, "שם") { n -> if (n.isNotBlank()) { x.newName = n.trim(); render() } }
            }, lp(WRAP, WRAP).m(0, 0, dp(6), 0))
            if (!x.isDir) b.addView(chip("פתיחה", false) { ReaderActivity.open(this, x.file.absolutePath, 1) })
            c.addView(b)
        }
    }

    private fun doNames(all: Boolean) {
        run("מחפש שמות לשיפור…", { p -> Organizer.beginBatch(); renames = Organizer.suggestNames(Library.roots(), all, p) }) { render() }
    }

    private fun undo() {
        val n = Organizer.undoCount()
        if (n == 0) { Sheet.info(this, "אין מה לבטל", "לא בוצעו העברות או שינויי שמות מאז הפעולה האחרונה."); return }
        Sheet.confirm(this, "ביטול הפעולה האחרונה", "להחזיר $n קבצים/תיקיות למקום ולשם הקודמים?", "ביטול הפעולה") {
            run("מחזיר…", { _ -> val k = Organizer.undo(); ui { toast("הוחזרו $k") } }) { dups = null; suggestions = null; renames = null; plan = null; render() }
        }
    }

    override fun onDestroy() { super.onDestroy(); bg.shutdown() }
}
```

## `app/src/main/java/com/chdv/searchplus/Organizer.kt`

```kotlin
package com.chdv.searchplus

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Tidying the library on the device itself:
 *  - finding duplicate books (identical file, or the same text saved in another file),
 *  - suggesting the right folder for each book by what is written in it, and moving the files.
 * Every move is written to a log so the last tidy-up can be undone.
 */
object Organizer {
    const val DUP_DIR = "_כפולים"
    @Volatile var buildTargetsCache: List<Target>? = null

    // ---------------- duplicates ----------------

    enum class DupKind(val title: String) { SAME_FILE("קובץ זהה לגמרי"), SAME_TEXT("אותו תוכן (גם אם שם הקובץ שונה)") }

    class DupGroup(val kind: DupKind, val books: List<Book>, val similarity: Int = 100)

    private fun quickHash(f: File): String? = try {
        val md = MessageDigest.getInstance("MD5")
        RandomAccessFile(f, "r").use { r ->
            val len = r.length()
            val buf = ByteArray(65536)
            var n = r.read(buf); if (n > 0) md.update(buf, 0, n)
            if (len > 131072) { r.seek(len / 2); n = r.read(buf); if (n > 0) md.update(buf, 0, n) }
            if (len > 65536) { r.seek(maxOf(0, len - 65536)); n = r.read(buf); if (n > 0) md.update(buf, 0, n) }
            md.update(len.toString().toByteArray())
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    private const val SKETCH = 128

    /**
     * A fingerprint of what is written in the book (not of its name): the smallest hashes of all the
     * 5-word sequences in up to 12 pages. Two books with the same text have mostly the same smallest
     * hashes, even if one is a different scan, has a different cover page or a few OCR mistakes.
     */
    private fun sketch(b: Book): IntArray? {
        if (b.textPages < 2) return null
        val db = Db.get()
        val words = ArrayList<String>()
        var got = 0
        var p = 1
        while (got < 12 && p <= minOf(b.pages, 60)) {
            val t = db.pageText(b.id, p)
            if (t != null && Heb.letterCount(t) > 120) { words.addAll(Heb.words(t)); got++ }
            p++
        }
        if (words.size < 120) return null
        val hs = HashSet<Int>()
        for (i in 0..words.size - 5) {
            var h = 1125899907
            for (j in 0 until 5) h = h * 31 + words[i + j].hashCode()
            hs.add(h xor (h ushr 15))
        }
        return hs.sorted().take(SKETCH).toIntArray()
    }

    private fun jaccard(a: IntArray, b: IntArray): Float {
        // estimate from bottom-k sketches
        val union = (a.toList() + b.toList()).toSortedSet().take(SKETCH).toSet()
        val sa = a.toHashSet(); val sb = b.toHashSet()
        val both = union.count { it in sa && it in sb }
        return both.toFloat() / union.size.coerceAtLeast(1)
    }

    fun findDuplicates(progress: (String) -> Unit): List<DupGroup> {
        val books = Db.get().books().values.filter { File(it.path).exists() }
        val out = ArrayList<DupGroup>()
        val used = HashSet<Long>()
        // 1. identical files: same size, then same bytes
        progress("משווה קבצים…")
        books.groupBy { it.size }.values.filter { it.size > 1 }.forEach { same ->
            same.groupBy { quickHash(File(it.path)) }.forEach { (h, g) ->
                if (h != null && g.size > 1) { out.add(DupGroup(DupKind.SAME_FILE, g)); g.forEach { used.add(it.id) } }
            }
        }
        // 2. the same text inside different files
        val sk = HashMap<Long, IntArray>()
        var i = 0
        for (b in books) {
            if (++i % 25 == 0) progress("קורא את תוכן הספרים להשוואה… $i/${books.size}")
            sketch(b)?.let { sk[b.id] = it }
        }
        progress("מחפש ספרים עם אותו תוכן…")
        // candidates: books that share one of their 6 smallest hashes
        val buckets = HashMap<Int, MutableList<Long>>()
        for ((id, s) in sk) s.take(6).forEach { buckets.getOrPut(it) { ArrayList() }.add(id) }
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long { var r = x; while (parent[r] != null && parent[r] != r) r = parent[r]!!; return r }
        val sims = HashMap<Long, Float>()
        val checked = HashSet<Long>()
        for (ids in buckets.values) {
            if (ids.size < 2 || ids.size > 50) continue
            for (a in ids.indices) for (b in a + 1 until ids.size) {
                val x = ids[a]; val y = ids[b]
                val key = minOf(x, y) * 1_000_003L + maxOf(x, y)
                if (!checked.add(key)) continue
                val j = jaccard(sk[x]!!, sk[y]!!)
                if (j >= 0.5f) {
                    val rx = find(x); val ry = find(y)
                    if (rx != ry) parent[rx] = ry
                    sims[x] = maxOf(sims[x] ?: 0f, j); sims[y] = maxOf(sims[y] ?: 0f, j)
                }
            }
        }
        val groups = HashMap<Long, MutableList<Long>>()
        for (id in sims.keys) groups.getOrPut(find(id)) { ArrayList() }.add(id)
        val m = Db.get().books()
        for (g in groups.values) {
            if (g.size < 2) continue
            val bs = g.mapNotNull { m[it] }
            if (bs.all { it.id in used } && out.any { d -> d.books.map { it.id }.toSet() == bs.map { it.id }.toSet() }) continue
            out.add(DupGroup(DupKind.SAME_TEXT, bs, (g.minOf { sims[it] ?: 0f } * 100).toInt()))
        }
        return out
    }

    // ---------------- moving files (with undo) ----------------

    /** Move a file; works across volumes (copy + delete). Returns the new file or null. */
    fun move(src: File, destDir: File): File? {
        destDir.mkdirs()
        var dest = File(destDir, src.name)
        var k = 2
        while (dest.exists()) { dest = File(destDir, src.nameWithoutExtension + " ($k)." + src.extension); k++ }
        if (src.renameTo(dest)) return dest
        return try {
            FileInputStream(src).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o, 1 shl 16) } }
            if (dest.length() == src.length() && src.delete()) dest else { dest.delete(); null }
        } catch (e: Exception) { dest.delete(); null }
    }

    /** Move a book and keep the index (no need to read it again). */
    fun moveBook(path: String, destDir: File): File? {
        val src = File(path)
        val dest = move(src, destDir) ?: return null
        val db = Db.get()
        db.bookByPath(path)?.let { b ->
            db.writableDatabase.execSQL("UPDATE files SET path=?, dir=?, name=?, mtime=? WHERE id=?",
                arrayOf(dest.absolutePath, dest.parent, dest.name, dest.lastModified(), b.id))
            db.invalidate()
        }
        val log = Prefs.list("moveLog")
        log.add(0, "M|${dest.absolutePath}|${src.parent}")
        Prefs.putList("moveLog", log.take(5000))
        return dest
    }

    fun beginBatch() { Prefs.put("moveLog", "") }
    fun undoCount() = Prefs.list("moveLog").size

    fun undo(): Int {
        var n = 0
        for (e in Prefs.list("moveLog")) {
            val parts = e.split('|')
            if (parts.size < 3) continue
            val f = File(parts[1])
            if (!f.exists()) continue
            val ok = if (parts[0] == "R") renameRaw(f, File(parts[2])) else moveBookNoLog(f, File(parts[2]))
            if (ok) n++
        }
        Prefs.put("moveLog", "")
        return n
    }

    private fun moveBookNoLog(src: File, destDir: File): Boolean {
        val path = src.absolutePath
        val dest = move(src, destDir) ?: return false
        val db = Db.get()
        db.bookByPath(path)?.let { b ->
            db.writableDatabase.execSQL("UPDATE files SET path=?, dir=?, name=?, mtime=? WHERE id=?",
                arrayOf(dest.absolutePath, dest.parent, dest.name, dest.lastModified(), b.id))
            db.invalidate()
        }
        return true
    }

    fun dupDir(): File = File(Library.roots().firstOrNull() ?: Library.storageRoot().absolutePath, DUP_DIR)

    // ---------------- smart sorting into folders ----------------

    private val STOP = hashSetOf(
        "של", "את", "על", "הוא", "זה", "לא", "כי", "אשר", "עם", "גם", "כל", "אם", "מה", "היא", "הנה", "וכו", "אל", "או", "יש",
        "אין", "רק", "כמו", "אבל", "זו", "היה", "הם", "בו", "לו", "כן", "עד", "מן", "שהוא", "ולא", "וזה", "דהנה", "והנה", "להיות",
        "שלא", "אלא", "בזה", "וגם", "לכן", "אך", "הרי", "מפני", "שם", "בין", "לפי", "וכן", "אחד", "אותו", "היינו", "שהם", "אותם",
        "יותר", "מאד", "הזה", "הזאת", "כאשר", "ואם", "וכל", "שזה", "ועל", "וכמו", "שיש", "שאין", "עוד", "ואין", "וגו", "ע\"ה", "the", "and", "of"
    )

    /** Word counts of a text, without very common words. */
    private fun bag(text: String, into: HashMap<String, Float>, weight: Float = 1f) {
        for (w in Heb.words(text)) {
            if (w.length < 3 || w in STOP || w.all { it.isDigit() }) continue
            into[w] = (into[w] ?: 0f) + weight
        }
    }

    /** The text we use to understand what a book is about: title + a few pages from its start and middle. */
    private fun bookBag(path: String, title: String): HashMap<String, Float> {
        val m = HashMap<String, Float>()
        bag(title, m, 6f)
        val db = Db.get()
        val b = db.bookByPath(path)
        if (b != null && b.textPages > 0) {
            val pages = listOf(2, 3, 4, 6, 8, 10, b.pages / 2, b.pages / 2 + 1).filter { it in 1..b.pages }.distinct()
            for (p in pages) db.pageText(b.id, p)?.let { bag(it, m) }
        } else {
            // not indexed yet (e.g. a book in the Download folder) - read a few pages now
            try {
                Indexer.loadDoc(File(path)).use { doc ->
                    val st = com.tom_roush.pdfbox.text.PDFTextStripper()
                    st.startPage = 1; st.endPage = minOf(doc.numberOfPages, 8)
                    Indexer.prepare(st.getText(doc))?.let { bag(it, m) }
                }
            } catch (e: Throwable) {}
        }
        return m
    }

    class Target(val dir: String, val cat: List<String>) {
        val bag = HashMap<String, Float>()
        var vec: Map<String, Float> = emptyMap()
        var norm = 1f
        var books = 0
    }

    class Suggestion(val path: String, val title: String, var dest: Target?, val score: Float, val currentCat: List<String>) {
        var selected = true
    }

    /** All folders (up to 3 levels) of the library, with a description of what is in them. */
    fun buildTargets(c: Context, progress: (String) -> Unit): List<Target> {
        val db = Db.get()
        val targets = LinkedHashMap<String, Target>()
        for (r in Library.roots()) {
            fun walk(d: File, depth: Int) {
                if (depth > 3) return
                d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != DUP_DIR }?.forEach { k ->
                    targets[k.absolutePath] = Target(k.absolutePath, Library.categoryOf(k.absolutePath))
                    walk(k, depth + 1)
                }
            }
            walk(File(r), 1)
        }
        val books = db.books().values.filter { it.textPages > 0 }
        // sample books of every folder
        val perFolder = HashMap<String, Int>()
        var i = 0
        for (b in books.shuffled(java.util.Random(7))) {
            val owners = targets.keys.filter { b.dir == it || b.dir.startsWith("$it/") }
            if (owners.isEmpty()) continue
            if (owners.all { (perFolder[it] ?: 0) >= 25 }) continue
            if (++i % 20 == 0) progress("לומד מה יש בכל תיקייה… $i")
            val bb = bookBag(b.path, b.title)
            for (o in owners) {
                if ((perFolder[o] ?: 0) >= 25) continue
                perFolder[o] = (perFolder[o] ?: 0) + 1
                val t = targets[o]!!
                t.books++
                for ((w, n) in bb) t.bag[w] = (t.bag[w] ?: 0f) + n
            }
        }
        // the folder name itself is a strong hint
        for (t in targets.values) bag(File(t.dir).name, t.bag, 25f)
        // tf-idf
        val df = HashMap<String, Int>()
        targets.values.forEach { t -> t.bag.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val nT = targets.size.coerceAtLeast(1)
        for (t in targets.values) {
            val top = t.bag.entries.sortedByDescending { it.value }.take(600)
            val v = HashMap<String, Float>()
            var s = 0f
            for ((w, n) in top) {
                val x = (1 + ln(n.toDouble())).toFloat() * ln(1.0 + nT.toDouble() / (df[w] ?: 1)).toFloat()
                v[w] = x; s += x * x
            }
            t.vec = v; t.norm = sqrt(s).coerceAtLeast(1e-6f)
            t.bag.clear()
        }
        return targets.values.toList()
    }

    private fun cosine(b: Map<String, Float>, bn: Float, t: Target): Float {
        var dot = 0f
        for ((w, x) in b) { val y = t.vec[w] ?: continue; dot += x * y }
        return dot / (bn * t.norm)
    }

    /** Best folder for a book: first the best top folder, then go down while the match stays good. */
    fun classify(path: String, title: String, targets: List<Target>): Pair<Target?, Float> {
        val raw = bookBag(path, title)
        if (raw.isEmpty()) return null to 0f
        val v = HashMap<String, Float>()
        var s = 0f
        for ((w, n) in raw) { val x = (1 + ln(n.toDouble())).toFloat(); v[w] = x; s += x * x }
        val bn = sqrt(s).coerceAtLeast(1e-6f)
        var level = targets.filter { it.cat.size == targets.minOf { t -> t.cat.size } }
        var best: Target? = null
        var bestScore = 0f
        while (level.isNotEmpty()) {
            val scored = level.map { it to cosine(v, bn, it) }.sortedByDescending { it.second }
            val top = scored.first()
            val second = scored.getOrNull(1)?.second ?: 0f
            if (top.second < 0.04f || (best != null && top.second < bestScore * 0.6f)) break
            if (best != null && top.second - second < 0.005f) break
            best = top.first; bestScore = top.second
            level = targets.filter { it.cat.size == best.cat.size + 1 && it.dir.startsWith(best.dir + "/") }
        }
        return best to bestScore
    }

    /** Suggestions for the books in [sourceDir] (or loose books in the library roots when null). */
    fun suggest(c: Context, sourceDir: String?, checkAll: Boolean, progress: (String) -> Unit): List<Suggestion> {
        val targets = buildTargets(c, progress)
        buildTargetsCache = targets
        if (targets.isEmpty()) return emptyList()
        val files: List<Pair<String, String>> = when {
            sourceDir != null -> (File(sourceDir).listFiles()?.filter { it.isFile && it.name.endsWith(".pdf", true) } ?: emptyList())
                .map { it.absolutePath to it.nameWithoutExtension }
            checkAll -> Db.get().books().values.map { it.path to it.title }
            else -> Db.get().books().values.filter { Library.categoryOf(it.dir).let { cat -> cat.isEmpty() || (Library.roots().size > 1 && cat.size == 1) } }
                .map { it.path to it.title }
        }
        val out = ArrayList<Suggestion>()
        files.forEachIndexed { i, (p, t) ->
            progress("מנתח ספרים… ${i + 1}/${files.size}")
            val (dest, score) = classify(p, t, targets)
            val cur = Library.categoryOf(File(p).parent ?: "")
            if (dest == null) return@forEachIndexed
            if (checkAll) {
                // only books that look clearly misplaced: the suggested top folder differs from the current one
                if (cur.isEmpty() || dest.cat.firstOrNull() == cur.firstOrNull() || score < 0.12f) return@forEachIndexed
            }
            if (File(p).parent == dest.dir) return@forEachIndexed
            out.add(Suggestion(p, t, dest, score, cur).apply { selected = score >= 0.08f })
        }
        return out.sortedByDescending { it.score }
    }

    // ---------------- smart names ----------------

    private val BAD = Regex("(?i)^(scan|scanned|img|image|doc|document|file|untitled|new|hebrewbooks|hebrewbooks_org|book|pdf|download|copy|cam|dsc)?[ _\\-.]*(org)?[ _\\-.]*\\d*[ _\\-.()0-9a-f]*$")

    /** Is this name meaningless (scan0012, 12345, HebrewBooks_org_4521, New folder…)? */
    fun meaningless(name: String): Boolean {
        val n = name.substringBeforeLast('.').trim()
        if (n.isEmpty()) return true
        if (n.any { Heb.isHebLetter(it) }) return n.startsWith("תיקייה חדשה") || n.startsWith("עותק של")
        if (n.startsWith("New folder", true) || n.startsWith("Untitled", true)) return true
        return BAD.matches(n) || n.count { it.isDigit() } * 2 >= n.length
    }

    /** Characters that are not allowed in file names on memory cards. */
    fun safeName(s: String): String = s.replace('"', '\u05F4').replace('\'', '\u05F3')
        .replace(Regex("[\\\\/:*?<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(90)

    /** A title for a book from what is written on its title page (the biggest letters), or from the PDF metadata. */
    fun suggestBookName(path: String): String? {
        return try {
            Indexer.loadDoc(File(path)).use { doc ->
                val meta = doc.documentInformation?.title?.trim()
                if (meta != null && meta.count { Heb.isHebLetter(it) } >= 3 && meta.length in 3..90) return safeName(meta)
                for (pg in 1..minOf(3, doc.numberOfPages)) {
                    val plain = com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = pg; endPage = pg }
                    val method = Heb.repairPage(plain.getText(doc)).method
                    data class W(val text: String, val size: Float, val y: Float)
                    val ws = ArrayList<W>()
                    val st = object : com.tom_roush.pdfbox.text.PDFTextStripper() {
                        override fun writeString(text: String?, textPositions: MutableList<com.tom_roush.pdfbox.text.TextPosition>?) {
                            val tps = textPositions ?: return
                            if (tps.isEmpty()) return
                            val raw = tps.joinToString("") { it.unicode ?: "" }
                            val t = Heb.clean(Heb.applyMethod(raw, method))
                            if (t.isBlank()) return
                            ws.add(W(t, tps.map { it.fontSizeInPt * it.textMatrix.scalingFactorY }.average().toFloat().coerceAtLeast(tps[0].heightDir), tps[0].yDirAdj))
                        }
                    }
                    st.startPage = pg; st.endPage = pg
                    st.getText(doc)
                    val words = ws.filter { w -> w.text.count { Heb.isHebLetter(it) || it.isLetter() } >= 2 }
                    if (words.isEmpty()) continue
                    val maxSize = words.maxOf { it.size }
                    val big = words.filter { it.size >= maxSize * 0.85f }
                    // the lines of the biggest letters, top to bottom
                    val lines = big.groupBy { (it.y / (maxSize * 0.6f)).toInt() }.toSortedMap().values.take(2)
                    val title = lines.joinToString(" ") { line -> line.joinToString(" ") { it.text } }
                    val clean = safeName(title)
                    if (clean.count { Heb.isHebLetter(it) } >= 3 && clean.length >= 3) return clean
                }
                null
            }
        } catch (e: Throwable) { null }
    }

    /** A name for a folder from the titles of the books in it (what they have in common). */
    fun suggestFolderName(dir: File): String? {
        val titles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".pdf", true) }
            ?.map { it.nameWithoutExtension }?.filter { !meaningless(it) } ?: return null
        if (titles.size < 2) return null
        val tw = titles.map { t -> t.split(Regex("[\\s_\\-]+")).filter { it.isNotBlank() } }
        // longest beginning that most titles share ("לקוטי שיחות חלק א", "לקוטי שיחות חלק ב" -> "לקוטי שיחות")
        for (len in 4 downTo 1) {
            val counts = HashMap<String, Int>()
            tw.filter { it.size >= len }.forEach { counts[it.take(len).joinToString(" ")] = (counts[it.take(len).joinToString(" ")] ?: 0) + 1 }
            val best = counts.maxByOrNull { it.value } ?: continue
            if (best.value >= titles.size * 0.6 && best.key.count { Heb.isHebLetter(it) } >= 3 && Heb.words(best.key).none { it in setOf("חלק", "כרך", "ספר") && len == 1 })
                return safeName(best.key)
        }
        // otherwise the most common pair of words
        val pairs = HashMap<String, Int>()
        tw.forEach { ws -> ws.windowed(2).map { it.joinToString(" ") }.toSet().forEach { pairs[it] = (pairs[it] ?: 0) + 1 } }
        val best = pairs.maxByOrNull { it.value } ?: return null
        return if (best.value >= titles.size * 0.5 && best.key.count { Heb.isHebLetter(it) } >= 3) safeName(best.key) else null
    }

    class Rename(val file: File, val isDir: Boolean, var newName: String) { var selected = true }

    fun suggestNames(roots: List<String>, all: Boolean, progress: (String) -> Unit): List<Rename> {
        val out = ArrayList<Rename>()
        var n = 0
        for (r in roots) {
            val stack = ArrayDeque<File>(); stack.add(File(r))
            while (stack.isNotEmpty()) {
                val d = stack.removeLast()
                val kids = d.listFiles() ?: continue
                for (k in kids) {
                    if (k.name.startsWith(".") || k.name == DUP_DIR) continue
                    if (k.isDirectory) {
                        stack.add(k)
                        if (all || meaningless(k.name)) {
                            val s = suggestFolderName(k)
                            if (s != null && s != k.name) out.add(Rename(k, true, s))
                        }
                    } else if (k.name.endsWith(".pdf", true) && (all || meaningless(k.nameWithoutExtension))) {
                        if (++n % 5 == 0) progress("קורא דפי שער… $n")
                        val s = suggestBookName(k.absolutePath)
                        if (s != null && s != k.nameWithoutExtension) out.add(Rename(k, false, s))
                    }
                }
            }
        }
        return out
    }

    private fun renameRaw(src: File, dest: File): Boolean {
        if (dest.exists() || !src.renameTo(dest)) return false
        val db = Db.get()
        val old = src.absolutePath; val nw = dest.absolutePath
        db.writableDatabase.execSQL("UPDATE files SET path=?, dir=?, name=? WHERE path=?", arrayOf(nw, dest.parent, dest.name, old))
        db.writableDatabase.execSQL("UPDATE files SET path=? || substr(path, ?), dir=? || substr(dir, ?) WHERE path LIKE ? ESCAPE '\\'",
            arrayOf(nw, old.length + 1, nw, old.length + 1, old.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%"))
        db.invalidate()
        val roots = Library.roots().map { if (it == old) nw else if (it.startsWith("$old/")) nw + it.substring(old.length) else it }
        Library.setRoots(roots)
        return true
    }

    /** Rename a book file or a folder, keep the index, write to the undo log. */
    fun rename(r: Rename): Boolean {
        val ext = if (r.isDir) "" else "." + r.file.extension
        var dest = File(r.file.parentFile, safeName(r.newName) + ext)
        var k = 2
        while (dest.exists()) { dest = File(r.file.parentFile, safeName(r.newName) + " ($k)" + ext); k++ }
        val was = r.file.absolutePath
        if (!renameRaw(r.file, dest)) return false
        val log = Prefs.list("moveLog"); log.add(0, "R|${dest.absolutePath}|$was"); Prefs.putList("moveLog", log.take(5000))
        return true
    }

    // ---------------- sorting one folder, with a preview ----------------

    enum class Scheme(val title: String, val about: String) {
        TOPIC("לפי נושא (לפי התוכן)", "התוכנה קוראת את הספרים, מקבצת ספרים שעוסקים באותו נושא, ונותנת לכל קבוצה שם"),
        SERIES("לפי סדרה / שם משותף", "ספרים ששמם מתחיל אותו דבר (לקוטי שיחות חלק א, ב…) נכנסים לתיקייה אחת"),
        LETTER("לפי האות הראשונה", "תיקייה לכל אות: א, ב, ג…"),
        LIBRARY("לתיקיות הקיימות במאגר", "כל ספר עובר לתיקייה המתאימה לו במאגר (הלכה, חסידות…)"),
    }

    class PGroup(var name: String, val books: MutableList<File>, var dest: String? = null)

    /** A proposed arrangement of the PDFs of one folder. The user edits it, then [applyPlan]. */
    class Plan(val dir: File, val scheme: Scheme, val groups: MutableList<PGroup>, val stay: MutableList<File>)

    private fun pdfsIn(dir: File) = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".pdf", true) }?.sortedBy { it.name } ?: emptyList()

    fun plan(c: Context, dir: File, scheme: Scheme, progress: (String) -> Unit): Plan {
        val files = pdfsIn(dir)
        val stay = ArrayList<File>()
        val groups = ArrayList<PGroup>()
        when (scheme) {
            Scheme.LETTER -> {
                files.groupBy { f -> f.nameWithoutExtension.trim().firstOrNull { it.isLetterOrDigit() }?.let { ch ->
                    if (Heb.isHebLetter(ch)) "ךםןףץ".indexOf(ch).let { i -> if (i >= 0) "כמנפצ"[i] else ch }.toString()
                    else if (ch.isDigit()) "0-9" else ch.uppercaseChar().toString()
                } ?: "אחר" }.toSortedMap().forEach { (k, v) -> groups.add(PGroup(k, v.toMutableList())) }
            }
            Scheme.SERIES -> {
                val left = files.toMutableList()
                for (len in 4 downTo 1) {
                    val byKey = left.groupBy { f -> f.nameWithoutExtension.split(Regex("[\\s_\\-,]+")).filter { it.isNotBlank() }.let { w -> if (w.size > len) w.take(len).joinToString(" ") else "" } }
                    byKey.forEach { (k, v) ->
                        if (k.isNotEmpty() && v.size >= 2 && k.count { it.isLetter() } >= 3 && !(len == 1 && Heb.words(k).firstOrNull() in setOf("ספר", "חלק", "כרך"))) {
                            groups.add(PGroup(safeName(k), v.toMutableList())); left.removeAll(v)
                        }
                    }
                }
                stay.addAll(left)
            }
            Scheme.TOPIC -> topicGroups(files, progress, groups, stay)
            Scheme.LIBRARY -> {
                val targets = buildTargets(c, progress)
                buildTargetsCache = targets
                val by = LinkedHashMap<String, PGroup>()
                files.forEachIndexed { i, f ->
                    progress("מנתח ספרים… ${i + 1}/${files.size}")
                    val (t, score) = classify(f.absolutePath, f.nameWithoutExtension, targets)
                    if (t == null || score < 0.05f || t.dir == dir.absolutePath) stay.add(f)
                    else by.getOrPut(t.dir) { PGroup(t.cat.joinToString(" › "), ArrayList(), t.dir) }.books.add(f)
                }
                groups.addAll(by.values)
            }
        }
        return Plan(dir, scheme, groups.sortedByDescending { it.books.size }.toMutableList(), stay)
    }

    private fun topicGroups(files: List<File>, progress: (String) -> Unit, groups: MutableList<PGroup>, stay: MutableList<File>) {
        if (files.size < 4) { stay.addAll(files); return }
        val bags = files.mapIndexed { i, f -> progress("קורא את הספרים… ${i + 1}/${files.size}"); bookBag(f.absolutePath, f.nameWithoutExtension) }
        val df = HashMap<String, Int>()
        bags.forEach { b -> b.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = files.size
        // words that appear in a few books but not in almost all of them describe a topic
        val vecs = bags.map { b ->
            val v = HashMap<String, Float>()
            var s = 0f
            for ((w, c) in b) {
                val d = df[w] ?: 1
                if (d < 2 || d > n * 0.6) continue
                val x = (1 + ln(c.toDouble())).toFloat() * ln(n.toDouble() / d).toFloat()
                v[w] = x; s += x * x
            }
            val nn = sqrt(s).coerceAtLeast(1e-6f)
            for (k in v.keys.toList()) v[k] = v[k]!! / nn
            v
        }
        fun dot(a: Map<String, Float>, b: Map<String, Float>): Float { var d = 0f; val (x, y) = if (a.size < b.size) a to b else b to a; for ((w, v) in x) y[w]?.let { d += v * it }; return d }
        val k = sqrt(n / 2.0).toInt().coerceIn(2, 12)
        // farthest-point start, then a few rounds of k-means on the sphere
        val centers = ArrayList<Map<String, Float>>()
        centers.add(vecs[0])
        while (centers.size < k) {
            val far = vecs.indices.minByOrNull { i -> centers.maxOf { dot(vecs[i], it) } } ?: break
            centers.add(vecs[far])
        }
        var assign = IntArray(n)
        repeat(8) { round ->
            progress("מקבץ לפי נושאים… ${round + 1}/8")
            assign = IntArray(n) { i -> centers.indices.maxByOrNull { dot(vecs[i], centers[it]) } ?: 0 }
            for (ci in centers.indices) {
                val m = HashMap<String, Float>()
                vecs.indices.filter { assign[it] == ci }.forEach { i -> for ((w, v) in vecs[i]) m[w] = (m[w] ?: 0f) + v }
                val top = m.entries.sortedByDescending { it.value }.take(300)
                var s = 0f; top.forEach { s += it.value * it.value }
                val nn = sqrt(s).coerceAtLeast(1e-6f)
                centers[ci] = top.associate { it.key to it.value / nn }
            }
        }
        for (ci in centers.indices) {
            val members = files.indices.filter { assign[it] == ci && dot(vecs[it], centers[ci]) > 0.05f }
            val loose = files.indices.filter { assign[it] == ci && it !in members }
            loose.forEach { stay.add(files[it]) }
            if (members.size < 2) { members.forEach { stay.add(files[it]) }; continue }
            groups.add(PGroup(topicName(members.map { files[it] }, centers[ci], centers), members.map { files[it] }.toMutableList()))
        }
    }

    /** Name for a topic group: a shared title beginning, otherwise its two most typical words. */
    private fun topicName(fs: List<File>, center: Map<String, Float>, all: List<Map<String, Float>>): String {
        val tw = fs.map { f -> f.nameWithoutExtension.split(Regex("[\\s_\\-,]+")).filter { it.isNotBlank() } }
        for (len in 3 downTo 1) {
            val cnt = tw.filter { it.size >= len }.groupingBy { it.take(len).joinToString(" ") }.eachCount()
            val b = cnt.maxByOrNull { it.value }
            if (b != null && b.value >= fs.size * 0.6 && b.key.count { Heb.isHebLetter(it) } >= 3) return safeName(b.key)
        }
        val words = center.entries.sortedByDescending { e -> e.value - all.filter { it !== center }.maxOfOrNull { it[e.key] ?: 0f }.let { it ?: 0f } * 0.5f }
            .map { it.key }.filter { w -> w.length >= 3 && w.count { Heb.isHebLetter(it) } >= 3 }.take(2)
        return safeName(if (words.isEmpty()) "נושא" else words.joinToString(" "))
    }

    /** Make the planned changes on the device. */
    fun applyPlan(p: Plan, progress: (String) -> Unit): Int {
        beginBatch()
        var moved = 0
        val all = p.groups.sumOf { it.books.size }
        for (g in p.groups) {
            if (g.books.isEmpty()) continue
            val dest = File(g.dest ?: File(p.dir, safeName(g.name)).absolutePath)
            for (f in g.books) {
                progress("מעביר… ${moved + 1}/$all")
                if (f.parentFile?.absolutePath == dest.absolutePath) continue
                if (moveBook(f.absolutePath, dest) != null) moved++
            }
        }
        return moved
    }
}
```

## `app/src/main/java/com/chdv/searchplus/ReaderActivity.kt`

```kotlin
package com.chdv.searchplus

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.concurrent.Executors

/**
 * Built-in PDF reader: opens on the page of the result and marks the searched words on the page.
 * Made for E-ink: whole pages, no animation, page turning by tapping the side of the screen,
 * with the volume keys or with the buttons at the bottom.
 */
class ReaderActivity : BaseActivity() {

    companion object {
        fun open(c: Context, path: String, page: Int, terms: List<String> = emptyList(), hits: IntArray? = null,
                 refLabel: String? = null, amudIndex: Int = -1, exact: Boolean = true, phrase: String? = null) {
            if (c is android.app.Activity && !Prefs.b("readerChosen", false)) {
                Library.chooseDefault(c) { open(c, path, page, terms, hits, refLabel, amudIndex, exact, phrase) }
                return
            }
            if (Prefs.s("openWith", "internal") == "external" && c is android.app.Activity) {
                Library.addRecent(path, page)
                Library.openExternal(c, path, page, terms, chooser = false, phrase = phrase)
                return
            }
            c.startActivity(Intent(c, ReaderActivity::class.java)
                .putExtra("path", path).putExtra("page", page)
                .putExtra("terms", terms.toTypedArray())
                .putExtra("hits", hits)
                .putExtra("refLabel", refLabel).putExtra("amudIndex", amudIndex).putExtra("exact", exact)
                .putExtra("phrase", phrase))
        }
    }

    private val exec = Executors.newSingleThreadExecutor()
    private var path: String? = null
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var doc: PDDocument? = null
    private var pageCount = 0
    private var page = 1
    private var terms: List<String> = emptyList()
    /** The sentence to mark (from question & answer). */
    private var phrase: String? = null
    private var phrasePage = 0
    private var hits: IntArray = IntArray(0)
    private lateinit var view: PageView
    private lateinit var pageLabel: TextView
    private lateinit var hitLabel: TextView
    private lateinit var titleView: TextView
    private var renderToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Indexer.initPdfBox(this)
        var uri: Uri? = null
        if (intent.action == Intent.ACTION_VIEW) {
            uri = intent.data
            if (uri?.scheme == "file") path = uri.path
        } else path = intent.getStringExtra("path")
        page = savedInstanceState?.getInt("page") ?: intent.getIntExtra("page", 0).let { if (it <= 0 && path != null) Library.lastPage(path!!) else it.coerceAtLeast(1) }
        terms = intent.getStringArrayExtra("terms")?.toList() ?: emptyList()
        phrase = intent.getStringExtra("phrase")
        phrasePage = page
        hits = intent.getIntArrayExtra("hits") ?: IntArray(0)

        build()
        try {
            pfd = if (path != null) ParcelFileDescriptor.open(File(path!!), ParcelFileDescriptor.MODE_READ_ONLY)
            else contentResolver.openFileDescriptor(uri!!, "r")
            renderer = PdfRenderer(pfd!!)
            pageCount = renderer!!.pageCount
        } catch (e: Exception) {
            Sheet.info(this, "לא ניתן לפתוח את הקובץ", "ייתכן שהקובץ פגום או מוגן בסיסמה.\n\n" + (e.message ?: ""))
            return
        }
        titleView.text = path?.let { File(it).name.removeSuffix(".pdf").removeSuffix(".PDF") } ?: "PDF"
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page)
    }

    private fun build() {
        val root = vbox().apply { setBackgroundColor(Col.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val head = hbox().apply { setPadding(dp(6), dp(4), dp(6), dp(4)) }
        head.addView(label("→", 24f, Col.ACC, true).apply { setPadding(dp(10), 0, dp(10), 0); setOnClickListener { finish() } })
        titleView = label("", 16f, Col.TEXT, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
        head.addView(titleView, lp(0, WRAP, 1f))
        if (terms.isNotEmpty() || phrase != null) head.addView(headerButton("✨") { showAgain() }.apply { contentDescription = "להדגיש שוב" })
        head.addView(headerButton("חיפוש בספר") { searchInBook() })
        head.addView(headerButton("⋯") { menu() })
        root.addView(head, lp(MATCH, WRAP))
        root.addView(divider())

        // opened from a reference (ברכות דף כז): let the user confirm or correct the place, and remember it
        val refLabel = intent.getStringExtra("refLabel")
        val amudIndex = intent.getIntExtra("amudIndex", -1)
        if (refLabel != null) {
            val rb = hbox().apply { setPadding(dp(12), dp(6), dp(8), dp(6)); setBackgroundColor(Col.ACC_LIGHT) }
            rb.addView(label("⤶ " + refLabel + if (!intent.getBooleanExtra("exact", true)) " · משוער" else "", 14.5f, Col.ACC, true), lp(0, WRAP, 1f))
            if (amudIndex >= 0) rb.addView(headerButton("✓ זה המקום") {
                path?.let { Jump.calibrate(it, page, amudIndex) }
                toast("נשמר – בפעם הבאה ייפתח כאן")
                rb.visibility = View.GONE
            })
            rb.addView(headerButton("✕") { rb.visibility = View.GONE })
            root.addView(rb, lp(MATCH, WRAP))
            if (amudIndex >= 0) root.addView(label("לא הגעתם בדיוק? דפדפו לדף הנכון ולחצו \"✓ זה המקום\" – התוכנה תלמד את הספר", 12.5f, Col.SUB).apply {
                setPadding(dp(12), dp(2), dp(12), dp(4)); id = 77
            }, lp(MATCH, WRAP))
        }

        val frame = FrameLayout(this)
        view = PageView(this)
        view.onTap = { fx ->
            when {
                fx < 0.3f -> next()
                fx > 0.7f -> prev()
                else -> menu()
            }
        }
        frame.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(frame, lp(MATCH, 0, 1f))

        root.addView(divider())
        val bar = hbox().apply { setPadding(dp(6), dp(4), dp(6), dp(4)) }
        bar.addView(barBtn("הקודם ▶") { prev() }, lp(0, WRAP, 1f))
        pageLabel = barBtn("") { goTo() }
        bar.addView(pageLabel, lp(0, WRAP, 1.2f))
        hitLabel = barBtn("") { nextHit() }
        hitLabel.setOnLongClickListener { prevHit(); true }
        bar.addView(hitLabel, lp(0, WRAP, 1.3f))
        bar.addView(barBtn("◀ הבא") { next() }, lp(0, WRAP, 1f))
        root.addView(bar, lp(MATCH, WRAP))
        setContentView(root)
    }

    private fun barBtn(t: String, onClick: () -> Unit) = label(t, 15f, Col.ACC, true).apply {
        gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
        setPadding(dp(4), dp(10), dp(4), dp(10))
        background = pressBg(Color.TRANSPARENT, Col.ACC_LIGHT, 12)
        setOnClickListener { onClick() }
    }

    private fun updateBar() {
        pageLabel.text = "עמוד $page / $pageCount"
        if (hits.isEmpty()) {
            hitLabel.text = ""
            hitLabel.visibility = View.GONE
        } else {
            val idx = hits.indexOf(page)
            hitLabel.visibility = View.VISIBLE
            hitLabel.text = if (idx >= 0) "תוצאה ${idx + 1}/${hits.size} ◀" else "לתוצאה הבאה ◀"
        }
    }

    private fun show() {
        if (renderer == null) return
        page = page.coerceIn(1, pageCount.coerceAtLeast(1))
        updateBar()
        path?.let { Library.addRecent(it, page) }
        val token = ++renderToken
        val w = view.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val p = page
        exec.execute {
            if (token != renderToken) return@execute
            val r = renderer ?: return@execute
            val rendered: Pair<Bitmap, Float> = try {
                r.openPage(p - 1).use { pg ->
                    val bw = (w * 1.5f).toInt().coerceIn(600, 2000)
                    val bh = (bw.toLong() * pg.height / pg.width.coerceAtLeast(1)).toInt().coerceIn(200, 4000)
                    val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b to pg.width.toFloat()
                }
            } catch (e: Throwable) {
                ui { toast("לא ניתן להציג את העמוד") }
                return@execute
            }
            val bmp = rendered.first
            val pageW = rendered.second
            ui { if (token == renderToken) view.setPage(bmp, emptyList()) else bmp.recycle() }
            if ((terms.isNotEmpty() || phrase != null) && path != null) {
                val rects = try { highlights(p, bmp.width / pageW, bmp.width) } catch (e: Throwable) { emptyList() }
                ui { if (token == renderToken && rects.isNotEmpty()) view.setHighlights(rects) }
            }
        }
    }

    /** Find the boxes of the searched words on the page with PDFBox (positions of every letter). */
    /**
     * Boxes to mark on the page. With a [phrase] (the sentence that answers a question) the run of
     * words that best matches it is marked as one passage; otherwise every word that contains a
     * searched word.
     */
    private fun highlights(p: Int, scale: Float, bmpW: Int = 0): List<RectF> {
        val d = doc ?: Indexer.loadDoc(File(path!!)).also { doc = it }
        val plain = PDFTextStripper().apply { startPage = p; endPage = p }
        val raw = plain.getText(d)
        val method = Heb.repairPage(raw).method
        val words = ArrayList<Pair<String, RectF>>()
        val st = object : PDFTextStripper() {
            override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                val tps = textPositions ?: return
                val word = ArrayList<TextPosition>()
                fun flush() {
                    if (word.isEmpty()) return
                    val s = Heb.clean(Heb.applyMethod(word.joinToString("") { it.unicode ?: "" }, method)).filter { it.isLetterOrDigit() }
                    if (s.isNotEmpty()) {
                        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -1f; var b = -1f
                        for (tp in word) {
                            val x = tp.xDirAdj; val y = tp.yDirAdj
                            val h = tp.heightDir.coerceAtLeast(tp.fontSizeInPt * 0.7f)
                            l = minOf(l, x); r = maxOf(r, x + tp.widthDirAdj)
                            t = minOf(t, y - h); b = maxOf(b, y)
                        }
                        val pad = 2.5f
                        words.add(s to RectF((l - pad) * scale, (t - pad) * scale, (r + pad) * scale, (b + pad * 2) * scale))
                    }
                    word.clear()
                }
                for (tp in tps) {
                    val u = tp.unicode ?: ""
                    if (u.isBlank() || u == "-" || u == "," || u == "." || u == ":") flush() else word.add(tp)
                }
                flush()
            }
        }
        st.sortByPosition = Prefs.b("sortByPosition", false)
        st.startPage = p; st.endPage = p
        st.getText(d)
        // an old scanned page has no text layer: find the words on the image itself (OCR)
        if (words.size < 5 && bmpW > 0) {
            ui { toast("מחפש את המילים בעמוד הסרוק…") }
            words.clear()
            words.addAll(Ocr.wordBoxes(this, path!!, p - 1, bmpW))
        }

        val ph = phrase?.takeIf { p == phrasePage }?.let { Heb.words(it).filter { w -> w.length > 1 } } ?: emptyList()
        if (ph.size >= 3 && words.isNotEmpty()) {
            // slide a window as long as the sentence and keep the place where most of its words are
            val n = minOf(ph.size, 40)
            val want = ph.take(n)
            var best = -1; var bestScore = 0
            for (i in words.indices) {
                var sc = 0
                for (k in 0 until n) {
                    val w = words.getOrNull(i + k)?.first ?: break
                    if (want.any { it == w || (it.length > 2 && (w.endsWith(it) || it.endsWith(w))) }) sc++
                }
                if (sc > bestScore) { bestScore = sc; best = i }
            }
            if (best >= 0 && bestScore >= minOf(3, n)) return words.subList(best, minOf(words.size, best + n)).map { it.second }
        }
        // final letters compared as regular ones (מלך / מלכים), so every form of the word is marked
        fun fin(x: String) = x.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ')
        val want = terms.map { fin(it.removeSuffix("*")) }.filter { it.isNotEmpty() }
        val minLen = if (want.size == 1) 1 else 2
        return words.filter { (w, _) -> val fw = fin(w); want.any { it.length >= minLen && fw.contains(it) } }.map { it.second }
    }

    private fun next() { if (page < pageCount) { page++; show() } }
    private fun prev() { if (page > 1) { page--; show() } }

    private fun nextHit() {
        val n = hits.firstOrNull { it > page } ?: hits.firstOrNull() ?: return
        page = n; show()
    }

    private fun prevHit() {
        val n = hits.lastOrNull { it < page } ?: hits.lastOrNull() ?: return
        page = n; show()
    }

    private fun goTo() {
        Sheet.input(this, "מעבר לעמוד", "", "1 – $pageCount", numeric = true) { s ->
            s.trim().toIntOrNull()?.let { page = it; show() }
        }
    }

    private fun searchInBook() {
        val p = path ?: return
        val b = Db.get().bookByPath(p)
        if (b == null || b.status != St.DONE) {
            Sheet.info(this, "הספר עוד לא נסרק", "החיפוש בתוך הספר יהיה זמין אחרי שהסריקה תגיע אליו.")
            return
        }
        Sheet.input(this, "חיפוש בתוך הספר", terms.joinToString(" ") { it.removeSuffix("*") }, "מה לחפש?") { q ->
            val r = Search.run(q, Match.valueOf(Prefs.s("match", "PHRASE")), Prefs.b("prefixes", true), b.id)
            val pages = r.books.firstOrNull()?.pages ?: emptyList()
            if (pages.isEmpty()) { Sheet.info(this, "לא נמצא", "\"$q\" לא נמצא בספר הזה."); return@input }
            terms = r.terms
            phrase = null
            hits = pages.map { it.page }.distinct().toIntArray()
            val expr = Search.buildMatch(r.terms, Match.valueOf(Prefs.s("match", "PHRASE")), Prefs.b("prefixes", true))
            Sheet.actions(this, "נמצא ב-${hits.size} עמודים", pages.take(200).map { ph ->
                val sn = Search.snippet(expr, ph.pageId)?.replace("\u0001", "«")?.replace("\u0002", "»")?.replace('\n', ' ') ?: ""
                "עמוד ${ph.page}:  $sn" to { page = ph.page; show() }
            })
        }
    }

    private fun menu() {
        val p = path
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("מעבר לעמוד…" to { goTo() })
        if (terms.isNotEmpty() || phrase != null) items.add("✨ להדגיש שוב את מה שחיפשתי" to { showAgain() })
        if (p != null) {
            val key = "$p|$page"
            items.add((if (Library.isFavorite(key)) "הסרה מהמועדפים" else "הוספת העמוד למועדפים ★") to {
                val on = Library.toggleFavorite(key); toast(if (on) "נוסף למועדפים" else "הוסר מהמועדפים")
            })
            items.add("📄 תצוגת טקסט – שורות מסודרות" to { textView() })
            items.add("העתקת הטקסט של העמוד" to { copyPage() })
            items.add("שיתוף הקובץ" to {
                val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, Library.uriFor(this, File(p)))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                safeStart(Intent.createChooser(i, "שיתוף"))
            })
            items.add("פתיחה באפליקציה אחרת" to { Library.openExternal(this, p, page, terms, chooser = true, phrase = phrase?.takeIf { page == phrasePage }) })
        }
        items.add(if (view.zoomed()) "חזרה לגודל מלא" to { view.reset() } else "הגדלה ×2" to { view.zoomTo(2f) })
        Sheet.actions(this, "עמוד $page", items)
    }

    /**
     * The page as clean text: lines joined into paragraphs, large letters, the searched words or the
     * answering sentence marked. Reads well even when the PDF is an old scan (its text comes from OCR).
     */
    private fun textView() {
        val p = path ?: return
        val b = Db.get().bookByPath(p)
        var pg = page
        val body = vbox().apply { setPadding(dp(18), dp(14), dp(18), dp(14)) }
        val head = label("", 15f, Col.ACC, true)
        val tv = label("", 19f, Col.TEXT).apply { setLineSpacing(0f, 1.35f); setTextIsSelectable(true) }
        fun render() {
            head.text = "עמוד $pg" + (b?.let { " · " + it.title } ?: "")
            val raw = b?.let { Db.get().pageText(it.id, pg) }
            if (raw.isNullOrBlank()) { tv.text = "אין עדיין טקסט לעמוד הזה (אם הוא סרוק – הזיהוי (OCR) עוד לא הגיע אליו)."; return }
            // join broken lines; a line that ends a sentence starts a new paragraph
            val sb = StringBuilder()
            for (line in raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
                if (sb.isNotEmpty()) sb.append(if (sb.last() in ".:?!") "\n\n" else " ")
                sb.append(line)
            }
            val text = sb.toString()
            val sp = android.text.SpannableString(text)
            fun mark(st: Int, en: Int) {
                sp.setSpan(android.text.style.BackgroundColorSpan(if (Col.isEink) 0x40000000 else 0x66FFB300), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val ph = phrase?.takeIf { pg == phrasePage }
            val at = ph?.let { text.indexOf(it.take(40)) } ?: -1
            if (ph != null && at >= 0) mark(at, minOf(text.length, at + ph.length))
            else for (t in terms.map { it.removeSuffix("*") }.filter { it.length > 1 }) {
                var i = text.indexOf(t)
                while (i >= 0) {
                    var st = i; var en = i + t.length
                    while (st > 0 && text[st - 1].isLetter()) st--
                    while (en < text.length && text[en].isLetter()) en++
                    mark(st, en); i = text.indexOf(t, en)
                }
            }
            tv.text = sp
        }
        body.addView(head)
        body.addView(android.widget.ScrollView(this).apply { addView(tv) }, lp(MATCH, dp(480)).m(0, dp(8), 0, dp(8)))
        val row = hbox()
        row.addView(pill("◀ הבא", false) { if (pg < pageCount) { pg++; render() } }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
        row.addView(pill("לעמוד בתמונה") { page = pg; show(); dlg?.dismiss() }, lp(0, WRAP, 1.3f).m(dp(3), 0, dp(3), 0))
        row.addView(pill("הקודם ▶", false) { if (pg > 1) { pg--; render() } }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
        body.addView(row)
        render()
        dlg = Sheet.make(this, body).also { it.show() }
    }
    private var dlg: android.app.Dialog? = null

    /** Mark again where the searched words are (after the marking was calmed by a touch, or on another page). */
    private fun showAgain() {
        if (terms.isEmpty() && phrase == null) { toast("אין מילים לסמן – חפשו בספר"); return }
        if (hits.isNotEmpty() && page !in hits) { nextHit(); return }
        phrasePage = if (phrase != null) page else phrasePage
        show()
    }

    private fun copyPage() {
        val p = path ?: return
        val b = Db.get().bookByPath(p)
        val t = b?.let { Db.get().pageText(it.id, page) }
        if (t.isNullOrBlank()) { toast("אין טקסט שמור לעמוד הזה"); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("page", "$t\n\n(${b.title}, עמוד $page)"))
        toast("הטקסט הועתק")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_LEFT -> { next(); return true }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> { prev(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderToken++
        exec.execute {
            try { renderer?.close() } catch (e: Exception) {}
            try { pfd?.close() } catch (e: Exception) {}
            try { doc?.close() } catch (e: Exception) {}
        }
        exec.shutdown()
    }
}

/** Shows one page; pinch to zoom, drag when zoomed, double tap to zoom in/out, tap to turn pages. */
@SuppressLint("ViewConstructor")
class PageView(c: Context) : View(c) {
    var onTap: ((Float) -> Unit)? = null
    private var bmp: Bitmap? = null
    private var rects: List<RectF> = emptyList()
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (Col.isEink) 0x40000000 else 0x66FFB300; style = Paint.Style.FILL }
    private val hlLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (Col.isEink) 0xFF000000.toInt() else 0xFFE65100.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (Col.isEink) 0xFF000000.toInt() else 0xFFE65100.toInt(); style = Paint.Style.FILL }
    /** 0..1 – the "look here" pulse after the words were found */
    private var pulse = 0f
    /** Strong marking, until the first touch. */
    private var emph = false

    /** The user touched the page: the marking stays, quieter. */
    fun calm() {
        if (!emph) return
        emph = false
        pulseAnim?.cancel(); pulse = 0f
        invalidate()
    }
    private var pulseAnim: android.animation.ValueAnimator? = null

    /** Words next to each other on the same line become one box, so a whole phrase is framed together. */
    private fun merge(r: List<RectF>): List<RectF> {
        val out = ArrayList<RectF>()
        for (x in r.sortedWith(compareBy({ it.centerY() }, { it.left }))) {
            val last = out.lastOrNull()
            val h = x.height()
            if (last != null && kotlin.math.abs(last.centerY() - x.centerY()) < h * 0.5f &&
                (x.left - last.right < h * 1.6f && last.left - x.right < h * 1.6f)) {
                last.union(x)
            } else out.add(RectF(x))
        }
        return out
    }

    fun setPage(b: Bitmap, r: List<RectF>) {
        val old = bmp
        bmp = b; rects = r
        zoom = 1f; tx = 0f; ty = 0f
        if (old != null && old !== b) old.recycle()
        invalidate()
    }

    fun setHighlights(r: List<RectF>) {
        rects = merge(r)
        // a phone screen: zoom in on the first place, so it is seen at once
        val b = bmp
        if (b != null && rects.isNotEmpty() && width < context.dp(600) && zoom <= 1.01f) {
            val f = rects[0]
            zoom = 1.8f
            val s = base() * zoom
            tx = width / 2f - f.centerX() * s
            ty = height / 3f - f.centerY() * s
            clamp()
        }
        // "it is here": strong until the user touches the page; on colour screens it keeps breathing
        emph = rects.isNotEmpty()
        pulseAnim?.cancel()
        pulseAnim = android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = if (Col.isEink) 900 else 1100
            repeatCount = if (Col.isEink) 2 else android.animation.ValueAnimator.INFINITE
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { pulse = 0f; invalidate() } })
            start()
        }
        invalidate()
    }
    fun zoomed() = zoom > 1.01f
    fun reset() { zoom = 1f; tx = 0f; ty = 0f; invalidate() }
    fun zoomTo(z: Float) { zoom = z; tx = -(width * (z - 1) / 2); ty = -(height * (z - 1) / 2); clamp(); invalidate() }

    private fun base(): Float {
        val b = bmp ?: return 1f
        return minOf(width / b.width.toFloat(), height / b.height.toFloat())
    }

    private fun clamp() {
        val b = bmp ?: return
        val s = base() * zoom
        val cw = b.width * s; val ch = b.height * s
        tx = if (cw <= width) (width - cw) / 2 else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) (height - ch) / 2 else ty.coerceIn(height - ch, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        clamp()
        val s = base() * zoom
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(s, s)
        canvas.drawBitmap(b, 0f, 0f, paint)
        val d = context.resources.displayMetrics.density
        hlLine.strokeWidth = ((if (emph) 4f else 2f) + 3f * pulse) * d / s
        hl.color = if (Col.isEink) (if (emph) 0x55000000 else 0x30000000) else (if (emph) 0x88FFB300.toInt() else 0x50FFB300)
        val grow = 6f * pulse * d / s
        val rad = 5f * d / s
        for (r in rects) {
            val rr = RectF(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow)
            canvas.drawRoundRect(rr, rad, rad, hl)
            canvas.drawRoundRect(rr, rad, rad, hlLine)
            // a marker in the margin of that line
            val cy = r.centerY(); val m = 7f * d / s
            val x = b.width - 4f * d / s
            val path = android.graphics.Path().apply { moveTo(x, cy - m); lineTo(x - m * 1.3f, cy); lineTo(x, cy + m); close() }
            canvas.drawPath(path, mark)
        }
        canvas.restore()
    }

    private val scaleDet = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            calm()
            val nz = (zoom * d.scaleFactor).coerceIn(1f, 5f)
            val f = nz / zoom
            tx = d.focusX - (d.focusX - tx) * f
            ty = d.focusY - (d.focusY - ty) * f
            zoom = nz
            invalidate()
            return true
        }
    })

    private val gest = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            calm()
            if (!zoomed()) onTap?.invoke(e.x / width.coerceAtLeast(1))
            else onTap?.invoke(0.5f)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed()) reset() else {
                val nz = 2.2f
                tx = e.x - (e.x - tx) * nz; ty = e.y - (e.y - ty) * nz; zoom = nz
                invalidate()
            }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            calm()
            if (!zoomed()) return false
            tx -= dx; ty -= dy; invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (zoomed() || e1 == null) return false
            val dx = e2.x - e1.x
            if (kotlin.math.abs(dx) > width * 0.2f && kotlin.math.abs(dx) > kotlin.math.abs(e2.y - e1.y)) {
                // Hebrew books: swipe right = next page
                onTap?.invoke(if (dx > 0) 0f else 1f)
                return true
            }
            return false
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDet.onTouchEvent(event)
        if (!scaleDet.isInProgress) gest.onTouchEvent(event)
        return true
    }
}
```

## `app/src/main/java/com/chdv/searchplus/Search.kt`

```kotlin
package com.chdv.searchplus

import android.content.Context

/** How the words of a query must appear on a page. */
enum class Match(val key: String, val title: String) {
    PHRASE("phrase", "ביטוי מדויק"),
    ALL("all", "כל המילים בעמוד"),
    NEAR("near", "מילים קרובות"),
    ANY("any", "אחת המילים לפחות"),
}

/** One page that matched. */
data class PageHit(val pageId: Long, val page: Int, val score: Float = 0f)

/** One book with all its matching pages. */
class BookHit(val book: Book, val pages: MutableList<PageHit>, var nameMatch: Boolean = false) {
    val category: List<String> by lazy { Library.categoryOf(book.dir) }
    var score: Float = 0f
}

class Results(
    val query: String,
    val terms: List<String>,
    val books: List<BookHit>,
    val semantic: Boolean,
    val truncated: Boolean,
    val error: String? = null,
) {
    val pageCount: Int get() = books.sumOf { it.pages.size }
}

/** Full-text search over the index. */
object Search {
    private const val MAX_ROWS = 60000

    /** Query words in index form; keeps a trailing * (prefix search). */
    fun terms(q: String): List<String> {
        val out = ArrayList<String>()
        for (raw in q.trim().split(Regex("\\s+"))) {
            val star = raw.endsWith("*")
            val ws = Heb.words(raw)
            ws.forEachIndexed { i, w -> out.add(if (star && i == ws.size - 1) "$w*" else w) }
        }
        return out
    }

    /** Builds an FTS4 MATCH expression (standard syntax: implicit AND, OR binds tighter, "phrases", NEAR/n). */
    fun buildMatch(terms: List<String>, match: Match, prefixes: Boolean): String {
        if (terms.isEmpty()) return ""
        fun variants(w: String): List<String> =
            if (!prefixes || w.endsWith("*") || w.any { it in 'a'..'z' }) listOf(w)
            else listOf(w) + Heb.PREFIXES.map { it + w }

        if (terms.size == 1) return variants(terms[0]).joinToString(" OR ")
        return when (match) {
            Match.PHRASE -> {
                val rest = terms.drop(1).joinToString(" ")
                variants(terms[0]).joinToString(" OR ") { "\"$it $rest\"" }
            }
            Match.ALL -> terms.joinToString(" ") { t -> variants(t).joinToString(" OR ") }
            Match.ANY -> terms.flatMap { variants(it) }.joinToString(" OR ")
            Match.NEAR -> terms.joinToString(" NEAR/12 ")
        }
    }

    fun run(q: String, match: Match, prefixes: Boolean, inFile: Long? = null): Results {
        val terms = terms(q)
        if (terms.isEmpty()) return Results(q, terms, emptyList(), false, false)
        val expr = buildMatch(terms, match, prefixes)
        val db = Db.get()
        val books = db.books()
        val byBook = LinkedHashMap<Long, BookHit>()
        var rows = 0
        try {
            val sql = "SELECT p.file, p.page, p.id FROM fts JOIN pages p ON p.id = fts.docid WHERE fts MATCH ?" +
                (if (inFile != null) " AND p.file = $inFile" else "") + " LIMIT $MAX_ROWS"
            db.readableDatabase.rawQuery(sql, arrayOf(expr)).use { c ->
                while (c.moveToNext()) {
                    rows++
                    val fid = c.getLong(0)
                    val b = books[fid] ?: continue
                    byBook.getOrPut(fid) { BookHit(b, ArrayList()) }.pages.add(PageHit(c.getLong(2), c.getInt(1)))
                }
            }
        } catch (e: Exception) {
            return Results(q, terms, emptyList(), false, false, "החיפוש לא הצליח: " + (e.message ?: ""))
        }
        // books whose name matches
        if (inFile == null) {
            val plain = terms.map { it.removeSuffix("*") }
            for (b in books.values) {
                val name = Heb.clean(b.title)
                if (plain.all { name.contains(it) }) {
                    val h = byBook.getOrPut(b.id) { BookHit(b, ArrayList()) }
                    h.nameMatch = true
                }
            }
        }
        byBook.values.forEach { h ->
            h.pages.sortBy { it.page }
            h.score = h.pages.size.toFloat() + if (h.nameMatch) 10000f else 0f
        }
        return Results(q, terms, byBook.values.toList(), false, rows >= MAX_ROWS)
    }

    fun runSemantic(c: Context, q: String, progress: (String) -> Unit = {}): Results {
        val db = Db.get()
        val books = db.books()
        val hits = try { Semantic.search(c, q, progress) } catch (e: Throwable) {
            Semantic.lastError = e.message ?: e.javaClass.simpleName
            return Results(q, terms(q), emptyList(), true, false, "חיפוש לפי משמעות נכשל: " + (e.message ?: e.javaClass.simpleName))
        }
        val byBook = LinkedHashMap<Long, BookHit>()
        for (h in hits) {
            val ref = db.pageRef(h.pageId) ?: continue
            val b = books[ref.first] ?: continue
            val bh = byBook.getOrPut(b.id) { BookHit(b, ArrayList()) }
            bh.pages.add(PageHit(h.pageId, ref.second, h.score))
            bh.score = maxOf(bh.score, h.score)
        }
        return Results(q, Semantic.keyWords(q), byBook.values.toList(), true, false)
    }

    /** Snippet with the matching words wrapped in \u0001 ... \u0002. */
    fun snippet(expr: String, pageId: Long): String? = try {
        Db.get().readableDatabase.rawQuery(
            "SELECT snippet(fts, '\u0001', '\u0002', '…', -1, 30) FROM fts WHERE fts MATCH ? AND docid = ?",
            arrayOf(expr, pageId.toString())
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null }

    /** For meaning search: the passage of the page that is closest to the question words, marked. */
    fun semanticSnippet(pageId: Long, terms: List<String>): String? {
        val body = Db.get().pageTextById(pageId) ?: return null
        val words = body.split(' ', '\n').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        var bestI = 0; var bestS = -1
        val win = 34
        for (i in 0 until maxOf(1, words.size - win) step 6) {
            var s = 0
            for (j in i until minOf(words.size, i + win)) if (terms.any { t -> words[j].contains(t.removeSuffix("*")) }) s++
            if (s > bestS) { bestS = s; bestI = i }
        }
        return (if (bestI > 0) "…" else "") + words.subList(bestI, minOf(words.size, bestI + win)).joinToString(" ") { w ->
            if (terms.any { t -> t.length > 1 && w.contains(t.removeSuffix("*")) }) "\u0001$w\u0002" else w
        } + "…"
    }
}
```

## `app/src/main/java/com/chdv/searchplus/Semantic.kt`

```kotlin
package com.chdv.searchplus

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import com.chdv.searchplus.core.E5Model
import com.chdv.searchplus.core.E5Tokenizer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Search by meaning. Uses the multilingual-e5-small model (it understands Hebrew) running on the
 * device with ONNX Runtime. Every page is cut into passages; every passage gets a vector of 384
 * numbers that represents its meaning. A question is turned into a vector the same way and the
 * passages with the closest meaning are returned. Nothing leaves the device.
 *
 * Two sources are combined: the prepared index (vectors.bin, built in the background) and, at
 * question time, the pages that contain the question's words, read and compared on the spot – so
 * meaning search gives answers even before the index is complete.
 */
object Semantic {
    private const val BASE = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/"
    const val DIM = 384
    private const val REC = 4 + 4 + DIM // pageId, scale, int8 vector
    private const val WORDS_PER_CHUNK = 90
    private const val MAX_CHUNKS = 4

    fun dir(c: Context) = File(c.filesDir, "e5")
    private fun modelFile(c: Context) = File(dir(c), "model_quantized.onnx")
    private fun tokFile(c: Context) = File(dir(c), "tokenizer.json")
    private fun vocabFile(c: Context) = File(dir(c), "vocab.bin")
    fun vectorsFile(c: Context) = File(c.filesDir, "vectors.bin")

    fun ready(c: Context) = modelFile(c).length() > 50_000_000 && vocabFile(c).length() > 1_000_000

    /** Last problem, shown in the settings (so a failure is never silent). */
    var lastError: String
        get() = Prefs.s("semError", "")
        set(v) = Prefs.put("semError", v.take(400))

    fun download(c: Context, progress: (String) -> Unit) {
        dir(c).mkdirs()
        if (vocabFile(c).length() < 1_000_000) {
            Net.download(BASE + "tokenizer.json", tokFile(c)) { progress("מוריד מילון… $it%") }
            progress("מכין את המילון…")
            E5Tokenizer.compile(tokFile(c), vocabFile(c))
            tokFile(c).delete()
        }
        if (modelFile(c).length() < 50_000_000) Net.download(BASE + "onnx/model_quantized.onnx", modelFile(c)) { progress("מוריד את מודל הבינה… $it%") }
        progress("בודק את המודל…")
        // a real test run: if this works, search by meaning works
        val v = embed(c, "query: בדיקה")
        if (v.size != DIM) throw IllegalStateException("המודל החזיר ${v.size} מספרים במקום $DIM")
        lastError = ""
    }

    fun deleteAll(c: Context) {
        close()
        dir(c).deleteRecursively()
        vectorsFile(c).delete()
        Db.get().resetEmbeddings()
    }

    // ---------- model ----------

    private var model: E5Model? = null

    @Synchronized
    private fun open(c: Context): E5Model {
        model?.let { return it }
        if (!vocabFile(c).exists() && tokFile(c).exists()) E5Tokenizer.compile(tokFile(c), vocabFile(c))
        val tok = E5Tokenizer.load(vocabFile(c))
        val threads = max(1, min(4, Runtime.getRuntime().availableProcessors()))
        return E5Model(OrtEnvironment.getEnvironment(), modelFile(c).absolutePath, tok, threads).also { model = it }
    }

    @Synchronized
    fun close() {
        try { model?.close() } catch (e: Exception) {}
        model = null
    }

    @Synchronized
    fun embed(c: Context, text: String, maxLen: Int = 192): FloatArray = open(c).embed(text, maxLen)

    private fun dot(a: FloatArray, b: FloatArray): Float { var d = 0f; for (i in a.indices) d += a[i] * b[i]; return d }

    // ---------- passages ----------

    /** Split the text of a page into passages of ~90 words. */
    fun chunks(body: String): List<String> {
        val words = body.split(' ', '\n').filter { it.isNotBlank() }
        if (words.size < 8) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size && out.size < MAX_CHUNKS) {
            val end = min(words.size, i + WORDS_PER_CHUNK)
            out.add(words.subList(i, end).joinToString(" "))
            if (end == words.size) break
            i = end - 10 // small overlap
        }
        return out
    }

    /** Index every book that is not in the meaning index yet. Limited to the folders chosen in settings. */
    fun indexPending(c: Context) {
        val db = Db.get()
        val only = Prefs.list("semanticRoots")
        val books = db.filesForEmbedding().filter { b -> only.isEmpty() || only.any { b.path.startsWith("$it/") } }
        if (books.isEmpty()) return
        Progress.phase = "בונה אינדקס לפי משמעות"
        Progress.total = books.size; Progress.done = 0
        Prefs.put("semBusy", System.currentTimeMillis())
        try {
            open(c)
        } catch (e: Throwable) {
            lastError = "המודל לא נטען: " + (e.message ?: e.javaClass.simpleName)
            return
        }
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(vectorsFile(c), true), 1 shl 16)).use { out ->
                for (b in books) {
                    if (Progress.cancel) return
                    Progress.waitWhilePaused()
                    Progress.current = b.title
                    try {
                        val recs = ArrayList<Pair<Int, ByteArray>>()
                        val scales = ArrayList<Float>()
                        for ((pageId, body) in db.pagesOf(b.id)) {
                            if (Progress.cancel) return
                            Progress.waitWhilePaused()
                            for (ch in chunks(body)) {
                                val v = embed(c, "passage: $ch")
                                var mx = 0f
                                for (x in v) mx = max(mx, kotlin.math.abs(x))
                                val sc = if (mx == 0f) 1f else mx / 127f
                                recs.add(pageId.toInt() to ByteArray(DIM) { (v[it] / sc).roundToInt().coerceIn(-127, 127).toByte() })
                                scales.add(sc)
                            }
                        }
                        // the whole book is written at once, so a stop in the middle never leaves half a book
                        for (k in recs.indices) { out.writeInt(recs[k].first); out.writeFloat(scales[k]); out.write(recs[k].second) }
                        out.flush()
                        db.markEmbedded(b.id)
                    } catch (e: OutOfMemoryError) {
                        System.gc(); db.markEmbedded(b.id, 2)
                        lastError = "הספר ${b.title} גדול מדי לזיכרון – דילגתי עליו"
                    } catch (e: Throwable) {
                        db.markEmbedded(b.id, 2)
                        lastError = "${b.title}: " + (e.message ?: e.javaClass.simpleName)
                    }
                    Progress.done++
                }
            }
        } finally {
            close()
            Prefs.put("semBusy", 0L)
        }
    }

    data class Hit(val pageId: Long, val score: Float)

    /** The pages whose meaning is closest to the question, from the prepared index. */
    fun searchIndex(c: Context, q: FloatArray, k: Int = 300): List<Hit> {
        val f = vectorsFile(c)
        if (!f.exists() || f.length() < REC) return emptyList()
        val best = HashMap<Int, Float>() // best score per page
        val heap = PriorityQueue<Pair<Int, Float>>(k + 1, compareBy { it.second })
        val buf = ByteArray(DIM)
        DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 18)).use { i ->
            val n = f.length() / REC
            for (r in 0 until n) {
                val pid = i.readInt()
                val sc = i.readFloat()
                i.readFully(buf)
                var d = 0f
                for (x in 0 until DIM) d += q[x] * buf[x]
                d *= sc
                val prev = best[pid]
                if (prev != null && prev >= d) continue
                if (heap.size >= k && d <= heap.peek()!!.second) continue
                best[pid] = d
                heap.add(pid to d)
                if (heap.size > k) heap.poll()
            }
        }
        val m = HashMap<Int, Float>()
        heap.forEach { (p, s) -> if ((m[p] ?: -9f) < s) m[p] = s }
        return m.entries.sortedByDescending { it.value }.map { Hit(it.key.toLong(), it.value) }
    }

    /** Small words that say nothing about the subject of a question. */
    private val STOP = setOf("מה", "מהו", "מהי", "איך", "כיצד", "למה", "מדוע", "מתי", "מי", "האם", "איפה", "היכן", "של", "על", "את", "אל",
        "עם", "הוא", "היא", "הם", "זה", "זו", "זאת", "אם", "כי", "יש", "אין", "לא", "כל", "גם", "או", "אבל", "רק", "כמו", "אחרי", "לפני",
        "צריך", "צריכים", "אפשר", "מותר", "אסור", "דין", "הדין", "לגבי", "בענין", "בעניין", "אומרים", "אומר", "כתוב", "נאמר", "עושים")

    fun keyWords(q: String): List<String> = Heb.words(q).filter { it.length >= 2 && it !in STOP }

    /**
     * Meaning search = prepared index + on-the-spot reading of the pages that contain the question's
     * words. [progress] reports what is happening (it can take a few seconds on an E-ink device).
     */
    fun search(c: Context, query: String, progress: (String) -> Unit = {}): List<Hit> {
        val q = embed(c, "query: $query", 96)
        val scores = HashMap<Long, Float>()
        searchIndex(c, q).forEach { scores[it.pageId] = it.score }

        // pages with the most question words, compared now
        val kw = keyWords(query)
        if (kw.isNotEmpty()) {
            progress("קורא את העמודים שמזכירים את המילים…")
            val expr = Search.buildMatch(kw, Match.ANY, true)
            val cand = ArrayList<Pair<Long, Int>>()
            try {
                Db.get().readableDatabase.rawQuery("SELECT docid, body FROM fts WHERE fts MATCH ? LIMIT 400", arrayOf(expr)).use { cur ->
                    while (cur.moveToNext()) {
                        val body = cur.getString(1)
                        val n = kw.count { w -> body.contains(w) }
                        cand.add(cur.getLong(0) to n)
                    }
                }
            } catch (e: Exception) {}
            val top = cand.sortedByDescending { it.second }.take(16).filter { it.first !in scores }
            top.forEachIndexed { i, (pid, _) ->
                progress("משווה משמעות ${i + 1}/${top.size}…")
                val body = Db.get().pageTextById(pid) ?: return@forEachIndexed
                val chunk = chunks(body).maxByOrNull { ch -> kw.count { ch.contains(it) } } ?: return@forEachIndexed
                scores[pid] = dot(q, embed(c, "passage: $chunk"))
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(300).map { Hit(it.key, it.value) }
    }

    private var qText = ""
    private var qVec: FloatArray? = null

    @Synchronized
    private fun queryVec(c: Context, q: String): FloatArray {
        if (q == qText) qVec?.let { return it }
        return embed(c, "query: $q", 96).also { qText = q; qVec = it }
    }

    /** Sentences of a page (or ~30-word pieces where the text has no punctuation). */
    fun sentences(body: String): List<String> {
        val out = ArrayList<String>()
        for (part in body.replace('\n', ' ').split(Regex("(?<=[.:;?!])\\s+"))) {
            val ws = part.split(' ').filter { it.isNotBlank() }
            if (ws.size < 5) continue
            var i = 0
            while (i < ws.size) { out.add(ws.subList(i, minOf(ws.size, i + 35)).joinToString(" ")); i += 30 }
        }
        return out
    }

    /**
     * The sentence of a page whose meaning is closest to the question – shown in the results and
     * marked in the reader, instead of scattered words.
     */
    fun sentenceFor(c: Context, question: String, pageId: Long): String? {
        val body = Db.get().pageTextById(pageId) ?: return null
        val kw = keyWords(question)
        val cand = sentences(body).sortedByDescending { s -> kw.count { s.contains(it) } }.take(6)
        if (cand.isEmpty()) return null
        val q = queryVec(c, question)
        return cand.maxByOrNull { dot(q, embed(c, "passage: $it", 80)) }
    }

    /**
     * The deep understanding model (bge-m3, ~570MB, optional): reads the candidate sentences in their
     * context and picks the one that really answers. Slower, much more accurate; used only for the
     * final choice of the answer, so it stays fast enough on the device.
     */
    object Deep {
        private const val REPO = "https://huggingface.co/Xenova/bge-m3/resolve/main/"
        fun dir(c: Context): File = File(VoiceModels.dir(c, "x").parentFile, "bge-m3")
        private fun model(c: Context) = File(dir(c), "model_quantized.onnx")
        private fun tok(c: Context) = File(dir(c), "tokenizer.json")
        private fun vocab(c: Context) = File(dir(c), "vocab.bin")
        fun ready(c: Context) = model(c).length() > 300_000_000 && vocab(c).length() > 1_000_000
        fun on(c: Context) = ready(c) && Prefs.b("deep", true)

        fun download(c: Context, progress: (String) -> Unit) {
            dir(c).mkdirs()
            if (vocab(c).length() < 1_000_000) {
                Net.download(REPO + "tokenizer.json", tok(c)) { progress("מוריד מילון… $it%") }
                E5Tokenizer.compile(tok(c), vocab(c)); tok(c).delete()
            }
            if (model(c).length() < 300_000_000) Net.download(REPO + "onnx/model_quantized.onnx", model(c)) { progress("מוריד את מודל ההבנה… $it%") }
            progress("בודק את המודל…")
            close()
            use(c) { it.embed("בדיקה") }
            close()
        }

        fun delete(c: Context) { close(); dir(c).deleteRecursively() }

        private var m: E5Model? = null

        @Synchronized
        fun <T> use(c: Context, f: (E5Model) -> T): T {
            val mm = m ?: run {
                Semantic.close(); VoiceModels.Engine.release(); System.gc()
                if (VoiceModels.freeMemoryMb(c) < 700) throw IllegalStateException("אין מספיק זיכרון פנוי למודל ההבנה")
                E5Model(OrtEnvironment.getEnvironment(), model(c).absolutePath, E5Tokenizer.load(vocab(c)), max(1, min(4, Runtime.getRuntime().availableProcessors())), cls = true, lowMemory = true)
            }.also { m = it }
            return f(mm)
        }

        @Synchronized
        fun close() { try { m?.close() } catch (e: Exception) {}; m = null }

        /** Best of the sentences for the question, with its score (cosine). */
        fun best(c: Context, question: String, sentences: List<String>): Pair<String, Float>? = use(c) { mm ->
            val q = mm.embed(question, 96)
            sentences.map { sn -> val v = mm.embed(sn, 128); sn to q.indices.fold(0f) { acc, i -> acc + q[i] * v[i] } }.maxByOrNull { it.second }
        }
    }

    fun vectorCount(c: Context): Long = vectorsFile(c).length() / REC

    class Answer(val text: String, val pageId: Long, val score: Float)

    /** Extractive answer: the sentence (from the closest pages) whose meaning is closest to the question. */
    fun answer(c: Context, question: String, pageIds: List<Long>): Answer? {
        if (pageIds.isEmpty()) return null
        val q = embed(c, "query: $question", 96)
        val kw = keyWords(question)
        val db = Db.get()
        val sents = ArrayList<Pair<String, Long>>()
        for (pid in pageIds) {
            val body = db.pageTextById(pid) ?: continue
            for (part in body.replace('\n', ' ').split(Regex("(?<=[.:;?!])\\s+"))) {
                val ws = part.split(' ').filter { it.isNotBlank() }
                if (ws.size < 5) continue
                var i = 0
                while (i < ws.size) { sents.add(ws.subList(i, minOf(ws.size, i + 35)).joinToString(" ") to pid); i += 30 }
            }
        }
        // compare only the sentences most likely to answer (each comparison costs time on the device)
        val pick = sents.sortedByDescending { (s, _) -> kw.count { s.contains(it) } }.take(20)
        if (Deep.on(c)) try {
            // the deep model reads each candidate sentence together with its neighbour, for context
            val withCtx = pick.map { (sn, _) -> sn }
            val r = Deep.best(c, question, withCtx)
            Deep.close()
            if (r != null) {
                val pid = pick.first { it.first == r.first }.second
                return Answer(r.first, pid, r.second).takeIf { it.score > 0.45f }
            }
        } catch (e: Throwable) { Deep.close(); lastError = "מודל ההבנה: " + (e.message ?: e.javaClass.simpleName) }
        var best: Answer? = null
        for ((sn, pid) in pick) {
            val d = dot(q, embed(c, "passage: $sn", 80))
            if (best == null || d > best.score) best = Answer(sn, pid, d)
        }
        return best?.takeIf { it.score > 0.8f }
    }
}
```

## `app/src/main/java/com/chdv/searchplus/SettingsActivity.kt`

```kotlin
package com.chdv.searchplus

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class SettingsActivity : BaseActivity() {
    private lateinit var box: LinearLayout
    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = vbox().apply { setPadding(dp(14), dp(6), dp(14), dp(30)) }
        screen("הגדרות", ScrollView(this).apply { addView(box) })
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun section(t: String) = box.addView(hbox().apply {
        setPadding(dp(2), dp(20), dp(2), dp(8))
        addView(View(this@SettingsActivity).apply { background = Col.gradient(Col.ACC, Col.ACC2, dp(3).toFloat()) }, lp(dp(5), dp(20)))
        addView(label(t, 16.5f, Col.ACC, true).apply { setPadding(dp(10), 0, 0, 0) })
    })

    private fun item(title: String, sub: String = "", onClick: (() -> Unit)?): TextView {
        val v = vbox().apply {
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = pressBg(Col.CARD, Col.ACC_LIGHT, 14, dp(1), Col.LINE)
            if (!Col.isEink) elevation = dp(1).toFloat()
        }
        v.addView(label(title, 16f, Col.TEXT, true))
        val s = label(sub, 13.5f, Col.SUB)
        if (sub.isNotEmpty()) v.addView(s)
        if (onClick != null) v.setOnClickListener { onClick() }
        box.addView(v, lp(MATCH, WRAP).m(0, dp(3), 0, dp(3)))
        return s
    }

    private fun toggle(title: String, sub: String, key: String, def: Boolean, after: (Boolean) -> Unit = {}) {
        val on = Prefs.b(key, def)
        item((if (on) "☑ " else "☐ ") + title, sub) { Prefs.put(key, !on); after(!on); render() }
    }

    private fun render() {
        box.removeAllViews()

        section("מאגר הספרים")
        val roots = Library.roots()
        item("תיקיות הספרים", if (roots.isEmpty()) "עוד לא נבחרה תיקייה" else roots.joinToString("\n")) { rootsMenu() }
        val statsLabel = item("מצב הסריקה", "טוען…") { statsSheet() }
        bg.execute {
            val st = Db.get().stats()
            ui {
                statsLabel.text = "${st.books} ספרים · נסרקו ${st.done}" + (if (st.pending > 0) " · ממתינים ${st.pending}" else "") +
                    (if (st.errors > 0) " · שגיאות ${st.errors}" else "") + "\n${st.textPages} עמודים עם טקסט · ${st.emptyPages} עמודים סרוקים בלי טקסט" +
                    (if (Progress.running) "\n⟳ " + Progress.text() else "")
            }
        }
        item("סריקה עכשיו", "מחפש ספרים חדשים, שהשתנו או שנמחקו") { Indexer.start(this); toast("הסריקה התחילה") }
        if (Progress.running) item("עצירת הסריקה", "") { Progress.cancel = true; toast("עוצר…") }
        toggle("סריקה אוטומטית בפתיחת התוכנה", "בכל פתיחה בודק אם נוספו ספרים", "autoScan", true)
        item("סריקה מחדש של כל הספרים", "מוחק את האינדקס וקורא הכול מההתחלה") {
            Sheet.confirm(this, "סריקה מחדש של הכול", "האינדקס יימחק וכל הספרים ייקראו מחדש. זה יכול לקחת זמן רב.", "כן, לסרוק מחדש") {
                Progress.cancel = true
                bg.execute { Db.get().resetAll(); ui { Indexer.start(this) } }
            }
        }
        toggle("סדר טקסט לפי מיקום בעמוד", "לספרים שהטקסט שלהם יוצא מבולגן. משפיע על סריקה חדשה", "sortByPosition", false)

        section("סידור המאגר")
        item("ספרים כפולים", "מציאת אותו ספר שנשמר כמה פעמים – לפי התוכן, לא לפי השם") { startActivity(android.content.Intent(this, OrganizeActivity::class.java).putExtra("tab", 0)) }
        item("סידור חכם לתיקיות", "הצעה לאיזו תיקייה כל ספר שייך, לפי מה שכתוב בו") { startActivity(android.content.Intent(this, OrganizeActivity::class.java).putExtra("tab", 1)) }
        item("שמות חכמים", "שמות לקבצים ולתיקיות לפי דף השער והתוכן – עם אישור על כל שינוי") { startActivity(android.content.Intent(this, OrganizeActivity::class.java).putExtra("tab", 2)) }

        section("זיהוי טקסט בעמודים סרוקים (OCR)")
        val ocrReady = Ocr.ready(this)
        item(if (ocrReady) "רכיב ה-OCR העברי מותקן ✓" else "התקנת רכיב OCR עברי",
            if (ocrReady) "לחיצה להחלפה לגרסה המדויקת (איטית יותר) או לחזרה לגרסה המהירה"
            else "ספרים ישנים סרוקים (תמונה בלי טקסט) – התוכנה תזהה את האותיות ותוכל לחפש בהם") { ocrMenu() }
        toggle("זיהוי אוטומטי של כל העמודים הסרוקים", "רץ ברקע אחרי קריאת הספרים. במכשיר חלש זה איטי (כמה שניות לעמוד)", "ocrAuto", true)

        section("חיפוש קולי 🎤")
        val eng = Prefs.s("voiceEngine", "whisper")
        val sysOk = VoiceSearch.systemAvailable(this)
        item("מנוע זיהוי הדיבור", when (eng) {
            "system" -> "שירות הדיבור של המכשיר (צריך אינטרנט)"
            "whisper" -> "בתוך המכשיר – Whisper (בלי אינטרנט)"
            else -> "אוטומטי: " + if (sysOk && !Prefs.b("systemFailed", false)) "שירות המכשיר, ואם הוא לא עובד – בתוך המכשיר" else "בתוך המכשיר (לא נמצא שירות דיבור במכשיר)"
        }) {
            Sheet.choice(this, "איך לזהות את הדיבור?", listOf(
                "whisper" to "בתוך המכשיר – Whisper, בלי אינטרנט (מומלץ)",
                "auto" to "אוטומטי: שירות המכשיר כשיש אינטרנט, אחרת בתוך המכשיר",
                "system" to "שירות הדיבור של המכשיר" + if (sysOk) "" else " (לא נמצא במכשיר הזה)",
            ), eng) { Prefs.put("voiceEngine", it); Prefs.put("systemFailed", false); render() }
        }
        val wm = VoiceModels.spec()
        val wReady = VoiceModels.ready(this)
        item(if (wReady) "מודל הדיבור במכשיר: ${wm.title} ✓" else "הורדת מודל דיבור לעבודה בלי אינטרנט",
            if (wReady) "נשמר ב: ${VoiceModels.dir(this, wm.key).parent}\nלחיצה להחלפה או למחיקה"
            else "הורדה חד-פעמית. שלוש רמות: מהיר · מדויק · מדויק מאוד. נשמר במכשיר או בכרטיס – איפה שיש יותר מקום") {
            val items = ArrayList<Pair<String, () -> Unit>>()
            VoiceModels.SPECS.forEach { sp ->
                val have = VoiceModels.ready(this, sp.key)
                items.add((if (sp.key == wm.key && have) "● " else "○ ") + "${sp.title} – כ-${sp.sizeMb}MB" + (if (have) " (מותקן)" else "") + "\n${sp.note}" to {
                    Prefs.put("whisperModel", sp.key); VoiceModels.Engine.release()
                    if (!have) VoiceSearch.downloadNow(this, sp.key) { render() } else render()
                })
            }
            if (wReady) items.add("מחיקת מודל הדיבור (${wm.title})" to { VoiceModels.delete(this, wm.key); render() })
            Sheet.actions(this, "מודל זיהוי הדיבור", items)
        }
        toggle("לחפש מיד אחרי הדיבור", "כבוי = המיקרופון רק כותב את מה שאמרתם (תמלול), ואתם לוחצים חיפוש כשמוכן", "voiceSearchAuto", true)
        item("בדיקת החיפוש הקולי", "לחיצה, ודברו – תראו מה התוכנה שמעה" + Prefs.s("voiceLast", "").let { if (it.isEmpty()) "" else "\nבפעם האחרונה: $it" }) {
            VoiceSearch.start(this) { t -> Sheet.info(this, "זה מה ששמעתי", t + (com.chdv.searchplus.core.RefParser.parse(t)?.let { "\n\nהבנתי: " + it.label() } ?: "")) }
        }

        section("חיפוש לפי משמעות (בינה מלאכותית)")
        val semReady = Semantic.ready(this)
        item(if (semReady) "מודל המשמעות מותקן ✓" else "הורדת מודל המשמעות (כ-120MB)",
            if (semReady) "וקטורים באינדקס: ${Semantic.vectorCount(this)} · לחיצה לניהול" + (Semantic.lastError.takeIf { it.isNotEmpty() }?.let { "\n⚠ $it" } ?: "")
            else "מאפשר לכתוב שאלה או רעיון ולמצוא את המקומות שעוסקים בהם. עובד בתוך המכשיר, בלי אינטרנט") { semanticMenu() }
        if (semReady) item("🌙 בניית אינדקס המשמעות המלא (מומלץ בלילה)", "כל הספרים נקראים ומובנים פעם אחת. השאירו את המכשיר בטעינה עם התוכנה פתוחה או ברקע – בבוקר אפשר לשאול שאלות בלי אינטרנט" +
            (if (Progress.running) "\n⟳ " + Progress.text() else "")) {
            Prefs.put("semantic", true)
            Sheet.info(this, "בונים את אינדקס המשמעות", "הבנייה רצה ברקע ומציגה כמה זמן נשאר.\n\n• חברו את המכשיר לטעינה.\n• ב-BOOX: הגדרות › אפליקציות › ניהול אפליקציות › חיפוש פלוס – כבו \"הקפאה\" / אפשרו ריצה ברקע, כדי שהמכשיר לא יעצור את העבודה בלילה.\n• אפשר להמשיך לחפש בזמן הבנייה.")
            Indexer.start(this)
        }
        val dReady = Semantic.Deep.ready(this)
        item(if (dReady) "🧠 מודל ההבנה העמוק מותקן ✓" else "🧠 מודל הבנה עמוק – תשובות מדויקות יותר (כ-570MB)",
            if (dReady) "קורא את המשפטים בהקשר ובוחר את התשובה האמיתית · לחיצה לניהול"
            else "הבנה מתקדמת: מודל גדול (BGE-M3) שקורא את המשפטים בהקשר ובוחר את התשובה האמיתית. איטי יותר במכשירים חלשים") {
            if (!dReady) {
                val st = label("מתחיל…", 15f, Col.SUB)
                val d = Sheet.make(this, vbox().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); addView(label("מוריד את מודל ההבנה", 18f, Col.TEXT, true)); addView(st) })
                d.setCancelable(false); d.show()
                bg.execute {
                    try { Semantic.Deep.download(this) { t -> ui { st.text = t } }; ui { d.dismiss(); toast("הותקן ✓"); render() } }
                    catch (e: Throwable) { ui { d.dismiss(); Sheet.info(this, "ההורדה או הבדיקה נכשלו", (e.message ?: e.javaClass.simpleName) + "\n\nמה שכבר ירד נשמר – אפשר לנסות שוב.") } }
                }
            } else Sheet.actions(this, "מודל ההבנה העמוק", listOf(
                (if (Prefs.b("deep", true)) "כיבוי (להשתמש רק במודל הקל)" else "הפעלה") to { Prefs.put("deep", !Prefs.b("deep", true)); render() },
                "מחיקת המודל" to { Semantic.Deep.delete(this); render() },
            ))
        }
        toggle("בניית אינדקס משמעות ברקע", "כבוי = החיפוש לפי משמעות מבוטל ולא נבנה אינדקס", "semantic", false) { on ->
            if (on && Semantic.ready(this)) Indexer.start(this)
        }
        val semRoots = Prefs.list("semanticRoots")
        item("תיקיות לאינדקס המשמעות", if (semRoots.isEmpty()) "כל הספרים (מומלץ לבחור תיקיות חשובות – כך זה מהיר יותר)" else semRoots.joinToString("\n")) {
            Sheet.actions(this, "תיקיות לאינדקס המשמעות", listOf(
                "הוספת תיקייה" to { FolderPicker.pick(this) { d -> Prefs.putList("semanticRoots", (Prefs.list("semanticRoots") + d).distinct()); render() } },
                "כל הספרים" to { Prefs.put("semanticRoots", ""); render() },
            ))
        }

        section("פתיחת ספרים ותצוגה")
        val ow = Prefs.s("openWith", "internal")
        val rp = Prefs.s("readerPkg", "")
        item("באיזו אפליקציה לפתוח ספרים", if (ow == "internal") "הקורא המובנה – בעמוד הנכון עם סימון המילים"
            else (if (rp.isEmpty()) "אפליקציה אחרת – תישאלו פעם אחת באיזו" else Library.appName(this, rp)) + " · נפתח בעמוד, והמילים מועתקות לחיפוש") {
            val apps = Library.pdfApps(this)
            val opts = ArrayList<Pair<String, String>>()
            opts.add("internal" to "קורא מובנה – עמוד מדויק + סימון המילים (מומלץ)")
            apps.forEach { r -> opts.add("app:${r.activityInfo.packageName}/${r.activityInfo.name}" to r.loadLabel(packageManager).toString()) }
            val cur = if (ow == "internal") "internal" else "app:$rp/${Prefs.s("readerCls", "")}"
            Sheet.choice(this, "ברירת מחדל לפתיחת ספרים", opts, cur) { k ->
                Prefs.put("readerChosen", true)
                if (k == "internal") Prefs.put("openWith", "internal") else {
                    val (pkg, cls) = k.removePrefix("app:").split('/', limit = 2)
                    Prefs.put("openWith", "external"); Prefs.put("readerPkg", pkg); Prefs.put("readerCls", cls)
                }
                render()
            }
        }
        if (ow == "external") {
            toggle("לפתוח את העמוד שנמצא – מסומן", "האפליקציה (ReadEra וכו׳) נפתחת ישר על העמוד שנמצא (ועוד 4 אחריו), עם המילים מסומנות בצהוב. כבוי = הספר המלא מההתחלה", "excerpt", true)
            toggle("העתקת מילות החיפוש בפתיחה", "כדי להדביק אותן בחיפוש של הקורא", "copyTerms", true)
        }
        val th = Prefs.s("theme", "auto")
        item("🎨 ערכת נושא", (Col.THEMES.firstOrNull { it.first == th }?.second ?: "כחול") + if (th == "auto") (" – " + if (Col.isEink) "שחור-לבן (זוהה מסך E-ink)" else "כחול") else "") {
            Sheet.choice(this, "ערכת נושא", Col.THEMES, th) {
                Prefs.put("theme", it); recreate()
            }
        }
        val fs = Prefs.s("fontSize", "normal")
        item("גודל הכתב", mapOf("small" to "קטן", "normal" to "רגיל", "large" to "גדול", "xlarge" to "גדול מאוד")[fs] ?: "רגיל") {
            Sheet.choice(this, "גודל הכתב", listOf("small" to "קטן", "normal" to "רגיל", "large" to "גדול", "xlarge" to "גדול מאוד"), fs) {
                Prefs.put("fontSize", it); recreate()
            }
        }
        item("ניקוי היסטוריה", "חיפושים אחרונים וספרים שנפתחו לאחרונה") {
            Prefs.put("history", ""); Prefs.put("recent", ""); toast("נוקה")
        }

        section("עדכונים")
        toggle("בדיקת עדכונים אוטומטית", "פעם ביום, כשיש אינטרנט", "autoUpdate", true)
        item("בדיקת עדכונים עכשיו", "גרסה נוכחית: " + version()) { Updater.check(this, false) }

        section("אודות")
        item("חיפוש פלוס", "גרסה ${version()}\nפותח על ידי שניאור וילהלם\nמופץ בחינם לתועלת הרבים\n\nקוד פתוח: github.com/sw0555041250-code/Search-Plus-", null)
        box.addView(label("יחי אדוננו מורנו ורבנו מלך המשיח לעולם ועד", 16f, Col.ACC, true).apply {
            gravity = android.view.Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(dp(8), dp(22), dp(8), dp(10))
        })
    }

    private fun version() = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun rootsMenu() {
        val roots = Library.roots()
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("הוספת תיקייה" to { FolderPicker.pick(this) { d -> Library.setRoots(Library.roots() + d); render(); Indexer.start(this) } })
        roots.forEach { r -> items.add("הסרה: $r" to {
            Sheet.confirm(this, "הסרת תיקייה", "הספרים שבתיקייה $r יוסרו מהאינדקס (הקבצים עצמם לא יימחקו).", "הסרה") {
                Library.setRoots(Library.roots() - r); render(); Indexer.start(this)
            }
        }) }
        Sheet.actions(this, "תיקיות הספרים", items)
    }

    private fun statsSheet() {
        bg.execute {
            val st = Db.get().stats()
            val errs = Db.get().errors()
            ui {
                Sheet.info(this, "מצב הסריקה",
                    "ספרים: ${st.books}\nנסרקו: ${st.done}\nממתינים: ${st.pending}\nשגיאות: ${st.errors}\nעמודים: ${st.pages}\n" +
                        "עמודים עם טקסט: ${st.textPages}\nעמודים סרוקים שממתינים ל-OCR: ${st.emptyPages}\nספרים באינדקס המשמעות: ${st.embedded}" +
                        (if (Progress.running) "\n\nעכשיו: " + Progress.text() else "") +
                        (if (errs.isNotEmpty()) "\n\nקבצים שלא נקראו:\n" + errs.joinToString("\n") { "• ${it.first}" } else ""))
            }
        }
    }

    private fun ocrMenu() {
        fun dl(best: Boolean) {
            val s = label("מוריד…", 15f, Col.SUB)
            val d = Sheet.make(this, vbox().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); addView(s) })
            d.setCancelable(false); d.show()
            bg.execute {
                try {
                    Ocr.download(this, best) { p -> ui { s.text = "מוריד את רכיב ה-OCR… $p%" } }
                    ui { d.dismiss(); toast("הותקן ✓"); render(); Indexer.start(this) }
                } catch (e: Exception) {
                    ui { d.dismiss(); Sheet.info(this, "ההורדה נכשלה", "בדקו את החיבור לאינטרנט ונסו שוב.\n" + (e.message ?: "")) }
                }
            }
        }
        Sheet.actions(this, "רכיב OCR עברי", listOf(
            "גרסה מהירה (מומלץ ל-BOOX)" to { dl(false) },
            "גרסה מדויקת (איטית יותר, לסריקות קשות)" to { dl(true) },
            "הסרת הרכיב" to { Ocr.delete(this); render() },
        ))
    }

    private fun semanticMenu() {
        if (!Semantic.ready(this)) {
            Sheet.confirm(this, "הורדת מודל המשמעות",
                "יורדים כ-120MB (מומלץ ב-Wi‑Fi). אחר כך התוכנה בונה ברקע אינדקס משמעות לספרים.\n\nבמכשיר כמו BOOX Go 6 בניית האינדקס איטית (כשנייה לעמוד), ולכן כדאי לבחור תיקיות חשובות בהגדרה \"תיקיות לאינדקס המשמעות\".",
                "הורדה") {
                val s = label("מוריד…", 15f, Col.SUB)
                val d = Sheet.make(this, vbox().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); addView(s) })
                d.setCancelable(false); d.show()
                bg.execute {
                    try {
                        Semantic.download(this) { t -> ui { s.text = t } }
                        Prefs.put("semantic", true)
                        ui { d.dismiss(); toast("הותקן ✓"); render(); Indexer.start(this) }
                    } catch (e: Throwable) {
                        ui { d.dismiss(); Sheet.info(this, "ההורדה נכשלה", "בדקו את החיבור לאינטרנט ואת המקום הפנוי ונסו שוב.\n" + (e.message ?: "")) }
                    }
                }
            }
            return
        }
        Sheet.actions(this, "מודל המשמעות", listOf(
            "בניית האינדקס מחדש" to {
                Semantic.vectorsFile(this).delete(); Db.get().resetEmbeddings(); Indexer.start(this); render()
            },
            "מחיקת המודל והאינדקס (ביטול החיפוש לפי משמעות)" to {
                Sheet.confirm(this, "מחיקה", "המודל והאינדקס יימחקו. אפשר להוריד שוב בכל עת.", "מחיקה") {
                    Semantic.deleteAll(this); Prefs.put("semantic", false); render()
                }
            },
        ))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == VoiceSearch.REQ_MIC) VoiceSearch.onPermission(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    override fun onDestroy() { super.onDestroy(); bg.shutdown() }
}
```

## `app/src/main/java/com/chdv/searchplus/Text.kt`

```kotlin
package com.chdv.searchplus

import java.text.Normalizer

/**
 * Hebrew text repair and normalisation.
 *
 * Old PDFs often give back broken text: Hebrew stored in "visual" (reversed) order, or Hebrew letters
 * hidden behind old Windows-1255 / DOS-862 code pages so they come out as Latin gibberish
 * ("ùîòåï" instead of "שמעון"). [repairPage] tries every repair and keeps the one that looks most like
 * real Hebrew. [clean] turns text into the form that is stored in the search index.
 */
object Heb {
    private const val FINALS = "ךםןףץ"
    private val COMMON = hashSetOf(
        "של", "את", "על", "הוא", "זה", "לא", "כי", "אשר", "עם", "גם", "כל", "אם", "מה", "היא", "הנה",
        "וכו", "אל", "או", "יש", "אין", "רק", "כמו", "אבל", "זו", "היה", "הם", "בו", "לו", "כן", "עד",
        "מן", "שהוא", "ולא", "וזה", "דהנה", "והנה", "להיות", "שלא", "אלא", "בזה", "ע\"ה", "כמ\"ש", "וגם",
        "ה", "ב", "לכן", "אך", "הרי", "מפני", "כמבואר", "שם", "בין", "לפי", "וכן", "אחד", "אותו", "היינו"
    )

    /** Prefix letters that may be glued to the front of a Hebrew word. */
    val PREFIXES = listOf(
        "ו", "ה", "ב", "כ", "ל", "מ", "ש", "ד", "וה", "וב", "ול", "ומ", "וש", "וכ", "וד",
        "שה", "שב", "של", "שמ", "מה", "כש", "לה", "בה", "דה", "כה", "וכש", "ושה", "מש"
    )

    fun isHebLetter(c: Char) = c in 'א'..'ת'

    // ---------- encoding / direction repair ----------

    private const val CP437_HEB = "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ" // DOS-862 bytes 0x80..0x9A shown as CP437

    private fun fromCp1255Latin(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in 'à'..'ú' -> sb.append('א' + (c - 'à'))
                in 'À'..'Ò' -> {} // cp1255 nikud bytes
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun fromCp862Controls(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '\u0080'..'\u009A') 'א' + (c - '\u0080') else c)
        return sb.toString()
    }

    private fun fromCp862Cp437(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val i = CP437_HEB.indexOf(c)
            sb.append(if (i >= 0) 'א' + i else c)
        }
        return sb.toString()
    }

    private fun mirror(c: Char): Char = when (c) {
        '(' -> ')'; ')' -> '('; '[' -> ']'; ']' -> '['; '{' -> '}'; '}' -> '{'; '<' -> '>'; '>' -> '<'
        else -> c
    }

    /** Reverse every line (fixes text stored in visual order). */
    fun reverseLines(s: String): String = s.split('\n').joinToString("\n") { line ->
        val sb = StringBuilder(line.length)
        for (i in line.length - 1 downTo 0) sb.append(mirror(line[i]))
        sb.toString()
    }

    /** How much does this look like correct, logically-ordered Hebrew? */
    fun score(s: String): Double {
        var score = 0.0
        var heb = 0
        val word = StringBuilder()
        fun flush() {
            if (word.isEmpty()) return
            val w = word.toString()
            if (w in COMMON) score += 3.0
            for (i in w.indices) {
                if (FINALS.indexOf(w[i]) >= 0) {
                    if (i == w.length - 1) score += 1.0 else score -= 2.0
                }
            }
            word.setLength(0)
        }
        for (c in s) {
            if (isHebLetter(c) || c == '"') {
                if (c != '"') heb++
                word.append(c)
            } else flush()
        }
        flush()
        return score + heb * 0.02
    }

    data class Repair(val text: String, val method: String)

    /** Pick the most Hebrew-looking interpretation of the raw text of one page. */
    fun repairPage(raw: String): Repair {
        val cands = ArrayList<Pair<String, String>>()
        cands.add(raw to "plain")
        var latin = 0; var ctrl = 0; var cp437 = 0
        for (c in raw) {
            if (c in 'à'..'ú') latin++
            if (c in '\u0080'..'\u009A') ctrl++
            if (CP437_HEB.indexOf(c) >= 0) cp437++
        }
        val n = raw.length.coerceAtLeast(1)
        if (latin * 20 > n) cands.add(fromCp1255Latin(raw) to "cp1255")
        if (ctrl * 20 > n) cands.add(fromCp862Controls(raw) to "cp862")
        if (cp437 * 20 > n) cands.add(fromCp862Cp437(raw) to "cp862b")
        var best = raw; var bestM = "plain"; var bestS = Double.NEGATIVE_INFINITY
        for ((t, m) in cands) {
            val s1 = score(t)
            if (s1 > bestS) { bestS = s1; best = t; bestM = m }
            val r = reverseLines(t)
            val s2 = score(r)
            if (s2 > bestS + 0.5) { bestS = s2; best = r; bestM = "$m+rev" }
        }
        return Repair(best, bestM)
    }

    /** Apply a repair chosen by [repairPage] (for the whole page) to a piece of the same page. */
    fun applyMethod(s: String, method: String): String {
        val base = method.substringBefore('+')
        var t = when (base) {
            "cp1255" -> fromCp1255Latin(s)
            "cp862" -> fromCp862Controls(s)
            "cp862b" -> fromCp862Cp437(s)
            else -> s
        }
        if (method.endsWith("+rev")) t = reverseLines(t)
        return t
    }

    // ---------- normalisation ----------

    private fun needsNfkd(s: String): Boolean {
        for (c in s) if (c.code >= 0xFB1D && c.code <= 0xFB4F || c.code in 0x00C0..0x024F || c.code in 0xFB00..0xFB06) return true
        return false
    }

    /**
     * The form stored in the index: no nikud / cantillation, Hebrew punctuation turned into ASCII
     * punctuation (which the index treats as word breaks), lower-case latin, single spaces.
     */
    fun clean(input: String): String {
        val s = if (needsNfkd(input)) Normalizer.normalize(input, Normalizer.Form.NFKD) else input
        val sb = StringBuilder(s.length)
        var lastSpace = true
        fun sp() { if (!lastSpace) { sb.append(' '); lastSpace = true } }
        for (c in s) {
            when {
                c == '\n' || c == '\r' -> {
                    while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
                    if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
                    lastSpace = true
                }
                c == '־' || c == '‐' || c == '‑' || c == '–' || c == '—' -> { sb.append('-'); lastSpace = false }
                c == '׃' -> { sb.append(':'); lastSpace = false }
                c == '׳' || c == '‘' || c == '’' || c == '‚' || c == '`' || c == '´' -> { sb.append('\''); lastSpace = false }
                c == '״' || c == '“' || c == '”' || c == '„' -> { sb.append('"'); lastSpace = false }
                c in '֑'..'ׇ' -> {} // nikud, cantillation marks
                c in '​'..'‏' || c in '‪'..'‮' || c in '⁦'..'⁩' || c == '﻿' -> {}
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}
                c.isLetterOrDigit() -> { sb.append(c.lowercaseChar()); lastSpace = false }
                c.isWhitespace() || c.code < 32 -> sp()
                c.code < 128 -> { sb.append(c); lastSpace = false }
                else -> sp()
            }
        }
        return sb.toString().trim()
    }

    /** Words (letters/digits only) of a piece of text, in the same form as the index. */
    fun words(s: String): List<String> {
        val out = ArrayList<String>()
        val w = StringBuilder()
        for (c in clean(s)) {
            if (c.isLetterOrDigit() || c.code >= 128) w.append(c)
            else if (w.isNotEmpty()) { out.add(w.toString()); w.setLength(0) }
        }
        if (w.isNotEmpty()) out.add(w.toString())
        return out
    }

    /** Count of real letters in a page; used to decide whether a page has a usable text layer. */
    fun letterCount(s: String): Int {
        var n = 0
        for (c in s) if (isHebLetter(c) || c in 'a'..'z' || c in 'A'..'Z') n++
        return n
    }

    /** Does extracted text look like garbage (wrong font encoding)? */
    fun looksBroken(s: String): Boolean {
        var letters = 0; var good = 0
        for (c in s) {
            if (c.isLetter()) {
                letters++
                if (isHebLetter(c) || c in 'a'..'z' || c in 'A'..'Z') good++
            }
        }
        return letters >= 40 && good * 2 < letters
    }
}
```

## `app/src/main/java/com/chdv/searchplus/Topics.kt`

```kotlin
package com.chdv.searchplus

/**
 * "What shall we look into today?" – topics to search, chosen for the day (and the Hebrew month),
 * and offered only if they really appear in the user's books, with how many places they appear in.
 */
object Topics {
    private val GENERAL = listOf(
        "אהבת ישראל", "ביטחון", "שמחה", "תשובה", "תפילה", "צדקה", "אמונה", "השגחה פרטית", "מסירות נפש", "ביאת המשיח",
        "גאולה", "לימוד התורה", "חינוך", "שלום בית", "ענווה", "זריזות", "הפצת המעיינות", "אחדות", "בירור הניצוצות",
        "דירה בתחתונים", "יראת שמים", "קבלת עול", "שבת קודש", "תפילין", "ארץ ישראל", "בית המקדש", "נשמה", "אור וחושך",
        "יעקב בורח מעשו", "פורס מפה ומקדש", "ויצא יעקב מבאר שבע", "סולם מוצב ארצה", "לך לך מארצך", "עקידת יצחק",
        "קריעת ים סוף", "מתן תורה", "אברהם אבינו", "משה רבינו", "דוד המלך", "רבי שמעון בר יוחאי", "הבעל שם טוב",
        "יוסף ואחיו", "שבירת הלוחות", "ברכת המזון", "קריאת שמע", "נטילת ידיים", "הדלקת נרות", "ברכות הנהנין",
        "אהבה ויראה", "מוחין דגדלות", "ביטול היש", "עבודה שבלב", "חסד ורחמים", "מחשבה דיבור ומעשה",
    )

    /** By Hebrew month (ICU: 0 = Tishrei … 12 = Elul; 5/6 = Adar). */
    private val MONTH = mapOf(
        0 to listOf("ראש השנה", "יום הכיפורים", "סוכה", "שמחת תורה", "ארבעת המינים"),
        1 to listOf("נח והמבול", "אברהם אבינו", "מבול"),
        2 to listOf("י״ט כסלו", "חנוכה", "נר חנוכה", "יעקב אבינו", "מסירות נפש"),
        3 to listOf("עשרה בטבת", "יוסף הצדיק", "ירידת מצרים"),
        4 to listOf("ט״ו בשבט", "יו״ד שבט", "יציאת מצרים", "שירת הים"),
        5 to listOf("פורים", "מגילת אסתר", "משלוח מנות", "מרדכי ואסתר"),
        6 to listOf("פורים", "מגילת אסתר", "משלוח מנות", "מרדכי ואסתר"),
        7 to listOf("פסח", "יציאת מצרים", "חמץ ומצה", "הגדה של פסח"),
        8 to listOf("ספירת העומר", "ל״ג בעומר", "רבי שמעון בר יוחאי"),
        9 to listOf("מתן תורה", "שבועות", "קבלת התורה"),
        10 to listOf("י״ב תמוז", "גאולה", "שבעה עשר בתמוז"),
        11 to listOf("תשעה באב", "ט״ו באב", "נחמה", "בית המקדש"),
        12 to listOf("חודש אלול", "תשובה", "המלך בשדה", "ראש השנה"),
    )

    class Topic(val text: String, val places: Int)

    private fun count(t: String): Int = try {
        val expr = Search.buildMatch(Search.terms(t), Match.ALL, true)
        Db.get().readableDatabase.rawQuery("SELECT count(*) FROM (SELECT docid FROM fts WHERE fts MATCH ? LIMIT 999)", arrayOf(expr))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    /** Today's suggestions (cached for the day). Runs in the background. */
    fun today(n: Int = 6): List<Topic> {
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        if (Prefs.s("topicsDay", "") == day) {
            val l = Prefs.list("topics").mapNotNull { r -> r.split('|').takeIf { it.size == 2 }?.let { Topic(it[0], it[1].toIntOrNull() ?: 0) } }
            if (l.isNotEmpty()) return l
        }
        val month = try { android.icu.util.HebrewCalendar().get(android.icu.util.Calendar.MONTH) } catch (e: Throwable) { -1 }
        val rnd = java.util.Random(day.toLong())
        val pool = (MONTH[month].orEmpty().shuffled(rnd).take(3) + GENERAL.shuffled(rnd)).distinct()
        val out = ArrayList<Topic>()
        for (t in pool) {
            if (out.size >= n) break
            val c = count(t)
            if (c > 0) out.add(Topic(t, c))
        }
        if (out.isNotEmpty()) { Prefs.put("topicsDay", day); Prefs.putList("topics", out.map { "${it.text}|${it.places}" }) }
        return out
    }
}
```

## `app/src/main/java/com/chdv/searchplus/Ui.kt`

```kotlin
package com.chdv.searchplus

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        Prefs.init(this)
        // if the app ever closes because of an error, keep the details and show them next time
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "crash.txt").writeText("${java.util.Date()}  thread=${t.name}\n$sw".take(8000))
            } catch (x: Throwable) {}
            // a crash in a background thread (scan, AI) must not close the app
            if (t !== android.os.Looper.getMainLooper().thread) {
                Progress.running = false
                return@setDefaultUncaughtExceptionHandler
            }
            prev?.uncaughtException(t, e)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // the system is short of memory: free the AI models (they load again when needed)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Thread { try { Semantic.close() } catch (e: Throwable) {}; try { if (!Progress.paused) VoiceModels.Engine.release() } catch (e: Throwable) {} }.start()
        }
    }

    companion object {
        lateinit var ctx: App
        val main = Handler(Looper.getMainLooper())
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("searchplus", Context.MODE_PRIVATE) }
    fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    fun s(k: String, d: String) = sp.getString(k, d) ?: d
    fun i(k: String, d: Int) = sp.getInt(k, d)
    fun l(k: String, d: Long) = sp.getLong(k, d)
    fun has(k: String) = sp.contains(k)
    fun put(k: String, v: Any?) {
        val e = sp.edit()
        when (v) {
            null -> e.remove(k)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            else -> e.putString(k, v.toString())
        }
        e.apply()
    }
    fun list(k: String): MutableList<String> = s(k, "").split('\n').filter { it.isNotBlank() }.toMutableList()
    fun putList(k: String, l: List<String>) = put(k, l.joinToString("\n"))
}

/** Colours: calm, high contrast, works well on E-ink too. */
/**
 * Colours. Two looks: calm high-contrast for E-ink readers (BOOX), and a livelier colourful one for
 * phones and tablets. Chosen automatically by the device, changeable in the settings.
 */
object Col {
    /** Themes: four colourful ones and black-and-white. "auto" = black-and-white on E-ink readers, blue elsewhere. */
    val THEMES = listOf("auto" to "אוטומטי לפי המכשיר", "blue" to "🔵 כחול", "green" to "🟢 ירוק", "purple" to "🟣 סגול", "warm" to "🟠 חם (כתום-אדום)", "eink" to "⚫ שחור-לבן, ניגודיות גבוהה")
    private val theme: String get() = Prefs.s("theme", "auto").let { if (it == "color") "blue" else it }
    val isEink: Boolean get() = when (theme) {
        "eink" -> true
        "blue", "green", "purple", "warm" -> false
        else -> android.os.Build.MANUFACTURER.equals("ONYX", true) || android.os.Build.BRAND.equals("ONYX", true) || android.os.Build.MODEL.contains("BOOX", true)
    }
    private val c get() = !isEink
    val BG: Int get() = if (c) 0xFFF7F8FC.toInt() else 0xFFFFFFFF.toInt()
    val TEXT: Int get() = 0xFF111318.toInt()
    val SUB: Int get() = if (c) 0xFF5B6070.toInt() else 0xFF5A5A5A.toInt()
    val LINE: Int get() = if (c) 0xFFE1E4EE.toInt() else 0xFFD6D6D6.toInt()
    val CARD: Int get() = if (c) 0xFFFFFFFF.toInt() else 0xFFF4F5F8.toInt()
    private val pal: Triple<Int, Int, Int> get() = when {
        !c -> Triple(0xFF1B3B6F.toInt(), 0xFF1B3B6F.toInt(), 0xFFE6ECF7.toInt())
        theme == "green" -> Triple(0xFF0B8A6F.toInt(), 0xFF2BB673.toInt(), 0xFFE2F6EE.toInt())
        theme == "purple" -> Triple(0xFF6A35D4.toInt(), 0xFFC04BD8.toInt(), 0xFFF0E8FF.toInt())
        theme == "warm" -> Triple(0xFFD4512B.toInt(), 0xFFF29A2E.toInt(), 0xFFFFEEE3.toInt())
        else -> Triple(0xFF3B4FD8.toInt(), 0xFF7B3FE4.toInt(), 0xFFE8EAFF.toInt())
    }
    val ACC: Int get() = pal.first
    val ACC2: Int get() = pal.second
    val ACC_LIGHT: Int get() = pal.third
    val GOLD = 0xFFFFD65A.toInt()
    val HL = 0x77FFC800
    val RED = 0xFFB00020.toInt()
    /** Colours of the feature tiles (voice, meaning, words, books, organize, settings). */
    val TILES: List<Pair<Int, Int>> get() = if (c) listOf(
        0xFF3B4FD8.toInt() to 0xFF6F7CFF.toInt(),
        0xFF7B3FE4.toInt() to 0xFFB06BFF.toInt(),
        0xFF0E9F8E.toInt() to 0xFF35CDB4.toInt(),
        0xFFE0712C.toInt() to 0xFFFFA65C.toInt(),
        0xFFD6336C.toInt() to 0xFFFF6F9B.toInt(),
        0xFF475066.toInt() to 0xFF7A8499.toInt(),
    ) else List(6) { 0xFF1B3B6F.toInt() to 0xFF1B3B6F.toInt() }

    fun gradient(a: Int, b: Int, radius: Float): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { cornerRadius = radius }
}

fun scale(): Float = when (Prefs.s("fontSize", "normal")) {
    "small" -> 0.9f; "large" -> 1.15f; "xlarge" -> 1.3f; else -> 1f
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
fun ui(r: () -> Unit) = App.main.post(r)

fun roundBg(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun Context.pressBg(color: Int, pressed: Int, radiusDp: Int, stroke: Int = 0, strokeColor: Int = 0): Drawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), roundBg(pressed, dp(radiusDp).toFloat(), stroke, strokeColor))
        addState(intArrayOf(), roundBg(color, dp(radiusDp).toFloat(), stroke, strokeColor))
    }

fun Context.label(s: CharSequence, size: Float, color: Int = Col.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size * scale())
        if (bold) typeface = Typeface.DEFAULT_BOLD
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
fun LinearLayout.LayoutParams.m(l: Int, t: Int, r: Int, b: Int) = apply { setMargins(l, t, r, b) }

/** Rounded pill button. */
fun Context.pill(text: String, filled: Boolean = true, onClick: () -> Unit): TextView = label(text, 15f, if (filled) Color.WHITE else Col.ACC, true).apply {
    gravity = Gravity.CENTER
    textAlignment = View.TEXT_ALIGNMENT_CENTER
    setPadding(dp(16), dp(10), dp(16), dp(10))
    background = if (filled) pressBg(Col.ACC, 0xFF0F2540.toInt(), 22) else pressBg(Col.BG, Col.ACC_LIGHT, 22, dp(1.5f), Col.ACC)
    isClickable = true
    setOnClickListener { onClick() }
}

/** Small selectable chip. */
fun Context.chip(text: String, selected: Boolean, onClick: () -> Unit): TextView = label(text, 14f, if (selected) Color.WHITE else Col.TEXT, selected).apply {
    gravity = Gravity.CENTER
    textAlignment = View.TEXT_ALIGNMENT_CENTER
    setPadding(dp(12), dp(7), dp(12), dp(7))
    background = if (selected) pressBg(Col.ACC, 0xFF0F2540.toInt(), 16) else pressBg(Col.CARD, Col.ACC_LIGHT, 16, dp(1), Col.LINE)
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(Col.LINE)
    layoutParams = lp(MATCH, maxOf(1, dp(0.7f)))
}

/** A simple wrapping row layout (chips flow to the next line). Lays out right-to-left. */
class FlowLayout(c: Context) : ViewGroup(c) {
    var gap = c.dp(6)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            measureChild(ch, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += ch.measuredWidth + gap
            rowH = maxOf(rowH, ch.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x = 0; var y = paddingTop; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            val left = if (rtl) (r - l) - paddingRight - x - ch.measuredWidth else paddingLeft + x
            ch.layout(left, y, left + ch.measuredWidth, y + ch.measuredHeight)
            x += ch.measuredWidth + gap
            rowH = maxOf(rowH, ch.measuredHeight)
        }
    }
}

open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    /** Standard screen: header with back + title + optional actions, and a body. */
    fun screen(title: String, body: View, actions: List<View> = emptyList(), back: Boolean = true): LinearLayout {
        val root = vbox().apply { setBackgroundColor(Col.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val head = hbox().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        if (back) head.addView(label("→", 24f, Col.ACC, true).apply {
            setPadding(dp(10), dp(2), dp(12), dp(2))
            setOnClickListener { finish() }
        })
        head.addView(label(title, 21f, Col.TEXT, true).apply { setSingleLine() }, lp(0, WRAP, 1f))
        actions.forEach { head.addView(it) }
        head.addView(Nav.button(this))
        root.addView(head, lp(MATCH, WRAP))
        root.addView(divider())
        root.addView(body, lp(MATCH, 0, 1f))
        setContentView(root)
        return root
    }

    fun headerButton(text: String, onClick: () -> Unit): TextView = label(text, 15f, Col.ACC, true).apply {
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = pressBg(Color.TRANSPARENT, Col.ACC_LIGHT, 14)
        setOnClickListener { onClick() }
    }
}

/** Dialog helpers (bottom sheets in the app's style). */
object Sheet {
    fun make(ctx: Context, content: View): Dialog {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val wrap = FrameLayout(ctx).apply { setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12)) }
        content.layoutDirection = View.LAYOUT_DIRECTION_RTL
        content.background = roundBg(Col.BG, ctx.dp(16).toFloat(), ctx.dp(1), Col.LINE)
        wrap.addView(content)
        d.setContentView(wrap)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        return d
    }

    private fun box(ctx: Context, title: String?): LinearLayout = ctx.vbox().apply {
        setPadding(ctx.dp(18), ctx.dp(16), ctx.dp(18), ctx.dp(14))
        if (title != null) addView(ctx.label(title, 19f, Col.TEXT, true), lp(MATCH, WRAP).m(0, 0, 0, ctx.dp(10)))
    }

    private fun scrollable(ctx: Context, inner: View, maxDp: Int = 420): View = ScrollView(ctx).apply {
        addView(inner)
        isVerticalScrollBarEnabled = true
        viewTreeObserver.addOnGlobalLayoutListener {
            val max = ctx.dp(maxDp)
            if (height > max) layoutParams = layoutParams.apply { height = max }
        }
    }

    fun info(ctx: Context, title: String, msg: String) {
        val b = box(ctx, title)
        b.addView(scrollable(ctx, ctx.label(msg, 15f, Col.SUB)), lp(MATCH, WRAP))
        lateinit var d: Dialog
        b.addView(ctx.pill("אישור") { d.dismiss() }, lp(MATCH, WRAP).m(0, ctx.dp(14), 0, 0))
        d = make(ctx, b); d.show()
    }

    fun confirm(ctx: Context, title: String, msg: String, ok: String, onOk: () -> Unit) {
        val b = box(ctx, title)
        b.addView(scrollable(ctx, ctx.label(msg, 15f, Col.SUB)), lp(MATCH, WRAP))
        lateinit var d: Dialog
        val row = ctx.hbox()
        row.addView(ctx.pill(ok) { d.dismiss(); onOk() }, lp(0, WRAP, 1f).m(ctx.dp(4), 0, ctx.dp(4), 0))
        row.addView(ctx.pill("ביטול", false) { d.dismiss() }, lp(0, WRAP, 1f).m(ctx.dp(4), 0, ctx.dp(4), 0))
        b.addView(row, lp(MATCH, WRAP).m(0, ctx.dp(14), 0, 0))
        d = make(ctx, b); d.show()
    }

    fun choice(ctx: Context, title: String, options: List<Pair<String, String>>, current: String?, onPick: (String) -> Unit) {
        val b = box(ctx, title)
        val list = ctx.vbox()
        lateinit var d: Dialog
        options.forEach { (key, text) ->
            val sel = key == current
            list.addView(ctx.label((if (sel) "● " else "○ ") + text, 16f, if (sel) Col.ACC else Col.TEXT, sel).apply {
                setPadding(ctx.dp(6), ctx.dp(12), ctx.dp(6), ctx.dp(12))
                background = ctx.pressBg(Color.TRANSPARENT, Col.ACC_LIGHT, 10)
                setOnClickListener { d.dismiss(); onPick(key) }
            }, lp(MATCH, WRAP))
        }
        b.addView(scrollable(ctx, list), lp(MATCH, WRAP))
        d = make(ctx, b); d.show()
    }

    fun actions(ctx: Context, title: String?, items: List<Pair<String, () -> Unit>>) =
        choice(ctx, title ?: "", items.mapIndexed { i, p -> i.toString() to p.first }, null) { k -> items[k.toInt()].second() }

    fun input(ctx: Context, title: String, initial: String, hint: String, numeric: Boolean = false, onOk: (String) -> Unit) {
        val b = box(ctx, title)
        val e = EditText(ctx).apply {
            setText(initial); this.hint = hint
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        b.addView(e, lp(MATCH, WRAP))
        lateinit var d: Dialog
        b.addView(ctx.pill("אישור") { d.dismiss(); onOk(e.text.toString()) }, lp(MATCH, WRAP).m(0, ctx.dp(12), 0, 0))
        d = make(ctx, b)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        e.requestFocus()
    }
}

fun Activity.safeStart(i: Intent): Boolean = try { startActivity(i); true } catch (e: Exception) { false }

fun human(size: Long): String = when {
    size >= 1L shl 30 -> String.format("%.1f GB", size / (1L shl 30).toDouble())
    size >= 1L shl 20 -> String.format("%.1f MB", size / (1L shl 20).toDouble())
    size >= 1L shl 10 -> String.format("%d KB", size shr 10)
    else -> "$size B"
}


/** Today's Hebrew date, e.g. "יום ג׳ · י״ג כסלו תשפ״ז" (after nightfall it is already the next day only in the calendar, not here). */
fun hebrewDate(): String = try {
    val cal = android.icu.util.HebrewCalendar()
    val day = cal.get(android.icu.util.Calendar.DAY_OF_MONTH)
    val m = cal.get(android.icu.util.Calendar.MONTH)
    val y = cal.get(android.icu.util.Calendar.YEAR)
    val leap = ((7 * y + 1) % 19) < 7
    val months = arrayOf("תשרי", "חשון", "כסלו", "טבת", "שבט", "אדר א׳", if (leap) "אדר ב׳" else "אדר", "ניסן", "אייר", "סיון", "תמוז", "אב", "אלול")
    fun g(n: Int): String { val h = com.chdv.searchplus.core.HebNum.toHeb(n); return if (h.length == 1) h + "׳" else h.dropLast(1) + "״" + h.last() }
    val dow = arrayOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת קודש")[java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1]
    (if (dow.startsWith("שבת")) dow else "יום $dow") + " · " + g(day) + " " + months[m] + " " + g(y % 1000)
} catch (e: Throwable) { "" }
```

## `app/src/main/java/com/chdv/searchplus/Updater.kt`

```kotlin
package com.chdv.searchplus

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Self update from GitHub Releases (github.com/sw0555041250-code/Search-Plus-). Every push to the
 * repository builds a new signed APK and publishes it as release v1.0.<build>. All builds are
 * signed with the same key, so an update installs over the existing app and keeps the index.
 */
object Updater {
    private const val API = "https://api.github.com/repos/sw0555041250-code/Search-Plus-/releases/latest"
    @Volatile private var busy = false

    private fun build(v: String): Int = v.trim().removePrefix("v").substringAfterLast('.').toIntOrNull() ?: 0

    private fun local(a: Activity) = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }

    fun dailyCheck(a: Activity) {
        if (!Prefs.b("autoUpdate", true)) return
        // after an update (or the first run of this version) check again right away; otherwise every 6 hours
        val v = local(a)
        if (Prefs.s("checkedForVersion", "") != v) { Prefs.put("checkedForVersion", v); Prefs.put("lastUpdateCheck", 0L) }
        if (System.currentTimeMillis() - Prefs.l("lastUpdateCheck", 0L) < 6L * 3600 * 1000) return
        Prefs.put("lastUpdateCheck", System.currentTimeMillis())
        check(a, true)
    }

    fun check(a: Activity, silent: Boolean) {
        if (busy) return
        busy = true
        if (!silent) a.toast("בודק אם יש עדכון…")
        Thread {
            try {
                val body = Net.text(API)
                val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
                val apk = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").find(body)?.groupValues?.get(1)
                val notes = Regex("\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
                    ?.replace("\\r\\n", "\n")?.replace("\\n", "\n")?.replace("\\\"", "\"")?.take(600) ?: ""
                val loc = local(a)
                ui {
                    busy = false
                    if (build(tag) > build(loc) && apk != null) {
                        Sheet.confirm(a, "יש עדכון לחיפוש פלוס",
                            "גרסה חדשה: ${tag.removePrefix("v")}\nהגרסה שלך: $loc\n\n$notes\n\nהאינדקס וההגדרות נשמרים.", "הורדה והתקנה") { download(a, apk) }
                    } else if (!silent) Sheet.info(a, "אין עדכון חדש", "הגרסה שלך ($loc) היא העדכנית ביותר.")
                }
            } catch (e: Exception) {
                ui {
                    busy = false
                    Prefs.put("lastUpdateCheck", 0L) // try again next time
                    if (!silent) Sheet.info(a, "לא ניתן לבדוק עדכונים", "ודאו שהמכשיר מחובר לאינטרנט ונסו שוב.\n(${e.javaClass.simpleName}: ${e.message ?: ""})")
                }
            }
        }.start()
    }

    private fun download(a: Activity, url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            Sheet.confirm(a, "צריך אישור חד-פעמי",
                "כדי להתקין עדכונים יש לאשר לחיפוש פלוס להתקין אפליקציות. אחרי האישור חזרו ולחצו שוב על \"בדיקת עדכונים\".", "פתיחת ההגדרה") {
                a.safeStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            }
            return
        }
        a.toast("מוריד את העדכון…")
        Thread {
            try {
                val out = File(File(a.cacheDir, "update").apply { mkdirs() }, "SearchPlus.apk")
                var last = -1
                Net.download(url, out) { p -> if (p / 25 != last / 25) { last = p; ui { a.toast("מוריד… $p%") } } }
                ui {
                    val i = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Library.uriFor(a, out), "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (!a.safeStart(i)) a.toast("לא ניתן לפתוח את מתקין האפליקציות")
                }
            } catch (e: Exception) {
                ui { Sheet.info(a, "ההורדה נכשלה", "בדקו את החיבור לאינטרנט ונסו שוב.") }
            }
        }.start()
    }
}
```

## `app/src/main/java/com/chdv/searchplus/Voice.kt`

```kotlin
package com.chdv.searchplus

import ai.onnxruntime.OrtEnvironment
import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.chdv.searchplus.core.Whisper
import com.chdv.searchplus.core.WhisperTokenizer
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Voice search. Two engines:
 *  • the device's own speech recognition service (Google), when it exists – fast and accurate, needs internet;
 *  • Whisper, an AI model that runs on the device without internet (downloaded once).
 * "Automatic" uses the device service when it is there and falls back to Whisper.
 */
object VoiceModels {
    class Spec(val key: String, val title: String, val repo: String, val sizeMb: Int, val note: String)

    /** From light to most accurate. */
    val SPECS = listOf(
        Spec("base", "מהיר", "Xenova/whisper-base", 80, "קל ומהיר, טועה יותר בעברית"),
        Spec("small", "מדויק", "Xenova/whisper-small", 250, "מבין עברית טוב – מתאים לכל מכשיר"),
    )

    private fun totalMemMb(c: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem / (1024 * 1024)
    }

    /** The default level for this device: the most accurate one it can run comfortably. */
    fun defaultKey(c: Context = App.ctx) = if (totalMemMb(c) < 1500) "base" else "small"

    fun spec(key: String = Prefs.s("whisperModel", defaultKey())) = SPECS.firstOrNull { it.key == key } ?: SPECS[1]

    /** The next, more accurate level (null at the top). */
    fun nextSpec(): Spec? = SPECS.getOrNull(SPECS.indexOf(spec()) + 1)

    /** Models go to the storage with the most free space (internal memory or the memory card). */
    fun dir(c: Context, key: String): File {
        val saved = Prefs.s("whisperDir", "")
        val base = if (saved.isNotEmpty() && File(saved).let { it.exists() && it.canWrite() }) File(saved) else {
            val best = (listOf(c.filesDir) + c.getExternalFilesDirs(null).filterNotNull()).filter { it.exists() || it.mkdirs() }
                .maxByOrNull { it.usableSpace } ?: c.filesDir
            Prefs.put("whisperDir", best.absolutePath)
            best
        }
        return File(base, "whisper-$key")
    }

    fun paths(c: Context, key: String) = files(c, key)

    private fun files(c: Context, key: String) = dir(c, key).let {
        Triple(File(it, "encoder_model_quantized.onnx"), File(it, "decoder_model_merged_quantized.onnx"), File(it, "tokenizer.json"))
    }

    fun ready(c: Context, key: String = spec().key): Boolean {
        val (e, d, t) = files(c, key)
        return e.length() > 5_000_000 && d.length() > 5_000_000 && t.length() > 500_000
    }

    fun download(c: Context, key: String, progress: (String) -> Unit) {
        val s = spec(key)
        val d = dir(c, key).apply { mkdirs() }
        val base = "https://huggingface.co/${s.repo}/resolve/main/"
        val (e, dec, t) = files(c, key)
        if (t.length() < 500_000) Net.download(base + "tokenizer.json", t) { progress("מוריד מילון… $it%") }
        if (e.length() < 5_000_000) Net.download(base + "onnx/encoder_model_quantized.onnx", e) { progress("מוריד את המודל (חלק 1 מתוך 2)… $it%") }
        if (dec.length() < 5_000_000) Net.download(base + "onnx/decoder_model_merged_quantized.onnx", dec) { progress("מוריד את המודל (חלק 2 מתוך 2)… $it%") }
        if (!d.exists()) throw IllegalStateException("התיקייה לא נוצרה")
    }

    fun delete(c: Context, key: String) { Engine.release(); dir(c, key).deleteRecursively() }

    /** Keeps Whisper loaded for a few minutes after use (loading takes several seconds), then frees the memory. */
    object Engine {
        private var w: Whisper? = null
        private var key = ""
        private val releaser = Runnable { release() }

        @Synchronized
        fun get(c: Context): Whisper {
            val k = spec().key
            App.main.removeCallbacks(releaser)
            w?.let { if (key == k) return it }
            release()
            Semantic.close() // never hold two models at once on a 2GB device
            System.gc()
            val (e, d, t) = files(c, k)
            val threads = max(1, minOf(4, Runtime.getRuntime().availableProcessors()))
            return Whisper(OrtEnvironment.getEnvironment(), e.absolutePath, d.absolutePath, WhisperTokenizer(t), threads).also { w = it; key = k }
        }

        fun releaseLater() { App.main.removeCallbacks(releaser); App.main.postDelayed(releaser, 3 * 60 * 1000L) }

        @Synchronized
        fun release() { try { w?.close() } catch (e: Exception) {}; w = null }
    }

    /** A device with little memory (like a 2GB E-ink reader): the model is run in the low-memory way. */
    fun lowMemory(c: Context): Boolean = totalMemMb(c) < 3200 || Prefs.b("voiceLowMem", false)

    fun freeMemoryMb(c: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }
}

/** Records from the microphone until the speaker stops (about 1.3 seconds of silence) or 12 seconds pass. */
class Recorder(private val onLevel: (Float) -> Unit) {
    @Volatile var stop = false
    @Volatile var cancel = false

    fun record(): FloatArray? {
        val sr = 16000
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // some devices (E-ink readers among them) don't offer the speech-recognition source: fall back to the plain microphone
        var rec: AudioRecord? = null
        for (src in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)) {
            val r = try { AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, sr)) } catch (e: Exception) { null } ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
            r.release()
        }
        if (rec == null) return null
        val out = ShortArrayBuilder()
        val buf = ShortArray(sr / 10) // 100ms
        var noise = 300f
        var spoke = false
        var quiet = 0
        try {
            rec.startRecording()
            val t0 = System.currentTimeMillis()
            while (!stop && !cancel && System.currentTimeMillis() - t0 < 12_000) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                out.add(buf, n)
                var e = 0L
                for (i in 0 until n) e += abs(buf[i].toInt())
                val level = e.toFloat() / n
                onLevel((level / 3000f).coerceIn(0f, 1f))
                if (!spoke) {
                    if (level > noise * 2.2f && level > 150) spoke = true
                    else noise = noise * 0.9f + level * 0.1f
                } else {
                    if (level < noise * 1.8f) quiet++ else quiet = 0
                    if (quiet >= 13) break
                }
                if (!spoke && System.currentTimeMillis() - t0 > 6000) break // nobody spoke
            }
        } finally {
            try { rec.stop() } catch (e: Exception) {}
            rec.release()
        }
        if (cancel) return null
        val s = out.get()
        return FloatArray(s.size) { s[it] / 32768f }
    }

    private class ShortArrayBuilder {
        private var a = ShortArray(16000 * 4); private var n = 0
        fun add(b: ShortArray, len: Int) { if (n + len > a.size) a = a.copyOf(maxOf(a.size * 2, n + len)); System.arraycopy(b, 0, a, n, len); n += len }
        fun get() = a.copyOf(n)
    }
}

/** The voice search window: listen → recognize → give the text back. */
object VoiceSearch {
    const val REQ_MIC = 41
    private var pending: ((String) -> Unit)? = null

    fun systemAvailable(c: Context) = try { SpeechRecognizer.isRecognitionAvailable(c) } catch (e: Exception) { false }

    // default: on the device, no internet needed (the user asked for offline voice search)
    fun engine(c: Context): String = when (Prefs.s("voiceEngine", "whisper")) {
        "system" -> "system"
        "whisper" -> "whisper"
        else -> if (systemAvailable(c) && !Prefs.b("systemFailed", false)) "system" else "whisper"
    }

    fun start(a: Activity, onText: (String) -> Unit) {
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pending = onText
            a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (engine(a) == "system") listenSystem(a, onText) else listenWhisper(a, onText)
    }

    /** Call from onRequestPermissionsResult. */
    fun onPermission(a: Activity, granted: Boolean) {
        val p = pending; pending = null
        if (granted && p != null) start(a, p)
        else if (!granted) Sheet.info(a, "אין גישה למיקרופון", "כדי לחפש בקול צריך לאשר לחיפוש פלוס להשתמש במיקרופון. אפשר לאשר בהגדרות המכשיר › אפליקציות › חיפוש פלוס › הרשאות.")
    }

    private class Ui(val d: Dialog, val title: TextView, val text: TextView, val bar: View, val btn: TextView)

    private fun window(a: Activity, onButton: () -> Unit, onClose: () -> Unit): Ui {
        val box = a.vbox().apply { setPadding(a.dp(20), a.dp(20), a.dp(20), a.dp(18)) }
        val title = a.label("🎤 מקשיב… דברו עכשיו", 20f, Col.TEXT, true)
        box.addView(title)
        val hint = a.label("לדוגמה: \"ברכות דף כ״ז עמוד א\" · \"ליקוטי שיחות חלק ג ויצא\" · \"מה מברכים על פרי העץ\"", 13.5f, Col.SUB)
        box.addView(hint, lp(MATCH, WRAP).m(0, a.dp(4), 0, a.dp(12)))
        val track = LinearLayout(a).apply { background = roundBg(Col.CARD, a.dp(6).toFloat(), a.dp(1), Col.LINE) }
        val bar = View(a).apply { background = roundBg(Col.ACC, a.dp(6).toFloat()) }
        track.addView(bar, LinearLayout.LayoutParams(a.dp(4), a.dp(14)))
        box.addView(track, lp(MATCH, a.dp(14)))
        val text = a.label("", 17f, Col.ACC, true)
        box.addView(text, lp(MATCH, WRAP).m(0, a.dp(12), 0, 0))
        val row = a.hbox()
        val btn = a.pill("סיימתי לדבר") { onButton() }
        row.addView(btn, lp(0, WRAP, 1f).m(a.dp(4), 0, a.dp(4), 0))
        lateinit var d: Dialog
        row.addView(a.pill("ביטול", false) { d.dismiss() }, lp(0, WRAP, 1f).m(a.dp(4), 0, a.dp(4), 0))
        box.addView(row, lp(MATCH, WRAP).m(0, a.dp(16), 0, 0))
        d = Sheet.make(a, box)
        d.setOnDismissListener { onClose() }
        d.show()
        return Ui(d, title, text, bar, btn)
    }

    private fun level(u: Ui, l: Float) {
        val w = (u.bar.parent as View).width
        u.bar.layoutParams = u.bar.layoutParams.apply { width = max(u.bar.context.dp(4), (w * l).toInt()) }
        u.bar.requestLayout()
    }

    // ---------- the device's speech service ----------

    private fun listenSystem(a: Activity, onText: (String) -> Unit) {
        val sr = try { SpeechRecognizer.createSpeechRecognizer(a) } catch (e: Exception) { null }
        if (sr == null) { Prefs.put("systemFailed", true); listenWhisper(a, onText); return }
        var done = false
        lateinit var u: Ui
        u = window(a, { sr.stopListening() }, { if (!done) { done = true; try { sr.cancel() } catch (e: Exception) {} }; try { sr.destroy() } catch (e: Exception) {} })
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) { level(u, ((rms + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { u.title.text = "⏳ מזהה…" }
            override fun onPartialResults(r: Bundle?) {
                r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { u.text.text = it }
            }
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onResults(r: Bundle?) {
                if (done) return
                done = true
                val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                u.d.dismiss()
                if (t.isNotBlank()) onText(t) else a.toast("לא נקלט דיבור. נסו שוב")
            }
            override fun onError(err: Int) {
                if (done) return
                done = true
                u.d.dismiss()
                when (err) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> a.toast("לא נקלט דיבור. נסו שוב")
                    else -> {
                        // no internet / service missing: offer the on-device engine
                        if (Prefs.s("voiceEngine", "whisper") == "auto") {
                            if (err == SpeechRecognizer.ERROR_CLIENT || err == SpeechRecognizer.ERROR_SERVER || err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) Prefs.put("systemFailed", true)
                            listenWhisper(a, onText)
                        } else Sheet.info(a, "זיהוי הדיבור נכשל", "שירות הזיהוי של המכשיר לא הצליח (קוד $err). ייתכן שאין אינטרנט. אפשר לבחור בהגדרות זיהוי בתוך המכשיר (Whisper), שעובד בלי אינטרנט.")
                    }
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        try { sr.startListening(i) } catch (e: Exception) { done = true; u.d.dismiss(); Prefs.put("systemFailed", true); listenWhisper(a, onText) }
    }

    // ---------- Whisper on the device ----------

    fun offerDownload(a: Activity, then: () -> Unit) {
        val items = VoiceModels.SPECS.map { s -> "${s.title} – כ-${s.sizeMb}MB\n${s.note}" to { Prefs.put("whisperModel", s.key); downloadNow(a, s.key, then) } }
        Sheet.actions(a, "זיהוי דיבור בתוך המכשיר – הורדה חד-פעמית", items)
    }

    fun downloadNow(a: Activity, key: String, then: () -> Unit) {
        val s = a.label("מתחיל להוריד…", 15f, Col.SUB)
        val d = Sheet.make(a, a.vbox().apply { setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(18)); addView(a.label("מוריד את מודל זיהוי הדיבור", 18f, Col.TEXT, true)); addView(s) })
        d.setCancelable(false); d.show()
        Thread {
            try {
                VoiceModels.download(a, key) { t -> ui { s.text = t } }
                ui { d.dismiss(); a.toast("הותקן ✓"); then() }
            } catch (e: Throwable) {
                ui { d.dismiss(); Sheet.info(a, "ההורדה נכשלה", "בדקו את החיבור לאינטרנט ואת המקום הפנוי ונסו שוב. מה שכבר ירד נשמר.\n" + (e.message ?: "")) }
            }
        }.start()
    }

    private fun listenWhisper(a: Activity, onText: (String) -> Unit) {
        if (!VoiceModels.ready(a)) { offerDownload(a) { listenWhisper(a, onText) }; return }
        val rec = Recorder { l -> ui { if (cur != null) level(cur!!, l) } }
        var closed = false
        val u = window(a, { rec.stop = true }, { closed = true; rec.cancel = true; Progress.paused = false })
        cur = u
        Progress.paused = true
        // the model loads in its own process while the user speaks
        VoiceRemote.warm(a)
        Thread {
            val audio = try { rec.record() } catch (e: Throwable) { null }
            if (closed) { cur = null; ui { VoiceRemote.release(a) }; return@Thread }
            if (audio == null || audio.size < 16000 / 3) {
                ui { VoiceRemote.release(a); u.d.dismiss(); a.toast(if (audio == null) "לא ניתן להשתמש במיקרופון" else "לא נקלט דיבור. נסו שוב") }
                cur = null; Progress.paused = false; return@Thread
            }
            ui {
                u.title.text = "⏳ מזהה את הדיבור…"; u.btn.visibility = View.GONE; level(u, 0f)
                val t0 = System.currentTimeMillis()
                val ticker = object : Runnable { override fun run() {
                    if (closed || cur == null) return
                    u.title.text = "⏳ מזהה את הדיבור… ${(System.currentTimeMillis() - t0) / 1000} שנ׳"
                    App.main.postDelayed(this, 1000)
                } }
                App.main.post(ticker)
                VoiceRemote.transcribe(a, audio) { r ->
                    App.main.removeCallbacks(ticker)
                    cur = null
                    Progress.paused = false
                    if (closed) return@transcribe
                    u.d.dismiss()
                    when {
                        r.text != null -> {
                            Prefs.put("voiceLast", "${r.ms / 1000} שנ׳ · \"${r.text}\"")
                            // very slow on this device: suggest the faster level once
                            if (r.encMs > 25_000 && VoiceModels.spec().key != "base" && !Prefs.b("slowHinted", false)) {
                                Prefs.put("slowHinted", true)
                                a.toast("הזיהוי איטי במכשיר הזה – אפשר לבחור בהגדרות זיהוי \"מהיר\"")
                            }
                            val clean = r.text.trim().trimEnd('.', '!', '?')
                            if (clean.isBlank()) a.toast("לא נקלט דיבור. נסו שוב") else onText(clean)
                        }
                        else -> failed(a, r, onText)
                    }
                }
            }
        }.start()
    }

    /** Recognition failed or its process died: never just close – offer what can work on this device. */
    private fun failed(a: Activity, r: VoiceRemote.Result, onText: (String) -> Unit) {
        val cur = VoiceModels.spec()
        val lower = VoiceModels.SPECS.getOrNull(VoiceModels.SPECS.indexOf(cur) - 1)
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (lower != null) items.add("🎤 לנסות עם זיהוי ${lower.title} (קל יותר למכשיר)" to {
            Prefs.put("whisperModel", lower.key)
            if (VoiceModels.ready(a, lower.key)) listenWhisper(a, onText) else downloadNow(a, lower.key) { listenWhisper(a, onText) }
        })
        if (systemAvailable(a)) items.add("🌐 זיהוי דרך האינטרנט (שירות הדיבור של המכשיר)" to { Prefs.put("voiceEngine", "system"); listenSystem(a, onText) })
        items.add("⌨ להקליד במקום לדבר" to { a.startActivity(android.content.Intent(a, VoiceActivity::class.java)) })
        val why = when {
            r.died -> "למכשיר נגמר הזיכרון (יש בו 2GB) והוא עצר את הזיהוי. התוכנה עצמה ממשיכה לעבוד. כדאי לסגור אפליקציות אחרות, או לבחור זיהוי \"מהיר\"."
            else -> r.error ?: "שגיאה"
        }
        Sheet.actions(a, "הזיהוי לא הצליח – $why", items)
    }

    @Volatile private var cur: Ui? = null
}
```

## `app/src/main/java/com/chdv/searchplus/VoiceActivity.kt`

```kotlin
package com.chdv.searchplus

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chdv.searchplus.core.Ref
import com.chdv.searchplus.core.RefParser
import java.io.File
import java.util.concurrent.Executors

/**
 * The voice search screen: a big microphone, what was heard (editable), what it was understood as,
 * and where it leads – every edition of the book at that place, or the books that contain the words.
 * Every step is shown, so the way from the spoken words to the open book is always visible.
 */
class VoiceActivity : BaseActivity() {
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var heard: EditText
    private lateinit var understood: TextView
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private var token = 0
    private val typing by lazy { intent.getStringExtra("mode") == "type" }
    private val live = Runnable { val t = heard.text.toString().trim(); if (t.length >= 3) handle(t, false, quiet = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = vbox().apply { setPadding(dp(16), dp(10), dp(16), dp(30)) }

        // the big microphone
        val mic = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(22), dp(16), dp(18))
            background = Col.gradient(Col.TILES[0].first, Col.TILES[0].second, dp(26).toFloat())
            setOnClickListener { listen() }
        }
        mic.addView(label("🎤", 64f, Col.ACC, true).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = roundBg(Color.WHITE, dp(70).toFloat(), dp(4), if (Col.isEink) Col.ACC else 0x55FFFFFF)
        }, lp(dp(140), dp(140)))
        mic.addView(label("לחצו ודברו", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, dp(12), 0, 0) }, lp(MATCH, WRAP))
        status = label(engineNote(), 13.5f, 0xFFE6E9FF.toInt()).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
        mic.addView(status, lp(MATCH, WRAP))
        if (!typing) box.addView(mic, lp(MATCH, WRAP).m(0, dp(4), 0, dp(14)))
        else box.addView(vbox().apply {
            background = Col.gradient(Col.TILES[3].first, Col.TILES[3].second, dp(20).toFloat())
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(label("⚡ לאן לקפוץ?", 22f, Color.WHITE, true))
            addView(label("כותבים ספר ומקום – והוא נפתח. למשל: \"לקו״ש חלק כ״ד עמוד 358\", \"ברכות כ״ג\", \"שו״ע אדה״ז סימן רע״ח\"", 14f, 0xEEFFFFFF.toInt()).apply { setPadding(0, dp(6), 0, 0) })
        }, lp(MATCH, WRAP).m(0, dp(4), 0, dp(14)))

        // what was heard – can be corrected by hand
        box.addView(label("שמעתי", 13.5f, Col.SUB, true))
        val row = hbox().apply { background = roundBg(Col.CARD, dp(22).toFloat(), dp(1.5f), Col.ACC); setPadding(dp(14), 0, dp(4), 0) }
        heard = EditText(this).apply {
            hint = "מה שתגידו יופיע כאן – אפשר גם לתקן ולהקליד"
            background = null; setSingleLine(); textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f * scale())
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { handle(text.toString(), false); true } else false }
        }
        row.addView(heard, lp(0, WRAP, 1f))
        if (typing) row.addView(label("🎤", 22f, Col.ACC, true).apply { setPadding(dp(8), dp(4), dp(8), dp(4)); setOnClickListener { listen() } })
        row.addView(pill(if (typing) "לפתוח" else "חיפוש") { handle(heard.text.toString(), typing) }, lp(WRAP, WRAP))
        if (typing) heard.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                // live: what was understood and where it leads, while typing
                heard.removeCallbacks(live)
                heard.postDelayed(live, 550)
            }
        })
        box.addView(row, lp(MATCH, WRAP).m(0, dp(4), 0, dp(10)))

        understood = label("", 16f, Col.ACC, true)
        box.addView(understood, lp(MATCH, WRAP).m(dp(4), 0, dp(4), dp(6)))
        results = vbox()
        box.addView(results, lp(MATCH, WRAP))

        // recent searches – one tap away
        val hist = Library.history().take(8)
        if (hist.isNotEmpty()) {
            box.addView(label("🕘 אחרונים", 13.5f, Col.SUB, true).apply { setPadding(0, dp(16), 0, dp(4)) })
            val hf = FlowLayout(this)
            hist.forEach { h -> hf.addView(chip(h, false) { heard.setText(h); handle(h, true) }) }
            box.addView(hf, lp(MATCH, WRAP))
        }
        // examples
        box.addView(label(if (typing) "אפשר לכתוב למשל" else "אפשר לומר למשל", 13.5f, Col.SUB, true).apply { setPadding(0, dp(16), 0, dp(4)) })
        val ex = FlowLayout(this)
        listOf("ברכות דף כ״ז עמוד א", "לקו״ש חלק כ״ד עמוד 358", "ליקוטי שיחות חלק ד שיחה לפרשת נח", "שו״ע אדה״ז סימן רע״ח", "תניא פרק ה", "מה מברכים על פרי העץ").forEach { e ->
            ex.addView(chip("“$e”", false) { heard.setText(e); handle(e, false) })
        }
        box.addView(ex, lp(MATCH, WRAP))

        screen(if (typing) "⚡ מעבר מהיר" else "🎤 חיפוש קולי", ScrollView(this).apply { addView(box) })
        if (typing) heard.post { heard.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(heard, 0) }
        intent.getStringExtra("q")?.let { heard.setText(it); handle(it, true) }
        if (intent.getBooleanExtra("listen", false) && savedInstanceState == null && intent.getStringExtra("q") == null) heard.post { listen() }
    }

    private fun engineNote() = if (VoiceSearch.engine(this) == "system") "זיהוי: שירות הדיבור של המכשיר" else
        "זיהוי בתוך המכשיר – בלי אינטרנט" + if (!VoiceModels.ready(this)) " (צריך להוריד פעם אחת)" else ""

    private fun listen() {
        VoiceSearch.start(this) { t ->
            heard.setText(t); heard.setSelection(heard.text.length)
            // "transcribe only": the words are written in the box, the search waits for the user
            if (Prefs.b("voiceSearchAuto", true)) handle(t, true)
            else { understood.text = "✍ נכתב. אפשר לתקן, ואז \"${if (typing) "לפתוח" else "חיפוש"}\""; heard.requestFocus() }
        }
    }

    override fun onResume() { super.onResume(); status.text = engineNote() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == VoiceSearch.REQ_MIC) VoiceSearch.onPermission(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    /** Spoken (or typed) words → a place in a book, or the books that contain them. */
    private fun handle(raw: String, auto: Boolean, quiet: Boolean = false) {
        val text = raw.trim().trimEnd('.', '?', '!')
        if (text.isEmpty()) return
        if (!quiet) Library.addHistory(text)
        val my = ++token
        results.removeAllViews()
        understood.text = "🔎 מתחיל לחפש…"
        exec.execute {
            val ref = try { RefParser.parse(text) } catch (e: Throwable) { null }
            val noDaf = ref != null && ref.kind == Ref.Kind.GEMARA && ref.daf == null
            // a newer request (typing on) makes this one stale: stop at once, don't block the next
            if (my != token) return@execute
            val stale = { my != token }
            val prog: (String) -> Unit = { t -> ui { if (my == token) understood.text = t } }
            if (ref != null) prog("💡 הבנתי: ${ref.label()}")
            val targets = if (ref != null && !noDaf) try { Jump.resolve(ref, text, stale, prog) } catch (e: Throwable) { emptyList() } else emptyList()
            if (targets.isEmpty() && !stale()) prog("🔤 מחפש את המילים בתוך הספרים…")
            if (stale()) return@execute
            // no place in a book – maybe the name of a book or a folder (as they are on the device)
            val named = if (ref == null) try { Catalog.find(text, null, 8).filter { it.score >= 5f } } catch (e: Throwable) { emptyList() } else emptyList()
            if (stale()) return@execute
            // not a place in a book (or no such book): the books that contain the words
            val words = if (targets.isEmpty()) try {
                Search.run(text, Match.ALL, true).takeIf { it.books.isNotEmpty() } ?: Search.run(text, Match.ANY, true)
            } catch (e: Throwable) { null } else null
            ui {
                if (my != token || isFinishing) return@ui
                show(text, ref, noDaf, targets, words, auto, named)
            }
        }
    }

    private fun show(text: String, ref: Ref?, noDaf: Boolean, targets: List<Jump.Target>, words: Results?, auto: Boolean, named: List<Catalog.Match> = emptyList()) {
        results.removeAllViews()
        when {
            noDaf -> understood.text = "הבנתי שמדובר במסכת ${ref!!.work} – איזה דף? למשל: \"${ref.work} דף כ״ז עמוד א\""
            ref != null && targets.size == 1 -> understood.text = "✓ מצאתי! ${ref.label()} – " + if (targets[0].exact) "לחצו לפתיחה" else "בעמוד המשוער, אפשר לתקן בקורא"
            ref != null && targets.isNotEmpty() -> understood.text = "✓ מצאתי לך ${targets.size} אפשרויות ל${ref.label()} – באיזו לפתוח?"
            ref != null -> understood.text = "חיפשתי ${ref.label()} – אבל לא מצאתי ספר כזה במאגר" + (if (Progress.running) " (הסריקה עדיין רצה)" else "")
            else -> understood.text = "חיפוש המילים: \"$text\""
        }
        // the request is the name of books / a folder on the device
        if (named.isNotEmpty()) {
            understood.text = "📚 מצאתי ${if (named.size == 1) "ספר" else "${named.size} ספרים"} בשם הזה" + if (named.size > 1) " – איזה לפתוח?" else ""
            for (m in named) results.addView(card("📖 ${m.book.title}", Library.categoryOf(m.book.dir).joinToString(" › ").ifEmpty { "תיקייה ראשית" } + " · ${m.book.pages} עמ׳", true) {
                ReaderActivity.open(this, m.book.path, Library.lastPage(m.book.path))
            })
            if (words != null && words.books.any { b -> b.pages.isNotEmpty() }) results.addView(label("ובתוך הספרים, המילים מופיעות כאן:", 14.5f, Col.SUB, true).apply { setPadding(dp(4), dp(12), dp(4), dp(2)) })
        }
        // no such book: the closest names in the library
        if (ref != null && targets.isEmpty()) {
            val sug = try { Jump.suggest(text) } catch (e: Throwable) { emptyList() }
            if (sug.isNotEmpty()) {
                results.addView(label("אולי התכוונת ל:", 14.5f, Col.SUB, true).apply { setPadding(dp(4), dp(4), dp(4), dp(2)) })
                for (b in sug) results.addView(card("📖 ${b.title}", Library.categoryOf(b.dir).joinToString(" › "), false) { ReaderActivity.open(this, b.path, 1) })
            }
        }
        val nums = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫"
        for ((k, t) in targets.withIndex()) results.addView(card((if (targets.size > 1) "${nums.getOrElse(k) { '•' }}  " else "📖 ") + t.book.title, Library.categoryOf(t.book.dir).joinToString(" › ") + " · " + t.note, true) {
            ReaderActivity.open(this, t.book.path, t.page, refLabel = t.ref.label(), amudIndex = t.amudIndex ?: -1, exact = t.exact)
        })
        if (words != null) {
            val books = words.books.sortedByDescending { it.score }.take(25)
            if (books.isEmpty()) results.addView(label("לא נמצא. נסו לומר אחרת, או לשאול לפי משמעות ⬇", 15f, Col.SUB).apply { setPadding(dp(4), dp(6), dp(4), dp(6)) })
            for (h in books) {
                val first = h.pages.firstOrNull()?.page ?: 1
                results.addView(card("📖 ${h.book.title}", h.category.joinToString(" › ").ifEmpty { "תיקייה ראשית" } +
                    if (h.pages.isNotEmpty()) " · ${h.pages.size} עמודים" else " · לפי השם", false) {
                    ReaderActivity.open(this, h.book.path, first, words.terms, h.pages.map { it.page }.distinct().sorted().toIntArray())
                })
            }
            if (words.books.size > books.size) results.addView(pill("לכל ${words.books.size} התוצאות", false) { openMain("words", text) }, lp(MATCH, WRAP).m(0, dp(8), 0, 0))
        }
        results.addView(pill("💡 לשאול את זה לפי משמעות", false) { openMain("meaning", text) }, lp(MATCH, WRAP).m(0, dp(10), 0, 0))
        // nothing useful came out: offer the more accurate speech recognition
        val weak = noDaf || (targets.isEmpty() && (words == null || words.books.isEmpty())) || (ref != null && targets.isEmpty())
        val next = VoiceModels.nextSpec()
        if (weak && next != null && VoiceSearch.engine(this) == "whisper") {
            results.addView(pill("🎤 לא הבין נכון? לנסות זיהוי ${next.title} (${next.note})") {
                Prefs.put("whisperModel", next.key); VoiceModels.Engine.release()
                if (VoiceModels.ready(this, next.key)) listen() else VoiceSearch.downloadNow(this, next.key) { listen() }
            }, lp(MATCH, WRAP).m(0, dp(10), 0, 0))
        } else if (weak && VoiceSearch.engine(this) == "whisper" && VoiceSearch.systemAvailable(this)) {
            // the most advanced: the device's online speech service (Google), much stronger in Hebrew
            results.addView(pill("🌐 לא הבין נכון? לנסות זיהוי מתקדם דרך האינטרנט") {
                Prefs.put("voiceEngine", "auto"); Prefs.put("systemFailed", false); listen()
            }, lp(MATCH, WRAP).m(0, dp(10), 0, 0))
        }
        if (auto && targets.size == 1) results.postDelayed({ if (!isFinishing) results.getChildAt(0)?.performClick() }, 700)
    }

    private fun openMain(screen: String, q: String) =
        startActivity(Intent(this, MainActivity::class.java).putExtra("screen", screen).putExtra("q", q).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    private fun card(title: String, sub: String, strong: Boolean, onClick: () -> Unit): View = vbox().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = pressBg(if (strong) Col.ACC_LIGHT else Col.CARD, Col.ACC_LIGHT, 14, dp(1), if (strong) Col.ACC else Col.LINE)
        addView(label(title, 16.5f, Col.TEXT, true).apply { maxLines = 2 })
        addView(label(sub, 13f, Col.SUB))
        setOnClickListener { onClick() }
        layoutParams = lp(MATCH, WRAP).m(0, dp(4), 0, dp(4))
    }

    override fun onDestroy() { super.onDestroy(); exec.shutdown() }
}
```

## `app/src/main/java/com/chdv/searchplus/VoiceService.kt`

```kotlin
package com.chdv.searchplus

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import com.chdv.searchplus.core.Whisper
import com.chdv.searchplus.core.WhisperTokenizer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Speech recognition in its own process (":voice"). If the model is too heavy for the device and the
 * system kills it, or the native code crashes, only this process dies – the app stays open, notices,
 * and offers the lighter model or online recognition instead of closing.
 */
class VoiceService : Service() {
    companion object {
        const val ACT_LOAD = "load"
        const val ACT_RUN = "run"
        private var w: Whisper? = null
        private var wKey = ""
        private val exec = Executors.newSingleThreadExecutor()
    }

    private val main = Handler(Looper.getMainLooper())
    private val idle = Runnable {
        // nothing to do for a while: give all the memory back by ending this process
        try { w?.close() } catch (e: Exception) {}
        w = null
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onBind(intent: Intent?): IBinder = Binder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        main.removeCallbacks(idle)
        // a foreground service: Android does not kill it first when memory runs short
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (nm.getNotificationChannel("voice") == null) nm.createNotificationChannel(android.app.NotificationChannel("voice", "זיהוי דיבור", android.app.NotificationManager.IMPORTANCE_LOW))
            startForeground(2, android.app.Notification.Builder(this, "voice").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("חיפוש פלוס – מזהה דיבור").setOngoing(true).build())
        } catch (e: Exception) {}
        exec.execute {
            @Suppress("DEPRECATION") val rr: ResultReceiver? = i.getParcelableExtra("rr")
            try {
                val m = load(i)
                if (i.action == ACT_RUN) {
                    val audio = readAudio(File(i.getStringExtra("audio")!!))
                    val t0 = System.currentTimeMillis()
                    val text = m.transcribe(audio, "he", Whisper.PROMPT)
                    rr?.send(0, Bundle().apply {
                        putString("text", text); putLong("ms", System.currentTimeMillis() - t0); putLong("enc", m.lastEncMs)
                    })
                }
            } catch (e: Throwable) {
                try { w?.close() } catch (x: Exception) {}
                w = null
                rr?.send(1, Bundle().apply { putString("error", (if (e is OutOfMemoryError) "אין מספיק זיכרון" else e.message ?: e.javaClass.simpleName)) })
            }
            main.post { try { stopForeground(true) } catch (e: Exception) {} }
            main.postDelayed(idle, if (i.getBooleanExtra("low", false)) 30_000L else 3 * 60 * 1000L)
        }
        return START_NOT_STICKY
    }

    private fun load(i: Intent): Whisper {
        val key = i.getStringExtra("key") ?: "small"
        w?.let { if (wKey == key) return it }
        try { w?.close() } catch (e: Exception) {}
        w = null
        System.gc()
        val threads = max(1, minOf(4, Runtime.getRuntime().availableProcessors()))
        return Whisper(OrtEnvironment.getEnvironment(), i.getStringExtra("enc")!!, i.getStringExtra("dec")!!,
            WhisperTokenizer(File(i.getStringExtra("tok")!!)), threads, OrtSession.SessionOptions.OptLevel.BASIC_OPT,
            lowMemory = i.getBooleanExtra("low", false)).also { w = it; wKey = key }
    }

    private fun readAudio(f: File): FloatArray = DataInputStream(f.inputStream().buffered()).use { d ->
        val n = d.readInt(); FloatArray(n) { d.readFloat() }
    }
}

/** The app's side: start the voice process, send it the recording, and notice if it dies. */
object VoiceRemote {
    class Result(val text: String?, val error: String?, val died: Boolean, val ms: Long = 0, val encMs: Long = 0)

    private fun base(c: Context, action: String): Intent {
        val (e, d, t) = VoiceModels.paths(c, VoiceModels.spec().key)
        return Intent(c, VoiceService::class.java).setAction(action)
            .putExtra("low", VoiceModels.lowMemory(c))
            .putExtra("key", VoiceModels.spec().key).putExtra("enc", e.absolutePath).putExtra("dec", d.absolutePath).putExtra("tok", t.absolutePath)
    }

    /** Start loading the model while the user is still speaking. */
    private var keep: ServiceConnection? = null

    fun warm(c: Context) {
        // free this process's AI models first: on a 2GB device only one model should be in memory
        try { Semantic.close(); Semantic.Deep.close() } catch (e: Throwable) {}
        System.gc()
        try {
            // bound by the visible screen = treated as part of it, not as background work to kill
            if (keep == null) keep = object : ServiceConnection {
                override fun onServiceConnected(n: ComponentName?, b: IBinder?) {}
                override fun onServiceDisconnected(n: ComponentName?) {}
            }.also { c.applicationContext.bindService(Intent(c, VoiceService::class.java), it, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT) }
            // in low-memory mode loading ahead would keep both halves of the model; it loads when needed
            if (!VoiceModels.lowMemory(c)) c.startService(base(c, VoiceService.ACT_LOAD))
        } catch (e: Exception) {}
    }

    fun release(c: Context) { keep?.let { try { c.applicationContext.unbindService(it) } catch (e: Exception) {} }; keep = null }

    fun transcribe(c: Context, audio: FloatArray, done: (Result) -> Unit) {
        val f = File(c.cacheDir, "voice.f32")
        DataOutputStream(f.outputStream().buffered()).use { o -> o.writeInt(audio.size); for (x in audio) o.writeFloat(x) }
        var finished = false
        lateinit var conn: ServiceConnection
        fun finish(r: Result) {
            if (finished) return
            finished = true
            try { c.unbindService(conn) } catch (e: Exception) {}
            release(c)
            done(r)
        }
        conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, b: IBinder?) {}
            // the voice process died (killed for memory or crashed in native code)
            override fun onServiceDisconnected(n: ComponentName?) { ui { finish(Result(null, null, true)) } }
        }
        try { c.bindService(Intent(c, VoiceService::class.java), conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT) } catch (e: Exception) {}
        val rr = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                if (code == 0) finish(Result(data?.getString("text") ?: "", null, false, data?.getLong("ms") ?: 0, data?.getLong("enc") ?: 0))
                else finish(Result(null, data?.getString("error") ?: "שגיאה", false))
            }
        }
        try { c.startService(base(c, VoiceService.ACT_RUN).putExtra("audio", f.absolutePath).putExtra("rr", rr)) }
        catch (e: Exception) { finish(Result(null, e.message ?: "לא ניתן להפעיל את הזיהוי", false)); return }
        // a device far too slow for this model: stop waiting after 3 minutes
        App.main.postDelayed({ finish(Result(null, "הזיהוי לקח יותר מדי זמן במכשיר הזה", false)) }, 180_000)
    }
}
```

## `app/src/main/res/values/strings.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">חיפוש פלוס</string>
    <string name="reader_name">חיפוש פלוס – קורא</string>
</resources>
```

## `app/src/main/res/values/themes.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.SearchPlus" parent="Theme.AppCompat.Light.NoActionBar">
        <item name="colorPrimary">#1F3A5F</item>
        <item name="colorPrimaryDark">#FFFFFF</item>
        <item name="colorAccent">#1F3A5F</item>
        <item name="colorControlActivated">#1F3A5F</item>
        <item name="colorControlNormal">#555555</item>
        <item name="android:windowBackground">@android:color/white</item>
        <item name="android:statusBarColor">@android:color/white</item>
        <item name="android:navigationBarColor">@android:color/white</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:windowAnimationStyle">@null</item>
    </style>
</resources>
```

## `app/src/main/res/xml/file_paths.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-path name="storage" path="." />
    <root-path name="root" path="." />
    <cache-path name="cache" path="." />
</paths>
```

## `build.gradle.kts`

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.10" apply false
}
```

## `core/build.gradle.kts`

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // The app ships onnxruntime-android (same API); tests run on the desktop build.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    maxHeapSize = "3g"
    systemProperty("models", System.getenv("MODELS_DIR") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
```

## `core/src/main/kotlin/com/chdv/searchplus/core/Abbr.kt`

```kotlin
package com.chdv.searchplus.core

/**
 * ראשי תיבות – abbreviations used in Torah books, in Chabad above all, with their full forms.
 * Used three ways: to understand a request ("שו״ע אדה״ז"), to understand folder and file names
 * (a folder "אדה״ז" holds the books of אדמו״ר הזקן), and as a dictionary the user can look up.
 * Keys are written without quote marks (the way RefParser.normalize leaves them).
 */
object Abbr {
    val MAP: Map<String, String> = linkedMapOf(
        // רבותינו נשיאינו
        "אדהז" to "אדמו״ר הזקן", "אדמוהז" to "אדמו״ר הזקן", "רבנו הזקן" to "אדמו״ר הזקן", "בעל התניא" to "אדמו״ר הזקן",
        "אדהאמ" to "אדמו״ר האמצעי", "אדמוהאמ" to "אדמו״ר האמצעי",
        "אדמצ" to "אדמו״ר הצמח צדק", "אדמוצ" to "אדמו״ר הצמח צדק", "צצ" to "הצמח צדק", "הצצ" to "הצמח צדק",
        "מהרש" to "אדמו״ר מהר״ש", "אדמוהרש" to "אדמו״ר מהר״ש",
        "רשב" to "אדמו״ר הרש״ב", "אדמוהרשב" to "אדמו״ר הרש״ב", "מוהרשב" to "אדמו״ר הרש״ב",
        "ריצ" to "אדמו״ר הריי״צ", "רייצ" to "אדמו״ר הריי״צ", "אדמוהריצ" to "אדמו״ר הריי״צ", "מוהריצ" to "אדמו״ר הריי״צ", "מוהרייצ" to "אדמו״ר הריי״צ",
        "כק" to "כבוד קדושת", "כקאדמור" to "כבוד קדושת אדמו״ר", "אדמור" to "אדוננו מורנו ורבנו",
        "שליטא" to "שיחיה לאורך ימים טובים אמן", "נע" to "נשמתו עדן", "זיע" to "זכותו יגן עלינו", "זצל" to "זכר צדיק לברכה",
        "זל" to "זכרונו לברכה", "עה" to "עליו השלום", "נבגם" to "נשמתו בגנזי מרומים", "מהמ" to "מלך המשיח",
        // ספרים
        "לקוש" to "ליקוטי שיחות", "ליקוש" to "ליקוטי שיחות", "תומ" to "תורת מנחם", "אגק" to "אגרות קודש", "סהמ" to "ספר המאמרים",
        "סהש" to "ספר השיחות", "לקות" to "ליקוטי תורה", "תוא" to "תורה אור", "דמח" to "דרך מצוותיך", "מאהז" to "מאמרי אדמו״ר הזקן",
        "שוע" to "שולחן ערוך", "שעהר" to "שולחן ערוך הרב", "קצשוע" to "קיצור שולחן ערוך", "משב" to "משנה ברורה", "מב" to "משנה ברורה",
        "רמבם" to "רמב״ם", "רמבן" to "רמב״ן", "רשי" to "רש״י", "תוס" to "תוספות", "מהרשא" to "מהרש״א", "שך" to "ש״ך", "טז" to "ט״ז",
        "בהג" to "באר היטב", "שות" to "שאלות ותשובות", "חזל" to "חכמינו זכרונם לברכה", "תנך" to "תורה נביאים כתובים",
        "שס" to "שישה סדרים (תלמוד)", "ספהמ" to "ספר המצוות", "פיהמ" to "פירוש המשניות",
        "אוח" to "אורח חיים", "יוד" to "יורה דעה", "אהע" to "אבן העזר", "חומ" to "חושן משפט",
        "בק" to "בבא קמא", "במ" to "בבא מציעא", "בב" to "בבא בתרא", "עז" to "עבודה זרה", "רה" to "ראש השנה", "יוהכ" to "יום הכיפורים",
        // מונחים
        "ע״א" to "עמוד א", "עא" to "עמוד א", "עב" to "עמוד ב", "סע" to "סעיף", "סי" to "סימן", "פ" to "פרק", "ח״א" to "חלק א",
        "עיי״ש" to "עיין שם", "עיש" to "עיין שם", "וכו" to "וכולי", "וכמש״נ" to "וכמו שנתבאר", "כמשנ" to "כמו שנתבאר", "כנל" to "כנזכר לעיל",
        "ס״ה" to "סך הכל", "סה" to "סך הכל", "וז״ל" to "וזה לשונו", "וזל" to "וזה לשונו",
        "עכ״ל" to "עד כאן לשונו", "עכל" to "עד כאן לשונו", "אא״כ" to "אלא אם כן", "אאכ" to "אלא אם כן", "בד״כ" to "בדרך כלל", "בדכ" to "בדרך כלל",
        "דוקא" to "דווקא", "הקב״ה" to "הקדוש ברוך הוא", "הקבה" to "הקדוש ברוך הוא", "ית׳" to "יתברך", "יתב" to "יתברך", "אוא״ס" to "אור אין סוף", "אואס" to "אור אין סוף",
        "א״ס" to "אין סוף", "אס" to "אין סוף", "ב״ה" to "ברוך השם", "בה" to "ברוך השם", "בעזה״י" to "בעזרת השם יתברך", "בעזהי" to "בעזרת השם יתברך",
        "בס״ד" to "בסייעתא דשמיא", "בסד" to "בסייעתא דשמיא", "אי״ה" to "אם ירצה השם", "איה" to "אם ירצה השם", "בל״נ" to "בלי נדר", "בלנ" to "בלי נדר",
        "ז״א" to "זאת אומרת / זעיר אנפין", "זא" to "זאת אומרת", "כו״כ" to "כמה וכמה", "כוכ" to "כמה וכמה", "מ״מ" to "מכל מקום", "ממ" to "מכל מקום",
        "מ״ש" to "מה שכתוב", "מש" to "מה שכתוב", "ע״ד" to "על דרך", "עד" to "על דרך", "ע״פ" to "על פי", "עפ" to "על פי", "עפ״ז" to "על פי זה",
        "ע״י" to "על ידי", "עי" to "על ידי", "ע״ז" to "על זה", "עפ״י" to "על פי", "עפי" to "על פי", "ר״ל" to "רוצה לומר", "רל" to "רוצה לומר",
        "צ״ל" to "צריך לומר", "צל" to "צריך לומר", "צ״ע" to "צריך עיון", "צע" to "צריך עיון", "ק״ו" to "קל וחומר", "קו" to "קל וחומר",
        "וד״ל" to "ודי למבין", "ודל" to "ודי למבין", "ואכ״מ" to "ואין כאן מקומו", "ואכמ" to "ואין כאן מקומו",
        "ע״ה" to "עם הארץ", "ת״ח" to "תלמיד חכם", "תח" to "תלמיד חכם", "ת״ת" to "תלמוד תורה", "תת" to "תלמוד תורה", "יר״ש" to "ירא שמים", "ירש" to "ירא שמים",
        "אה״ק" to "ארץ הקודש", "אהק" to "ארץ הקודש", "אהי״ש" to "אור החיים", "מס״נ" to "מסירות נפש", "מסנ" to "מסירות נפש", "קב״ע" to "קבלת עול", "קבע" to "קבלת עול",
        "חב״ד" to "חכמה בינה דעת", "חבד" to "חכמה בינה דעת", "חג״ת" to "חסד גבורה תפארת", "חגת" to "חסד גבורה תפארת", "נה״י" to "נצח הוד יסוד", "נהי" to "נצח הוד יסוד",
        "זו״נ" to "זעיר אנפין ונוקבא", "זון" to "זעיר אנפין ונוקבא", "או״א" to "אבא ואמא", "אוא" to "אבא ואמא", "אצי״ע" to "אצילות בריאה יצירה עשיה", "אביע" to "אצילות בריאה יצירה עשיה",
        "נה״ב" to "נפש הבהמית", "נהב" to "נפש הבהמית", "נה״א" to "נפש האלקית", "נהא" to "נפש האלקית", "יחו״ע" to "יחודא עילאה", "יחו״ת" to "יחודא תתאה",
        "ג״ע" to "גן עדן", "גע" to "גן עדן", "גה״ע" to "גן עדן", "תחה״מ" to "תחיית המתים", "תחהמ" to "תחיית המתים", "ביהמ״ק" to "בית המקדש", "ביהמק" to "בית המקדש",
        "ביהכנ״ס" to "בית הכנסת", "ביהכנס" to "בית הכנסת", "ביהמ״ד" to "בית המדרש", "ביהמד" to "בית המדרש", "אנ״ש" to "אנשי שלומנו", "אנש" to "אנשי שלומנו",
        "תשב״כ" to "תורה שבכתב", "תשבכ" to "תורה שבכתב", "תשבע״פ" to "תורה שבעל פה", "תשבעפ" to "תורה שבעל פה", "מצו״ע" to "מצוות עשה", "מ״ע" to "מצוות עשה",
        "מל״ת" to "מצוות לא תעשה", "מלת" to "מצוות לא תעשה", "יו״ט" to "יום טוב", "יוט" to "יום טוב", "חוה״מ" to "חול המועד", "חוהמ" to "חול המועד", "ר״ח" to "ראש חודש", "רח" to "ראש חודש",
        "שמחת״ת" to "שמחת תורה", "ער״ה" to "ערב ראש השנה", "יוהכ״פ" to "יום הכיפורים",
        "פ׳" to "פרשת", "פר׳" to "פרשת", "ש״פ" to "שבת פרשת", "שפ" to "שבת פרשת", "מוצש״ק" to "מוצאי שבת קודש", "מוצשק" to "מוצאי שבת קודש", "ש״ק" to "שבת קודש", "שק" to "שבת קודש",
    )

    private val norm: Map<String, String> = MAP.mapKeys { RefParser.normalize(it.key) }.filterKeys { it.length >= 2 }

    /** Full form of one abbreviation (null if unknown). */
    fun of(word: String): String? = norm[RefParser.normalize(word)]

    /**
     * Text with the abbreviations that matter for finding books written out ("שו״ע אדה״ז" → "שולחן ערוך אדמו״ר הזקן").
     * Only words that were written as abbreviations (with ״ or ׳, or known book/author abbreviations) are expanded.
     */
    fun expand(text: String): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.joinToString(" ") { w ->
            val key = RefParser.normalize(w)
            val hasMark = w.contains('"') || w.contains('״') || w.contains('\'') || w.contains('׳')
            val full = norm[key]
            if (full != null && key !in KEEP && (hasMark || key in BOOKS_AND_PEOPLE)) full else w
        }
    }

    /** Titles that are part of names and stay as they are ("אדמו״ר הזקן", "כ״ק"). */
    private val KEEP = setOf("אדמור", "כק", "כקאדמור", "עא", "עב", "סע", "סי")

    /** Abbreviations that are expanded even when written without quote marks (they are not ordinary words). */
    private val BOOKS_AND_PEOPLE = setOf("אדהז", "אדמוהז", "אדהאמ", "אדמצ", "אדמוצ", "מהרש", "רשב", "מוהרשב", "רייצ", "מוהריצ", "מוהרייצ",
        "לקוש", "ליקוש", "תומ", "אגק", "סהמ", "סהש", "לקות", "תוא", "דמח", "שוע", "שעהר", "קצשוע", "אוח", "יוד", "אהע", "חומ", "רמבם", "שליטא")

    /** Search the dictionary (by abbreviation or by meaning). */
    fun lookup(q: String): List<Pair<String, String>> {
        val n = RefParser.normalize(q)
        if (n.isBlank()) return MAP.entries.map { it.key to it.value }.distinctBy { it.second + it.first.length }
        return MAP.entries.filter { (k, v) -> RefParser.normalize(k).contains(n) || v.contains(q.trim()) }.map { it.key to it.value }
    }
}
```

## `core/src/main/kotlin/com/chdv/searchplus/core/HebRef.kt`

```kotlin
package com.chdv.searchplus.core

/**
 * Understanding a spoken or typed reference to a place in a book:
 * "מסכת ברכות דף כז עמוד א", "ברכות כ"ז ע"ב", "ליקוטי שיחות חלק ג שיחה ב לפרשת ויצא",
 * "תניא פרק ה", "דף כף זין" (letter names, as people say them aloud), "דף עשרים ושבע".
 */
object HebNum {
    private val VAL = mapOf(
        'א' to 1, 'ב' to 2, 'ג' to 3, 'ד' to 4, 'ה' to 5, 'ו' to 6, 'ז' to 7, 'ח' to 8, 'ט' to 9,
        'י' to 10, 'כ' to 20, 'ך' to 20, 'ל' to 30, 'מ' to 40, 'ם' to 40, 'נ' to 50, 'ן' to 50, 'ס' to 60, 'ע' to 70,
        'פ' to 80, 'ף' to 80, 'צ' to 90, 'ץ' to 90, 'ק' to 100, 'ר' to 200, 'ש' to 300, 'ת' to 400,
    )

    /** Names of the letters, as they are said aloud (and as speech recognition tends to write them). */
    val LETTER_NAMES: Map<String, Char> = buildMap {
        fun n(c: Char, vararg names: String) = names.forEach { put(it, c) }
        n('א', "אלף", "אלפ", "אלעף")
        n('ב', "בית", "בייט", "בת", "בס", "בעת", "בית'")
        n('ג', "גימל", "גמל", "גימעל")
        n('ד', "דלת", "דלד", "דלט", "דאלעד", "דאלת")
        n('ה', "הא", "הי", "היי", "הה", "האי")
        n('ו', "וו", "ואו", "וב", "וואו", "ווב")
        n('ז', "זין", "זיין", "זאין", "זן", "זיון")
        n('ח', "חית", "חת", "חס", "חיס", "חיט")
        n('ט', "טית", "טת", "טס", "טיס", "טט")
        n('י', "יוד", "יוּד", "יד", "יאד", "יודה")
        n('כ', "כף", "כאף", "קף", "חף", "כפ", "קאף", "כאפ", "חב", "כב", "חאף", "חאב", "כהף")
        n('ל', "למד", "לאמד", "למעד", "לאמעד", "למ")
        n('מ', "מם", "מים", "מעם", "מאם")
        n('נ', "נון", "נוּן", "נן")
        n('ס', "סמך", "סאמעך", "סמח", "סאמך")
        n('ע', "עין", "עיין", "איין", "אין")
        n('פ', "פא", "פה", "פי", "פיי", "פאי")
        n('צ', "צדי", "צדיק", "צאדי", "צדיי", "צדיה")
        n('ק', "קוף", "קופ", "קוּף")
        n('ר', "ריש", "רייש", "רש", "רעש")
        n('ש', "שין", "שיין", "שן", "סין")
        n('ת', "תו", "תיו", "תוו", "תף", "תאו", "סאף", "תאף")
    }

    private val UNITS = mapOf(
        "אחד" to 1, "אחת" to 1, "ראשון" to 1, "ראשונה" to 1,
        "שתיים" to 2, "שתים" to 2, "שניים" to 2, "שנים" to 2, "שני" to 2, "שנייה" to 2, "שניה" to 2, "שתי" to 2,
        "שלוש" to 3, "שלושה" to 3, "שלש" to 3, "שלשה" to 3, "שלישי" to 3, "שלישית" to 3,
        "ארבע" to 4, "ארבעה" to 4, "רביעי" to 4, "רביעית" to 4,
        "חמש" to 5, "חמישה" to 5, "חמשה" to 5, "חמישי" to 5, "חמישית" to 5,
        "שש" to 6, "שישה" to 6, "ששה" to 6, "שישי" to 6, "שישית" to 6,
        "שבע" to 7, "שבעה" to 7, "שביעי" to 7, "שביעית" to 7,
        "שמונה" to 8, "שמיני" to 8, "שמינית" to 8,
        "תשע" to 9, "תשעה" to 9, "תשיעי" to 9, "תשיעית" to 9,
        "עשר" to 10, "עשרה" to 10, "עשירי" to 10, "עשירית" to 10,
    )
    private val TENS = mapOf(
        "עשרים" to 20, "שלושים" to 30, "שלשים" to 30, "ארבעים" to 40, "חמישים" to 50, "חמשים" to 50,
        "שישים" to 60, "ששים" to 60, "שבעים" to 70, "שמונים" to 80, "תשעים" to 90,
        "מאה" to 100, "מאתיים" to 200, "מאתים" to 200,
    )

    /** Value of a Hebrew-letter number written as one word (כז, קנג, טו). Null when the word is not a number. */
    fun gematria(word: String): Int? {
        val w = word.filter { it != '"' && it != '\'' && it != '״' && it != '׳' }
        if (w.isEmpty() || w.length > 4) return null
        var sum = 0
        var last = Int.MAX_VALUE
        for ((i, ch) in w.withIndex()) {
            val v = VAL[ch] ?: return null
            // final letters only at the end, letters go from large to small (except טו / טז)
            if (ch in "ךםןףץ" && i != w.length - 1) return null
            if (v > last && !(w.endsWith("טו") || w.endsWith("טז"))) return null
            if (v == last && v != 400 && v != 100) return null
            last = v
            sum += v
        }
        return sum
    }

    fun toHeb(n: Int): String {
        if (n <= 0) return n.toString()
        val sb = StringBuilder()
        var x = n
        while (x >= 400) { sb.append('ת'); x -= 400 }
        for ((v, c) in listOf(300 to 'ש', 200 to 'ר', 100 to 'ק')) if (x >= v) { sb.append(c); x -= v }
        if (x == 15) return sb.append("טו").toString()
        if (x == 16) return sb.append("טז").toString()
        for ((v, c) in listOf(90 to 'צ', 80 to 'פ', 70 to 'ע', 60 to 'ס', 50 to 'נ', 40 to 'מ', 30 to 'ל', 20 to 'כ', 10 to 'י')) if (x >= v) { sb.append(c); x -= v; break }
        if (x > 0) sb.append("אבגדהוזחט"[x - 1])
        return sb.toString()
    }

    /**
     * Read a number that starts at tokens[i]. Accepts: digits (27), letters (כז / כ"ז), letter names
     * (כף זין), number words (עשרים ושבע, שבע ועשרים, שבעה עשר). Returns value and how many tokens were used.
     */
    fun read(tokens: List<String>, i: Int, allowPlainWord: Boolean = true): Pair<Int, Int>? {
        if (i >= tokens.size) return null
        val t = tokens[i]
        t.toIntOrNull()?.let { return it to 1 }
        Regex("^(\\d+)[אבab.:]?$").find(t)?.let { return it.groupValues[1].toInt() to 1 }
        // letter names: "כף זין", "קוף נון גימל"
        run {
            var j = i
            val sb = StringBuilder()
            while (j < tokens.size && sb.length < 4) {
                val c = LETTER_NAMES[stripVav(tokens[j], j > i)]
                if (c != null) { sb.append(c); j++; continue }
                // two letter names written as one word by the speech recognizer: "כפזין", "חבזין"
                val glued = glued(tokens[j]) ?: break
                sb.append(glued); j++
            }
            if (j - i == 1 && sb.length >= 2) gematria(sb.toString())?.let { return it to 1 }
            if (j - i >= 2 || (j - i == 1 && tokens[i].length >= 2 && tokens[i] !in UNITS && tokens[i] !in TENS && tokens[i] in STRONG_NAMES)) {
                gematria(sb.toString())?.let { return it to (j - i) }
            }
        }
        // number words
        run {
            var j = i
            var total = 0
            var used = false
            while (j < tokens.size) {
                val w = stripVav(tokens[j], used)
                val u = UNITS[w]; val tn = TENS[w]
                when {
                    tn != null -> { total += tn; used = true; j++ }
                    u != null -> {
                        // "שבעה עשר" = 17
                        if (j + 1 < tokens.size && (tokens[j + 1] == "עשר" || tokens[j + 1] == "עשרה") && u < 10) { total += u + 10; j += 2 }
                        else { total += u; j++ }
                        used = true
                    }
                    else -> break
                }
            }
            if (used && total > 0) return total to (j - i)
        }
        if (allowPlainWord) gematria(t)?.let { return it to 1 }
        return null
    }

    private fun glued(w: String): String? {
        if (w.length < 4) return null
        for (k in 2..w.length - 2) {
            val a = LETTER_NAMES[w.substring(0, k)] ?: continue
            val b = LETTER_NAMES[w.substring(k)] ?: continue
            return "$a$b"
        }
        return null
    }

    /** Letter names that are clearly a number even when said alone ("דף זין"). */
    private val STRONG_NAMES = setOf("אלף", "בית", "גימל", "דלת", "זין", "חית", "טית", "יוד", "כף", "למד", "מם", "נון", "סמך", "עין", "צדי", "קוף", "ריש", "שין", "תו")

    private fun stripVav(w: String, allowed: Boolean): String =
        if (allowed && w.length > 2 && w[0] == 'ו' && w !in UNITS && w !in TENS && w !in LETTER_NAMES) w.substring(1) else w
}

data class Ref(
    val kind: Kind,
    /** Work name as it should appear in a book's file or folder name (ברכות, לקוטי שיחות, תניא...). */
    val work: String,
    /** Other words said, used to choose between books (edition names like שוטנשטיין, וילנא, עוז והדר). */
    val extra: List<String> = emptyList(),
    val volume: Int? = null,
    val daf: Int? = null,
    /** 1 = עמוד א, 2 = עמוד ב, null = not said. */
    val amud: Int? = null,
    val chapter: Int? = null,
    val siman: Int? = null,
    val seif: Int? = null,
    val sicha: Int? = null,
    val parsha: String? = null,
    /** A printed page number ("עמוד 45" in a book that is not Gemara). */
    val page: Int? = null,
) {
    enum class Kind { GEMARA, SICHOS, GENERIC }

    /** Short Hebrew description, e.g. "ברכות דף כז ע״א". */
    fun label(): String = buildString {
        append(work)
        volume?.let { append(" חלק ").append(HebNum.toHeb(it)) }
        parsha?.let { append(" – ").append(it) }
        sicha?.let { append(" שיחה ").append(HebNum.toHeb(it)) }
        daf?.let { append(" דף ").append(HebNum.toHeb(it)) }
        amud?.let { append(if (it == 1) " ע״א" else " ע״ב") }
        chapter?.let { append(" פרק ").append(HebNum.toHeb(it)) }
        siman?.let { append(" סימן ").append(HebNum.toHeb(it)) }
        seif?.let { append(" סעיף ").append(HebNum.toHeb(it)) }
        page?.let { append(" עמוד ").append(it) }
    }
}

object RefParser {
    val MASECHTOT: List<String> = listOf(
        "ברכות", "שבת", "עירובין", "פסחים", "שקלים", "יומא", "סוכה", "ביצה", "ראש השנה", "תענית", "מגילה",
        "מועד קטן", "חגיגה", "יבמות", "כתובות", "נדרים", "נזיר", "סוטה", "גיטין", "קידושין", "בבא קמא",
        "בבא מציעא", "בבא בתרא", "סנהדרין", "מכות", "שבועות", "עבודה זרה", "הוריות", "זבחים", "מנחות",
        "חולין", "בכורות", "ערכין", "תמורה", "כריתות", "מעילה", "תמיד", "נדה",
    )

    /** Other spellings → the standard name. */
    private val MAS_ALIASES = mapOf(
        "ערובין" to "עירובין", "עירובן" to "עירובין", "פסחין" to "פסחים", "סוכא" to "סוכה", "ביצא" to "ביצה",
        "מגלה" to "מגילה", "כתובות" to "כתובות", "כתובת" to "כתובות", "קדושין" to "קידושין", "גטין" to "גיטין",
        "בק" to "בבא קמא", "במ" to "בבא מציעא", "בב" to "בבא בתרא", "בבא קמה" to "בבא קמא", "בבא מציאה" to "בבא מציעא",
        "עז" to "עבודה זרה", "עבודה זרא" to "עבודה זרה", "מק" to "מועד קטן", "רה" to "ראש השנה", "חלין" to "חולין",
        "נידה" to "נדה", "סנהדרן" to "סנהדרין", "שבועת" to "שבועות", "יבמת" to "יבמות", "זבחין" to "זבחים",
        "מנחת" to "מנחות", "בכורת" to "בכורות", "כריתת" to "כריתות", "הוריות" to "הוריות", "הוריוט" to "הוריות",
        "נזר" to "נזיר", "סוטא" to "סוטה", "יומה" to "יומא", "שקלין" to "שקלים", "תמורא" to "תמורה",
    )

    val PARSHIYOS: List<String> = listOf(
        "בראשית", "נח", "לך לך", "וירא", "חיי שרה", "תולדות", "ויצא", "וישלח", "וישב", "מקץ", "ויגש", "ויחי",
        "שמות", "וארא", "בא", "בשלח", "יתרו", "משפטים", "תרומה", "תצוה", "כי תשא", "ויקהל", "פקודי",
        "ויקרא", "צו", "שמיני", "תזריע", "מצורע", "אחרי מות", "אחרי", "קדושים", "אמור", "בהר", "בחוקותי", "בחקתי",
        "במדבר", "נשא", "בהעלותך", "שלח", "קרח", "חוקת", "חקת", "בלק", "פנחס", "מטות", "מסעי",
        "דברים", "ואתחנן", "עקב", "ראה", "שופטים", "כי תצא", "כי תבוא", "נצבים", "וילך", "האזינו", "וזאת הברכה",
        // festivals and special days that have sichos
        "ראש השנה", "יום כיפור", "סוכות", "שמיני עצרת", "שמחת תורה", "חנוכה", "פורים", "פסח", "שבועות",
        "ל״ג בעומר", "לג בעומר", "תשעה באב", "ט״ו באב", "חמשה עשר באב", "י״ט כסלו", "יט כסלו", "י״ב תמוז", "יב תמוז",
    )
    private val PARSHA_ALIASES = mapOf("תצווה" to "תצוה", "בחוקתי" to "בחוקותי", "פינחס" to "פנחס", "מטת" to "מטות",
        "חיי שרא" to "חיי שרה", "נוח" to "נח", "שמיני עצרת" to "שמיני עצרת", "בחקותי" to "בחוקותי", "ויצה" to "ויצא", "וישלך" to "וישלח", "שלח לך" to "שלח", "קורח" to "קרח")

    private val SICHOS_NAMES = listOf("ליקוטי שיחות", "לקוטי שיחות", "ליקוטי שיחת", "לקוטי שיחת", "לקוש", "ליקוש", "לקש")
    private val GEMARA_WORDS = setOf("גמרא", "גמרה", "גמ", "תלמוד", "בבלי", "מסכת", "מסכתא", "מסכתות", "שס", "ש\"ס")

    /** Spoken filler words that carry no meaning for finding the place. */
    private val FILLER = setOf(
        "תפתח", "תפתחי", "תח", "תפתחו", "פתיחת", "ספר", "בספר", "הספר", "פתח", "פתחי", "תביא", "תמצא", "מצא", "חפש", "תחפש", "תחפשי", "לי", "את", "של", "במהדורת",
        "מהדורת", "מהדורה", "בבקשה", "אני", "רוצה", "ללמוד", "לפתוח", "תראה", "הראה", "על", "לפרשת", "בפרשת", "פרשת", "פרשה",
        "פ", "ה", "או", "עם", "ב", "ל", "דף", "עמוד", "עמ", "חלק", "כרך", "פרק", "סימן", "שיחה", "שיחת", "שיחות",
    )

    fun normalize(s: String): String {
        var t = s.replace(Regex("[\\u0591-\\u05C7]"), "") // nikud & teamim
        t = t.replace('״', '"').replace('׳', '\'').replace('“', '"').replace('”', '"').replace('’', '\'').replace('`', '\'')
        t = t.replace(Regex("(?<=[א-ת])['\"](?=[.:?!]?(\\s|$))"), "") // ה׳. → ה.
        // amud marks: "כז." = amud a, "כז:" = amud b → keep as words
        t = t.replace(Regex("(?<=[א-ת\\d])\\.(?=\\s|$)"), " ע\"א ").replace(Regex("(?<=[א-ת\\d]):(?=\\s|$)"), " ע\"ב ")
        t = t.replace(Regex("[,;!?()\\[\\]{}–—\\-/]"), " ").replace(Regex("[.:](?=\\s|$)"), " ")
        t = t.replace("\"", "").replace("'", "")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun parse(input: String): Ref? {
        // abbreviations written out first: "שו״ע אדה״ז" → "שולחן ערוך אדמו״ר הזקן"
        val norm = normalize(Abbr.expand(input))
        if (norm.isEmpty()) return null
        val tokens = norm.split(' ').flatMap { t ->
            // "דפנט" / "דפנת" = "דף נט" written together by the speech recognizer
            if ((t.startsWith("דף") || t.startsWith("דפ")) && t.length in 4..6 && t !in setOf("דפים", "דפוס", "דפנה")) listOf("דף", t.substring(2)) else listOf(t)
        }.toMutableList()
        // after "דף": a ת at the end that makes no daf number is a mis-heard ט (נת → נט)
        for (k in 1 until tokens.size) {
            val t = tokens[k]
            if (tokens[k - 1] == "דף" && t.length in 2..3 && t.endsWith("ת")) {
                val g = HebNum.gematria(t)
                if (g == null || g > 180) { val f = t.dropLast(1) + "ט"; if (HebNum.gematria(f) != null) tokens[k] = f }
            }
        }
        val text = " $norm "

        // ---- which work ----
        var kind = Ref.Kind.GENERIC
        var work: String? = null
        val used = HashSet<Int>()

        fun findPhrase(p: String): Int {
            val ws = p.split(' ')
            outer@ for (i in 0..tokens.size - ws.size) {
                for (k in ws.indices) {
                    val t = tokens[i + k]
                    if (t != ws[k] && !(k == 0 && (t == "ב" + ws[k] || t == "ל" + ws[k] || t == "מ" + ws[k] || t == "ו" + ws[k]))) continue@outer
                }
                return i
            }
            return -1
        }

        for (n in SICHOS_NAMES) {
            val i = findPhrase(n)
            if (i >= 0) { kind = Ref.Kind.SICHOS; work = "לקוטי שיחות"; repeat(n.split(' ').size) { used.add(i + it) }; break }
        }
        if (work == null) {
            val all = MASECHTOT.map { it to it } + MAS_ALIASES.entries.map { it.key to it.value }
            for ((spelling, name) in all.sortedByDescending { it.first.length }) {
                if (spelling.length <= 2 && !(text.contains(" דף ") || tokens.any { it in GEMARA_WORDS })) continue
                val i = findPhrase(spelling)
                if (i >= 0) {
                    // "שבת" / "ראש השנה" / "שבועות" can also be a festival or a word: needs a Gemara word, or a daf
                    val needsCtx = name in setOf("שבת", "ראש השנה", "שבועות", "תמיד", "ביצה", "סוכה", "מכות", "נזיר", "תענית")
                    if (needsCtx && !(tokens.any { it in GEMARA_WORDS } || tokens.contains("דף") || looksLikeDafAfter(tokens, i + spelling.split(' ').size))) continue
                    kind = Ref.Kind.GEMARA; work = name; repeat(spelling.split(' ').size) { used.add(i + it) }; break
                }
            }
        }

        // speech recognition often spells a masechet name wrongly ("בבה מיציה"): compare without the vowel letters
        if (work == null && (tokens.contains("דף") || tokens.any { it in GEMARA_WORDS })) {
            fun key(w: String) = w.filter { it !in "אהעיו" }.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ')
            loop@ for (name in MASECHTOT.sortedByDescending { it.length }) {
                val ws = name.split(' ')
                val nk = ws.map(::key)
                if (nk.any { it.length < 2 }) continue
                for (k in 0..tokens.size - ws.size) {
                    if ((0 until ws.size).all { key(tokens[k + it]) == nk[it] && tokens[k + it] != "דף" }) {
                        kind = Ref.Kind.GEMARA; work = name; repeat(ws.size) { used.add(k + it) }; break@loop
                    }
                }
            }
        }

        // ---- numbers by their keyword ----
        var volume: Int? = null; var daf: Int? = null; var amud: Int? = null; var chapter: Int? = null
        var siman: Int? = null; var seif: Int? = null; var sicha: Int? = null; var page: Int? = null
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            fun num(): Int? {
                val r = HebNum.read(tokens, i + 1) ?: return null
                for (k in 0..r.second) used.add(i + k)
                i += r.second
                return r.first
            }
            when (t) {
                "דף", "בדף", "לדף" -> { num()?.let { daf = it } }
                "חלק", "בחלק", "כרך", "בכרך" -> {
                    // "חלק ח' ד'" = חלק ד (the ח stands for חלק)
                    if (tokens.getOrNull(i + 1) == "ח" && HebNum.read(tokens, i + 2, allowPlainWord = true)?.first?.let { it in 1..39 } == true) { used.add(i); i++ }
                    num()?.let { volume = it }
                }
                "פרק", "בפרק", "לפרק" -> { num()?.let { chapter = it } }
                "סימן", "בסימן", "סי" -> { num()?.let { siman = it } }
                "סעיף", "בסעיף", "סע" -> { num()?.let { if (siman == null) siman = it else seif = it } }
                "שיחה", "בשיחה", "שיחת" -> { num()?.let { sicha = it } }
                "עמוד", "בעמוד", "עמ", "צד", "צפחה" -> {
                    // after a daf: amud a/b. Otherwise: a page number.
                    val nx = tokens.getOrNull(i + 1)
                    val ab = amudOf(nx)
                    if ((daf != null || kind == Ref.Kind.GEMARA) && ab != null) { amud = ab; used.add(i); used.add(i + 1); i++ }
                    else num()?.let { if (daf != null && it in 1..2) amud = it else page = it }
                }
                "עא", "עב" -> { if (kind == Ref.Kind.GEMARA) amud = if (t == "עא") 1 else 2; used.add(i) }
            }
            i++
        }
        // "ברכות כז" / "ברכות כז ע"א" without the word דף
        if (kind == Ref.Kind.GEMARA && daf == null) {
            for (k in tokens.indices) {
                if (k in used) continue
                val r = HebNum.read(tokens, k) ?: continue
                if (r.first in 2..200) { daf = r.first; for (x in 0 until r.second) used.add(k + x); break }
            }
        }
        if (kind == Ref.Kind.GEMARA && daf != null && amud == null) {
            // "דף כז א" / "דף כז ב"
            for (k in tokens.indices) if (k !in used && (tokens[k] == "א" || tokens[k] == "ב" || tokens[k] == "אלף" || tokens[k] == "בית")) {
                amud = if (tokens[k].startsWith("א")) 1 else 2; used.add(k); break
            }
        }
        // "חלק ח' ד'" – people sometimes say the letter ח (for חלק) before the number
        if (volume == null) {
            for (k in 0 until tokens.size - 1) if (k !in used && tokens[k] == "ח") {
                val r = HebNum.read(tokens, k + 1) ?: continue
                if (r.first in 1..39) { volume = r.first; used.add(k); for (x in 0 until r.second) used.add(k + 1 + x); break }
            }
        }
        // ליקוטי שיחות: "חלק ג" may come as "ח"ג" → "חג"
        if (kind == Ref.Kind.SICHOS && volume == null) {
            for (k in tokens.indices) {
                val w = tokens[k]
                if (k !in used && w.length in 2..3 && w[0] == 'ח') HebNum.gematria(w.substring(1))?.let { if (it in 1..39) { volume = it; used.add(k) } }
                if (volume != null) break
            }
            if (volume == null) for (k in tokens.indices) {
                if (k in used) continue
                val r = HebNum.read(tokens, k, allowPlainWord = false) ?: tokens[k].toIntOrNull()?.let { it to 1 } ?: continue
                if (r.first in 1..39) { volume = r.first; for (x in 0 until r.second) used.add(k + x); break }
            }
        }

        // ---- parsha ----
        var parsha: String? = null
        val pList = PARSHIYOS.map { it to it } + PARSHA_ALIASES.entries.map { it.key to it.value }
        for ((sp, name) in pList.sortedByDescending { it.first.length }) {
            if (kind == Ref.Kind.GEMARA) break
            val n = normalize(sp)
            val k = findPhrase(n)
            if (k >= 0 && (0 until n.split(' ').size).none { (k + it) in used }) {
                // short parsha names that are also common words need the word פרשה / a Sichos context
                if (n in setOf("בא", "צו", "ראה", "נח", "שלח", "עקב", "אחרי") && kind != Ref.Kind.SICHOS && !text.contains("פרש")) continue
                parsha = name.replace("״", ""); repeat(n.split(' ').size) { used.add(k + it) }; break
            }
        }

        if (kind == Ref.Kind.GENERIC) {
            val anyNumber = chapter != null || siman != null || page != null || daf != null || volume != null || sicha != null || parsha != null
            if (!anyNumber) return null
        }

        val extra = tokens.filterIndexed { k, w -> k !in used && w !in FILLER && w !in GEMARA_WORDS && w.length >= 2 }
        if (kind == Ref.Kind.GENERIC) {
            if (extra.isEmpty()) return null
            work = extra.joinToString(" ")
            // a known book under any of its names ("שו״ע אדמו״ר הזקן" = שולחן ערוך הרב)
            Works.canonical(work!!)?.let { work = it }
        }
        return Ref(kind, work!!, if (kind == Ref.Kind.GENERIC) emptyList() else extra,
            volume, daf, amud, chapter, siman, seif, sicha, parsha, page)
    }

    private fun looksLikeDafAfter(tokens: List<String>, i: Int): Boolean {
        val r = HebNum.read(tokens, i) ?: return false
        return r.first in 2..180
    }

    private fun amudOf(w: String?): Int? = when (w) {
        null -> null
        "א", "אלף", "ראשון", "1", "a" -> 1
        "ב", "בית", "שני", "2", "b" -> 2
        else -> null
    }
}

/**
 * The names people use for the same book: abbreviations (שו״ע, לקו״ש, אג״ק), the author's title
 * (שולחן ערוך אדמו״ר הזקן = שולחן ערוך הרב), other spellings. Used to understand a request and to find
 * the book in the library under whatever name its file has.
 */
object Works {
    /** canonical name → other names (normalized: no quotes, no nikud) */
    val ALIASES: Map<String, List<String>> = linkedMapOf(
        "שולחן ערוך הרב" to listOf("שולחן ערוך אדמור הזקן", "שולחן ערוך של אדמור הזקן", "שוע אדמור הזקן", "שוע הרב", "שוע אדהז", "שלחן ערוך הרב", "שולחן ערוך אדהז", "אדמור הזקן שולחן ערוך", "שולחן ערוך רבנו הזקן"),
        "ליקוטי שיחות" to listOf("לקוטי שיחות", "לקוש", "ליקוש"),
        "תורת מנחם" to listOf("תומ", "תורת מנחם התוועדויות", "התוועדויות"),
        "אגרות קודש" to listOf("אגרות קדש", "איגרות קודש", "אגק", "אגרות"),
        "ספר המאמרים" to listOf("סהמ", "ספר המאמרים מלוקט", "מאמרים"),
        "תניא" to listOf("ליקוטי אמרים", "לקוטי אמרים", "ספר התניא"),
        "משנה תורה" to listOf("רמבם", "הרמבם", "משנה תורה להרמבם", "יד החזקה"),
        "משנה ברורה" to listOf("מב", "משנב"),
        "ליקוטי תורה" to listOf("לקוטי תורה", "לקות"),
        "תורה אור" to listOf("תוא"),
        "היום יום" to listOf("היום יום"),
        "שולחן ערוך" to listOf("שוע", "שלחן ערוך", "מחבר"),
        "קיצור שולחן ערוך" to listOf("קיצור שוע", "קצשוע", "קיצשוע"),
        "ספר השיחות" to listOf("סהש"),
        "שערי תשובה" to listOf("שערי תשובה"),
        "זוהר" to listOf("זהר", "ספר הזוהר"),
        "חומש" to listOf("תורה", "מקראות גדולות", "חמשה חומשי תורה"),
        "אורח חיים" to listOf("אוח", "או״ח"),
        "יורה דעה" to listOf("יוד", "יו״ד"),
        "אבן העזר" to listOf("אהע", "אה״ע"),
        "חושן משפט" to listOf("חומ", "חו״מ", "חשן משפט"),
    )

    private fun n(s: String) = RefParser.normalize(s)

    /** The canonical name hidden in a request (longest match), with the rest kept: "שוע אדהז אוח" → "שולחן ערוך הרב אורח חיים". */
    fun canonical(text: String): String? {
        var out = " " + n(text) + " "
        var changed = false
        val all = ALIASES.flatMap { (c, l) -> (l + c).map { n(it) to c } }.sortedByDescending { it.first.length }
        val done = HashSet<String>()
        for ((alias, c) in all) {
            if (c in done) continue
            if (out.contains(" $alias ")) {
                // mark the replaced name (§) so a shorter alias inside it is not replaced again
                out = out.replace(" $alias ", " §" + c.replace(' ', '¦') + "§ ")
                changed = true; done.add(c)
            }
        }
        if (!changed) return null
        return out.replace("§", "").replace('¦', ' ').replace(Regex("\\s+"), " ").trim()
    }

    /** Every name a book may carry, for matching file and folder names. */
    fun variants(name: String): List<String> {
        val nn = n(name)
        for ((c, l) in ALIASES) if (n(c) == nn || l.any { n(it) == nn }) return (listOf(c) + l).map(::n).distinct()
        return listOf(nn)
    }
}

/** Finding where a daf / chapter / parsha starts inside a book, from the text of its pages. */
object PageFinder {
    /** Amud number counted from 2a: 2a=0, 2b=1, 3a=2 ... */
    fun amudIndex(daf: Int, amud: Int) = (daf - 2) * 2 + (amud - 1)

    /**
     * Daf marks in the top (or bottom) lines of a page: "ברכות כז.", "דף כז ע"א", "27a", "כז:" etc.
     * Returns the amud indexes found.
     */
    fun dafMarks(pageText: String, masechet: String?): List<Int> {
        val out = ArrayList<Int>()
        val head = edge(pageText)
        // Latin "27a" / "27b" (Schottenstein / Artscroll)
        Regex("\\b(\\d{1,3})\\s?([ab])\\b").findAll(head).forEach { m ->
            val d = m.groupValues[1].toInt(); if (d in 2..180) out.add(amudIndex(d, if (m.groupValues[2] == "a") 1 else 2))
        }
        val t = RefParser.normalize(head)
        val ws = t.split(' ')
        for (k in ws.indices) {
            val w = ws[k]
            val afterMas = masechet != null && k > 0 && (ws[k - 1] == masechet.substringAfterLast(' '))
            if (w == "דף" || afterMas) {
                val idx = if (w == "דף") k + 1 else k
                val word = ws.getOrNull(idx) ?: continue
                val d = HebNum.gematria(word) ?: word.toIntOrNull() ?: continue
                if (d !in 2..180) continue
                val next = ws.getOrNull(idx + 1)
                val a = when (next) { "עא" -> 1; "עב" -> 2; else -> 0 }
                if (a == 0) { out.add(amudIndex(d, 1)); out.add(amudIndex(d, 2)) } else out.add(amudIndex(d, a))
            }
        }
        return out
    }

    private fun edge(s: String): String = if (s.length <= 360) s else s.substring(0, 180) + "\n" + s.substring(s.length - 180)

    /**
     * Given anchors (pdf page → amud index) found in a book, estimate the page of the wanted amud.
     * Keeps only anchors that agree with each other (pages and amudim must both go forward).
     */
    fun estimate(anchors: List<Pair<Int, Int>>, target: Int, pageCount: Int): Int? {
        if (anchors.isEmpty()) return null
        // longest chain where page and amud both increase
        val a = anchors.sortedWith(compareBy({ it.first }, { it.second }))
        val n = a.size
        val len = IntArray(n) { 1 }; val prev = IntArray(n) { -1 }
        for (i in 0 until n) for (j in 0 until i) {
            if (a[j].first < a[i].first && a[j].second < a[i].second && (a[i].second - a[j].second) <= (a[i].first - a[j].first) * 2 + 2 && len[j] + 1 > len[i]) {
                len[i] = len[j] + 1; prev[i] = j
            }
        }
        var best = (0 until n).maxByOrNull { len[it] } ?: return null
        val chain = ArrayList<Pair<Int, Int>>()
        while (best >= 0) { chain.add(a[best]); best = prev[best] }
        chain.reverse()
        if (chain.size < 2) {
            val (p, x) = chain[0]
            return (p + (target - x)).coerceIn(1, pageCount)
        }
        // exact hit
        chain.firstOrNull { it.second == target }?.let { return it.first }
        val lo = chain.lastOrNull { it.second < target }
        val hi = chain.firstOrNull { it.second > target }
        val (p0, x0, p1, x1) = when {
            lo != null && hi != null -> listOf(lo.first, lo.second, hi.first, hi.second)
            hi == null -> { val l = chain.takeLast(2); listOf(l[0].first, l[0].second, l[1].first, l[1].second) }
            else -> { val l = chain.take(2); listOf(l[0].first, l[0].second, l[1].first, l[1].second) }
        }
        val rate = (p1 - p0).toDouble() / (x1 - x0).coerceAtLeast(1)
        return (p0 + Math.round((target - x0) * rate).toInt()).coerceIn(1, pageCount)
    }

    /** Is this page the start of a section called [name] (parsha, chapter...)? Looks at the top lines. */
    fun headerHas(pageText: String, words: List<String>): Boolean {
        val head = RefParser.normalize(pageText.take(220))
        val hw = head.split(' ').toSet()
        return words.all { w -> w in hw || hw.any { it.length > 2 && (it == "ב$w" || it == "ל$w" || it == "ו$w") } }
    }

    /** "פרק ה" / "סימן קכג" / "שיחה ב" at the top of a page. */
    fun hasNumbered(pageText: String, keyword: String, n: Int): Boolean {
        val head = RefParser.normalize(pageText.take(260)).split(' ')
        for (k in 0 until head.size - 1) if (head[k] == keyword || head[k] == "ה$keyword") {
            val v = HebNum.gematria(head[k + 1]) ?: head[k + 1].toIntOrNull()
            if (v == n) return true
        }
        return false
    }

    /**
     * The printed page number of a page, from its first or last line ("58", "נח", "- 58 -").
     * Returns all candidate numbers (the caller keeps the ones that agree with neighbouring pages).
     */
    fun printedNumbers(pageText: String): List<Int> {
        val out = ArrayList<Int>()
        val lines = pageText.trim().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return out
        for (line in listOf(lines.first(), lines.last()) + (if (lines.size > 1) listOf(lines[1], lines[lines.size - 2]) else emptyList())) {
            val ws = RefParser.normalize(line).split(' ').filter { it.isNotEmpty() }
            for (w in listOf(ws.firstOrNull(), ws.lastOrNull())) {
                if (w == null) continue
                w.toIntOrNull()?.let { if (it in 1..2000) out.add(it) }
                if (w.length in 1..4 && line.length < 80) HebNum.gematria(w)?.let { if (it in 1..999) out.add(it) }
            }
        }
        return out.distinct()
    }

    /** PDF page of a printed page number, from the numbers printed on the pages of the book. */
    fun pageOfPrinted(pages: Map<Int, String>, printed: Int, pageCount: Int): Pair<Int, Boolean> {
        val anchors = ArrayList<Pair<Int, Int>>()
        for ((p, t) in pages) for (n in printedNumbers(t)) {
            // keep numbers that fit "printed ≈ pdf page - offset" for some plausible offset
            if (n <= p + 40 && n >= p - 1500) anchors.add(p to n)
        }
        // the most common offset (pdf page − printed page) is the book's numbering
        val offsets = anchors.groupingBy { it.first - it.second }.eachCount()
        val best = offsets.maxByOrNull { it.value }
        if (best != null && best.value >= 3) {
            val exact = anchors.firstOrNull { it.second == printed && it.first - it.second == best.key }
            if (exact != null) return exact.first to true
            // offsets can change (plates, two numberings): take the anchors near the wanted page
            val near = anchors.filter { (pp, n) -> kotlin.math.abs(n - printed) < 60 && offsets[pp - n]!! >= 2 }
            val local = near.groupingBy { it.first - it.second }.eachCount().maxByOrNull { it.value }?.key ?: best.key
            return (printed + local).coerceIn(1, pageCount) to true
        }
        return printed.coerceIn(1, pageCount) to false
    }

    /** How well a book file matches a wanted volume (חלק ג / 3 / ג׳ in the name). */
    fun volumeOf(name: String): Int? {
        val n = RefParser.normalize(name.replace('_', ' ').replace('.', ' '))
        Regex("(?:^|\\s)(?:חלק|כרך|ח)\\s*([א-ת]{1,2}|\\d{1,2})(?:\\s|$)").findAll(n).forEach { m ->
            val g = m.groupValues[1]; (g.toIntOrNull() ?: HebNum.gematria(g))?.let { return it }
        }
        Regex("(?:^|\\s)(\\d{1,2})(?:\\s|$)").find(n)?.let { return it.groupValues[1].toInt() }
        // "לקוטי שיחות ג" – a lone letter number at the end
        Regex("\\s([א-ת]{1,2})$").find(n)?.let { m -> HebNum.gematria(m.groupValues[1])?.let { if (it <= 39) return it } }
        return null
    }
}
```

## `core/src/main/kotlin/com/chdv/searchplus/core/Json.kt`

```kotlin
package com.chdv.searchplus.core

import java.io.Reader

/** A very small JSON reader (objects → Map, arrays → List, numbers → Double). Enough for tokenizer files. */
class Json private constructor(private val r: Reader) {
    private var ch = -2

    private fun peek(): Int { if (ch == -2) ch = r.read(); return ch }
    private fun next(): Int { val c = peek(); ch = -2; return c }
    private fun ws() { while (true) { val c = peek(); if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) next() else return } }
    private fun expect(c: Char) { ws(); val x = next(); if (x != c.code) throw IllegalStateException("JSON: expected $c got ${if (x < 0) "EOF" else x.toChar().toString()}") }

    fun value(): Any? {
        ws()
        return when (val c = peek()) {
            '{'.code -> obj()
            '['.code -> arr()
            '"'.code -> str()
            't'.code -> { lit("true"); true }
            'f'.code -> { lit("false"); false }
            'n'.code -> { lit("null"); null }
            else -> if (c == '-'.code || (c >= '0'.code && c <= '9'.code)) num() else throw IllegalStateException("JSON: bad char ${c.toChar()}")
        }
    }

    private fun lit(s: String) { for (c in s) if (next() != c.code) throw IllegalStateException("JSON: bad literal") }

    private fun num(): Double {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || !(c.toChar().isDigit() || c == '-'.code || c == '+'.code || c == '.'.code || c == 'e'.code || c == 'E'.code)) break
            sb.append(next().toChar())
        }
        return sb.toString().toDouble()
    }

    fun str(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = next()
            if (c < 0) throw IllegalStateException("JSON: EOF in string")
            if (c == '"'.code) return sb.toString()
            if (c == '\\'.code) {
                when (val e = next().toChar()) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> { val h = CharArray(4) { next().toChar() }; sb.append(String(h).toInt(16).toChar()) }
                    else -> sb.append(e)
                }
            } else sb.append(c.toChar())
        }
    }

    private fun obj(): Map<String, Any?> {
        expect('{')
        val m = LinkedHashMap<String, Any?>()
        ws(); if (peek() == '}'.code) { next(); return m }
        while (true) {
            ws(); val k = str(); expect(':'); m[k] = value(); ws()
            when (next()) { ','.code -> continue; '}'.code -> return m; else -> throw IllegalStateException("JSON: bad object") }
        }
    }

    private fun arr(): List<Any?> {
        expect('[')
        val l = ArrayList<Any?>()
        ws(); if (peek() == ']'.code) { next(); return l }
        while (true) {
            l.add(value()); ws()
            when (next()) { ','.code -> continue; ']'.code -> return l; else -> throw IllegalStateException("JSON: bad array") }
        }
    }

    /** Skip to the value of the first key named [key] (at any depth), reading as little as possible. */
    private fun seek(key: String): Boolean {
        val target = "\"$key\""
        val win = StringBuilder()
        var inStr = false; var esc = false
        while (true) {
            val c = next(); if (c < 0) return false
            val x = c.toChar()
            if (inStr) {
                if (esc) esc = false else if (x == '\\') esc = true else if (x == '"') inStr = false
            } else if (x == '"') inStr = true
            win.append(x); if (win.length > target.length + 4) win.deleteCharAt(0)
            if (!inStr && win.endsWith(target)) { ws(); if (peek() == ':'.code) { next(); return true } }
        }
    }

    companion object {
        fun parse(r: Reader): Any? = Json(r).value()

        /**
         * Stream the "vocab" array of a SentencePiece tokenizer.json ([[piece, score], ...]) without
         * loading the whole 17MB file into memory. Also reports "unk_id" if it comes first.
         */
        fun streamUnigramVocab(r: Reader, onPiece: (String, Float) -> Unit): Int {
            val j = Json(r)
            if (!j.seek("vocab")) throw IllegalStateException("tokenizer.json: no vocab")
            j.expect('[')
            j.ws()
            if (j.peek() == ']'.code) return 0
            var n = 0
            while (true) {
                j.expect('['); j.ws()
                val p = j.str(); j.expect(',')
                val s = (j.value() as Number).toFloat()
                j.ws(); while (j.peek() == ','.code) { j.next(); j.value(); j.ws() }
                j.expect(']')
                onPiece(p, s); n++
                j.ws()
                when (j.next()) { ','.code -> continue; ']'.code -> return n; else -> throw IllegalStateException("JSON: bad vocab") }
            }
        }
    }
}
```

## `core/src/main/kotlin/com/chdv/searchplus/core/Names.kt`

```kotlin
package com.chdv.searchplus.core

/**
 * Finding books by the names they have on the device – file names and folder names, with
 * abbreviations written out and spelling variants allowed. Pure Kotlin (tested without Android).
 * Built once from the book list (fast: no page reading), then every request is a quick scan of it.
 */
class Names(books: List<Item>) {
    class Item(val id: Long, val title: String, val folders: List<String>)
    class Hit(val id: Long, val score: Float)

    private class Entry(val item: Item, val title: Set<String>, val folders: Set<String>, val keys: Set<String>,
                        val titleText: String, val allText: String, val volume: Int?)

    private val entries: List<Entry> = books.map { b ->
        val tw = words(b.title)
        val fw = b.folders.flatMap { words(it) }.toSet()
        val volume = PageFinder.volumeOf(b.title) ?: b.folders.lastOrNull()?.let { PageFinder.volumeOf(it) }
        Entry(b, tw, fw, (tw + fw).map(::key).filter { it.length >= 2 }.toSet(),
            " " + tw.joinToString(" ") + " ", " " + (tw + fw).joinToString(" ") + " ", volume)
    }

    val size: Int get() = entries.size

    companion object {
        /** Words that say where in a book (handled by the page finder), or are just talk. */
        val PLACE = setOf("דף", "עמוד", "עמ", "חלק", "כרך", "פרק", "סימן", "סעיף", "שיחה", "שיחת", "פרשת", "פרשה", "לפרשת", "בפרשת",
            "עא", "עב", "תפתח", "פתח", "תביא", "חפש", "תחפש", "ספר", "את", "של", "לי", "מתוך", "בספר", "במהדורת", "מהדורת", "מסכת", "גמרא")

        fun key(w: String) = w.filter { it !in "אהויע" }.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ')

        /** Normalized words of a name, also with its abbreviations written out. */
        fun words(name: String): Set<String> {
            val t = name.replace('_', ' ').replace('-', ' ').replace('.', ' ').replace(Regex("(?i)\\bpdf\\b"), " ")
            val out = LinkedHashSet<String>()
            for (s in listOf(t, Abbr.expand(t))) RefParser.normalize(s).split(' ').filter { it.length >= 2 }.forEach { out.add(it) }
            return out
        }

        private fun isNumber(w: String) = w.toIntOrNull() != null || (w.length <= 3 && HebNum.gematria(w) != null)
    }

    private fun hit(w: String, e: Entry): Float {
        if (w in e.title) return 3f
        if (w in e.folders) return 2.2f
        if (w.length > 3 && w[0] in "בהוכלמש" && w.substring(1).let { it in e.title || it in e.folders }) return 2f
        val k = key(w)
        // spelling variants (ליקוטי / לקוטי, שולחן / שלחן) count almost as much as the same spelling
        if (k.length >= 2 && k in e.keys) return 2.6f
        if (w.length >= 3 && (e.title.any { it.startsWith(w) } || e.folders.any { it.startsWith(w) })) return 1f
        return 0f
    }

    /**
     * Books for a request. [ref] (if the request was understood as a place) narrows it:
     * Gemara must carry the masechet's name, Likkutei Sichos the right volume.
     */
    fun find(request: String, ref: Ref? = null, max: Int = 10): List<Hit> {
        val base = LinkedHashSet<String>()
        base.addAll(words(request))
        // the understood work name and its other names count too ("שו״ע אדה״ז" → שולחן ערוך הרב)
        ref?.let { r -> Works.variants(r.work).forEach { v -> base.addAll(words(v)) }; base.addAll(words(r.work)) }
        val ws = base.filter { it !in PLACE && !isNumber(it) && (ref?.parsha == null || it != RefParser.normalize(ref.parsha)) }
        val reqWords = words(request).filter { it !in PLACE && !isNumber(it) }
        val out = ArrayList<Hit>()
        for (e in entries) {
            when (ref?.kind) {
                Ref.Kind.GEMARA -> {
                    val m = RefParser.normalize(ref.work)
                    if (!e.allText.contains(" $m ") && !e.allText.contains(" ב$m ")) continue
                    // a longer masechet name containing this one is another masechet
                    if (RefParser.MASECHTOT.any { it != ref.work && it.contains(ref.work) && e.allText.contains(" " + RefParser.normalize(it) + " ") }) continue
                }
                Ref.Kind.SICHOS -> if (!(e.allText.contains("שיחות") || e.allText.contains("לקוש") || e.allText.contains("ליקוש"))) continue
                else -> {}
            }
            var score = 0f; var covered = 0
            for (w in reqWords) { val h = hit(w, e); if (h > 0) { score += h; covered++ } }
            var extra = 0f
            for (w in ws) if (w !in reqWords) { val h = hit(w, e); if (h > 0) extra += h * 0.6f }
            if (ref?.kind != Ref.Kind.GEMARA && ref?.kind != Ref.Kind.SICHOS) {
                // a book by name: most of what was asked must be in its names
                val need = if (reqWords.isEmpty()) 1 else maxOf(1, (reqWords.size * 6 + 9) / 10)
                if (covered < need && extra < 2f) continue
            }
            score += extra
            if (ref?.kind == Ref.Kind.GEMARA) {
                score += 10f
                if (e.allText.contains(" ירושלמי ") || e.allText.contains(" משניות ") || e.titleText.contains(" משנה ")) score -= 8f
            }
            if (ref?.volume != null) {
                if (e.volume == ref.volume) score += 12f else if (e.volume != null) { if (ref.kind == Ref.Kind.SICHOS) continue else score -= 6f }
            }
            if (score <= 0f) continue
            out.add(Hit(e.item.id, score))
        }
        // equal scores: the plainer (shorter) name first
        val len = entries.associate { it.item.id to it.item.title.length }
        return out.sortedWith(compareByDescending<Hit> { Math.round(it.score * 10) }.thenBy { len[it.id] ?: 0 }).take(max)
    }
}
```

## `core/src/main/kotlin/com/chdv/searchplus/core/Whisper.kt`

```kotlin
package com.chdv.searchplus.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/** Log-Mel spectrogram exactly as Whisper expects it (16kHz, 25ms window, 10ms hop, 30 seconds). */
object WhisperMel {
    const val SR = 16000
    const val NFFT = 400
    const val HOP = 160
    const val FRAMES = 3000
    const val SAMPLES = SR * 30
    private const val BINS = NFFT / 2 + 1

    private fun hzToMel(f: Double): Double {
        val fsp = 200.0 / 3; val minLogHz = 1000.0; val minLogMel = minLogHz / fsp; val logstep = ln(6.4) / 27.0
        return if (f >= minLogHz) minLogMel + ln(f / minLogHz) / logstep else f / fsp
    }

    private fun melToHz(m: Double): Double {
        val fsp = 200.0 / 3; val minLogHz = 1000.0; val minLogMel = minLogHz / fsp; val logstep = ln(6.4) / 27.0
        return if (m >= minLogMel) minLogHz * kotlin.math.exp(logstep * (m - minLogMel)) else fsp * m
    }

    /** librosa.filters.mel(sr=16000, n_fft=400, n_mels=n, norm="slaney") */
    fun filters(nMels: Int): Array<FloatArray> {
        val fft = DoubleArray(BINS) { it * (SR / 2.0) / (BINS - 1) }
        val lo = hzToMel(0.0); val hi = hzToMel(SR / 2.0)
        val melF = DoubleArray(nMels + 2) { melToHz(lo + (hi - lo) * it / (nMels + 1)) }
        return Array(nMels) { i ->
            val enorm = 2.0 / (melF[i + 2] - melF[i])
            FloatArray(BINS) { k ->
                val lower = (fft[k] - melF[i]) / (melF[i + 1] - melF[i])
                val upper = (melF[i + 2] - fft[k]) / (melF[i + 2] - melF[i + 1])
                (max(0.0, minOf(lower, upper)) * enorm).toFloat()
            }
        }
    }

    private val cosT by lazy { Array(BINS) { k -> FloatArray(NFFT) { n -> cos(2 * PI * k * n / NFFT).toFloat() } } }
    private val sinT by lazy { Array(BINS) { k -> FloatArray(NFFT) { n -> sin(2 * PI * k * n / NFFT).toFloat() } } }
    private val window by lazy { FloatArray(NFFT) { (0.5 - 0.5 * cos(2 * PI * it / NFFT)).toFloat() } }
    private val melCache = HashMap<Int, Array<FloatArray>>()

    /** Returns [nMels * 3000] (row = mel band). Audio: mono 16kHz floats in -1..1, at most 30 seconds are used. */
    fun logMel(audio: FloatArray, nMels: Int = 80): FloatArray {
        val n = SAMPLES
        val len = minOf(audio.size, n)
        val pad = NFFT / 2
        // x = audio padded/trimmed to 30s, then reflect-padded by 200 on both sides (torch.stft center=True)
        fun x(i: Int): Float = if (i < len) audio[i] else 0f
        fun padded(p: Int): Float {
            val i = p - pad
            return when {
                i < 0 -> x(-i)
                i >= n -> x(2 * n - 2 - i)
                else -> x(i)
            }
        }
        val mel = synchronized(melCache) { melCache.getOrPut(nMels) { filters(nMels) } }
        val out = FloatArray(nMels * FRAMES)
        val frame = FloatArray(NFFT)
        val power = FloatArray(BINS)
        val zeroMel = log10(1e-10).toFloat()
        var mx = Float.NEGATIVE_INFINITY
        for (t in 0 until FRAMES) {
            var any = false
            val start = t * HOP
            for (k in 0 until NFFT) { val v = padded(start + k) * window[k]; frame[k] = v; if (v != 0f) any = true }
            if (!any) {
                for (m in 0 until nMels) out[m * FRAMES + t] = zeroMel
                mx = max(mx, zeroMel)
                continue
            }
            for (b in 0 until BINS) {
                val c = cosT[b]; val s = sinT[b]
                var re = 0f; var im = 0f
                for (k in 0 until NFFT) { val v = frame[k]; re += v * c[k]; im += v * s[k] }
                power[b] = re * re + im * im
            }
            for (m in 0 until nMels) {
                val f = mel[m]
                var e = 0f
                for (b in 0 until BINS) e += f[b] * power[b]
                val l = log10(max(e, 1e-10f))
                out[m * FRAMES + t] = l
                if (l > mx) mx = l
            }
        }
        val floor = mx - 8f
        for (i in out.indices) out[i] = (max(out[i], floor) + 4f) / 4f
        return out
    }
}

/** Byte-level BPE tokenizer of Whisper, read from the Hugging Face tokenizer.json. */
class WhisperTokenizer(tokenizerJson: File) {
    private val vocab = HashMap<String, Int>(60_000)
    private val idToTok = HashMap<Int, String>(60_000)
    private val ranks = HashMap<String, Int>(60_000)
    val special = HashMap<String, Int>()

    init {
        @Suppress("UNCHECKED_CAST")
        val root = tokenizerJson.bufferedReader(Charsets.UTF_8).use { Json.parse(it) } as Map<String, Any?>
        val model = root["model"] as Map<String, Any?>
        (model["vocab"] as Map<String, Any?>).forEach { (k, v) -> val id = (v as Number).toInt(); vocab[k] = id; idToTok[id] = k }
        (model["merges"] as List<Any?>).forEachIndexed { i, m ->
            val key = when (m) { is String -> m; is List<*> -> "${m[0]} ${m[1]}"; else -> return@forEachIndexed }
            ranks[key] = i
        }
        (root["added_tokens"] as? List<Any?>)?.forEach { t ->
            val o = t as Map<String, Any?>
            val id = (o["id"] as Number).toInt(); val c = o["content"] as String
            special[c] = id; idToTok[id] = c
        }
    }

    fun id(s: String): Int = special[s] ?: vocab[s] ?: throw IllegalStateException("no token $s")
    val eot: Int get() = id("<|endoftext|>")

    private val byteEnc: CharArray
    private val byteDec = HashMap<Char, Int>()
    init {
        val bs = ArrayList<Int>()
        bs.addAll('!'.code..'~'.code); bs.addAll('¡'.code..'¬'.code); bs.addAll('®'.code..'ÿ'.code)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        byteEnc = CharArray(256)
        for (i in bs.indices) { byteEnc[bs[i]] = cs[i].toChar(); byteDec[cs[i].toChar()] = bs[i] }
    }

    fun decode(ids: List<Int>): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id >= eot) continue
            val t = idToTok[id] ?: continue
            for (c in t) { val b = byteDec[c]; if (b != null) bytes.write(b) }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private val pat = Regex("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+")

    fun encode(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (m in pat.findAll(text)) {
            val word = m.value.toByteArray(Charsets.UTF_8).map { byteEnc[it.toInt() and 0xFF].toString() }.toMutableList()
            while (word.size > 1) {
                var best = -1; var bestRank = Int.MAX_VALUE
                for (i in 0 until word.size - 1) {
                    val r = ranks[word[i] + " " + word[i + 1]] ?: continue
                    if (r < bestRank) { bestRank = r; best = i }
                }
                if (best < 0) break
                word[best] = word[best] + word[best + 1]
                word.removeAt(best + 1)
            }
            for (p in word) out.add(vocab[p] ?: continue)
        }
        return out
    }
}

/**
 * Whisper speech recognition with ONNX Runtime: encoder + merged decoder (with key/value cache).
 * Works with the Xenova / onnx-community exports (encoder_model*.onnx, decoder_model_merged*.onnx).
 */
class Whisper(
    private val env: OrtEnvironment,
    encoderPath: String,
    decoderPath: String,
    val tok: WhisperTokenizer,
    threads: Int = 4,
    /** BASIC uses less memory while loading (weak devices); EXTENDED runs a little faster. */
    optLevel: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.EXTENDED_OPT,
    /**
     * For devices with little memory (2GB): the two halves of the model are never in memory together –
     * the encoder is loaded, run and freed, then the decoder. Slower (it loads each time), but the
     * peak memory is about half.
     */
    private val lowMemory: Boolean = false,
) : AutoCloseable {
    private val encPath = encoderPath
    private val decPath = decoderPath
    private val opts: () -> OrtSession.SessionOptions = {
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(optLevel)
            if (lowMemory) setCPUArenaAllocator(false)
        }
    }
    private var encS: OrtSession? = null
    private var decS: OrtSession? = null
    private val enc: OrtSession get() = encS ?: env.createSession(encPath, opts()).also { encS = it }
    private val dec: OrtSession get() = decS ?: env.createSession(decPath, opts()).also { decS = it }
    private var melsKnown = 0
    val nMels: Int get() {
        if (melsKnown == 0) {
            val shape = (enc.inputInfo.values.first().info as TensorInfo).shape
            melsKnown = if (shape.size == 3 && shape[1] > 0) shape[1].toInt() else 80
        }
        return melsKnown
    }

    init {
        if (!lowMemory) { enc; try { dec } catch (e: Throwable) { encS?.close(); throw e } }
    }

    /** Timings of the last run, for tests and the status line. */
    var lastMelMs = 0L; var lastEncMs = 0L; var lastDecMs = 0L

    fun transcribe(audio: FloatArray, language: String = "he", prompt: String? = null, maxTokens: Int = 64, cancelled: () -> Boolean = { false }): String {
        var t0 = System.currentTimeMillis()
        val mel = WhisperMel.logMel(normalize(audio), nMels)
        lastMelMs = System.currentTimeMillis() - t0
        if (cancelled()) return ""

        t0 = System.currentTimeMillis()
        val encIn = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), longArrayOf(1, nMels.toLong(), WhisperMel.FRAMES.toLong()))
        val encRes = try { enc.run(mapOf(enc.inputNames.first() to encIn)) } finally { encIn.close() }
        lastEncMs = System.currentTimeMillis() - t0
        t0 = System.currentTimeMillis()
        var own: OnnxTensor? = null
        if (lowMemory) {
            // keep only the encoder's answer (a few MB), free the encoder itself before loading the decoder
            val h = encRes.get(0) as OnnxTensor
            val shape = h.info.shape
            val copy = h.floatBuffer
            own = OnnxTensor.createTensor(env, copy, shape)
            encRes.close()
            encS?.close(); encS = null
            System.gc()
        }
        try {
            val hidden = own ?: (encRes.get(0) as OnnxTensor)
            val prefix = ArrayList<Int>()
            if (!prompt.isNullOrBlank()) {
                prefix.add(tok.id("<|startofprev|>"))
                prefix.addAll(tok.encode(" " + prompt.trim()).takeLast(120))
            }
            prefix.add(tok.id("<|startoftranscript|>"))
            prefix.add(tok.id("<|$language|>"))
            prefix.add(tok.id("<|transcribe|>"))
            prefix.add(tok.id("<|notimestamps|>"))
            val out = decode(hidden, prefix, maxTokens, cancelled)
            return tok.decode(out).trim()
        } finally {
            if (own == null) encRes.close() else { own.close(); decS?.close(); decS = null }
            lastDecMs = System.currentTimeMillis() - t0
        }
    }

    private fun decode(hidden: OnnxTensor, prefix: List<Int>, maxTokens: Int, cancelled: () -> Boolean): List<Int> {
        val eot = tok.eot
        val inputs = dec.inputNames
        val pastNames = inputs.filter { it.startsWith("past_key_values.") }
        val empties = ArrayList<OnnxTensor>()
        val emptyPast = HashMap<String, OnnxTensor>()
        for (name in pastNames) {
            val s = (dec.inputInfo[name]!!.info as TensorInfo).shape
            val heads = if (s.size == 4 && s[1] > 0) s[1] else 8
            val dim = if (s.size == 4 && s[3] > 0) s[3] else 64
            OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, heads, 0, dim)).also { empties.add(it); emptyPast[name] = it }
        }
        val generated = ArrayList<Int>()
        var first: OrtSession.Result? = null
        var prev: OrtSession.Result? = null
        val small = ArrayList<OnnxTensor>()
        try {
            var ids: List<Int> = prefix
            for (step in 0 until maxTokens) {
                if (cancelled()) break
                val feed = HashMap<String, OnnxTensor>()
                val idT = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, ids.size.toLong()))
                small.add(idT)
                feed["input_ids"] = idT
                if ("encoder_hidden_states" in inputs) feed["encoder_hidden_states"] = hidden
                if ("use_cache_branch" in inputs) OnnxTensor.createTensor(env, booleanArrayOf(step > 0)).also { small.add(it); feed["use_cache_branch"] = it }
                for (name in pastNames) {
                    feed[name] = if (step == 0) emptyPast[name]!! else {
                        val pres = name.replace("past_key_values", "present")
                        // the cross-attention (encoder) cache comes from the first step; self-attention from the last one
                        val src = if (".encoder." in name) first!! else prev!!
                        (src.get(pres).orElse(null) as? OnnxTensor) ?: emptyPast[name]!!
                    }
                }
                val res = dec.run(feed)
                val logits = res.get("logits").orElse(res.get(0)) as OnnxTensor
                val shape = logits.info.shape
                val vocab = shape[2].toInt()
                val buf = logits.floatBuffer
                val base = (shape[1].toInt() - 1) * vocab
                var best = -1; var bestV = Float.NEGATIVE_INFINITY
                for (v in 0 until minOf(vocab, eot + 1)) {
                    if (step == 0 && (v == eot || v == 220)) continue
                    val x = buf.get(base + v)
                    if (x > bestV) { bestV = x; best = v }
                }
                if (step == 0) first = res else { if (prev !== first) prev?.close() }
                prev = res
                small.forEach { it.close() }; small.clear()
                if (best < 0 || best == eot) break
                generated.add(best)
                // stop on a loop of the same token
                if (generated.size >= 6 && generated.takeLast(6).toSet().size == 1) break
                ids = listOf(best)
            }
        } finally {
            small.forEach { it.close() }
            if (prev !== first) prev?.close()
            first?.close()
            empties.forEach { it.close() }
        }
        return generated
    }

    override fun close() {
        try { encS?.close() } catch (e: Exception) {}
        try { decS?.close() } catch (e: Exception) {}
        encS = null; decS = null
    }


    companion object {
        /** A quiet microphone (common on E-ink readers) is brought to a normal level; silence stays silence. */
        fun normalize(audio: FloatArray): FloatArray {
            var peak = 0f
            for (x in audio) { val v = kotlin.math.abs(x); if (v > peak) peak = v }
            // every recording is brought to the same level (tests: clearer numbers like כ״ז at this level)
            if (peak < 0.003f) return audio
            val g = minOf(0.6f / peak, 40f)
            return FloatArray(audio.size) { audio[it] * g }
        }

        /** A hint that teaches the recognizer how the names of books are written. */
        const val PROMPT = "מסכת ברכות דף כ״ז עמוד א׳. ליקוטי שיחות חלק ג׳ פרשת ויצא. תניא פרק ה׳. רמב״ם. שולחן ערוך. גמרא שוטנשטיין."
    }
}

/** SentencePiece (Unigram) tokenizer of multilingual-e5, as in XLM-RoBERTa. */
class E5Tokenizer private constructor(private val vocab: HashMap<String, Int>, private val scores: FloatArray, unk: Int) {
    private val unkId = unk
    private val bosId = vocab["<s>"] ?: 0
    private val eosId = vocab["</s>"] ?: 2
    private val maxPiece: Int
    private val unkScore: Float

    init {
        var mx = 1; var mn = 0f
        for ((p, id) in vocab) { if (p.length > mx) mx = p.length; if (scores[id] < mn) mn = scores[id] }
        maxPiece = minOf(mx, 24); unkScore = mn - 10f
    }

    fun tokenize(text: String, maxLen: Int): LongArray {
        val norm = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()
        val ids = ArrayList<Int>()
        ids.add(bosId)
        for (word in norm.split(' ')) {
            if (word.isEmpty()) continue
            val w = "▁$word"
            val n = w.length
            val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
            val from = IntArray(n + 1)
            val tok = IntArray(n + 1)
            best[0] = 0f
            for (i in 0 until n) {
                if (best[i] == Float.NEGATIVE_INFINITY) continue
                var single = false
                for (len in 1..minOf(maxPiece, n - i)) {
                    val id = vocab[w.substring(i, i + len)] ?: continue
                    if (len == 1) single = true
                    val s = best[i] + scores[id]
                    if (s > best[i + len]) { best[i + len] = s; from[i + len] = i; tok[i + len] = id }
                }
                if (!single) {
                    val s = best[i] + unkScore
                    if (s > best[i + 1]) { best[i + 1] = s; from[i + 1] = i; tok[i + 1] = unkId }
                }
            }
            val wordIds = ArrayList<Int>()
            var k = n
            while (k > 0) { wordIds.add(tok[k]); k = from[k] }
            for (j in wordIds.indices.reversed()) {
                val id = wordIds[j]
                if (id == unkId && ids.isNotEmpty() && ids.last() == unkId) continue
                ids.add(id)
            }
            if (ids.size >= maxLen - 1) break
        }
        while (ids.size > maxLen - 1) ids.removeAt(ids.size - 1)
        ids.add(eosId)
        return LongArray(ids.size) { ids[it].toLong() }
    }

    companion object {
        /** Turn the big tokenizer.json into a compact binary file (read with [load]). */
        fun compile(tokenizerJson: File, out: File) {
            val pieces = ArrayList<String>(260_000)
            val sc = ArrayList<Float>(260_000)
            tokenizerJson.bufferedReader(Charsets.UTF_8).use { r -> Json.streamUnigramVocab(r) { p, s -> pieces.add(p); sc.add(s) } }
            if (pieces.size < 1000) throw IllegalStateException("tokenizer.json: vocabulary too small (${pieces.size})")
            val tmp = File(out.parentFile, out.name + ".tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 16)).use { o ->
                o.writeInt(pieces.size); o.writeInt(pieces.indexOf("<unk>").let { if (it < 0) 3 else it })
                for (i in pieces.indices) { o.writeUTF(pieces[i]); o.writeFloat(sc[i]) }
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw java.io.IOException("rename failed")
        }

        fun load(bin: File): E5Tokenizer =
            java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(bin), 1 shl 16)).use { i ->
                val n = i.readInt(); val unk = i.readInt()
                val m = HashMap<String, Int>(n * 2)
                val s = FloatArray(n)
                for (k in 0 until n) { m[i.readUTF()] = k; s[k] = i.readFloat() }
                E5Tokenizer(m, s, unk)
            }
    }
}

/** Runs the e5 model on a piece of text and returns a normalized 384-number vector. */
class E5Model(private val env: OrtEnvironment, modelPath: String, val tok: E5Tokenizer, threads: Int = 4,
              /** true for models that use the first token as the sentence vector (bge-m3); false = average (e5). */
              private val cls: Boolean = false, lowMemory: Boolean = false) : AutoCloseable {
    private val s: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
        if (lowMemory) { setCPUArenaAllocator(false); setMemoryPatternOptimization(false) }
    })
    private val needsTypes = s.inputNames.contains("token_type_ids")

    fun embed(text: String, maxLen: Int = 192): FloatArray {
        val ids = tok.tokenize(text, maxLen)
        val n = ids.size
        val shape = longArrayOf(1, n.toLong())
        val tensors = ArrayList<OnnxValue>()
        try {
            val inputs = HashMap<String, OnnxTensor>()
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).also { tensors.add(it); inputs["input_ids"] = it }
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 1L }), shape).also { tensors.add(it); inputs["attention_mask"] = it }
            if (needsTypes) OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n)), shape).also { tensors.add(it); inputs["token_type_ids"] = it }
            s.run(inputs).use { r ->
                val out = r.get(0) as OnnxTensor
                val sh = out.info.shape
                val fb = out.floatBuffer
                val dim = sh[sh.size - 1].toInt()
                val v = FloatArray(dim)
                if (sh.size == 2) { for (d in 0 until dim) v[d] = fb.get(d) } // already pooled
                else if (cls) { for (d in 0 until dim) v[d] = fb.get(d) } // first token
                else {
                    val rows = sh[1].toInt()
                    for (row in 0 until rows) for (d in 0 until dim) v[d] += fb.get(row * dim + d)
                    for (d in 0 until dim) v[d] /= rows
                }
                var norm = 0f
                for (d in 0 until dim) norm += v[d] * v[d]
                norm = kotlin.math.sqrt(norm).coerceAtLeast(1e-9f)
                for (d in 0 until dim) v[d] /= norm
                return v
            }
        } finally { tensors.forEach { it.close() } }
    }

    override fun close() { try { s.close() } catch (e: Exception) {} }
}
```

## `core/src/test/kotlin/com/chdv/searchplus/core/HebRefTest.kt`

```kotlin
package com.chdv.searchplus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HebRefTest {
    @Test fun gematria() {
        assertEquals(27, HebNum.gematria("כז"))
        assertEquals(27, HebNum.gematria("כ\"ז"))
        assertEquals(15, HebNum.gematria("טו"))
        assertEquals(153, HebNum.gematria("קנג"))
        assertNull(HebNum.gematria("ויצא"))
        assertNull(HebNum.gematria("שלום"))
        assertEquals("כז", HebNum.toHeb(27))
        assertEquals("טז", HebNum.toHeb(16))
        assertEquals("קנג", HebNum.toHeb(153))
    }

    @Test fun numbers() {
        assertEquals(27 to 2, HebNum.read(listOf("כף", "זין"), 0))
        assertEquals(27 to 2, HebNum.read(listOf("חף", "זין"), 0))
        assertEquals(27 to 2, HebNum.read(listOf("עשרים", "ושבע"), 0))
        assertEquals(27 to 2, HebNum.read(listOf("שבע", "ועשרים"), 0))
        assertEquals(17 to 2, HebNum.read(listOf("שבעה", "עשר"), 0))
        assertEquals(27 to 1, HebNum.read(listOf("27"), 0))
        assertEquals(3 to 1, HebNum.read(listOf("ג"), 0))
        assertEquals(7 to 1, HebNum.read(listOf("זין"), 0))
    }

    @Test fun gemara() {
        val cases = listOf(
            "מסכת ברכות דף כז עמוד א",
            "ברכות דף כ\"ז ע\"א",
            "ברכות כז.",
            "גמרא ברכות דף כף זין עמוד אלף",
            "גמרא ברכות דף חף זין עמוד א'",
            "מסכת ברכות דף עשרים ושבע עמוד א במהדורת ש\"ס וילנא",
            "תפתח לי ברכות דף כ״ז ע״א",
            "ברכות דף חבזין עמוד א",
            "ברכות דף כפזין עמוד א",
        )
        for (c in cases) {
            val r = RefParser.parse(c)
            assertNotNull(c, r)
            assertEquals(c, Ref.Kind.GEMARA, r!!.kind)
            assertEquals(c, "ברכות", r.work)
            assertEquals(c, 27, r.daf)
            assertEquals(c, 1, r.amud)
        }
        val r = RefParser.parse("בבא מציעא דף נט עמוד ב שוטנשטיין")!!
        assertEquals("בבא מציעא", r.work); assertEquals(59, r.daf); assertEquals(2, r.amud)
        assertEquals(listOf("שוטנשטיין"), r.extra)
        val s = RefParser.parse("שבת דף לא")!!
        assertEquals("שבת", s.work); assertEquals(31, s.daf); assertNull(s.amud)
        assertEquals("עוז", RefParser.parse("ברכות דף ב עוז והדר")!!.extra.first())
        assertEquals(64, RefParser.parse("ברכות סד.")!!.daf)
    }

    @Test fun sichos() {
        val cases = listOf(
            "ליקוטי שיחות חלק ג שיחה לפרשת ויצא",
            "לקוטי שיחות חלק ג' פרשת ויצא",
            "ליקוטי שיחות ח\"ג ויצא",
            "לקו\"ש חלק ג ויצא",
            "ליקוטי שיחות חלק שלישי ויצא",
        )
        for (c in cases) {
            val r = RefParser.parse(c)
            assertNotNull(c, r)
            assertEquals(c, Ref.Kind.SICHOS, r!!.kind)
            assertEquals(c, 3, r.volume)
            assertEquals(c, "ויצא", r.parsha)
        }
        val p58 = RefParser.parse("ליקוטי שיחות חלק ד עמוד 58")!!
        assertEquals(4, p58.volume); assertEquals(58, p58.page); assertNull(p58.parsha)
        val noach = RefParser.parse("תח ליקוטי שיחות חלק ח' ד' שיחה לפרשת נוח")!!
        assertEquals(4, noach.volume); assertEquals("נח", noach.parsha)
        assertEquals(58, RefParser.parse("לקוטי שיחות חלק ד עמוד נח")!!.page)
        val r = RefParser.parse("ליקוטי שיחות חלק טו שיחה ב לפרשת חיי שרה")!!
        assertEquals(15, r.volume); assertEquals(2, r.sicha); assertEquals("חיי שרה", r.parsha)
    }

    @Test fun generic() {
        val r = RefParser.parse("תניא פרק ה")!!
        assertEquals(Ref.Kind.GENERIC, r.kind); assertEquals("תניא", r.work); assertEquals(5, r.chapter)
        val s = RefParser.parse("שולחן ערוך אורח חיים סימן קכח")!!
        assertEquals(128, s.siman); assertEquals("שולחן ערוך אורח חיים", s.work)
        assertEquals(5, RefParser.parse("תניא פרק ה׳.")!!.chapter)
        val bm2 = RefParser.parse("גמרא בבה מיציה דפנת")!!
        assertEquals("בבא מציעא", bm2.work); assertEquals(59, bm2.daf)
        val bm = RefParser.parse("גמרא בבה מיציה דף נט")!!
        assertEquals("בבא מציעא", bm.work); assertEquals(59, bm.daf)
        assertNull(RefParser.parse("ליקוטי שיחות חלק ג׳ פרשת ויצא.")!!.amud)
        val sah = RefParser.parse("שולחן ערוך אדמו\"ר הזקן סימן רע\"ח סעיף א'")!!
        assertEquals("שולחן ערוך הרב", sah.work); assertEquals(278, sah.siman); assertEquals(1, sah.seif)
        assertEquals("שולחן ערוך הרב", RefParser.parse("שו\"ע אדה\"ז סימן ב")!!.work)
        val ls24 = RefParser.parse("שיחה לפרשת ויצא ליקוטי שיחות חלק כ\"ד עמוד 358")!!
        assertEquals(24, ls24.volume); assertEquals(358, ls24.page)
        assertEquals("שולחן ערוך הרב", RefParser.parse("שוע אדהז סימן א")!!.work)
        assertEquals("אדמו״ר הצמח צדק", Abbr.of("אדמ\"צ"))
        assertEquals("שיחיה לאורך ימים טובים אמן", Abbr.of("שליט\"א"))
        assertEquals(27, RefParser.parse("ברכות כ\"ז ע\"א")!!.daf)
        assertNull(RefParser.parse("מה הדין של מי ששכח לברך"))
        assertNull(RefParser.parse("רבי שמעון בר יוחאי"))
    }

    @Test fun pageFinder() {
        assertEquals(listOf(PageFinder.amudIndex(27, 1)), PageFinder.dafMarks("פרק רביעי תפלת השחר ברכות דף כז ע\"א\nגמרא ...", "ברכות").distinct().take(1))
        assert(PageFinder.dafMarks("BERACHOS 27a  ...", null).contains(PageFinder.amudIndex(27, 1)))
        val anchors = listOf(10 to 0, 12 to 2, 20 to 10, 60 to 50)
        assertEquals(60, PageFinder.estimate(anchors, 50, 300))
        assertEquals(35, PageFinder.estimate(anchors, 25, 300))
        assertEquals(3, PageFinder.volumeOf("לקוטי שיחות חלק ג.pdf"))
        assertEquals(15, PageFinder.volumeOf("ליקוטי שיחות - חלק טו"))
        assertEquals(3, PageFinder.volumeOf("LS 3"))
        assert(PageFinder.headerHas("לקוטי שיחות ויצא\nא. ...", listOf("ויצא")))
        val book = (1..300).associateWith { p -> if (p > 6) "${p - 6}\nטקסט של העמוד\nלקוטי שיחות" else "שער" }
        assertEquals(64, PageFinder.pageOfPrinted(book, 58, 300).first)
        assert(PageFinder.hasNumbered("תניא פרק ה\n...", "פרק", 5))
    }
}
```

## `core/src/test/kotlin/com/chdv/searchplus/core/ModelTest.kt`

```kotlin
package com.chdv.searchplus.core

import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests with the real models. The CI workflow downloads them into MODELS_DIR, together with
 * reference values made by the official Python libraries (tools/make_reference.py).
 */
class ModelTest {
    private val dir = File(System.getProperty("models") ?: "")
    private fun need(f: String): File { val x = File(dir, f); assumeTrue("missing $x", x.exists()); return x }

    private fun readWav(f: File): FloatArray {
        val b = f.readBytes()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII); val size = bb.getInt(p + 4)
            if (id == "data") {
                val n = size / 2
                return FloatArray(n) { bb.getShort(p + 8 + it * 2) / 32768f }
            }
            p += 8 + size + (size and 1)
        }
        error("no data in $f")
    }

    @Test fun melMatchesReference() {
        val ref = need("mel_ref.f32")
        val wav = need("audio/0.wav")
        val mine = WhisperMel.logMel(readWav(wav), 80)
        val bb = ByteBuffer.wrap(ref.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        var maxDiff = 0f
        for (i in mine.indices) maxDiff = maxOf(maxDiff, kotlin.math.abs(mine[i] - bb.get(i)))
        println("mel max diff = $maxDiff")
        assertTrue("mel differs by $maxDiff", maxDiff < 2e-3f)
    }

    @Test fun whisperTokenizer() {
        val tok = WhisperTokenizer(need("whisper-base/tokenizer.json"))
        val expected = need("whisper_prompt_ids.txt").readText().trim().split(',').map { it.trim().toInt() }
        val mine = tok.encode(" " + Whisper.PROMPT)
        assertEquals(expected, mine)
        assertEquals(Whisper.PROMPT, tok.decode(mine).trim())
        assertEquals(50258, tok.id("<|startoftranscript|>"))
    }

    @Test fun whisperTranscribes() {
        val env = OrtEnvironment.getEnvironment()
        val cases = need("audio/cases.txt").readLines().filter { it.isNotBlank() }
        for (model in listOf("whisper-base", "whisper-small")) {
            val enc = File(dir, "$model/encoder_model_quantized.onnx")
            if (!enc.exists()) { println("skip $model"); continue }
            val w = Whisper(env, enc.path, File(dir, "$model/decoder_model_merged_quantized.onnx").path, WhisperTokenizer(File(dir, "$model/tokenizer.json")))
            w.use {
                var good = 0
                cases.forEachIndexed { i, said ->
                    val audio = readWav(File(dir, "audio/$i.wav"))
                    val t0 = System.currentTimeMillis()
                    val text = w.transcribe(audio, "he", Whisper.PROMPT)
                    val ms = System.currentTimeMillis() - t0
                    val want = RefParser.parse(said)
                    val got = RefParser.parse(text)
                    val ok = want == null || (got != null && got.work == want.work && got.daf == want.daf && got.volume == want.volume && got.parsha == want.parsha && got.chapter == want.chapter)
                    if (ok) good++
                    println("[$model] ${if (ok) "OK " else "BAD"} said=「$said」 heard=「$text」 ref=${got?.label()} (${ms}ms: mel ${w.lastMelMs} enc ${w.lastEncMs} dec ${w.lastDecMs})")
                    assertTrue("empty transcription for $said", text.isNotBlank())
                }
                println("[$model] understood $good / ${cases.size}")
                // the low-memory way (2GB devices) must give the same words
                Whisper(env, enc.path, File(dir, "$model/decoder_model_merged_quantized.onnx").path, WhisperTokenizer(File(dir, "$model/tokenizer.json")),
                    4, ai.onnxruntime.OrtSession.SessionOptions.OptLevel.BASIC_OPT, lowMemory = true).use { lw ->
                    val a0 = readWav(File(dir, "audio/1.wav"))
                    val t0 = System.currentTimeMillis()
                    val lowText = lw.transcribe(a0, "he", Whisper.PROMPT)
                    println("[$model] low-memory: 「$lowText」 (${System.currentTimeMillis() - t0}ms)")
                    assertTrue(lowText.isNotBlank())
                }
                // a quiet microphone: the same sentence at 3% volume must still be understood
                val quiet = readWav(File(dir, "audio/0.wav")).map { it * 0.03f }.toFloatArray()
                println("[$model] quiet: 「${w.transcribe(quiet, "he", Whisper.PROMPT)}」")
                println("[$model] peak memory so far: " + File("/proc/self/status").readLines().filter { it.startsWith("VmHWM") || it.startsWith("VmRSS") })
            }
        }
    }

    @Test fun e5MatchesPython() {
        val bin = File(dir, "e5/vocab.bin")
        val t0 = System.currentTimeMillis()
        E5Tokenizer.compile(need("e5/tokenizer.json"), bin)
        println("compile vocab ${System.currentTimeMillis() - t0}ms, ${bin.length()} bytes")
        val tok = E5Tokenizer.load(bin)
        val lines = need("e5_ref.txt").readLines().filter { it.isNotBlank() }
        val env = OrtEnvironment.getEnvironment()
        E5Model(env, need("e5/model_quantized.onnx").path, tok).use { m ->
            for (l in lines) {
                val (text, ids, vec) = l.split('\t')
                val want = ids.split(',').map { it.toLong() }.toLongArray()
                val mine = tok.tokenize(text, 512)
                assertArrayEquals("tokens of 「$text」", want, mine)
                val ref = vec.split(',').map { it.toFloat() }
                val v = m.embed(text, 512)
                var dot = 0f
                for (d in v.indices) dot += v[d] * ref[d]
                println("e5 cos=$dot  $text")
                assertTrue("embedding differs ($dot) for $text", dot > 0.999f)
            }
            // meaning: the question is closer to the passage that answers it
            val q = m.embed("query: מה מברכים על פרי העץ")
            val good = m.embed("passage: על פירות האילן הוא אומר בורא פרי העץ, ועל פירות הארץ בורא פרי האדמה")
            val bad = m.embed("passage: מי שנתחייב מלקות במזיד והתרו בו לוקה ארבעים חסר אחת")
            fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }
            println("e5 good=${dot(q, good)} bad=${dot(q, bad)}")
            assertTrue(dot(q, good) > dot(q, bad))
        }
    }

    @Test fun bgeM3() {
        val bin = File(dir, "bge/vocab.bin")
        E5Tokenizer.compile(need("bge/tokenizer.json"), bin)
        val tok = E5Tokenizer.load(bin)
        val env = OrtEnvironment.getEnvironment()
        val t0 = System.currentTimeMillis()
        E5Model(env, need("bge/model_quantized.onnx").path, tok, cls = true, lowMemory = true).use { m ->
            println("bge load ${System.currentTimeMillis() - t0}ms")
            val vecs = ArrayList<FloatArray>()
            for (l in need("bge_ref.txt").readLines().filter { it.isNotBlank() }) {
                val (text, ids, vec) = l.split('\t')
                assertArrayEquals("tokens of 「$text」", ids.split(',').map { it.toLong() }.toLongArray(), tok.tokenize(text, 512))
                val ref = vec.split(',').map { it.toFloat() }
                val t1 = System.currentTimeMillis()
                val v = m.embed(text, 512)
                var dot = 0f
                for (d in v.indices) dot += v[d] * ref[d]
                println("bge cos=$dot (${System.currentTimeMillis() - t1}ms) $text")
                assertTrue(dot > 0.98f) // the quantized model differs slightly between ONNX Runtime versions
                vecs.add(v)
            }
            fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }
            println("bge good=${dot(vecs[0], vecs[1])} bad=${dot(vecs[0], vecs[2])}")
            assertTrue(dot(vecs[0], vecs[1]) > dot(vecs[0], vecs[2]))
            println("bge peak memory: " + File("/proc/self/status").readLines().filter { it.startsWith("VmHWM") })
        }
    }
}
```

## `core/src/test/kotlin/com/chdv/searchplus/core/NamesTest.kt`

```kotlin
package com.chdv.searchplus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A library of 12,000 books shaped like a real Chabad library, and the requests people make. */
class NamesTest {
    private val items = ArrayList<Names.Item>()
    private val titleOf = HashMap<Long, String>()
    private var id = 1L
    private fun add(title: String, vararg folders: String) { items.add(Names.Item(id, title, folders.toList())); titleOf[id] = title; id++ }

    private val names: Names by lazy {
        for (v in 1..39) add("לקוטי שיחות חלק ${HebNum.toHeb(v)}", "חסידות", "רבותינו נשיאינו", "הרבי", "לקוטי שיחות")
        add("ליקוטי שיחות - כרך 24 (מהדורה חדשה)", "חסידות", "ליקוטי שיחות מהדורה חדשה")
        for (m in RefParser.MASECHTOT) add(m, "גמרא", "ש\"ס וילנא")
        for (m in listOf("ברכות", "שבת", "בבא מציעא")) add("$m - עוז והדר", "גמרא", "עוז והדר")
        add("ברכות א - שוטנשטיין", "גמרא", "Schottenstein")
        add("ירושלמי ברכות", "גמרא", "ירושלמי")
        add("משניות ברכות", "משנה")
        add("חלק א - אורח חיים", "הלכה", "שו\"ע אדה\"ז")
        add("חלק ב - אורח חיים", "הלכה", "שו\"ע אדה\"ז")
        add("קיצור שולחן ערוך", "הלכה")
        add("משנה ברורה חלק ג", "הלכה", "משנה ברורה")
        add("ספר התניא", "חסידות", "תניא")
        for (v in 1..30) add("אגרות קודש חלק ${HebNum.toHeb(v)}", "חסידות", "רבותינו נשיאינו", "אגרות קודש")
        add("תורת מנחם תשמ\"ח", "חסידות", "תורת מנחם")
        add("scan_0042", "חסידות", "ספרים שונים")
        // filler up to 12,000
        val voc = listOf("ספר", "קונטרס", "מאמרים", "שיחות", "דרושים", "פירוש", "חידושים", "תורה", "חסידות", "הלכות", "שבת", "יום טוב", "מועדים", "פסח",
            "ביאור", "מכתבים", "תולדות", "זכרון", "לקט", "אוצר", "מנחת", "שערי", "דברי", "נועם", "אור", "חיים", "ישועות", "בית", "יוסף", "אהרן")
        val r = java.util.Random(1)
        while (items.size < 12000) add((1..3).joinToString(" ") { voc[r.nextInt(voc.size)] } + " " + r.nextInt(500), voc[r.nextInt(voc.size)], voc[r.nextInt(voc.size)])
        Names(items)
    }

    private fun top(q: String): List<String> {
        val ref = RefParser.parse(q)
        return names.find(q, ref).map { titleOf[it.id]!! }
    }

    @Test fun findsTheRightBooks() {
        val t0 = System.currentTimeMillis()
        names.size
        println("built ${names.size} in ${System.currentTimeMillis() - t0}ms")

        fun check(q: String, wantFirst: String, orAlso: String? = null) {
            val t = System.currentTimeMillis()
            val r = top(q)
            println("「$q」 → ${r.take(3)} (${System.currentTimeMillis() - t}ms)")
            assertTrue("「$q」: got $r", r.isNotEmpty())
            if (orAlso != null && r.first() == orAlso) return
            assertEquals("「$q」", wantFirst, r.first())
        }
        check("לקוטי שיחות חלק כד עמוד 358", "לקוטי שיחות חלק כד")
        check("לקו\"ש חכ\"ד עמ' 358", "לקוטי שיחות חלק כד", "ליקוטי שיחות - כרך 24 (מהדורה חדשה)")
        check("ליקוטי שיחות חלק ד שיחה לפרשת נח", "לקוטי שיחות חלק ד")
        check("לקוטי שיחות חלק כ פרשת ויצא", "לקוטי שיחות חלק כ")
        check("ברכות דף כג", "ברכות")
        check("שו\"ע אדה\"ז סימן ב", "חלק א - אורח חיים")
        check("שולחן ערוך אדמו\"ר הזקן סימן רעח", "חלק א - אורח חיים")
        check("אגרות קודש חלק ה", "אגרות קודש חלק ה")
        check("אג\"ק חלק ה עמוד 20", "אגרות קודש חלק ה")
        check("תניא פרק ה", "ספר התניא")
        check("משנה ברורה", "משנה ברורה חלק ג")
        check("קיצור שולחן ערוך", "קיצור שולחן ערוך")
        // all Berachos editions, the Bavli first; not the Yerushalmi / Mishna
        val b = top("ברכות כז")
        assertTrue(b.containsAll(listOf("ברכות", "ברכות - עוז והדר", "ברכות א - שוטנשטיין")))
        assertTrue(b.indexOf("ירושלמי ברכות").let { it < 0 || it > 2 })

        val t = System.currentTimeMillis()
        repeat(50) { top("לקו\"ש חלק כד עמוד 358") }
        val per = (System.currentTimeMillis() - t) / 50
        println("one request: ${per}ms")
        assertTrue("too slow: ${per}ms", per < 300)
    }
}
```

## `docs/GOOGLE_DRIVE_SETUP.md`

```markdown
# חיבור Google Drive – הגדרה חד-פעמית אצל Google (למפתח)

כדי שהכפתור **"🔗 חיבור לחשבון Google Drive"** בתוכנה יעבוד, Google צריכה להכיר את האפליקציה פעם אחת.
עושים את זה פעם אחת בלבד, מהמחשב או מהטלפון (כ-10 דקות). אחרי זה כל משתמש פשוט לוחץ "חיבור", בוחר חשבון ומאשר.

## הפרטים של האפליקציה (להעתיק)
- **Package name:** `com.chdv.searchplus`
- **SHA-1:** `71:8B:76:ED:F4:A4:4E:34:9B:F6:19:A4:98:C4:C2:8F:53:63:4B:D4`

## השלבים
1. נכנסים ל-https://console.cloud.google.com עם חשבון Google שלכם ← **Select a project** ← **New project** ← שם: `Search Plus` ← **Create**.
2. חיפוש למעלה: **Google Drive API** ← **Enable**.
3. תפריט ☰ ← **APIs & Services** ← **OAuth consent screen** (או **Google Auth Platform**):
   - App name: `חיפוש פלוס` · User support email: המייל שלכם · Audience: **External** ← שמירה.
   - **Data access / Scopes** ← Add scope ← `.../auth/drive.readonly` ← שמירה.
   - **Audience / Test users** ← מוסיפים את כתובות ה-Gmail של מי שישתמש (עד 100), לפחות את שלכם.
4. **Credentials** (או **Clients**) ← **Create credentials** ← **OAuth client ID** ← Application type: **Android**:
   - Package name: `com.chdv.searchplus`
   - SHA-1: מעתיקים מלמעלה ← **Create**.
5. זהו. בתוכנה: ☰ ← "ספרים מ-Google Drive" ← "🔗 חיבור לחשבון Google Drive".

## לפרסום לכל אחד (לא רק 100 משתמשי בדיקה)
ב-OAuth consent screen לוחצים **Publish app**. כיוון שהתוכנה מבקשת לקרוא את ה-Drive, Google תבקש אימות (Verification) –
דף פרטיות (אפשר באתר של חיפוש פלוס), הסבר קצר וסרטון שמראה את השימוש. עד שהאימות מאושר, משתמשים שאינם ברשימת הבדיקה
יראו אזהרה "Google hasn't verified this app" ויוכלו ללחוץ **Advanced ← Go to חיפוש פלוס** כדי להמשיך.

## איך זה עובד בתוכנה
- Google מציגה את מסך הכניסה והאישור שלה; התוכנה מקבלת הרשאת **קריאה בלבד**.
- התוכנה מוצאת את כל קבצי ה-PDF ב-Drive (כולל משותפים), מציגה את התיקיות, והמשתמש בוחר מה להביא.
- הספרים יורדים לתיקייה "חיפוש פלוס – ספרים מהענן" במכשיר (במבנה התיקיות של ה-Drive), נכנסים למאגר ונסרקים.
  מאז החיפוש, הקולי והשאלות עובדים עליהם בתוך המכשיר. לחיצה נוספת על "חיבור" מביאה רק ספרים חדשים.
```

## `docs/ad/ad.html`

```html
<!doctype html>
<html lang="he" dir="rtl">
<head>
<meta charset="utf-8">
<title>חיפוש פלוס – מודעה</title>
<style>
@font-face { font-family: Heebo; src: url(../guide/fonts/heebo-hebrew.woff2) format("woff2"); font-weight: 100 900; unicode-range: U+0590-05FF, U+200C-2010, U+20AA, U+25CC, U+FB1D-FB4F; }
@font-face { font-family: Heebo; src: url(../guide/fonts/heebo-latin.woff2) format("woff2"); font-weight: 100 900; }
html, body { margin: 0; }
body { width: 1080px; height: 1350px; font-family: Heebo, sans-serif; color: #fff; overflow: hidden;
  background: radial-gradient(900px 700px at 85% 10%, #6F7CFF 0%, #3B4FD8 45%, #1d2a86 100%); position: relative; }
.wrap { padding: 70px 80px; height: 100%; box-sizing: border-box; display: flex; flex-direction: column; }
.top { display: flex; align-items: center; gap: 18px; font-weight: 900; font-size: 34px; }
.top b { background: #fff; color: #3B4FD8; border-radius: 16px; padding: 4px 16px; }
.kick { margin-top: 46px; background: #F2C14E; color: #222; font-weight: 900; font-size: 28px; border-radius: 40px; padding: 8px 26px; align-self: flex-start; }
h1 { font-size: 104px; line-height: 1.02; margin: 26px 0 18px; font-weight: 900; letter-spacing: -1px; }
.lead { font-size: 34px; color: #e3e7ff; line-height: 1.4; margin: 0 0 34px; }
.say { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 40px; }
.say span { background: rgba(255,255,255,.14); border: 2px solid rgba(255,255,255,.35); border-radius: 40px; padding: 8px 22px; font-size: 27px; font-weight: 700; }
.feats { display: grid; grid-template-columns: 1fr 1fr; gap: 16px 30px; font-size: 29px; font-weight: 700; margin-bottom: auto; }
.bottom { display: flex; align-items: center; gap: 34px; background: #fff; color: #1d2a86; border-radius: 34px; padding: 26px 34px; }
.bottom img { width: 210px; height: 210px; }
.bottom .t { font-size: 40px; font-weight: 900; line-height: 1.15; }
.bottom .s { font-size: 24px; color: #4a5070; margin-top: 8px; }
.moshiach { text-align: center; font-weight: 800; font-size: 28px; margin-top: 26px; color: #F2C14E; }
</style>
</head>
<body><div class="wrap">
  <div class="top"><b>פ+</b>חיפוש פלוס</div>
  <div class="kick">חדש · חינם · בלי אינטרנט</div>
  <h1>אומרים –<br>והספר נפתח במקום.</h1>
  <p class="lead">כל ספרי ה-PDF שבמכשיר, בכרטיס הזיכרון וב-Drive – בחיפוש אחד: בקול, בכתיבה מהירה, במילים, ולפי משמעות.</p>
  <div class="say"><span>״ברכות דף כ״ז עמוד א׳״</span><span>״לקו״ש חלק כ״ד עמוד 358״</span><span>״שו״ע אדה״ז סימן רע״ח״</span></div>
  <div class="feats">
    <div>🎤 חיפוש קולי בעברית</div><div>⚡ מעבר מהיר – מכיר ראשי תיבות</div>
    <div>💡 שאלה – והמשפט שעונה</div><div>📖 נפתח מסומן, גם ב-ReadEra</div>
    <div>📜 גם ספרים ישנים וסרוקים</div><div>📱 BOOX, טלפון וטאבלט</div>
  </div>
  <div class="bottom">
    <img src="qr.png" alt="">
    <div><div class="t">סורקים ומורידים<br>חיפוש פלוס – חינם</div><div class="s" dir="ltr" style="text-align:right">github.com/sw0555041250-code/Search-Plus-</div></div>
  </div>
  <div class="moshiach">יחי אדוננו מורנו ורבנו מלך המשיח לעולם ועד</div>
</div></body>
</html>
```

## `docs/guide/guide.html`

```html
<!doctype html>
<html lang="he" dir="rtl">
<head>
<meta charset="utf-8">
<title>חיפוש פלוס – מדריך</title>
<style>
@font-face { font-family: Heebo; src: url(fonts/heebo-hebrew.woff2) format("woff2"); font-weight: 100 900; unicode-range: U+0590-05FF, U+200C-2010, U+20AA, U+25CC, U+FB1D-FB4F; }
@font-face { font-family: Heebo; src: url(fonts/heebo-latin.woff2) format("woff2"); font-weight: 100 900; }
@page { size: A4; margin: 0; }
:root { --acc: #1B3B6F; --acc2: #2C5BA8; --light: #E6ECF7; --gold: #F2C14E; --ink: #121417; --sub: #575c66; --card: #F4F5F8; --line: #D9DCE3; }
* { box-sizing: border-box; }
body { margin: 0; font-family: Heebo, sans-serif; color: var(--ink); background: #fff; font-size: 11.6pt; line-height: 1.5; }
.page { width: 210mm; height: 297mm; padding: 15mm 17mm 20mm; position: relative; page-break-after: always; overflow: hidden; }
.page:last-child { page-break-after: auto; }
.tight { font-size: 10.6pt; line-height: 1.42; }
.tight h2 { font-size: 18pt; margin-bottom: 3mm; } .tight h3 { margin-top: 3.5mm; } .tight .say { font-size: 9.5pt; padding: .3mm 3mm; }
.foot { position: absolute; bottom: 8mm; left: 17mm; right: 17mm; display: flex; justify-content: space-between; color: var(--sub); font-size: 9pt; border-top: 1px solid var(--line); padding-top: 3mm; }
h1 { font-size: 40pt; font-weight: 900; margin: 0; line-height: 1.05; letter-spacing: -0.5px; }
h2 { font-size: 21pt; font-weight: 900; margin: 0 0 4mm; color: var(--acc); display: flex; align-items: center; gap: 3mm; }
h2 .n { background: var(--acc); color: #fff; border-radius: 50%; width: 11mm; height: 11mm; display: inline-flex; align-items: center; justify-content: center; font-size: 15pt; flex: none; }
h3 { font-size: 13.5pt; margin: 5mm 0 1.5mm; font-weight: 800; }
p { margin: 0 0 2.5mm; }
ul, ol { margin: 0 0 3mm; padding-right: 6mm; }
li { margin-bottom: 1.2mm; }
.lead { font-size: 14pt; color: var(--sub); }
.box { background: var(--card); border: 1px solid var(--line); border-radius: 4mm; padding: 4mm 5mm; margin: 3mm 0; }
.tip { background: #FFF6DA; border: 1px solid #E8C55C; border-radius: 4mm; padding: 3.5mm 5mm; margin: 3mm 0; }
.say { font-size: 10.5pt; display: inline-block; background: var(--light); color: var(--acc); border: 1px solid #c3d0ea; border-radius: 10mm; padding: .6mm 3.5mm; margin: .8mm .5mm; font-weight: 700; white-space: nowrap; }
.cols { display: flex; gap: 8mm; align-items: flex-start; }
.cols > .grow { flex: 1; }
table { width: 100%; border-collapse: collapse; font-size: 11.5pt; }
td, th { border-bottom: 1px solid var(--line); padding: 2mm 1.5mm; text-align: right; vertical-align: top; }
th { color: var(--acc); }

/* cover */
.cover { background: linear-gradient(160deg, #1B3B6F 0%, #2C5BA8 60%, #3C74C9 100%); color: #fff; }
.cover .lead { color: #dbe6f7; }
.badge { display: inline-block; background: var(--gold); color: #1d1d1d; font-weight: 800; border-radius: 10mm; padding: 1mm 4mm; font-size: 11pt; }
.feat { display: grid; grid-template-columns: 1fr 1fr; gap: 4mm; margin-top: 8mm; }
.feat div { background: rgba(255,255,255,.12); border: 1px solid rgba(255,255,255,.25); border-radius: 4mm; padding: 4mm; font-size: 11.5pt; }
.feat b { display: block; font-size: 13.5pt; margin-bottom: 1mm; }

/* phone mockups */
.phone { color: var(--ink); width: 64mm; flex: none; border: 2.2mm solid #222; border-radius: 6mm; background: #fff; overflow: hidden; box-shadow: 0 2mm 6mm rgba(0,0,0,.18); font-size: 7.6pt; line-height: 1.35; }
.cover .phone { border-color: #0d1b33; }
.ph-head { display: flex; align-items: center; gap: 1.6mm; padding: 2.2mm 2.4mm 1.4mm; }
.ph-logo { width: 6mm; height: 6mm; border-radius: 1.6mm; background: var(--acc); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 900; font-size: 8pt; }
.ph-title { font-weight: 900; font-size: 10pt; color: var(--ink); }
.ph-sub { color: var(--sub); font-size: 6.4pt; }
.ph-btn { margin-right: auto; color: var(--acc); font-weight: 700; font-size: 7pt; }
.ph-search { margin: 1mm 2.2mm 1.4mm; border: .45mm solid var(--acc); border-radius: 5mm; background: var(--card); display: flex; align-items: center; padding: .8mm 1mm .8mm 2.4mm; gap: 1mm; color: var(--ink); }
.ph-search .q { flex: 1; font-size: 8.4pt; font-weight: 500; }
.ph-search .hint { color: #8a8a8a; }
.ph-search .mic { font-size: 9pt; }
.ph-search .go { background: var(--acc); color: #fff; border-radius: 4mm; padding: .7mm 2.2mm; font-weight: 700; font-size: 7pt; }
.ph-modes { display: flex; gap: 1mm; padding: 0 2.2mm; }
.ph-modes span { flex: 1; text-align: center; border-radius: 3mm; padding: .8mm 0; background: var(--card); border: .25mm solid var(--line); font-size: 6.8pt; }
.ph-modes .on { background: var(--acc); color: #fff; border-color: var(--acc); font-weight: 700; }
.ph-body { padding: 1.6mm 2.2mm 2.4mm; }
.ph-hero { background: var(--acc); color: #fff; border-radius: 3mm; padding: 2.2mm 2.6mm; }
.ph-hero b { font-size: 9pt; }
.ph-mic { margin-top: 1.8mm; border: .5mm solid var(--acc); border-radius: 3.5mm; padding: 2.2mm; text-align: center; }
.ph-mic .circle { width: 13mm; height: 13mm; border-radius: 50%; background: var(--light); border: .6mm solid var(--acc); margin: 0 auto 1mm; display: flex; align-items: center; justify-content: center; font-size: 15pt; }
.ph-mic b { font-size: 9pt; display: block; }
.chips span { display: inline-block; background: var(--card); border: .25mm solid var(--line); border-radius: 3mm; padding: .4mm 1.6mm; margin: .5mm .3mm; font-size: 6.4pt; }
.ph-jump { background: var(--acc); color: #fff; border-radius: 2.4mm; padding: 1.6mm 2.2mm; font-weight: 700; margin: 1.2mm 0; }
.ph-row { border-bottom: .25mm solid var(--line); padding: 1.6mm .6mm; }
.ph-row .t { font-weight: 800; font-size: 8pt; display: flex; justify-content: space-between; }
.ph-row .t i { font-style: normal; background: var(--acc); color: #fff; border-radius: 2mm; padding: 0 1.4mm; font-size: 6.6pt; }
.ph-row .f { color: var(--sub); font-size: 6.3pt; }
.ph-snip { background: var(--card); padding: 1.4mm 2.4mm 1.4mm 1mm; font-size: 7pt; border-bottom: .25mm solid var(--line); }
.ph-snip .pg { color: var(--acc); font-weight: 700; }
mark { background: #FFE38A; font-weight: 800; text-decoration: underline; padding: 0 .3mm; }
.ph-answer { background: #FFF7DD; border: .4mm solid #D8B040; border-radius: 2.6mm; padding: 1.8mm 2.2mm; margin: 1mm 0 1.6mm; }
.ph-answer .h { color: var(--acc); font-weight: 800; font-size: 7pt; }
.ph-answer .src { color: var(--acc); font-weight: 700; font-size: 6.6pt; }
.ph-sheet { margin-top: 2mm; border: .3mm solid var(--line); border-radius: 3mm; padding: 2.4mm; box-shadow: 0 -1mm 3mm rgba(0,0,0,.08); }
.ph-sheet b { font-size: 9pt; }
.ph-bar { height: 2.4mm; border-radius: 1.2mm; background: var(--card); border: .25mm solid var(--line); margin: 1.6mm 0; overflow: hidden; }
.ph-bar i { display: block; height: 100%; width: 62%; background: var(--acc); }
.ph-page { margin: 1mm 0; border: .25mm solid var(--line); height: 55mm; padding: 2mm; font-size: 6pt; color: #333; text-align: justify; position: relative; background: #fff; }
.ph-page .hdr { text-align: center; font-weight: 800; font-size: 7pt; border-bottom: .2mm solid #999; margin-bottom: 1mm; }
.ph-refbar { background: var(--light); color: var(--acc); font-weight: 800; padding: 1.2mm 2mm; display: flex; justify-content: space-between; font-size: 7pt; }
.caption { font-size: 9.5pt; color: var(--sub); text-align: center; margin-top: 2mm; }
</style>
</head>
<body>

<!-- ================= COVER ================= -->
<section class="page cover">
  <span class="badge">BOOX Go 6 · אנדרואיד · בלי אינטרנט</span>
  <div class="cols" style="margin-top: 10mm">
    <div class="grow">
      <h1>חיפוש<br>פלוס 🔍</h1>
      <p class="lead" style="font-size: 16pt; margin-top: 5mm">אומרים מה מחפשים –<br>והספר נפתח בדיוק במקום.</p>
      <p style="margin-top: 6mm; font-size: 13pt">חיפוש מעמיק בתוך כל ספרי ה-PDF שבמכשיר ובכרטיס הזיכרון: לפי מילים, לפי משמעות, ובקול. הבינה המלאכותית רצה בתוך המכשיר עצמו.</p>
    </div>
    <div class="phone" style="margin-top: 2mm">
      <div class="ph-head"><div class="ph-logo">פ+</div><div><div class="ph-title">חיפוש פלוס</div><div class="ph-sub">חיפוש מעמיק בתוך הספרים שלך</div></div><div class="ph-btn">📚 ⚙</div></div>
      <div class="ph-search"><span class="q hint">מה לחפש? או לחצו 🎤 ודברו</span><span class="mic">🎤</span><span class="go">חיפוש</span></div>
      <div class="ph-modes"><span class="on">🔤 חיפוש מילים</span><span>💡 לפי משמעות</span></div>
      <div class="ph-body">
        <div class="ph-hero"><b>בוקר טוב! מה נלמד היום?</b><div style="opacity:.85">במאגר 4,812 ספרים · 1,206,330 עמודים</div></div>
        <div class="ph-mic"><div class="circle">🎤</div><b>לחצו ודברו</b><span style="color:var(--sub)">אומרים מה מחפשים – והספר נפתח במקום</span>
          <div class="chips" style="margin-top:1mm"><span>“ברכות דף כ״ז ע״א”</span><span>“לקוטי שיחות חלק ד עמוד 58”</span><span>“מה מברכים על פרי העץ”</span></div></div>
      </div>
    </div>
  </div>
  <div class="feat">
    <div><b>🎤 חיפוש קולי</b>"ברכות דף כ״ז עמוד א" – וכל מהדורות הגמרא שלך נפתחות בדף.</div>
    <div><b>💡 שאלה ותשובה</b>כותבים שאלה במילים שלך ומקבלים את המשפט שעונה – עם המקור.</div>
    <div><b>🔤 חיפוש מילים</b>בתוך הספרים, לא רק בשמות. כולל ו׳ ה׳ ב׳ ל׳, כוכבית, ביטוי מדויק.</div>
    <div><b>📖 נפתח במקום</b>בעמוד הנכון עם המילים מסומנות – או באפליקציה שתבחרו.</div>
  </div>
  <div class="foot" style="color:#cfdcf2; border-color: rgba(255,255,255,.3)"><span>פותח על ידי שניאור וילהלם · מופץ לתועלת הרבים</span><span dir="ltr">github.com/sw0555041250-code/Search-Plus-</span></div>
</section>

<!-- ================= INSTALL ================= -->
<section class="page">
  <h2><span class="n">1</span>התקנה – שתי דקות</h2>
  <div class="cols">
    <div class="grow">
      <ol>
        <li>במכשיר, פותחים את הקישור:<br><b dir="ltr" style="font-size:10.5pt">github.com/sw0555041250-code/Search-Plus-/releases/latest/download/SearchPlus.apk</b></li>
        <li>פותחים את הקובץ שירד ומתקינים. אם מופיעה הודעה על "מקורות לא ידועים" – מאשרים וחוזרים.</li>
        <li>פותחים את חיפוש פלוס › <b>מתן הרשאה</b> › מפעילים "גישה לכל הקבצים".</li>
        <li><b>בחירת תיקיית הספרים</b> › כרטיס זיכרון › התיקייה הראשית של המאגר.</li>
      </ol>
      <div class="tip">💡 <b>כבר מותקנת גרסה קודמת?</b> מתקינים את החדשה מעליה – האינדקס וההגדרות נשמרים. מכאן והלאה התוכנה מציעה בעצמה כל עדכון חדש.</div>
      <h3>מה קורה אחרי שבוחרים תיקייה?</h3>
      <p>התוכנה עוברת על כל ה-PDF במאגר (גם בתיקיות עמוקות ולא מוכרות), קוראת כל עמוד ושומרת את הטקסט באינדקס. הספרים עצמם לא משוכפלים ולא זזים – האינדקס הוא רק טקסט, והוא קטן פי כמה מהמאגר.</p>
      <ul>
        <li>אפשר לחפש מיד בספרים שכבר נסרקו.</li>
        <li>ספרים ישנים עם עברית הפוכה או קידוד ישן – מתוקנים אוטומטית.</li>
        <li>עמודים סרוקים (תמונה בלי טקסט) – מזוהים ב-OCR עברי בתוך המכשיר.</li>
      </ul>
    </div>
  </div>

  <h2 style="margin-top: 8mm"><span class="n">2</span>הורדות חד-פעמיות (ואז – בלי אינטרנט)</h2>
  <table>
    <tr><th>רכיב</th><th>גודל</th><th>למה</th><th>איפה</th></tr>
    <tr><td><b>🎤 מודל דיבור – מדויק</b> (מומלץ)</td><td>כ-250MB</td><td>מבין עברית ושמות ספרים. כ-10 שניות לזיהוי ב-BOOX</td><td>הגדרות › חיפוש קולי</td></tr>
    <tr><td>🎤 מודל דיבור – מהיר</td><td>כ-80MB</td><td>מהיר פי 3, טועה יותר בעברית</td><td>הגדרות › חיפוש קולי</td></tr>
    <tr><td><b>💡 מודל המשמעות</b></td><td>כ-120MB</td><td>חיפוש לפי משמעות, שאלה ותשובה</td><td>הגדרות › חיפוש לפי משמעות</td></tr>
    <tr><td>OCR עברי</td><td>כלול</td><td>קריאת עמודים סרוקים</td><td>מותקן מראש</td></tr>
  </table>
  <p style="margin-top:3mm;color:var(--sub);font-size:10pt">המסכים במדריך הם המחשה של התוכנה.</p><p>המודלים נשמרים במקום שיש בו הכי הרבה זיכרון פנוי (במכשיר או בכרטיס), ולא נטענים יחד – כדי שהמכשיר (2GB RAM) לא יתעייף.</p>
  <div class="foot"><span>חיפוש פלוס – מדריך</span><span>2</span></div>
</section>

<!-- ================= VOICE ================= -->
<section class="page tight">
  <h2><span class="n">3</span>🎤 חיפוש קולי – אומרים, והספר נפתח</h2>
  <div class="cols">
    <div class="grow">
      <p>לוחצים על המיקרופון הגדול במסך הבית (או על 🎤 בשורת החיפוש) ואומרים מה רוצים. כשמסיימים לדבר – התוכנה מזהה לבד, מבינה את ההפניה ופותחת את הספר בעמוד. הכול קורה בתוך המכשיר, בלי אינטרנט.</p>
      <h3>גמרא</h3>
      <p><span class="say">מסכת ברכות דף כ״ז עמוד א</span><span class="say">ברכות כ״ז ע״א</span><span class="say">גמרא ברכות דף כף זין</span><span class="say">שבת דף עשרים ושבע</span><span class="say">בבא מציעא נ״ט שוטנשטיין</span></p>
      <p>יש לך כמה מהדורות (וילנא, עוז והדר, שוטנשטיין…)? מופיעה רשימה – בוחרים, וכל אחת נפתחת באותו דף. אומרים את שם המהדורה – והיא תופיע ראשונה.</p>
      <h3>ליקוטי שיחות</h3>
      <p><span class="say">ליקוטי שיחות חלק ד׳ עמוד 58</span><span class="say">ליקוטי שיחות חלק ד׳ שיחה לפרשת נח</span><span class="say">לקו״ש ח״ג ויצא</span><span class="say">חלק טו שיחה ב לפרשת חיי שרה</span></p>
      <p>"עמוד 58" – לפי המספר המודפס בספר, לא לפי מספר העמוד בקובץ. התוכנה לומדת לבד את המספור של כל כרך.</p>
      <h3>כל ספר אחר במאגר</h3>
      <p><span class="say">תניא פרק ה</span><span class="say">שולחן ערוך אורח חיים סימן קכח</span><span class="say">רמב״ם הלכות תשובה פרק ג</span></p>
      <p>התוכנה מחפשת את הספר לפי השם, התיקייה ודף השער – גם אם הוא בתיקייה נידחת, וגם אם האיות שונה (ליקוטי/לקוטי, שולחן/שלחן).</p>
      <h3>⚡ מעבר מהיר – בכתיבה</h3>
      <p>בתפריט ☰ › "מעבר מהיר": כותבים בקיצור, כמו שרגילים – <span class="say">לקו״ש חכ״ד עמ׳ 358</span><span class="say">שו״ע אדה״ז סימן רע״ח</span> – והתוכנה מבינה את השמות והקיצורים, מציעה אפשרויות ממוספרות ① ② ③, ופותחת.</p>
      <h3>שאלה</h3>
      <p><span class="say">מה מברכים על פרי העץ?</span><span class="say">למה יעקב יצא לחרן?</span> – עוברים לבד לחיפוש לפי משמעות.</p>
    </div>
    <div>
      <div class="phone">
        <div class="ph-head"><div class="ph-logo">פ+</div><div class="ph-title">חיפוש פלוס</div></div>
        <div class="ph-search"><span class="q">ברכות דף כ״ז עמוד א</span><span class="mic">🎤</span><span class="go">חיפוש</span></div>
        <div class="ph-body">
          <div class="ph-sheet"><b>🎤 מקשיב… דברו עכשיו</b><div class="ph-bar"><i></i></div><div style="color:var(--acc); font-weight:800; font-size:8.5pt">ברכות דף כ״ז עמוד א</div></div>
          <div class="ph-sheet"><b>איזה ספר לפתוח?</b>
            <div class="ph-row"><div class="t">📖 ברכות – וילנא</div><div class="f">גמרא › ש״ס וילנא · עמוד 57</div></div>
            <div class="ph-row"><div class="t">📖 ברכות – עוז והדר</div><div class="f">גמרא › עוז והדר · עמוד 61</div></div>
            <div class="ph-row" style="border:0"><div class="t">📖 ברכות ב – שוטנשטיין</div><div class="f">גמרא › שוטנשטיין · עמוד 212</div></div>
          </div>
        </div>
      </div>
      <p class="caption">מדברים › בוחרים מהדורה › נפתח בדף</p>
      <div class="tip" style="width:64mm; font-size:10.5pt">✓ <b>הדף לא מדויק?</b> בספר סרוק בלי טקסט העמוד משוער. מדפדפים לדף הנכון ולוחצים <b>"✓ זה המקום"</b> – התוכנה זוכרת לפעם הבאה.</div>
    </div>
  </div>
  <div class="foot"><span>חיפוש פלוס – מדריך</span><span>3</span></div>
</section>

<!-- ================= MEANING ================= -->
<section class="page">
  <h2><span class="n">4</span>💡 חיפוש לפי משמעות – שאלה ותשובה</h2>
  <div class="cols">
    <div class="grow">
      <p>בוחרים "💡 חיפוש לפי משמעות" וכותבים (או אומרים) רעיון או שאלה במילים שלך. התוכנה מוצאת את המקומות שעוסקים בזה – גם אם המילים עצמן לא מופיעות שם – וקוראת אותם משפט אחרי משפט כדי להביא את <b>המשפט שעונה</b>, עם שם הספר והעמוד.</p>
      <h3>🌙 בונים פעם אחת – בלילה</h3>
      <p>כדי שהשאלות יעבדו על כל המאגר, התוכנה צריכה "להבין" כל ספר פעם אחת:</p>
      <ol>
        <li>הגדרות › חיפוש לפי משמעות › <b>הורדת מודל המשמעות</b>.</li>
        <li>לוחצים <b>"🌙 בניית אינדקס המשמעות המלא"</b>.</li>
        <li>מחברים לטעינה ומשאירים ללילה. בהתראה ובמסך הבית רואים כמה ספרים נעשו וכמה זמן נשאר.</li>
      </ol>
      <p>בבוקר: <b>"💡 אינדקס המשמעות: מוכן לשאלות ✓"</b>. מכאן – כל שאלה, בלי אינטרנט.</p>
      <div class="tip">⚡ גם לפני שהאינדקס הושלם – אפשר לשאול. התוכנה קוראת במקום את העמודים שמזכירים את מילות השאלה ומשווה את המשמעות שלהם.</div>
      <div class="box"><b>BOOX: כדי שהעבודה בלילה לא תיעצר</b><br>הגדרות המכשיר › אפליקציות › ניהול אפליקציות › חיפוש פלוס › לכבות "הקפאה" ולאפשר ריצה ברקע.</div>
      <h3>טיפים</h3>
      <ul>
        <li>שאלה מלאה עובדת הכי טוב: "מה הדין אם שכח לברך ברכת המזון?"</li>
        <li>אפשר להתמקד: אחרי החיפוש לוחצים על תיקייה (חסידות › רבותינו נשיאינו…).</li>
        <li>אפשר לבחור תיקיות חשובות לאינדקס כדי שייבנה מהר יותר.</li>
      </ul>
    </div>
    <div>
      <div class="phone">
        <div class="ph-head"><div class="ph-logo">פ+</div><div class="ph-title">חיפוש פלוס</div></div>
        <div class="ph-search"><span class="q">מה מברכים על פרי העץ?</span><span class="mic">🎤</span><span class="go">חיפוש</span></div>
        <div class="ph-modes"><span>🔤 חיפוש מילים</span><span class="on">💡 לפי משמעות</span></div>
        <div class="ph-body">
          <div class="ph-answer"><div class="h">💡 התשובה מתוך הספרים שלך</div>
            <div style="font-size:8pt; margin:1mm 0">‟על פירות האילן הוא אומר בורא פרי העץ, חוץ מן היין, שעל היין הוא אומר בורא פרי הגפן”</div>
            <div class="src">📖 משנה ברכות, עמוד 74 · לפתיחה ◂</div></div>
          <div class="ph-row"><div class="t">▾ ברכות – וילנא <i>91%</i></div><div class="f">גמרא › ש״ס וילנא</div></div>
          <div class="ph-snip"><span class="pg">עמוד 78 (התאמה 91%)</span><br>…כיצד מברכין על הפירות? על <mark>פירות</mark> האילן אומר בורא <mark>פרי העץ</mark>…</div>
          <div class="ph-row"><div class="t">◂ שולחן ערוך הרב <i>87%</i></div><div class="f">הלכה › סדר ברכות הנהנין</div></div>
          <div class="ph-row"><div class="t">◂ קיצור שולחן ערוך <i>84%</i></div><div class="f">הלכה</div></div>
        </div>
      </div>
    </div>
  </div>
  <div class="foot"><span>חיפוש פלוס – מדריך</span><span>4</span></div>
</section>

<!-- ================= WORDS + READER ================= -->
<section class="page tight">
  <h2><span class="n">5</span>🔤 חיפוש מילים – מדויק ומהיר</h2>
  <div class="cols">
    <div class="grow">
      <table>
        <tr><th>אפשרות</th><th>מה היא עושה</th></tr>
        <tr><td><b>ביטוי מדויק</b></td><td>"רבי שמעון" – בדיוק בסדר הזה</td></tr>
        <tr><td><b>כל המילים בעמוד</b></td><td>כל המילים מופיעות באותו עמוד</td></tr>
        <tr><td><b>מילים קרובות</b></td><td>המילים קרובות זו לזו (עד 12 מילים)</td></tr>
        <tr><td><b>כולל ו׳ ה׳ ב׳ ל׳ ש׳…</b></td><td>"שמעון" ימצא גם "ושמעון", "לשמעון"</td></tr>
        <tr><td><b>כוכבית</b></td><td>ויצ* – ויצא, ויצאו, ויצאה…</td></tr>
      </table>
      <p style="margin-top:3mm">אחרי החיפוש: לוחצים על תיקייה כדי למקד (הכל › חסידות חב"ד › רבותינו נשיאינו), ממיינים לפי רלוונטיות / שם / תיקייה / גודל / תאריך, ופותחים ספר כדי לראות את כל העמודים עם קטע מודגש.</p>
      <p>הקלדה של הפניה ("ברכות כז") מציגה למעלה פס <b>"⤶ פתיחה"</b> שמביא ישר לדף.</p>
    </div>
  </div>

  <h2 style="margin-top:6mm"><span class="n">6</span>📖 פתיחת ספר – איפה שבחרת</h2>
  <div class="cols">
    <div class="grow">
      <h3>הקורא המובנה (מומלץ)</h3>
      <ul>
        <li>נפתח בדיוק בעמוד, <b>המילים שחיפשת מסומנות</b>.</li>
        <li>הקשה בצדדים / כפתורי עוצמה – דפדוף. צביטה – הגדלה.</li>
        <li>"תוצאה הבאה" קופצת לעמוד הבא שבו המילים מופיעות.</li>
        <li>חיפוש בתוך הספר, מועדפים, העתקת טקסט, המשך מהעמוד האחרון.</li>
      </ul>
      <h3>אפליקציה משלך כברירת מחדל (ReadEra וכו׳)</h3>
      <p>הגדרות › <b>באיזו אפליקציה לפתוח ספרים</b> › בוחרים פעם אחת – ומאז כל ספר נפתח שם ישר, בלי השאלה "פתיחה באמצעות". התוכנה שולחת את מספר העמוד, ומעתיקה את מילות החיפוש כדי שאפשר יהיה להדביק אותן בחיפוש של הקורא. אפשר לשנות בכל רגע.</p>
      <div class="tip">📖 <b>ReadEra ודומיו:</b> התוכנה שולחת לאפליקציה את העמוד שנמצא ו-4 העמודים שאחריו, עם המילים מסומנות בצהוב – כך הקורא נפתח ישר על המקום, מסומן.<br>📜 <b>ספרים סרוקים:</b> המילים מזוהות בתוך התמונה ומסומנות; ב-⋯ › "תצוגת טקסט" רואים את העמוד כטקסט מסודר.</div>
    </div>
    <div>
      <div class="phone">
        <div class="ph-refbar"><span>⤶ לקוטי שיחות חלק ד – נח</span><span>✓ זה המקום ✕</span></div>
        <div class="ph-body">
          <div class="ph-page"><div class="hdr">לקוטי שיחות · נח · 58</div>
          א. עה״פ אלה תולדות <mark>נח</mark> <mark>נח</mark> איש צדיק תמים הי׳ בדורותיו, ופירש״י: ״הואיל והזכירו סיפר בשבחו״. וצריך להבין… ואח״כ ממשיך ״את האלקים התהלך <mark>נח</mark>״, ובפשטות צריך ביאור מהו החידוש שבזה…
          <br><br>ב. ויש לומר הביאור בזה, דהנה ידוע שכל עניני התורה הם הוראה בעבודת האדם…</div>
          <div style="display:flex; justify-content:space-between; color:var(--acc); font-weight:800; font-size:7pt; padding-top:1mm"><span>הקודם ▶</span><span>עמוד 64 / 412</span><span>◀ הבא</span></div>
        </div>
      </div>
    </div>
  </div>
  <div class="foot"><span>חיפוש פלוס – מדריך</span><span>5</span></div>
</section>

<!-- ================= ORGANIZE + FAQ ================= -->
<section class="page tight">
  <h2><span class="n">7</span>🗂 סידור המאגר</h2>
  <p>התוכנה כבר מכירה את כל הספרים מהסריקה, ולכן הסידור מהיר ולא קורא את הספרים מחדש.</p>
  <ul>
    <li><b>ספרים כפולים</b> – לפי התוכן, לא לפי השם (אותו ספר שנשמר פעמיים בשמות שונים).</li>
    <li><b>סידור תיקייה</b> – תצוגה מקדימה שאפשר לערוך לפני שמחילים: לפי נושא, סדרה, אות, או לתיקיות המאגר.</li>
    <li><b>שמות חכמים</b> – שם לפי דף השער והתוכן, עם אישור על כל שינוי. הכול ניתן לביטול.</li>
  </ul>

  <h2 style="margin-top:6mm"><span class="n">8</span>☁ ספרים מ-Google Drive</h2>
  <p>☰ › "ספרים מ-Google Drive" › "חיבור לחשבון": מתחברים פעם אחת, בוחרים תיקיות, והספרים נכנסים למאגר – לחיפוש, לחיפוש הקולי ולשאלות, בתוך המכשיר.</p>
  <h2 style="margin-top:6mm"><span class="n">9</span>יציבות ו-BOOX</h2>
  <ul>
    <li>מותאם ל-BOOX Go 6 (אנדרואיד 11, 2GB RAM, מסך E-ink): ניגודיות גבוהה, בלי אנימציות, דפדוף בעמודים שלמים.</li>
    <li>שני מודלי AI אף פעם לא נטענים יחד. כשהמכשיר עמוס – המודלים משתחררים ונטענים שוב כשצריך.</li>
    <li>בזמן שמדברים, הסריקה ברקע עוצרת לרגע ומפנה לך את המעבד.</li>
    <li>אם משהו משתבש – התוכנה לא נסגרת בגלל ספר אחד פגום, והפרטים נשמרים להעתקה ושליחה.</li>
  </ul>

  <h2 style="margin-top:6mm"><span class="n">10</span>שאלות נפוצות</h2>
  <h3>החיפוש הקולי לא הבין אותי</h3>
  <p>מדברים ברור, קרוב למכשיר, ואומרים את שם הספר בהתחלה ("ברכות דף…"). בהגדרות › חיפוש קולי › "בדיקת החיפוש הקולי" אפשר לראות בדיוק מה נשמע. אם בחרתם במודל המהיר – עברו למדויק.</p>
  <h3>"לא נמצא ספר כזה במאגר"</h3>
  <p>הסריקה עוד רצה, או ששם הקובץ שונה מאוד. אפשר לחפש את שם הספר בחיפוש מילים, או לתת לקובץ "שם חכם" בסידור המאגר.</p>
  <h3>החיפוש לפי משמעות לא מוצא כלום</h3>
  <p>בהגדרות רואים כמה ספרים כבר באינדקס המשמעות, ואם הייתה שגיאה – היא כתובה שם (⚠). הפעילו "🌙 בניית אינדקס המשמעות המלא" והשאירו בטעינה.</p>
  <h3>איך מעדכנים?</h3>
  <p>התוכנה בודקת פעם ביום ומציעה להתקין גרסה חדשה. אפשר גם: הגדרות › בדיקת עדכונים עכשיו.</p>

  <div class="box" style="margin-top:6mm"><b>קוד פתוח</b> · <span dir="ltr">github.com/sw0555041250-code/Search-Plus-</span> · כל הקוד, ההסבר למפתחים (HANDOFF.md) והגרסאות.<br>פותח על ידי <b>שניאור וילהלם</b> · מופץ בחינם לתועלת הרבים<br><b style="color:var(--acc)">יחי אדוננו מורנו ורבנו מלך המשיח לעולם ועד</b></div>
  <div class="foot"><span>חיפוש פלוס – מדריך</span><span>6</span></div>
</section>
</body>
</html>
```

## `docs/site/index.html`

```html
<!doctype html>
<html lang="he" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>חיפוש פלוס – אומרים מה מחפשים, והספר נפתח במקום</title>
<meta name="description" content="חיפוש פלוס: חיפוש מעמיק בתוך כל ספרי ה-PDF שבמכשיר – במילים, לפי משמעות ובקול. בינה מלאכותית שעובדת בתוך המכשיר, בלי אינטרנט. חינם.">
<meta property="og:title" content="חיפוש פלוס">
<meta property="og:description" content="אומרים מה מחפשים – והספר נפתח בדיוק במקום. חיפוש קולי, שאלה ותשובה, וחיפוש מילים בתוך כל הספרים שלך.">
<link rel="icon" href="icon.png">
<style>
@font-face { font-family: Heebo; src: url(fonts/heebo-hebrew.woff2) format("woff2"); font-weight: 100 900; unicode-range: U+0590-05FF, U+200C-2010, U+20AA, U+25CC, U+FB1D-FB4F; }
@font-face { font-family: Heebo; src: url(fonts/heebo-latin.woff2) format("woff2"); font-weight: 100 900; }
:root { --acc: #3B4FD8; --acc2: #7B3FE4; --ink: #111318; --sub: #5B6070; --bg: #F7F8FC; --card: #fff; --line: #E1E4EE; --gold: #F2C14E; --light: #E8EAFF; }
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body { margin: 0; font-family: Heebo, system-ui, sans-serif; color: var(--ink); background: var(--bg); font-size: 18px; line-height: 1.6; }
a { color: var(--acc); }
.wrap { max-width: 1120px; margin: 0 auto; padding: 0 22px; }
nav { position: sticky; top: 0; z-index: 10; background: rgba(247,248,252,.86); backdrop-filter: blur(10px); border-bottom: 1px solid var(--line); }
nav .wrap { display: flex; align-items: center; gap: 22px; height: 64px; }
nav .logo { font-weight: 900; font-size: 21px; display: flex; align-items: center; gap: 10px; color: var(--ink); text-decoration: none; }
nav .logo b { background: linear-gradient(135deg, var(--acc), var(--acc2)); color: #fff; border-radius: 10px; padding: 2px 9px; }
nav .links { display: flex; gap: 20px; margin-right: auto; font-size: 16px; }
nav .links a { color: var(--sub); text-decoration: none; }
.btn { display: inline-flex; align-items: center; gap: 10px; background: linear-gradient(135deg, var(--acc), var(--acc2)); color: #fff; text-decoration: none; font-weight: 800; border-radius: 40px; padding: 15px 30px; font-size: 19px; box-shadow: 0 10px 26px rgba(59,79,216,.35); transition: transform .2s; }
.btn:hover { transform: translateY(-2px); }
.btn.ghost { background: #fff; color: var(--acc); box-shadow: none; border: 2px solid var(--acc); }
.btn.small { padding: 9px 18px; font-size: 15px; }
header.hero { background: radial-gradient(900px 600px at 15% 10%, #6F7CFF 0%, #3B4FD8 40%, #23308f 100%); color: #fff; overflow: hidden; }
.hero .wrap { display: flex; align-items: center; gap: 50px; padding-top: 70px; padding-bottom: 80px; }
.hero h1 { font-size: clamp(42px, 6vw, 72px); line-height: 1.04; margin: 0 0 18px; font-weight: 900; }
.hero p.lead { font-size: 22px; color: #e3e7ff; margin: 0 0 30px; max-width: 560px; }
.hero .btn { background: #fff; color: var(--acc); box-shadow: 0 14px 30px rgba(0,0,0,.25); }
.hero .btn.ghost { background: transparent; color: #fff; border-color: rgba(255,255,255,.6); }
.hero .ctas { display: flex; gap: 14px; flex-wrap: wrap; }
.hero .badges { margin-top: 26px; display: flex; gap: 10px; flex-wrap: wrap; font-size: 15px; }
.hero .badges span { background: rgba(255,255,255,.14); border: 1px solid rgba(255,255,255,.28); border-radius: 30px; padding: 4px 14px; }
.hero-text { flex: 1.2; }
section { padding: 80px 0; }
h2 { font-size: clamp(30px, 4vw, 44px); margin: 0 0 12px; font-weight: 900; line-height: 1.15; }
.kicker { color: var(--acc); font-weight: 800; letter-spacing: .5px; font-size: 16px; margin-bottom: 6px; }
.intro { color: var(--sub); font-size: 20px; max-width: 760px; margin: 0 0 40px; }
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; }
.feat { background: var(--card); border: 1px solid var(--line); border-radius: 22px; padding: 26px; box-shadow: 0 6px 20px rgba(17,19,24,.04); }
.feat .ic { width: 54px; height: 54px; border-radius: 16px; display: flex; align-items: center; justify-content: center; font-size: 27px; margin-bottom: 14px; color: #fff; }
.feat h3 { margin: 0 0 6px; font-size: 21px; }
.feat p { margin: 0; color: var(--sub); font-size: 16.5px; }
.g1 { background: linear-gradient(135deg, #3B4FD8, #6F7CFF); } .g2 { background: linear-gradient(135deg, #7B3FE4, #B06BFF); }
.g3 { background: linear-gradient(135deg, #0E9F8E, #35CDB4); } .g4 { background: linear-gradient(135deg, #E0712C, #FFA65C); }
.g5 { background: linear-gradient(135deg, #D6336C, #FF6F9B); } .g6 { background: linear-gradient(135deg, #475066, #7A8499); }
.split { display: flex; gap: 60px; align-items: center; }
.split.rev { flex-direction: row-reverse; }
.split .txt { flex: 1; }
.say { display: inline-block; background: var(--light); color: var(--acc); border-radius: 30px; padding: 4px 14px; margin: 4px 2px; font-weight: 700; font-size: 16px; }
ul.check { list-style: none; padding: 0; margin: 18px 0 0; }
ul.check li { padding-right: 32px; position: relative; margin-bottom: 10px; }
ul.check li::before { content: '✓'; position: absolute; right: 0; top: 0; color: #0E9F8E; font-weight: 900; }
.band { background: #fff; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; text-align: center; }
.stats b { display: block; font-size: 40px; font-weight: 900; background: linear-gradient(135deg, var(--acc), var(--acc2)); -webkit-background-clip: text; background-clip: text; color: transparent; }
.stats span { color: var(--sub); font-size: 16px; }
.video { border-radius: 22px; overflow: hidden; box-shadow: 0 24px 60px rgba(17,19,24,.25); background: #000; }
.video video { width: 100%; display: block; }
.steps { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; counter-reset: s; }
.step { background: var(--card); border: 1px solid var(--line); border-radius: 22px; padding: 26px; position: relative; }
.step::before { counter-increment: s; content: counter(s); position: absolute; top: -18px; right: 24px; width: 40px; height: 40px; border-radius: 50%; background: linear-gradient(135deg, var(--acc), var(--acc2)); color: #fff; font-weight: 900; display: flex; align-items: center; justify-content: center; }
.step h3 { margin: 8px 0 6px; }
.step p { margin: 0; color: var(--sub); font-size: 16.5px; }
details { background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 16px 20px; margin-bottom: 12px; }
summary { font-weight: 800; cursor: pointer; font-size: 18.5px; }
details p { color: var(--sub); margin: 10px 0 0; }
.contact { background: linear-gradient(135deg, #23308f, #3B4FD8 60%, #7B3FE4); color: #fff; border-radius: 28px; padding: 50px; text-align: center; }
.contact p { color: #e3e7ff; font-size: 20px; }
footer { color: var(--sub); text-align: center; padding: 30px 0 50px; font-size: 15px; }

/* phone mockups */
.phone { width: 300px; flex: none; border: 10px solid #111; border-radius: 40px; background: #fff; overflow: hidden; box-shadow: 0 30px 70px rgba(0,0,0,.35); color: var(--ink); font-size: 12.5px; line-height: 1.4; }
.ph-head { display: flex; align-items: center; gap: 8px; padding: 14px 12px 8px; }
.ph-logo { width: 28px; height: 28px; border-radius: 8px; background: linear-gradient(135deg, var(--acc), var(--acc2)); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 900; font-size: 12px; }
.ph-title { font-weight: 900; font-size: 15px; }
.ph-search { margin: 4px 10px 8px; border: 2px solid var(--acc); border-radius: 20px; background: #F4F5F8; display: flex; align-items: center; padding: 4px 6px 4px 10px; gap: 6px; }
.ph-search .q { flex: 1; font-size: 13.5px; font-weight: 500; }
.ph-search .go { background: var(--acc); color: #fff; border-radius: 14px; padding: 3px 10px; font-weight: 700; font-size: 11.5px; }
.ph-body { padding: 4px 10px 12px; }
.ph-hero { background: linear-gradient(135deg, var(--acc), var(--acc2)); color: #fff; border-radius: 14px; padding: 10px 12px; }
.tiles { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; margin-top: 8px; }
.tiles div { border-radius: 12px; color: #fff; padding: 8px; font-weight: 800; font-size: 12px; }
.tiles div i { display: block; font-style: normal; font-size: 17px; }
.row { border-bottom: 1px solid var(--line); padding: 8px 4px; }
.row .t { font-weight: 800; font-size: 13px; display: flex; justify-content: space-between; }
.row .t i { font-style: normal; background: var(--acc); color: #fff; border-radius: 9px; padding: 0 7px; font-size: 11px; }
.row .f { color: var(--sub); font-size: 11px; }
.answer { background: #FFF7DD; border: 2px solid #D8B040; border-radius: 12px; padding: 9px 11px; margin: 4px 0 8px; }
.answer .h { color: var(--acc); font-weight: 800; font-size: 11.5px; }
.answer .s { color: var(--acc); font-weight: 700; font-size: 11px; }
.mic { text-align: center; padding: 16px 10px; background: linear-gradient(135deg, var(--acc), #6F7CFF); border-radius: 18px; color: #fff; margin: 4px 0 10px; }
.mic .c { width: 90px; height: 90px; border-radius: 50%; background: #fff; margin: 0 auto 8px; display: flex; align-items: center; justify-content: center; font-size: 40px; box-shadow: 0 0 0 10px rgba(255,255,255,.18); animation: pulse 2s infinite; }
@keyframes pulse { 50% { box-shadow: 0 0 0 18px rgba(255,255,255,.08); } }
.heard { border: 2px solid var(--acc); border-radius: 18px; padding: 6px 12px; font-weight: 700; margin: 6px 0; font-size: 13.5px; }
.und { color: var(--acc); font-weight: 800; margin: 6px 2px; }
.card { border: 1px solid var(--acc); background: var(--light); border-radius: 12px; padding: 8px 10px; margin: 5px 0; }
.card b { display: block; font-size: 13px; }
.card span { color: var(--sub); font-size: 11px; }
mark { background: #FFE38A; font-weight: 800; padding: 0 2px; }
.reveal { opacity: 0; transform: translateY(30px); transition: all .8s cubic-bezier(.2,.8,.2,1); }
.reveal.in { opacity: 1; transform: none; }
@media (max-width: 860px) {
  body { font-size: 17px; }
  .hero .wrap, .split, .split.rev { flex-direction: column; }
  .grid, .steps { grid-template-columns: 1fr; }
  .stats { grid-template-columns: 1fr 1fr; }
  nav .links { display: none; }
  nav .btn.small { margin-right: auto; }
  .contact { padding: 34px 20px; }
  section { padding: 56px 0; }
}
</style>
</head>
<body>
<nav><div class="wrap">
  <a class="logo" href="#"><b>פ+</b>חיפוש פלוס</a>
  <div class="links"><a href="#features">יכולות</a><a href="#voice">חיפוש קולי</a><a href="#qa">שאלה ותשובה</a><a href="#video">סרטון</a><a href="#install">התקנה</a><a href="#faq">שאלות</a></div>
  <a class="btn small" href="https://github.com/sw0555041250-code/Search-Plus-/releases/latest/download/SearchPlus.apk">⬇ הורדה</a>
</div></nav>

<header class="hero"><div class="wrap">
  <div class="hero-text">
    <h1>אומרים מה מחפשים –<br>והספר נפתח במקום.</h1>
    <p class="lead">חיפוש פלוס קורא את כל ספרי ה-PDF שלך – אלפי ספרים, גם בכרטיס הזיכרון – ומוצא כל מקום: לפי מילים, לפי משמעות, ובקול. הבינה המלאכותית רצה בתוך המכשיר, בלי אינטרנט.</p>
    <div class="ctas">
      <a class="btn" href="https://github.com/sw0555041250-code/Search-Plus-/releases/latest/download/SearchPlus.apk">⬇ הורדה חינם לאנדרואיד</a>
      <a class="btn ghost" href="#video">▶ לצפייה בסרטון</a>
    </div>
    <div class="badges"><span>🎤 חיפוש קולי בעברית</span><span>💡 שאלה ותשובה</span><span>📴 בלי אינטרנט</span><span>📖 BOOX ו-E-ink</span><span>❤ חינם</span></div>
  </div>
  <div class="phone">
    <div class="ph-head"><div class="ph-logo">פ+</div><div class="ph-title">חיפוש פלוס</div><span style="margin-right:auto;color:var(--acc);font-weight:800">☰</span></div>
    <div class="ph-search"><span class="q" style="color:#8a8a8a">מה לחפש? או לחצו 🎤</span><span>🎤</span><span class="go">חיפוש</span></div>
    <div class="ph-body">
      <div class="ph-hero"><b style="font-size:14px">ערב טוב! מה נלמד היום?</b><div style="opacity:.85">אלפי ספרים · כל עמוד ניתן לחיפוש</div></div>
      <div class="tiles">
        <div class="g1"><i>🎤</i>חיפוש קולי</div><div class="g2"><i>💡</i>שאלה ותשובה</div>
        <div class="g3"><i>🔤</i>חיפוש מילים</div><div class="g4"><i>📚</i>עיון בספרים</div>
        <div class="g5"><i>🗂</i>סידור המאגר</div><div class="g6"><i>⚙</i>הגדרות</div>
      </div>
    </div>
  </div>
</div></header>

<div class="band"><div class="wrap" style="padding:36px 22px">
  <div class="stats">
    <div><b>אלפי</b><span>ספרים נסרקים ונזכרים</span></div>
    <div><b>שניות</b><span>מהמילה הנאמרת עד העמוד</span></div>
    <div><b>0</b><span>חיבור לאינטרנט נדרש אחרי ההתקנה</span></div>
    <div><b>100%</b><span>חינם, קוד פתוח</span></div>
  </div>
</div></div>

<section id="features"><div class="wrap">
  <div class="kicker">מה יש בפנים</div>
  <h2>כל הספרייה שלך – בכיס, ומבינה אותך</h2>
  <p class="intro">במקום לחפש את הכרך, לדפדף ולנחש – אומרים או כותבים, ומגיעים. כל יכולת היא מסך משלה, וכולן נגישות מהמסך הראשי ומהתפריט הצדדי.</p>
  <div class="grid">
    <div class="feat reveal"><div class="ic g1">🎤</div><h3>חיפוש קולי</h3><p>"ברכות דף כ״ז עמוד א" – וכל מהדורות הגמרא שלך נפתחות בדף. "ליקוטי שיחות חלק ד׳ עמוד 58" – הכרך הנכון, בעמוד המודפס.</p></div>
    <div class="feat reveal"><div class="ic g2">💡</div><h3>שאלה ותשובה</h3><p>שואלים במילים שלכם ומקבלים את המשפט שעונה, עם שם הספר והעמוד – גם כשהמילים עצמן לא מופיעות שם.</p></div>
    <div class="feat reveal"><div class="ic g3">🔤</div><h3>חיפוש מילים בתוך הספרים</h3><p>בכל עמוד של כל ספר, לא רק בשמות. כולל ו׳ ה׳ ב׳ ל׳ ש׳, ביטוי מדויק, מילים קרובות וכוכבית.</p></div>
    <div class="feat reveal"><div class="ic g4">📖</div><h3>נפתח בדיוק במקום</h3><p>בקורא המובנה עם המילים מסומנות – או ב-ReadEra וכל קורא שתבחרו כברירת מחדל.</p></div>
    <div class="feat reveal"><div class="ic g5">🗂</div><h3>סידור המאגר</h3><p>ספרים כפולים לפי התוכן, סידור תיקיות עם תצוגה מקדימה, ושמות חכמים לקבצים – הכול ניתן לביטול.</p></div>
    <div class="feat reveal"><div class="ic g6">🔍</div><h3>גם ספרים ישנים וסרוקים</h3><p>תיקון אוטומטי של עברית הפוכה וקידודים ישנים, וזיהוי טקסט (OCR) עברי לעמודים סרוקים.</p></div>
  </div>
</div></section>

<section id="voice" class="band"><div class="wrap split">
  <div class="txt reveal">
    <div class="kicker">🎤 חיפוש קולי</div>
    <h2>מדברים. רואים מה הובן. מגיעים.</h2>
    <p class="intro" style="margin-bottom:14px">מסך קולי משלו, עם מיקרופון גדול. התוכנה מראה מה שמעה, מה הבינה, ולאן זה מוביל – כל המהדורות, או הספרים שמכילים את המילים.</p>
    <div><span class="say">מסכת ברכות דף כ״ז עמוד א</span><span class="say">גמרא ברכות דף כף זין</span><span class="say">ליקוטי שיחות חלק ד׳ שיחה לפרשת נח</span><span class="say">ליקוטי שיחות חלק ד׳ עמוד 58</span><span class="say">תניא פרק ה</span><span class="say">שולחן ערוך אורח חיים סימן קכח</span></div>
    <ul class="check"><li>מבין עברית ושמות ספרים – גם "כף זין" וגם "כ״ז"</li><li>עובד בתוך המכשיר, בלי אינטרנט</li><li>הדף לא מדויק בספר סרוק? "✓ זה המקום" – והתוכנה לומדת</li></ul>
  </div>
  <div class="phone reveal">
    <div class="ph-head"><div class="ph-logo">פ+</div><div class="ph-title">🎤 חיפוש קולי</div></div>
    <div class="ph-body">
      <div class="mic"><div class="c">🎤</div><b style="font-size:15px">לחצו ודברו</b><div style="font-size:11px;opacity:.9">זיהוי בתוך המכשיר – בלי אינטרנט</div></div>
      <div style="color:var(--sub);font-size:11px;font-weight:700">שמעתי</div>
      <div class="heard">ברכות דף כ״ז עמוד א</div>
      <div class="und">הבנתי: ברכות דף כז ע״א · נמצא ב-3 ספרים</div>
      <div class="card"><b>📖 ברכות – ש״ס וילנא</b><span>גמרא › וילנא · עמוד 57</span></div>
      <div class="card"><b>📖 ברכות – עוז והדר</b><span>גמרא › עוז והדר · עמוד 61</span></div>
      <div class="card"><b>📖 ברכות ב – שוטנשטיין</b><span>גמרא › שוטנשטיין · עמוד 212</span></div>
    </div>
  </div>
</div></section>

<section id="qa"><div class="wrap split rev">
  <div class="txt reveal">
    <div class="kicker">💡 שאלה ותשובה</div>
    <h2>שואלים שאלה – ומקבלים את המשפט שעונה</h2>
    <p class="intro" style="margin-bottom:14px">מודל בינה מלאכותית שמבין עברית לומד את כל המאגר פעם אחת – מחברים לטעינה ומשאירים ללילה. מהבוקר, כל שאלה, בלי אינטרנט.</p>
    <ul class="check"><li>מוצא מקומות שעוסקים ברעיון, גם בלי אותן מילים</li><li>מביא את המשפט המדויק, עם הספר והעמוד</li><li>עובד כבר לפני שהאינדקס הושלם</li></ul>
  </div>
  <div class="phone reveal">
    <div class="ph-head"><div class="ph-logo">פ+</div><div class="ph-title">💡 שאלה ותשובה</div></div>
    <div class="ph-search"><span class="q">מה מברכים על פרי העץ?</span><span class="go">חיפוש</span></div>
    <div class="ph-body">
      <div class="answer"><div class="h">💡 התשובה מתוך הספרים שלך</div><div style="font-size:13.5px;margin:4px 0">‟על פירות האילן הוא אומר בורא פרי העץ, חוץ מן היין, שעל היין הוא אומר בורא פרי הגפן”</div><div class="s">📖 משנה ברכות פרק ו · לפתיחה ◂</div></div>
      <div class="row"><div class="t">ברכות – ש״ס וילנא <i>91%</i></div><div class="f">גמרא</div></div>
      <div class="row"><div class="t">שולחן ערוך הרב <i>88%</i></div><div class="f">הלכה › סדר ברכות הנהנין</div></div>
      <div class="row"><div class="t">קיצור שולחן ערוך <i>84%</i></div><div class="f">הלכה</div></div>
    </div>
  </div>
</div></section>

<section id="video" class="band"><div class="wrap">
  <div class="kicker">▶ בשתי דקות וחצי</div>
  <h2>רואים את זה עובד</h2>
  <p class="intro">מהמיקרופון ועד העמוד – חיפוש קולי, כל המהדורות, שאלה ותשובה, ובנייה בלילה.</p>
  <div class="video reveal"><video controls preload="metadata" poster="poster.jpg" src="SearchPlus-Video.mp4"></video></div>
</div></section>

<section id="install"><div class="wrap">
  <div class="kicker">⬇ התקנה</div>
  <h2>שלושה צעדים, ואתם בפנים</h2>
  <p class="intro">התוכנה לא בחנות Play (עדיין), ולכן אנדרואיד שואל לפני ההתקנה – זה רגיל ובטוח.</p>
  <div class="steps">
    <div class="step reveal"><h3>מורידים</h3><p>לוחצים <a href="https://github.com/sw0555041250-code/Search-Plus-/releases/latest/download/SearchPlus.apk">הורדה</a> במכשיר. אם הדפדפן שואל "להוריד בכל זאת?" – מאשרים.</p></div>
    <div class="step reveal"><h3>מאשרים התקנה</h3><p>פותחים את הקובץ. כשמופיע "מטעמי אבטחה…" › <b>הגדרות</b> › מפעילים "אפשר ממקור זה" › חוזרים › <b>התקנה</b>. אם Play Protect שואל – "פרטים נוספים" › <b>"התקנה בכל זאת"</b>.</p></div>
    <div class="step reveal"><h3>בוחרים תיקייה</h3><p>נותנים הרשאה לקבצים ובוחרים את תיקיית הספרים. הסריקה רצה ברקע – ומחפשים מיד. העדכונים מגיעים מעכשיו לבד.</p></div>
  </div>
  <p style="text-align:center;margin-top:40px"><a class="btn" href="https://github.com/sw0555041250-code/Search-Plus-/releases/latest/download/SearchPlus.apk">⬇ הורדת חיפוש פלוס</a>&nbsp; <a class="btn ghost" href="https://github.com/sw0555041250-code/Search-Plus-/releases/download/media/SearchPlus-Guide.pdf">📄 המדריך המלא (PDF)</a></p>
</div></section>

<section id="faq" class="band"><div class="wrap">
  <div class="kicker">❓ שאלות נפוצות</div>
  <h2>שאלות ותשובות</h2>
  <details><summary>זה באמת עובד בלי אינטרנט?</summary><p>כן. מורידים פעם אחת את מודל הדיבור ואת מודל המשמעות (כמה מאות MB), ומאז הכול – סריקה, חיפוש, זיהוי דיבור ושאלות – קורה בתוך המכשיר. האינטרנט נדרש רק לעדכונים.</p></details>
  <details><summary>על איזה מכשירים זה עובד?</summary><p>כל מכשיר אנדרואיד 8 ומעלה – טלפון, טאבלט, וקוראי E-ink כמו BOOX. בקוראי E-ink התוכנה עוברת לבד לתצוגת שחור-לבן בניגודיות גבוהה; בטלפון היא צבעונית.</p></details>
  <details><summary>כמה מקום זה תופס? זה משכפל לי את הספרים?</summary><p>לא משכפל. התוכנה שומרת רק את הטקסט של העמודים (אינדקס), שהוא קטן פי כמה מהקבצים עצמם. הספרים נשארים איפה שהם.</p></details>
  <details><summary>יש לי 6,000 ספרים – כמה זמן לוקחת הסריקה?</summary><p>הסריקה הראשונה רצה ברקע – ספרים קטנים קודם, כך שרוב המאגר ניתן לחיפוש תוך זמן קצר. את אינדקס המשמעות כדאי לבנות לילה אחד בטעינה. אחרי זה נסרקים רק ספרים חדשים.</p></details>
  <details><summary>אנדרואיד חוסם את ההתקנה. מה עושים?</summary><p>מאשרים "התקנה ממקורות לא ידועים" לדפדפן או למנהל הקבצים (הגדרות › אפליקציות › גישה מיוחדת › התקנת אפליקציות לא מוכרות). אם Play Protect מזהיר – "פרטים נוספים" › "התקנה בכל זאת". כל הגרסאות חתומות באותו מפתח ונבנות אוטומטית מקוד פתוח ב-GitHub.</p></details>
  <details><summary>ספרים פותחים לי באפליקציה אחרת?</summary><p>בפעם הראשונה בוחרים: הקורא המובנה (עמוד מדויק + סימון המילים), או כל אפליקציה שיכולה לפתוח PDF – ReadEra, NeoReader ועוד. אפשר לשנות בהגדרות.</p></details>
  <details><summary>זה עולה כסף?</summary><p>לא. חיפוש פלוס חינם לגמרי ומופץ לתועלת הרבים. הקוד פתוח ב-GitHub.</p></details>
</div></section>

<section id="contact"><div class="wrap">
  <div class="contact reveal">
    <h2>יש שאלה, רעיון או ספר שלא נמצא?</h2>
    <p>נשמח לשמוע – כל הצעה הופכת את חיפוש פלוס לטוב יותר.</p>
    <p><a class="btn" style="background:#fff;color:var(--acc)" href="mailto:sw0555041250@gmail.com?subject=%D7%97%D7%99%D7%A4%D7%95%D7%A9%20%D7%A4%D7%9C%D7%95%D7%A1">✉ sw0555041250@gmail.com</a></p>
  </div>
</div></section>

<footer>פותח על ידי <b>שניאור וילהלם</b> · מופץ בחינם לתועלת הרבים · <a href="https://github.com/sw0555041250-code/Search-Plus-">קוד פתוח ב-GitHub</a></footer>

<script>
const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); } }), { threshold: .15 });
document.querySelectorAll('.reveal').forEach(el => io.observe(el));
</script>
</body>
</html>
```

## `docs/video/make_audio.py`

```python
"""Hebrew narration for the video: one clip per scene (Microsoft neural voice via edge-tts),
then a single narration track where every scene lasts exactly as long as its clip + a pause.
Usage: python make_audio.py <out_dir>   →  durations.json, narration.wav
"""
import asyncio
import json
import os
import subprocess
import sys

import edge_tts

HERE = os.path.dirname(os.path.abspath(__file__))
out = os.path.abspath(sys.argv[1])
os.makedirs(out, exist_ok=True)
scenes = json.load(open(os.path.join(HERE, "script.json"), encoding="utf-8"))
VOICE = os.environ.get("VOICE", "he-IL-AvriNeural")


def duration(p):
    return float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p]).decode().strip())


async def main():
    durs = []
    parts = []
    for i, s in enumerate(scenes):
        mp3 = os.path.join(out, f"s{i}.mp3")
        await edge_tts.Communicate(s["say"], VOICE, rate="+4%").save(mp3)
        d = duration(mp3)
        lead = 0.5 if i else 1.0
        ms = int((lead + d + 0.8) * 1000)
        if s["id"] == "outro":
            ms += 2500
        durs.append({"id": s["id"], "ms": ms})
        wav = os.path.join(out, f"s{i}.wav")
        # silence before, the clip, silence until the scene ends
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", mp3, "-af",
                        f"adelay={int(lead * 1000)}|{int(lead * 1000)},apad", "-t", f"{ms / 1000:.3f}",
                        "-ar", "44100", "-ac", "2", wav], check=True)
        parts.append(wav)
        print(s["id"], round(d, 1), "s")
    lst = os.path.join(out, "list.txt")
    with open(lst, "w") as f:
        for p in parts:
            f.write(f"file '{p}'\n")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy",
                    os.path.join(out, "narration.wav")], check=True)
    json.dump(durs, open(os.path.join(out, "durations.json"), "w"))
    print("total", sum(d["ms"] for d in durs) / 1000, "s")


asyncio.run(main())
```

## `docs/video/record.js`

```javascript
// Records docs/video/video.html into a video (silent). Usage: node record.js <durations.json> <out_dir>
// durations.json: [{"id": "...", "ms": 5200}, ...] in the order of script.json.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const script = JSON.parse(fs.readFileSync(path.join(__dirname, 'script.json'), 'utf8'));
  const durs = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const out = process.argv[3];
  const scenes = script.map((s, i) => ({ ...s, ms: durs[i].ms }));
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 }, recordVideo: { dir: out, size: { width: 1280, height: 720 } } });
  const page = await ctx.newPage();
  const t0 = Date.now();
  await page.goto('file://' + path.join(__dirname, 'video.html'));
  await page.evaluate(() => document.fonts.ready);
  const lead = Date.now() - t0;
  fs.writeFileSync(path.join(out, 'lead_ms.txt'), String(lead));
  await page.evaluate(sc => { window.run(sc); }, scenes);
  await page.waitForFunction(() => window.done === true, null, { timeout: 15 * 60 * 1000, polling: 500 });
  await page.waitForTimeout(800);
  await ctx.close();
  await browser.close();
})();
```

## `docs/video/script.json`

```json
[
 {
  "id": "intro",
  "title": "חיפוש פלוס",
  "sub": "אומרים מה מחפשים – והספר נפתח בדיוק במקום",
  "say": "הכירו את חיפוש פלוס. אלפי ספרים במכשיר אחד, ועכשיו אפשר פשוט לבקש, ולהגיע בדיוק למקום."
 },
 {
  "id": "library",
  "title": "כל המאגר שלך – מוכר ומסודר",
  "sub": "גמרא · חסידות · הלכה · מפרשים · וכל תיקייה נידחת",
  "say": "התוכנה קוראת פעם אחת את כל ספרי הפי די אף שבמכשיר ובכרטיס הזיכרון, גם בתיקיות הכי נידחות, ובונה לעצמה אינדקס חכם. בלי לשכפל את הספרים, ובלי אינטרנט."
 },
 {
  "id": "voice",
  "title": "🎤 לוחצים ומדברים",
  "sub": "״ליקוטי שיחות חלק ד׳, שיחה לפרשת נח״",
  "say": "לוחצים על המיקרופון, ואומרים: ליקוטי שיחות, חלק ד, שיחה לפרשת נח. הבינה המלאכותית מזהה את הדיבור בתוך המכשיר עצמו,"
 },
 {
  "id": "open",
  "title": "נפתח בדיוק במקום",
  "sub": "הכרך הנכון · הפרשה הנכונה · המילים מסומנות",
  "say": "ותוך שניות, הכרך הנכון נפתח, בדיוק בתחילת השיחה. לא צריך לחפש את הכרך, לא צריך לדפדף."
 },
 {
  "id": "gemara",
  "title": "כל המהדורות – באותו דף",
  "sub": "״ברכות דף כ״ז עמוד א׳״",
  "say": "גם בגמרא. אומרים: ברכות, דף כז, עמוד א. ואם יש לכם כמה מהדורות, וילנא, עוז והדר, שוטנשטיין, כולן מופיעות, וכל אחת נפתחת באותו הדף."
 },
 {
  "id": "page",
  "title": "גם לפי מספר העמוד המודפס",
  "sub": "״ליקוטי שיחות חלק ד׳ עמוד 58״",
  "say": "ואפשר גם לפי מספר העמוד שמודפס בספר. התוכנה לומדת לבד את המספור של כל כרך."
 },
 {
  "id": "quick",
  "title": "⚡ מעבר מהיר – בכתיבה",
  "sub": "״לקו״ש חלק כ״ד עמוד 358״ · ״שו״ע אדה״ז סימן רע״ח״",
  "say": "מעדיפים לכתוב? במסך המעבר המהיר כותבים בקיצור, כמו שרגילים: לקו״ש חלק כד עמוד שלוש מאות חמישים ושמונה, או שו״ע אדה״ז סימן רעח. התוכנה מכירה את השמות, את ראשי התיבות ואת שמות התיקיות, ואם יש כמה אפשרויות, היא מציעה אותן ממוספרות, אחת, שתיים, שלוש."
 },
 {
  "id": "words",
  "title": "🔤 חיפוש מילים בתוך הספרים",
  "sub": "כולל ו׳ ה׳ ב׳ ל׳ · ביטוי מדויק · מיקוד לפי תיקיות",
  "say": "רוצים מילה או ביטוי? החיפוש עובר על כל העמודים של כל הספרים, מוצא גם עם אותיות השימוש, ומאפשר להתמקד בתיקייה, שלב אחרי שלב."
 },
 {
  "id": "meaning",
  "title": "💡 שואלים – ומקבלים תשובה",
  "sub": "״מה מברכים על פרי העץ?״",
  "say": "והחידוש הגדול: חיפוש לפי משמעות. כותבים שאלה במילים שלכם, והתוכנה מביאה את המשפט שעונה עליה, עם שם הספר והעמוד. גם כשהמילים עצמן לא מופיעות שם."
 },
 {
  "id": "scanned",
  "title": "📜 גם ספרים ישנים וסרוקים",
  "sub": "זיהוי טקסט בעברית · סימון המקום · תצוגת טקסט מסודרת",
  "say": "וגם ספרים ישנים וסרוקים: התוכנה מזהה את האותיות בתוך העמוד, מסמנת בדיוק איפה כתוב מה שחיפשתם, ואפשר לקרוא את העמוד כטקסט נקי ומסודר."
 },
 {
  "id": "night",
  "title": "🌙 בונים פעם אחת – בלילה",
  "sub": "מחברים לטעינה · בבוקר הכול מוכן · בלי אינטרנט",
  "say": "בפעם הראשונה משאירים את המכשיר לילה אחד בטעינה, והתוכנה לומדת את כל המאגר. מהבוקר, כל שאלה, בלי חיבור לאינטרנט."
 },
 {
  "id": "reader",
  "title": "📖 פותחים איפה שנוח לכם",
  "sub": "קורא מובנה עם סימון · או ReadEra – נפתח על העמוד, מסומן",
  "say": "הספר נפתח בקורא המובנה, עם המילים מסומנות ומהבהבות, או ב-ReadEra ובכל קורא אחר שבחרתם – ישר על העמוד, מסומן."
 },
 {
  "id": "drive",
  "title": "☁ גם הספרים שב-Google Drive",
  "sub": "מתחברים פעם אחת · בוחרים תיקיות · מחפשים בלי אינטרנט",
  "say": "והספרים שלכם ב-Google Drive? מתחברים פעם אחת, בוחרים תיקיות, והם נכנסים למאגר, לחיפוש, לחיפוש הקולי ולשאלות."
 },
 {
  "id": "boox",
  "title": "מותאם ל-BOOX – ולכל מי שצריך חיפוש",
  "sub": "E-ink · 2GB זיכרון · אנדרואיד",
  "say": "התוכנה תוכננה למכשירי בוקס עם מסך דיו אלקטרוני, וזיכרון קטן, אבל היא עובדת בכל מכשיר אנדרואיד. מהירה, יציבה, ושקטה."
 },
 {
  "id": "outro",
  "title": "חיפוש פלוס",
  "sub": "חינם · יחי אדוננו מורנו ורבנו מלך המשיח לעולם ועד",
  "say": "חיפוש פלוס. פותח על ידי שניאור וילהלם, ומופץ בחינם, לתועלת הרבים. מורידים, מתקינים, ומתחילים ללמוד. יחי אדוננו מורנו ורבנו מלך המשיח לעולם ועד."
 }
]
```

## `docs/video/video.html`

```html
<!doctype html>
<html lang="he" dir="rtl">
<head>
<meta charset="utf-8">
<title>חיפוש פלוס – סרטון</title>
<style>
@font-face { font-family: Heebo; src: url(../guide/fonts/heebo-hebrew.woff2) format("woff2"); font-weight: 100 900; unicode-range: U+0590-05FF, U+200C-2010, U+20AA, U+25CC, U+FB1D-FB4F; }
@font-face { font-family: Heebo; src: url(../guide/fonts/heebo-latin.woff2) format("woff2"); font-weight: 100 900; }
:root { --acc: #1B3B6F; --light: #E6ECF7; --gold: #F2C14E; --ink: #121417; --sub: #5a5f69; --card: #F4F5F8; --line: #D9DCE3; }
* { box-sizing: border-box; }
html, body { margin: 0; width: 1280px; height: 720px; overflow: hidden; font-family: Heebo, sans-serif; }
body { background: radial-gradient(1200px 700px at 80% 20%, #3C74C9 0%, #1B3B6F 55%, #0f2346 100%); color: #fff; }
#stage { position: absolute; inset: 0; }
.caption { position: absolute; right: 90px; top: 0; bottom: 0; width: 600px; display: flex; flex-direction: column; justify-content: center; }
.caption h1 { font-size: 64px; font-weight: 900; margin: 0 0 18px; line-height: 1.08; opacity: 0; transform: translateY(24px); transition: all .7s cubic-bezier(.2,.8,.2,1); }
.caption p { font-size: 30px; margin: 0; color: #dbe6f7; opacity: 0; transform: translateY(24px); transition: all .7s .25s cubic-bezier(.2,.8,.2,1); line-height: 1.35; }
.caption.in h1, .caption.in p { opacity: 1; transform: none; }
.dots { position: absolute; bottom: 36px; right: 90px; display: flex; gap: 8px; }
.dots i { width: 10px; height: 10px; border-radius: 5px; background: rgba(255,255,255,.3); transition: all .4s; }
.dots i.on { width: 34px; background: var(--gold); }
.brand { position: absolute; top: 30px; right: 90px; font-weight: 900; font-size: 22px; opacity: .85; }
.brand b { background: #fff; color: var(--acc); border-radius: 8px; padding: 0 8px; margin-left: 8px; }

.phone { position: absolute; left: 120px; top: 34px; width: 340px; height: 652px; border-radius: 40px; background: #111; padding: 12px; box-shadow: 0 30px 70px rgba(0,0,0,.45); transition: transform .8s cubic-bezier(.2,.8,.2,1), filter .8s; }
.screen { width: 100%; height: 100%; border-radius: 30px; background: #fff; overflow: hidden; position: relative; color: var(--ink); font-size: 13px; line-height: 1.35; }
.eink .screen { filter: grayscale(1) contrast(1.1); }
.head { display: flex; align-items: center; gap: 8px; padding: 16px 14px 8px; }
.logo { width: 30px; height: 30px; border-radius: 8px; background: var(--acc); color: #fff; font-weight: 900; display: flex; align-items: center; justify-content: center; font-size: 13px; }
.ttl { font-weight: 900; font-size: 17px; }
.st { color: var(--sub); font-size: 10.5px; }
.search { margin: 4px 12px 8px; border: 2px solid var(--acc); border-radius: 22px; background: var(--card); display: flex; align-items: center; padding: 5px 6px 5px 12px; gap: 6px; }
.search .q { flex: 1; font-size: 14.5px; font-weight: 500; min-height: 20px; white-space: nowrap; overflow: hidden; }
.search .q.hint { color: #8a8a8a; }
.search .go { background: var(--acc); color: #fff; border-radius: 16px; padding: 4px 11px; font-weight: 700; font-size: 12px; }
.caret::after { content: '|'; color: var(--acc); animation: blink 1s steps(1) infinite; }
@keyframes blink { 50% { opacity: 0; } }
.modes { display: flex; gap: 6px; padding: 0 12px 6px; }
.modes span { flex: 1; text-align: center; border-radius: 14px; padding: 5px 0; background: var(--card); border: 1px solid var(--line); font-size: 12px; transition: all .4s; }
.modes .on { background: var(--acc); color: #fff; border-color: var(--acc); font-weight: 700; }
.body { padding: 4px 12px; position: relative; }
.hero { background: var(--acc); color: #fff; border-radius: 16px; padding: 12px 14px; }
.hero b { font-size: 16px; display: block; }
.mic { margin-top: 10px; border: 2px solid var(--acc); border-radius: 18px; padding: 14px; text-align: center; }
.mic .c { width: 74px; height: 74px; border-radius: 50%; background: var(--light); border: 3px solid var(--acc); margin: 0 auto 6px; display: flex; align-items: center; justify-content: center; font-size: 34px; transition: transform .3s; }
.mic .c.press { transform: scale(.88); box-shadow: 0 0 0 14px rgba(27,59,111,.15); }
.mic b { font-size: 16px; display: block; }
.chip { display: inline-block; background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 2px 9px; margin: 2px; font-size: 11px; transition: all .4s; }
.chip.on { background: var(--acc); color: #fff; border-color: var(--acc); }
.sheet { position: absolute; left: 0; right: 0; bottom: 0; background: #fff; border-radius: 22px 22px 0 0; box-shadow: 0 -10px 30px rgba(0,0,0,.18); padding: 18px 16px 22px; transform: translateY(110%); transition: transform .55s cubic-bezier(.2,.8,.2,1); }
.sheet.in { transform: none; }
.sheet h3 { margin: 0 0 8px; font-size: 18px; }
.level { height: 14px; border-radius: 7px; background: var(--card); border: 1px solid var(--line); overflow: hidden; margin: 10px 0; }
.level i { display: block; height: 100%; width: 10%; background: var(--acc); transition: width .12s; }
.heard { color: var(--acc); font-weight: 800; font-size: 17px; min-height: 48px; }
.row { border-bottom: 1px solid var(--line); padding: 9px 4px; opacity: 0; transform: translateX(-20px); transition: all .45s; }
.row.in { opacity: 1; transform: none; }
.row .t { font-weight: 800; font-size: 14px; display: flex; justify-content: space-between; align-items: center; }
.row .t i { font-style: normal; background: var(--acc); color: #fff; border-radius: 10px; padding: 0 8px; font-size: 11.5px; }
.row .f { color: var(--sub); font-size: 11px; }
.row.sel { background: var(--light); }
.snip { background: var(--card); padding: 7px 10px; font-size: 12px; border-bottom: 1px solid var(--line); opacity: 0; transition: opacity .5s; }
.snip.in { opacity: 1; }
.snip .pg { color: var(--acc); font-weight: 700; }
mark { background: #FFE38A; font-weight: 800; padding: 0 2px; border-radius: 2px; }
.answer { background: #FFF7DD; border: 2px solid #D8B040; border-radius: 14px; padding: 10px 12px; margin: 6px 0 8px; opacity: 0; transform: scale(.95); transition: all .6s; }
.answer.in { opacity: 1; transform: none; }
.answer .h { color: var(--acc); font-weight: 800; font-size: 12px; }
.answer .a { font-size: 14.5px; margin: 6px 0; }
.answer .s { color: var(--acc); font-weight: 700; font-size: 11.5px; }
.refbar { background: var(--light); color: var(--acc); font-weight: 800; padding: 10px 12px; display: flex; justify-content: space-between; font-size: 13px; margin-top: 30px; }
.page { margin: 10px 12px; border: 1px solid var(--line); height: 470px; padding: 14px 12px; position: relative; background: #fff; overflow: hidden; }
.page .hdr { text-align: center; font-weight: 900; font-size: 13px; border-bottom: 1px solid #999; padding-bottom: 5px; margin-bottom: 8px; display: flex; justify-content: space-between; }
.ln { height: 7px; background: #c9ccd3; border-radius: 3px; margin: 9px 0; }
.ln.s { width: 70%; }
.para { font-size: 12.5px; margin: 6px 0; line-height: 1.6; }
.bar { display: flex; justify-content: space-between; color: var(--acc); font-weight: 800; font-size: 12px; padding: 0 14px; }
.folders .fo { padding: 8px 6px; border-bottom: 1px solid var(--line); font-size: 14px; display: flex; justify-content: space-between; opacity: 0; transition: opacity .4s; }
.folders .fo.in { opacity: 1; }
.folders .fo span { color: var(--sub); font-size: 12px; }
.counter { text-align: center; font-size: 13px; color: var(--acc); font-weight: 700; padding: 8px; background: var(--light); border-radius: 12px; margin: 8px 0; }
.prog { height: 18px; border-radius: 9px; background: var(--card); border: 1px solid var(--line); overflow: hidden; margin: 12px 0 6px; }
.prog i { display: block; height: 100%; width: 0; background: var(--acc); transition: width linear; }
.big { font-size: 64px; text-align: center; margin: 30px 0 6px; }
.set { padding: 10px 12px; border-radius: 12px; background: var(--card); margin: 6px 0; }
.set b { display: block; font-size: 14px; }
.set span { color: var(--sub); font-size: 12px; }
.opt { padding: 11px 6px; font-size: 14.5px; border-bottom: 1px solid var(--line); }
.opt.on { color: var(--acc); font-weight: 800; }
.tap { position: absolute; width: 46px; height: 46px; border-radius: 50%; background: rgba(242,193,78,.55); border: 3px solid #F2C14E; transform: scale(0); transition: transform .25s; pointer-events: none; z-index: 9; }
.tap.on { transform: scale(1); }
.logoBig { position: absolute; left: 50%; top: 50%; transform: translate(-50%,-50%) scale(.8); text-align: center; opacity: 0; transition: all .9s cubic-bezier(.2,.8,.2,1); }
.logoBig.in { opacity: 1; transform: translate(-50%,-50%) scale(1); }
.logoBig .l { width: 150px; height: 150px; border-radius: 36px; background: #fff; color: var(--acc); font-size: 64px; font-weight: 900; display: flex; align-items: center; justify-content: center; margin: 0 auto 20px; box-shadow: 0 20px 50px rgba(0,0,0,.35); }
.logoBig h1 { font-size: 84px; margin: 0; font-weight: 900; }
.logoBig p { font-size: 30px; color: #dbe6f7; margin: 8px 0 0; }
.logoBig .link { margin-top: 26px; font-size: 22px; background: rgba(255,255,255,.12); border: 1px solid rgba(255,255,255,.3); padding: 8px 18px; border-radius: 30px; display: inline-block; direction: ltr; }
.hidden { opacity: 0 !important; pointer-events: none; }
.fade { transition: opacity .6s; }
</style>
</head>
<body>
<div id="stage">
  <div class="brand"><b>פ+</b>חיפוש פלוס</div>
  <div class="caption" id="cap"><h1 id="t"></h1><p id="s"></p></div>
  <div class="phone fade" id="phone"><div class="screen" id="scr"></div></div>
  <div class="logoBig" id="logo"><div class="l">פ+</div><h1>חיפוש פלוס</h1><p id="logoSub"></p><div class="link hidden" id="link">github.com/sw0555041250-code/Search-Plus-</div></div>
  <div class="dots" id="dots"></div>
</div>
<script>
const $ = (s, r = document) => r.querySelector(s);
const wait = ms => new Promise(r => setTimeout(r, ms));
const scr = () => $('#scr');

function head() { return `<div class="head"><div class="logo">פ+</div><div><div class="ttl">חיפוש פלוס</div><div class="st">חיפוש מעמיק בתוך הספרים שלך</div></div><div style="margin-right:auto;color:var(--acc);font-weight:700">📚 ⚙</div></div>`; }
function search(q, hint) { return `<div class="search"><span class="q ${hint ? 'hint' : ''}" id="q">${q}</span><span>🎤</span><span class="go">חיפוש</span></div>`; }
function modes(sem) { return `<div class="modes"><span class="${sem ? '' : 'on'}" id="m1">🔤 חיפוש מילים</span><span class="${sem ? 'on' : ''}" id="m2">💡 לפי משמעות</span></div>`; }
function lines(n, seed = 1) { let h = ''; for (let i = 0; i < n; i++) h += `<div class="ln ${((i * 7 + seed) % 5 === 0) ? 's' : ''}" style="width:${70 + ((i * 37 + seed * 13) % 30)}%"></div>`; return h; }
function home() {
  return head() + search('מה לחפש? או לחצו 🎤 ודברו', true) + modes(false) +
  `<div class="body"><div class="hero"><b>ערב טוב! מה נלמד היום?</b><span style="opacity:.85;font-size:12px">אלפי ספרים · כל עמוד ניתן לחיפוש</span></div>
   <div class="mic"><div class="c" id="micc">🎤</div><b>לחצו ודברו</b><span style="color:var(--sub);font-size:12px">אומרים מה מחפשים – והספר נפתח במקום</span>
   <div style="margin-top:8px"><span class="chip">“ברכות דף כ״ז ע״א”</span><span class="chip">“לקוטי שיחות חלק ד עמוד 58”</span><span class="chip">“מה מברכים על פרי העץ”</span></div></div></div>`;
}
async function type(el, text, total) {
  el.classList.remove('hint'); el.textContent = ''; el.classList.add('caret');
  const step = Math.max(25, total / text.length);
  for (const ch of text) { el.textContent += ch; await wait(step); }
  el.classList.remove('caret');
}
function tapAt(el) {
  const r = el.getBoundingClientRect(), s = $('#phone').getBoundingClientRect();
  const t = document.createElement('div'); t.className = 'tap';
  t.style.left = (r.left - s.left + r.width / 2 - 23) + 'px'; t.style.top = (r.top - s.top + r.height / 2 - 23) + 'px';
  $('#phone').appendChild(t); requestAnimationFrame(() => t.classList.add('on'));
  setTimeout(() => t.remove(), 600);
}
function reader(ref, hdrR, hdrL, body) {
  return `<div class="refbar"><span>⤶ ${ref}</span><span>✓ זה המקום ✕</span></div><div class="page"><div class="hdr"><span>${hdrR}</span><span>${hdrL}</span></div>${body}</div><div class="bar"><span>הקודם ▶</span><span>עמוד ${hdrL}</span><span>◀ הבא</span></div>`;
}

const S = {
  async intro(d) { $('#phone').classList.add('hidden'); $('#logo').classList.add('in'); $('#logoSub').textContent = 'אומרים מה מחפשים – והספר נפתח במקום'; $('#cap').style.opacity = 0; await wait(d - 700); $('#logo').classList.remove('in'); $('#cap').style.opacity = 1; },
  async library(d) {
    $('#phone').classList.remove('hidden');
    const f = [['📁 גמרא', '412 ספרים'], ['📁 חסידות חב״ד', '1,930 ספרים'], ['   › רבותינו נשיאינו', '1,214'], ['      › לקוטי שיחות', '39 כרכים'], ['📁 הלכה', '688 ספרים'], ['📁 מפרשים', '540 ספרים'], ['📁 תנ״ך', '210 ספרים'], ['📁 ספרים שונים', '…']];
    scr().innerHTML = head() + `<div class="body"><div class="counter" id="cnt">⟳ סורק ספרים… 0</div><div class="folders">${f.map(x => `<div class="fo"><b>${x[0].replace(/ /g, '&nbsp;')}</b><span>${x[1]}</span></div>`).join('')}</div></div>`;
    const fos = [...document.querySelectorAll('.fo')];
    const n = 4812, t0 = performance.now();
    const iv = setInterval(() => { const k = Math.min(1, (performance.now() - t0) / (d * .7)); $('#cnt').textContent = (k < 1 ? '⟳ סורק ספרים… ' : '✓ נסרקו ') + Math.round(n * k).toLocaleString('en') + (k < 1 ? '' : ' ספרים'); if (k >= 1) clearInterval(iv); }, 60);
    for (const x of fos) { x.classList.add('in'); await wait(d * .7 / fos.length); }
    await wait(d * .3);
  },
  async voice(d) {
    scr().innerHTML = home() + `<div class="sheet" id="sh"><h3>🎤 מקשיב… דברו עכשיו</h3><div style="color:var(--sub);font-size:12px">עובד בתוך המכשיר – בלי אינטרנט</div><div class="level"><i id="lv"></i></div><div class="heard" id="hd"></div></div>`;
    await wait(500); tapAt($('#micc')); $('#micc').classList.add('press'); await wait(350); $('#micc').classList.remove('press');
    $('#sh').classList.add('in');
    const words = 'ליקוטי שיחות חלק ד׳, שיחה לפרשת נח'.split(' ');
    const iv = setInterval(() => $('#lv').style.width = (15 + Math.random() * 80) + '%', 120);
    for (const w of words) { await wait((d * .55) / words.length); $('#hd').textContent += (($('#hd').textContent ? ' ' : '') + w); }
    clearInterval(iv); $('#lv').style.width = '0%';
    $('.sheet h3').textContent = '⏳ מזהה…';
    await wait(Math.max(300, d * .45 - 1200));
  },
  async open(d) {
    scr().innerHTML = reader('לקוטי שיחות חלק ד – נח', 'לקוטי שיחות · נח', '58',
      `<div class="para">א. עה״פ <mark>אלה תולדות נח</mark> <mark>נח</mark> איש צדיק תמים היה בדורותיו –</div>${lines(4, 2)}<div class="para">ופירש״י: ״הואיל והזכירו סיפר בשבחו״.</div>${lines(9, 5)}<div class="para" style="font-weight:800">ב.</div>${lines(8, 9)}`);
    scr().style.opacity = 0; await wait(50); scr().style.transition = 'opacity .5s'; scr().style.opacity = 1;
    await wait(d - 100);
  },
  async gemara(d) {
    scr().innerHTML = home() + `<div class="sheet" id="sh"><h3>איזה ספר לפתוח?</h3><div style="color:var(--sub);font-size:12px;margin-bottom:6px">ברכות דף כז ע״א</div>
      <div class="row"><div class="t">📖 ברכות – ש״ס וילנא</div><div class="f">גמרא › וילנא · עמוד 57</div></div>
      <div class="row"><div class="t">📖 ברכות – עוז והדר</div><div class="f">גמרא › עוז והדר · עמוד 61</div></div>
      <div class="row"><div class="t">📖 ברכות ב – שוטנשטיין</div><div class="f">גמרא › שוטנשטיין · עמוד 212</div></div>
      <div class="row"><div class="t">📖 ברכות – מתיבתא</div><div class="f">גמרא › מתיבתא · עמוד 140</div></div></div>`;
    await type($('#q'), 'ברכות דף כ״ז עמוד א', d * .25);
    $('#sh').classList.add('in'); await wait(400);
    for (const r of document.querySelectorAll('.row')) { r.classList.add('in'); await wait(d * .08); }
    await wait(d * .15); const first = document.querySelector('.row'); tapAt(first); first.classList.add('sel'); await wait(500);
    scr().innerHTML = reader('ברכות דף כז ע״א', 'ברכות · פרק רביעי · תפלת השחר', 'כז.', `<div style="display:flex;gap:10px"><div style="flex:1">${lines(18, 3)}</div><div style="flex:1.4;border-right:1px solid #ccc;padding-right:8px">${lines(18, 7)}</div><div style="flex:1">${lines(18, 11)}</div></div>`);
    await wait(Math.max(400, d * .2 - 900));
  },
  async page(d) {
    scr().innerHTML = home();
    await type($('#q'), 'לקוטי שיחות חלק ד עמוד 58', d * .3);
    await wait(d * .1);
    scr().innerHTML = reader('לקוטי שיחות חלק ד עמוד 58', 'לקוטי שיחות', '58', lines(30, 4));
    await wait(d * .6 - 100);
  },
  async quick(d) {
    scr().innerHTML = head() + `<div class="body"><div class="hero" style="background:linear-gradient(135deg,#E0712C,#FFA65C)"><b>⚡ לאן לקפוץ?</b><span style="font-size:12px;opacity:.9">כותבים ספר ומקום – והוא נפתח</span></div></div>` + search('', false) + `<div class="body"><div class="und" id="und" style="color:var(--acc);font-weight:800;font-size:13.5px;min-height:20px"></div><div id="res"></div></div>`;
    await type($('#q'), 'לקו״ש חלק כ״ד עמוד 358', d * .22);
    $('#und').textContent = '✓ מצאתי! לקוטי שיחות חלק כד עמוד 358 – לחצו לפתיחה';
    $('#res').innerHTML = `<div class="row in" style="background:var(--light);border:1px solid var(--acc);border-radius:12px;padding:9px"><div class="t">📖 לקוטי שיחות חלק כד</div><div class="f">חסידות › רבותינו נשיאינו · עמוד 366</div></div>`;
    await wait(d * .22);
    $('#q').textContent = ''; $('#und').textContent = ''; $('#res').innerHTML = '';
    await type($('#q'), 'שו״ע אדה״ז סימן רע״ח', d * .2);
    $('#und').textContent = '✓ מצאתי לך 3 אפשרויות לשולחן ערוך הרב סימן רעח – באיזו לפתוח?';
    $('#res').innerHTML = ['① שולחן ערוך הרב – חלק ב', '② שולחן ערוך הרב – מהדורה חדשה', '③ שו״ע אדמו״ר הזקן – ביאורים'].map(t => `<div class="row" style="border:1px solid var(--acc);border-radius:12px;margin:5px 0;padding:9px;background:var(--light)"><div class="t">${t}</div><div class="f">הלכה › שולחן ערוך הרב · סימן רעח</div></div>`).join('');
    for (const r of document.querySelectorAll('#res .row')) { r.classList.add('in'); await wait(d * .06); }
    await wait(d * .15);
  },
  async scanned(d) {
    scr().innerHTML = reader('חיפוש: ״ויצא יעקב״', 'ספר ישן · סרוק', '212', `<div style="filter:sepia(.5) contrast(.9);background:#f3ead7;padding:8px;border-radius:4px">${lines(10, 3)}<div class="para"><span id="hl" style="background:#FFE38A;outline:3px solid #E65100;border-radius:4px;padding:0 3px;font-weight:800">ויצא יעקב מבאר שבע</span> וילך חרנה…</div>${lines(12, 8)}</div>`);
    for (let k = 0; k < 3; k++) { await wait(d * .1); $('#hl').style.outlineWidth = '7px'; await wait(d * .08); $('#hl').style.outlineWidth = '3px'; }
    await wait(d * .1);
    scr().innerHTML = `<div class="head"><div class="ttl">📄 תצוגת טקסט – שורות מסודרות</div></div><div class="body" style="font-size:15px;line-height:1.8"><mark>ויצא יעקב מבאר שבע וילך חרנה.</mark> ויפגע במקום וילן שם כי בא השמש, ויקח מאבני המקום וישם מראשותיו, וישכב במקום ההוא.</div>`;
    await wait(d * .35);
  },
  async drive(d) {
    scr().innerHTML = `<div class="head"><div class="ttl">☁ ספרים מ-Google Drive</div></div><div class="body"><div class="hero" style="background:linear-gradient(135deg,#0E9F8E,#35CDB4)"><b>🔗 חיבור לחשבון Google Drive</b><span style="font-size:12px">קריאה בלבד · פעם אחת</span></div><div class="counter" id="dc" style="margin-top:12px">מחפש ספרים ב-Drive…</div><div id="fl"></div></div>`;
    await wait(d * .25);
    $('#dc').textContent = 'נמצאו 1,284 ספרים (6.2GB) – בחרו תיקיות';
    $('#fl').innerHTML = ['☑ 📁 חסידות · 512 ספרים', '☑ 📁 גמרא · 140 ספרים', '☑ 📁 הלכה · 233 ספרים', '☐ 📁 שונות · 399 ספרים'].map(t => `<div class="fo in">${t}</div>`).join('');
    await wait(d * .3);
    $('#dc').textContent = '⬇ מביא… 885 / 885 ✓ נכנסו למאגר';
    await wait(d * .4);
  },
  async words(d) {
    scr().innerHTML = head() + search('', false) + modes(false) + `<div style="padding:4px 12px"><span class="chip on">הכל</span> › <span class="chip" id="cat">📁 חסידות חב״ד (184)</span><span class="chip">📁 גמרא (61)</span><span class="chip">📁 הלכה (22)</span></div><div style="color:var(--sub);font-size:12px;padding:2px 14px" id="sum"></div><div class="body" id="res"></div>`;
    await type($('#q'), 'רבי שמעון בר יוחאי', d * .2);
    $('#sum').textContent = 'נמצאו 1,203 עמודים ב-267 ספרים';
    const res = [['זוהר – בראשית', 'חסידות › קבלה', 212], ['לקוטי שיחות חלק כב', 'חסידות › רבותינו נשיאינו', 37], ['ספר המאמרים תשמ״ח', 'חסידות › רבותינו נשיאינו', 29], ['שבת לג:', 'גמרא › ש״ס וילנא', 14], ['תורת מנחם – התוועדויות', 'חסידות › רבותינו נשיאינו', 12]];
    $('#res').innerHTML = res.map((r, i) => `<div class="row"><div class="t">${i == 1 ? '▾ ' : '◂ '}${r[0]} <i>${r[2]}</i></div><div class="f">${r[1]}</div></div>` + (i == 1 ? `<div class="snip"><span class="pg">עמוד 112</span><br>…ובפרט ביום ההילולא של <mark>רשב״י</mark>, <mark>ורבי שמעון</mark> אמר…</div>` : '')).join('');
    for (const r of document.querySelectorAll('#res .row')) { r.classList.add('in'); await wait(d * .07); }
    document.querySelector('.snip').classList.add('in');
    await wait(d * .15); tapAt($('#cat')); $('#cat').classList.add('on'); $('#sum').textContent = 'חסידות חב״ד › 184 עמודים';
    await wait(d * .25);
  },
  async meaning(d) {
    scr().innerHTML = head() + search('', false) + modes(false) + `<div class="body"><div class="answer" id="ans"><div class="h">💡 התשובה מתוך הספרים שלך</div><div class="a">‟על פירות האילן הוא אומר בורא פרי העץ, חוץ מן היין, שעל היין הוא אומר בורא פרי הגפן”</div><div class="s">📖 משנה ברכות פרק ו · לפתיחה ◂</div></div><div id="res"></div></div>`;
    await wait(300); tapAt($('#m2')); $('#m1').classList.remove('on'); $('#m2').classList.add('on'); await wait(300);
    await type($('#q'), 'מה מברכים על פרי העץ?', d * .22);
    await wait(d * .1); $('#ans').classList.add('in');
    const res = [['ברכות – ש״ס וילנא', 'גמרא', '91%'], ['שולחן ערוך הרב – סדר ברכות הנהנין', 'הלכה', '88%'], ['קיצור שולחן ערוך', 'הלכה', '84%']];
    $('#res').innerHTML = res.map(r => `<div class="row"><div class="t">◂ ${r[0]} <i>${r[2]}</i></div><div class="f">${r[1]}</div></div>`).join('');
    await wait(d * .1);
    for (const r of document.querySelectorAll('#res .row')) { r.classList.add('in'); await wait(d * .06); }
    await wait(d * .25);
  },
  async night(d) {
    scr().innerHTML = head() + `<div class="body"><div class="big">🌙</div><div style="text-align:center;font-weight:900;font-size:17px">בונה את אינדקס המשמעות</div><div class="prog"><i id="pg"></i></div><div class="counter" id="pc">0 / 4,812 ספרים · עוד כ-7 שעות</div><div style="text-align:center;color:var(--sub);font-size:12px">🔌 בטעינה · אפשר להמשיך לחפש בזמן הבנייה</div><div class="set" style="margin-top:20px;opacity:0;transition:opacity .6s" id="ok"><b>💡 אינדקס המשמעות: 4,812 מתוך 4,812 ✓</b><span>מוכן לשאלות – בלי אינטרנט</span></div></div>`;
    await wait(200); $('#pg').style.transitionDuration = (d * .6) + 'ms'; $('#pg').style.width = '100%';
    const t0 = performance.now();
    const iv = setInterval(() => { const k = Math.min(1, (performance.now() - t0) / (d * .6)); const h = Math.round(7 * (1 - k)); $('#pc').textContent = Math.round(4812 * k).toLocaleString('en') + ' / 4,812 ספרים' + (k < 1 ? ` · עוד כ-${h || 1} שעות` : ' ✓'); if (k >= 1) clearInterval(iv); }, 60);
    await wait(d * .62); $('#ok').style.opacity = 1; $('.big').textContent = '☀️';
    await wait(d * .36);
  },
  async reader(d) {
    scr().innerHTML = `<div class="head"><span style="color:var(--acc);font-size:20px">→</span><div class="ttl">הגדרות</div></div><div class="body">
      <div class="set"><b>באיזו אפליקציה לפתוח ספרים</b><span>הקורא המובנה – בעמוד הנכון עם סימון המילים</span></div>
      <div class="set"><b>🎤 מנוע זיהוי הדיבור</b><span>בתוך המכשיר – בלי אינטרנט</span></div>
      <div class="set"><b>💡 חיפוש לפי משמעות</b><span>מוכן ✓</span></div></div>
      <div class="sheet" id="sh"><h3>ברירת מחדל לפתיחת ספרים</h3><div class="opt on" id="o1">● קורא מובנה – עמוד מדויק + סימון (מומלץ)</div><div class="opt" id="o2">○ ReadEra</div><div class="opt">○ NeoReader</div><div class="opt">○ Moon+ Reader</div></div>`;
    await wait(d * .2); tapAt(document.querySelector('.set')); $('#sh').classList.add('in');
    await wait(d * .35); tapAt($('#o2')); $('#o1').classList.remove('on'); $('#o1').textContent = '○ קורא מובנה – עמוד מדויק + סימון (מומלץ)'; $('#o2').classList.add('on'); $('#o2').textContent = '● ReadEra';
    await wait(d * .45 - 100);
  },
  async boox(d) {
    scr().innerHTML = reader('לקוטי שיחות חלק ד – נח', 'לקוטי שיחות · נח', '58', `<div class="para"><mark>אלה תולדות נח</mark> נח איש צדיק…</div>${lines(26, 6)}`);
    $('#phone').classList.add('eink'); $('#phone').style.transform = 'rotate(-4deg) scale(.97)';
    await wait(d - 100);
    $('#phone').classList.remove('eink'); $('#phone').style.transform = '';
  },
  async outro(d) {
    $('#phone').classList.add('hidden'); $('#cap').style.opacity = 0;
    $('#logoSub').textContent = 'פותח על ידי שניאור וילהלם · חינם, לתועלת הרבים';
    $('#link').classList.remove('hidden'); $('#logo').classList.add('in');
    await wait(d);
  },
};

window.run = async function (scenes) {
  await document.fonts.ready;
  $('#dots').innerHTML = scenes.map(() => '<i></i>').join('');
  const dots = [...document.querySelectorAll('#dots i')];
  for (let i = 0; i < scenes.length; i++) {
    const sc = scenes[i];
    dots.forEach((x, k) => x.classList.toggle('on', k === i));
    $('#cap').classList.remove('in'); await wait(60);
    $('#t').textContent = sc.title; $('#s').textContent = sc.sub;
    $('#s').style.direction = /github/.test(sc.sub) ? 'rtl' : '';
    $('#cap').classList.add('in');
    await S[sc.id](sc.ms - 60);
  }
  window.done = true;
};
</script>
</body>
</html>
```

## `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

## `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SearchPlus"
include(":app")
include(":core")
```

## `tools/make_reference.py`

```python
"""Downloads the models used by the app and makes reference values with the official Python
libraries, so the Kotlin code in core/ can be checked against them (see ModelTest.kt).

Usage: python tools/make_reference.py <models_dir>
"""
import asyncio
import os
import subprocess
import sys

import numpy as np
from huggingface_hub import hf_hub_download

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

# ---------- models (the same files the app downloads) ----------
WHISPER = {
    "whisper-base": "Xenova/whisper-base",
    "whisper-small": "Xenova/whisper-small",
}
for name, repo in WHISPER.items():
  try:
    for f in ["onnx/encoder_model_quantized.onnx", "onnx/decoder_model_merged_quantized.onnx", "tokenizer.json"]:
        p = hf_hub_download(repo, f, local_dir=os.path.join(out, "_hf", name))
        dst = os.path.join(out, name, os.path.basename(f))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if not os.path.exists(dst):
            os.symlink(p, dst)
        print(name, os.path.basename(f), os.path.getsize(p) // 1024 // 1024, "MB")
  except Exception as e:
    print("skipped", name, e)

for f in ["onnx/model_quantized.onnx", "tokenizer.json"]:
    p = hf_hub_download("Xenova/multilingual-e5-small", f, local_dir=os.path.join(out, "_hf", "e5"))
    dst = os.path.join(out, "e5", os.path.basename(f))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if not os.path.exists(dst):
        os.symlink(p, dst)
    print("e5", os.path.basename(f), os.path.getsize(p) // 1024 // 1024, "MB")

# ---------- whisper tokenizer reference ----------
from tokenizers import Tokenizer

sys.path.insert(0, os.path.dirname(__file__))
PROMPT = open(os.path.join(os.path.dirname(__file__), "..", "core", "src", "main", "kotlin", "com", "chdv",
                           "searchplus", "core", "Whisper.kt"), encoding="utf-8").read().split('const val PROMPT = "')[1].split('"')[0]
wt = Tokenizer.from_file(os.path.join(out, "whisper-base", "tokenizer.json"))
ids = wt.encode(" " + PROMPT, add_special_tokens=False).ids
open(os.path.join(out, "whisper_prompt_ids.txt"), "w").write(",".join(map(str, ids)))
print("prompt tokens:", len(ids))

# ---------- spoken test sentences (Microsoft Hebrew voice) ----------
CASES = [
    "מסכת ברכות דף כ״ז עמוד א׳",
    "ליקוטי שיחות חלק ג׳ פרשת ויצא",
    "גמרא בבא מציעא דף נ״ט",
    "תניא פרק ה׳",
    "מה מברכים על פרי העץ",
]
os.makedirs(os.path.join(out, "audio"), exist_ok=True)
open(os.path.join(out, "audio", "cases.txt"), "w", encoding="utf-8").write("\n".join(CASES))


async def tts():
    import edge_tts
    for i, t in enumerate(CASES):
        mp3 = os.path.join(out, "audio", f"{i}.mp3")
        await edge_tts.Communicate(t, "he-IL-AvriNeural").save(mp3)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", mp3, "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
                        os.path.join(out, "audio", f"{i}.wav")], check=True)


asyncio.run(tts())

# ---------- log-mel reference (numpy, same recipe as openai/whisper) ----------
import wave


def read_wav(p):
    with wave.open(p) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0


def mel_filters(n_mels=80, sr=16000, n_fft=400):
    def hz_to_mel(f):
        f_sp = 200.0 / 3
        mels = f / f_sp
        min_log_hz = 1000.0
        min_log_mel = min_log_hz / f_sp
        logstep = np.log(6.4) / 27.0
        return np.where(f >= min_log_hz, min_log_mel + np.log(np.maximum(f, 1e-10) / min_log_hz) / logstep, mels)

    def mel_to_hz(m):
        f_sp = 200.0 / 3
        freqs = f_sp * m
        min_log_hz = 1000.0
        min_log_mel = min_log_hz / f_sp
        logstep = np.log(6.4) / 27.0
        return np.where(m >= min_log_mel, min_log_hz * np.exp(logstep * (m - min_log_mel)), freqs)

    fftfreqs = np.linspace(0, sr / 2, 1 + n_fft // 2)
    mel_f = mel_to_hz(np.linspace(hz_to_mel(0.0), hz_to_mel(sr / 2), n_mels + 2))
    fdiff = np.diff(mel_f)
    ramps = mel_f[:, None] - fftfreqs[None, :]
    w = np.zeros((n_mels, len(fftfreqs)))
    for i in range(n_mels):
        lower = -ramps[i] / fdiff[i]
        upper = ramps[i + 2] / fdiff[i + 1]
        w[i] = np.maximum(0, np.minimum(lower, upper))
    enorm = 2.0 / (mel_f[2:n_mels + 2] - mel_f[:n_mels])
    return w * enorm[:, None]


def log_mel(audio):
    n = 480000
    audio = np.pad(audio[:n], (0, max(0, n - len(audio))))
    x = np.pad(audio, 200, mode="reflect")
    win = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(400) / 400)
    frames = np.stack([x[t * 160: t * 160 + 400] * win for t in range(3000)])
    spec = np.abs(np.fft.rfft(frames, axis=1)) ** 2
    mel = mel_filters() @ spec.T
    lg = np.log10(np.maximum(mel, 1e-10))
    lg = np.maximum(lg, lg.max() - 8.0)
    return ((lg + 4.0) / 4.0).astype(np.float32)


log_mel(read_wav(os.path.join(out, "audio", "0.wav"))).astype("<f4").tofile(os.path.join(out, "mel_ref.f32"))

# ---------- e5 reference: tokens (HF tokenizers) and vectors (onnxruntime) ----------
import onnxruntime as ort

et = Tokenizer.from_file(os.path.join(out, "e5", "tokenizer.json"))
sess = ort.InferenceSession(os.path.join(out, "e5", "model_quantized.onnx"))
TEXTS = [
    "query: מה מברכים על פרי העץ",
    "passage: אמר רבי שמעון בן יוחאי, כל המתפלל צריך שיכוין את לבו לשמים",
    "passage: וַיֵּצֵא יַעֲקֹב מִבְּאֵר שָׁבַע וַיֵּלֶךְ חָרָנָה",
    "query: what is the blessing on fruit",
    "passage: ליקוטי שיחות חלק ג׳, שיחה לפרשת ויצא – ״ויצא יעקב״ (כ״ח, י)",
]
with open(os.path.join(out, "e5_ref.txt"), "w", encoding="utf-8") as f:
    for t in TEXTS:
        enc = et.encode(t)
        ids = np.array([enc.ids], dtype=np.int64)
        feed = {"input_ids": ids, "attention_mask": np.ones_like(ids)}
        if "token_type_ids" in [i.name for i in sess.get_inputs()]:
            feed["token_type_ids"] = np.zeros_like(ids)
        h = sess.run(None, feed)[0][0]
        v = h.mean(axis=0)
        v = v / np.linalg.norm(v)
        f.write(t + "\t" + ",".join(map(str, enc.ids)) + "\t" + ",".join(f"{x:.6f}" for x in v) + "\n")
# ---------- bge-m3 (the deep understanding model): tokens, CLS vectors ----------
try:
    for f in ["onnx/model_quantized.onnx", "tokenizer.json"]:
        p = hf_hub_download("Xenova/bge-m3", f, local_dir=os.path.join(out, "_hf", "bge"))
        dst = os.path.join(out, "bge", os.path.basename(f))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if not os.path.exists(dst):
            os.symlink(p, dst)
        print("bge", os.path.basename(f), os.path.getsize(p) // 1024 // 1024, "MB")
    bt = Tokenizer.from_file(os.path.join(out, "bge", "tokenizer.json"))
    bs = ort.InferenceSession(os.path.join(out, "bge", "model_quantized.onnx"))
    print("bge inputs", [i.name for i in bs.get_inputs()], "outputs", [o.name for o in bs.get_outputs()])
    with open(os.path.join(out, "bge_ref.txt"), "w", encoding="utf-8") as f:
        for t in ["מה מברכים על פרי העץ", "על פירות האילן הוא אומר בורא פרי העץ", "מי שנתחייב מלקות לוקה ארבעים חסר אחת"]:
            enc = bt.encode(t)
            ids = np.array([enc.ids], dtype=np.int64)
            feed = {"input_ids": ids, "attention_mask": np.ones_like(ids)}
            h = bs.run(None, feed)[0][0]
            v = h[0] / np.linalg.norm(h[0])
            f.write(t + "\t" + ",".join(map(str, enc.ids)) + "\t" + ",".join(f"{x:.6f}" for x in v) + "\n")
except Exception as e:
    print("bge-m3 skipped:", e)
print("reference ready")
```
