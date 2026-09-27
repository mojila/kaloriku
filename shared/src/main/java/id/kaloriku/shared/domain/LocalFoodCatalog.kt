package id.kaloriku.shared.domain

/** One entry in the built-in Indonesian food catalog. */
data class LocalFood(
    val id: String,
    val name: String,
    val category: FoodCategory,
    val kcalPerPortion: Int,
    val portionLabel: String,
    val aliases: List<String> = emptyList(),
    val carbsG: Double = 0.0,
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
)

/**
 * A hand-curated catalog of common Indonesian foods, used for fast local matching
 * and as grounding for the Jev calorie questions. Values are typical single-portion
 * estimates from Indonesian food composition references and are intentionally rough.
 */
object LocalFoodCatalog {

    val foods: List<LocalFood> = listOf(
        // Nasi & karbohidrat
        LocalFood("nasi_putih", "Nasi Putih", FoodCategory.NASI, 175, "1 centong (100 g)", listOf("nasi", "sega")),
        LocalFood("nasi_goreng", "Nasi Goreng", FoodCategory.NASI, 380, "1 piring", listOf("nasgor")),
        LocalFood("nasi_uduk", "Nasi Uduk", FoodCategory.NASI, 320, "1 porsi"),
        LocalFood("nasi_kuning", "Nasi Kuning", FoodCategory.NASI, 330, "1 porsi"),
        LocalFood("nasi_padang", "Nasi Padang", FoodCategory.NASI, 650, "1 porsi lengkap"),
        LocalFood("lontong", "Lontong", FoodCategory.NASI, 120, "2 potong"),
        LocalFood("ketupat", "Ketupat", FoodCategory.NASI, 110, "1 buah"),
        LocalFood("bubur_ayam", "Bubur Ayam", FoodCategory.NASI, 280, "1 mangkuk", listOf("bubur")),
        LocalFood("mie_goreng", "Mie Goreng", FoodCategory.NASI, 380, "1 piring", listOf("mie")),
        LocalFood("mie_kuah", "Mie Kuah", FoodCategory.KUAH, 300, "1 mangkuk"),
        LocalFood("kwetiau", "Kwetiau Goreng", FoodCategory.NASI, 400, "1 piring"),
        LocalFood("bihun", "Bihun Goreng", FoodCategory.NASI, 330, "1 piring"),
        LocalFood("kentang", "Kentang", FoodCategory.NASI, 130, "1 buah sedang"),
        LocalFood("singkong", "Singkong Rebus", FoodCategory.NASI, 160, "1 potong"),
        LocalFood("ubi", "Ubi Rebus", FoodCategory.NASI, 150, "1 buah sedang"),

        // Lauk hewani
        LocalFood("ayam_goreng", "Ayam Goreng", FoodCategory.LAUK_HEWANI, 260, "1 potong paha", listOf("ayam")),
        LocalFood("ayam_bakar", "Ayam Bakar", FoodCategory.LAUK_HEWANI, 230, "1 potong"),
        LocalFood("ayam_geprek", "Ayam Geprek", FoodCategory.LAUK_HEWANI, 320, "1 porsi"),
        LocalFood("rendang", "Rendang", FoodCategory.LAUK_HEWANI, 250, "1 potong"),
        LocalFood("sate_ayam", "Sate Ayam", FoodCategory.LAUK_HEWANI, 200, "10 tusuk", listOf("sate")),
        LocalFood("sate_kambing", "Sate Kambing", FoodCategory.LAUK_HEWANI, 280, "10 tusuk"),
        LocalFood("gulai_ayam", "Gulai Ayam", FoodCategory.KUAH, 280, "1 porsi"),
        LocalFood("ikan_goreng", "Ikan Goreng", FoodCategory.LAUK_HEWANI, 220, "1 ekor sedang"),
        LocalFood("ikan_bakar", "Ikan Bakar", FoodCategory.LAUK_HEWANI, 180, "1 ekor"),
        LocalFood("pepes_ikan", "Pepes Ikan", FoodCategory.LAUK_HEWANI, 150, "1 bungkus"),
        LocalFood("telur_goreng", "Telur Goreng", FoodCategory.LAUK_HEWANI, 120, "1 butir", listOf("telur")),
        LocalFood("telur_rebus", "Telur Rebus", FoodCategory.LAUK_HEWANI, 78, "1 butir"),
        LocalFood("telur_dadar", "Telur Dadar", FoodCategory.LAUK_HEWANI, 150, "1 butir"),
        LocalFood("udang_goreng", "Udang Goreng", FoodCategory.LAUK_HEWANI, 180, "5 ekor"),
        LocalFood("bakso", "Bakso Sapi", FoodCategory.KUAH, 260, "1 porsi", listOf("baso")),
        LocalFood("soto_ayam", "Soto Ayam", FoodCategory.KUAH, 250, "1 mangkuk", listOf("soto")),
        LocalFood("sup_ayam", "Sup Ayam", FoodCategory.KUAH, 180, "1 mangkuk"),

        // Lauk nabati
        LocalFood("tempe_goreng", "Tempe Goreng", FoodCategory.LAUK_NABATI, 120, "2 potong", listOf("tempe")),
        LocalFood("tempe_orek", "Tempe Orek", FoodCategory.LAUK_NABATI, 160, "1 porsi"),
        LocalFood("tahu_goreng", "Tahu Goreng", FoodCategory.LAUK_NABATI, 110, "2 potong", listOf("tahu")),
        LocalFood("tahu_bacem", "Tahu Bacem", FoodCategory.LAUK_NABATI, 140, "2 potong"),
        LocalFood("perkedel", "Perkedel Kentang", FoodCategory.GORENGAN, 130, "1 buah"),
        LocalFood("oncom", "Oncom Goreng", FoodCategory.LAUK_NABATI, 120, "2 potong"),

        // Sayur
        LocalFood("sayur_asem", "Sayur Asem", FoodCategory.SAYUR, 90, "1 mangkuk"),
        LocalFood("sayur_sop", "Sayur Sop", FoodCategory.SAYUR, 80, "1 mangkuk"),
        LocalFood("daun_singkong", "Daun Singkong", FoodCategory.SAYUR, 70, "1 porsi"),
        LocalFood("kangkung", "Kangkung Tumis", FoodCategory.SAYUR, 100, "1 porsi"),
        LocalFood("sambal_goreng", "Sambal Goreng", FoodCategory.SAYUR, 130, "1 porsi"),
        LocalFood("urap", "Urap Sayur", FoodCategory.SAYUR, 120, "1 porsi"),
        LocalFood("gado_gado", "Gado-Gado", FoodCategory.SAYUR, 280, "1 porsi"),
        LocalFood("pecel", "Pecel", FoodCategory.SAYUR, 250, "1 porsi"),
        LocalFood("lalapan", "Lalapan", FoodCategory.SAYUR, 30, "1 porsi"),

        // Gorengan
        LocalFood("bakwan", "Bakwan", FoodCategory.GORENGAN, 110, "1 buah"),
        LocalFood("pisang_goreng", "Pisang Goreng", FoodCategory.GORENGAN, 140, "1 buah"),
        LocalFood("tahu_isi", "Tahu Isi", FoodCategory.GORENGAN, 120, "1 buah"),
        LocalFood("cireng", "Cireng", FoodCategory.GORENGAN, 130, "2 buah"),
        LocalFood("risoles", "Risoles", FoodCategory.GORENGAN, 150, "1 buah"),
        LocalFood("kerupuk", "Kerupuk", FoodCategory.GORENGAN, 70, "3 buah"),

        // Minuman
        LocalFood("es_teh_manis", "Es Teh Manis", FoodCategory.MINUMAN, 90, "1 gelas", listOf("teh manis", "es teh")),
        LocalFood("teh_tawar", "Teh Tawar", FoodCategory.MINUMAN, 5, "1 gelas"),
        LocalFood("kopi_susu", "Kopi Susu", FoodCategory.MINUMAN, 150, "1 gelas", listOf("kopi")),
        LocalFood("es_jeruk", "Es Jeruk", FoodCategory.MINUMAN, 100, "1 gelas", listOf("jeruk")),
        LocalFood("es_kelapa", "Es Kelapa Muda", FoodCategory.MINUMAN, 110, "1 gelas", listOf("kelapa")),
        LocalFood("es_cendol", "Es Cendol", FoodCategory.JAJANAN, 230, "1 gelas", listOf("cendol")),
        LocalFood("es_doger", "Es Doger", FoodCategory.JAJANAN, 260, "1 gelas"),
        LocalFood("susu_kental", "Susu Kental Manis", FoodCategory.MINUMAN, 130, "2 sdm"),
        LocalFood("air_mineral", "Air Mineral", FoodCategory.MINUMAN, 0, "1 gelas"),
        LocalFood("jus_alpukat", "Jus Alpukat", FoodCategory.MINUMAN, 230, "1 gelas", listOf("alpukat")),
        LocalFood("sirup", "Es Sirup", FoodCategory.MINUMAN, 120, "1 gelas"),

        // Jajanan & manis
        LocalFood("pisang_ijo", "Pisang Ijo", FoodCategory.JAJANAN, 250, "1 porsi"),
        LocalFood("klepon", "Klepon", FoodCategory.JAJANAN, 80, "1 buah"),
        LocalFood("onde_onde", "Onde-Onde", FoodCategory.JAJANAN, 110, "1 buah"),
        LocalFood("martabak_manis", "Martabak Manis", FoodCategory.JAJANAN, 380, "1 potong", listOf("martabak")),
        LocalFood("martabak_telur", "Martabak Telur", FoodCategory.JAJANAN, 320, "1 potong"),
        LocalFood("kue_lapis", "Kue Lapis", FoodCategory.JAJANAN, 120, "1 potong"),
        LocalFood("lapis_legit", "Lapis Legit", FoodCategory.JAJANAN, 180, "1 potong"),
        LocalFood("nagasari", "Nagasari", FoodCategory.JAJANAN, 130, "1 buah"),
        LocalFood("serabi", "Serabi", FoodCategory.JAJANAN, 150, "1 buah"),
        LocalFood("kue_putu", "Kue Putu", FoodCategory.JAJANAN, 100, "1 buah"),
        LocalFood("dodol", "Dodol", FoodCategory.JAJANAN, 120, "1 potong"),
        LocalFood("kue_pancong", "Kue Pancong", FoodCategory.JAJANAN, 180, "1 buah"),
        LocalFood("roti_bakar", "Roti Bakar", FoodCategory.JAJANAN, 220, "1 porsi"),
        LocalFood("bika_ambon", "Bika Ambon", FoodCategory.JAJANAN, 160, "1 potong"),
        LocalFood("es_krim", "Es Krim", FoodCategory.JAJANAN, 200, "1 scoop"),

        // Buah
        LocalFood("pisang", "Pisang", FoodCategory.BUAH, 105, "1 buah"),
        LocalFood("pepaya", "Pepaya", FoodCategory.BUAH, 60, "1 potong"),
        LocalFood("mangga", "Mangga", FoodCategory.BUAH, 100, "1 buah sedang"),
        LocalFood("semangka", "Semangka", FoodCategory.BUAH, 80, "1 potong"),
        LocalFood("jeruk_buah", "Jeruk", FoodCategory.BUAH, 60, "1 buah"),
        LocalFood("apel", "Apel", FoodCategory.BUAH, 95, "1 buah"),
        LocalFood("salak", "Salak", FoodCategory.BUAH, 80, "1 buah"),
        LocalFood("rambutan", "Rambutan", FoodCategory.BUAH, 60, "5 buah"),
        LocalFood("durian", "Durian", FoodCategory.BUAH, 150, "1 biji sedang"),
        LocalFood("mangga_muda", "Mangga Muda", FoodCategory.BUAH, 70, "1 potong"),

        // Umum / bukan khas daerah
        LocalFood("roti_tawar", "Roti Tawar", FoodCategory.LAINNYA, 140, "2 lembar"),
        LocalFood("mie_instan", "Mie Instan", FoodCategory.LAINNYA, 380, "1 bungkus"),
        LocalFood("sereal", "Sereal", FoodCategory.LAINNYA, 150, "1 mangkuk"),
        LocalFood("telur_ceplok", "Telur Ceplok", FoodCategory.LAINNYA, 110, "1 butir"),
    )

    private val byId: Map<String, LocalFood> = foods.associateBy { it.id }

    private val searchIndex: List<Pair<String, LocalFood>> =
        foods.flatMap { food ->
            (listOf(food.name) + food.aliases).map { it.lowercase() to food }
        }.sortedByDescending { it.first.length }

    fun byId(id: String): LocalFood? = byId[id]

    /** Case-insensitive substring match against names and aliases. */
    fun search(query: String): List<LocalFood> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val direct = searchIndex.filter { q.contains(it.first) || it.first.contains(q) }.map { it.second }
        return direct.distinctBy { it.id }
    }

    /** The best single catalog match for a food phrase, if any. */
    fun bestMatch(query: String): LocalFood? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null

        // 1. Exact name or alias.
        foods.firstOrNull { it.name.lowercase() == q }?.let { return it }
        foods.firstOrNull { food -> food.aliases.any { it.lowercase() == q } }?.let { return it }

        // 2. Name or alias that contains the query, preferring the most specific.
        val containing = foods.filter { food ->
            food.name.lowercase().contains(q) || food.aliases.any { it.lowercase().contains(q) }
        }
        if (containing.isNotEmpty()) return containing.maxByOrNull { it.name.length }

        // 3. Query that contains a name or alias, preferring the longest token.
        return searchIndex
            .filter { q.contains(it.first) }
            .maxByOrNull { it.first.length }
            ?.second
    }
}
