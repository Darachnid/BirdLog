package org.birdlog.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Optional Groq text LLM interpretation of a watch-produced STT transcript.
 *  It cannot create or assert a taxon: all outputs are validated against current catalog.
 */
object SpeechResolver {
    data class Match(val code:String,val count:Int,val setTotal:Boolean)
    fun resolve(context:Context,spoken:String,catalog:List<Bird>):Match?{
        val db=BirdStore.get(context);val text=spoken.lowercase(Locale.US).replace('-',' ')
        val normalized= {v:String->v.lowercase(Locale.US).replace('-', ' ').replace(Regex("[^a-z0-9 ]")," ").replace(Regex("\\s+")," ").trim()}
        val candidate=catalog.filter{b->(normalized(b.common).length>=5 && normalized(text).contains(normalized(b.common))) || (b.scientific.isNotBlank() && normalized(text).contains(normalized(b.scientific)))}
            .maxByOrNull{it.common.length}
        if(candidate!=null)return Match(candidate.code,extractCount(text),Regex("\\b(total|set|change)\\b").containsMatchIn(text))
        val key=db.getSetting("groq_key");if(key.isBlank())return null
        val species=catalog.joinToString("; "){"${it.code}:${it.common}"}.take(21000)
        val system="""You parse short birding field voice entries. Only select an EXACT species code from the supplied catalog. If uncertain or none matches respond {"code":"","count":0,"set":false}. Otherwise return a JSON object {"code":"catalogcode","count":positive_integer,"set":boolean}. Phrases 'another' or 'add' imply increment, 'set to' or 'total' imply absolute total. Never fabricate a species and never respond with prose. Catalog: $species"""
        return try {
            val conn=(URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection)
            conn.requestMethod="POST";conn.connectTimeout=10000;conn.readTimeout=18000;conn.doOutput=true
            conn.setRequestProperty("Authorization","Bearer $key")
            conn.setRequestProperty("Content-Type","application/json")
            val body=JSONObject().put("model",db.getSetting("groq_model","llama-3.3-70b-versatile"))
                .put("temperature",0).put("response_format",JSONObject().put("type","json_object"))
                .put("messages",org.json.JSONArray().put(JSONObject().put("role","system").put("content",system))
                    .put(JSONObject().put("role","user").put("content",spoken)))
            conn.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
            if(conn.responseCode !in 200..299){conn.disconnect();return null}
            val raw=conn.inputStream.bufferedReader().use{it.readText()};conn.disconnect()
            val out=JSONObject(JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
            val code=out.optString("code")
            val count=out.optInt("count",0)
            if(code !in catalog.map{it.code} || count !in 1..99999)null else Match(code,count,out.optBoolean("set",false))
        }catch(ex:Exception){Log.w("BirdLog","LLM unavailable",ex);null}
    }
    private fun extractCount(text:String):Int{
        val n=Regex("\\b[0-9]{1,5}\\b").find(text)?.value?.toIntOrNull()
        if(n!=null)return n
        val names=listOf("one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve","thirteen","fourteen","fifteen","twenty")
        return (names.indexOfFirst{Regex("\\b$it\\b").containsMatchIn(text)}).let{if(it<0)1 else if(it==15)20 else it+1}
    }
}
