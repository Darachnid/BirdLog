package org.birdlog.app

import android.Manifest
import android.app.*
import android.content.pm.PackageManager
import android.content.Intent
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.util.Log

/** Explicitly started foreground service; no covert/background tracking before the user starts an outing. */
class TrackService:Service(),LocationListener {
    private lateinit var locations:LocationManager
    private var enabled=false
    private var id=0L
    override fun onCreate(){super.onCreate();locations=getSystemService(Context.LOCATION_SERVICE) as LocationManager}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action=="STOP"){stopSelf();return START_NOT_STICKY}
        id=BirdStore.get(this).activeRun()
        if(id==0L){stopSelf();return START_NOT_STICKY}
        val chan="birdlog-track";val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(chan,"BirdLog tracking",NotificationManager.IMPORTANCE_LOW))
        val pi=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,TrackService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val notice=Notification.Builder(this,chan).setSmallIcon(org.birdlog.app.R.drawable.ic_bird)
            .setContentTitle("BirdLog: outing in progress").setContentText("Recording GPS route and sighting locations")
            .setContentIntent(pi).addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,R.drawable.ic_bird),"Stop GPS",stop).build()).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(23,notice,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(23,notice)
        try{
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,10000L,8f,this,Looper.getMainLooper());enabled=true
            }
            if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED){
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,30000L,15f,this,Looper.getMainLooper());enabled=true
            }
        }catch(t:Exception){Log.e("BirdLog","GPS error",t)}
        return START_STICKY
    }
    override fun onLocationChanged(location:Location){
        val actual=BirdStore.get(this).activeRun()
        if(actual>0 && location.accuracy<=100f && System.currentTimeMillis()-location.time<=120000)
            BirdStore.get(this).recordFix(actual,location)
    }
    override fun onDestroy(){if(enabled)locations.removeUpdates(this);super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}
