package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.model.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.video.Video

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
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://api.yani.tv/search?q=$encoded&offset=0&limit=20"

        val headers = mapOf(
            "X-Application" to "i0zejgswfnwup27a",
            "Accept" to "application/json",
            "Lang" to "ru"
        )

        println("YUMMY SEARCH URL: $url")
        showToast("Search: $query")

        return try {
            val responseBody = app.get(url, headers).toString()

            println("YUMMY RESPONSE: $responseBody")

            val json = org.json.JSONObject(responseBody)
            val responseArray = json.optJSONArray("response")

            println("YUMMY ARRAY SIZE: ${responseArray?.length()}")

            if (responseArray == null || responseArray.length() == 0) {
                showToast("❌ Нічого не знайдено")
                return emptyList()
            }

            val results = mutableListOf<SearchResponse>()

            for (i in 0 until responseArray.length()) {
                val obj = responseArray.optJSONObject(i) ?: continue

                val title = obj.optString("title", "")
                val animeUrl = obj.optString("anime_url", "")
                val posterRaw = obj.optJSONObject("poster")?.optString("fullsize") ?: ""
                val poster = if (posterRaw.startsWith("//")) "https:$posterRaw" else posterRaw

                println("POSTER FIXED: $poster")
                println("ITEM: $title | $animeUrl")

                if (title.isBlank() || animeUrl.isBlank()) continue

                results.add(
                    newAnimeSearchResponse(
                        title,
                        "https://site.yummyani.me/catalog/item/$animeUrl",
                        TvType.Anime
                    ) {
                        this.posterUrl = poster
                    }
                )
            }

            showToast("✅ Знайдено: ${results.size}")

            results

        } catch (e: Exception) {
            e.printStackTrace()
            println("YUMMY ERROR: ${e.message}")
            showToast("❌ Error search")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

    // Назва
        val title = doc.selectFirst("div.titles > h1")?.text() ?: "No title"

    // Обкладинка
        val poster = doc.selectFirst("div.poster-block img")?.attr("src") ?: ""

    // Опис
        val plot = doc.selectFirst("p[itemprop=description]")?.text()

    // -------------------------
    // Епізоди через Kodik API
    // -------------------------
        val episodes = mutableListOf<Episode>()

        try {
            // Витягуємо slug для API
            val urlParts = url.removePrefix(mainUrl).removePrefix("/anime/").split("/")
            if (urlParts.isNotEmpty()) {
                val animeSlug = urlParts[0]
                val apiUrl = "$mainUrl/api/anime/$animeSlug/episodes"
                val response = app.get(apiUrl).text()

                // Десеріалізація JSON
                val episodesResponse = gson.fromJson(response, EpisodesResponse::class.java)
                episodesResponse.data?.forEach { ep ->
                    val episode = Episode(
                        name = ep.title ?: "Episode ${ep.number ?: "?"}",
                        url = "${animeSlug}/episodes/${ep.id ?: ""}",
                        episodeNumber = ep.number?.toFloat() ?: 0f
                    )
                    episodes.add(episode)
                }
            }
        } catch (e: Exception) {
            println("Error loading episodes: ${e.message}")
        }

        return SAnime(
            title = title,
            thumbnail_url = poster,
            description = description,
            episodes = episodes
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val urlParts = data.split("/")
            if (urlParts.size < 3) return false

            val animeSlug = urlParts[0]
            val episodeId = urlParts[2]
            val apiUrl = "$mainUrl/api/anime/$animeSlug/episodes/$episodeId/players"

            val response = app.get(apiUrl).text()
            val playersResponse = gson.fromJson(response, PlayersResponse::class.java)

            playersResponse.data?.forEach { player ->
                player.embeds?.forEach { embed ->
                    val videos = extractKodik(embed.url ?: "")
                    videos.forEach { video ->
                        callback.invoke(ExtractorLink(video.url, "Kodik", video.url, null))
                    }
                }
            }
            return true
        } catch (e: Exception) {
            println("Error loading links: ${e.message}")
            return false
        }
    }
}
