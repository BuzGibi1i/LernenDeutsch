// Dışarıda hiçbir şey tanımlamıyoruz, her şeyi cloudstream bloğunun içine alıyoruz.
cloudstream {
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "BuzGibi1i/LernenDeutsch")


    version     = 1
    language    = "tr"
    description = "Almanca Öğrenme İçerikleri"
    authors     = listOf("BuzGibi1i")
    status      = 1
    tvTypes     = listOf("Movie")
    iconUrl     = "https://cdn-icons-png.flaticon.com/256/5988/5988791.png"
}
