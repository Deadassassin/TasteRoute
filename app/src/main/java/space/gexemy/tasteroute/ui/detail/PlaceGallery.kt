package space.gexemy.tasteroute.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import space.gexemy.tasteroute.data.Perf

/**
 * Swipeable photo strip. Photos are the fastest way to answer "what am I actually getting", which
 * is why this leads the screen — but a place with none must not look broken, so the empty state is
 * the brand tile plus the ask.
 */
@Composable
fun PlaceGallery(
    photos: List<String>,
    fallbackInitial: String,
    uploading: Boolean,
    onAddPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(240.dp)) {
        if (photos.isEmpty()) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    fallbackInitial,
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { photos.size })
            HorizontalPager(
                state = pagerState,
                // Neighbours stay decoded so a swipe doesn't land on an empty frame; exactly one
                // on either side, because three full-bleed bitmaps is the ceiling on a small heap.
                beyondViewportPageCount = if (Perf.richMotion) 1 else 0,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                AsyncImage(
                    model = photos[page],
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = if (Perf.richMotion) FilterQuality.Medium else FilterQuality.Low,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (photos.size > 1) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(photos.size.coerceAtMost(10)) { index ->
                        Box(
                            Modifier
                                .size(if (index == pagerState.currentPage) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == pagerState.currentPage) Color.White
                                    else Color.White.copy(alpha = 0.45f),
                                ),
                        )
                    }
                }
                Surface(
                    Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.45f),
                ) {
                    Text(
                        "${pagerState.currentPage + 1}/${photos.size}",
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }

        // Scrim only where the back chip sits, so a bright photo doesn't swallow it.
        Box(Modifier.fillMaxWidth().height(72.dp).background(Color.Black.copy(alpha = 0.22f)))

        Surface(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .clickable(enabled = !uploading, onClick = onAddPhoto),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.AddAPhoto,
                    "Add a photo",
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (uploading) "Uploading…" else if (photos.isEmpty()) "Add the first photo" else "Add",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
