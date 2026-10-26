package ayx.whatsapp

/**
 * Fixed popular list for the status editor's music picker (v191).
 *
 * Curated, static — NOT a live trending API (user choice, 2026-10-26).
 * Each entry is title + artist only; tapping a row resolves the real
 * audio through the EXISTING JioSaavn search/matcher, so no audio URLs
 * are stored here. Edit entries in this one place; the picker reads
 * PopularSongs.LIST directly. Lyrics code is untouched by this list.
 */
object PopularSongs {
    val LIST: List<Pair<String, String>> = listOf(
        "Aaj Ki Raat" to "Stree 2 (Sachin-Jigar)",
        "Aayi Nai" to "Stree 2",
        "Tum Se" to "Teri Baaton Mein Aisa Uljha Jiya",
        "Chaleya" to "Jawan",
        "Kesariya" to "Brahmastra",
        "Raabta" to "Agent Vinod",
        "Apna Bana Le" to "Bhediya",
        "Tum Hi Ho" to "Aashiqui 2",
        "Agar Tum Saath Ho" to "Tamasha",
        "Husn" to "Anuv Jain",
        "Jo Tum Mere Ho" to "Anuv Jain",
        "Heeriye" to "Jasleen Royal, Arijit Singh",
        "Soulmate" to "Badshah, Arijit Singh",
        "Ve Kamleya" to "Rocky Aur Rani Kii Prem Kahaani",
        "O Maahi" to "Dunki",
        "With You" to "AP Dhillon",
        "Excuses" to "AP Dhillon",
        "Calm Down" to "Rema, Selena Gomez",
        "Die With A Smile" to "Lady Gaga, Bruno Mars",
        "Espresso" to "Sabrina Carpenter",
        "Birds of a Feather" to "Billie Eilish",
        "Shape of You" to "Ed Sheeran",
        "Blinding Lights" to "The Weeknd",
        "As It Was" to "Harry Styles"
    )
}
