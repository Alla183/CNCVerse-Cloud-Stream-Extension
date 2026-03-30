package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.models.*
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
    // 🔥 витягуємо anime_url
        val animeUrl = url.substringAfterLast("/")

        val apiUrl = "https://api.yani.tv/anime/$animeUrl?need_videos=true"

        val headers = mapOf(
            "X-Application" to "i0zejgswfnwup27a",
            "Accept" to "application/json",
            "Lang" to "ru"
        )

        val json = JSONObject(app.get(apiUrl, headers).toString())
        val response = json.optJSONObject("response")

        val videos = response?.optJSONArray("videos")

        val episodes = mutableListOf<Episode>()

        val seen = mutableSetOf<String>()

        if (videos != null) {
            for (i in 0 until videos.length()) {
                val obj = videos.optJSONObject(i) ?: continue

                val number = obj.optString("number") ?: continue
                val iframe = obj.optString("iframe_url") ?: continue

                if (number in seen) continue
                seen.add(number)

                val fixedIframe = if (iframe.startsWith("//")) "https:$iframe" else iframe

                episodes.add(
                    newEpisode(fixedIframe) {
                        name = "Серия $number"
                        episode = number.toIntOrNull()
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = fixUrl(poster)
            title = title
            plot = plot
            this.episodes = mapOf(DubStatus.Subbed to episodes.sortedBy { it.episode })
        }
    }
    // -------------------------
    // Епізоди через Kodik API
    // -------------------------
    fun decodeKodik(src: String): String? {
        if (src.startsWith("http")) return src

        for (shift in 0..25) {
            val shifted = src.map {
                when (it) {
                    in 'a'..'z' -> 'a' + (it - 'a' + shift) % 26
                    in 'A'..'Z' -> 'A' + (it - 'A' + shift) % 26
                    else -> it
                }
            }.joinToString("")

            val decoded = runCatching {
                val padded = shifted + "=".repeat((4 - shifted.length % 4) % 4)
                String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
            }.getOrNull()

            if (decoded != null && decoded.startsWith("http")) {
                return decoded
            }
        }

        return null
    }

    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (data.isBlank()) return false

        val iframe = if (data.startsWith("//")) "https:$data" else data

        println("YUMMY iframe: $iframe")

    // 🔥 витягуємо ID + HASH
        val regex = Regex("/season/(\\d+)/([a-z0-9]+)/")
        val match = regex.find(iframe)

        if (match == null) {
            println("❌ Не знайдено ID")
            return false
        }

        val id = match.groupValues[1]
        val hash = match.groupValues[2]

    // 🔥 fallback домени як у Tachiyomi
        val hosts = listOf(
            "https://kodik.cc",
            "https://kodik.info",
            "https://kodik.biz",
            "https://kodik.link"
        )

        for (host in hosts) {
            try {
                val request = app.post(
                    "$host/ftor",
                    data = mapOf(
                        "id" to id,
                        "hash" to hash,
                        "type" to "anime-serial",
                        "bad_user" to "true",
                        "cdn_is_working" to "true"
                    ),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to iframe
                    )
                )

                val json = JSONObject(request.text)

                val links = json.optJSONObject("links") ?: continue

                links.keys().forEach { quality ->

                    val arr = links.optJSONArray(quality) ?: return@forEach

                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue

                        val src = obj.optString("src")

                        val decoded = decodeKodik(src) ?: continue

                        callback.invoke(
                            newExtractorLink(
                                source = "YummyAnime",
                                name = "Kodik $quality",
                                url = decoded,
                                referer = iframe,
                                quality = quality.removeSuffix("p").toIntOrNull() ?: 720,
                                isM3u8 = decoded.contains(".m3u8")
                            )
                        )
                    }
                }

                println("✅ SUCCESS через $host")
                return true

            } catch (e: Exception) {
                println("❌ fail $host: ${e.message}")
            }
        }

        return false
        }
    }
}
