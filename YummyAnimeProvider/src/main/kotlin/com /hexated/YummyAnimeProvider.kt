package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.FormBody
import android.net.Uri
import android.util.Base64


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


    data class AnimeItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("anime_url") val animeUrl: String? = null,
        @JsonProperty("poster") val poster: Poster? = null,
        @JsonProperty("description") val description: String? = null
    )

    data class Poster(
        @JsonProperty("fullsize") val fullsize: String? = null
    )

    data class ApiResponse(
        @JsonProperty("response") val response: List<AnimeItem>? = null
    )

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

    // 🔥 отримуємо anime_url
        val animeUrl = url.substringAfterLast("/")

        val apiUrl = "https://api.yani.tv/anime/$animeUrl?need_videos=true"

        val headers = mapOf(
            "X-Application" to "i0zejgswfnwup27a",
            "Accept" to "application/json",
            "Lang" to "ru"
        )

        val json = JSONObject(app.get(apiUrl, headers = headers).text)
        val response = json.optJSONObject("response")

        val videos = response?.optJSONArray("videos")

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        if (videos != null) {
            for (i in 0 until videos.length()) {
                val obj = videos.optJSONObject(i) ?: continue

                val number = obj.optString("number").trim()
                if (number.isEmpty() || number == "-") continue
                val key = number.trimStart('0').ifEmpty { number }.lowercase()
                if (key in seen) continue
                seen.add(key)

                episodes.add(
                    newEpisode("$animeUrl|$key") {
                        name = "Серія $key"
                        episode = key.toFloatOrNull()?.toInt()
                    }
                )
            }
        }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            posterUrl = fixUrl(poster)
            this.plot = plot

        // 🔥 ВАЖЛИВО: саме так CloudStream очікує episodes
            this.episodes = mutableMapOf(
                DubStatus.Subbed to episodes.sortedBy { it.episode }
            )
        }
    }

        // =========================
    // 🎥 Відео
    // =========================
    private val apiHeaders = mapOf(
        "X-Application" to "i0zejgswfnwup27a",
        "Accept" to "application/json",
        "Lang" to "ru"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|", limit = 2)
        if (parts.size < 2) return false
        val animeUrl = parts[0]
        val number = parts[1]

        val body = app.get(
            "https://api.yani.tv/anime/$animeUrl?need_videos=true",
            headers = apiHeaders
        ).text
        val videos = JSONObject(body).optJSONObject("response")?.optJSONArray("videos")
            ?: return false

        var found = false

        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (v.optString("number") != number) continue

            var iframe = v.optString("iframe_url")
            if (iframe.isBlank()) continue
            if (iframe.startsWith("//")) iframe = "https:$iframe"

            val info = v.optJSONObject("data")
            val player = info?.optString("player").orEmpty()
            val dubbing = info?.optString("dubbing").orEmpty()
            val label = listOf(player, dubbing)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { name }

            try {
                val ok = if (iframe.contains("kodik", ignoreCase = true)) {
                    invokeKodik(iframe, label, callback)
                } else {
                    loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
                }
                if (ok) found = true
            } catch (e: Exception) {
                println("YUMMY LINKS ERROR [$label]: ${e.message}")
            }
        }

        return found
    }

    // ---------- Kodik ----------
    private suspend fun invokeKodik(
        iframe: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = "https://" + java.net.URI(iframe).host
        val page = app.get(iframe, referer = "$mainUrl/").text

        val paramsJson = Regex("""urlParams\s*=\s*['"]?(\{.*?\})['"]?\s*;""")
            .find(page)?.groupValues?.get(1) ?: return false
        val params = JSONObject(paramsJson)

        val type = Regex("""videoInfo\.type\s*=\s*['"](.*?)['"]""").find(page)?.groupValues?.get(1)
            ?: return false
        val hash = Regex("""videoInfo\.hash\s*=\s*['"](.*?)['"]""").find(page)?.groupValues?.get(1)
            ?: return false
        val id = Regex("""videoInfo\.id\s*=\s*['"](.*?)['"]""").find(page)?.groupValues?.get(1)
            ?: return false

        // Шлях POST-ендпоінта Kodik змінюється — беремо його зі скрипта плеєра
        var ftorPath = "/ftor"
        try {
            val scriptPath = Regex("""src="(/assets/js/app\.player_single[^"]+)"""")
                .find(page)?.groupValues?.get(1)
            if (scriptPath != null) {
                val js = app.get(host + scriptPath, referer = iframe).text
                val enc = Regex("""url:\s*atob\(["'](.*?)["']\)""").find(js)?.groupValues?.get(1)
                if (enc != null) {
                    ftorPath = String(Base64.decode(enc, Base64.DEFAULT))
                }
            }
        } catch (_: Exception) { }

        val res = app.post(
            host + ftorPath,
            data = mapOf(
                "d" to params.optString("d"),
                "d_sign" to params.optString("d_sign"),
                "pd" to params.optString("pd"),
                "pd_sign" to params.optString("pd_sign"),
                "ref" to params.optString("ref"),
                "ref_sign" to params.optString("ref_sign"),
                "bad_user" to "true",
                "cdn_is_working" to "true",
                "type" to type,
                "hash" to hash,
                "id" to id,
                "info" to "{}"
            ),
            referer = iframe,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        val links = JSONObject(res).optJSONObject("links") ?: return false
        var any = false

        for (q in links.keys()) {
            val arr = links.optJSONArray(q) ?: continue
            val src = arr.optJSONObject(0)?.optString("src") ?: continue
            var url = kodikDecode(src) ?: continue
            if (url.startsWith("//")) url = "https:$url"

            callback(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$host/"
                    this.quality = q.toIntOrNull() ?: Qualities.Unknown.value
                }
            )
            any = true
        }
        return any
    }

    // Kodik кодує посилання ROT-N + base64 (N невідомий, тому перебираємо)
    private fun kodikDecode(src: String): String? {
        if (src.startsWith("http") || src.startsWith("//")) return src
        for (shift in 0..25) {
            val rotated = src.map { c ->
                when (c) {
                    in 'a'..'z' -> 'a' + (c - 'a' + shift) % 26
                    in 'A'..'Z' -> 'A' + (c - 'A' + shift) % 26
                    else -> c
                }
            }.joinToString("")
            val padded = rotated + "=".repeat((4 - rotated.length % 4) % 4)
            val decoded = try {
                String(Base64.decode(padded, Base64.DEFAULT))
            } catch (e: Exception) {
                continue
            }
            if (decoded.startsWith("http") || decoded.startsWith("//")) return decoded
        }
        return null
    }
}
   
