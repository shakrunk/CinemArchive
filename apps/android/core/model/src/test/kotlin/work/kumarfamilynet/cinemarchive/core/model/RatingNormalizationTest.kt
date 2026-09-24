package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Same numerical examples as web ratingNormalization.test.ts. */
class RatingNormalizationTest {
    private fun title(id: String, rating: Double?, type: MediaType = MediaType.MOVIE) =
        RatingObservation(id, id, type, rating)

    @Test fun `population scores and tied empirical ranks match web`() {
        val result = deriveRatingNormalization(listOf(title("a", 1.0), title("b", 3.0), title("c", 3.0), title("d", 5.0)))
        assertEquals(3.0, result.groups.single().mean!!, 1e-12)
        assertEquals(sqrt(2.0), result.groups.single().deviation!!, 1e-12)
        assertEquals(listOf(87.5, 50.0, 50.0, 12.5), result.rows.map { it.percentile })
        assertEquals(sqrt(2.0), result.rows.first().zScore!!, 1e-12)
        assertEquals(0.0, result.rows.sumOf { it.zScore!! }, 1e-12)
        assertEquals(1.0, result.rows.sumOf { it.zScore!! * it.zScore } / result.rows.size, 1e-12)
    }

    @Test fun `zero and fractional ratings count but missing and invalid ratings do not`() {
        val input = listOf(title("zero", 0.0), title("fraction", 0.5), title("rollup", 3.333),
            title("missing", null), title("nan", Double.NaN), title("inf", Double.POSITIVE_INFINITY),
            title("negative", -1.0), title("high", 6.0))
        assertEquals(listOf("rollup", "fraction", "zero"), deriveRatingNormalization(input).rows.map { it.title.titleId })
    }

    @Test fun `empty singleton and constant baselines do not invent z scores`() {
        val empty = deriveRatingNormalization(emptyList())
        assertTrue(empty.rows.isEmpty())
        assertNull(empty.groups.single().mean)
        for (input in listOf(listOf(title("a", 4.0)), listOf(title("a", 4.0), title("b", 4.0)))) {
            deriveRatingNormalization(input).rows.forEach {
                assertNull(it.zScore)
                assertEquals(50.0, it.percentile, 0.0)
            }
        }
    }

    @Test fun `media baselines separate habits while scope only filters rows`() {
        val input = listOf(title("film-low", 1.0), title("film-high", 3.0),
            title("tv-low", 3.0, MediaType.TV), title("tv-high", 5.0, MediaType.TV))
        val all = deriveRatingNormalization(input)
        val scoped = deriveRatingNormalization(input, scope = "movies")
        assertEquals(4, scoped.groups.single().count)
        assertEquals(all.rows.filter { it.title.type == MediaType.MOVIE }, scoped.rows)
        val media = deriveRatingNormalization(input, RatingBaseline.MEDIA)
        assertEquals(1.0, media.rows.single { it.title.titleId == "film-high" }.zScore!!, 0.0)
        assertEquals(-1.0, media.rows.single { it.title.titleId == "tv-low" }.zScore!!, 0.0)
    }

    @Test fun `library changes recompute without changing original ratings`() {
        val input = listOf(title("a", 1.0), title("b", 5.0))
        assertEquals(3.0, deriveRatingNormalization(input).groups.single().mean!!, 0.0)
        assertNull(deriveRatingNormalization(input.take(1)).rows.single().zScore)
        assertEquals(2.0, deriveRatingNormalization(listOf(input[0], input[1].copy(rating = 3.0))).groups.single().mean!!, 0.0)
        assertEquals(5.0, input[1].rating!!, 0.0)
    }
}
