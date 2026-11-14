# חיפוש פלוס (Search Plus): מסמך המשך עבודה למפתח או ל-Claude Code

אפליקציית אנדרואיד לחיפוש מעמיק בתוך ספרי PDF שבמכשיר (כולל כרטיס זיכרון): חיפוש מילים, חיפוש לפי משמעות (AI על המכשיר), קטגוריות לפי תיקיות, קורא מובנה שפותח בעמוד ומסמן את המילים.
מותאמת ל-**BOOX Go 6** (E-ink, ‏Android 11, ‏2GB RAM). החבילה: `com.chdv.searchplus`. המאגר: `github.com/sw0555041250-code/Search-Plus-` (פומבי). פותח: שניאור וילהלם.

**כדי להמשיך לעבוד עם Claude Code:** פותחים סשן עם המאגר הזה, ואומרים "קרא את HANDOFF.md והמשך מכאן: <מה רוצים>". כל קוד המקור נמצא גם בקובץ `SearchPlus-full-source.md`.

## בנייה ופרסום
- אין צורך ב-Android Studio. כל push ל-`main` מפעיל את `.github/workflows/build-apk.yml`: מוריד את מודל ה-OCR העברי (`heb.traineddata` מ-tessdata_fast) לתוך `app/src/main/assets/tessdata`, בונה APK חתום (release), ומפרסם Release ‏`v1.0.<run_number>` עם `SearchPlus.apk` (סימון latest).
- **בנייה שנכשלה:** נוצר pre-release בשם `build-log` עם `build-log.txt` (שגיאות קומפילציה).
- **חתימה:** `app/searchplus.p12`, ‏PKCS12, סיסמה `searchplus123`, ‏alias `searchplus`. אסור להחליף.
- **גרסה:** `versionName = 1.0.<GITHUB_RUN_NUMBER+100>`, ‏`versionCode = <run+100>` (‏+200 כי המאגר עבר פעמיים (`chdv770-ctrl/Search-Plus` עד 1.0.4, ואז `sw0555041250-code/-` עד 1.0.1xx) – כך כל גרסה חדשה גבוהה מהישנות ומותקנת מעליהן).
- **ענפים:** push לכל ענף `claude/**` בונה APK לבדיקה (בלי לפרסם). רק `main` מפרסם Release.
- **בדיקות:** `.github/workflows/test-core.yml` מוריד את המודלים האמיתיים, מייצר ערכי ייחוס עם ספריות Python הרשמיות (`tools/make_reference.py`, כולל הקלטות קול עבריות של edge-tts) ובודק מולם את מודול `core`.
- ABI: רק `arm64-v8a` (כדי שה-APK יהיה קטן). למכשיר 32 ביט – להוסיף `armeabi-v7a` ב-`app/build.gradle.kts`.
- ספריות: androidx core/appcompat/recyclerview, ‏`com.tom-roush:pdfbox-android` (חילוץ טקסט ומיקום אותיות), ‏`com.microsoft.onnxruntime:onnxruntime-android` (מודל המשמעות), ‏`cz.adaptech.tesseract4android` (OCR, מ-JitPack).

## מודול core (Kotlin טהור, נבדק בלי אנדרואיד)
| קובץ | תפקיד |
|---|---|
| `HebRef.kt` | `HebNum` (גימטריה, שמות אותיות כפי שאומרים אותן – "כף זין", מספרים במילים), `RefParser` (הבנת הפניה: גמרא דף/עמוד, ליקוטי שיחות חלק/פרשה/שיחה, פרק/סימן; התאמה "פונטית" לשמות מסכתות שזוהו לא נכון), `PageFinder` (סימני דף בראש העמוד, הערכת עמוד מעוגנים, כותרות פרשה/פרק, מספר כרך משם קובץ) |
| `Whisper.kt` | `WhisperMel` (ספקטרוגרמה זהה ל-OpenAI), `WhisperTokenizer` (BPE), `Whisper` (מקודד + מפענח merged עם KV-cache, עם רמז שמות ספרים), `E5Tokenizer`/`E5Model` (מודל המשמעות) |
| `Json.kt` | קורא JSON קטן + קריאה זורמת של אוצר המילים של e5 |

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
| `Organizer.kt` | סידור המאגר על המכשיר: כפולים לפי תוכן (hash לקובץ זהה + טביעת אצבע של רצפי 5 מילים / bottom‑k MinHash לתוכן זהה), סיווג ספר לתיקייה (TF‑IDF של כל תיקייה מול הספר, יורד בעץ התיקיות), תוכנית סידור לתיקייה עם תצוגה מקדימה (`Plan`: לפי נושא – k‑means על התוכן, לפי סדרה, לפי אות, לתיקיות המאגר), שמות חכמים (דף שער – הגופן הגדול / מטא‑דאטה; שם תיקייה לפי הספרים), העברה/שינוי שם עם עדכון האינדקס ויומן ביטול (`moveLog`) |
| `Voice.kt` | חיפוש קולי: `VoiceModels` (הורדת Whisper small/base לאחסון עם הכי הרבה מקום, טעינה ושחרור אחרי 3 דקות), `Recorder` (מיקרופון + זיהוי סוף דיבור), `VoiceSearch` (שירות הדיבור של המכשיר אם יש, אחרת Whisper; משהה את הסריקה בזמן הזיהוי) |
| `Jump.kt` | קפיצה ישירה להפניה: מציאת כל הספרים המתאימים (כל המהדורות), העמוד לפי סימני הדף/הכותרות, ותיקון המשתמש ("✓ זה המקום") שנשמר ב-`dafCal` |
| `OrganizeActivity.kt` | המסך של סידור המאגר: לשוניות כפולים / סידור לתיקיות (כולל תצוגה מקדימה ועריכה) / שמות חכמים, וכפתור "ביטול" |

## מפתחות Prefs
`roots`, `semanticRoots`, `match` (PHRASE/ALL/NEAR/ANY), `prefixes`, `sort`, `lastSemantic`, `semantic`, `ocrAuto`, `autoScan`, `sortByPosition`, `openWith` (internal/external), `fontSize`, `autoUpdate`, `lastUpdateCheck`, `favs` (`path|page`), `history`, `recent` (`path|page`), `moveLog` (`M|now|wasDir` או `R|now|wasPath`), `voiceEngine` (auto/system/whisper), `whisperModel` (small/base), `whisperDir`, `systemFailed`, `readerPkg`/`readerCls` (אפליקציית ברירת המחדל לפתיחה), `copyTerms`, `dafCal` (`path|page|amudIndex`), `semError`.

## חיפוש לפי משמעות – מה תוקן
האינדקס נבנה עכשיו לפני ה-OCR (קודם הוא חיכה עד שכל העמודים הסרוקים יעברו OCR – במאגר גדול זה לא קרה אף פעם), שגיאות נשמרות ומוצגות בהגדרות, ובחיפוש משווים במקום גם את העמודים שמכילים את מילות השאלה – כך יש תוצאות עוד לפני שהאינדקס הושלם.

## רעיונות להמשך
- גיבוי האינדקס לכרטיס הזיכרון ושחזור אחרי התקנה מחדש.
- הדגשה גם בעמודי OCR (מיקומי מילים מ-Tesseract ResultIterator).
- תמיכה ב-EPUB/TXT/DOCX.
- חיפוש היברידי (שילוב ציון מילים וציון משמעות).
- ייצוא/גיבוי של האינדקס לכרטיס הזיכרון.
