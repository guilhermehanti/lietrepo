// Adicione o plugin do Cloudstream no topo
plugins {
    id("com.android.library")
    id("com.lagradost.cloudstream3.plugin")
}

android {
    namespace = "com.PobreFlix"
    compileSdk = 34 // Recomendo 34 para compatibilidade atual

    defaultConfig {
        minSdk = 21
    }

    buildFeatures {
        buildConfig = true
    }
}

// Configuração do Plugin Cloudstream
cloudstream {
    setPlugins {
        // Nome da classe principal que estende Plugin
        register("PobreFlixProvider") {
            id = "PobreFlixProvider"
            displayName = "PobreFlix"
            version = "1.0.0"
            description = "PobreFlix, assistir online, filmes, séries, animes, doramas"
            authors = listOf("lawlietbr")
            websiteUrl = "https://lospobreflix.lat"
            iconUrl = "https://www.image2url.com/r2/default/images/1776018665375-eafe8c65-10f1-490c-9994-2f519402b6e3.png"
            language = "pt-br"
            status = 1
            tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama")
        }
    }
}

dependencies {
    // O Cloudstream provê as libs base, não precisa adicionar jackson manualmente 
    // se estiver usando a API do Cloudstream, pois ela já resolve essas dependências.
    compileOnly("com.lagradost:cloudstream3:pre-release")
}