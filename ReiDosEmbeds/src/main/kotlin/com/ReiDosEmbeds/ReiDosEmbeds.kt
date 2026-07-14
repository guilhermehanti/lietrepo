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
    private var channelsCache: MutableMap<String, Pair<String, String>> = mutableMapOf()
    private val blockedCategories = listOf("Adulto", "adulto", "ADULTO")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categories = mutableListOf<HomePageList>()

        // 1. Scraping da Agenda
        try {
            val agendaDoc = app.get("https://reidosembeds.com/agenda").document
            val agendaEvents = mutableListOf<SearchResponse>()
            val eventCards = agendaDoc.select("article[data-event-card]")
            
            for (card in eventCards) {
                val statusBadge = card.select("div.absolute.right-2.top-2").text().trim()
                
                // Filtrar apenas Ao Vivo e Em Breve
                if (statusBadge.equals("AO VIVO", ignoreCase = true) || statusBadge.equals("EM BREVE", ignoreCase = true)) {
                    val rawTitle = card.select("h3").text().trim()
                    
                    // Adiciona a TAG diretamente no título para aparecer no card do Cloudstream
                    val title = "[$statusBadge] $rawTitle"
                    val posterUrl = card.select("img").firstOrNull()?.attr("src") ?: ""
                    
                    // Pega o link real da página individual do evento
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
                categories.add(HomePageList("Agenda (Ao Vivo e Em Breve)", agendaEvents, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            // Se falhar o load da agenda, ignorar para não quebrar o load dos canais
        }

        // 2. Fetch de Categorias (Canais normais)
        val categoriesResponse = app.get("$apiUrl/channels/categories").text
        val categoriesJson = JSONObject(categoriesResponse)
        val categoriesArray = categoriesJson.getJSONArray("data")
        
        for (i in 0 until categoriesArray.length()) {
            val category = categoriesArray.getJSONObject(i)
            val categoryName = category.getString("name")
            val categoryId = category.getString("id")
            
            if (blockedCategories.contains(categoryName)) continue
            
            val channelsResponse = app.get("$apiUrl/channels?category=${categoryId.replace(" ", "%20")}").text
            val channelsJson = JSONObject(channelsResponse)
            val channelsArray = channelsJson.getJSONArray("data")
            
            val channels = mutableListOf<SearchResponse>()
            
            for (j in 0 until channelsArray.length()) {
                val channel = channelsArray.getJSONObject(j)
                val name = channel.getString("name")
                val slug = channel.getString("id")
                val embedUrl = channel.getString("embed_url")
                val logoUrl = channel.getString("logo_url")
                
                var posterUrl = logoUrl
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                channelsCache[slug] = Pair(name, posterUrl)
                
                channels.add(
                    newLiveSearchResponse(name, embedUrl, TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                )
            }
            
            if (channels.isNotEmpty()) {
                categories.add(HomePageList(categoryName, channels, isHorizontalImages = true))
            }
        }
        
        // 3. Fetch de Todos os Canais
        val allChannelsResponse = app.get("$apiUrl/channels").text
        val allChannelsJson = JSONObject(allChannelsResponse)
        val allChannelsArray = allChannelsJson.getJSONArray("data")
        
        val allChannels = mutableListOf<SearchResponse>()
        for (i in 0 until allChannelsArray.length()) {
            val channel = allChannelsArray.getJSONObject(i)
            val name = channel.getString("name")
            val embedUrl = channel.getString("embed_url")
            val logoUrl = channel.getString("logo_url")
            val category = channel.optString("category", "")
            
            if (blockedCategories.contains(category)) continue
            
            var posterUrl = logoUrl
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            allChannels.add(
                newLiveSearchResponse(name, embedUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            )
        }
        
        categories.add(0, HomePageList("Todos", allChannels, isHorizontalImages = true))
        
        return newHomePageResponse(categories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        // Se for um evento da agenda (tem "/eventos/" na URL)
        if (url.contains("/eventos/")) {
            val doc = app.get(url).document
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
        val channelData = channelsCache[slug]
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
        val response = app.get("$apiUrl/pesquisa?q=${query.replace(" ", "%20")}").text
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
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Lógica para raspar página individual de EVENTO
        if (data.contains("/eventos/")) {
            val doc = app.get(data).document
            
            // Pega todos os links das opções de player na página individual
            val choices = doc.select(".player-choice[data-player-url]")
            
            coroutineScope {
                choices.map { choice ->
                    async {
                        val optUrl = choice.attr("data-player-url")
                        val optName = choice.select(".block.truncate").text().trim()
                        if (optUrl.isNotEmpty()) {
                            extractFromUrl(optUrl, optName, data, callback)
                        }
                    }
                }.awaitAll()
            }
            
            // Fallback se não encontrar os botões de escolha, tenta achar o iframe principal
            if (choices.isEmpty()) {
                val iframe = doc.select("iframe#event-player-frame").attr("src")
                if (iframe.isNotEmpty()) {
                    extractFromUrl(iframe, "Principal", data, callback)
                }
            }
            return true
        }

        // Lógica original para raspar os CANAIS normais (que vêm com link embed_url)
        extractFromUrl(data, name, data, callback)
        return true
    }

    /**
     * Função auxiliar para ler tanto páginas que têm um iframe de `__play`
     * quanto páginas de player que já possuem a lista `var sources`.
     */
    private suspend fun extractFromUrl(
        url: String, 
        sourceName: String, 
        referer: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, headers = mapOf("Referer" to referer)).text
            var playerHtml = html
            var playerUrl = url

            // Checa se tem o iframe do __play por cima
            val iframePattern = Regex("""<iframe[^>]*src="([^"]*__play[^"]*)"[^>]*>""")
            val iframeMatch = iframePattern.find(html)
            
            if (iframeMatch != null) {
                playerUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
                if (playerUrl.startsWith("//")) playerUrl = "https:$playerUrl"
                playerHtml = app.get(playerUrl, headers = mapOf("Referer" to url)).text
            }

            // Procura a variável das fontes m3u8
            val sourcesPattern = Regex("""var sources\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
            val sourcesMatch = sourcesPattern.find(playerHtml)
            
            if (sourcesMatch != null) {
                val sourcesArray = JSONArray(sourcesMatch.groupValues[1])
                for (i in 0 until sourcesArray.length()) {
                    val source = sourcesArray.getJSONObject(i)
                    val streamUrl = source.getString("src").replace("\\/", "/")
                    val label = source.optString("label", "Source ${i + 1}")
                    
                    val finalName = if (sourceName.isNotEmpty()) "$sourceName - $label" else label
                    
                    M3u8Helper.generateM3u8(
                        finalName,
                        streamUrl,
                        playerUrl,
                        headers = mapOf(
                            "Referer" to playerUrl,
                            "Origin" to "https://v2.rde.lat",
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                        )
                    ).forEach(callback)
                }
            }
        } catch (e: Exception) {
            // Caso dê erro em um link específico, ignora para não interromper a extração de outros players
        }
    }
}