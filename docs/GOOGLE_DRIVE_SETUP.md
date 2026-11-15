# חיבור Google Drive – רישום חד-פעמי אצל Google

כדי שהכפתור **"🔗 חיבור לחשבון Google Drive"** בחיפוש פלוס יעבוד, Google צריכה להכיר את האפליקציה.
עושים את זה **פעם אחת בלבד**, מהמחשב (הכי נוח) או מהטלפון, בערך 10 דקות, עם חשבון Google רגיל (Gmail).
אחרי זה כל מי שמשתמש בתוכנה פשוט לוחץ "חיבור", בוחר חשבון ומאשר – ואין צורך לגעת בזה שוב.

> אפשר לבקש מ-Claude לעזור: לפתוח לו את המסמך הזה ואת https://console.cloud.google.com, ולעבור יחד שלב אחרי שלב.

## הפרטים של האפליקציה (להעתיק בדיוק)
- **Package name:** `com.chdv.searchplus`
- **SHA-1:** `71:8B:76:ED:F4:A4:4E:34:9B:F6:19:A4:98:C4:C2:8F:53:63:4B:D4`

## השלבים
1. **פרויקט:** נכנסים ל-https://console.cloud.google.com ← למעלה **Select a project** ← **New project** ← שם: `Search Plus` ← **Create**. מוודאים שהפרויקט החדש נבחר למעלה.
2. **הפעלת Drive:** בשורת החיפוש למעלה כותבים **Google Drive API** ← נכנסים ← **Enable**.
3. **מסך ההסכמה** (☰ ← **APIs & Services** ← **OAuth consent screen**, ובחלק מהחשבונות זה נקרא **Google Auth Platform** ← **Get started**):
   - App name: `חיפוש פלוס` · User support email: המייל שלכם ← Next.
   - Audience: **External** ← Next. Contact information: המייל שלכם ← Next ← מסכימים ← **Create**.
4. **ההרשאה:** בתפריט של Google Auth Platform ← **Data access** ← **Add or remove scopes** ← בחיפוש כותבים `drive.readonly` ← מסמנים את
   `.../auth/drive.readonly` ← **Update** ← **Save**.
5. **מי יכול להשתמש:** ← **Audience**:
   - הכי פשוט: **Publish app** ← Confirm. (Google תציג למשתמשים אזהרה "Google hasn't verified this app" – לוחצים **Advanced** ← **Go to חיפוש פלוס** וממשיכים. זה בסדר: האפליקציה רק קוראת.)
   - או להשאיר ב-Testing ולהוסיף ב-**Test users** את כתובות ה-Gmail של כל מי שישתמש. שימו לב: ב-Testing האישור פג אחרי 7 ימים וצריך לאשר שוב.
6. **האפליקציה עצמה:** ← **Clients** (או **Credentials** ← **Create credentials** ← **OAuth client ID**) ← **Create client** ←
   Application type: **Android** ← Name: `Search Plus Android` ← Package name ו-SHA-1 מלמעלה ← **Create**.
7. זהו. אחרי כמה דקות, בתוכנה: ☰ ← "ספרים מ-Google Drive" ← "🔗 חיבור לחשבון Google Drive" ← בוחרים חשבון ← מאשרים.

אם עדיין מופיע "Google עוד לא מכירה את חיפוש פלוס": לוודא ש-Drive API הופעל (שלב 2), שה-Package name וה-SHA-1 הועתקו בדיוק (שלב 6),
ושהחשבון שמתחברים איתו נמצא ב-Test users או שהאפליקציה פורסמה (שלב 5). שינויים אצל Google לוקחים לפעמים 5–10 דקות.

## איך זה עובד בתוכנה
- Google מציגה את מסך הכניסה והאישור שלה; התוכנה מקבלת הרשאת **קריאה בלבד** – שום דבר ב-Drive לא משתנה.
- התוכנה מוצאת את כל קבצי ה-PDF: ב-Drive שלך **וגם בתיקיות ששיתפו איתך** (בתיקייה "משותף איתי"). בוחרים באילו תיקיות לחפש.
- **הספרים נשארים בענן.** כדי לסרוק ספר, הוא יורד לרגע, הטקסט שלו נשמר באינדקס (קטן), והקובץ נמחק.
  כשפותחים ספר מתוצאות החיפוש – הוא יורד לקריאה; האחרונים נשמרים זמנית (עד 300MB) ונמחקים לבד.
- בלי אינטרנט: החיפוש עובד בכל מה שכבר נסרק. ספרים שעוד לא נסרקו מחכים לסריקה הבאה.
