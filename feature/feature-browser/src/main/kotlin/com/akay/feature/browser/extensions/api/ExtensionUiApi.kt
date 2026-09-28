package com.akay.feature.browser.extensions.api

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.akay.feature.browser.extensions.Extension
import com.akay.feature.browser.extensions.ExtensionRuntime
import com.akay.feature.browser.extensions.resourceFile
import com.akay.feature.browser.extensions.strings
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ExtensionUiApi(private val context: Context, private val runtime: ExtensionRuntime) {
    val revision = MutableStateFlow(0)
    private val actions = mutableMapOf<String, JSONObject>()
    private val menus = mutableMapOf<String, MutableMap<String, JSONObject>>()
    private val notifications = mutableMapOf<String, MutableSet<String>>()
    fun action(ext: Extension): JSONObject = actions.getOrPut(ext.id) { JSONObject().put("title", ext.manifest.name).put("popup", ext.manifest.popup ?: "").put("badgeText", "") }
    fun menus(id: String) = menus[id]?.values?.toList().orEmpty()
    fun call(ext: Extension, method: String, args: JSONArray): Any {
        val namespace = method.substringBefore('.'); val name = method.substringAfter('.')
        val details = args.optJSONObject(0) ?: JSONObject()
        val result: Any = when (namespace) {
            "action" -> {
                check(!details.has("tabId")) { "Per-tab action state is unsupported" }
                val state = action(ext)
                when (name) {
                    "setBadgeText" -> state.put("badgeText", details.getString("text"))
                    "setTitle" -> state.put("title", details.getString("title"))
                    "setPopup" -> { val path = details.getString("popup"); if (path.isNotEmpty()) check(resourceFile(ext.root, path).isFile); state.put("popup", path) }
                    "setBadgeBackgroundColor" -> state.put("badgeColor", details.get("color"))
                    "getBadgeText" -> return state.getString("badgeText")
                    "getTitle" -> return state.getString("title")
                    "getPopup" -> return state.getString("popup")
                    else -> error("Unsupported action API")
                }; JSONObject.NULL
            }
            "commands" -> {
                val commands = ext.manifest.json.optJSONObject("commands") ?: JSONObject()
                JSONArray(commands.keys().asSequence().map { key -> JSONObject().put("name", key).put("description", commands.getJSONObject(key).optString("description")).put("shortcut", "") }.toList())
            }
            "contextMenus" -> {
                val entries = menus.getOrPut(ext.id) { mutableMapOf() }
                when (name) {
                    "create" -> {
                        check(entries.size < 32) { "At most 32 page actions per extension" }
                        check(details.optString("type", "normal") == "normal" && !details.has("parentId") && !details.has("onclick") && !details.has("documentUrlPatterns") && !details.has("targetUrlPatterns")) { "Only flat page menu items are supported" }
                        check(details.optJSONArray("contexts").strings().all { it == "page" || it == "all" }) { "Only page context menus are supported" }
                        val id = details.getString("id"); check(id !in entries) { "Duplicate menu ID" }
                        check(details.optString("title").isNotBlank()); entries[id] = JSONObject(details.toString()); id
                    }
                    "update" -> { val entry = entries[args.getString(0)] ?: error("Menu item not found"); val changes = args.getJSONObject(1)
                        check(changes.keys().asSequence().all { it in setOf("title", "enabled", "visible") }) { "Unsupported menu update" }
                        changes.keys().forEach { entry.put(it, changes.get(it)) }; JSONObject.NULL }
                    "remove" -> { entries.remove(args.getString(0)); JSONObject.NULL }
                    "removeAll" -> { entries.clear(); JSONObject.NULL }
                    else -> error("Unsupported contextMenus API")
                }
            }
            "notifications" -> {
                val manager = NotificationManagerCompat.from(context)
                val id = if (args.opt(0) is String) args.getString(0) else UUID.randomUUID().toString()
                val tag = "extension:${ext.id}:$id"
                if (name == "clear") { manager.cancel(tag, 1); notifications[ext.id]?.remove(id) == true }
                else {
                    check(manager.areNotificationsEnabled()) { "Enable Android notifications for AxBrowser first" }
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        error("Android notification permission is not granted")
                    }
                    val options = if (args.opt(0) is String) args.getJSONObject(1) else details
                    check(options.optString("type", "basic") == "basic" && !options.has("buttons")) { "Only basic notifications are supported" }
                    val channel = "ax_extensions"
                    context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "Browser extensions", NotificationManager.IMPORTANCE_DEFAULT))
                    manager.notify(tag, 1, NotificationCompat.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(options.getString("title")).setContentText(options.getString("message")).setSubText(ext.manifest.name).setAutoCancel(true).build())
                    notifications.getOrPut(ext.id) { mutableSetOf() }.add(id); id
                }
            }
            else -> error("Unsupported UI API")
        }
        revision.value += 1
        return result
    }
    fun clear(id: String) {
        actions.remove(id); menus.remove(id)
        notifications.remove(id)?.forEach { NotificationManagerCompat.from(context).cancel("extension:$id:$it", 1) }
        revision.value += 1
    }
}
