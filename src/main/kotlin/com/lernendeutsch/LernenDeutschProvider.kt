// Dosyanın en üstüne bu annotation ŞART:
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.lang.Exception

class LernenDeutschProvider : MainAPI() {
    override var mainUrl = "https://raw.githubusercontent.com/kullanici/repo/main"
    override var name = "LernenDeutsch"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "tr"
    override val hasMainPage = true

    // ✅ FİX 1: mainPage property tanımlandı (newHomePageResponse için zorunlu)
    override val mainPage = mainPageOf(
        "Filmler" to "Filmler"
    )

    private var cachedSettings: SettingsSchema? = null

    // JSON'dan gelen "boş" placeholder değerleri — bunlar gerçek veri sayılmaz
    private val EMPTY_PLACEHOLDERS = setOf(
        "Kategori Belirtilmedi",
        "Oyuncu Belirtilmedi"
    )

    // ── 1. ADIM: SETTINGS.JSON BOOTSTRAP MOTORU ──
    private suspend fun getSettings(): SettingsSchema {
        if (cachedSettings != null) return cachedSettings!!

        val settingsUrl = "$mainUrl/data/settings.json"
        return try {
            val response = app.get(settingsUrl).text
            val parsed = parseJson<SettingsSchema>(response)
            cachedSettings = parsed
            parsed
        } catch (e: Exception) {
            // ✅ FİX 2: SettingsSchema() çağrısı artık çalışır (schema_version = null default'u var)
            SettingsSchema()
        }
    }

    // ── 2. ADIM: AKILLI YEDEKLİ JSON ÇEKME MEKANİZMASI (FALLBACK) ──
    private suspend fun fetchJsonWithFallback(urls: List<String>): String? {
        for (url in urls) {
            try {
                val res = app.get(url)
                if (res.code == 200 && res.text.isNotBlank()) {
                    // ✅ FİX 3: schema_version yerine gerçek JSON validity kontrolü
                    // movies.json ve 3.json'da schema_version yok, önceki kod hep null dönüyordu!
                    val trimmed = res.text.trim()
                    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                        return res.text
                    }
                }
            } catch (e: Exception) {
                println("Kaynak erişim hatası: $url -> ${e.message}")
            }
        }
        return null
    }

    // ── 3. ADIM: ANA SAYFA YÜKLEME ──
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val settings = getSettings()

        if (settings.triggers.maintenance_mode == true) {
            throw Exception("Sistem şu anda bakımda. Lütfen daha sonra tekrar deneyiniz.")
        }

        val moviesJsonRaw = fetchJsonWithFallback(settings.endpoints.movies_sources)
            ?: throw Exception("Ana sunuculara ulaşılamadı. Felaket senaryosu devrede!")

        // ✅ FİX 4: MoviesSchema artık tanımlı (aşağıda data class eklendi)
        val data = parseJson<MoviesSchema>(moviesJsonRaw)

        val homeItems = data.movies.map { movie ->
            if (settings.triggers.privacy_mode == true && movie.id.contains("secret")) {
                null
            } else {
                MovieSearchResponse(
                    name  = movie.title,
                    url   = movie.id,
                    apiName   = this.name,
                    type  = TvType.Movie,
                    posterUrl = movie.poster,
                    year  = movie.year
                )
            }
        }.filterNotNull()

        // ✅ FİX 1 (devamı): mainPage property tanımlı olduğu için artık derlenir
        return newHomePageResponse(mainPage, homeItems)
    }

    // ── 4. ADIM: FİLM DETAY SAYFASI ──
    override suspend fun load(url: String): LoadResponse? {
        val settings = getSettings()
        val movieId = url

        // ✅ FİX 5: movie_details_3.json → 3.json (gerçek dosya adına göre)
        val detailUrls = settings.endpoints.details_base_urls.map { baseUrl ->
            "${baseUrl}${movieId}.json"
        }

        val detailJsonRaw = fetchJsonWithFallback(detailUrls)
            ?: throw Exception("Film detay bilgisi yüklenemedi. ID: $movieId")

        val detail = parseJson<MovieDetailSchema>(detailJsonRaw)

        // ✅ FİX 6: hide_descriptions String modunu doğru işle ("all" / "by_movie" / "none")
        val finalDescription = when (settings.triggers.hide_descriptions) {
            "all"      -> ""
            "by_movie" -> if (detail.hide_description == true) "" else detail.description ?: ""
            else       -> detail.description ?: "" // "none" veya null
        }

        // Poster: film posterı yoksa settings'teki default_poster kullan
        val finalPoster = detail.poster?.takeIf { it.isNotBlank() }
            ?: settings.defaults.default_poster

        return newMovieLoadResponse(detail.title, url, TvType.Movie, url) {
            this.posterUrl  = finalPoster
            this.year       = detail.year
            this.plot       = finalDescription
            this.tags       = detail.categories.filter { it !in EMPTY_PLACEHOLDERS }
            this.actors     = detail.actors
                .filter { it !in EMPTY_PLACEHOLDERS }
                .map { ActorData(Actor(it)) }
        }
    }

// ── 5. ADIM: MULTI-STREAM OYNATICI (RESOLVER) VE EXTRACTOR ──
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val settings = getSettings()

        val detailUrls = settings.endpoints.details_base_urls.map { baseUrl ->
            "${baseUrl}${data}.json"
        }

        val detailJsonRaw = fetchJsonWithFallback(detailUrls) ?: return false
        val detail = parseJson<MovieDetailSchema>(detailJsonRaw)

        if (detail.sources.isEmpty()) return false

        detail.sources.forEach { src ->
            // 1. URL'yi Türkiye engellerine karşı filtreden geçir
            val fixedUrl = bypassTurkeyBlocks(src.url)
            val type = src.type.lowercase()

            if (type == "mp4" || type == "m3u8") {
                // Doğrudan video linkleri (Sunucu bazlı)
                callback.invoke(
                    ExtractorLink(
                        source  = this.name,
                        name    = "${src.name} ${src.quality ?: ""}".trim(),
                        url     = fixedUrl,
                        referer = "",
                        quality = parseQuality(src.quality),
                        isM3u8  = type == "m3u8"
                    )
                )
            } else {
                // 2. Telegram, Vidoza, Pixeldrain, Embed gibi platformlar için 
                // Cloudstream'in dahili Extractor motorunu kullan.
                // loadExtractor, linki tanır ve otomatik olarak arka planda videoyu çözer.
                loadExtractor(fixedUrl, subtitleCallback, callback)
            }
        }
        return true
    }

    // ── TÜRKİYE İÇİN DNS/DOMAIN ENGEL AŞICI (URL MODİFİYE) ──
    private fun bypassTurkeyBlocks(url: String): String {
        var safeUrl = url
        
        // Telegram (t.me) Türkiye'de sık sık ISP engeline takılır, telegram.me daha güvenlidir.
        if (safeUrl.contains("t.me/")) {
            safeUrl = safeUrl.replace("t.me/", "telegram.me/")
        }
        
        // Vidoza için olası domain engellerine karşı alternatif ayna (mirror) kullanımı
        // (Eğer vidoza.net engellenirse buraya güncel aynayı yazabilirsin)
        if (safeUrl.contains("vidoza.net")) {
            safeUrl = safeUrl.replace("vidoza.net", "videolink.net") // Örnek aktif ayna
        }

        // Voes, Filemoon vb. platformlar için engellendikçe buraya yeni replace kuralları ekleyebilirsin.
        
        return safeUrl
    }

    // ── KALİTE DEĞERİNİ CLOUDSTREAM FORMATINA ÇEVİRME ──
    private fun parseQuality(qualityString: String?): Int {
        return when (qualityString?.lowercase()?.replace("p", "")) {
            "360" -> Qualities.P360.value
            "480" -> Qualities.P480.value
            "720" -> Qualities.P720.value
            "1080" -> Qualities.P1080.value
            "1440", "2k" -> Qualities.P1440.value
            "2160", "4k" -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}

// ─────────────────────────────────────────────
// DATA CLASS TANIMLARI
// ─────────────────────────────────────────────

// ── settings.json için ──
data class Triggers(
    val maintenance_mode: Boolean? = false,
    val hide_descriptions: String? = "none", // "all" | "by_movie" | "none"
    val privacy_mode: Boolean? = false,
    val show_recently_added: Boolean? = true,
    val show_recommended: Boolean? = true
)

data class Defaults(
    val default_poster: String? = null  // ✅ Nullable için default null eklendi
)

data class Endpoints(
    val movies_sources: List<String>      = emptyList(),
    val actors_sources: List<String>      = emptyList(),
    val categories_sources: List<String>  = emptyList(),
    val details_base_urls: List<String>   = emptyList()
)

data class SettingsSchema(
    // ✅ FİX 2: = null default'u eklendi, artık SettingsSchema() çağrısı derlenir
    val schema_version: String?           = null,
    val triggers: Triggers                = Triggers(),
    val defaults: Defaults                = Defaults(),
    val endpoints: Endpoints              = Endpoints()
)

// ── Rozetler için ──
data class Badges(
    val featured: Boolean?    = false,
    val recommended: Boolean? = false
)

// ── movies.json için liste öğesi ──
// ✅ FİX 4: MoviesSchema ve MovieListItem eklendi (tamamen eksikti!)
data class MovieListItem(
    val id: String,
    val title: String,
    val year: Int?            = null,
    val poster: String?       = null,
    val badges: Badges?       = null,
    val categories: List<String> = emptyList()
)

data class MoviesSchema(
    val app_id: String?          = null,  // PHP'den int gelebilir, String? güvenli
    val last_updated: String?    = null,
    val total_movies: Int?       = null,
    val movies: List<MovieListItem> = emptyList()
)

// ── 3.json (detay) için ──
data class MovieDetailSchema(
    val id: String,
    val title: String,
    val year: Int?                      = null,
    val description: String?            = null,
    val hide_description: Boolean?      = false,
    val poster: String?                 = null,
    val badges: Badges?                 = null,
    val categories: List<String>        = emptyList(),
    val actors: List<String>            = emptyList(),
    val sources: List<StreamSource>     = emptyList(),
    val last_updated: String?           = null
)

data class StreamSource(
    val name: String,
    val type: String,
    val quality: String? = null,
    val url: String
)

// ── categories.json ve actors.json için ──
data class CategoriesResponse(
    val last_updated: String?           = null,
    val categories: List<CategorySchema> = emptyList()
)

data class ActorsResponse(
    val last_updated: String?           = null,
    val actors: List<ActorSchema>       = emptyList()
)

data class CategorySchema(
    val id: Int,
    val name: String,
    val movie_count: Int = 0
)

data class ActorSchema(
    val id: Int,
    val name: String,
    val movie_count: Int = 0
)
