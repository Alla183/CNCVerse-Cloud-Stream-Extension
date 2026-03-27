version = 5

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "ru"

    authors = listOf("Alla183")
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Anime")
    iconUrl = "https://site.yummyani.me/favicon.ico"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
