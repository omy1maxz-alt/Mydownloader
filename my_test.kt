import android.graphics.Color
import androidx.core.graphics.ColorUtils

fun getLuminance(color: Int): Double {
    return ColorUtils.calculateLuminance(color)
}

println("Hello")
