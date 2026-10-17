package com.dedhapp3n.pokeldn.frlg

import org.junit.Assert.*
import org.junit.Test

class FrlgSaveRestoreTest {
    @Test fun `safe plan targets next slot invalidates id zero writes id zero last and restores extras`() {
        val input=FrlgSaveTest.syntheticSave(40,41,FrlgSave.Layout.LATIN)
        val chip=FrlgSaveTest.syntheticSave(100,101,FrlgSave.Layout.LATIN)
        val footers=footers(chip); assertEquals(1 to 101L,FrlgSave.chipNewest(footers))
        val plan=FrlgSave.sectorsToWrite(input,footers)
        assertEquals(19,plan.size)
        assertTrue(plan.first().data.all{it==0xff.toByte()})
        val writes=plan.drop(1).take(14);assertEquals(0,FrlgSave.footer(writes.last().data,0).id)
        assertTrue(writes.dropLast(1).all{FrlgSave.footer(it.data,0).id!=0})
        assertEquals((28..31).toList(),plan.takeLast(4).map{it.physicalSector})
        writes.forEach{assertEquals(102L,FrlgSave.footer(it.data,0).counter)}
        plan.takeLast(4).forEach{assertArrayEquals(FrlgSave.sector(input,it.physicalSector),it.data)}
    }

    @Test fun `interrupted plan leaves original newest slot loadable`() {
        val input=FrlgSaveTest.syntheticSave(4,5,FrlgSave.Layout.LATIN)
        val chip=FrlgSaveTest.syntheticSave(100,101,FrlgSave.Layout.LATIN)
        val plan=FrlgSave.sectorsToWrite(input,footers(chip));val interrupted=chip.copyOf()
        plan.take(8).forEach{it.data.copyInto(interrupted,it.physicalSector*FrlgSave.SECTOR_SIZE)}
        val summary=FrlgSave.describe(interrupted);assertTrue(summary.sound);assertEquals(1,summary.newest!!.index);assertEquals(101L,summary.newest!!.counter)
    }

    @Test fun `restore payload patches all upstream LoadGameSave addresses`() {
        val addresses=mapOf("BPRE" to 0x080ddbf4,"BPGE" to 0x080ddbc8,"BPRF" to 0x080ddfd4,"BPGF" to 0x080ddfa8,"BPRD" to 0x080ddf14,"BPGD" to 0x080ddee8,"BPRI" to 0x080ddf14,"BPGI" to 0x080ddee8,"BPRS" to 0x080ddffc,"BPGS" to 0x080ddfd0,"BPRJ" to 0x080ded68,"BPGJ" to 0x080ded3c)
        assertEquals(addresses.keys,FrlgSaveRestorePayload.supportedGameCodes)
        addresses.forEach{(code,address)->val p=FrlgSaveRestorePayload.build(code);assertEquals(620,p.size);assertEquals(address or 1,le32(p,8))};assertEquals("7f412789fe97e4bc5bf86a32e423f074621559bde75aa51ba4bdd605d5c28451",java.security.MessageDigest.getInstance("SHA-256").digest(FrlgSaveRestorePayload.build("BPGD")).joinToString(""){"%02x".format(it)})
    }

    @Test fun `data messages inflate to exact 4096 byte sector`() {
        val raw=ByteArray(4096){((it*29) xor (it/13)).toByte()};val messages=FrlgSaveRestoreEngine.dataMessages(7,raw);val rebuilt=ByteArray(4096)
        messages.forEachIndexed{i,m->assertEquals(0xea0000ff.toInt(),le32(m,0));assertEquals(7,m[5].toInt());assertEquals(if(i==messages.lastIndex)1 else 0,m[6].toInt());FrlgSave.inflate(m.copyOfRange(12,12+u16(m,10)),rebuilt,u16(m,8))}
        assertArrayEquals(raw,rebuilt)
    }

    @Test fun `valid restore verifies every sector then succeeds only on exact load status`() {
        val engine=FrlgSaveRestoreEngine(FrlgSaveTest.syntheticSave(8,9,FrlgSave.Layout.LATIN),"LeafGreen","German")
        runEngine(engine,loadResult=1)
        assertEquals(engine.snapshot().detail,FrlgSaveRestoreEngine.Stage.COMPLETED,engine.snapshot().stage);assertEquals(engine.snapshot().totalSectors,engine.snapshot().sectorsWritten);assertEquals(0L,engine.snapshot().failureMask)
    }

    @Test fun `readback failure aborts and final load failure is not success`() {
        val failed=FrlgSaveRestoreEngine(FrlgSaveTest.syntheticSave(8,9,FrlgSave.Layout.LATIN),"LeafGreen","German")
        runEngine(failed,writeFailureAt=2,loadResult=1);assertEquals(FrlgSaveRestoreEngine.Stage.FAILED,failed.snapshot().stage);assertEquals(failed.snapshot().detail,0x40L,failed.snapshot().failureMask);assertTrue(failed.snapshot().sectorsWritten<failed.snapshot().totalSectors)
        val load=FrlgSaveRestoreEngine(FrlgSaveTest.syntheticSave(8,9,FrlgSave.Layout.LATIN),"LeafGreen","German")
        runEngine(load,loadResult=0);assertEquals(FrlgSaveRestoreEngine.Stage.FAILED,load.snapshot().stage);assertEquals(0,load.snapshot().loadResult)
    }

    @Test fun `wrong layout and malformed save cause zero restore writes`() {
        val japanese=FrlgSaveRestoreEngine(FrlgSaveTest.syntheticSave(8,9,FrlgSave.Layout.JAPANESE),"LeafGreen","German")
        val sent=runEngine(japanese,loadResult=1);assertEquals(japanese.snapshot().detail,FrlgSaveRestoreEngine.Stage.REFUSED,japanese.snapshot().stage);assertFalse(japanese.writesStarted);assertFalse(sent.contains(FrlgSaveRestoreEngine.RAM_SCRIPT));assertFalse(sent.contains(FrlgSaveRestoreEngine.NEWS))
        val malformed=FrlgSaveTest.syntheticSave(8,9,FrlgSave.Layout.LATIN).also{it[10]=(it[10].toInt() xor 1).toByte();it[14*4096+10]=(it[14*4096+10].toInt() xor 1).toByte()}
        val bad=FrlgSaveRestoreEngine(malformed);runEngine(bad,loadResult=1);assertFalse(bad.writesStarted);assertEquals(FrlgSaveRestoreEngine.Stage.REFUSED,bad.snapshot().stage)
    }

    private fun runEngine(e:FrlgSaveRestoreEngine,writeFailureAt:Int?=null,loadResult:Int):List<Int>{val sent=mutableListOf<Int>();var writes=0;var guard=0
        while(e.action !is FrlgSaveRestoreEngine.Action.Done&&guard++<20000)when(val a=e.action){is FrlgSaveRestoreEngine.Action.Send->{sent+=a.ident;e.onSent()};is FrlgSaveRestoreEngine.Action.Receive->when(a.ident){FrlgSaveRestoreEngine.GAME_DATA->e.onReceived(a.ident,gameData());FrlgSaveRestoreEngine.READY_END->e.onReceived(a.ident,byteArrayOf());else->{val s=e.snapshot();val response=when(s.stage){FrlgSaveRestoreEngine.Stage.READING_FOOTERS->footers(FrlgSaveTest.syntheticSave(100,101,FrlgSave.Layout.LATIN));FrlgSaveRestoreEngine.Stage.WRITING->{writes++;ByteArray(4).also{if(writeFailureAt==writes)it[0]=0x40}};FrlgSaveRestoreEngine.Stage.LOADING->ByteArray(8).also{it[4]=loadResult.toByte()};else->ByteArray(4)};e.onReceived(a.ident,response)}};FrlgSaveRestoreEngine.Action.Done->Unit}
        assertTrue("restore engine did not terminate",guard<20000);return sent}
    private fun gameData()=ByteArray(0x64).also{put32(it,0,0x101);put32(it,4,1);put32(it,8,1);put32(it,12,1);put32(it,16,2);"BPGD".encodeToByteArray().copyInto(it,0x5c)}
    private fun footers(save:ByteArray)=ByteArray(28*12).also{o->repeat(28){n->val f=FrlgSave.footer(save,n);put16(o,n*12,f.id);put16(o,n*12+2,f.checksum);put32(o,n*12+4,f.signature);put32(o,n*12+8,f.counter)}}
    private fun put16(d:ByteArray,a:Int,v:Int){d[a]=v.toByte();d[a+1]=(v ushr 8).toByte()};private fun put32(d:ByteArray,a:Int,v:Int)=put32(d,a,v.toLong());private fun put32(d:ByteArray,a:Int,v:Long)=repeat(4){d[a+it]=(v ushr(8*it)).toByte()};private fun le32(d:ByteArray,a:Int)=(0..3).fold(0){v,i->v or((d[a+i].toInt()and 255)shl(8*i))};private fun u16(d:ByteArray,a:Int)=(d[a].toInt()and 255)or((d[a+1].toInt()and 255)shl 8)
}
