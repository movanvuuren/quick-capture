package com.mo.quickcapture.widget

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment as GlanceAlignment
import androidx.glance.layout.Box as GlanceBox
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.text.Text as GlanceText
import androidx.glance.Image as GlanceImage
import com.mo.quickcapture.R

class HabitGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            HabitWidgetContent()
        }
    }

    @Composable
    fun HabitWidgetContent() {
        GlanceBox(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(R.drawable.widget_pill_light))
                .padding(6.dp),
            contentAlignment = GlanceAlignment.Center
        ) {
            GlanceText(
                text = "⭐",
                style = TextStyle(
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Normal
                )
            )
            GlanceImage(
                provider = ImageProvider(R.drawable.ic_stat_quick_capture),
                contentDescription = "Habit status",
                modifier = GlanceModifier.size(22.dp)
            )
        }
    }
}

class HabitGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitGlanceWidget()
}

/**
 * Preview for the widget using standard Compose components.
 * Note: Glance components cannot be rendered directly in a standard Compose Preview,
 * so we simulate the UI using standard Compose UI components for the preview.
 *
 * The ClassNotFoundException for ComposeViewAdapter was resolved by ensuring 
 * 'androidx.compose.ui:ui-tooling' is correctly included in the build dependencies.
 */
@Preview(showBackground = true, widthDp = 100, heightDp = 50)
@Composable
fun HabitWidgetPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .size(100.dp, 50.dp)
                .background(
                    color = Color.LightGray,
                    shape = RoundedCornerShape(25.dp)
                )
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⭐",
                fontSize = 20.sp,
            )
            Image(
                painter = painterResource(id = R.drawable.ic_stat_quick_capture),
                contentDescription = "Habit status",
                modifier = Modifier
                    .size(22.dp)
                    .align(Alignment.BottomEnd)
            )
        }
    }
}
