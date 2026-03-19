package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.M3u8Helper

class DoramaLandProvider : MainAPI() {

    override var mainUrl = "https://dorama.land"
    override var name = "DoramaLand"
    override var lang = "uk"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val hasMainPage = true
    override val mainPage = mainPageOf(
        "$mainUrl/all-dramas" to "Усі дорами"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/all-dramas?page=$page").document

        val home = doc.select("div.catalog-item.catalog-item_type_poster").map { el ->
            val title = el.selectFirst("div.catalog-item__title")?.text() ?: "No title"
            val href = fixUrl(el.selectFirst("a")?.attr("href") ?: "")
            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("=== SEARCH START === $query")

        val doc = app.get("$mainUrl/search?q=$query").document
        val elements = doc.select(".search-item")

        println("FOUND ITEMS: ${elements.size}")

        return elements.mapNotNull { element ->

            val title = element.selectFirst(".search-item__title")?.text()?.trim()
            val hrefRaw = element.selectFirst("a.search-item-wrap")?.attr("href")

            if (title.isNullOrEmpty() || hrefRaw.isNullOrEmpty()) {
                println("SKIP: no title or href")
                return@mapNotNull null
            }

            val href = fixUrl(hrefRaw)

        // 🔥 беремо src
            val img = element.selectFirst("div.search-image-wrap img")

            val src = img?.attr("src")?.trim()
            val srcset = img?.attr("srcset")?.trim()
            val dataSrc = img?.attr("data-src")?.trim()
            val dataSrcSet = img?.attr("data-srcset")?.trim()

            println("SRC: $src")
            println("SRCSET: $srcset")
            println("DATA-SRC: $dataSrc")
            println("DATA-SRCSET: $dataSrcSet")

            val posterRaw = when {
                !dataSrc.isNullOrEmpty() -> dataSrc                     // 🔥 ГОЛОВНЕ
                !src.isNullOrEmpty() -> src
                !dataSrcSet.isNullOrEmpty() -> dataSrcSet.split(" ").firstOrNull()
                !srcset.isNullOrEmpty() -> srcset.split(" ").firstOrNull()
                else -> null
            }

            val poster = posterRaw?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }

            println("FINAL POSTER: $poster")

            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        showToast("LOAD: start")

        val res = app.get(url)
        println("FINAL URL: ${res.url}")
        val doc = res.document

        showToast("LOAD: page loaded")

        val title = doc.selectFirst("h1")?.text() ?: "No title"
        println("TITLE: $title")

        val poster = fixUrlNull(doc.selectFirst(".about-serial-poster img")?.attr("src"))

        val description = doc.selectFirst(
            ".serial-description-text .spoiler__content[itemprop=description]"
        )?.text()

        // 🎭 ЖАНРИ
        val genresRaw = doc.selectFirst(".serial-genres-links [itemprop=genre]")?.text()
        println("GENRES RAW: $genresRaw")

        val genres = genresRaw
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }

        println("GENRES LIST: $genres")

        // 🔗 RELATED
        val related = doc.select(".related-serials .catalog-item").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst(".catalog-item__title")?.text() ?: return@mapNotNull null
            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(title, fixUrl(href), TvType.AsianDrama) {
                this.posterUrl = poster
            }
         }

        println("RELATED SIZE: ${related.size}")

// ⭐ RECOMMENDATIONS (similar)
        val recommendations = doc.select(".similar-serials .catalog-item").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst(".catalog-item__title")?.text() ?: return@mapNotNull null
            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(title, fixUrl(href), TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }

        println("RECOMMENDATIONS SIZE: ${recommendations.size}")
        
        val episodeElements = doc.select(".short-cinematic")
        println("EP ELEMENTS SIZE: ${episodeElements.size}")

        val episodes = episodeElements.mapNotNull { ep ->
            val hrefRaw = ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null

            val cleanHref = hrefRaw
                .replace("\\s+".toRegex(), "")   // 💥 КЛЮЧОВИЙ ФІКС
                .trim()

            val fixed = fixUrl(cleanHref)

            println("FIXED CLEAN: $fixed")



            val name = ep.selectFirst(".short-cinematic__episode-number")?.text()

            val episode = Regex("(\\d+)")
                .find(name ?: "")
                ?.groupValues?.getOrNull(1)
                ?.toIntOrNull()

            newEpisode(fixed) {
                this.name = name
                this.episode = episode

                this.data = fixed
            }
        }

        showToast("LOAD: episodes = ${episodes.size}")

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.recommendations = related + recommendations
        }
    }


    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        println("LOADLINKS DATA: $data")

        val document = app.get(data).document

    // 🔥 беремо всі озвучки
        val players = document.select("[data-url-player]")

        if (players.isEmpty()) {
            println("NO PLAYERS FOUND")
            return false
        }

        players.forEach { player ->

            val voiceName = player.attr("data-label").ifEmpty { "Unknown" }
            var iframeUrl = player.attr("data-url-player")

            if (iframeUrl.isNullOrEmpty()) return@forEach

        // 👉 додаємо https
            if (iframeUrl.startsWith("//")) {
                iframeUrl = "https:$iframeUrl"
            }

            println("VOICE: $voiceName")
            println("IFRAME: $iframeUrl")

            try {
                val iframeHtml = app.get(
                    iframeUrl,
                    headers = mapOf(
                        "Referer" to "https://dorama.land/",
                        "User-Agent" to USER_AGENT
                    )
                ).text

            // 🔍 шукаємо m3u8
                val m3u8 = Regex("""https:\\/\\/[^"]+\.m3u8""")
                    .find(iframeHtml)
                    ?.value
                    ?.replace("\\/", "/")

                if (m3u8.isNullOrEmpty()) {
                    println("NO M3U8 FOR $voiceName")
                    return@forEach
                }

                println("FOUND: $m3u8")

                M3u8Helper.generateM3u8(
                    source = "DoramaLand ($voiceName)",
                    streamUrl = m3u8,
                    referer = "https://dorama.land/"
                ).forEach(callback)

            } catch (e: Exception) {
                println("ERROR: ${e.message}")
            }
        }

        return true
    }
}
