package org.birdlog.app

import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

class BirdPebbleService:BasePebbleListenerService(){
    override suspend fun onMessageReceived(watchappUUID:UUID,data:PebbleDictionary,watch:WatchIdentifier):ReceiveResult{
        if(watchappUUID!=Protocol.UUID_APP)return ReceiveResult.Nack
        val type=Protocol.int(data,Protocol.TYPE)
        BirdEngine.command(applicationContext,type,Protocol.int(data,Protocol.INDEX),Protocol.int(data,Protocol.ACTION),
            Protocol.int(data,Protocol.RUN_ID).toLong(),Protocol.string(data,Protocol.TEXT))
        return ReceiveResult.Ack
    }
    override fun onAppOpened(watchappUUID:UUID,watch:WatchIdentifier){
        if(watchappUUID==Protocol.UUID_APP)BirdEngine.command(applicationContext,10,0,0,0,"")
    }
}
