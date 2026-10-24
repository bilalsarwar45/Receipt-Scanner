package com.receiptscan.commands

/**
 * Words the command parser understands, in English, Roman Urdu and Urdu script.
 * Keep this list honest: a word belongs here only if a test covers it.
 */
object Lexicon {
    /** Category aliases → built-in category id. Multi-word aliases are matched as phrases. */
    val categoryAliases: Map<String, String> = HashMap<String, String>().apply {
        fun alias(id: String, vararg words: String) = words.forEach { this[it] = id }
        alias("fuel", "fuel", "petrol", "diesel", "cng", "ایندھن", "پیٹرول", "پٹرول", "ڈیزل")
        alias("groceries", "grocery", "groceries", "rashan", "ration", "sabzi", "sabzian", "sauda", "سودا", "راشن", "گروسری", "سبزی", "supermarket")
        alias("dining", "dining", "restaurant", "dinner", "lunch", "breakfast", "food out", "eating out", "cafe", "coffee", "ریسٹورنٹ", "کھانا")
        alias("transport", "transport", "taxi", "uber", "careem", "indrive", "rickshaw", "bus", "fare", "kiraya", "کرایہ", "ٹیکسی", "رکشہ")
        alias("health", "health", "medicine", "medicines", "pharmacy", "doctor", "dawai", "dawa", "دوائی", "دوا", "ڈاکٹر")
        alias("shopping", "shopping", "clothes", "kapray", "kapde", "shoes", "کپڑے", "جوتے", "شاپنگ")
        alias("electronics", "electronics", "charger", "phone", "mobile", "laptop", "headphones")
        alias("utilities", "utilities", "utility", "electricity", "bijli", "gas bill", "water bill", "internet", "بجلی", "گیس", "پانی")
        alias("bills", "bill", "bills", "بل")
        alias("household", "household", "ghar", "گھر")
        alias("personal-care", "personal care", "salon", "barber", "toiletries")
        alias("education", "education", "school", "fees", "fee", "tuition", "books", "اسکول", "فیس")
        alias("entertainment", "entertainment", "movie", "movies", "cinema", "netflix", "games")
        alias("travel", "travel", "hotel", "flight", "ticket", "safar", "سفر")
        alias("gifts", "gift", "gifts", "tohfa", "تحفہ")
    }

    val addVerbs = setOf(
        "add", "added", "record", "log", "save", "spent", "spend", "paid", "pay", "put", "enter", "note",
        "daal", "dal", "daalo", "likh", "likho", "shamil", "شامل", "ڈال", "لکھ", "لکھو", "ڈالو", "kharch", "kharcha",
    )
    val queryVerbs = setOf(
        "show", "bring", "list", "give", "get", "display", "see", "view", "tell", "dikhao", "dikha", "batao", "bata", "دکھاؤ", "دکھائیں", "بتاؤ", "بتائیں", "laao", "lao",
    )
    val summaryWords = setOf("how", "much", "total", "kitna", "kitne", "kitni", "sum", "summary", "summarize", "کتنا", "کتنے", "کتنی", "ٹوٹل")
    val expenseNouns = setOf(
        "expense", "expenses", "spending", "spend", "spent", "kharcha", "kharchay", "kharche", "kharchey", "kharch", "خرچ", "خرچہ", "خرچے", "اخراجات",
    )
    val findVerbs = setOf("find", "search", "look", "where", "dhoondo", "dhundo", "talash", "ڈھونڈو", "تلاش")
    val receiptNouns = setOf("receipt", "receipts", "rasid", "raseed", "رسید", "بل")
    val undoWords = setOf("undo", "revert", "wapas", "واپس", "ہٹاؤ", "hatao", "hata")
    val editWords = setOf("change", "make", "edit", "update", "correct", "set", "badal", "badlo", "بدل", "بدلو", "تبدیل")
    val remindWords = setOf("remind", "reminder", "yaad", "یاد")
    val pronounsIt = setOf("it", "that", "this", "ise", "isay", "isey", "usay", "use", "اسے", "اس", "یہ")

    val today = setOf("today", "aaj", "آج", "tonight")
    val yesterday = setOf("yesterday")
    /** "kal" means yesterday or tomorrow; "parson" the day before yesterday or after tomorrow. */
    val kal = setOf("kal", "کل")
    val parson = setOf("parson", "parsoon", "پرسوں")
    /** Past-tense markers that make "kal" mean yesterday. */
    val pastMarkers = setOf("kiya", "kiye", "kia", "liya", "liye", "tha", "thi", "the", "diya", "diye", "hua", "huay", "spent", "paid", "کیا", "کیے", "لیا", "لیے", "تھا", "تھی", "دیا", "دیے", "ہوا")
    val last = setOf("last", "past", "previous", "pichlay", "pichle", "pichhle", "pichhlay", "guzishta", "پچھلے", "گزشتہ")
    val thisWords = setOf("this", "current", "is", "iss", "اس", "رواں")
    val dayWords = setOf("day", "days", "din", "دن", "دنوں")
    val weekWords = setOf("week", "weeks", "hafta", "hafte", "haftay", "ہفتے", "ہفتہ")
    val monthWords = setOf("month", "months", "mahina", "mahine", "mahinay", "مہینے", "مہینہ", "ماہ")
    val yearWords = setOf("year", "saal", "sal", "سال")

    /** Words with no meaning for intent or slots; they are ignored when checking for unknown content. */
    val fillers = setOf(
        "me", "my", "i", "the", "for", "of", "on", "in", "to", "a", "an", "please", "kindly", "from", "all", "mera", "meri", "mere",
        "ka", "ki", "ke", "ko", "kar", "karo", "kardo", "do", "dein", "den", "de", "hai", "hain", "ho", "par", "pe", "se", "main", "mein",
        "کا", "کی", "کے", "کو", "کر", "دو", "ہے", "میں", "میرے", "میرا", "میری", "سے", "پر", "plz", "what", "did", "were", "was", "with", "at",
        "item", "items", "purchase", "purchases", "entry", "new", "an", "amount", "rupee", "rupees", "rupay", "rupaye", "rs", "pkr", "روپے", "روپیہ",
    )
}
