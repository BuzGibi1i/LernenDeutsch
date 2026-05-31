package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.lang.Exception

// ✅ FİX 1: @CloudstreamPlugin olmadan plugin uygulama tarafından tanınmaz
@CloudstreamPlugin
class LernenDeutschProvider : MainAPI() {

    // ⚠️ GitHub raw base URL — kendi repo adresinle değiştir
    override var mainUrl = "https://raw.githubusercontent.com/BuzGibi1i/LernenDeutsch/master"
    override var name    = "LernenDeutschProvider"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang    = "tr"
    override val hasMainPage = true

    // ✅ FİX 2: mainPage property — newHomePageResponse için zorunlu
    override val mainPage = mainPageOf(
        "Filmler" to "Filmler"
    )

    private var cachedSettings: SettingsSchema? = null

    // JSON'dan gelen boş placeholder değerleri
    private val EMPTY_PLACEHOLDERS = setOf(
        "Kategori Belirtilmedi",
        "Oyuncu Belirtilmedi"
    )

    // ══════════════════════════════════════════════
    // 1. ADIM: settings.json BOOTSTRAP
    // Her şey buradan başlar. Diğer tüm URL'ler buradan gelir.
    // ⚠️ GitHub'a "settings.json" adıyla yükle (settingsv2.json değil!)
    // ══════════════════════════════════════════════
    private suspend fun getSettings(): SettingsSchema {
        if (cachedSettings != null) return cachedSettings!!

        val settingsUrl = "$mainUrl/data/settings.json"
        return try {
            val response = app.get(settingsUrl).text
            val parsed   = parseJson<SettingsSchema>(response)
            cachedSettings = parsed
            parsed
        } catch (e: Exception) {
            SettingsSchema() // Fallback — tüm default değerler devreye girer
        }
    }

    // ══════════════════════════════════════════════
    // 2. ADIM: YEDEKLİ JSON ÇEKME
    // settings.json'daki URL listesini sırayla dener, ilk çalışanı döner
    // ══════════════════════════════════════════════
    private suspend fun fetchJsonWithFallback(urls: List<String>): String? {
        for (url in urls) {
            try {
                val res = app.get(url)
                if (res.code == 200 && res.text.isNotBlank()) {
                    val trimmed = res.text.trim()
                    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                        return res.text
                    }
                }
            } catch (e: Exception) {
                println("Kaynak erişim hatası: $url → ${e.message}")
            }
        }
        return null
    }

    // ══════════════════════════════════════════════
    // 3. ADIM: ANA SAYFA
    // settings → movies_sources → movies.json
    // ══════════════════════════════════════════════
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val settings = getSettings()

        if (settings.triggers.maintenance_mode == true) {
            throw Exception("Sistem şu anda bakımda. Lütfen daha sonra tekrar deneyiniz.")
        }

        val moviesJsonRaw = fetchJsonWithFallback(settings.endpoints.movies_sources)
            ?: throw Exception("movies.json alınamadı.")

        val data = parseJson<MoviesSchema>(moviesJsonRaw)

        // ✅ FİX 3: newMovieSearchResponse builder kullanıldı
        val homeItems = data.movies.mapNotNull { movie ->
            if (settings.triggers.privacy_mode == true && movie.id.contains("secret")) {
                null
            } else {
                newMovieSearchResponse(movie.title, movie.id, TvType.Movie) {
                    this.posterUrl = movie.poster
                    this.year      = movie.year
                }
            }
        }

        // ✅ FİX 4: request.name kullanıldı (mainPage List<MainPageData> String değil)
        return newHomePageResponse(request.name, homeItems)
    }

    // ══════════════════════════════════════════════
    // 4. ADIM: FİLM DETAY SAYFASI
    // settings → details_base_urls → {id}.json
    // Örn: ".../details/" + "3" + ".json" = ".../details/3.json"
    // ══════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val settings = getSettings()
        val movieId  = url

        val detailUrls = settings.endpoints.details_base_urls.map { baseUrl ->
            "${baseUrl}${movieId}.json"
        }

        val detailJsonRaw = fetchJsonWithFallback(detailUrls)
            ?: throw Exception("Detay JSON yüklenemedi. Film ID: $movieId")

        val detail = parseJson<MovieDetailSchema>(detailJsonRaw)

        // hide_descriptions = "by_movie" → film bazlı (3.json'da hide_description: true)
        val finalDescription = when (settings.triggers.hide_descriptions) {
            "all"      -> ""
            "by_movie" -> if (detail.hide_description == true) "" else detail.description ?: ""
            else       -> detail.description ?: ""
        }

        val finalPoster = detail.poster?.takeIf { it.isNotBlank() }
            ?: settings.defaults.default_poster

        return newMovieLoadResponse(detail.title, url, TvType.Movie, url) {
            this.posterUrl = finalPoster
            this.year      = detail.year
            this.plot      = finalDescription
            this.tags      = detail.categories.filter { it !in EMPTY_PLACEHOLDERS }
            this.actors    = detail.actors
                .filter { it !in EMPTY_PLACEHOLDERS }
                .map { ActorData(Actor(it)) }
        }
    }

    // ══════════════════════════════════════════════
    // 5. ADIM: VIDEO KAYNAKLARI
    // 3.json → sources[] işlenir
    // type="telegram" → loadExtractor (otomatik çözümlenir)
    // type="mp4/m3u8" → doğrudan callback
    // ══════════════════════════════════════════════
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
        val detail        = parseJson<MovieDetailSchema>(detailJsonRaw)

        if (detail.sources.isEmpty()) return false

        // ✅ FİX 5: forEach → for döngüsü
        // loadExtractor suspend fonksiyon, forEach lambda suspend değil → hata
		for (src in detail.sources) {
            val fixedUrl = bypassTurkeyBlocks(src.url)
            val type     = src.type.lowercase()

            when (type) {
                "mp4", "m3u8" -> {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${src.name} ${src.quality ?: ""}".trim(),
                            url = fixedUrl,
                            type = if (type == "m3u8") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = ""
                            this.quality = parseQuality(src.quality)
                        }
                    )
                }
                else -> loadExtractor(fixedUrl, subtitleCallback, callback)
            }
        }
        return true
    }

    // ── Türkiye engel aşıcı ──
    private fun bypassTurkeyBlocks(url: String): String {
        var u = url
        if (u.contains("t.me/"))      u = u.replace("t.me/", "telegram.me/")
        if (u.contains("vidoza.net")) u = u.replace("vidoza.net", "videolink.net")
        return u
    }

    // ── Kalite → CloudStream formatı ──
    private fun parseQuality(q: String?): Int {
        return when (q?.lowercase()?.replace("p", "")) {
            "360"        -> getQualityFromName("360")
            "480"        -> getQualityFromName("480")
            "720"        -> getQualityFromName("720")
            "1080"       -> getQualityFromName("1080")
            "1440", "2k" -> getQualityFromName("1440")
            "2160", "4k" -> getQualityFromName("2160")
            else         -> getQualityFromName("")
        }
    }
}

// ══════════════════════════════════════════════
// DATA CLASS TANIMLARI — JSON yapılarıyla birebir
// ══════════════════════════════════════════════

// settings.json → triggers
data class Triggers(
    val maintenance_mode:    Boolean? = false,
    val hide_descriptions:   String?  = "none",
    val privacy_mode:        Boolean? = false,
    val show_recently_added: Boolean? = true,
    val show_recommended:    Boolean? = true
)

// settings.json → defaults
data class Defaults(val default_poster: String? = null)

// settings.json → endpoints
data class Endpoints(
    val movies_sources:     List<String> = emptyList(),
    val actors_sources:     List<String> = emptyList(),
    val categories_sources: List<String> = emptyList(),
    val details_base_urls:  List<String> = emptyList()
)

// settings.json (kök)
data class SettingsSchema(
    val schema_version: String?   = null,
    val triggers:       Triggers  = Triggers(),
    val defaults:       Defaults  = Defaults(),
    val endpoints:      Endpoints = Endpoints()
)

// movies.json + 3.json → badges
data class Badges(val featured: Boolean? = false, val recommended: Boolean? = false)

// movies.json → movies[] öğesi
data class MovieListItem(
    val id:         String,
    val title:      String,
    val year:       Int?         = null,
    val poster:     String?      = null,
    val badges:     Badges?      = null,
    val categories: List<String> = emptyList()
)

// movies.json (kök)
// ✅ FİX 6: app_id JSON'da integer (2) → String? crash eder, Int? olmalı
data class MoviesSchema(
    val app_id:       Int?                = null,
    val last_updated: String?             = null,
    val total_movies: Int?                = null,
    val movies:       List<MovieListItem> = emptyList()
)

// 3.json (kök)
data class MovieDetailSchema(
    val id:               String,
    val title:            String,
    val year:             Int?              = null,
    val description:      String?           = null,
    val hide_description: Boolean?          = false,
    val poster:           String?           = null,
    val badges:           Badges?           = null,
    val categories:       List<String>      = emptyList(),
    val actors:           List<String>      = emptyList(),
    val sources:          List<StreamSource> = emptyList(),
    val last_updated:     String?           = null
)

// 3.json → sources[]
data class StreamSource(
    val name:    String,
    val type:    String,
    val quality: String? = null,
    val url:     String
)

// actors.json
data class ActorsResponse(
    val last_updated: String?           = null,
    val actors:       List<ActorSchema>  = emptyList()
)
data class ActorSchema(val id: Int, val name: String, val movie_count: Int = 0)

// categories.json
data class CategoriesResponse(
    val last_updated: String?               = null,
    val categories:   List<CategorySchema>  = emptyList()
)
data class CategorySchema(val id: Int, val name: String, val movie_count: Int = 0)
