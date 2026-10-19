package me.prince.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime

private const val TAG = "Sports"
private const val API = "https://site.api.espn.com/apis/site/v2/sports"
private const val MAX_GAMES = 12
private const val AHEAD_MS = 18 * 3_600_000L
// Games that started within this long and haven't finished yet still count as upcoming (delays, rain)
private const val LATE_START_MS = 3_600_000L
// ESPN gives start times only: a game that started up to 10h ago ended roughly within the last 6h
private const val FINAL_KEEP_MS = 10 * 3_600_000L
private const val NEXT_GAME_MS = 7 * 24 * 3_600_000L
private const val SCHEDULE_REFRESH_MS = 6 * 3_600_000L
private const val ESPN_APP = "com.espn.score_center"
private val FALLBACK_GLOW = Color(0xFF3A4A8C)

// sports.leagues aliases → ESPN paths
private val LEAGUES = mapOf(
    "nfl" to "football/nfl",
    "nba" to "basketball/nba",
    "wnba" to "basketball/wnba",
    "mlb" to "baseball/mlb",
    "nhl" to "hockey/nhl",
    "cfb" to "football/college-football",
    "cbb" to "basketball/mens-college-basketball",
    "mls" to "soccer/usa.1",
    "epl" to "soccer/eng.1",
)
// College scoreboards default to ranked games only; these groups return every FBS / Division I game
private val COLLEGE_GROUPS = mapOf("cfb" to 80, "cbb" to 50)

// ESPN network names (lowercase) → apps that carry them, tried in order
private val NETWORK_APPS: Map<String, List<String>> = listOf(
    listOf("espn", "espn2", "espnu", "espnews", "espn+", "secn", "sec network", "accn", "acc network", "abc") to listOf(ESPN_APP),
    listOf("fox", "fs1", "fs2", "btn", "big ten network", "fox deportes") to listOf("com.fox.foxone"),
    listOf("nbc", "peacock", "nbcsn") to listOf("com.peacocktv.peacockandroid", "com.nbcsports.apps.tv", "com.nbcuni.nbc.androidtv"),
    listOf("usa net", "usa") to listOf("com.nbcu.tve.usatv.androidtv", "com.peacocktv.peacockandroid"),
    listOf("cbs", "cbssn", "paramount+") to listOf("com.cbs.ott"),
    listOf("tnt", "tbs", "trutv", "tnt sports") to
        listOf("com.wbd.stream", "com.turner.tnt.android.networkapp", "com.turner.tbs.android.networkapp", "com.turner.trutv"),
    listOf("prime video", "amazon prime video") to listOf("com.amazon.amazonvideo.livingroom"),
    listOf("apple tv", "apple tv+", "mls season pass") to listOf("com.apple.atve.androidtv.appletv"),
    listOf("nba tv", "nba league pass") to listOf("com.nbaimd.gametime.nba2011"),
).flatMap { (names, apps) -> names.map { it to apps } }.toMap()

enum class GameState { Upcoming, Live, Final }

data class Side(val abbr: String, val score: String?, val logo: ImageBitmap?, val winner: Boolean)

data class Game(
    val id: String,
    val away: Side,
    val home: Side,
    val state: GameState,
    // Clock/period while live ("4:12 - 3rd"), "Final" when over; upcoming games are formatted from start
    val detail: String,
    val start: Instant,
    val network: String?,
    val isMine: Boolean,
    val glow: Color,
    val launch: Intent?,
)

private class RawSide(val abbr: String, val score: String?, val logoUrl: String?, val color: String?, val winner: Boolean, val ranked: Boolean)

private class RawGame(
    val id: String,
    val league: String,
    val away: RawSide,
    val home: RawSide,
    val state: GameState,
    val detail: String,
    val start: Instant,
    val networks: List<String>,
)

private class NextGame(val fetchedAt: Long, val game: RawGame?)

// Process-wide caches; only touched from the polling loop on Dispatchers.IO
private val logoCache = HashMap<String, ImageBitmap>()
private val teamIds = HashMap<Pair<String, String>, String>()
private val nextGames = HashMap<Pair<String, String>, NextGame>()

// Your teams' games first, then the rest; null when every league failed to load (keep showing the old list)
fun fetchGames(pm: PackageManager): List<Game>? {
    val teams = BuildConfig.SPORTS_TEAMS.split(',')
        .mapNotNull { entry ->
            val (league, abbr) = entry.split(':').map { it.trim() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            (league.lowercase() to abbr.uppercase()).takeIf { it.first in LEAGUES }
        }
        .toSet()
    val leagues = (BuildConfig.SPORTS_LEAGUES.split(',').map { it.trim().lowercase() } + teams.map { it.first })
        .filter { it in LEAGUES }
        .distinct()
    if (leagues.isEmpty()) return emptyList()

    val now = System.currentTimeMillis()
    var loaded = false
    val all = leagues.flatMap { league ->
        try {
            scoreboard(league).also { loaded = true }
        } catch (e: IOException) {
            Log.w(TAG, "Scoreboard $league failed", e)
            emptyList()
        } catch (e: JSONException) {
            Log.w(TAG, "Unexpected scoreboard $league", e)
            emptyList()
        }
    }
    if (!loaded) return null

    fun RawGame.isMine() = (league to away.abbr) in teams || (league to home.abbr) in teams
    val shown = all.filter { game ->
        val sinceStart = now - game.start.toEpochMilli()
        val inWindow = when (game.state) {
            GameState.Live -> true
            GameState.Upcoming -> sinceStart in -AHEAD_MS..LATE_START_MS
            GameState.Final -> sinceStart in 0..FINAL_KEEP_MS
        }
        val big = game.league !in COLLEGE_GROUPS || game.away.ranked || game.home.ranked
        inWindow && (big || game.isMine())
    }
    // Teams with nothing today still show their next game
    val idle = teams.filter { team -> shown.none { it.league == team.first && (it.away.abbr == team.second || it.home.abbr == team.second) } }
    val upcoming = idle.mapNotNull { nextGame(it, now) }

    val rank = { state: GameState -> when (state) { GameState.Live -> 0; GameState.Upcoming -> 1; GameState.Final -> 2 } }
    return (shown + upcoming)
        .distinctBy { it.id }
        .sortedWith(
            compareByDescending<RawGame> { it.isMine() }
                .thenBy { rank(it.state) }
                // Upcoming soonest first, finals most recent first
                .thenBy { if (it.state == GameState.Final) -it.start.toEpochMilli() else it.start.toEpochMilli() }
        )
        .take(MAX_GAMES)
        .map { it.toGame(pm, it.isMine()) }
}

private fun scoreboard(league: String): List<RawGame> {
    val group = COLLEGE_GROUPS[league]?.let { "?groups=$it&limit=300" }.orEmpty()
    val events = JSONObject(httpGet("$API/${LEAGUES.getValue(league)}/scoreboard$group")).optJSONArray("events")
    return events.objects().mapNotNull { parseEvent(it, league) }
}

private fun nextGame(team: Pair<String, String>, now: Long): RawGame? {
    nextGames[team]?.takeIf { now - it.fetchedAt < SCHEDULE_REFRESH_MS }?.let { return it.game }
    return try {
        val path = LEAGUES.getValue(team.first)
        val id = teamIds[team] ?: teamId(path, team.second)?.also { teamIds[team] = it } ?: return null
        val events = JSONObject(httpGet("$API/$path/teams/$id/schedule")).optJSONArray("events")
        val game = events.objects()
            .mapNotNull { parseEvent(it, team.first) }
            .filter { it.state == GameState.Upcoming && it.start.toEpochMilli() - now in 0..NEXT_GAME_MS }
            .minByOrNull { it.start }
        nextGames[team] = NextGame(now, game)
        game
    } catch (e: IOException) {
        Log.w(TAG, "Schedule ${team.second} failed", e)
        null
    } catch (e: JSONException) {
        Log.w(TAG, "Unexpected schedule ${team.second}", e)
        null
    }
}

// /teams/{abbr} only works in some leagues, so look the id up in the league's team list
private fun teamId(path: String, abbr: String): String? =
    JSONObject(httpGet("$API/$path/teams?limit=1000"))
        .optJSONArray("sports")?.optJSONObject(0)
        ?.optJSONArray("leagues")?.optJSONObject(0)
        ?.optJSONArray("teams").objects()
        .mapNotNull { it.optJSONObject("team") }
        .firstOrNull { it.optString("abbreviation").equals(abbr, ignoreCase = true) }
        ?.optString("id")

// Scoreboard and team-schedule events share a shape, except scores (string vs object) and broadcasts
private fun parseEvent(event: JSONObject, league: String): RawGame? {
    val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: return null
    val competitors = competition.optJSONArray("competitors").objects()
    val away = competitors.firstOrNull { it.optString("homeAway") == "away" }?.let(::parseSide) ?: return null
    val home = competitors.firstOrNull { it.optString("homeAway") == "home" }?.let(::parseSide) ?: return null
    val status = (competition.optJSONObject("status") ?: event.optJSONObject("status"))?.optJSONObject("type") ?: return null
    val state = when (status.optString("state")) {
        "in" -> GameState.Live
        "post" -> GameState.Final
        else -> GameState.Upcoming
    }
    val shortDetail = status.optString("shortDetail")
    val detail = if (state == GameState.Final && shortDetail.startsWith("FT")) "Final" + shortDetail.removePrefix("FT") else shortDetail
    val start = try {
        OffsetDateTime.parse(event.optString("date")).toInstant()
    } catch (e: java.time.format.DateTimeParseException) {
        return null
    }
    val broadcasts = competition.optJSONArray("broadcasts").objects()
    val networks = broadcasts.flatMap { it.optJSONArray("names")?.strings().orEmpty() } +
        broadcasts.mapNotNull { it.optJSONObject("media")?.optString("shortName")?.takeIf(String::isNotBlank) }
    return RawGame(event.optString("id"), league, away, home, state, detail, start, networks.distinct())
}

private fun parseSide(competitor: JSONObject): RawSide? {
    val team = competitor.optJSONObject("team") ?: return null
    val score = when (val raw = competitor.opt("score")) {
        is JSONObject -> raw.optString("displayValue")
        is String -> raw
        else -> null
    }?.takeIf { it.isNotBlank() }
    val logo = team.optString("logo").ifBlank { team.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty() }
    val rank = competitor.optJSONObject("curatedRank")?.optInt("current", 99) ?: 99
    return RawSide(
        abbr = team.optString("abbreviation"),
        score = score,
        logoUrl = logo.ifBlank { null },
        color = team.optString("color").ifBlank { null },
        winner = competitor.optBoolean("winner"),
        ranked = rank in 1..25,
    )
}

private fun RawGame.toGame(pm: PackageManager, mine: Boolean): Game {
    val (launch, network) = gameLaunch(pm, networks)
    return Game(
        id = "$league:$id",
        away = away.toSide(),
        home = home.toSide(),
        state = state,
        detail = detail,
        start = start,
        network = network,
        isMine = mine,
        glow = teamColor(home.color) ?: teamColor(away.color) ?: FALLBACK_GLOW,
        launch = launch,
    )
}

private fun RawSide.toSide() = Side(abbr, score, logo(logoUrl), winner)

// First installed app that carries one of the game's networks, else the ESPN app for the score
private fun gameLaunch(pm: PackageManager, networks: List<String>): Pair<Intent?, String?> {
    for (network in networks) {
        val launch = NETWORK_APPS[network.lowercase()].orEmpty().firstNotNullOfOrNull { pm.getLeanbackLaunchIntentForPackage(it) }
        if (launch != null) return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) to network
    }
    return pm.getLeanbackLaunchIntentForPackage(ESPN_APP)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) to networks.firstOrNull()
}

// White and near-black team colours vanish as a glow on a black screen
private fun teamColor(hex: String?): Color? {
    if (hex == null || hex.length != 6) return null
    val argb = hex.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or argb).takeIf { it.luminance() in 0.02f..0.8f }
}

// 64px copies via ESPN's resizer (the originals are 500px)
private fun logo(url: String?): ImageBitmap? {
    if (url == null) return null
    logoCache[url]?.let { return it }
    val source = URL(url)
    val small = if (source.host.endsWith("espncdn.com")) "https://a.espncdn.com/combiner/i?img=${source.path}&w=64&h=64" else url
    return try {
        val bytes = httpBytes(small)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { logoCache[url] = it }
    } catch (e: IOException) {
        Log.w(TAG, "Logo $url failed", e)
        null
    }
}