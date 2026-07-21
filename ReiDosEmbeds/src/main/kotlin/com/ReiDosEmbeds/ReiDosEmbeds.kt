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

@CloudstreamPlugin
class ReiDosEmbedsProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosEmbeds())
    }
}

class ReiDosEmbeds : MainAPI() {
    override var name = "Rei dos Embeds"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiUrl = "https://reidosembeds.com/api"
    private val blockedCategories = listOf("Adulto", "adulto", "ADULTO")

    // Headers para evitar bloqueios simples de bots
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to "https://reidosembeds.com",
        "Referer" to "https://reidosembeds.com/"
    )

    // O companion object mantém os dados salvos na RAM enquanto o app estiver aberto
    companion object {
        private var cachedChannelCategories: List<HomePageList>? = null
        private val channelsCacheMap: MutableMap<String, Pair<String, String>> = mutableMapOf()

        private var cachedAgenda: HomePageList? = null
        private var lastAgendaFetch: Long = 0L
        private const val AGENDA_CACHE_MS = 15 * 60 * 1000L // 15 minutos
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()
        val currentTime = System.currentTimeMillis()

        // 1. Scraping da Agenda (com cache de 15 minutos)
        if (cachedAgenda == null || (currentTime - lastAgendaFetch) > AGENDA_CACHE_MS) {
            try {
                val agendaDoc = app.get("https://reidosembeds.com/agenda", headers = defaultHeaders).document
                val agendaEvents = mutableListOf<SearchResponse>()
                val eventCards = agendaDoc.select("article[data-event-card]")
                
                for (card in eventCards) {
                    val statusBadge = card.select("div.absolute.right-2.top-2").text().trim()
                    
                    // Filtrar apenas Ao Vivo e Em Breve
                    if (statusBadge.equals("AO VIVO", ignoreCase = true) || statusBadge.equals("EM BREVE", ignoreCase = true)) {
                        val rawTitle = card.select("h3").text().trim()
                        val title = "[$statusBadge] $rawTitle"
                        val posterUrl = card.select("img").firstOrNull()?.attr("src") ?: ""
                        val mainUrl = card.select("div.flex.gap-2 a[href^=http]").firstOrNull()?.attr("href") ?: ""
                        
                        if (mainUrl.isNotEmpty()) {
                            agendaEvents.add(
                                newLiveSearchResponse(title, mainUrl, TvType.Live) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }
                }

                if (agendaEvents.isNotEmpty()) {
                    agendaEvents.sortBy { if (it.name.contains("AO VIVO", ignoreCase = true)) 0 else 1 }
                    cachedAgenda = HomePageList("Agenda (Ao Vivo e Em Breve)", agendaEvents, isHorizontalImages = true)
                } else {
                    cachedAgenda = null
                }
                
                lastAgendaFetch = currentTime
            } catch (e: Exception) {
                // Falha na agenda, mantém o cache antigo se existir
            }
        }
        
        cachedAgenda?.let { homeCategories.add(it) }

        // 2. Fetch Otimizado de Canais e Categorias (roda apenas 1x e salva na RAM)
        if (cachedChannelCategories == null) {
            try {
                val categoriesList = mutableListOf<HomePageList>()
                
                // Requisita categorias (1 única requisição)
                val categoriesResponse = app.get("$apiUrl/channels/categories", headers = defaultHeaders).text
                val categoriesJson = JSONObject(categoriesResponse)
                val categoriesArray = categoriesJson.getJSONArray("data")
                
                val categoryNameMap = mutableMapOf<String, String>()
                val validCategoryIds = mutableListOf<String>()
                
                for (i in 0 until categoriesArray.length()) {
                    val cat = categoriesArray.getJSONObject(i)
                    val catName = cat.getString("name")
                    val catId = cat.getString("id")
                    
                    if (!blockedCategories.contains(catName)) {
                        categoryNameMap[catId] = catName
                        validCategoryIds.add(catId)
                    }
                }

                // Requisita TODOS os canais de uma vez (1 única requisição em vez de um loop)
                val allChannelsResponse = app.get("$apiUrl/channels", headers = defaultHeaders).text
                val allChannelsJson = JSONObject(allChannelsResponse)
                val allChannelsArray = allChannelsJson.getJSONArray("data")
                
                val allChannelsList = mutableListOf<SearchResponse>()
                val groupedChannels = mutableMapOf<String, MutableList<SearchResponse>>()

                for (i in 0 until allChannelsArray.length()) {
                    val channel = allChannelsArray.getJSONObject(i)
                    val name = channel.getString("name")
                    val slug = channel.getString("id")
                    val embedUrl = channel.getString("embed_url")
                    val logoUrl = channel.getString("logo_url")
                    val catId = channel.optString("category", "") 
                    
                    var posterUrl = logoUrl
                    if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                    
                    // Salva no map global para a função load()
                    channelsCacheMap[slug] = Pair(name, posterUrl)
                    
                    val searchResponse = newLiveSearchResponse(name, embedUrl, TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                    
                    allChannelsList.add(searchResponse)
                    
                    // Agrupa canais localmente sem fazer novas requisições web
                    if (validCategoryIds.contains(catId)) {
                        val catName = categoryNameMap[catId] ?: "Outros"
                        if (!groupedChannels.containsKey(catName)) {
                            groupedChannels[catName] = mutableListOf()
                        }
                        groupedChannels[catName]?.add(searchResponse)
                    }
                }

                if (allChannelsList.isNotEmpty()) {
                    categoriesList.add(HomePageList("Todos", allChannelsList, isHorizontalImages = true))
                }
                
                for ((catName, channels) in groupedChannels) {
                    if (channels.isNotEmpty()) {
                        categoriesList.add(HomePageList(catName, channels, isHorizontalImages = true))
                    }
                }
                
                cachedChannelCategories = categoriesList
                
            } catch (e: Exception) {
                // Em caso de erro 429, a variável continua nula e ele tenta na próxima vez
            }
        }
        
        cachedChannelCategories?.let { homeCategories.addAll(it) }

        return newHomePageResponse(homeCategories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        // Se for um evento da agenda
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

        // Lógica para canais normais
        val slug = url.substringAfterLast("/")
        val channelData = channelsCacheMap[slug]
        val title = channelData?.first ?: slug.replace("-", " ").split(" ").joinToString(" ") { word ->
            if (word.isNotEmpty()) word.replaceFirstChar { it.uppercase() } else word
        }
        val posterUrl = channelData?.second ?: ""
        val plot = "Assista $title ao vivo!"
        
        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = posterUrl
            this.plot = plot
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
                val embedUrl = channel.getString("embed_url")
                val logoUrl = channel.optString("logo_url", "")
                val category = channel.optString("category", "")
                
                if (blockedCategories.contains(category)) continue
                
                var posterUrl = logoUrl
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                results.add(
                    newLiveSearchResponse(name, embedUrl, TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                )
            }
            
            val eventsArray = data.getJSONArray("events")
            for (i in 0 until eventsArray.length()) {
                val event = eventsArray.getJSONObject(i)
                val title = event.getString("title")
                val poster = event.optString("poster", "")
                val embeds = event.getJSONArray("embeds")
                
                var posterUrl = poster
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                if (embeds.length() > 0) {
                    val embedUrl = embeds.getJSONObject(0).getString("embed_url")
                    results.add(
                        newLiveSearchResponse(title, embedUrl, TvType.Live) {
                            this.posterUrl = posterUrl
                        }
                    )
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
                            val optUrl = choice.attr("data-player-url")
                            val optName = choice.select(".block.truncate").text().trim()
                            if (optUrl.isNotEmpty()) {
                                resolvePlayer(optUrl, data, optName, callback)
                            }
                        }
                    }.awaitAll()
                }
                return true
            } else {
                val mainIframe = doc.select("iframe#event-player-frame").attr("src")
                if (mainIframe.isNotEmpty()) {
                    resolvePlayer(mainIframe, data, "Principal", callback)
                    return true
                }
            }
        }

        resolvePlayer(data, data, name, callback)
        return true
    }

    private suspend fun resolvePlayer(
        startUrl: String, 
        initialReferer: String, 
        sourceName: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = startUrl
        var referer = initialReferer

        for (i in 0..2) {
            try {
                val currentHtml = app.get(currentUrl, headers = mapOf("Referer" to referer)).text
                
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
                
                var iframeMatch = Regex("""<iframe[^>]*src="([^"]*__play[^"]*)"[^>]*>""").find(currentHtml)
                if (iframeMatch == null) {
                    iframeMatch = Regex("""<iframe[^>]*src="([^"]+)"[^>]*>""").find(currentHtml)
                }
                
                if (iframeMatch != null) {
                    var nextUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
                    if (nextUrl.startsWith("//")) nextUrl = "https:$nextUrl"
                    
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