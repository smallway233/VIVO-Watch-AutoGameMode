package top.smallway.gamewatch

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GameSession(val name: String, val startTime: Long, val duration: Long)

fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    val hours = (durationMs / (1000 * 60 * 60))
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}

fun formatTime(timeMs: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timeMs))
}

fun loadHistory(context: Context): List<GameSession> {
    val prefs = context.getSharedPreferences("game_history", Context.MODE_PRIVATE)
    val historyJson = prefs.getString("history_list", "[]")
    val list = mutableListOf<GameSession>()
    try {
        val jsonArray = JSONArray(historyJson)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(GameSession(
                obj.getString("name"),
                obj.getLong("startTime"),
                obj.getLong("duration")
            ))
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}
