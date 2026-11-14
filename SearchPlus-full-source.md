# חיפוש פלוס (Search Plus): קוד המקור המלא

מסמך זה מכיל את כל קבצי הקוד של האפליקציה. כדי להמשיך לפתח: קחו את המאגר github.com/chdv770-ctrl/Search-Plus (או פרסו כל קובץ לנתיב שבכותרת שלו), קראו את HANDOFF.md, ובקשו מ-Claude Code להמשיך. הקבצים הבינאריים (סמלי PNG ומפתח החתימה app/searchplus.p12) נמצאים במאגר בלבד.

---

## `HANDOFF.md`

````markdown
# חיפוש פלוס (Search Plus): מסמך המשך עבודה למפתח או ל-Claude Code

אפליקציית אנדרואיד לחיפוש מעמיק בתוך ספרי PDF שבמכשיר (כולל כרטיס זיכרון): חיפוש מילים, חיפוש לפי משמעות (AI על המכשיר), קטגוריות לפי תיקיות, קורא מובנה שפותח בעמוד ומסמן את המילים.
מותאמת ל-**BOOX Go 6** (E-ink, ‏Android 11, ‏2GB RAM). החבילה: `com.chdv.searchplus`. המאגר: `github.com/chdv770-ctrl/Search-Plus` (פומבי). פותח: שניאור וילהלם.

**כדי להמשיך לעבוד עם Claude Code:** פותחים סשן עם המאגר הזה, ואומרים "קרא את HANDOFF.md והמשך מכאן: <מה רוצים>". כל קוד המקור נמצא גם בקובץ `SearchPlus-full-source.md`.

## בנייה ופרסום
- אין צורך ב-Android Studio. כל push ל-`main` מפעיל את `.github/workflows/build-apk.yml`: מוריד את מודל ה-OCR העברי (`heb.traineddata` מ-tessdata_fast) לתוך `app/src/main/assets/tessdata`, בונה APK חתום (release), ומפרסם Release ‏`v1.0.<run_number>` עם `SearchPlus.apk` (סימון latest).
- **בנייה שנכשלה:** נוצר pre-release בשם `build-log` עם `build-log.txt` (שגיאות קומפילציה).
- **חתימה:** `app/searchplus.p12`, ‏PKCS12, סיסמה `searchplus123`, ‏alias `searchplus`. אסור להחליף.
- **גרסה:** `versionName = 1.0.<GITHUB_RUN_NUMBER>`, ‏`versionCode = <run>`.
- ABI: רק `arm64-v8a` (כדי שה-APK יהיה קטן). למכשיר 32 ביט – להוסיף `armeabi-v7a` ב-`app/build.gradle.kts`.
- ספריות: androidx core/appcompat/recyclerview, ‏`com.tom-roush:pdfbox-android` (חילוץ טקסט ומיקום אותיות), ‏`com.microsoft.onnxruntime:onnxruntime-android` (מודל המשמעות), ‏`cz.adaptech.tesseract4android` (OCR, מ-JitPack).

## מבנה הקוד (Kotlin, ממשק בקוד בלי XML layouts)
| קובץ | תפקיד |
|---|---|
| `Ui.kt` | `App` (Application), ‏`Prefs`, צבעים `Col`, עזרים (`label`, `pill`, `chip`, `vbox`/`hbox`, `FlowLayout`), ‏`BaseActivity` (כותרת + חזרה, RTL), ‏`Sheet` (חלונות: info/confirm/choice/actions/input) |
| `Text.kt` | `Heb`: תיקון טקסט עברי – `repairPage` בוחר בין רגיל / Windows‑1255 / DOS‑862 ובין סדר רגיל להפוך (ניקוד לפי מילים נפוצות ומיקום אותיות סופיות); `clean` – הסרת ניקוד וטעמים, המרת פיסוק עברי ל-ASCII; `PREFIXES` (אותיות שימוש) |
| `Db.kt` | SQLite: ‏`files` (ספר), ‏`pages` (עמוד: kind 0=טקסט, 1=OCR, 2=בלי טקסט – ממתין ל-OCR, 3=OCR לא מצא כלום), ‏`fts` (FTS4, ‏docid = pages.id) |
| `Library.kt` | תיקיות הספרים (`roots`), קטגוריה = נתיב התיקייה ביחס לתיקיית הספרים (`categoryOf`), הרשאת "כל הקבצים", סריקת קבצים, פתיחה חיצונית, מועדפים/היסטוריה/אחרונים |
| `Indexer.kt` | `Progress` (מצב חי), ‏`Indexer` (סריקה → חילוץ טקסט במעבר אחד על כל ספר → OCR → משמעות), ‏`Ocr` (Tesseract עברי על עמודים בלי טקסט, רינדור ב-PdfRenderer), ‏`Net` (הורדות), ‏`IndexService` (שירות חזית עם התראה ו-WakeLock) |
| `Search.kt` | בניית שאילתת FTS4 (ביטוי מדויק / כל המילים / NEAR / OR, עם אותיות שימוש וכוכבית), קיבוץ לפי ספר, התאמת שם ספר, קטעי טקסט (snippet) |
| `Semantic.kt` | multilingual-e5-small (ONNX מ-HuggingFace, ‏Xenova), טוקנייזר SentencePiece Unigram (Viterbi) שנבנה מ-tokenizer.json, הטמעה (mean pooling + נרמול), קטעים של ~90 מילים, וקטורים int8 בקובץ `vectors.bin` (רשומה: pageId,scale,384 בתים), חיפוש brute-force עם top‑K |
| `MainActivity.kt` | מסך החיפוש: מצב מילים/משמעות, אפשרויות, פירורי לחם וצ'יפים של קטגוריות (drill‑down), מיון, רשימת ספרים מתרחבת לעמודים עם קטעים מודגשים, מסך בית (היסטוריה, מועדפים, אחרונים, הסבר) |
| `ReaderActivity.kt` | קורא PDF: ‏PdfRenderer לתמונה, ‏PDFBox למיקומי מילים → מלבני הדגשה, ‏`PageView` (זום, גרירה, הקשה בצדדים/החלקה לדפדוף), תוצאה הבאה/קודמת, חיפוש בספר, מועדפים, העתקת טקסט |
| `BrowseActivity.kt` | עיון בתיקיות וספרים + `FolderPicker` (זיכרון פנימי וכרטיס זיכרון) |
| `SettingsActivity.kt` | כל ההגדרות: תיקיות, סריקה, OCR, משמעות, פתיחה, גודל כתב, עדכונים, אודות |
| `Updater.kt` | עדכון עצמי מ-`releases/latest` של המאגר |

## מפתחות Prefs
`roots`, `semanticRoots`, `match` (PHRASE/ALL/NEAR/ANY), `prefixes`, `sort`, `lastSemantic`, `semantic`, `ocrAuto`, `autoScan`, `sortByPosition`, `openWith` (internal/external), `fontSize`, `autoUpdate`, `lastUpdateCheck`, `favs` (`path|page`), `history`, `recent` (`path|page`).

## רעיונות להמשך
- הדגשה גם בעמודי OCR (מיקומי מילים מ-Tesseract ResultIterator).
- תמיכה ב-EPUB/TXT/DOCX.
- חיפוש היברידי (שילוב ציון מילים וציון משמעות).
- ייצוא/גיבוי של האינדקס לכרטיס הזיכרון.
````

## `README.md`

````markdown
<p align="center"><img src="docs/icon.png" width="120" alt="חיפוש פלוס"></p>

<h1 align="center">חיפוש פלוס</h1>
<p align="center"><b>חיפוש מעמיק בתוך ספרי ה-PDF שבמכשיר – במילים ולפי משמעות</b><br>
פותח על ידי שניאור וילהלם · מופץ בחינם לתועלת הרבים</p>

---

## ⬇️ הורדה

**[להורדת הקובץ SearchPlus.apk מהגרסה האחרונה](https://github.com/chdv770-ctrl/Search-Plus/releases/latest/download/SearchPlus.apk)**

(כל הגרסאות: [עמוד ה-Releases](https://github.com/chdv770-ctrl/Search-Plus/releases))

## מה התוכנה עושה

חיפוש פלוס סורק את כל קובצי ה-PDF בתיקיית הספרים שלך – גם בכרטיס הזיכרון – וקורא כל מילה בכל עמוד. אחר כך אפשר לחפש **בתוך הספרים עצמם**, לא רק בשמות הקבצים.

- **חיפוש מילים** – ביטוי מדויק ("רבי שמעון"), כל המילים בעמוד, מילים קרובות, או אחת המילים. כולל אפשרות למצוא גם עם אותיות שימוש (ורבי, לרבי, שרבי…) וכוכבית להמשך מילה (`ויצ*`).
- **חיפוש לפי משמעות** – כותבים שאלה או רעיון במילים שלך ("מה הטעם שמדליקים נרות חנוכה בלילה") והתוכנה מוצאת את הקטעים שעוסקים בזה, גם כשהמילים שונות. מודל בינה מלאכותית רב-לשוני (multilingual-e5) רץ בתוך המכשיר – בלי אינטרנט ובלי לשלוח שום דבר החוצה. אפשר לכבות אותו לגמרי.
- **קטגוריות לפי תיקיות** – התוצאות מתחלקות לפי התיקיות הראשיות (הלכה, חסידות חב"ד, נגלה…). לוחצים על קטגוריה, ושוב על תת-תיקייה (רבותינו נשיאינו › הרבי…) – וכך ממקדים את החיפוש שלב אחרי שלב.
- **מיון** – לפי רלוונטיות, שם, תיקייה, גודל, תאריך או מספר עמודים.
- **פתיחה בדיוק בעמוד** – הקורא המובנה פותח את הספר בעמוד שבו נמצא הביטוי, **ומסמן את המילים על העמוד**. כפתור "תוצאה הבאה" קופץ לעמוד הבא שבו מופיע הביטוי. אפשר גם לפתוח באפליקציה אחרת.
- **ספרים ישנים** – תיקון אוטומטי של טקסט עברי הפוך (סדר ויזואלי) ושל קידודים ישנים (Windows-1255 / DOS-862) שנראים כמו ג'יבריש. עמודים סרוקים בלי שכבת טקסט עוברים **זיהוי אותיות (OCR) עברי** – הרכיב כבר כלול בתוכנה.
- **עיון בספרים** בלי חיפוש, **חיפוש בתוך ספר**, **מועדפים**, **חיפושים אחרונים**, **ספרים שנפתחו לאחרונה** (המשך מהעמוד האחרון), העתקת טקסט של עמוד ושיתוף.
- **עדכון אוטומטי** – התוכנה בודקת פעם ביום אם יש גרסה חדשה כאן ב-GitHub ומציעה להתקין אותה. האינדקס וההגדרות נשמרים.

### מותאם ל-BOOX Go 6 (ומכשירי E-ink אחרים)
שחור-לבן בניגודיות גבוהה, בלי אנימציות, דפדוף בעמודים שלמים (לחיצה בצד המסך, החלקה, מקשי עוצמת הקול או הכפתורים למטה), שימוש חסכוני בזיכרון (2GB RAM): קבצים גדולים נקראים דרך קובץ זמני, והסריקה רצה ברקע בעדיפות נמוכה.

## התקנה

1. במכשיר, פותחים את [קישור ההורדה](https://github.com/chdv770-ctrl/Search-Plus/releases/latest/download/SearchPlus.apk) ומורידים את `SearchPlus.apk`.
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
````

## `settings.gradle.kts`

````kotlin
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
````

## `build.gradle.kts`

````kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.10" apply false
}
````

## `gradle.properties`

````properties
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
````

## `.gitignore`

````
.gradle/
build/
app/build/
local.properties
*.iml
.idea/
app/src/main/assets/tessdata/
````

## `app/build.gradle.kts`

````kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
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
            abiFilters += listOf("arm64-v8a")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // PDF text extraction (including the position of every letter, for highlighting)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // On-device AI model for search by meaning
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    // OCR for scanned pages that have no text layer
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")
}
````

## `.github/workflows/build-apk.yml`

````yaml
name: Build Search Plus APK

on:
  push:
    branches: [main]
    paths-ignore: ['**.md', 'docs/**']
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
        uses: softprops/action-gh-release@v2
        with:
          tag_name: v1.0.${{ github.run_number }}
          name: חיפוש פלוס 1.0.${{ github.run_number }}
          body: |
            קובץ ההתקנה של חיפוש פלוס – חיפוש מעמיק בתוך ספרי PDF במכשיר.
            הורידו את SearchPlus.apk והתקינו. מי שכבר מותקן אצלו – העדכון נשמר על האינדקס וההגדרות.
            ${{ github.event.head_commit.message }}
          files: SearchPlus.apk
          make_latest: true

      - name: Publish build log on failure
        if: failure()
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release delete build-log -y --cleanup-tag || true
          (grep -E "^e: |error:|FAILURE|What went wrong" -A3 build.log | head -c 50000; echo; echo ----; tail -c 20000 build.log) > build-log.txt 2>/dev/null || echo "no log" > build-log.txt
          gh release create build-log build-log.txt --prerelease --title "build log" --notes "failed build log"
````

## `app/src/main/AndroidManifest.xml`

````xml
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

    <queries>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:mimeType="application/pdf" />
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
        <activity android:name=".SettingsActivity" android:exported="false" />
        <activity android:name=".OrganizeActivity" android:exported="false" />

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
````

## `app/src/main/res/xml/file_paths.xml`

````xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-path name="storage" path="." />
    <root-path name="root" path="." />
    <cache-path name="cache" path="." />
</paths>
````

## `app/src/main/res/values/strings.xml`

````xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">חיפוש פלוס</string>
    <string name="reader_name">חיפוש פלוס – קורא</string>
</resources>
````

## `app/src/main/res/values/themes.xml`

````xml
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
````

## `app/src/main/java/com/chdv/searchplus/BrowseActivity.kt`

````kotlin
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
        items.add("פתיחה באפליקציה אחרת" to { Library.openExternal(this, f.absolutePath, 1) })
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
````

## `app/src/main/java/com/chdv/searchplus/Db.kt`

````kotlin
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

    fun invalidate() { cache = null }

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
````

## `app/src/main/java/com/chdv/searchplus/Indexer.kt`

````kotlin
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
    @Volatile var phase = ""
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var current = ""
    @Volatile var cancel = false
    @Volatile var lastFinished = 0L
    /** A single book the user asked to OCR now. */
    @Volatile var ocrFile: Long? = null

    fun text(): String = if (!running) "" else buildString {
        append(phase)
        if (total > 0) append("  $done / $total")
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
        val pending = db.pendingFiles()
        Progress.phase = "קורא ספרים"; Progress.total = pending.size; Progress.done = 0
        for ((id, path) in pending) {
            if (Progress.cancel) return
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

        // 3. OCR of scanned pages
        if (Prefs.b("ocrAuto", true) && Ocr.ready(c)) Ocr.runPending(c, null)
        if (Progress.cancel) return

        // 4. meaning index
        if (Prefs.b("semantic", false) && Semantic.ready(c)) Semantic.indexPending(c)
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
            setReferenceCounted(false); acquire(6 * 3600 * 1000L)
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
````

## `app/src/main/java/com/chdv/searchplus/Library.kt`

````kotlin
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

    /** Open a PDF in another app (the device's default reader). */
    fun openExternal(a: Activity, path: String, page: Int) {
        val f = File(path)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(a, f), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra("page", page)
            .putExtra("PAGE", page)
            .putExtra("pageNumber", page)
        val chooser = Intent.createChooser(i, "פתיחה באמצעות")
        if (!a.safeStart(chooser)) a.toast("לא נמצאה אפליקציה לפתיחת PDF")
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
````

## `app/src/main/java/com/chdv/searchplus/MainActivity.kt`

````kotlin
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
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        if (Library.hasAccess(this) && Library.roots().isNotEmpty() && !Progress.running &&
            System.currentTimeMillis() - Progress.lastFinished > 10 * 60 * 1000 && Prefs.b("autoScan", true)) {
            Indexer.start(this)
        }
        Updater.dailyCheck(this)
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
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, lp(dp(38), dp(38)).m(0, 0, 0, 0))
        head.addView(vbox().apply {
            addView(label("חיפוש פלוס", 22f, Col.TEXT, true))
            addView(label("חיפוש מעמיק בתוך הספרים שלך", 12.5f, Col.SUB))
            setPadding(dp(10), 0, dp(10), 0)
        }, lp(0, WRAP, 1f))
        head.addView(headerButton("📚 ספרים") { startActivity(Intent(this, BrowseActivity::class.java)) })
        head.addView(headerButton("🗂 סידור") { startActivity(Intent(this, OrganizeActivity::class.java)) })
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
            hint = "מה לחפש? לדוגמה: רבי שמעון"
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
            setOnClickListener { input.setText(""); results = null; showHome() }
        })
        box.addView(pill("חיפוש") { doSearch() }, lp(WRAP, WRAP))
        root.addView(box, lp(MATCH, WRAP).m(dp(12), dp(4), dp(12), dp(6)))

        modeRow = hbox().apply { setPadding(dp(12), 0, dp(12), 0) }
        root.addView(modeRow, lp(MATCH, WRAP))
        optRow = hbox().apply { setPadding(dp(12), dp(6), dp(12), 0) }
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(optRow)
        }, lp(MATCH, WRAP))
        buildModes()

        crumbs = FlowLayout(this).apply { setPadding(dp(12), dp(8), dp(12), 0); gap = dp(2) }
        root.addView(crumbs, lp(MATCH, WRAP))
        cats = FlowLayout(this).apply { setPadding(dp(12), dp(6), dp(12), dp(2)) }
        root.addView(cats, lp(MATCH, WRAP))
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
        setContentView(root)
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
        crumbs.removeAllViews(); cats.removeAllViews()
        summary.text = ""
        homeBox.removeAllViews()
        exec.execute {
            val st = try { Db.get().stats() } catch (e: Exception) { null }
            ui {
                if (st != null && st.books > 0) {
                    homeBox.addView(label("במאגר: ${st.books} ספרים · ${st.textPages} עמודים עם טקסט" +
                        (if (st.pending > 0) " · עוד ${st.pending} ספרים בתור לסריקה" else ""), 13.5f, Col.SUB).apply { setPadding(0, dp(4), 0, dp(8)) })
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
                "• לחיצה על תוצאה פותחת את הספר בדיוק בעמוד, והמילים מסומנות.", 14.5f, Col.SUB).apply { setLineSpacing(0f, 1.15f) })
        homeBox.addView(label("\nפותח על ידי שניאור וילהלם · מופץ לתועלת הרבים", 12.5f, Col.SUB).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
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

    private fun doSearch() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        Library.addHistory(q)
        prefix = emptyList()
        expanded.clear()
        home.visibility = View.GONE
        list.visibility = View.VISIBLE
        summary.text = if (semantic) "מחפש לפי משמעות… (בפעם הראשונה זה לוקח כמה שניות)" else "מחפש…"
        crumbs.removeAllViews(); cats.removeAllViews()
        adapter.set(emptyList())
        val token = ++searchToken
        val sem = semantic
        val m = Match.valueOf(Prefs.s("match", "PHRASE"))
        val pre = Prefs.b("prefixes", true)
        exec.execute {
            val r = if (sem) Search.runSemantic(this, q) else Search.run(q, m, pre)
            val e = if (sem) "" else Search.buildMatch(r.terms, m, pre)
            ui {
                if (token != searchToken) return@ui
                results = r; expr = e
                render()
            }
        }
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

    private fun openHit(h: BookHit, page: Int) {
        val r = results ?: return
        val hitPages = h.pages.map { it.page }.distinct().sorted().toIntArray()
        ReaderActivity.open(this, h.book.path, page, r.terms, hitPages)
    }

    private fun bookMenu(h: BookHit) {
        val b = h.book
        Sheet.actions(this, b.title, listOf(
            "פתיחה בעמוד הראשון שנמצא" to { openHit(h, h.pages.firstOrNull()?.page ?: 1) },
            "פתיחה באפליקציה אחרת" to { Library.openExternal(this, b.path, h.pages.firstOrNull()?.page ?: 1) },
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
                        snipExec.execute {
                            val s = (if (sem) Search.semanticSnippet(ph.pageId, terms) else Search.snippet(e, ph.pageId))?.replace('\n', ' ') ?: ""
                            val cs = marked(s)
                            ui {
                                snippetCache[ph.pageId] = cs
                                if (tv.tag == ph.pageId) tv.text = cs
                            }
                        }
                    }
                    v.setOnClickListener { openHit(row.hit, ph.page) }
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
                        a.pageId < 0 || src == null -> "לא נמצא משפט שעונה במדויק. הקטעים הקרובים ביותר מופיעים למטה."
                        else -> "‟" + a.text + "”"
                    }
                    v.findViewById<TextView>(3).text = if (src != null && a != null && a.pageId >= 0) "📖 ${src.first.title}, עמוד ${src.second}  ·  לפתיחה ◂" else ""
                    v.setOnClickListener {
                        if (src != null) ReaderActivity.open(this@MainActivity, src.first.path, src.second, Heb.words(a!!.text).filter { it.length > 2 }.take(6))
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
````

## `app/src/main/java/com/chdv/searchplus/OrganizeActivity.kt`

````kotlin
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
            tabs.addView(chip(t, i == tab) { if (!busy) { tab = i; render() } }, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
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
        val s = suggestions
        if (s == null) {
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
            run("מחזיר…", { _ -> val k = Organizer.undo(); ui { toast("הוחזרו $k") } }) { dups = null; suggestions = null; renames = null; render() }
        }
    }

    override fun onDestroy() { super.onDestroy(); bg.shutdown() }
}
````

## `app/src/main/java/com/chdv/searchplus/Organizer.kt`

````kotlin
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
}
````

## `app/src/main/java/com/chdv/searchplus/ReaderActivity.kt`

````kotlin
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
        fun open(c: Context, path: String, page: Int, terms: List<String> = emptyList(), hits: IntArray? = null) {
            if (Prefs.s("openWith", "internal") == "external" && c is android.app.Activity) {
                Library.addRecent(path, page)
                Library.openExternal(c, path, page)
                return
            }
            c.startActivity(Intent(c, ReaderActivity::class.java)
                .putExtra("path", path).putExtra("page", page)
                .putExtra("terms", terms.toTypedArray())
                .putExtra("hits", hits))
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
        head.addView(headerButton("חיפוש בספר") { searchInBook() })
        head.addView(headerButton("⋯") { menu() })
        root.addView(head, lp(MATCH, WRAP))
        root.addView(divider())

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
            if (terms.isNotEmpty() && path != null) {
                val rects = try { highlights(p, bmp.width / pageW) } catch (e: Throwable) { emptyList() }
                ui { if (token == renderToken && rects.isNotEmpty()) view.setHighlights(rects) }
            }
        }
    }

    /** Find the boxes of the searched words on the page with PDFBox (positions of every letter). */
    private fun highlights(p: Int, scale: Float): List<RectF> {
        val d = doc ?: Indexer.loadDoc(File(path!!)).also { doc = it }
        val plain = PDFTextStripper().apply { startPage = p; endPage = p }
        val raw = plain.getText(d)
        val method = Heb.repairPage(raw).method
        val want = terms.map { it.removeSuffix("*") }.filter { it.isNotEmpty() }
        val minLen = if (want.size == 1) 1 else 2
        val out = ArrayList<RectF>()
        val st = object : PDFTextStripper() {
            override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                val tps = textPositions ?: return
                val word = ArrayList<TextPosition>()
                fun flush() {
                    if (word.isEmpty()) return
                    val s = Heb.clean(Heb.applyMethod(word.joinToString("") { it.unicode ?: "" }, method))
                    val letters = s.filter { it.isLetterOrDigit() }
                    if (want.any { it.length >= minLen && letters.contains(it) }) {
                        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -1f; var b = -1f
                        for (tp in word) {
                            val x = tp.xDirAdj; val y = tp.yDirAdj
                            val h = tp.heightDir.coerceAtLeast(tp.fontSizeInPt * 0.7f)
                            l = minOf(l, x); r = maxOf(r, x + tp.widthDirAdj)
                            t = minOf(t, y - h); b = maxOf(b, y)
                        }
                        val pad = 1.5f
                        out.add(RectF((l - pad) * scale, (t - pad) * scale, (r + pad) * scale, (b + pad * 2) * scale))
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
        st.startPage = p; st.endPage = p
        st.getText(d)
        return out
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
        if (p != null) {
            val key = "$p|$page"
            items.add((if (Library.isFavorite(key)) "הסרה מהמועדפים" else "הוספת העמוד למועדפים ★") to {
                val on = Library.toggleFavorite(key); toast(if (on) "נוסף למועדפים" else "הוסר מהמועדפים")
            })
            items.add("העתקת הטקסט של העמוד" to { copyPage() })
            items.add("שיתוף הקובץ" to {
                val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, Library.uriFor(this, File(p)))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                safeStart(Intent.createChooser(i, "שיתוף"))
            })
            items.add("פתיחה באפליקציה אחרת" to { Library.openExternal(this, p, page) })
        }
        items.add(if (view.zoomed()) "חזרה לגודל מלא" to { view.reset() } else "הגדלה ×2" to { view.zoomTo(2f) })
        Sheet.actions(this, "עמוד $page", items)
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
    private val hl = Paint().apply { color = Col.HL; style = Paint.Style.FILL }
    private val hlLine = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }

    fun setPage(b: Bitmap, r: List<RectF>) {
        val old = bmp
        bmp = b; rects = r
        zoom = 1f; tx = 0f; ty = 0f
        if (old != null && old !== b) old.recycle()
        invalidate()
    }

    fun setHighlights(r: List<RectF>) { rects = r; invalidate() }
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
        hlLine.strokeWidth = 2f / s
        for (r in rects) { canvas.drawRect(r, hl); canvas.drawRect(r.left, r.bottom - 3f / s, r.right, r.bottom, hlLine) }
        canvas.restore()
    }

    private val scaleDet = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
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
````

## `app/src/main/java/com/chdv/searchplus/Search.kt`

````kotlin
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

    fun runSemantic(c: Context, q: String): Results {
        val db = Db.get()
        val books = db.books()
        val hits = try { Semantic.search(c, q) } catch (e: Throwable) {
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
        return Results(q, terms(q), byBook.values.toList(), true, false)
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
````

## `app/src/main/java/com/chdv/searchplus/Semantic.kt`

````kotlin
package com.chdv.searchplus

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.JsonReader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Search by meaning. Uses the multilingual-e5-small model (it understands Hebrew) running on the
 * device with ONNX Runtime. Every page is cut into passages; every passage gets a vector of 384
 * numbers that represents its meaning. A question is turned into a vector the same way and the
 * passages with the closest meaning are returned. Nothing leaves the device.
 */
object Semantic {
    private const val BASE = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/"
    const val DIM = 384
    private const val REC = 4 + 4 + DIM // pageId, scale, int8 vector
    private const val WORDS_PER_CHUNK = 90
    private const val MAX_CHUNKS = 6

    fun dir(c: Context) = File(c.filesDir, "e5")
    private fun modelFile(c: Context) = File(dir(c), "model_quantized.onnx")
    private fun tokFile(c: Context) = File(dir(c), "tokenizer.json")
    private fun vocabFile(c: Context) = File(dir(c), "vocab.bin")
    fun vectorsFile(c: Context) = File(c.filesDir, "vectors.bin")

    fun ready(c: Context) = modelFile(c).length() > 50_000_000 && (vocabFile(c).exists() || tokFile(c).length() > 1_000_000)

    fun download(c: Context, progress: (String) -> Unit) {
        dir(c).mkdirs()
        Net.download(BASE + "tokenizer.json", tokFile(c)) { progress("מוריד מילון… $it%") }
        Net.download(BASE + "onnx/model_quantized.onnx", modelFile(c)) { progress("מוריד את מודל הבינה… $it%") }
        progress("מכין את המילון…")
        buildVocab(c)
    }

    fun deleteAll(c: Context) {
        close()
        dir(c).deleteRecursively()
        vectorsFile(c).delete()
        Db.get().resetEmbeddings()
    }

    // ---------- tokenizer (SentencePiece Unigram, as in XLM-RoBERTa) ----------

    private var vocab: HashMap<String, Int>? = null
    private var scores: FloatArray = FloatArray(0)
    private var maxPiece = 16
    private var unkId = 3
    private var bosId = 0
    private var eosId = 2
    private var unkScore = -20f

    /** Read the vocabulary out of tokenizer.json (17MB) once, and keep a compact copy. */
    private fun buildVocab(c: Context) {
        val pieces = ArrayList<String>(260_000)
        val sc = ArrayList<Float>(260_000)
        var unk = 3
        JsonReader(InputStreamReader(BufferedInputStream(FileInputStream(tokFile(c)), 1 shl 16), Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "model") { r.skipValue(); continue }
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "unk_id" -> unk = r.nextInt()
                        "vocab" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginArray()
                                pieces.add(r.nextString())
                                sc.add(r.nextDouble().toFloat())
                                while (r.hasNext()) r.skipValue()
                                r.endArray()
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            r.endObject()
        }
        DataOutputStream(BufferedOutputStream(FileOutputStream(vocabFile(c)), 1 shl 16)).use { o ->
            o.writeInt(pieces.size); o.writeInt(unk)
            for (i in pieces.indices) { o.writeUTF(pieces[i]); o.writeFloat(sc[i]) }
        }
        tokFile(c).delete()
    }

    private fun loadVocab(c: Context) {
        if (vocab != null) return
        if (!vocabFile(c).exists()) buildVocab(c)
        DataInputStream(BufferedInputStream(FileInputStream(vocabFile(c)), 1 shl 16)).use { i ->
            val n = i.readInt()
            unkId = i.readInt()
            val m = HashMap<String, Int>(n * 2)
            val s = FloatArray(n)
            var mx = 1
            var minScore = 0f
            for (k in 0 until n) {
                val p = i.readUTF()
                s[k] = i.readFloat()
                m[p] = k
                if (p.length > mx) mx = p.length
                if (s[k] < minScore) minScore = s[k]
            }
            vocab = m; scores = s; maxPiece = min(mx, 24)
            unkScore = minScore - 10f
            bosId = m["<s>"] ?: 0
            eosId = m["</s>"] ?: 2
        }
    }

    private fun tokenize(text: String, maxLen: Int): LongArray {
        val v = vocab!!
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()
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
                val lim = min(maxPiece, n - i)
                for (len in 1..lim) {
                    val id = v[w.substring(i, i + len)] ?: continue
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

    // ---------- model ----------

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Synchronized
    private fun open(c: Context) {
        if (session != null) return
        loadVocab(c)
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(max(1, min(4, Runtime.getRuntime().availableProcessors())))
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env = e
        session = e.createSession(modelFile(c).absolutePath, opts)
    }

    @Synchronized
    fun close() {
        try { session?.close() } catch (e: Exception) {}
        session = null
        vocab = null
    }

    @Synchronized
    fun embed(c: Context, text: String, maxLen: Int = 192): FloatArray {
        open(c)
        val s = session!!
        val e = env!!
        val ids = tokenize(text, maxLen)
        val n = ids.size
        val shape = longArrayOf(1, n.toLong())
        val tensors = ArrayList<OnnxTensor>()
        try {
            val inputs = HashMap<String, OnnxTensor>()
            OnnxTensor.createTensor(e, LongBuffer.wrap(ids), shape).also { tensors.add(it); inputs["input_ids"] = it }
            OnnxTensor.createTensor(e, LongBuffer.wrap(LongArray(n) { 1L }), shape).also { tensors.add(it); inputs["attention_mask"] = it }
            if (s.inputNames.contains("token_type_ids")) {
                OnnxTensor.createTensor(e, LongBuffer.wrap(LongArray(n)), shape).also { tensors.add(it); inputs["token_type_ids"] = it }
            }
            s.run(inputs).use { r ->
                @Suppress("UNCHECKED_CAST")
                val out = (r.get(0).value as Array<Array<FloatArray>>)[0]
                val v = FloatArray(DIM)
                for (row in out) for (d in 0 until DIM) v[d] += row[d]
                var norm = 0f
                for (d in 0 until DIM) { v[d] /= out.size; norm += v[d] * v[d] }
                norm = sqrt(norm).coerceAtLeast(1e-9f)
                for (d in 0 until DIM) v[d] /= norm
                return v
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

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
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(vectorsFile(c), true), 1 shl 16)).use { out ->
                for (b in books) {
                    if (Progress.cancel) return
                    Progress.current = b.title
                    val pages = db.pagesOf(b.id)
                    for ((pageId, body) in pages) {
                        if (Progress.cancel) return
                        for (ch in chunks(body)) {
                            val v = embed(c, "passage: $ch")
                            var mx = 0f
                            for (x in v) mx = max(mx, kotlin.math.abs(x))
                            val sc = if (mx == 0f) 1f else mx / 127f
                            out.writeInt(pageId.toInt())
                            out.writeFloat(sc)
                            val bytes = ByteArray(DIM) { (v[it] / sc).roundToInt().coerceIn(-127, 127).toByte() }
                            out.write(bytes)
                        }
                    }
                    out.flush()
                    db.markEmbedded(b.id)
                    Progress.done++
                }
            }
        } finally {
            close()
        }
    }

    data class Hit(val pageId: Long, val score: Float)

    /** The pages whose meaning is closest to the question. */
    fun search(c: Context, query: String, k: Int = 400): List<Hit> {
        val f = vectorsFile(c)
        if (!f.exists() || f.length() < REC) return emptyList()
        val q = embed(c, "query: $query", 96)
        val best = HashMap<Int, Float>() // best score per page
        val heap = PriorityQueue<Pair<Int, Float>>(k + 1, compareBy { it.second })
        val buf = ByteArray(DIM)
        DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 18)).use { i ->
            val n = f.length() / REC
            for (r in 0 until n) {
                val pid = i.readInt()
                val sc = i.readFloat()
                i.readFully(buf)
                var dot = 0f
                for (d in 0 until DIM) dot += q[d] * buf[d]
                dot *= sc
                val prev = best[pid]
                if (prev != null && prev >= dot) continue
                if (heap.size >= k && dot <= heap.peek()!!.second) continue
                best[pid] = dot
                heap.add(pid to dot)
                if (heap.size > k) heap.poll()
            }
        }
        // the heap can contain the same page twice; keep its best score
        val m = HashMap<Int, Float>()
        heap.forEach { (p, s) -> if ((m[p] ?: -9f) < s) m[p] = s }
        return m.entries.sortedByDescending { it.value }.map { Hit(it.key.toLong(), it.value) }
    }

    fun vectorCount(c: Context): Long = vectorsFile(c).length() / REC

    class Answer(val text: String, val pageId: Long, val score: Float)

    /** Extractive answer: the single sentence (from the closest pages) whose meaning is closest to the question. */
    fun answer(c: Context, question: String, pageIds: List<Long>): Answer? {
        if (pageIds.isEmpty()) return null
        val q = embed(c, "query: $question", 96)
        var best: Answer? = null
        val db = Db.get()
        for (pid in pageIds) {
            val body = db.pageTextById(pid) ?: continue
            // sentences, or ~30-word pieces where the text has no punctuation
            val sents = ArrayList<String>()
            for (part in body.replace('\n', ' ').split(Regex("(?<=[.:;?!])\\s+"))) {
                val ws = part.split(' ').filter { it.isNotBlank() }
                if (ws.size < 5) continue
                var i = 0
                while (i < ws.size) { sents.add(ws.subList(i, minOf(ws.size, i + 35)).joinToString(" ")); i += 30 }
            }
            for (sn in sents.take(40)) {
                val v = embed(c, "passage: $sn", 80)
                var dot = 0f
                for (d in 0 until DIM) dot += q[d] * v[d]
                if (best == null || dot > best.score) best = Answer(sn, pid, dot)
            }
        }
        return best?.takeIf { it.score > 0.78f }
    }
}
````

## `app/src/main/java/com/chdv/searchplus/SettingsActivity.kt`

````kotlin
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

    private fun section(t: String) = box.addView(label(t, 15f, Col.ACC, true).apply { setPadding(dp(4), dp(18), dp(4), dp(6)) })

    private fun item(title: String, sub: String = "", onClick: (() -> Unit)?): TextView {
        val v = vbox().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = pressBg(Col.CARD, Col.ACC_LIGHT, 12)
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

        section("חיפוש לפי משמעות (בינה מלאכותית)")
        val semReady = Semantic.ready(this)
        item(if (semReady) "מודל המשמעות מותקן ✓" else "הורדת מודל המשמעות (כ-120MB)",
            if (semReady) "וקטורים באינדקס: ${Semantic.vectorCount(this)} · לחיצה לניהול"
            else "מאפשר לכתוב שאלה או רעיון ולמצוא את המקומות שעוסקים בהם. עובד בתוך המכשיר, בלי אינטרנט") { semanticMenu() }
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
        item("פתיחת תוצאה", if (ow == "internal") "בקורא המובנה – בעמוד הנכון עם סימון המילים" else "באפליקציה אחרת (קורא ברירת המחדל)") {
            Sheet.choice(this, "איך לפתוח ספר?", listOf(
                "internal" to "קורא מובנה – בעמוד המדויק עם סימון (מומלץ)",
                "external" to "אפליקציה אחרת של המכשיר",
            ), ow) { Prefs.put("openWith", it); render() }
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
        item("חיפוש פלוס", "גרסה ${version()}\nפותח על ידי שניאור וילהלם\nמופץ בחינם לתועלת הרבים\n\nקוד פתוח: github.com/chdv770-ctrl/Search-Plus", null)
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

    override fun onDestroy() { super.onDestroy(); bg.shutdown() }
}
````

## `app/src/main/java/com/chdv/searchplus/Text.kt`

````kotlin
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
````

## `app/src/main/java/com/chdv/searchplus/Ui.kt`

````kotlin
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
object Col {
    val BG = 0xFFFFFFFF.toInt()
    val TEXT = 0xFF111111.toInt()
    val SUB = 0xFF5A5A5A.toInt()
    val LINE = 0xFFD6D6D6.toInt()
    val CARD = 0xFFF3F4F6.toInt()
    val ACC = 0xFF1F3A5F.toInt()
    val ACC_LIGHT = 0xFFE3EAF3.toInt()
    val GOLD = 0xFFFFD65A.toInt()
    val HL = 0x77FFC800
    val RED = 0xFFB00020.toInt()
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
````

## `app/src/main/java/com/chdv/searchplus/Updater.kt`

````kotlin
package com.chdv.searchplus

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Self update from GitHub Releases (github.com/chdv770-ctrl/Search-Plus). Every push to the
 * repository builds a new signed APK and publishes it as release v1.0.<build>. All builds are
 * signed with the same key, so an update installs over the existing app and keeps the index.
 */
object Updater {
    private const val API = "https://api.github.com/repos/chdv770-ctrl/Search-Plus/releases/latest"
    @Volatile private var busy = false

    private fun build(v: String): Int = v.trim().removePrefix("v").substringAfterLast('.').toIntOrNull() ?: 0

    private fun local(a: Activity) = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }

    fun dailyCheck(a: Activity) {
        if (!Prefs.b("autoUpdate", true)) return
        if (System.currentTimeMillis() - Prefs.l("lastUpdateCheck", 0L) < 24L * 3600 * 1000) return
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
                    if (!silent) Sheet.info(a, "לא ניתן לבדוק עדכונים", "ודאו שהמכשיר מחובר לאינטרנט ונסו שוב.")
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
````
