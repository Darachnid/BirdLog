package org.birdlog.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity:Activity(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val navy=Color.rgb(19,53,59)
    private val blue=Color.rgb(0,110,194)
    private val paper=Color.rgb(249,249,237)
    private lateinit var root:LinearLayout
    private lateinit var content:LinearLayout
    private lateinit var status:TextView
    private var pendingExport:String?=null
    private var pendingFilename=""
    private val db by lazy{BirdStore.get(this)}
    private fun d(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);requestPermissionsIfNeeded();build()}
    override fun onResume(){super.onResume();if(::content.isInitialized)render();if(BirdStore.get(this).activeRun()>0 && (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED))startTracking()}
    private fun requestPermissionsIfNeeded(){
        val needed=mutableListOf<String>()
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            needed+=Manifest.permission.ACCESS_FINE_LOCATION
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            needed+=Manifest.permission.ACCESS_COARSE_LOCATION
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            needed+=Manifest.permission.POST_NOTIFICATIONS
        if(needed.isNotEmpty())requestPermissions(needed.toTypedArray(),200)
    }
    private fun label(t:String,size:Float=16f,bold:Boolean=false):TextView=TextView(this).apply{
        text=t;textSize=size;setTextColor(navy);setPadding(d(6),d(6),d(6),d(6))
        if(bold)typeface=Typeface.create("sans-serif",Typeface.BOLD)
    }
    private fun button(t:String,action:()->Unit):Button=Button(this).apply{
        text=t;setAllCaps(false);setTextColor(Color.WHITE);setBackgroundTintList(android.content.res.ColorStateList.valueOf(navy));setOnClickListener{action()}
    }
    private fun build(){
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(paper)}
        val banner=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(d(14),d(16),d(14),d(10));setBackgroundColor(navy)}
        banner.addView(label("◉  BIRDLOG",26f,true).apply{setTextColor(Color.WHITE)})
        banner.addView(label("PEBBLE TIME 2 • FIELD NOTEBOOK",11f).apply{setTextColor(Color.rgb(175,216,223))})
        root.addView(banner)
        val scroll=ScrollView(this).apply{isFillViewport=true}
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(d(14),d(8),d(14),d(20))}
        scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        status=label("Ready",12f).apply{setTextColor(Color.WHITE);setBackgroundColor(navy);setPadding(d(14),d(9),d(14),d(9))}
        root.addView(status)
        setContentView(root);render()
    }
    private fun line(text:String,size:Float=15f,bold:Boolean=false)=content.addView(label(text,size,bold))
    private fun addButton(text:String,run:()->Unit){content.addView(button(text,run),LinearLayout.LayoutParams(-1,d(48)))}
    private fun sep(){content.addView(View(this).apply{setBackgroundColor(Color.rgb(206,214,207))},LinearLayout.LayoutParams(-1,d(1)).apply{topMargin=d(10);bottomMargin=d(10)})}
    private fun notify(t:String){runOnUiThread{if(::status.isInitialized)status.text=t;Toast.makeText(this,t,Toast.LENGTH_SHORT).show()}}
    private fun render(){
        content.removeAllViews()
        val id=BirdEngine.runId(this);val outing=db.run(id)
        if(outing==null){
            line("No active outing",21f,true)
            line("Start an outing here or select New Run on the watch. Saved runs stay on this phone.")
            addButton("＋  Start a birding outing"){askNewRun()}
            addButton("◷  Saved outings"){savedRuns()}
        }else{
            line(outing.title,20f,true)
            val events=db.sightings(id)
            val totals=db.totals(id)
            val duration=((System.currentTimeMillis()-outing.started)/60000).coerceAtLeast(0)
            line("${totals.size} species  ·  ${totals.values.sum()} individuals  ·  ${duration} min")
            line("${db.track(id).size} GPS fixes • ${"%.2f".format(Locale.US,Exporters.distanceMiles(db.track(id)))} mi recorded")
            val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            actions.addView(button("＋  Add bird"){addManual()},LinearLayout.LayoutParams(0,d(50),1f))
            actions.addView(button("🎙  STT"){phoneSpeech()},LinearLayout.LayoutParams(0,d(50),1f))
            content.addView(actions)
            addButton("✓  Save and end outing"){BirdEngine.save(this);render()}
            sep()
            line("OBSERVATIONS",15f,true)
            val birds=db.birds().associateBy{it.code}
            if(totals.isEmpty())line("Nothing logged yet. Speak on the watch, add here, or use its middle button.")
            totals.toList().sortedBy{birds[it.first]?.common}.forEach{ (code,count)->
                val b=birds[code]?:return@forEach
                val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,d(4),0,d(4))}
                row.addView(label(count.toString(),19f,true).apply{setTextColor(blue);gravity=Gravity.CENTER},LinearLayout.LayoutParams(d(42),d(51)))
                val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
                body.addView(label(b.common,15f,true).apply{setPadding(0,0,0,0)})
                body.addView(label(b.scientific,12f).apply{setPadding(0,0,0,0)})
                row.addView(body,LinearLayout.LayoutParams(0,d(51),1f));row.setOnClickListener{editBird(b,count)}
                content.addView(row)
            }
        }
        sep()
        line("EXPORT AND CONFIGURATION",15f,true)
        addButton("↗  Export eBird Record Format CSV"){export("csv")}
        addButton("↗  Export timestamped KML"){export("kml")}
        addButton("↗  Export full event history (JSON)"){export("json")}
        addButton("◎  Update local/seasonal eBird species"){syncCatalog()}
        addButton("⚙  Settings and checklist metadata"){settings()}
        if(outing!=null)addButton("◷  Open another saved outing"){savedRuns()}
        line("Watch: HOLD UP voice • HOLD DOWN map • HOLD SELECT save",12f)
    }
    private fun askNewRun(){
        val field=EditText(this).apply{setSingleLine(true);setText("Birding ${java.time.LocalDate.now()}");selectAll()}
        AlertDialog.Builder(this).setTitle("Outing / location name").setView(field).setNegativeButton("Cancel",null).setPositiveButton("Start") {_,_->
            val title=field.text.toString().trim().ifEmpty{"Birding outing"}
            BirdEngine.start(this,title)
            startTracking();render()
        }.show()
    }
    private fun startTracking(){
        val hasLocationPermission =
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!hasLocationPermission){
            requestPermissionsIfNeeded()
            notify("Enable location access to record a GPS track; bird logging still works")
            return
        }
        if(!lifecycleIsForeground){
            notify("Open BirdLog to start GPS tracking")
            return
        }
        try{startForegroundService(Intent(this,TrackService::class.java))}
        catch(ex:Exception){notify("GPS unavailable: ${ex.message}. Bird logging is still enabled.")}
    }
    private var lifecycleIsForeground=false
    override fun onStart(){super.onStart();lifecycleIsForeground=true}
    override fun onStop(){lifecycleIsForeground=false;super.onStop()}
    private fun savedRuns(){
        val list=db.runs()
        if(list.isEmpty()){notify("No saved runs");return}
        val labels=list.map{r->"${Instant.ofEpochMilli(r.started).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d HH:mm"))} — ${r.title}"}.toTypedArray()
        AlertDialog.Builder(this).setTitle("Previous outings").setItems(labels){_,idx->
            val r=list[idx]
            AlertDialog.Builder(this).setTitle(r.title).setItems(arrayOf("Open / resume", "Export eBird CSV", "Export KML", "Export JSON")){_,which->
                if(which==0){BirdEngine.open(this,r.id);startTracking();render()}
                else exportById(r.id,arrayOf("","csv","kml","json")[which])
            }.show()
        }.show()
    }
    private fun addManual(){
        val t=EditText(this).apply{hint="Species common or scientific name";setSingleLine(true)}
        AlertDialog.Builder(this).setTitle("Find bird").setView(t).setNegativeButton("Cancel",null).setPositiveButton("Next"){_,_->
            val input=t.text.toString().trim()
            val matches=db.birds().filter{it.common.contains(input,true)||it.scientific.contains(input,true)}.take(30)
            if(matches.isEmpty()){notify("No matching taxon. Update taxonomy or try another name.");return@setPositiveButton}
            AlertDialog.Builder(this).setTitle("Select species").setItems(matches.map{"${it.common} — ${it.scientific}"}.toTypedArray()){_,i-> editBird(matches[i],db.totals(BirdEngine.runId(this))[matches[i].code]?:0)}.show()
        }.show()
    }
    private fun editBird(b:Bird,old:Int){
        val id=BirdEngine.runId(this);if(id<=0){notify("Start an outing first");return}
        val field=EditText(this).apply{inputType=InputType.TYPE_CLASS_NUMBER;setText(old.toString());selectAll()}
        AlertDialog.Builder(this).setTitle(b.common).setMessage("Set final total (currently $old)")
            .setView(field).setNegativeButton("Cancel",null).setNeutralButton("Delete") {_,_->
                db.setTotal(id,b,0,"android-delete",BirdEngine.lastLocation(this));BirdEngine.refresh(this);render()
            }.setPositiveButton("Save") {_,_->
                val count=field.text.toString().toIntOrNull()
                if(count==null||count !in 0..99999)notify("Enter count 0–99999")
                else {db.setTotal(id,b,count,"android-edit",BirdEngine.lastLocation(this));BirdEngine.refresh(this);render()}
            }.show()
    }
    private fun phoneSpeech(){
        val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT,"Say a count and bird species")
        }
        try{startActivityForResult(i,301)}catch(e:Exception){notify("No phone speech recognizer available. Use watch dictation.")}
    }
    @Deprecated("Activity result for built-in Android recognition")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==301 && resultCode==RESULT_OK){
            val transcript=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?:return
            progress("Interpreting: $transcript")
            scope.launch(Dispatchers.IO){BirdEngine.voice(this@MainActivity,transcript);withContext(Dispatchers.Main){render()}}
        }
        if(requestCode==401 && resultCode==RESULT_OK){
            val contents=pendingExport;val uri=data?.data
            if(contents!=null&&uri!=null){
                try{contentResolver.openOutputStream(uri)?.use{it.write(contents.toByteArray(Charsets.UTF_8))};notify("Saved $pendingFilename")}
                catch(e:Exception){notify("Export failed: ${e.message}")}
            }
            pendingExport=null
        }
    }
    private fun progress(t:String){status.text=t}
    private fun export(format:String){val id=BirdEngine.runId(this);if(id<=0){savedRuns();return};exportById(id,format)}
    private fun exportById(id:Long,format:String){
        try{
            val r=db.run(id)?:return
            val data=when(format){"csv"->Exporters.exportEbird(r,db.sightings(id),db.track(id));"kml"->Exporters.exportKml(r,db.sightings(id),db.track(id));else->db.exportJson(id)}
            val ext=if(format=="csv")"csv" else if(format=="kml")"kml" else "json"
            val mime=when(ext){"csv"->"text/csv";"kml"->"application/vnd.google-earth.kml+xml";else->"application/json"}
            val name="birdlog-${id}-${java.time.LocalDate.now()}.$ext"
            pendingFilename=name;pendingExport=data
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type=mime;putExtra(Intent.EXTRA_TITLE,name)},401)
        }catch(e:Exception){AlertDialog.Builder(this).setTitle("Export needs attention").setMessage(e.message).setPositiveButton("OK",null).show()}
    }
    private fun syncCatalog(){
        val loc=BirdEngine.lastLocation(this)
        if(loc==null){notify("Wait for recent GPS fix then retry");return}
        progress("Downloading nearby eBird observations and taxonomy…")
        scope.launch(Dispatchers.IO){
            try{
                val result=EbirdCatalog.refresh(this@MainActivity,loc.latitude,loc.longitude)
                BirdEngine.refresh(this@MainActivity)
                withContext(Dispatchers.Main){notify("Loaded ${result.taxonomy} taxa; ${result.recent} recently reported nearby");render()}
            }catch(e:Exception){withContext(Dispatchers.Main){notify("eBird sync: ${e.message}")}}
        }
    }
    private fun settings(){
        val keys=arrayOf("ebird_key","groq_key","groq_model","protocol","country","region","observers","complete","show_all")
        val desc=arrayOf("eBird API key","Groq API key (optional)","Groq model","Protocol","Country code (US)","State code (MA/NH etc.)","Observer count","All species reported?","Show complete catalog on watch?")
        val fields=ArrayList<EditText>()
        val pane=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(d(20),d(7),d(20),d(7))}
        keys.forEachIndexed{idx,key->
            pane.addView(label(desc[idx],13f,true))
            val entry=EditText(this).apply{
                setSingleLine(true);setText(db.getSetting(key,when(key){"groq_model"->"llama-3.3-70b-versatile";"protocol"->"Traveling";"observers"->"1";"complete"->"Y";else->""}))
                if(key=="ebird_key"||key=="groq_key")inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            };fields+=entry;pane.addView(entry)
        }
        val scroll=ScrollView(this).apply{addView(pane)}
        AlertDialog.Builder(this).setTitle("BirdLog configuration").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save") {_,_->
            keys.forEachIndexed{i,k->db.setSetting(k,fields[i].text.toString().trim())}
            val run=BirdEngine.runId(this)
            if(run>0)db.run(run)?.let{o->db.updateRun(run,o.title,db.getSetting("protocol","Traveling"),db.getSetting("country"),db.getSetting("region"),db.getSetting("observers","1").toIntOrNull()?:1,db.getSetting("complete","Y")=="Y")}
            BirdEngine.refresh(this);render();notify("Settings saved")
        }.show()
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
