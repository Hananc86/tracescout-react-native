package com.tracescout.mobile.internal.loss

import com.tracescout.mobile.internal.queue.*
import com.tracescout.mobile.queue.*
import com.tracescout.mobile.transport.*
import com.tracescout.rn.LossFamilies
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import javax.crypto.KeyGenerator

/** Runs only with the isolated real controller/PostgreSQL harness; no invented ACKs. */
class CommittedRecoveryIntegrationTest {
    @Test fun splitRestartAndLostLossAckSettleOriginalImmutableClaim() {
        val endpoint = requireNotNull(System.getenv("RECOVERY_TEST_ENDPOINT"))
        val dir = File(requireNotNull(System.getenv("RECOVERY_TEST_DIR")))
        val sid = requireNotNull(System.getenv("RECOVERY_TEST_SID"))
        val project = requireNotNull(System.getenv("RECOVERY_TEST_PROJECT"))
        val files = File(dir,"authority").apply { mkdirs() }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val sync = DurableReplace.DirSync { true }
        fun authority() = (LossAuthority.open(files,sid,null,{key},sync) as LossAuthority.Open.Ok).handle
        fun sink() = AttributionSink.forFilesDir(files,
            { s -> LossAuthority.openExisting(files,s,{key},sync) },
            { s,q -> LossAuthority.openForRemoval(files,s,q,{key},sync) })
        val parser = Uploader("https://example.invalid","unused")
        fun settle(raw:String) {
            val a=requireNotNull(parser.parseLossAck(raw))
            sink().settleLossCheckpoint(sid,a.protocol,a.lossAccountingVersion,a.state,a.sessionId,
                a.acceptedThroughSequence,a.acceptedCounts,a.terminal)
        }
        fun admit(h:LossAuthority.Handle,n:Long) {
            val a=h.beginAdmission("a$n","event$n","mobile_lifecycle") as LossAuthority.Admission.Owned
            assertEquals(n,a.ticket.sequence)
            assertEquals(LossAuthority.Settle.Settled,h.settleAdmitted(a.ticket))
        }
        LossAuthority.closeAll();QueueHome.forgetForTest();F6CheckpointEmitter.resetForTest()
        var h=authority()
        val counts=LossFamilies.emptyCounts()
        for(n in 0L..5L)admit(h,n)
        val initial=CheckpointClaim.identity(sid,5,counts,false)
        assertEquals(LossAuthority.Write.Written,h.requestCheckpoint(initial,initial,5,counts,false))
        settle(File(dir,"baseline.json").readText())
        assertNull(h.pendingCheckpoint())
        for(n in 6L..15L)admit(h,n)
        val id=CheckpointClaim.identity(sid,15,counts,false)
        assertEquals(LossAuthority.Write.Written,h.requestCheckpoint(id,id,15,counts,false))
        val frozen=h.pendingCheckpoint()!!
        fun assertFrozen() {
            val p=authority().pendingCheckpoint()!!
            assertEquals(frozen.checkpointId,p.checkpointId);assertEquals(frozen.throughSequence,p.throughSequence)
            assertEquals(frozen.frozenCounts,p.frozenCounts);assertEquals(frozen.claimsTerminal,p.claimsTerminal)
        }
        val queue=EncryptedEventStore(File(files,"queue.bin"),key,4L*1024*1024,
            EncryptedEventStore.EvictionPolicy.ACTIVE_QUEUE,sync)
        File(dir,"events.jsonl").readLines().forEach { assertTrue(queue.append(it.toByteArray()).stored) }
        var die=true
        var allowRecoveryAck=false
        val requests=ArrayList<String>()
        val realReplies=ArrayList<String>()
        fun pipeline()=DurableUploadPipeline(
            home=QueueHome.of(queue.backingFile()){queue},mutationGate={LossAuthority.mutationFenceFor(files,it)},
            poster={_,bytes ->
                val body=String(bytes);requests.add(body)
                val connection=URI(endpoint).toURL().openConnection() as HttpURLConnection
                connection.requestMethod="POST";connection.doOutput=true
                connection.setRequestProperty("Content-Type","application/json")
                connection.outputStream.use{it.write(bytes)}
                val status=connection.responseCode
                val raw=(if(status<400)connection.inputStream else connection.errorStream).bufferedReader().use{it.readText()}
                connection.disconnect();realReplies.add(raw)
                assertEquals(raw,202,status)
                if(die){die=false;throw DurableUploadPipeline.SimulatedProcessDeath("after_server_commit")}
                val ack=parser.parseAck(raw)
                // Fault: the delivery response's loss block is unavailable to the receiver.
                // The automatic empty carrier also loses its reply until the intended restart.
                // After restart, the empty recovery receives the real server loss ACK.
                Uploader.Result(ResponseAction.SUCCESS,status,null,ack=ack,
                    lossAck=if(allowRecoveryAck && body.contains("\"events\":[]"))parser.parseLossAck(raw) else null)
            },projectId=project,maxRetries=1,appContext=null,backoffMillis={0L},
            results=object:DurableUploadPipeline.ResultSink{override var last:Uploader.Result?=null},
            quarantine=object:DurableUploadPipeline.QuarantineSink{override fun onQuarantined(count:Int,code:String?){error("unexpected quarantine")}},
            sleeper={},inflight=InFlightStore.file(File(files,"inflight.txt")),attribution=sink())
        val first=pipeline()
        first.javaClass.getDeclaredField("resliceMaxRecords").apply{isAccessible=true}.setInt(first,5)
        try{first.upload(queue.readAll().records,sid);fail("crash control did not fire")}catch(_:DurableUploadPipeline.SimulatedProcessDeath){}
        assertEquals(10,queue.readAll().records.size);assertFrozen()
        LossAuthority.closeAll();QueueHome.forgetForTest();F6CheckpointEmitter.resetForTest()
        pipeline().upload(queue.readAll().records,sid)
        assertEquals(requests[0],requests[1]);assertTrue(queue.readAll().records.isEmpty());assertFrozen()
        LossAuthority.closeAll();QueueHome.forgetForTest();F6CheckpointEmitter.resetForTest()
        allowRecoveryAck=true
        pipeline().upload(emptyList(),sid)
        h=authority();assertNull("genuine committed loss ACK settles frozen claim",h.pendingCheckpoint())
        admit(h,16);val successor=CheckpointClaim.identity(sid,16,counts,false)
        assertEquals(LossAuthority.Write.Written,h.requestCheckpoint(successor,successor,16,counts,false))
        settle(realReplies.last())
        assertEquals("duplicate old ACK cannot clear successor",successor,h.pendingCheckpoint()!!.checkpointId)
        File(dir,"wire-requests.jsonl").writeText(requests.joinToString("\n"))
        File(dir,"wire-responses.jsonl").writeText(realReplies.joinToString("\n"))
        LossAuthority.closeAll();QueueHome.forgetForTest()
    }
}
