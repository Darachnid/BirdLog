package org.birdlog.app

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import java.util.UUID

object Protocol {
    val UUID_APP: UUID = UUID.fromString("b18d10a6-30b2-4b59-9549-b1a973e18041")
    const val TYPE=0; const val INDEX=1; const val COUNT=2; const val NAME=3
    const val SCI=4; const val LAT=5; const val LON=6; const val TIME=7
    const val TOTAL=8; const val RUN_ID=9; const val TEXT=10; const val PAGE=11
    const val ACTION=12; const val FLAGS=13
    fun int(d: PebbleDictionary, key: Int, fallback:Int=0):Int =
        when(val v=d[key.toUInt()]?.value){ is Int->v; is Short->v.toInt(); is Byte->v.toInt(); is UInt->v.toInt(); is UShort->v.toInt(); is UByte->v.toInt(); else->fallback }
    fun string(d: PebbleDictionary, key:Int):String =
        (d[key.toUInt()]?.value as? String).orEmpty()
    fun pkt(type:Int, vararg args: Pair<Int,Any>):PebbleDictionary {
        val m=mutableMapOf<UInt,PebbleDictionaryItem>(0u to PebbleDictionaryItem.UInt8(type.toUByte()))
        for((key,v) in args) m[key.toUInt()]=when(v){
            is String -> PebbleDictionaryItem.Text(v.take(100))
            is Int -> PebbleDictionaryItem.Int32(v)
            is Long -> PebbleDictionaryItem.Int32(v.toInt())
            else -> throw IllegalArgumentException("Unsupported $key")
        }
        return m
    }
}
