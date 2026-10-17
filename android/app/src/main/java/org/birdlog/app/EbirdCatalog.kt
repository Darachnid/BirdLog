package org.birdlog.app

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** eBird API reference reads (not direct submission). Nearby observations are a seasonal proxy. */
object EbirdCatalog {
    data class SyncResult(val taxonomy:Int,val recent:Int)
    private fun get(url:String,key:String):String {
        val conn=(URL(url).openConnection() as HttpURLConnection).apply{
            connectTimeout=12000;readTimeout=20000;requestMethod="GET"
            setRequestProperty("X-eBirdApiToken",key)
            setRequestProperty("Accept","application/json")
        }
        try{
            if(conn.responseCode !in 200..299)throw IllegalStateException("eBird API returned ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use{it.readText()}
        }finally{conn.disconnect()}
    }
    fun refresh(context:Context,latitude:Double,longitude:Double):SyncResult{
        val db=BirdStore.get(context);val key=db.getSetting("ebird_key")
        require(key.isNotBlank()){ "Enter an eBird API key in settings." }
        val all=JSONArray(get("https://api.ebird.org/v2/ref/taxonomy/ebird?fmt=json&cat=species",key))
        val new=mutableListOf<Bird>()
        for(i in 0 until all.length()){
            val j=all.getJSONObject(i)
            val code=j.optString("speciesCode")
            val common=j.optString("comName")
            val sci=j.optString("sciName")
            if(code.isNotBlank()&&common.isNotBlank()&&sci.isNotBlank())new+=Bird(code,common,sci,i+200)
        }
        // Refresh catalog, retaining previously recorded common/scientific names in event history.
        db.mergeBirds(new)
        val recent=JSONArray(get("https://api.ebird.org/v2/data/obs/geo/recent?lat=$latitude&lng=$longitude&dist=25&back=30&maxResults=10000",key))
        val ids=mutableSetOf<String>()
        for(i in 0 until recent.length())recent.getJSONObject(i).optString("speciesCode").takeIf{it.isNotBlank()}?.let(ids::add)
        db.setSetting("recent_codes",ids.joinToString(","))
        db.setSetting("recent_updated",java.time.Instant.now().toString())
        return SyncResult(new.size,ids.size)
    }
}
