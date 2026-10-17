package org.birdlog.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

// Counts are sums of immutable change events. Edits append adjustments, never rewrite history.
data class Bird(val code:String,val common:String,val scientific:String,val priority:Int=99)
data class Sighting(val id:Long,val run:Long,val code:String,val common:String,val scientific:String,
    val delta:Int,val epoch:Long,val latitude:Double?,val longitude:Double?,val accuracy:Float?,
    val source:String,val note:String)
data class TrackFix(val epoch:Long,val lat:Double,val lon:Double,val accuracy:Float)
data class Outing(val id:Long,val started:Long,val ended:Long?,val title:String,val protocol:String,
    val country:String,val region:String,val observers:Int,val complete:Boolean,val latitude:Double?,val longitude:Double?)

class BirdStore private constructor(context:Context):SQLiteOpenHelper(context.applicationContext,"birdlog.db",null,2){
    companion object {
        @Volatile private var instance:BirdStore?=null
        fun get(context:Context):BirdStore=instance ?: synchronized(this){instance ?: BirdStore(context).also{instance=it}}
    }
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE runs(id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL, ended INTEGER, title TEXT NOT NULL, protocol TEXT NOT NULL DEFAULT 'Traveling', country TEXT NOT NULL DEFAULT '', region TEXT NOT NULL DEFAULT '', observers INTEGER NOT NULL DEFAULT 1, complete INTEGER NOT NULL DEFAULT 1, lat REAL, lon REAL)")
        db.execSQL("CREATE TABLE birds(code TEXT PRIMARY KEY, common TEXT NOT NULL, scientific TEXT NOT NULL, priority INTEGER NOT NULL DEFAULT 99)")
        db.execSQL("CREATE TABLE sightings(id INTEGER PRIMARY KEY AUTOINCREMENT, run_id INTEGER NOT NULL, code TEXT NOT NULL, common TEXT NOT NULL, scientific TEXT NOT NULL, delta INTEGER NOT NULL, time_ms INTEGER NOT NULL, lat REAL, lon REAL, accuracy REAL, source TEXT NOT NULL, note TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX sighting_run_idx ON sightings(run_id,time_ms)")
        db.execSQL("CREATE TABLE track(id INTEGER PRIMARY KEY AUTOINCREMENT, run_id INTEGER NOT NULL, time_ms INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL NOT NULL)")
        db.execSQL("CREATE INDEX track_run_idx ON track(run_id,time_ms)")
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT)")
        seed(db)
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){
        // v1 to v2 leaves birding records untouched.
        if(oldVersion<2){ db.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT)") }
    }
    private fun seed(db:SQLiteDatabase){
        // Starter catalog only, NOT a claim about geographic or seasonal presence.
        val seed="""amecro|American Crow|Corvus brachyrhynchos
norcar|Northern Cardinal|Cardinalis cardinalis
bkcchi|Black-capped Chickadee|Poecile atricapillus
carwre|Carolina Wren|Thryothorus ludovicianus
blujay|Blue Jay|Cyanocitta cristata
amrobin|American Robin|Turdus migratorius
houspa|House Sparrow|Passer domesticus
eursta|European Starling|Sturnus vulgaris
moudov|Mourning Dove|Zenaida macroura
rocpig|Rock Pigeon (Feral Pigeon)|Columba livia
sonspa|Song Sparrow|Melospiza melodia
whbspa|White-throated Sparrow|Zonotrichia albicollis
doweoo|Downy Woodpecker|Dryobates pubescens
haiwoo|Hairy Woodpecker|Dryobates villosus
rebwoo|Red-bellied Woodpecker|Melanerpes carolinus
norfli|Northern Flicker|Colaptes auratus
whbnut|White-breasted Nuthatch|Sitta carolinensis
rebsap|Red-breasted Nuthatch|Sitta canadensis
tuftit|Tufted Titmouse|Baeolophus bicolor
amgfin|American Goldfinch|Spinus tristis
houfin|House Finch|Haemorhous mexicanus
purfin|Purple Finch|Haemorhous purpureus
darkju|Dark-eyed Junco|Junco hyemalis
rewbla|Red-winged Blackbird|Agelaius phoeniceus
comgra|Common Grackle|Quiscalus quiscula
browco|Brown-headed Cowbird|Molothrus ater
baleag|Bald Eagle|Haliaeetus leucocephalus
ret haw|Red-tailed Hawk|Buteo jamaicensis
coohaw|Cooper's Hawk|Accipiter cooperii
osprey|Osprey|Pandion haliaetus
turvul|Turkey Vulture|Cathartes aura
cangoo|Canada Goose|Branta canadensis
mallar|Mallard|Anas platyrhynchos
woodeu|Wood Duck|Aix sponsa
commer|Common Merganser|Mergus merganser
greher|Great Blue Heron|Ardea herodias
belkin|Belted Kingfisher|Megaceryle alcyon
killde|Killdeer|Charadrius vociferus
comloo|Common Loon|Gavia immer
comter|Common Tern|Sterna hirundo
hergul|Herring Gull|Larus argentatus
rinbil|Ring-billed Gull|Larus delawarensis
barowl|Barred Owl|Strix varia
grehor|Great Horned Owl|Bubo virginianus
rubkin|Ruby-crowned Kinglet|Corthylio calendula
golcro|Golden-crowned Kinglet|Regulus satrapa
bnhvir|Blue-headed Vireo|Vireo solitarius
yerwar|Yellow-rumped Warbler|Setophaga coronata
comyel|Common Yellowthroat|Geothlypis trichas
magwar|Magnolia Warbler|Setophaga magnolia
balori|Baltimore Oriole|Icterus galbula
scatan|Scarlet Tanager|Piranga olivacea
cedwax|Cedar Waxwing|Bombycilla cedrorum
monwar|Mourning Warbler|Geothlypis philadelphia
norpar|Northern Parula|Setophaga americana
""".trimIndent().lines()
        val s=db.compileStatement("INSERT OR IGNORE INTO birds(code,common,scientific,priority) VALUES(?,?,?,?)")
        seed.forEachIndexed { n,line->val p=line.split('|'); if(p.size==3){s.clearBindings();s.bindString(1,p[0]);s.bindString(2,p[1]);s.bindString(3,p[2]);s.bindLong(4,n.toLong());s.executeInsert()} }
        s.close()
    }
    @Synchronized fun getSetting(k:String,def:String=""):String{readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?",arrayOf(k)).use{return if(it.moveToFirst())it.getString(0)else def}}
    @Synchronized fun setSetting(k:String,v:String){writableDatabase.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)",arrayOf(k,v))}
    private var maChecked = false
    @Synchronized private fun ensureMassachusetts(){
        if(maChecked)return
        val db=writableDatabase
        if(getSetting("ma_catalog_v1")=="yes"){maChecked=true;return}
        val names=try{appContext.assets.open("massachusetts_birds.txt").bufferedReader().use{it.readLines()}}catch(e:Exception){
            android.util.Log.e("BirdLog","Missing Massachusetts catalog",e);return
        }
        val present=mutableSetOf<String>()
        db.rawQuery("SELECT common FROM birds",null).use{while(it.moveToNext())present.add(it.getString(0).lowercase(java.util.Locale.US))}
        val statement=db.compileStatement("INSERT OR IGNORE INTO birds(code,common,scientific,priority) VALUES(?,?,?,?)")
        db.beginTransaction()
        try{
            names.forEachIndexed{index,raw->
                val name=raw.trim()
                if(name.isNotEmpty() && present.add(name.lowercase(java.util.Locale.US))){
                    statement.clearBindings()
                    statement.bindString(1,"ma_" + index.toString().padStart(4,'0'))
                    statement.bindString(2,name)
                    statement.bindString(3,"")
                    statement.bindLong(4,100L+index)
                    statement.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        }finally{statement.close();db.endTransaction()}
        setSetting("ma_catalog_v1","yes")
        maChecked=true
    }
    @Synchronized fun birds():List<Bird>{ensureMassachusetts();val out=mutableListOf<Bird>();readableDatabase.rawQuery("SELECT code,common,scientific,priority FROM birds ORDER BY priority,common",null).use{while(it.moveToNext())out+=Bird(it.getString(0),it.getString(1),it.getString(2),it.getInt(3))};return out}
    @Synchronized fun mergeBirds(items:List<Bird>){
        val db=writableDatabase; db.beginTransaction()
        try{val st=db.compileStatement("INSERT OR REPLACE INTO birds(code,common,scientific,priority) VALUES(?,?,?,?)")
            items.forEach { b->st.clearBindings();st.bindString(1,b.code);st.bindString(2,b.common);st.bindString(3,b.scientific);st.bindLong(4,b.priority.toLong());st.executeInsert()};st.close();db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    @Synchronized fun newRun(title:String,lat:Double?=null,lon:Double?=null):Long{
        val started=System.currentTimeMillis();val cv=android.content.ContentValues().apply{
            put("started",started);put("title",title);put("protocol",getSetting("protocol","Traveling"))
            put("country",getSetting("country",""));put("region",getSetting("region",""));put("observers",getSetting("observers","1").toIntOrNull()?:1)
            put("complete",if(getSetting("complete","Y")=="Y")1 else 0)
            if(lat!=null)put("lat",lat);if(lon!=null)put("lon",lon)
        }
        val id=writableDatabase.insertOrThrow("runs",null,cv);setSetting("active_run",id.toString());return id
    }
    @Synchronized fun saveRun(id:Long){writableDatabase.execSQL("UPDATE runs SET ended=COALESCE(ended,?) WHERE id=?",arrayOf(System.currentTimeMillis(),id));if(activeRun()==id)setSetting("active_run","")}
    @Synchronized fun resume(id:Long){writableDatabase.execSQL("UPDATE runs SET ended=NULL WHERE id=?",arrayOf(id));setSetting("active_run",id.toString())}
    @Synchronized fun activeRun():Long=getSetting("active_run").toLongOrNull()?:0L
    @Synchronized fun run(id:Long):Outing?{readableDatabase.rawQuery("SELECT id,started,ended,title,protocol,country,region,observers,complete,lat,lon FROM runs WHERE id=?",arrayOf(id.toString())).use{if(it.moveToFirst())return Outing(it.getLong(0),it.getLong(1),if(it.isNull(2))null else it.getLong(2),it.getString(3),it.getString(4),it.getString(5),it.getString(6),it.getInt(7),it.getInt(8)!=0,if(it.isNull(9))null else it.getDouble(9),if(it.isNull(10))null else it.getDouble(10))};return null}
    @Synchronized fun runs():List<Outing>{val result=mutableListOf<Outing>();readableDatabase.rawQuery("SELECT id FROM runs ORDER BY started DESC",null).use{while(it.moveToNext())run(it.getLong(0))?.let(result::add)};return result}
    @Synchronized fun updateRun(id:Long,title:String,protocol:String,country:String,region:String,observers:Int,complete:Boolean){
        writableDatabase.execSQL("UPDATE runs SET title=?,protocol=?,country=?,region=?,observers=?,complete=? WHERE id=?",arrayOf(title,protocol,country,region,observers,if(complete)1 else 0,id))
    }
    @Synchronized fun sightings(id:Long):List<Sighting>{val a=mutableListOf<Sighting>();readableDatabase.rawQuery("SELECT id,run_id,code,common,scientific,delta,time_ms,lat,lon,accuracy,source,note FROM sightings WHERE run_id=? ORDER BY time_ms,id",arrayOf(id.toString())).use{while(it.moveToNext())a+=Sighting(it.getLong(0),it.getLong(1),it.getString(2),it.getString(3),it.getString(4),it.getInt(5),it.getLong(6),if(it.isNull(7))null else it.getDouble(7),if(it.isNull(8))null else it.getDouble(8),if(it.isNull(9))null else it.getFloat(9),it.getString(10),it.getString(11))};return a}
    @Synchronized fun totals(run:Long):Map<String,Int>{val r=linkedMapOf<String,Int>();readableDatabase.rawQuery("SELECT code,SUM(delta) FROM sightings WHERE run_id=? GROUP BY code HAVING SUM(delta)>0",arrayOf(run.toString())).use{while(it.moveToNext())r[it.getString(0)]=it.getInt(1)};return r}
    @Synchronized fun log(run:Long,bird:Bird,number:Int,source:String,location:Location?=null,note:String=""):Int{
        val previous=totals(run)[bird.code]?:0
        val delta=number.coerceAtLeast(-previous)
        if(delta==0)return previous
        val cv=android.content.ContentValues().apply{
            put("run_id",run);put("code",bird.code);put("common",bird.common);put("scientific",bird.scientific)
            put("delta",delta);put("time_ms",System.currentTimeMillis());put("source",source);put("note",note)
            if(location!=null && System.currentTimeMillis()-location.time<180_000){put("lat",location.latitude);put("lon",location.longitude);put("accuracy",location.accuracy)}
        }
        writableDatabase.insertOrThrow("sightings",null,cv)
        return previous+delta
    }
    @Synchronized fun setTotal(run:Long,bird:Bird,target:Int,source:String,location:Location?=null):Int{
        val previous=totals(run)[bird.code]?:0;return log(run,bird,target.coerceAtLeast(0)-previous,source,location,"Set total to $target")
    }
    @Synchronized fun recordFix(run:Long,location:Location){
        if(location.accuracy>150 || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0)return
        writableDatabase.execSQL("INSERT INTO track(run_id,time_ms,lat,lon,accuracy) VALUES(?,?,?,?,?)",arrayOf(run,location.time,location.latitude,location.longitude,location.accuracy))
        writableDatabase.execSQL("UPDATE runs SET lat=COALESCE(lat,?),lon=COALESCE(lon,?) WHERE id=?",arrayOf(location.latitude,location.longitude,run))
    }
    @Synchronized fun track(run:Long):List<TrackFix>{val a=mutableListOf<TrackFix>();readableDatabase.rawQuery("SELECT time_ms,lat,lon,accuracy FROM track WHERE run_id=? ORDER BY time_ms",arrayOf(run.toString())).use{while(it.moveToNext())a+=TrackFix(it.getLong(0),it.getDouble(1),it.getDouble(2),it.getFloat(3))};return a}
    @Synchronized fun exportJson(id:Long):String{
        val o=run(id)?:error("Missing outing")
        val json=JSONObject().put("format","BirdLog v1").put("run",JSONObject().put("id",o.id).put("started",Instant.ofEpochMilli(o.started).toString()).put("ended",o.ended?.let{Instant.ofEpochMilli(it).toString()}).put("title",o.title).put("protocol",o.protocol).put("country",o.country).put("region",o.region).put("observers",o.observers).put("complete",o.complete))
        val es=JSONArray();sightings(id).forEach{es.put(JSONObject().put("id",it.id).put("code",it.code).put("common",it.common).put("scientific",it.scientific).put("change",it.delta).put("time",Instant.ofEpochMilli(it.epoch).toString()).put("lat",it.latitude).put("lon",it.longitude).put("accuracy_m",it.accuracy).put("source",it.source).put("note",it.note))}
        val tr=JSONArray();track(id).forEach{tr.put(JSONObject().put("time",Instant.ofEpochMilli(it.epoch).toString()).put("lat",it.lat).put("lon",it.lon).put("accuracy_m",it.accuracy))}
        val finalCounts=JSONObject()
        val byCode=birds().associateBy{it.code}
        totals(id).forEach{(code,count)->finalCounts.put(code,JSONObject().put("common",byCode[code]?.common).put("scientific",byCode[code]?.scientific).put("count",count))}
        return json.put("final_counts",finalCounts).put("events",es).put("gps_track",tr).toString(2)
    }
}
