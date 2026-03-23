package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class YummyAnimeProvider : MainAPI() {

    override var name = "YummyAnime"
    override var mainUrl = "https://yummyanime.tv"
    override var lang = "uk"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    private val pageSize = 20

    // 🔥 headers (щоб API не давав пусто)
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0",
        "Referer" to "https://yummyanime.tv/"
    )

    // =========================
    // 🔥 Main page
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = "$mainUrl/anime?limit=$pageSize&offset=${(page - 1) * pageSize}"

        val json = app.get(url, headers = headers).parsedSafe<JSONObject>()
        val array = json?.optJSONArray("response")

        val list = mutableListOf<SearchResponse>()

        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)

                list.add(
                    newAnimeSearchResponse(
                        obj.optString("title"),
                        "$mainUrl/anime/${obj.optString("anime_url")}",
                        TvType.Anime
                    ) {
                        posterUrl = obj.optJSONObject("poster")?.optString("original")
                    }
                )
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Популярное", list)),
            list.size >= pageSize
        )
    }

    // =========================
    // 🔎 Search
    // =========================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?q=$query"

        val json = app.get(url, headers = headers).parsedSafe<JSONObject>()
        val array = json?.optJSONArray("response")

        val list = mutableListOf<SearchResponse>()

        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)

                list.add(
                    newAnimeSearchResponse(
                        obj.optString("title"),
                        "$mainUrl/anime/${obj.optString("anime_url")}",
                        TvType.Anime
                    ) {
                        posterUrl = obj.optJSONObject("poster")?.optString("original")
                    }
                )
            }
        }

        return list
    }

    // =========================
    // 📄 Load (деталі + серії)
    // =========================
    override suspend fun load(url: String): LoadResponse {
        val json = app.get(
            "$url?need_videos=true",
            headers = headers
        ).parsedSafe<JSONObject>()

        val obj = json?.optJSONObject("response")
            ?: throw Exception("No data")

        val episodes = mutableListOf<Episode>()
        val videos = obj.optJSONArray("videos")

        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.getJSONObject(i)

                val number = v.optString("number")
                val iframe = v.optString("iframe")

                if (iframe.isNotBlank()) {
                    episodes.add(
                        newEpisode(iframe) {
                            name = "Серия $number"
                            episode = number.toIntOrNull()
                        }
                    )
                }
            }
        }

        return newAnimeLoadResponse(
            obj.optString("title"),
            url,
            TvType.Anime
        ) {
            posterUrl = obj.optJSONObject("poster")?.optString("original")
            plot = obj.optString("description")

            this.episodes = mutableMapOf(
                DubStatus.Subbed to episodes.toList()
            )
        }
    }

    // =========================
    // 🎥 Video links
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
