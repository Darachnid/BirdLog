package org.birdlog.app

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import io.rebble.pebblekit2.client.DefaultPebbleSender
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Watch is an event terminal. Android SQLite is the sole source of truth. */
object BirdEngine {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val sendLock=Mutex()
    @Volatile private var selectedRun=0L
    @Volatile private var listCache:List<Bird> = emptyList()
    @Volatile private var lastStatus="Ready"
    @Volatile private var currentPage=0
    fun runId(context:Context):Long=selectedRun.takeIf{it>0}?:BirdStore.get(context).activeRun().also{selectedRun=it}
    fun lastLocation(context:Context):Location?{
        val service=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            val fixes=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)
                .mapNotNull{p->service.getLastKnownLocation(p)}
            fixes.filter{System.currentTimeMillis()-it.time < 180_000}.minByOrNull{it.accuracy}
        }catch(_:SecurityException){null}
    }
    fun start(context:Context,title:String):Long{
        val pos=lastLocation(context)
        val store=BirdStore.get(context)
        val previous=store.activeRun()
        if(previous>0)store.saveRun(previous)
        val id=store.newRun(title,pos?.latitude,pos?.longitude)
        selectedRun=id; refresh(context)
        try { context.startForegroundService(android.content.Intent(context,TrackService::class.java)) }
        catch (e: Exception) { lastStatus="Open Android BirdLog to enable GPS" }
        return id
    }
    fun open(context:Context,id:Long){
        val db=BirdStore.get(context);if(db.run(id)==null)return
        db.resume(id);selectedRun=id;refresh(context)
        try { context.startForegroundService(android.content.Intent(context,TrackService::class.java)) }
        catch (e: Exception) { lastStatus="Open Android BirdLog to enable GPS" }
    }
    fun save(context:Context){
        val db=BirdStore.get(context);val id=runId(context)
        if(id>0)db.saveRun(id)
        context.stopService(android.content.Intent(context,TrackService::class.java))
        selectedRun=0
        scope.launch{status(context,"Saved outing to Android",true)}
    }
    fun refresh(context:Context){scope.launch{listCache=buildSpecies(context);sendPage(context,0)}}
    private suspend fun buildSpecies(context:Context):List<Bird>{
        val db=BirdStore.get(context);val all=db.birds();val rid=runId(context)
        val totals=if(rid>0)db.totals(rid) else emptyMap()
        val recent=db.getSetting("recent_codes").split(',').filter{it.isNotBlank()}.toSet()
        // Rank: recorded birds, nearby recent sightings (up to 30d), then complete taxonomy.
        val filter = all // Full Massachusetts checklist; seasonal/geographic filtering deliberately disabled.
        return filter.sortedWith(compareBy<Bird>({if(totals.containsKey(it.code))0 else if(it.code in recent)1 else 2},{it.priority},{it.common}))
    }
    fun cached(context:Context):List<Bird>{if(listCache.isEmpty())listCache=BirdStore.get(context).birds();return listCache}
    suspend fun send(context:Context,packet:io.rebble.pebblekit2.common.model.PebbleDictionary){
        sendLock.withLock {
            try{
                val sender=DefaultPebbleSender(context.applicationContext)
                try{sender.sendDataToPebble(Protocol.UUID_APP,packet)}finally{sender.close()}
            }catch(ex:Exception){Log.w("BirdLog","Pebble send failed: ${ex.message}")}
        }
    }
    suspend fun status(context:Context,message:String,vibrate:Boolean=false){
        lastStatus=message
        send(context,Protocol.pkt(5,Protocol.NAME to message.take(49),Protocol.FLAGS to if(vibrate)1 else 0))
    }
    suspend fun sendPage(context:Context,page:Int){
        currentPage=page
        val db=BirdStore.get(context);val birds=cached(context);val id=runId(context)
        val o=if(id>0) db.run(id) else null
        send(context,Protocol.pkt(1,Protocol.TOTAL to birds.size,Protocol.RUN_ID to id.toInt(),Protocol.NAME to (o?.title?:"New outing")))
        val totals=if(id>0) db.totals(id) else emptyMap()
        birds.drop(page*12).take(12).forEachIndexed { i,b ->
            send(context,Protocol.pkt(2,Protocol.INDEX to i,Protocol.COUNT to (totals[b.code]?:0),Protocol.NAME to b.common,Protocol.SCI to b.scientific))
        }
    }
    suspend fun sendMap(context:Context){
        val db=BirdStore.get(context);val id=runId(context);if(id<=0)return
        val fixes=db.track(id)
        val withLocation=Exporters.activeSightings(db.sightings(id)).mapNotNull{e->Exporters.position(e,fixes)?.let{p->e to p}}
        withLocation.takeLast(48).forEachIndexed{i,(e,p)->
            val whenAt=Instant.ofEpochMilli(e.epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d HH:mm",Locale.US))
            send(context,Protocol.pkt(3,Protocol.INDEX to i,Protocol.NAME to e.common,Protocol.COUNT to e.delta,
                Protocol.LAT to (p.latitude*1_000_000).toInt(),Protocol.LON to (p.longitude*1_000_000).toInt(),Protocol.TIME to whenAt))
        }
        // 24 evenly spaced track points preserve the route shape on a 200px watch.
        if(fixes.isNotEmpty()) {
            val sample=if(fixes.size<=24)fixes else (0 until 24).map{fixes[(it.toLong()*(fixes.size-1)/23).toInt()]}
            sample.forEachIndexed{i,f->
                send(context,Protocol.pkt(6,Protocol.INDEX to i,Protocol.LAT to (f.lat*1_000_000).toInt(),
                    Protocol.LON to (f.lon*1_000_000).toInt()))
            }
        }
        if(withLocation.isEmpty())status(context,"No geotagged sightings yet")
    }
    suspend fun sendRuns(context:Context){
        val list=BirdStore.get(context).runs().take(8)
        list.forEachIndexed{i,o->
            val day=Instant.ofEpochMilli(o.started).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d HH:mm",Locale.US))
            send(context,Protocol.pkt(4,Protocol.INDEX to i,Protocol.RUN_ID to o.id.toInt(),Protocol.NAME to "$day ${o.title}"))
        }
    }
    private fun chooseBird(text:String,birds:List<Bird>):Bird?{
        val norm={v:String->v.lowercase(Locale.US).replace("chickadees","chickadee").replace("geese","goose")
            .replace(Regex("[^a-z0-9 ]")," ").replace(Regex("\\s+")," ").trim()}
        val t=norm(text)
        return birds.firstOrNull{norm(it.common)==t || (it.scientific.isNotBlank() && norm(it.scientific)==t)} ?:
            birds.filter{(norm(it.common).length>=5 && Regex("(^| )"+Regex.escape(norm(it.common))+"($| )").containsMatchIn(t)) || (it.scientific.isNotBlank() && Regex("(^| )"+Regex.escape(norm(it.scientific))+"($| )").containsMatchIn(t))}.maxByOrNull{it.common.length} ?:
            birds.filter{norm(it.common).contains(t)}.singleOrNull()
    }
    // Conservative parser. An LLM can propose a species/code, but the database validates it.
    private val numbers=mapOf("a" to 1,"an" to 1,"one" to 1,"two" to 2,"three" to 3,"four" to 4,"five" to 5,
        "six" to 6,"seven" to 7,"eight" to 8,"nine" to 9,"ten" to 10,"eleven" to 11,"twelve" to 12,
        "thirteen" to 13,"fourteen" to 14,"fifteen" to 15,"twenty" to 20,"thirty" to 30)
    private fun quantity(text:String):Int{
        val d=Regex("\\b\\d{1,5}\\b").find(text)?.value?.toIntOrNull()
        if(d!=null)return d.coerceIn(0,99999)
        return Regex("\\b[a-z]+\\b").findAll(text.lowercase()).mapNotNull{numbers[it.value]}.firstOrNull()?:1
    }
    suspend fun voice(context:Context,text:String){
        val id=runId(context)
        if(id<=0){status(context,"Start an outing first",true);return}
        val db=BirdStore.get(context);val current=db.birds()
        var b=chooseBird(text,current)
        var n=quantity(text)
        var set=Regex("\\b(set|total|change|make it)\\b",RegexOption.IGNORE_CASE).containsMatchIn(text)
        if(b==null){
            val match=SpeechResolver.resolve(context,text,current)
            if(match!=null){b=current.firstOrNull{it.code==match.code};n=match.count;set=match.setTotal}
        }
        if(b==null){status(context,"Unrecognized bird. Try full name",true);return}
        if(n<1){status(context,"Say a positive bird count",true);return}
        val total=if(set)db.setTotal(id,b,n,"voice",lastLocation(context)) else db.log(id,b,n,"voice",lastLocation(context),text)
        status(context,"$total ${b.common}",true)
        listCache=buildSpecies(context);sendPage(context,currentPage)
    }
    suspend fun change(context:Context,index:Int,action:Int){
        val id=runId(context);val birds=cached(context)
        val b=birds.getOrNull(index)
        if(id<=0||b==null){status(context,"No selected species/outing");return}
        val db=BirdStore.get(context)
        val total=when {
            action>=10000 -> db.setTotal(id,b,action-10000,"watch-edit",lastLocation(context))
            action<0 -> db.setTotal(id,b,0,"watch-delete",lastLocation(context))
            else -> db.log(id,b,action,"watch",lastLocation(context))
        }
        status(context,"$total ${b.common}",true)
        listCache=buildSpecies(context);sendPage(context,currentPage)
    }
    fun command(context:Context,type:Int,index:Int,action:Int,id:Long,text:String){
        scope.launch{
            when(type){
                10 -> { listCache=buildSpecies(context);sendPage(context,0);status(context,lastStatus) }
                11 -> change(context,index,action)
                12 -> voice(context,text)
                13 -> save(context)
                14 -> {open(context,index.toLong());listCache=buildSpecies(context);sendPage(context,0)}
                15 -> sendMap(context)
                16 -> sendRuns(context)
                17 -> sendPage(context,index)
                18 -> {
                    if(action==1 || runId(context)==0L) start(context,"Birding ${java.time.LocalDate.now()}")
                    listCache=buildSpecies(context);sendPage(context,0)
                }
            }
        }
    }
}
