package com.bryanchi.kite.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A saved moment: what was on screen, which app, and the way back into it. */
data class Card(
    val id: String,
    val createdAt: Long,
    /** 0 = pinned, never expires. */
    val expiresAt: Long,
    val pkg: String?,
    val appLabel: String,
    val title: String,
    val snippet: String,
    val webUri: String?,
    val intentUri: String?,
    val image: String?,
) {
    val pinned get() = expiresAt == 0L

    fun toJson() = JSONObject().apply {
        put("id", id); put("createdAt", createdAt); put("expiresAt", expiresAt)
        put("pkg", pkg ?: ""); put("appLabel", appLabel); put("title", title); put("snippet", snippet)
        put("webUri", webUri ?: ""); put("intentUri", intentUri ?: ""); put("image", image ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject) = Card(
            id = o.getString("id"),
            createdAt = o.optLong("createdAt"),
            expiresAt = o.optLong("expiresAt"),
            pkg = o.optString("pkg").ifBlank { null },
            appLabel = o.optString("appLabel"),
            title = o.optString("title"),
            snippet = o.optString("snippet"),
            webUri = o.optString("webUri").ifBlank { null },
            intentUri = o.optString("intentUri").ifBlank { null },
            image = o.optString("image").ifBlank { null },
        )
    }
}

class ParkingLot(private val context: Context) {
    private val dir = File(context.filesDir, "parking").apply { mkdirs() }
    private val index = File(dir, "cards.json")
    private val _cards = MutableStateFlow(load())
    val cards: StateFlow<List<Card>> = _cards

    companion object {
        const val LIFETIME_MS = 24 * 3_600_000L
        private const val MAX_IMAGE_WIDTH = 720
    }

    private fun load(): List<Card> = runCatching {
        if (!index.exists()) return emptyList()
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map { Card.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun persist(list: List<Card>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        val tmp = File(dir, "cards.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
        _cards.value = list
    }

    fun imageFile(card: Card): File? = card.image?.let { File(dir, it) }?.takeIf { it.exists() }

    @Synchronized
    fun add(
        pkg: String?,
        appLabel: String,
        title: String,
        snippet: String,
        webUri: String?,
        intentUri: String?,
        screenshot: Bitmap?,
    ): Card {
        val id = UUID.randomUUID().toString()
        val image = screenshot?.let { saveBitmap(id, it) }
        val now = System.currentTimeMillis()
        val card = Card(id, now, now + LIFETIME_MS, pkg, appLabel, title.take(200), snippet.take(2000), webUri, intentUri, image)
        persist(listOf(card) + _cards.value)
        return card
    }

    private fun saveBitmap(id: String, src: Bitmap): String? = runCatching {
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val scaled = if (soft.width > MAX_IMAGE_WIDTH) {
            val h = soft.height * MAX_IMAGE_WIDTH / soft.width
            Bitmap.createScaledBitmap(soft, MAX_IMAGE_WIDTH, h, true)
        } else soft
        val name = "$id.jpg"
        File(dir, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        name
    }.getOrNull()

    /** Drops expired, unpinned cards. Call on every home resume. */
    @Synchronized
    fun prune() {
        val now = System.currentTimeMillis()
        val (keep, drop) = _cards.value.partition { it.pinned || it.expiresAt > now }
        if (drop.isEmpty()) return
        drop.forEach { imageFile(it)?.delete() }
        persist(keep)
    }

    @Synchronized
    fun setPinned(id: String, pinned: Boolean) {
        val now = System.currentTimeMillis()
        persist(_cards.value.map {
            if (it.id == id) it.copy(expiresAt = if (pinned) 0L else now + LIFETIME_MS) else it
        })
    }

    @Synchronized
    fun remove(id: String) {
        _cards.value.firstOrNull { it.id == id }?.let { imageFile(it)?.delete() }
        persist(_cards.value.filterNot { it.id == id })
    }

    /** Try, in order: the app's own deep link, the web link inside the app, any handler for the link, the app itself. */
    fun open(context: Context, card: Card): Boolean {
        card.intentUri?.let { raw ->
            val ok = runCatching {
                val i = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
                i.selector = null
                i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                val target = i.component?.packageName ?: i.`package`
                if (card.pkg != null && target != null && target != card.pkg) error("package mismatch")
                if (target == null && card.pkg != null) i.setPackage(card.pkg)
                context.startActivity(i)
            }.isSuccess
            if (ok) return true
        }
        card.webUri?.let { web ->
            val uri = Uri.parse(web)
            if (card.pkg != null && tryStart(context, Intent(Intent.ACTION_VIEW, uri).setPackage(card.pkg))) return true
            if (tryStart(context, Intent(Intent.ACTION_VIEW, uri))) return true
        }
        if (card.pkg != null && AppRepository.launchPackage(context, card.pkg)) return true
        return false
    }

    private fun tryStart(context: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
