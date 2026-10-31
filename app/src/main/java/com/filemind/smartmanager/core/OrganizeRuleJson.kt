package com.filemind.smartmanager.core

import org.json.JSONArray
import org.json.JSONObject

/** Persists a full organize task: rule + destination tree URI (permission is persisted when the user picks it). */
object OrganizeRuleJson {
    fun toJson(r: OrganizeRule, destUri: String?): String = JSONObject().apply {
        put("types", JSONArray(r.types.map { it.name })); put("primary", r.primary.name)
        r.secondary?.let { put("secondary", it.name) }
        put("dateSource", r.dateSource.name); put("destRoot", r.destRoot)
        r.unknownDateFolder?.let { put("unknown", it) }
        put("months", r.monthStyle.name)
        destUri?.let { put("dest", it) }
    }.toString()

    fun fromJson(s: String): Pair<OrganizeRule, String?>? = try {
        val o = JSONObject(s)
        val ts = o.getJSONArray("types")
        val rule = OrganizeRule(
            types = (0 until ts.length()).map { FileType.valueOf(ts.getString(it)) }.toSet(),
            primary = GroupBy.valueOf(o.getString("primary")),
            secondary = if (o.has("secondary")) GroupBy.valueOf(o.getString("secondary")) else null,
            dateSource = DateSource.valueOf(o.getString("dateSource")),
            destRoot = o.optString("destRoot"),
            unknownDateFolder = if (o.has("unknown")) o.getString("unknown") else null,
            monthStyle = MonthStyle.fromName(o.optString("months")),
        )
        rule to (if (o.has("dest")) o.getString("dest") else null)
    } catch (e: Exception) { null }
}
