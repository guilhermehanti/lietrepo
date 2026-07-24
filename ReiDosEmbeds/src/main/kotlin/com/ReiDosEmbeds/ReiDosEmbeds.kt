package com.reidosembeds

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.json.JSONArray
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

@CloudstreamPlugin
class ReiDosEmbedsProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosEmbeds())
    }
}

private data class TempAgendaEvent(
    val title: String,
    val url: String,
    val posterUrl: String,
    val isLive: Boolean,
    val timeStr: String,
    val sortKey: String,
    val statusBadge: String
)

class ReiDosEmbeds : MainAPI() {
    override var name = "Rei dos Embeds"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiUrl = "https://reidosembeds.com/api"

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to "https://reidosembeds.com",
        "Referer" to "https://reidosembeds.com/"
    )

    companion object {
        // Agora armazenamos o Nome, PosterUrl e o vital EmbedUrl!
        private val channelsCacheMap = ConcurrentHashMap<String, Triple<String, String, String>>()

        private var cachedAgenda: HomePageList? = null
        private var lastAgendaFetch: Long = 0L
        private const val AGENDA_CACHE_MS = 15 * 60 * 1000L 
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()
        val currentTime = System.currentTimeMillis()

        // ==========================================
        // 1. LÓGICA DA AGENDA (Carregada apenas na Página 1 inicial)
        // ==========================================
        if (page == 1) {
            try {
                if (cachedAgenda == null || (currentTime - lastAgendaFetch) > AGENDA_CACHE_MS) {
                    val tempAgendaEvents = mutableListOf<TempAgendaEvent>()
                    var currentPage = 1
                    var keepFetching = true
                    
                    while (keepFetching && currentPage <= 10) {
                        val pageUrl = "https://reidosembeds.com/agenda?status=all&page=$currentPage"
                        val agendaDoc = app.get(pageUrl, headers = defaultHeaders, cacheTime = 15).document
                        val eventCards = agendaDoc.select("article[data-event-card]")
                        
                        if (eventCards.isEmpty()) break
                        
                        var foundValidEventOnPage = false
                        
                        for (card in eventCards) {
                            val statusBadge = card.select("div.absolute.right-2.top-2").text().trim()
                            
                            if (statusBadge.equals("AO VIVO", ignoreCase = true) || statusBadge.equals("EM BREVE", ignoreCase = true)) {
                                foundValidEventOnPage = true
                                
                                val rawTitle = card.select("h3").text().trim()
                                val posterUrl = card.select("img").firstOrNull()?.attr("src") ?: ""
                                val mainUrl = card.select("div.flex.gap-2 a[href^=http]").firstOrNull()?.attr("href") ?: ""
                                
                                var eventDate = ""
                                var eventTime = ""
                                
                                val timeElements = card.select("span.inline-flex.items-center")
                                for (el in timeElements) {
                                    val text = el.text().trim()
                                    val timeMatch = Regex("\\b\\d{2}:\\d{2}\\b").find(text)
                                    if (timeMatch != null) eventTime = timeMatch.value
                                    
                                    val dateMatch = Regex("\\b\\d{2}/\\d{2}/\\d{4}\\b").find(text)
                                    if (dateMatch != null) {
                                        val parts = dateMatch.value.split("/")
                                        if (parts.size == 3) eventDate = "${parts[2]}${parts[1]}${parts[0]}" 
                                    }
                                }
                                
                                val sortKey = if (eventDate.isNotEmpty() && eventTime.isNotEmpty()) {
                                    "${eventDate}${eventTime}"
                                } else if (eventTime.isNotEmpty()) {
                                    "99999999${eventTime}"
                                } else {
                                    "9999999999:99"
                                }
                                
                                val isLive = statusBadge.equals("AO VIVO", ignoreCase = true)
                                if (mainUrl.isNotEmpty()) tempAgendaEvents.add(TempAgendaEvent(rawTitle, mainUrl, posterUrl, isLive, eventTime, sortKey, statusBadge))
                            }
                        }
                        if (!foundValidEventOnPage) keepFetching = false else currentPage++
                    }

                    if (tempAgendaEvents.isNotEmpty()) {
                        tempAgendaEvents.sortWith(compareBy({ !it.isLive }, { it.sortKey }))
                        val agendaEvents = tempAgendaEvents.map { event ->
                            val prefix = if (event.timeStr.isNotEmpty()) "[${event.statusBadge} - ${event.timeStr}]" else "[${event.statusBadge}]"
                            val fullTitle = "$prefix ${event.title}"
                            newLiveSearchResponse(fullTitle, event.url, TvType.Live) { this.posterUrl = event.posterUrl }
                        }
                        cachedAgenda = HomePageList("Agenda (Ao Vivo e Em Breve)", agendaEvents, isHorizontalImages = true)
                    } else {
                        cachedAgenda = null
                    }
                    lastAgendaFetch = currentTime
                }
            } catch (e: Exception) {}
            cachedAgenda?.let { homeCategories.add(it) }
        }

        // ==========================================
        // 2. LÓGICA DOS CANAIS (Paginada via Scroll Inteligente)
        // ==========================================
        try {
            // O Dicionário só precisa ser lido 1x do cache interno (super rápido)
            val categoriesResponse = app.get("$apiUrl/channels/categories", headers = defaultHeaders, cacheTime = 1440).text
            val categoriesJson = JSONObject(categoriesResponse)
            
            if (categoriesJson.has("data")) {
                val categoriesArray = categoriesJson.getJSONArray("data")
                val categoryPairs = mutableListOf<Pair<String, String>>()
                for (i in 0 until categoriesArray.length()) {
                    val cat = categoriesArray.getJSONObject(i)
                    categoryPairs.add(Pair(cat.getString("id"), cat.getString("name")))
                }

                // Configuração do Scroll do Cloudstream: Carregamos 3 categorias por página rolada
                val itemsPerPage = 3
                val startIndex = (page - 1) * itemsPerPage
                val endIndex = minOf(startIndex + itemsPerPage, categoryPairs.size)

                if (startIndex < categoryPairs.size) {
                    val chunk = categoryPairs.subList(startIndex, endIndex)

                    // Pedimos apenas essas 3 categorias para o banco de dados oficial!
                    val chunkResults = coroutineScope {
                        chunk.map { (catId, catName) ->
                            async {
                                try {
                                    val channelsResponse = app.get("$apiUrl/channels?category=${catId.replace(" ", "%20")}", headers = defaultHeaders, cacheTime = 1440).text
                                    val channelsJson = JSONObject(channelsResponse)
                                    if (!channelsJson.has("data")) return@async null

                                    val channelsArray = channelsJson.getJSONArray("data")
                                    val channels = mutableListOf<SearchResponse>()

                                    for (j in 0 until channelsArray.length()) {
                                        val channel = channelsArray.getJSONObject(j)
                                        val name = channel.getString("name")
                                        val slug = channel.getString("id")
                                        var posterUrl = channel.optString("logo_url", "")
                                        if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                                        
                                        // O SEGREDO DE MESTRE: Extrair o embed_url agora e salvar na RAM!
                                        val embedUrl = channel.optString("embed_url", "")
                                        channelsCacheMap[slug] = Triple(name, posterUrl, embedUrl)
                                        
                                        val channelUrl = "https://reidosembeds.com/canal/$slug"
                                        channels.add(newLiveSearchResponse(name, channelUrl, TvType.Live) {
                                            this.posterUrl = posterUrl
                                        })
                                    }
                                    
                                    // Ajuste estético no nome da categoria, se necessário
                                    var finalCatName = catName
                                    if (finalCatName.equals("geral", ignoreCase = true)) finalCatName = "Canais Abertos"
                                    if (finalCatName.equals("glo", ignoreCase = true)) finalCatName = "Rede Globo"
                                    
                                    if (channels.isNotEmpty()) HomePageList(finalCatName, channels, isHorizontalImages = true) else null
                                } catch (e: Exception) { null }
                            }
                        }.awaitAll().filterNotNull()
                    }
                    homeCategories.addAll(chunkResults)
                }

                // Avisa ao Cloudstream que ele pode habilitar o "Scroll para carregar mais" se ainda faltarem categorias
                val hasNext = endIndex < categoryPairs.size
                return newHomePageResponse(homeCategories, hasNext = hasNext)
            }
        } catch (e: Exception) {
            if (page == 1) {
                val errorItem = newLiveSearchResponse(e.message ?: "Erro de conexão", "https://reidosembeds.com", TvType.Live) {
                    this.posterUrl = "https://via.placeholder.com/300x450.png?text=ERRO"
                }
                homeCategories.add(HomePageList("🚨 DEBUG: ERRO", listOf(errorItem), isHorizontalImages = true))
            }
        }

        return newHomePageResponse(homeCategories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("/eventos/")) {
            val doc = app.get(url, headers = defaultHeaders).document
            val title = doc.select("h1.event-glow-title").text().trim()
            val plot = doc.select("aside.theme-card p").joinToString("\n") { it.text() }
            var posterUrl = doc.select(".event-stadium img.event-hero-bg").attr("src")
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            return newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        }

        val slug = url.substringAfterLast("/")
        var title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
        var posterUrl = ""
        var embedUrl = url
        
        // Puxa instantaneamente o embed sem usar internet!
        val cachedData = channelsCacheMap[slug]
        if (cachedData != null) {
            title = cachedData.first
            posterUrl = cachedData.second
            embedUrl = cachedData.third
        } else {
            // Trava de segurança: Se o usuário abriu por link direto, nós usamos a barra de busca deles para pegar o link
            try {
                val searchRes = app.get("$apiUrl/pesquisa?q=$slug", headers = defaultHeaders).text
                val data = JSONObject(searchRes).getJSONObject("data")
                val channelsArray = data.getJSONArray("channels")
                for (i in 0 until channelsArray.length()) {
                    val channel = channelsArray.getJSONObject(i)
                    if (channel.getString("id") == slug) {
                        embedUrl = channel.getString("embed_url")
                        posterUrl = channel.optString("logo_url", "")
                        if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                        break
                    }
                }
            } catch (e: Exception) {}
        }
        
        return newMovieLoadResponse(title, url, TvType.Live, embedUrl) {
            this.posterUrl = posterUrl
            this.plot = "Assista $title ao vivo!"
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        try {
            val response = app.get("$apiUrl/pesquisa?q=${query.replace(" ", "%20")}", headers = defaultHeaders).text
            val json = JSONObject(response)
            val data = json.getJSONObject("data")
            
            val results = mutableListOf<SearchResponse>()
            
            val channelsArray = data.getJSONArray("channels")
            for (i in 0 until channelsArray.length()) {
                val channel = channelsArray.getJSONObject(i)
                val name = channel.getString("name")
                val slug = channel.getString("id")
                var posterUrl = channel.optString("logo_url", "")
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                val channelUrl = "https://reidosembeds.com/canal/$slug"
                results.add(newLiveSearchResponse(name, channelUrl, TvType.Live) { this.posterUrl = posterUrl })
            }
            
            val eventsArray = data.getJSONArray("events")
            for (i in 0 until eventsArray.length()) {
                val event = eventsArray.getJSONObject(i)
                val title = event.getString("title")
                var posterUrl = event.optString("poster", "")
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                val embeds = event.getJSONArray("embeds")
                if (embeds.length() > 0) {
                    val embedUrl = embeds.getJSONObject(0).getString("embed_url") 
                    results.add(newLiveSearchResponse(title, embedUrl, TvType.Live) { this.posterUrl = posterUrl })
                }
            }
            return results
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("/eventos/")) {
            val doc = app.get(data, headers = defaultHeaders).document
            val choices = doc.select(".player-choice[data-player-url]")
            
            if (choices.isNotEmpty()) {
                coroutineScope {
                    choices.map { choice ->
                        async {
                            try {
                                val optUrl = choice.attr("data-player-url")
                                val optName = choice.select(".block.truncate").text().trim()
                                if (optUrl.isNotEmpty()) resolvePlayer(optUrl, data, optName, subtitleCallback, callback)
                            } catch (e: Exception) {} 
                        }
                    }.awaitAll()
                }
                return true
            } else {
                val mainIframe = doc.select("iframe#event-player-frame").attr("src")
                if (mainIframe.isNotEmpty()) {
                    resolvePlayer(mainIframe, data, "Principal", subtitleCallback, callback)
                    return true
                }
            }
        }

        // Agora o "data" já é o link direto graças a arquitetura de cache nova
        resolvePlayer(data, data, name, subtitleCallback, callback)
        return true
    }

    private suspend fun resolvePlayer(
        startUrl: String, 
        initialReferer: String, 
        sourceName: String, 
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = startUrl
        var referer = initialReferer

        for (i in 0..2) {
            try {
                if (currentUrl.contains("youtube.com") || currentUrl.contains("youtu.be") || currentUrl.contains("twitch.tv")) {
                    loadExtractor(currentUrl, subtitleCallback, callback)
                    return
                }

                val reqHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Referer" to referer
                )

                val currentHtml = app.get(currentUrl, headers = reqHeaders).text
                
                val sourcesPattern = Regex("""var sources\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
                val sourcesMatch = sourcesPattern.find(currentHtml)
                
                if (sourcesMatch != null) {
                    val sourcesArray = JSONArray(sourcesMatch.groupValues[1])
                    val uri = URI(currentUrl)
                    val origin = "${uri.scheme}://${uri.host}"

                    for (j in 0 until sourcesArray.length()) {
                        val source = sourcesArray.getJSONObject(j)
                        val streamUrl = source.getString("src").replace("\\/", "/")
                        val label = source.optString("label", "Source ${j + 1}")
                        val finalName = if (sourceName.isNotEmpty()) "$sourceName - $label" else label
                        
                        M3u8Helper.generateM3u8(
                            finalName,
                            streamUrl,
                            currentUrl,
                            headers = mapOf(
                                "Referer" to currentUrl,
                                "Origin" to origin,
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                            )
                        ).forEach(callback)
                    }
                    return 
                }
                
                var iframeMatch = Regex("""<iframe[^>]*src="([^"]*(?:rde\.lat|play|embed|youtube|twitch)[^"]*)"[^>]*>""").find(currentHtml)
                if (iframeMatch == null) iframeMatch = Regex("""<iframe[^>]*src="([^"]+)"[^>]*>""").find(currentHtml)
                
                if (iframeMatch != null) {
                    var nextUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
                    if (nextUrl.startsWith("//")) nextUrl = "https:$nextUrl"
                    
                    if (nextUrl.contains("youtube.com") || nextUrl.contains("youtu.be") || nextUrl.contains("twitch.tv")) {
                        loadExtractor(nextUrl, subtitleCallback, callback)
                        return
                    }
                    
                    referer = currentUrl
                    currentUrl = nextUrl
                } else {
                    break
                }
            } catch (e: Exception) {
                break
            }
        }
    }
}