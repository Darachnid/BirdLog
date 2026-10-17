package org.birdlog.app

import java.time.Instant
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

fun main(){
    val t=Instant.parse("2026-10-17T15:00:00Z").toEpochMilli()
    val o=Outing(1,t,t+600_000,"Concord Local Patch","Traveling","US","NH",1,true,43.20,-71.50)
    val a=Sighting(1,1,"bkcchi","Black-capped Chickadee","Poecile atricapillus",3,t+45_000,43.20,-71.50,8f,"voice","")
    val b=Sighting(2,1,"bkcchi","Black-capped Chickadee","Poecile atricapillus",-2,t+70_000,43.2001,-71.5001,8f,"watch-edit","")
    val c=Sighting(3,1,"amecro","American Crow","Corvus brachyrhynchos",2,t+80_000,null,null,null,"voice","")
    val fixes=listOf(TrackFix(t,43.20,-71.50,7f),TrackFix(t+120_000,43.201,-71.50,9f))
    val active=Exporters.activeSightings(listOf(a,b,c))
    check(active.size==2 && active[0].delta==1 && active[1].delta==2)
    check(Exporters.activeSightings(listOf(a,a.copy(id=4,delta=-3))).isEmpty())
    val csv=Exporters.exportEbird(o,listOf(a,b,c),fixes)
    check(csv.trim().lines().size==2)
    for(row in csv.trim().lines())check(row.split(',').size==19){row}
    check(csv.contains("Black-capped Chickadee,Poecile,atricapillus,1"))
    val kml=Exporters.exportKml(o,listOf(a,b,c),fixes)
    check(kml.contains("<gx:Track>"));check(kml.contains("INTERPOLATED"));check(kml.contains("<TimeStamp>"))
    check(kml.contains("count_delta"));check(kml.contains("<Placemark>"))
    val builder=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}
    builder.newDocumentBuilder().parse(kml.byteInputStream())
    val inc=Exporters.exportEbird(o.copy(protocol="Incidental"),listOf(a,b,c),emptyList())
    check(inc.contains("Incidental"))
    val p=Exporters.position(c,fixes)!!
    check(p.interpolated && p.latitude>43.2)
    check(Exporters.position(c, listOf(fixes.first(),fixes.last().copy(epoch=t+600_000)))==null)
    check(Exporters.haversine(43.2,-71.5,43.2,-71.5)==0.0)
    var invalid=false;try{Exporters.exportEbird(o.copy(country=""),listOf(a),fixes)}catch(e:IllegalArgumentException){invalid=true}
    check(invalid)
    invalid=false;try{Exporters.exportEbird(o.copy(ended=t+86_400_000),listOf(a),fixes)}catch(e:IllegalArgumentException){invalid=true}
    check(invalid)
    val dir=File(System.getenv("BIRDLOG_SAMPLE_DIR")?:"/tmp")
    dir.mkdirs()
    File(dir,"sample-ebird-record.csv").writeText(csv)
    File(dir,"sample-sightings-track.kml").writeText(kml)
    println("PASS: 14 assertions; CSV column order; time/date validation; correction replay; KML XML parse; GPS interpolation")
}
