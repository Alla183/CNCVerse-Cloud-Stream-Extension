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
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.*

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

import okhttp3.Interceptor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


class HDrezkaProvider : MainAPI() {

    companion object {
        private const val BROWSER_DEBOUNCE_MS = 10_000L

        var context: android.content.Context? = null

        private var csGuardWasEverActive = false
        private var lastBrowserOpenMs = 0L
    }

    private var anubisCookie: String? = null

    override var mainUrl = "https://rezka.ag"

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
        "$mainUrl/films/?filter=watching" to "фильмы",
        "$mainUrl/series/?filter=watching" to "сериалы",
        "$mainUrl/cartoons/?filter=watching" to "мультфильмы",
        "$mainUrl/animation/?filter=watching" to "аниме"
    )


    private val anubisKiller = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()

        if (anubisCookie.isNullOrEmpty()) {
            anubisCookie = getAnubisCookie(url)
        }

        val reqWithCookie =
            if (!anubisCookie.isNullOrEmpty()) {
                request.newBuilder()
                    .header("Cookie", anubisCookie!!)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
                    )
                    .build()
            } else {
                request.newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
                    )
                    .build()
            }

        var response = chain.proceed(reqWithCookie)

        val body = response.peekBody(Long.MAX_VALUE).string()

        if (body.contains("id=\"anubis_challenge\"") || response.code == 503) {
            response.close()

            val newCookie = getAnubisCookie(url)

            if (newCookie != null) {
                anubisCookie = newCookie

                val retry = request.newBuilder()
                    .header("Cookie", anubisCookie!!)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
                    )
                    .build()

                return@Interceptor chain.proceed(retry)
            }

            return@Interceptor chain.proceed(request)
        }

        response
    }



    private fun getAnubisCookie(url: String): String? {
        val latch = CountDownLatch(1)
        var fetchedCookie: String? = null

        Handler(Looper.getMainLooper()).post {
            try {
                val ctx = context ?: throw Exception("Context is null")

                val webView = WebView(ctx)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val targetHost = Uri.parse(url).host ?: return false
                        val reqHost = request.url.host ?: return false
                        return !reqHost.contains(targetHost)
                    }
                }

                webView.loadUrl(url)

                var polling = true

                val handler = Handler(Looper.getMainLooper())

                val checkRunnable = object : Runnable {
                    override fun run() {
                        if (!polling) return

                        val cookies = CookieManager.getInstance().getCookie(url)

                        if (cookies == null || !cookies.contains("-anubis-auth=")) {
                            handler.postDelayed(this, 250)
                            return
                        }

                        polling = false

                        fetchedCookie = cookies
                            .split(";")
                            .map { it.trim() }
                            .firstOrNull { it.contains("-anubis-auth=") }

                        try {
                            webView.stopLoading()
                            webView.destroy()
                        } catch (_: Exception) {
                        }

                        if (latch.count > 0)
                            latch.countDown()
                    }
                }

                handler.postDelayed(checkRunnable, 250)

                handler.postDelayed({
                    polling = false

                    try {
                        webView.stopLoading()
                        webView.destroy()
                    } catch (_: Exception) {
                    }

                    if (latch.count > 0)
                        latch.countDown()
                }, 15000)

            } catch (_: Exception) {
                if (latch.count > 0)
                    latch.countDown()
            }
        }

        latch.await(16, TimeUnit.SECONDS)

        return fetchedCookie
    }
    

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = request.data.split("?")

        val document = app.get(
            "${url.first()}page/$page/?${url.last()}",
            interceptor = anubisKiller
        ).document

        val home = document
            .select("div.b-content__inline_items div.b-content__inline_item")
            .map { it.toSearchResult() }

        return newHomePageResponse(
            request.name,
            home
        )
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

        val document = app.get(

            "$mainUrl/search/?do=search&subaction=search&q=$query",

            interceptor = anubisKiller

        ).document

        return document

            .select("div.b-content__inline_items div.b-content__inline_item")

            .map {

                it.toSearchResult()

            }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            interceptor = anubisKiller
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
            interceptor = anubisKiller
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

                        newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
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
                    interceptor = anubisKiller
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
                        interceptor = anubisKiller
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

}
