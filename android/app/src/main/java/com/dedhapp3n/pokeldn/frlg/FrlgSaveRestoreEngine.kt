package com.dedhapp3n.pokeldn.frlg

/** Pure port of upstream SaveRestoreServer. It does not own RFU transport or Android UI. */
internal class FrlgSaveRestoreEngine(
    input: ByteArray,
    private val expectedGame: String? = null,
    private val expectedLanguage: String? = null,
) {
    enum class Stage { REQUESTING_GAME_DATA, VALIDATING, READING_FOOTERS, WRITING, LOADING, ENDING, COMPLETED, REFUSED, FAILED }
    sealed interface Action { data class Send(val ident:Int,val payload:ByteArray):Action; data class Receive(val ident:Int):Action; data object Done:Action }
    data class Snapshot(val stage:Stage,val gameCode:String?,val game:String?,val language:String?,val sectorsWritten:Int,val totalSectors:Int,val failureMask:Long?,val loadResult:Int?,val detail:String?)

    val save = FrlgSave.normalize(input)
    val inputSummary = FrlgSave.describe(save)
    var action: Action = Action.Send(CLIENT_SCRIPT, CLIENT_SEND_GAME_DATA.copyOf()); private set
    private var stage=Stage.REQUESTING_GAME_DATA; private var gameCode:String?=null; private var game:String?=null; private var language:String?=null
    private var written=0; private var total=0; private var failureMask:Long?=null; private var loadResult:Int?=null; private var detail:String?="Requesting cartridge data"
    private val sends=ArrayDeque<Action.Send>(); private var afterSends:(()->Unit)?=null; private var receiver:((ByteArray)->Unit)?=null
    private var plan:List<FrlgSave.SectorWrite> = emptyList(); private var planAt=0
    var writesStarted=false; private set
    private var terminalStage: Stage? = null
    val successful get()=stage==Stage.COMPLETED

    fun snapshot()=Snapshot(stage,gameCode,game,language,written,total,failureMask,loadResult,detail)
    fun onSent(){ val sent=action as? Action.Send ?: return fail("onSent called without a send in flight"); if(stage==Stage.REQUESTING_GAME_DATA&&sent.ident==CLIENT_SCRIPT){receive(GAME_DATA,::acceptGameData)}else advanceSend() }
    fun onReceived(ident:Int,payload:ByteArray){ val wanted=(action as? Action.Receive)?.ident ?: return fail("onReceived called without a receive in flight"); if(ident!=wanted)return fail("Expected Mystery Gift ident $wanted, received $ident"); val callback=receiver; receiver=null; try{ callback?.invoke(payload) ?: fail("Unexpected restore response") }catch(e:IllegalArgumentException){ fail(e.message?:"Invalid restore response") } }

    private fun acceptGameData(data:ByteArray){
        if(!validGameData(data)) return fail("Console Mystery Gift game data is invalid")
        val code=data.copyOfRange(0x5c,0x60).toString(Charsets.US_ASCII); if(code !in FrlgSaveRestorePayload.supportedGameCodes)return fail("Unsupported FRLG cartridge: $code")
        val foundGame=if(code.startsWith("BPR"))"FireRed" else "LeafGreen"; val foundLanguage=LANGUAGES[code.lastOrNull()] ?: return fail("Unsupported FRLG cartridge language: $code")
        val version=(u32(data,16) and 15).toInt(); if(version != (if(foundGame=="FireRed")1 else 2) || expectedGame?.let{it!=foundGame}==true || expectedLanguage?.let{it!=foundLanguage}==true)return fail("Cartridge identity mismatch: $foundGame / $foundLanguage / $code")
        gameCode=code; game=foundGame; language=foundLanguage; stage=Stage.VALIDATING
        try { FrlgSave.checkRestorable(save, foundLanguage=="Japanese") } catch(e:FrlgSave.SaveException){ return refuse(e.message?:"Save is incompatible") }
        detail="Reading cartridge save footers"; stage=Stage.READING_FOOTERS
        sendSequence(listOf(Action.Send(CLIENT_SCRIPT, reportingScript(RAM_SCRIPT)),Action.Send(RAM_SCRIPT,FrlgSaveRestorePayload.build(code)))) { receive(RESPONSE,::acceptFooters) }
    }
    private fun acceptFooters(bytes:ByteArray){ plan=FrlgSave.sectorsToWrite(save,bytes); total=plan.size; planAt=0; stage=Stage.WRITING; detail="Restoring sector 1 of $total"; writeNext() }
    private fun writeNext(){ if(planAt>=plan.size)return finishWrites(); val item=plan[planAt]; val messages=dataMessages(item.physicalSector,item.data); val script=sectorScript(messages.size)
        sendSequence(listOf(Action.Send(CLIENT_SCRIPT,script))+messages.map{Action.Send(NEWS,it)}){ writesStarted=true; receive(RESPONSE){acceptWriteStatus(item.physicalSector,it)} }
    }
    private fun acceptWriteStatus(sector:Int,status:ByteArray){ val failed=if(status.size>=4)u32(status,0) else 0xffffffffL; if(failed!=0L){failureMask=failed;detail="Sector $sector readback failed (0x${failed.toString(16).padStart(8,'0')})";stage=Stage.FAILED;return end(NOT_RESTORED,false)}; written++; planAt++; detail="Restored $written of $total sectors";writeNext() }
    private fun finishWrites(){ stage=Stage.LOADING; detail="Loading restored save"; sendSequence(listOf(Action.Send(CLIENT_SCRIPT,reportingScript(NEWS)),Action.Send(NEWS,FINISH))){receive(RESPONSE,::acceptFinish)} }
    private fun acceptFinish(status:ByteArray){ failureMask=if(status.size>=8)u32(status,0)else 0xffffffffL;loadResult=if(status.size>=8)status[4].toInt()and 0xff else 0xff
        if(failureMask==0L&&loadResult==1){stage=Stage.COMPLETED;detail="Save restored and loaded";end(RESTORED,true)}else{stage=Stage.FAILED;detail="Save load failed (mask 0x${failureMask!!.toString(16).padStart(8,'0')}, result $loadResult)";end(NOT_RESTORED,false)} }
    private fun refuse(message:String){stage=Stage.REFUSED;detail=message;end(REFUSED,false)}
    private fun end(message:ByteArray,success:Boolean){terminalStage=stage;stage=Stage.ENDING;sendSequence(listOf(Action.Send(CLIENT_SCRIPT,endScript(success)),Action.Send(DYNAMIC,message))){receive(READY_END){stage=requireNotNull(terminalStage);action=Action.Done}}}
    private fun sendSequence(items:List<Action.Send>,done:()->Unit){sends.clear();sends.addAll(items);afterSends=done;action=sends.removeFirst()}
    private fun advanceSend(){if(sends.isNotEmpty())action=sends.removeFirst()else{val done=afterSends;afterSends=null;done?.invoke()?:fail("Unexpected restore send completion")}}
    private fun receive(ident:Int,done:(ByteArray)->Unit){receiver=done;action=Action.Receive(ident)}
    private fun fail(message:String){stage=Stage.FAILED;detail=message;action=Action.Done;sends.clear();receiver=null}

    companion object {
        const val CLIENT_SCRIPT=16;const val GAME_DATA=17;const val NEWS=18;const val RESPONSE=19;const val READY_END=20;const val DYNAMIC=21;const val RAM_SCRIPT=25
        private val LANGUAGES=mapOf('F' to "French",'E' to "English",'S' to "Spanish",'D' to "German",'I' to "Italian",'J' to "Japanese")
        private val CLIENT_SEND_GAME_DATA=hexR("0800000000000000030000000000000002000000100000000400000000000000")
        private val FINISH=hexR("ff0000ea02")
        private val RESTORED=hexR("cedcd900e7d5ead900dae6e3e100e8dcd900d7e3e1e4e9e8d9e6fedde700dde2ad00cdd5eadde2db00dde800e2e3ebadff")
        private val NOT_RESTORED=hexR("cedcd900e7d5ead900d7e3e9e0d800e2e3e800d6d900ebe6dde8e8d9e2adfec8e3e8dcdde2db00ebd5e700e7d5ead9d8adff")
        private val REFUSED=hexR("cedcdde700e7d5ead900dde700dae3e600d5e2e3e8dcd9e6fed7d5e6e8e6ddd8dbd9ad00c8e3e8dcdde2db00d7dcd5e2dbd9d8adff")
        private fun commands(vararg c:Pair<Int,Int>)=ByteArray(c.size*8).also{out->c.forEachIndexed{i,p->putU32(out,i*8,p.first);putU32(out,i*8+4,p.second)}}
        private fun reportingScript(ident:Int)=commands(2 to ident,14 to 0,21 to 0,3 to 0,2 to CLIENT_SCRIPT,4 to 0)
        private fun sectorScript(count:Int):ByteArray {val c=mutableListOf<Pair<Int,Int>>();repeat(count-1){c+=2 to NEWS;c+=21 to 0};c+=listOf(2 to NEWS,14 to 0,21 to 0,3 to 0,2 to CLIENT_SCRIPT,4 to 0);return commands(*c.toTypedArray())}
        private fun endScript(success:Boolean)=commands(2 to DYNAMIC,12 to 0,20 to 0,1 to if(success)13 else 14)
        internal fun dataMessages(sector:Int,raw:ByteArray):List<ByteArray>{require(raw.size==FrlgSave.SECTOR_SIZE);val tokens=FrlgSave.deflate(raw);val out=mutableListOf<ByteArray>();var i=0;var offset=0
            while(i<tokens.size){val start=offset;val body=ArrayList<Byte>();while(i<tokens.size&&12+body.size+tokens[i].encoded.size<=1024){body.addAll(tokens[i].encoded.toList());offset+=tokens[i].expandedSize;i++};val msg=ByteArray(12+body.size);putU32(msg,0,0xea0000ff.toInt());msg[4]=1;msg[5]=sector.toByte();msg[6]=if(i==tokens.size)1 else 0;putU16(msg,8,start);putU16(msg,10,body.size);body.forEachIndexed{n,b->msg[12+n]=b};out+=msg};return out}
        private fun validGameData(d:ByteArray)=d.size>=0x64&&u32(d,0)==0x101L&&u16(d,4)and 1!=0&&u32(d,8)and 1L!=0L&&u16(d,12)and 1!=0&&u32(d,16)and 15L!=0L
        private fun putU16(d:ByteArray,a:Int,v:Int){d[a]=v.toByte();d[a+1]=(v ushr 8).toByte()};private fun putU32(d:ByteArray,a:Int,v:Int)=repeat(4){d[a+it]=(v ushr(8*it)).toByte()}
        private fun u16(d:ByteArray,a:Int)=(d[a].toInt()and 255)or((d[a+1].toInt()and 255)shl 8);private fun u32(d:ByteArray,a:Int)=(0..3).fold(0L){v,i->v or((d[a+i].toInt()and 255).toLong()shl(8*i))}
    }
}
private fun hexR(v:String)=v.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
