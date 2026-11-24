package com.nuvio.app.features.sports

/**
 * The leagues the Sports page offers, as data.
 *
 * This is the set one provider carries: every path here answers on ESPN's public scoreboard, so a
 * league that is listed is a league that draws. That is the rule to keep when adding one — a path
 * that only fails at run time shows the viewer a league that is permanently empty.
 *
 * Sports ESPN does not carry (boxing, darts, snooker, MotoGP, the Gaelic games, handball…) are not
 * here. The reference TV implementation gets those from TheSportsDB through a scheduled data job;
 * see docs/sports-data-sources.md for what that would cost and why it is not wired up yet.
 *
 * [League.path] follows one provider's address shape. That is not an accident: it is a data string
 * rather than a URL built in code, so a different feed can translate or ignore it.
 */
internal object SportsCatalog {

    private val US = setOf("US")
    private val NA = setOf("US", "CA")
    private val UK = setOf("UK", "IE")

    val leagues: List<League> = listOf(
        // ---- American football
        League(
            id = "football/nfl", sport = Sport.AMERICAN_FOOTBALL, name = "NFL", shortName = "NFL",
            path = "football/nfl", regions = NA, minutes = 195,
            guideWords = listOf("NFL", "Football"), color = 0xFF013369,
            logo = "https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png",
        ),
        League(
            id = "football/college-football", sport = Sport.AMERICAN_FOOTBALL,
            name = "College Football", shortName = "NCAAF", path = "football/college-football",
            regions = NA, minutes = 210,
            guideWords = listOf("College Football", "NCAA Football", "NCAAF", "CFB"), color = 0xFF1E3A5F,
            logo = "https://a.espncdn.com/redesign/assets/img/icons/NCAASports_Football.png",
        ),
        // ---- Basketball
        League(
            id = "basketball/nba", sport = Sport.BASKETBALL, name = "NBA", shortName = "NBA",
            path = "basketball/nba", regions = NA, minutes = 150,
            guideWords = listOf("NBA"), color = 0xFF1D428A,
            logo = "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png",
        ),
        League(
            id = "basketball/wnba", sport = Sport.BASKETBALL, name = "WNBA", shortName = "WNBA",
            path = "basketball/wnba", regions = NA, minutes = 120,
            guideWords = listOf("WNBA"), color = 0xFFC8102E,
            logo = "https://a.espncdn.com/i/teamlogos/leagues/500/wnba.png",
        ),
        League(
            id = "basketball/mens-college-basketball", sport = Sport.BASKETBALL,
            name = "Men's College Basketball", shortName = "NCAAM",
            path = "basketball/mens-college-basketball", regions = US, minutes = 125,
            guideWords = listOf("College Basketball", "NCAA Basketball", "NCAAM"), color = 0xFF2B4C7E,
            logo = "https://a.espncdn.com/redesign/assets/img/icons/NCAASports_MensBasketball.png",
        ),
        League(
            id = "basketball/womens-college-basketball", sport = Sport.BASKETBALL,
            name = "Women's College Basketball", shortName = "NCAAW",
            path = "basketball/womens-college-basketball", regions = US, minutes = 125,
            guideWords = listOf("Women's College Basketball", "NCAAW"), color = 0xFF5B3A8C,
            logo = "https://a.espncdn.com/redesign/assets/img/icons/NCAASports_WomensBasketball.png",
        ),
        League(
            id = "basketball/nba-development", sport = Sport.BASKETBALL,
            name = "NBA G League", shortName = "G LEAGUE",
            path = "basketball/nba-development", minutes = 130,
            guideWords = listOf("G League", "NBA G League"), color = 0xFF1D428A,
            logo = "https://a.espncdn.com/combiner/i?img=/i/teamlogos/leagues/500/nba_gleague.png",
        ),
        League(
            id = "basketball/nbl", sport = Sport.BASKETBALL, name = "NBL", shortName = "NBL",
            path = "basketball/nbl", minutes = 125,
            guideWords = listOf("NBL", "National Basketball League"), color = 0xFFE4002B,
            logo = "https://a.espncdn.com/i/teamlogos/leagues/500/nbl.png",
        ),
        // ---- Hockey and baseball
        League(
            id = "hockey/nhl", sport = Sport.HOCKEY, name = "NHL", shortName = "NHL",
            path = "hockey/nhl", regions = NA, minutes = 160,
            guideWords = listOf("NHL", "Hockey"), color = 0xFF111111,
            logo = "https://a.espncdn.com/i/teamlogos/leagues/500/nhl.png",
        ),
        League(
            id = "baseball/mlb", sport = Sport.BASEBALL, name = "MLB", shortName = "MLB",
            path = "baseball/mlb", regions = NA, minutes = 185,
            guideWords = listOf("MLB", "Baseball"), color = 0xFF002D72,
            logo = "https://a.espncdn.com/i/teamlogos/leagues/500/mlb.png",
        ),
        League(
            id = "baseball/college-baseball", sport = Sport.BASEBALL, name = "College Baseball",
            shortName = "NCAA BSB", path = "baseball/college-baseball", regions = US, minutes = 185,
            guideWords = listOf("College Baseball", "NCAA Baseball"), color = 0xFF0B5394,
            logo = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-baseball.png",
        ),
        League(
            id = "baseball/world-baseball-classic", sport = Sport.BASEBALL,
            name = "World Baseball Classic", shortName = "WBC",
            path = "baseball/world-baseball-classic", minutes = 200,
            guideWords = listOf("World Baseball Classic", "WBC"), color = 0xFF0B3D91,
            logo = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-baseball.png",
        ),
        League(
            id = "baseball/llb", sport = Sport.BASEBALL, name = "Little League World Series",
            shortName = "LLWS", path = "baseball/llb", minutes = 150,
            guideWords = listOf("Little League"), color = 0xFF2E7D32,
            logo = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-baseball.png",
        ),
        // ---- Football (soccer)
        League(
            id = "soccer/eng.1", sport = Sport.FOOTBALL, name = "Premier League", shortName = "EPL",
            path = "soccer/eng.1", minutes = 115,
            guideWords = listOf("Premier League", "EPL"), color = 0xFF3D195B,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/23.png",
        ),
        League(
            id = "soccer/eng.2", sport = Sport.FOOTBALL, name = "Championship", shortName = "EFL",
            path = "soccer/eng.2", regions = UK, minutes = 115,
            guideWords = listOf("Championship", "EFL"), color = 0xFF1C2C5B,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/24.png",
        ),
        League(
            id = "soccer/eng.fa", sport = Sport.FOOTBALL, name = "FA Cup", shortName = "FA CUP",
            path = "soccer/eng.fa", regions = UK, minutes = 125,
            guideWords = listOf("FA Cup"), color = 0xFF7A0019,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/40.png",
        ),
        League(
            id = "soccer/eng.league_cup", sport = Sport.FOOTBALL, name = "Carabao Cup", shortName = "EFL CUP",
            path = "soccer/eng.league_cup", regions = UK, minutes = 125,
            guideWords = listOf("Carabao Cup", "EFL Cup", "League Cup"), color = 0xFF00563F,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/41.png",
        ),
        League(
            id = "soccer/sco.1", sport = Sport.FOOTBALL, name = "Scottish Premiership", shortName = "SPFL",
            path = "soccer/sco.1", regions = UK, minutes = 115,
            guideWords = listOf("Scottish Premiership", "SPFL"), color = 0xFF2B2D7A,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/45.png",
        ),
        League(
            id = "soccer/uefa.champions", sport = Sport.FOOTBALL, name = "Champions League", shortName = "UCL",
            path = "soccer/uefa.champions", minutes = 120,
            guideWords = listOf("Champions League", "UCL"), color = 0xFF0B1E5B,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/2.png",
        ),
        League(
            id = "soccer/uefa.europa", sport = Sport.FOOTBALL, name = "Europa League", shortName = "UEL",
            path = "soccer/uefa.europa", minutes = 120,
            guideWords = listOf("Europa League", "UEL"), color = 0xFFF58220,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/2310.png",
        ),
        League(
            id = "soccer/esp.1", sport = Sport.FOOTBALL, name = "La Liga", shortName = "LALIGA",
            path = "soccer/esp.1", minutes = 115,
            guideWords = listOf("La Liga", "LaLiga"), color = 0xFFEE8707,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/15.png",
        ),
        League(
            id = "soccer/ita.1", sport = Sport.FOOTBALL, name = "Serie A", shortName = "SERIE A",
            path = "soccer/ita.1", minutes = 115,
            guideWords = listOf("Serie A"), color = 0xFF008FD7,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/12.png",
        ),
        League(
            id = "soccer/ger.1", sport = Sport.FOOTBALL, name = "Bundesliga", shortName = "BUND",
            path = "soccer/ger.1", minutes = 115,
            guideWords = listOf("Bundesliga"), color = 0xFFD20515,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/10.png",
        ),
        League(
            id = "soccer/fra.1", sport = Sport.FOOTBALL, name = "Ligue 1", shortName = "LIGUE 1",
            path = "soccer/fra.1", minutes = 115,
            guideWords = listOf("Ligue 1"), color = 0xFF091C3E,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/9.png",
        ),
        League(
            id = "soccer/usa.1", sport = Sport.FOOTBALL, name = "MLS", shortName = "MLS",
            path = "soccer/usa.1", regions = NA, minutes = 115,
            guideWords = listOf("MLS", "Major League Soccer"), color = 0xFF1B2A41,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/19.png",
        ),
        League(
            id = "soccer/mex.1", sport = Sport.FOOTBALL, name = "Liga MX", shortName = "LIGA MX",
            path = "soccer/mex.1", regions = NA, minutes = 115,
            guideWords = listOf("Liga MX"), color = 0xFF0C2340,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/22.png",
        ),
        League(
            id = "soccer/usa.nwsl", sport = Sport.FOOTBALL, name = "NWSL", shortName = "NWSL",
            path = "soccer/usa.nwsl", regions = NA, minutes = 115,
            guideWords = listOf("NWSL", "National Women's Soccer League"), color = 0xFF0A2240,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/2323.png",
        ),
        League(
            id = "soccer/eng.w.1", sport = Sport.FOOTBALL, name = "Women's Super League",
            shortName = "WSL", path = "soccer/eng.w.1", regions = UK, minutes = 115,
            guideWords = listOf("Women's Super League", "WSL"), color = 0xFF2C1E5A,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/2314.png",
        ),
        League(
            id = "soccer/ned.1", sport = Sport.FOOTBALL, name = "Eredivisie", shortName = "ERE",
            path = "soccer/ned.1", regions = setOf("UK", "IE", "OTHER"), minutes = 115,
            guideWords = listOf("Eredivisie"), color = 0xFFF36C21,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/11.png",
        ),
        League(
            id = "soccer/bra.1", sport = Sport.FOOTBALL, name = "Brasileirao", shortName = "BRASIL",
            path = "soccer/bra.1", regions = setOf("OTHER", "US", "UK", "IE"), minutes = 115,
            guideWords = listOf("Brasileirao", "Brazilian Serie A"), color = 0xFF009C3B,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/85.png",
        ),
        League(
            id = "soccer/fifa.world", sport = Sport.FOOTBALL, name = "World Cup", shortName = "WORLD CUP",
            path = "soccer/fifa.world", minutes = 120,
            guideWords = listOf("World Cup", "FIFA World Cup"), color = 0xFF56042C,
            logo = "https://a.espncdn.com/i/leaguelogos/soccer/500/4.png",
        ),
        // ---- Racing
        League(
            id = "racing/f1", sport = Sport.RACING, name = "Formula 1", shortName = "F1",
            path = "racing/f1", minutes = 120,
            guideWords = listOf("Formula 1", "F1"), color = 0xFFE10600,
            logo = "https://a.espncdn.com/combiner/i?img=/i/teamlogos/leagues/500/f1.png",
        ),
        League(
            id = "racing/nascar-premier", sport = Sport.RACING, name = "NASCAR Cup", shortName = "NASCAR",
            path = "racing/nascar-premier", regions = NA, minutes = 210,
            guideWords = listOf("NASCAR"), color = 0xFFE4002B,
            logo = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-NASCAR.png",
        ),
        League(
            id = "racing/irl", sport = Sport.RACING, name = "IndyCar", shortName = "INDY",
            path = "racing/irl", regions = NA, minutes = 180,
            guideWords = listOf("IndyCar", "Indy 500"), color = 0xFF1E1E1E,
            logo = "https://a.espncdn.com/combiner/i?img=/i/espn/teamlogos/500/indycar_series.png",
        ),
        League(
            id = "racing/nascar-secondary", sport = Sport.RACING, name = "NASCAR O'Reilly Series",
            shortName = "NXS", path = "racing/nascar-secondary", regions = NA, minutes = 150,
            guideWords = listOf("O'Reilly", "Xfinity Series"), color = 0xFF007AC2,
            logo = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-NASCAR.png",
        ),
        League(
            id = "racing/nascar-truck", sport = Sport.RACING, name = "NASCAR Truck Series",
            shortName = "TRUCKS", path = "racing/nascar-truck", regions = NA, minutes = 120,
            guideWords = listOf("Truck Series"), color = 0xFF007AC2,
            logo = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-NASCAR.png",
        ),
        // ---- Golf
        League(
            id = "golf/pga", sport = Sport.GOLF, name = "PGA Tour", shortName = "PGA",
            path = "golf/pga", regions = NA, minutes = 240,
            guideWords = listOf("PGA", "Golf"), color = 0xFF004B87,
            logo = "https://a.espncdn.com/combiner/i?img=/i/teamlogos/leagues/500/pgatour.png",
        ),
        League(
            id = "golf/lpga", sport = Sport.GOLF, name = "LPGA Tour", shortName = "LPGA",
            path = "golf/lpga", minutes = 240,
            guideWords = listOf("LPGA", "Golf"), color = 0xFF00205B,
            logo = "https://a.espncdn.com/combiner/i?img=/i/teamlogos/leagues/500/lpga.png",
        ),
        League(
            id = "golf/eur", sport = Sport.GOLF, name = "DP World Tour", shortName = "DPWT",
            path = "golf/eur", regions = setOf("UK", "IE", "OTHER"), minutes = 240,
            guideWords = listOf("DP World Tour", "European Tour", "Golf"), color = 0xFF0A1F44,
            logo = "https://a.espncdn.com/combiner/i?img=/i/espn/teamlogos/500/european_tour.png",
        ),
        League(
            id = "golf/champions-tour", sport = Sport.GOLF, name = "PGA Tour Champions",
            shortName = "CHAMPS", path = "golf/champions-tour", minutes = 240,
            guideWords = listOf("PGA Tour Champions", "Champions Tour"), color = 0xFF003865,
            logo = "https://a.espncdn.com/combiner/i?img=/i/espn/teamlogos/500/pga_champions_tour.png",
        ),
        // ---- Tennis
        League(
            id = "tennis/atp", sport = Sport.TENNIS, name = "ATP Tour", shortName = "ATP",
            path = "tennis/atp", minutes = 150,
            guideWords = listOf("ATP", "Tennis"), color = 0xFF0A2E5C,
            logo = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-tennis.png",
        ),
        League(
            id = "tennis/wta", sport = Sport.TENNIS, name = "WTA Tour", shortName = "WTA",
            path = "tennis/wta", minutes = 150,
            guideWords = listOf("WTA", "Tennis"), color = 0xFF6A1B9A,
            logo = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-tennis.png",
        ),
        // ---- Combat
        // Boxing has no working scoreboard path on this feed (mma/* answers, boxing/* answers HTTP
        // 400), so it is left out rather than shown as a league that is permanently broken. The MMA
        // promotions are the Fighting group; a card lists its bouts, so the first one opens.
        League(
            id = "mma/ufc", sport = Sport.FIGHTING, name = "UFC", shortName = "UFC",
            path = "mma/ufc", minutes = 300,
            guideWords = listOf("UFC"), color = 0xFFD20A0A,
            logo = "https://a.espncdn.com/i/teamlogos/leagues/500/ufc.png",
        ),
        League(
            id = "mma/pfl", sport = Sport.FIGHTING, name = "PFL", shortName = "PFL",
            path = "mma/pfl", minutes = 240,
            guideWords = listOf("PFL", "Professional Fighters League"), color = 0xFF111111,
            logo = "https://a.espncdn.com/i/teamlogos/leagues/500/pfl.png",
        ),
        League(
            id = "mma/bellator", sport = Sport.FIGHTING, name = "Bellator", shortName = "BELLATOR",
            path = "mma/bellator", minutes = 240,
            guideWords = listOf("Bellator"), color = 0xFF1B1B1B,
            logo = "https://a.espncdn.com/redesign/assets/img/icons/ESPN-icon-mma.png",
        ),
        League(
            id = "mma/rizin", sport = Sport.FIGHTING, name = "RIZIN", shortName = "RIZIN",
            path = "mma/rizin", minutes = 240,
            guideWords = listOf("RIZIN"), color = 0xFF0B1F3A,
            logo = "https://a.espncdn.com/redesign/assets/img/icons/ESPN-icon-mma.png",
        ),
        // ---- Cricket and rugby
        // "cricket/8039" used to sit here and answered HTTP 502 on every call, so the Cricket chip
        // was there and its page was always empty. These are franchise leagues whose feeds work.
        League(
            id = "cricket/8048", sport = Sport.CRICKET, name = "Indian Premier League", shortName = "IPL",
            path = "cricket/8048", minutes = 230,
            guideWords = listOf("IPL", "Indian Premier League"), color = 0xFF19398A,
            logo = "https://a.espncdn.com/i/leaguelogos/cricket/500/8048.png",
        ),
        League(
            id = "cricket/8044", sport = Sport.CRICKET, name = "Big Bash League", shortName = "BBL",
            path = "cricket/8044", minutes = 200,
            guideWords = listOf("Big Bash", "BBL"), color = 0xFF00A3E0,
            logo = "https://a.espncdn.com/i/leaguelogos/cricket/500/8044.png",
        ),
        League(
            id = "cricket/21275", sport = Sport.CRICKET, name = "SA20", shortName = "SA20",
            path = "cricket/21275", regions = setOf("OTHER", "UK", "IE"), minutes = 220,
            guideWords = listOf("SA20"), color = 0xFF003B71,
            logo = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-cricket.png",
        ),
        League(
            id = "rugby/180659", sport = Sport.RUGBY, name = "Rugby Union", shortName = "RUGBY",
            path = "rugby/180659", minutes = 110,
            guideWords = listOf("Rugby"), color = 0xFF0B6E4F,
            logo = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-rugby.png",
        ),
        League(
            id = "rugby/270557", sport = Sport.RUGBY, name = "United Rugby Championship",
            shortName = "URC", path = "rugby/270557", regions = UK + setOf("OTHER"), minutes = 110,
            guideWords = listOf("United Rugby Championship", "URC"), color = 0xFF0E2A47,
            logo = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-rugby.png",
        ),
        // Rugby league shares [Sport.RUGBY]: the page's sport is the group a viewer looks under.
        League(
            id = "rugby-league/3", sport = Sport.RUGBY, name = "NRL", shortName = "NRL",
            path = "rugby-league/3", minutes = 110,
            guideWords = listOf("NRL", "National Rugby League"), color = 0xFF1E2A5E,
            logo = "https://a.espncdn.com/i/teamlogos/leagues/500/nrl.png",
        ),
        // ---- Aussie rules
        League(
            id = "australian-football/afl", sport = Sport.AUSSIE_RULES, name = "AFL", shortName = "AFL",
            path = "australian-football/afl", minutes = 150,
            guideWords = listOf("AFL", "Aussie Rules"), color = 0xFF01285E,
            logo = "https://a.espncdn.com/i/teamlogos/leagues/500/afl.png",
        ),
        // ---- Volleyball and lacrosse (NCAA, the only two this feed carries)
        League(
            id = "volleyball/mens-college-volleyball", sport = Sport.VOLLEYBALL,
            name = "NCAA Men's Volleyball", shortName = "NCAA VB",
            path = "volleyball/mens-college-volleyball", regions = US, minutes = 130,
            guideWords = listOf("College Volleyball", "NCAA Volleyball"), color = 0xFF1B5E20,
            logo = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/sports-volleyball-solid.png",
        ),
        League(
            id = "lacrosse/mens-college-lacrosse", sport = Sport.LACROSSE,
            name = "NCAA Men's Lacrosse", shortName = "NCAA LAX",
            path = "lacrosse/mens-college-lacrosse", regions = US, minutes = 120,
            guideWords = listOf("College Lacrosse", "NCAA Lacrosse"), color = 0xFF0B5394,
            logo = "https://a.espncdn.com/redesign/assets/img/icons/ESPN-icon-lacrosse.png",
        ),
    )

    private val byId: Map<String, League> = leagues.associateBy { it.id }

    fun league(id: String): League? = byId[id]

    /** The leagues offered in [region]; an unknown or empty region offers everything. */
    fun leaguesIn(region: String?): List<League> {
        val key = region?.trim()?.uppercase().orEmpty()
        if (key.isEmpty()) return leagues
        val matching = leagues.filter { key in it.regions }
        // A region nothing is tagged for must not leave the page empty; show everything instead.
        return matching.ifEmpty { leagues }
    }

    /** The sports that at least one of [leagues] covers, in the catalog's own order. */
    fun sportsOf(leagues: List<League>): List<Sport> =
        Sport.entries.filter { sport -> leagues.any { it.sport == sport } }

    /**
     * Words to look for in a channel's name for [league]'s games: its short name and every guide
     * word, lowercased, with the empties dropped.
     */
    fun searchWords(league: League): List<String> =
        (listOf(league.shortName) + league.guideWords)
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
}
