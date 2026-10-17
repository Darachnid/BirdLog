package org.birdlog.app

data class Sighting(val id:Long,val run:Long,val code:String,val common:String,val scientific:String,
    val delta:Int,val epoch:Long,val latitude:Double?,val longitude:Double?,val accuracy:Float?,
    val source:String,val note:String)
data class TrackFix(val epoch:Long,val lat:Double,val lon:Double,val accuracy:Float)
data class Outing(val id:Long,val started:Long,val ended:Long?,val title:String,val protocol:String,
    val country:String,val region:String,val observers:Int,val complete:Boolean,val latitude:Double?,val longitude:Double?)
