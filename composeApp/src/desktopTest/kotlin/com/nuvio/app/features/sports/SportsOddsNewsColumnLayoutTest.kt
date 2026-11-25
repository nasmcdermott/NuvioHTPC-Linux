package com.nuvio.app.features.sports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SportsOddsNewsColumnLayoutTest {
    @Test
    fun `news and leaders share a bounded column without measuring twice`() = runComposeUiTest {
        setContent {
            NuvioTheme(darkTheme = true) {
                Box(Modifier.width(1200.dp)) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        // Stand in for the team-stats card: its fixed height is what bounds the
                        // odds/news/leaders column beside it.
                        Column(Modifier.weight(1.3f)) {
                            Spacer(Modifier.height(900.dp))
                        }
                        SportsOddsNewsColumn(
                            event = sampleEvent(),
                            moneyline = MoneylineOdds(provider = "Test", away = "+120", home = "-140"),
                            news = sampleNews(),
                            leaders = sampleLeaders(),
                            injuries = emptyList(),
                            stretchNews = true,
                            modifier = Modifier.weight(0.9f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun sampleEvent(): SportsEvent {
        val away = Team("away", "Away FC", "Away", "AWY")
        val home = Team("home", "Home FC", "Home", "HME")
        return SportsEvent(
            id = "game-1",
            leagueId = "mls",
            startMs = 0L,
            state = GameState.LIVE,
            away = GameSide(away, score = "1"),
            home = GameSide(home, score = "0"),
        )
    }

    private fun sampleNews(): List<TeamNews> = listOf(
        TeamNews(
            teamId = "away",
            articles = listOf(
                NewsArticle(
                    id = "article-1",
                    headline = "Away FC prepares for the next match",
                    description = "The club returns to training with a full squad.",
                ),
            ),
        ),
    )

    private fun sampleLeaders(): List<TeamLeaders> = listOf(
        TeamLeaders(
            teamId = "away",
            categories = listOf(
                TeamLeaderCategory(
                    name = "Goals",
                    leader = SeasonLeader(name = "Away Player", position = "F", value = "3"),
                ),
            ),
        ),
    )
}
