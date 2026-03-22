package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.M3u8Helper

class YummyAnimeProvider : MainAPI() {

    override var name = "YummyAnime"
    override var mainUrl = "https://yummyanime.tv"
    override var lang = "uk"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    private val pageSize = 20

    // 🔐 встав свої токени (якщо потрібні)
    private val publicToken = ""
    private val privateToken = ""

    // =========================
    // 🌐 Headers
    // =========================
    override fun getRequestCreator(): RequestCreator {
        return {
            addHeader("Accept", "application/json, */*")
            addHeader("Lang", "ru")

            if (publicToken.isNotBlank()) {
                addHeader("X-Application", publicToken)
            }

            if (privateToken.isNotBlank()) {
                addHeader("Authorization", "Yummy $privateToken")
            }
        }
    }

    // =========================
    // 🔥 Головна сторінка
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = "$mainUrl/anime?limit=$pageSize&offset=${(page - 1) * pageSize}"

        val json = app.get(url).parsedSafe<JSONObject>()
        val array = json?.optJSONArray("response") ?: return HomePageResponse(emptyList())

        val list = array.mapNotNull {
            val obj = it as? JSONObject ?: return@mapNotNull null

            newAnimeSearchResponse(
                obj.optString("title"),
                "$mainUrl/anime/${obj.optString("anime_url")}",
                TvType.Anime
            ) {
                posterUrl = obj.optJSONObject("poster")?.optString("original")
            }
        }

        return HomePageResponse(
            listOf(HomePageList("Популярное", list)),
            hasNext = list.size >= pageSize
        )
    }

    // =========================
    // 🔎 Пошук
    // =========================
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val url = "$mainUrl/anime?q=${query}"

        val json = app.get(url).parsedSafe<JSONObject>()
        val array = json?.optJSONArray("response") ?: return emptyList()

        return array.mapNotNull {
            val obj = it as? JSONObject ?: return@mapNotNull null

            newAnimeSearchResponse(
                obj.optString("title"),
                "$mainUrl/anime/${obj.optString("anime_url")}",
                TvType.Anime
            ) {
                posterUrl = obj.optJSONObject("poster")?.optString("original")
            }
        }
    }

    // =========================
    // 📄 Деталі + епізоди
    // =========================
    override suspend fun load(url: String): LoadResponse {
        val json = app.get("$url?need_videos=true").parsedSafe<JSONObject>()
        val obj = json?.optJSONObject("response")
            ?: throw Exception("No response")

        val title = obj.optString("title")
        val description = obj.optString("description")
        val poster = obj.optJSONObject("poster")?.optString("original")

        val videos = obj.optJSONArray("videos") ?: throw Exception("No videos")

        val episodesMap = mutableMapOf<String, MutableList<String>>()

        videos.forEach {
            val v = it as JSONObject
            val number = v.optString("number")
            val iframe = v.optString("iframe")

            if (number.isNotBlank() && iframe.isNotBlank()) {
                episodesMap.getOrPut(number) { mutableListOf() }.add(iframe)
            }
        }

        val episodes = episodesMap.map { (num, iframes) ->
            Episode(
                data = iframes.first(), // беремо перший (далі extractor розрулить)
                name = "Серия $num",
                episode = num.toIntOrNull()
            )
        }.sortedByDescending { it.episode }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            plot = description
            posterUrl = poster
            this.episodes = episodes
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

        // YummyAnime використовує Kodik → Cloudstream вже вміє його
        loadExtractor(
            data,
            mainUrl,
            subtitleCallback,
            callback
        )

        return true
    }
}
