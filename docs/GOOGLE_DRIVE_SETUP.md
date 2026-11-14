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
