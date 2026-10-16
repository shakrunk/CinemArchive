package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle

private val FilmStock = Color(0xFF262420)
private val FilmInk = Color(0xFFF3EAD9)

/** Rails belong to each frame, so perforations move with the artwork during a swipe. */
@Composable
internal fun DiscoverFilmStrip(
    titles: List<TrendingTitle>,
    tag: String,
    onOpen: (TrendingTitle) -> Unit,
    onAdd: (TrendingTitle) -> Unit,
) {
    LazyRow(Modifier.fillMaxWidth().testTag("discover-film-strip-$tag")) {
        items(titles, key = { title -> title.catalogKey }) { title ->
            Column(Modifier.width(168.dp).testTag("$tag-${title.catalogKey}")) {
                FilmPerforations()
                Column(Modifier.background(FilmStock).padding(horizontal = 5.dp)
                    .background(Color(0xFF0A0A0A)).padding(3.dp)) {
                    Box(Modifier.semantics { contentDescription = "Preview ${title.title}" }) {
                        PosterSurface(tint = tintForKey(title.tmdbId.toString()), imageUrl = title.posterUrl,
                            cornerRadius = 0.dp, scrimStop = 0.98f, onClick = { onOpen(title) })
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 8.dp)) {
                        Text(title.title, style = MaterialTheme.typography.titleSmall, color = FilmInk,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(title) })
                        Row(Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("${title.year ?: "—"} · ${if (title.type == MediaType.TV) "TV" else "Movie"}",
                                style = MaterialTheme.typography.labelSmall, color = FilmInk.copy(alpha = 0.7f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onAdd(title) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.Add, "Add ${title.title} to library", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                FilmPerforations()
            }
        }
    }
}

/** Draw only the stock: the 8dp holes reveal the actual surface underneath. */
@Composable
private fun FilmPerforations() {
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val edge = 3.dp.toPx()
        val hole = 8.dp.toPx()
        val pitch = size.width / 5
        drawRect(FilmStock, size = Size(size.width, edge))
        drawRect(FilmStock, topLeft = Offset(0f, size.height - edge), size = Size(size.width, edge))
        repeat(5) { index ->
            val start = index * pitch
            val inset = (pitch - hole) / 2
            drawRect(FilmStock, Offset(start, edge), Size(inset, hole))
            drawRect(FilmStock, Offset(start + inset + hole, edge), Size(inset, hole))
        }
    }
}
