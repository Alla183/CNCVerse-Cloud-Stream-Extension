version = 1

android {
    buildFeatures {
        buildConfig = true
    }
} 

cloudstream {
    language = "ru"
    

    // description = "Аниме"
     authors = listOf("Alla183")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Anime")

    iconUrl = "https://site.yummyani.me/favicon.ico"
}
