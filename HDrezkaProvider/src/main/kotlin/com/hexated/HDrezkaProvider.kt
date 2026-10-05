package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Session
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*



class HDrezkaProvider : MainAPI() {

    private val http by lazy {
        Session(app.baseClient).apply {
            defaultHeaders = app.defaultHeaders
            responseParser = app.responseParser
        }
    }

    companion object {
        private const val BROWSER_DEBOUNCE_MS = 10_000L

        var context: android.content.Context? = null

        private var csGuardWasEverActive = false
        private var lastBrowserOpenMs = 0L
    }

    

    override var mainUrl = "https://hdrezka.ag"

    override var name = "HDrezka"

    override val hasMainPage = true

    override var lang = "ru"

    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "/films/?filter=last" to "фильмы — новинки",
        "/films/?filter=watching" to "фильмы — смотрят",
        "/films/?filter=popular" to "фильмы — популярные",

        "/series/?filter=last" to "сериалы — новинки",
        "/series/?filter=watching" to "сериалы — смотрят",
        "/series/?filter=popular" to "сериалы — популярные",

        "/cartoons/?filter=last" to "мультфильмы — новинки",
        "/cartoons/?filter=watching" to "мультфильмы — смотрят",

        "/animation/?filter=last" to "аниме — новинки",
        "/animation/?filter=watching" to "аниме — смотрят"
    )



    private fun solveAnubisPow(
        randomData: String,
        difficulty: Int
    ): Pair<Long, String>? {
        val digest = MessageDigest.getInstance("SHA-256")

        for (nonce in 0L until 50_000_000L) {
            val hash = digest.digest(
                "$randomData$nonce".toByteArray(Charsets.UTF_8)
            )

            var valid = true
            var remaining = difficulty

            for (byte in hash) {
                if (remaining <= 0) break

                val value = byte.toInt() and 0xff

                if (remaining >= 8) {
                    if (value != 0) {
                        valid = false
                        break
                    }
                    remaining -= 8
                } else {
                    val mask = 0xff shl (8 - remaining)
                    if ((value and mask) != 0) {
                        valid = false
                    }
                    remaining = 0
                }
            }

            if (valid) {
                return nonce to hash.joinToString("") {
                    "%02x".format(it)
                }
            }
        }

        return null
    }


    private suspend fun passAnubisChallenge(html: String, redir: String) {
        val raw = ANUBIS_SCRIPT.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return

        val payload = tryParseJson<AnubisPayload>(raw) ?: return
        val ch = payload.challenge ?: return

        val randomData = ch.randomData ?: return
        val id = ch.id ?: return

        val diff = payload.rules?.difficulty
            ?: ch.difficulty
            ?: 4

        val started = System.currentTimeMillis()

        val solved = solveAnubisPow(randomData, diff) ?: return
        val (nonce, response) = solved

        val elapsed = (System.currentTimeMillis() - started)
            .coerceAtLeast(50L)

        val basePrefix = ANUBIS_PREFIX.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"')
            ?.trim()
            .orEmpty()

        val q = listOf(
            "id" to id,
            "response" to response,
            "nonce" to nonce.toString(),
            "redir" to redir,
            "elapsedTime" to elapsed.toString(),
        ).joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }

        val pass = http.get(
            "$mainUrl$basePrefix/.within.website/x/cmd/anubis/api/pass-challenge?$q",
            referer = redir,
            timeout = 35_000,
        )

        val auth = pass.cookies.keys.filter {
            it.contains("anubis", ignoreCase = true)
        }

        Log.i(
            "HDrezka",
            "anubis pass code=${pass.code} authCookies=$auth " +
                "jarHas=${pass.cookies.isNotEmpty()} nonce=$nonce ${elapsed}ms"
        )
    }


    private fun syncMainUrl(finalUrl: String) {
        try {
            val uri = java.net.URI(finalUrl)

            val scheme = uri.scheme ?: return
            val host = uri.host ?: return

            mainUrl = "$scheme://$host"
        } catch (_: Exception) {
        // Ignore invalid redirect URL
        }
    }

    private suspend fun fetchDocument(
        url: String,
        timeout: Long = 30_000,
    ): org.jsoup.nodes.Document {
        var response = http.get(
            url,
            timeout = timeout,
        )

        syncMainUrl(response.url)

        var html = response.text

        if (isAnubisChallenge(html)) {
            passAnubisChallenge(
                html = html,
                redir = "$mainUrl/"
            )

            response = http.get(
                url,
                timeout = timeout,
            )

            syncMainUrl(response.url)

            html = response.text
        }

        return Jsoup.parse(
            html,
            response.url
        )
    }

    private val ANUBIS_SCRIPT = Regex(
        """<script[^>]*id=["']anubis_challenge["'][^>]*>([\s\S]*?)</script>""",
        RegexOption.IGNORE_CASE
    )

    private val ANUBIS_PREFIX = Regex(
        """<script[^>]*id=["']anubis_base_prefix["'][^>]*>([\s\S]*?)</script>""",
        RegexOption.IGNORE_CASE
    )

    private fun isAnubisChallenge(html: String): Boolean {
        return html.contains("anubis_challenge", ignoreCase = true) ||
                html.contains("не бот", ignoreCase = true)
    }

    
    


    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = request.data.split("?", limit = 2)

        val document = fetchDocument(
            "${url.first()}page/$page/?${url.last()}"
        )

        val home = document
            .select("div.b-content__inline_items div.b-content__inline_item")
            .map { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse {

        val title = selectFirst(
            "div.b-content__inline_item-link > a"
        )?.text()?.trim().orEmpty()

        val href = selectFirst("a")
            ?.attr("href")
            .orEmpty()

        val poster = select("img")
            .attr("src")

        val isSeries = select("span.info").isNotEmpty()

        return if (!isSeries) {

            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
            }

        } else {

            val episode = Regex("[^0-9]")
                .replace(
                    select("span.info")
                        .text()
                        .substringAfter(","),
                    ""
            )
                .toIntOrNull()

            newAnimeSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                posterUrl = poster
                addDubStatus(
                    true,
                    true,
                    episode,
                    episode
                )
            }
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val document = fetchDocument(
            "$mainUrl/search/?do=search&subaction=search&q=$query"
        )

        return document
            .select(
                "div.b-content__inline_items div.b-content__inline_item"
            )
            .map {
                it.toSearchResult()
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            
        ).document

        val id = url.split("/").last().split("-").first()
        val title = (document.selectFirst("div.b-post__title h1")?.text()?.trim()
            ?: document.selectFirst("div.b-post__origtitle")?.text()?.trim()).toString()
        val poster = fixUrlNull(document.selectFirst("div.b-sidecover img")?.attr("src"))
        val tags =
            document.select("table.b-post__info > tbody > tr:contains(Жанр) span[itemprop=genre]")
                .map { it.text() }
        val year = document.select("div.film-info > div:nth-child(2) a").text().toIntOrNull()
        val tvType = if (document.select("div#simple-episodes-tabs")
                .isNullOrEmpty()
        ) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("div.b-post__description_text")?.text()?.trim()
        val trailer = app.post(
            "$mainUrl/engine/ajax/gettrailervideo.php",
            data = mapOf("id" to id),
            referer = url,
            
        ).parsedSafe<Trailer>()?.code.let {
            Jsoup.parse(it.toString()).select("iframe").attr("src")
        }
        val ratingText =
            document.selectFirst("table.b-post__info > tbody > tr:nth-child(1) span.bold")?.text()
        val score = ratingText?.toDoubleOrNull()?.let { Score.from10(it) }
        val actors =
            document.select("table.b-post__info > tbody > tr:last-child span.item").mapNotNull {
                Actor(
                    it.selectFirst("span[itemprop=name]")?.text() ?: return@mapNotNull null,
                    it.selectFirst("span[itemprop=actor]")?.attr("data-photo")
                )
            }

        val recommendations = buildList {
            // Старые рекомендации
            addAll(
                document.select("div.b-sidelist div.b-content__inline_item")
                    .mapNotNull { it.toSearchResult() }
            )

    // Новые рекомендации
            addAll(
                document.select("div.b-post__partcontent_item[data-url]")
                    .mapNotNull { item ->
                        val href = item.attr("data-url")
                            .ifBlank { item.selectFirst("a")?.attr("href") ?: "" }

                        val title = item.selectFirst(".title")?.text()?.trim()
                            ?: item.selectFirst("a")?.text()?.trim()
                            ?: return@mapNotNull null

                        val year = item.selectFirst(".year")
                            ?.text()
                            ?.filter(Char::isDigit)
                            ?.toIntOrNull()

                        val num = item.selectFirst(".td.num")?.text()?.trim().orEmpty()

                        newMovieSearchResponse(
                            "$num. $title",
                            fixUrl(href),
                            TvType.Movie
                        ) {
                            this.year = year
                        }
                    }
            )
        }.distinctBy { it.url }

        val data = HashMap<String, Any>()
        val server = ArrayList<Map<String, String>>()

        data["id"] = id
        data["favs"] = document.selectFirst("input#ctrl_favs")?.attr("value").toString()
        data["ref"] = url

        return if (tvType == TvType.TvSeries) {
            // Забираємо всі li та a всередині ul#translators-list
            val translators = document.select("#translators-list li, #translators-list a")
            if (translators.isNotEmpty()) {
                translators.map { res ->
                    server.add(
                        mapOf(
                            "translator_name" to res.text().trim(),
                            "translator_id" to res.attr("data-translator_id"),
                        )
                    )
                }
            } else {
                // Extracts the default translator_id from the init script if translation list is missing
                document.select("script").map { script ->
                    val match = Regex("initCDNSeriesEvents\\(\\d+, (\\d+)").find(script.data())
                    if (match != null) {
                        server.add(
                            mapOf(
                                "translator_name" to "HDrezka",
                                "translator_id" to match.groupValues[1]
                            )
                        )
                    }
                }
            }
            val episodes = document.select(
                    "#simple-episodes-tabs .b-simple_episode__item"
                ).map { ep ->

                    val season = ep.attr("data-season_id").toIntOrNull()
                    val episode = ep.attr("data-episode_id").toIntOrNull()

                    val name = ep.selectFirst(".b-simple_episode__title")
                        ?.text()
                        ?.ifBlank { "Episode $episode" }
                        ?: "Episode $episode"

                    data["season"] = "$season"
                    data["episode"] = "$episode"
                    data["server"] = server
                    data["action"] = "get_stream"

                    newEpisode(data.toJson()) {
                        this.name = name
                        this.season = season
                        this.episode = episode
                    }
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            val translators = document.select("#translators-list li, #translators-list a")

            translators.forEach { el ->
                val node = if (el.tagName() == "li") {
                    el.selectFirst("a") ?: el
                } else el

                val id = node.attr("data-translator_id")
                if (id.isNullOrBlank()) return@forEach // пропускаємо сміття

                server.add(
                    mapOf(
                        "translator_name" to node.text().trim(),
                        "translator_id" to id,
                        "camrip" to node.attr("data-camrip"),
                        "ads" to node.attr("data-ads"),
                        "director" to node.attr("data-director")
                    )
                )
            }

            data["server"] = server
            data["action"] = "get_movie"

            newMovieLoadResponse(title, url, TvType.Movie, data.toJson()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    private fun decryptStreamUrl(data: String): String {
        // If the URL is already in plain text (starts with quality marker like [360p]),
        // skip decryption — HDrezka no longer encrypts stream URLs
        if (data.startsWith("[")) return data

        fun getTrash(arr: List<String>, item: Int): List<String> {
            val trash = ArrayList<List<String>>()
            for (i in 1..item) {
                trash.add(arr)
            }
            return trash.reduce { acc, list ->
                val temp = ArrayList<String>()
                acc.forEach { ac ->
                    list.forEach { li ->
                        temp.add(ac.plus(li))
                    }
                }
                return@reduce temp
            }
        }

        val trashList = listOf("@", "#", "!", "^", "$")
        val trashSet = getTrash(trashList, 2) + getTrash(trashList, 3)
        var trashString = data.replace("#h", "").split("//_//").joinToString("")

        trashSet.forEach {
            val temp = base64Encode(it.toByteArray())
            trashString = trashString.replace(temp, "")
        }

        return base64Decode(trashString)

    }

    private suspend fun cleanCallback(
        source: String,
        url: String,
        quality: String,
        isM3u8: Boolean,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        sourceCallback.invoke(
            newExtractorLink(
                source,
                source,
                url,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = getQuality(quality)
                this.headers = mapOf(
                    "Origin" to mainUrl
                )
            }
        )
    }

    private fun getLanguage(str: String): String {
        return when (str) {
            "Русский" -> "Russian"
            "Українська" -> "Ukrainian"
            else -> str
        }
    }

    private fun getQuality(str: String): Int {
        return when (str) {
            "360p" -> Qualities.P240.value
            "480p" -> Qualities.P360.value
            "720p" -> Qualities.P480.value
            "1080p" -> Qualities.P720.value
            "1080p Ultra" -> Qualities.P1080.value
            else -> getQualityFromName(str)
        }
    }

    private suspend fun invokeSources(
        source: String,
        url: String,
        subtitle: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        decryptStreamUrl(url).split(",").map { links ->
            val quality =
                Regex("\\[([0-9]{3,4}p\\s?\\w*?)]").find(links)?.groupValues?.getOrNull(1)
                    ?.trim() ?: return@map null
            links.replace("[$quality]", "").split(" or ")
                .map {
                    val link = it.trim()
                    val type = if(link.contains(".m3u8")) "(Main)" else "(Backup)"
                    cleanCallback(
                        "$source $type",
                        link,
                        quality,
                        link.contains(".m3u8"),
                        sourceCallback,
                    )
                }
        }

        subtitle.split(",").map { sub ->
            val language =
                Regex("\\[(.*)]").find(sub)?.groupValues?.getOrNull(1) ?: return@map null
            val link = sub.replace("[$language]", "").trim()
            subCallback.invoke(
                newSubtitleFile(
                    getLanguage(language),
                    link
                )
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        tryParseJson<Data>(data)?.let { res ->
            if (res.server?.isEmpty() == true) {
                val document = app.get(
                    res.ref ?: return@let,
                    
                ).document
                document.select("script").map { script ->
                    if (script.data().contains("sof.tv.initCDNMoviesEvents(")) {
                        val dataJson =
                            script.data().substringAfter("false, {").substringBefore("});")
                        tryParseJson<LocalSources>("{$dataJson}")?.let { source ->
                            invokeSources(
                                this.name,
                                source.streams,
                                source.subtitle.toString(),
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            } else {
                res.server?.map { server ->
                    app.post(
                        url = "$mainUrl/ajax/get_cdn_series/?t=${Date().time}",
                        data = mapOf(
                            "id" to res.id,
                            "translator_id" to server.translator_id,
                            "favs" to res.favs,
                            "is_camrip" to server.camrip,
                            "is_ads" to server.ads,
                            "is_director" to server.director,
                            "season" to res.season,
                            "episode" to res.episode,
                            "action" to res.action,
                        ).filterValues { it != null }
                            .mapValues { it.value as String },
                        referer = res.ref,
                        
                    ).parsedSafe<Sources>()?.let { source ->
                        invokeSources(
                            server.translator_name.toString(),
                            source.url,
                            source.subtitle.toString(),
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }

        return true
    }

    data class LocalSources(
        @JsonProperty("streams") val streams: String,
        @JsonProperty("subtitle") val subtitle: Any?,
    )

    data class Sources(
        @JsonProperty("url") val url: String,
        @JsonProperty("subtitle") val subtitle: Any?,
    )

    data class Server(
        @JsonProperty("translator_name") val translator_name: String?,
        @JsonProperty("translator_id") val translator_id: String?,
        @JsonProperty("camrip") val camrip: String?,
        @JsonProperty("ads") val ads: String?,
        @JsonProperty("director") val director: String?,
    )

    data class Data(
        @JsonProperty("id") val id: String?,
        @JsonProperty("favs") val favs: String?,
        @JsonProperty("server") val server: List<Server>?,
        @JsonProperty("season") val season: String?,
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("action") val action: String?,
        @JsonProperty("ref") val ref: String?,
    )

    data class Trailer(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("code") val code: String?,
    )
    
    data class AnubisPayload(
        @JsonProperty("rules") val rules: AnubisRules? = null,
        @JsonProperty("challenge") val challenge: AnubisChallenge? = null,
    )

    data class AnubisRules(
        @JsonProperty("algorithm") val algorithm: String? = null,
        @JsonProperty("difficulty") val difficulty: Int? = null,
    )

    data class AnubisChallenge(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("randomData") val randomData: String? = null,
        @JsonProperty("difficulty") val difficulty: Int? = null,
        @JsonProperty("method") val method: String? = null,
)
}
