package com.jarvis.app.music

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.app.JarvisNotificationListener

/**
 * 原生面板「音乐」区的数据层——必须是真的，不能是空架子：
 * 曲库扫本地文件（MediaStore），正在放什么读媒体会话（Salt Player 的），
 * 播放/暂停/上下曲发系统媒体键（Salt Player 接着干活），点歌单里的歌用
 * ACTION_VIEW 把那首歌交给 Salt Player 播。
 *
 * 权限：
 * - 曲库要 READ_MEDIA_AUDIO（Android 13+ 运行时权限；没给就返回空列表，面板上如实说）。
 * - 当前曲目要“通知使用权”（MediaSessionManager.getActiveSessions 靠 JarvisNotificationListener
 *   这个通知监听服务；没授权就返回 null，面板上显示“未在播放/未授权”，不编一首假歌）。
 */
object MusicPanel {

    data class Song(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val uri: Uri
    )

    data class NowPlaying(
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    /** 扫本地曲库：只要音乐文件（IS_MUSIC=1），按标题排，最多 200 首。没权限返回空列表。 */
    fun scanSongs(ctx: Context): List<Song> {
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_MEDIA_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val out = ArrayList<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext() && out.size < 200) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol).orEmpty()
                    if (title.isBlank()) continue
                    out.add(
                        Song(
                            id = id,
                            title = title,
                            artist = c.getString(artistCol).orEmpty().ifBlank { "未知歌手" },
                            album = c.getString(albumCol).orEmpty(),
                            durationMs = c.getLong(durCol),
                            uri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                            )
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    /** 当前在放什么：读活跃媒体会话（Salt Player 的）。没有会话/没授权返回 null。 */
    fun nowPlaying(ctx: Context): NowPlaying? {
        try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(ctx, JarvisNotificationListener::class.java)
            val controllers: List<MediaController> = msm.getActiveSessions(component)
            val ctrl = controllers.firstOrNull { it.playbackState != null } ?: return null
            val meta = ctrl.metadata ?: return null
            val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            if (title.isBlank()) return null
            val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            val state = ctrl.playbackState
            val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
            return NowPlaying(
                title = title,
                artist = artist.ifBlank { "未知歌手" },
                isPlaying = state?.state == PlaybackState.STATE_PLAYING,
                positionMs = state?.position ?: 0L,
                durationMs = if (duration > 0) duration else 0L
            )
        } catch (_: Exception) {
            return null
        }
    }

    /** 发一对媒体键（按下 + 抬起），走 AudioManager.dispatchMediaKeyEvent，不需要特殊权限。 */
    fun dispatchMediaKey(ctx: Context, keyCode: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (_: Exception) {
        }
    }

    fun togglePlayPause(ctx: Context, isPlaying: Boolean) {
        dispatchMediaKey(
            ctx,
            if (isPlaying) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY
        )
    }

    fun next(ctx: Context) = dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous(ctx: Context) = dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    /** 点歌单里的歌：把这首歌交给系统默认音乐应用播（Salt Player）。 */
    fun playSong(ctx: Context, song: Song): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(song.uri, "audio/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
