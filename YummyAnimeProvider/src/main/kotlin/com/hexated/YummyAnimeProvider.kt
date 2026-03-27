package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive


class YummyAnimeProvider : MainAPI() {

    override var name = "YummyAnime"
    override var mainUrl = "https://site.yummyani.me"
    override var lang = "ru"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // =========================
    // 🔥 Головна сторінка
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/catalog?page=$page"
        val doc = app.get(url).document

        val list = doc.select("div.anime-column").mapNotNull { element ->
            val link = element.selectFirst("a.image-block")?.attr("href") ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src") ?: ""
            val title = element.selectFirst(".anime-title")?.text() ?: return@mapNotNull null

            newAnimeSearchResponse(
                title,
                mainUrl + link,
                TvType.Anime
            ) {
                posterUrl = fixUrl(poster)
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Усі аніме", list)),
            list.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://api.yani.tv/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&offset=0&limit=20"

        val headers = mapOf(
            "X-Application" to "i0zejgswfnwup27a",
            "Accept" to "application/json",
            "Lang" to "ru"
        )

    // Получаем строку ответа
        val responseBody = app.get(url, headers)

    // Парсим JSON через Kotlin Serialization
        val json = Json.parseToJsonElement(responseBody).jsonObject

        val responseArray = json["response"]?.jsonArray ?: return emptyList()

        return responseArray.mapNotNull { anime ->
            val obj = anime.jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val animeUrl = obj["anime_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val poster = obj["poster"]?.jsonObject?.get("fullsize")?.jsonPrimitive?.content ?: ""
            val description = obj["description"]?.jsonPrimitive?.content ?: ""

            newAnimeSearchResponse(
                title,
                "https://site.yummyani.me/catalog/item/$animeUrl",
                TvType.Anime
            ) {
                this.posterUrl = poster
                this.description = description
            }
        }
    }

    // =========================
    // 📄 Деталі + епізоди
    // =========================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

    // Назва
        val title = doc.selectFirst("div.titles > h1")?.text() ?: "No title"

    // Обкладинка
        val poster = doc.selectFirst("div.poster-block img")?.attr("src") ?: ""

    // Опис
        val plot = doc.selectFirst("p[itemprop=description]")?.text()

    // Серії (на даний момент можна додати iframe як приклад)
        val episodes = mutableListOf<Episode>()
        val iframe = doc.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrBlank()) {
            episodes.add(
                newEpisode(iframe) {
                    name = "Episode 1"
                    episode = 1
                }
            )
        }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            posterUrl = fixUrl(poster)
            this.plot = plot
            this.episodes = mutableMapOf(
            DubStatus.Subbed to episodes
            )
        }
    }

    // =========================
    // 🎥 Відео
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }
}
