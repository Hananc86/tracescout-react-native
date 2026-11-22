package com.tracescout.mobile.internal.loss

import com.tracescout.mobile.internal.queue.AttributionSink
import com.tracescout.mobile.internal.queue.DurableUploadPipeline
import com.tracescout.mobile.internal.queue.*
import com.tracescout.mobile.queue.DurableReplace
import com.tracescout.mobile.queue.EncryptedEventStore
import com.tracescout.mobile.queue.QueueHome
import org.junit.rules.TemporaryFolder
import com.tracescout.mobile.transport.ResponseAction
import com.tracescout.mobile.transport.Uploader
import com.tracescout.rn.LossFamilies
import com.tracescout.rn.LossLedger
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real encrypted queue/authority integration; HTTP replies are controlled test inputs,
 * not evidence that a deployed server accepts a carrier. */
class Pr3PipelineRegressionTest {
    @get:Rule val tempDirs = TemporaryFolder()

    private fun snapshot(p: LossAuthority.Pending?) = p?.let {
        listOf(it.checkpointId, it.throughSequence, it.frozenCounts.toSortedMap(), it.claimsTerminal)
    }
    private val sid = "ts_1700000000000_aaaaaaaaaaaa"
    private val other = "ts_1700000000000_bbbbbbbbbbbb"
    private val yes = DurableReplace.DirSync { true }
    private val k: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val fam = LossFamilies.lossFamilyForEventType("mobile_view_hierarchy")!!

    @Before fun fresh() { LossAuthority.closeAll(); QueueHome.forgetForTest(); F6CheckpointEmitter.resetForTest() }
    @After fun done() { LossAuthority.closeAll(); F6CheckpointEmitter.resetForTest() }

    private fun tmp(): File = tempDirs.newFolder()

    private fun authority(f: File, s: String = sid): LossAuthority.Handle =
        when (val o = LossAuthority.open(f, s, null, { k }, yes)) {
            is LossAuthority.Open.Ok -> o.handle
            is LossAuthority.Open.Unavailable -> throw AssertionError("open: ${o.reason}")
        }

    private fun admit(h: LossAuthority.Handle, id: String): Long {
        val a = h.beginAdmission(id, "e$id", "mobile_view_hierarchy") as LossAuthority.Admission.Owned
        assertEquals(LossAuthority.Settle.Settled, h.settleAdmitted(a.ticket))
        return a.ticket.sequence
    }

    private fun lose(h: LossAuthority.Handle, id: String): Long {
        val a = h.beginAdmission(id, "e$id", "mobile_view_hierarchy") as LossAuthority.Admission.Owned
        assertEquals(LossAuthority.Settle.Settled, h.settleLost(a.ticket, LossAuthority.Cause.APPEND_REFUSED))
        return a.ticket.sequence
    }

    private fun counts(vararg p: Pair<String, Long>): Map<String, Long> =
        LossFamilies.emptyCounts().toMutableMap().also { m -> p.forEach { (a, b) -> m[a] = b } }

    private fun fingerprint(f: File, s: String = sid): String {
        val dir = EncryptedAuthorityStore.dirFor(f, s) ?: return "absent"
        val md = java.security.MessageDigest.getInstance("SHA-256")
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
            md.update(it.path.toByteArray(Charsets.UTF_8))
            md.update(it.readBytes())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun rec(seq: Long, s: String = sid) =
        """{"eventId":"e$s$seq","eventType":"mobile_view_hierarchy","sessionId":"$s","sequence":$seq,"timestamp":1,"monotonicNanos":1}"""
            .toByteArray()

    private fun queue(f: File): EncryptedEventStore = EncryptedEventStore(
        File(f, "queue.bin"), k, 4L * 1024 * 1024, EncryptedEventStore.EvictionPolicy.ACTIVE_QUEUE, yes)

    private class Sink : DurableUploadPipeline.ResultSink { override var last: Uploader.Result? = null }
    private class Quar : DurableUploadPipeline.QuarantineSink {
        var count = 0
        override fun onQuarantined(count: Int, code: String?) { this.count += count }
    }

    private fun pipeline(
        f: File,
        q: EncryptedEventStore,
        bodies: MutableList<String>,
        respond: (String) -> Uploader.Result,
    ): DurableUploadPipeline = DurableUploadPipeline(
        home = QueueHome.of(q.backingFile()) { q },
        mutationGate = { slice -> LossAuthority.mutationFenceFor(f, slice) },
        poster = { _, body ->
            val text = String(body, Charsets.UTF_8); bodies.add(text); respond(text)
        },
        projectId = "p", maxRetries = 1, appContext = null, backoffMillis = { 0L },
        results = Sink(), quarantine = Quar(), sleeper = {},
        inflight = InFlightStore.file(File(f, "inflight.txt")),
        attribution = AttributionSink.forFilesDir(f,
            { s -> LossAuthority.openExisting(f, s, { k }, yes) },
            { s, qs -> LossAuthority.openForRemoval(f, s, qs, { k }, yes) }),
    )

    private fun lossBlock(
        through: Long,
        c: Map<String, Long>,
        state: String = "accepted",
        terminal: Boolean = false,
        session: String = sid,
    ): String =
        "{\"protocol\":\"loss-checkpoint-ack-1\",\"state\":\"$state\",\"sessionId\":\"$session\"," +
            "\"lossAccountingVersion\":1,\"acceptedThroughSequence\":\"$through\",\"acceptedCounts\":{" +
            c.entries.joinToString(",") { "\"${it.key}\":${it.value}" } + "},\"terminal\":$terminal}"

    private val up = Uploader(baseUrl = "https://example.invalid", ingestKey = "k")

    private fun ok(block: String?): Uploader.Result = Uploader.Result(
        ResponseAction.SUCCESS, 202, null,
        lossAck = block?.let { up.parseLossAck("{\"lossCheckpointAck\":$it}") })

    private fun chunkOk(body: String): Uploader.Result {
        val s = Regex("\"sessionId\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val c = Regex("\"chunkId\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val seqs = Regex("\"sequence\":(\\d+)").findAll(body).map { it.groupValues[1].toLong() }.toList()
        return Uploader.Result(ResponseAction.SUCCESS, 202, null,
            ack = Uploader.Ack("mobile-ack-1", "accepted", s, c,
                seqs.min().toString(), seqs.max().toString(), seqs.size, "0".repeat(64)))
    }

    private fun isCarrier(body: String): Boolean = body.contains("\"events\":[]")

    private fun checkpointThroughIn(body: String): String? =
        Regex("\"lossCheckpoint\":\\{[^}]*\"throughSequence\":\"(\\d+)\"").find(body)?.groupValues?.get(1)
            ?: Regex("\"throughSequence\":\"(\\d+)\"").find(body)?.groupValues?.get(1)

    private fun lastSequenceIn(body: String): Long? =
        Regex("\"sequence\":(\\d+)").findAll(body).map { it.groupValues[1].toLong() }.maxOrNull()

    private fun strand(f: File, lossBefore: Long, through: Long, batchLast: Long): LossAuthority.Handle {
        val h = authority(f)
        for (i in 0 until lossBefore) lose(h, "l$i")
        for (i in lossBefore..through) admit(h, "a$i")
        val c = if (lossBefore > 0) counts(fam to lossBefore) else counts()
        val id = CheckpointClaim.identity(sid, through, c, false)
        assertEquals("ARRANGE: the claim is durable",
            LossAuthority.Write.Written, h.requestCheckpoint(id, id, through, c, false))
        assertNotNull("ARRANGE: a pending claim exists", h.pendingCheckpoint())
        assertEquals(through, h.pendingCheckpoint()!!.throughSequence)
        // the records the crash recovered — admitted, which is how they got a
        // sequence at all, and what raises the authority's high-water so the
        // NEXT chunk may claim its own last.
        for (i in (through + 1)..batchLast) admit(h, "b$i")
        return h
    }

    private fun realSink(f: File): AttributionSink = AttributionSink.forFilesDir(f,
        { s -> LossAuthority.openExisting(f, s, { k }, yes) },
        { s, qs -> LossAuthority.openForRemoval(f, s, qs, { k }, yes) })

    private class Mono(var nanos: Long = 1_000_000_000L) : () -> Long {
        override fun invoke(): Long = nanos
        fun advanceMillis(ms: Long) { nanos += ms * 1_000_000L }
    }

    private fun pipelineWith(
        f: File,
        q: EncryptedEventStore,
        bodies: MutableList<String>,
        sink: AttributionSink,
        park: ParkStore = ParkStore.none(),
        quar: Quar = Quar(),
        mono: Mono = Mono(),
        faults: (String) -> Unit = {},
        respond: (String) -> Uploader.Result,
    ): DurableUploadPipeline = DurableUploadPipeline(
        home = QueueHome.of(q.backingFile()) { q },
        mutationGate = { slice -> LossAuthority.mutationFenceFor(f, slice) },
        poster = { _, body ->
            val text = String(body, Charsets.UTF_8); bodies.add(text); respond(text)
        },
        projectId = "p", maxRetries = 1, appContext = null, backoffMillis = { 0L },
        results = Sink(), quarantine = quar, sleeper = {},
        inflight = InFlightStore.file(File(f, "inflight.txt")),
        attribution = sink,
        park = park,
        monotonicNanos = mono,
        faults = faults,
    )


    @Test fun foreignPrefixIsPreservedBeforeCurrentProjectDrains() {
        val f=tmp();val h=strand(f,0,6,7);val frozen=snapshot(h.pendingCheckpoint())
        val q=queue(f);val foreign=String(rec(7)).replace("{","{\"projectId\":\"foreign\",").toByteArray()
        val current=String(rec(0,other)).replace("{","{\"projectId\":\"p\",").toByteArray()
        q.append(foreign);q.append(current)
        val kept=ArrayList<ByteArray>();val entries=ArrayList<ParkedBatch>()
        val park=object:ParkStore {
            override fun entriesRead()=ParkManifestRead(entries.toList(),RecordStore.INTEGRITY_OK)
            override fun park(batch:ParkedBatch,records:List<ByteArray>):ParkOutcome {
                entries.add(batch);kept.addAll(records.map{it.copyOf()});return ParkOutcome.PARKED
            }
            override fun status()=ParkStatus(entries.size,kept.size,kept.sumOf{it.size.toLong()})
        }
        val bodies=ArrayList<String>()
        pipelineWith(f,q,bodies,realSink(f),park=park){chunkOk(it)}.upload(q.readAll().records,sid)
        assertEquals(1,kept.size);assertTrue(foreign.contentEquals(kept.single()))
        assertEquals(1,bodies.size);assertTrue(bodies.single().contains(other))
        assertFalse(bodies.single().contains("foreign"));assertTrue(q.readAll().records.isEmpty())
        assertEquals("slice_project_mismatch",entries.single().refusalCode)
        assertEquals("parking must not rewrite a frozen claim",frozen,snapshot(h.pendingCheckpoint()))
    }

    @Test fun freshPipelinesShareCarrierCooldown() {
        val f=tmp();strand(f,0,6,8);val q=queue(f);q.append(rec(7));q.append(rec(8))
        val bodies=ArrayList<String>();val mono=Mono()
        repeat(4){ pipelineWith(f,q,bodies,realSink(f),mono=mono){Uploader.Result(ResponseAction.RETRY,503,"busy")}.upload(q.readAll().records,sid) }
        assertEquals(3,bodies.size)
        mono.advanceMillis(300001)
        pipelineWith(f,q,bodies,realSink(f),mono=mono){Uploader.Result(ResponseAction.RETRY,503,"busy")}.upload(q.readAll().records,sid)
        assertEquals(4,bodies.size);assertNotNull(authority(f).pendingCheckpoint())
    }

    @Test fun producerCannotReuseReadOnlyAuthorityWithoutQueueReconciliation() {
        val f=tmp();val readonly=authority(f);assertFalse(readonly.reconciledWithQueue)
        val q=queue(f)
        val app=object:android.content.ContextWrapper(null){override fun getFilesDir()=f}
        val method=com.tracescout.mobile.rn.RnTransport::class.java.getDeclaredMethod("authorityFor",
            android.content.Context::class.java,String::class.java,EncryptedEventStore::class.java).apply{isAccessible=true}
        val producer=method.invoke(com.tracescout.mobile.rn.RnTransport,app,sid,q) as LossAuthority.Handle?
        // No Android Keystore exists in this JVM: refusing is valid, returning
        // an unreconciled handle that can accept PREPARED admissions is not.
        assertTrue("producer admitted through read-only handle",producer==null || producer.reconciledWithQueue)
        assertNull(readonly.pendingCheckpoint())
    }

    @Test fun busyIdentityGateStillChainsHostCrashHandler() {
        val lock=java.util.concurrent.locks.ReentrantLock()
        val provider=object:com.tracescout.mobile.rn.RnIncidentCapture.Context2 {
            override val identityLock=lock
            override fun identity():com.tracescout.rn.RnEnvelope.Identity?=error("busy gate must refuse before callbacks")
            override fun posture()=com.tracescout.rn.RnEnvelope.Posture(allowedViewCount=0,screenNeverRecord=false)
            override fun screenId():String?=null
        }
        val builder=com.tracescout.mobile.rn.RnIncidentCapture::class.java.getDeclaredMethod("buildCrashRecord",
            android.content.Context::class.java,com.tracescout.mobile.rn.RnIncidentCapture.Context2::class.java,
            Thread::class.java,Throwable::class.java).apply{isAccessible=true}
        val chained=java.util.concurrent.CountDownLatch(1)
        val ctor=com.tracescout.mobile.capture.CrashHandler::class.java.declaredConstructors.first{it.parameterCount==4}.apply{isAccessible=true}
        val build:(Thread,Throwable)->ByteArray={t,e->builder.invoke(com.tracescout.mobile.rn.RnIncidentCapture,android.content.ContextWrapper(null),provider,t,e) as ByteArray}
        val settled:(Boolean,EncryptedEventStore.Durability)->Unit={_,_->error("refused crash must not append")}
        val q=queue(tmp())
        val handler=ctor.newInstance(q,Thread.UncaughtExceptionHandler{_,_->chained.countDown()},build,settled) as Thread.UncaughtExceptionHandler
        val crash=Thread{handler.uncaughtException(Thread.currentThread(),Exception("synthetic"))}
        lock.lock()
        try {crash.start();assertTrue("host handler blocked on SDK identity gate",chained.await(1,java.util.concurrent.TimeUnit.SECONDS))}
        finally {lock.unlock();crash.join(5000)}
        assertFalse(crash.isAlive);assertTrue(q.readAll().records.isEmpty())
    }

    @Test fun rnDrainDiscoversCheckpointWithoutFinalizedSession() = checkpointOnlyDrain(false)
    @Test fun nativeWorkerRetainsCheckpointOnlyRetry() = checkpointOnlyDrain(true)

    @Test fun nativeDrainOutcomeBelongsToItsInvocation() = checkpointOnlyDrain(true, true)

    private fun checkpointOnlyDrain(native:Boolean, interleave:Boolean=false) {
        val f=tmp();val h=strand(f,0,6,8)
        assertTrue(realSink(f).projectOwnership(sid,"p",true))
        val q=EncryptedEventStore(File(f,if(native) com.tracescout.mobile.queue.QueueKeyStores.SDK_QUEUE else com.tracescout.mobile.queue.QueueKeyStores.RN_QUEUE),k,
            4L*1024*1024,EncryptedEventStore.EvictionPolicy.ACTIVE_QUEUE,yes)
        QueueHome.of(q.backingFile()){q}
        val transport:Any=if(native) com.tracescout.mobile.TraceScoutMobile else com.tracescout.mobile.rn.RnTransport
        fun field(name:String)=transport.javaClass.getDeclaredField(name).apply{isAccessible=true}
        val fields=(if(native) listOf("store","config","home","uploader","appContext","identity")
            else listOf("store","finalizedSession","lastPipelineDisposition")).associateWith{field(it)}
        val priorHook=com.tracescout.mobile.queue.RecoveryWorker.drainHook
        val prior=fields.mapValues{it.value.get(transport)}
        val context=object:android.content.ContextWrapper(null) {
            override fun getFilesDir()=f
            override fun getApplicationContext():android.content.Context=this
        }
        val posts=java.util.concurrent.atomic.AtomicInteger()
        val server=java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout=5000
        val serverError=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val serving=Thread {
            try { server.accept().use { client ->
                client.soTimeout=5000
                val input=client.getInputStream()
                val header=StringBuilder()
                while(!header.endsWith("\r\n\r\n")) {val c=input.read();check(c>=0);header.append(c.toChar())}
                val length=Regex("(?i)Content-Length: ([0-9]+)").find(header)!!.groupValues[1].toInt()
                repeat(length){check(input.read()>=0)}
                posts.incrementAndGet()
                client.getOutputStream().apply {
                    write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray());flush()
                }
            } } catch(t:Throwable){serverError.set(t)}
        }
        serving.start()
        try {
            fields.getValue("store").set(transport,q)
            val url="http://127.0.0.1:${server.localPort}"
            if(native) {
                fields.getValue("config").set(transport,com.tracescout.mobile.TraceScoutMobile.Config("p","synthetic-test-key",url))
                fields.getValue("home").set(transport,QueueHome.of(q.backingFile()){q})
                fields.getValue("uploader").set(transport,Uploader(url,"synthetic-test-key"))
                fields.getValue("appContext").set(transport,context)
                fields.getValue("identity").set(transport,null)
                transport.javaClass.getDeclaredMethod("installWorkerDrain").apply{isAccessible=true}.invoke(transport)
                if (!interleave) {
                    assertEquals(com.tracescout.mobile.queue.RecoveryWorker.Outcome.RETRY,
                        com.tracescout.mobile.queue.RecoveryWorker.drainHook!!(context))
                } else {
                    val call=transport.javaClass.getDeclaredMethod("uploadRawWithOutcome",List::class.java,kotlin.jvm.functions.Function1::class.java).apply{isAccessible=true}
                    var first:DurableUploadPipeline.Disposition?=null
                    val error=java.util.concurrent.atomic.AtomicReference<Throwable>()
                    val capture:(DurableUploadPipeline.Disposition)->Unit={ value ->
                        first=value
                        if(value==DurableUploadPipeline.Disposition.F15_CHECKPOINT_RETRY_OWED) {
                            val cfg=fields.getValue("config").get(transport)
                            fields.getValue("config").set(transport,null)
                            val second=Thread {try {
                                val other:(DurableUploadPipeline.Disposition)->Unit={assertEquals(DurableUploadPipeline.Disposition.RETRYABLE_REMAINING,it)}
                                assertEquals(false,call.invoke(transport,emptyList<ByteArray>(),other))
                            } catch(t:Throwable){error.set(t)}}
                            try {second.start();second.join(5000);assertFalse(second.isAlive)}
                            finally {fields.getValue("config").set(transport,cfg)}
                        }
                    }
                    assertEquals(true,call.invoke(transport,emptyList<ByteArray>(),capture))
                    assertNull(error.get());assertEquals(DurableUploadPipeline.Disposition.F15_CHECKPOINT_RETRY_OWED,first)
                }
            } else {
                fields.getValue("finalizedSession").set(transport,null)
                val cfg=com.tracescout.mobile.rn.RnSecureConfig.Config("p","synthetic-test-key","test",url)
                assertEquals(com.tracescout.mobile.rn.RnTransport.State.PENDING_NETWORK,
                    com.tracescout.mobile.rn.RnTransport.drainConfigured(context,cfg))
            }
            serving.join(6000);assertNull(serverError.get());assertEquals(1,posts.get());assertNotNull(h.pendingCheckpoint());assertTrue(q.readAll().records.isEmpty())
        } finally {
            server.close();serving.join(6000);fields.forEach{(name,field)->field.set(transport,prior[name])}
            com.tracescout.mobile.queue.RecoveryCoordinator.release(context)
            com.tracescout.mobile.queue.RecoveryWorker.drainHook=priorHook
        }
    }

    @Test fun reconciledDeliveredQueueStillRetriesUnsettledCheckpoint() {
        val f=tmp();val h=strand(f,0,0,0);val q=queue(f);q.append(owned(0))
        val bodies=ArrayList<String>()
        try {
            pipelineWith(f,q,bodies,realSink(f),faults={if(it=="after_delivered_durable")throw DurableUploadPipeline.SimulatedProcessDeath(it)}){chunkOk(it)}
                .upload(q.readAll().records,sid)
            fail("death seam not reached")
        } catch(_:DurableUploadPipeline.SimulatedProcessDeath) {}
        assertEquals(1,q.readAll().records.size);assertNotNull(h.pendingCheckpoint())
        bodies.clear()
        val next=pipeline(f,q,bodies){Uploader.Result(ResponseAction.RETRY,503,"busy")}
        next.upload(q.readAll().records,sid)
        assertTrue(q.readAll().records.isEmpty());assertEquals(1,bodies.size);assertTrue(isCarrier(bodies.single()))
        assertEquals(DurableUploadPipeline.Disposition.F15_CHECKPOINT_RETRY_OWED,next.disposition)
        assertNotNull(h.pendingCheckpoint())
    }

    @Test fun workerBootstrapsCheckpointOnlyWork() {
        val f=tmp(); val h=strand(f,0,6,8)
        assertTrue(LossAuthority.hasPendingRecovery(f))
        val broken=tmp();val brokenRoot=EncryptedAuthorityStore.rootFor(broken).toPath()
        java.nio.file.Files.createSymbolicLink(brokenRoot,brokenRoot)
        assertTrue("unreadable authority root is not empty",LossAuthority.hasPendingRecovery(broken))
        val worker=com.tracescout.mobile.queue.RecoveryWorker
        val oldDrain=worker.drainHook; val oldBoot=worker.bootstrapHook
        val context=object:android.content.ContextWrapper(null) {
            override fun getFilesDir()=f
        }
        var boots=0; var drains=0
        try {
            worker.drainHook=null
            worker.bootstrapHook={ boots++; worker.drainHook={ drains++; com.tracescout.mobile.queue.RecoveryWorker.Outcome.RETRY }; true }
            assertEquals(com.tracescout.mobile.queue.RecoveryWorker.Outcome.RETRY, worker.outcomeFor(context))
            assertEquals(1,boots);assertEquals(1,drains)
            worker.drainHook=null;worker.bootstrapHook=null
            assertEquals(com.tracescout.mobile.queue.RecoveryWorker.Outcome.RETRY,worker.outcomeFor(context))
            assertNotNull(h.pendingCheckpoint())
        } finally {worker.drainHook=oldDrain;worker.bootstrapHook=oldBoot}
    }

    @Test fun transientSettlementFailuresRemainRetryable() {
        for (mode in listOf("notDurable", "refused", "throw", "declined")) {
            val f=tmp();strand(f,0,6,8);val q=queue(f)
            q.append(rec(7));q.append(rec(8));val bodies=ArrayList<String>()
            val sink=object: AttributionSink by realSink(f) {
                override fun settleLossCheckpoint(sessionId:String, protocol:String, lossAccountingVersion:Int,
                    state:String, ackSessionId:String, acceptedThroughSequence:String?, acceptedCounts:Map<String,Long>, terminal:Boolean): AttributionSink.Settlement = when(mode) {
                    "notDurable" -> AttributionSink.Settlement.NotDurable("test")
                    "refused" -> AttributionSink.Settlement.Refused("test")
                    "declined" -> AttributionSink.Settlement.PolicyDeclined("test")
                    else -> throw java.io.IOException("test")
                }
            }
            val p=pipelineWith(f,q,bodies,sink){ok(lossBlock(6,counts()))}
            p.upload(q.readAll().records,sid)
            pipelineWith(f,q,bodies,sink){ok(lossBlock(6,counts()))}.upload(q.readAll().records,sid)
            assertEquals(mode,if(mode=="declined") 1 else 2,bodies.size)
            assertTrue(bodies.all{isCarrier(it)})
            assertEquals(2,q.readAll().records.size)
            assertEquals(6L,authority(f).pendingCheckpoint()!!.throughSequence)
            LossAuthority.closeAll()
        }
    }
    private fun owned(seq: Long) = String(rec(seq)).replace("{", "{\"projectId\":\"p\",").toByteArray()

    /** An inherited session with NO authority on disk: the binding is created
     * before the POST it guards, and the queue head is not held for ever. */
    @Test fun inheritedSessionWithoutAuthorityBindsBeforePostAndDelivers() {
        val f=tmp();val q=queue(f);for(seq in 0L..1L)q.append(owned(seq))
        assertFalse("ARRANGE: no authority exists",LossAuthority.authorityExists(f,sid))
        val bodies=ArrayList<String>();val boundAtPost=ArrayList<Boolean>()
        val p=pipeline(f,q,bodies){body ->
            boundAtPost.add(File(EncryptedAuthorityStore.dirFor(f,sid)!!,"project-owner-v1.digest").isFile);chunkOk(body)}
        p.upload(q.readAll().records,sid)
        assertEquals(1,bodies.size);assertFalse(isCarrier(bodies[0]))
        assertEquals(listOf(true),boundAtPost)
        assertEquals(0,q.readAll().records.size)
    }
    /** A torn inherited queue is not evidence of ownership: a sink that would
     * adopt a lossy view must not be asked, and nothing is sent. */
    @Test fun inheritedUntrustworthyQueueSendsNoPost() {
        val f=tmp();val q=queue(f);for(seq in 0L..1L)q.append(owned(seq))
        java.io.RandomAccessFile(q.backingFile(),"rw").use{it.seek(it.length());it.write(byteArrayOf(0,0,0,9,1,2))}
        val read=q.readAll()
        assertEquals("ARRANGE: both records still readable",2,read.records.size)
        assertFalse("ARRANGE: the read is lossy","ok"==read.integrityClass)
        assertFalse("ARRANGE: no authority exists",LossAuthority.authorityExists(f,sid))
        var adoptions=0;val real=realSink(f)
        // The sink the reviewer named: it answers Ready after a lossy view.
        val lenient=object: AttributionSink by real {
            override fun adoptForRemoval(sessionId:String, queue:List<ByteArray>, queueTrustworthy:Boolean):AttributionSink.Adoption {
                adoptions++;return real.adoptForRemoval(sessionId,queue,true)
            }
        }
        val bodies=ArrayList<String>();val p=pipelineWith(f,q,bodies,lenient){body -> chunkOk(body)}
        p.upload(read.records,sid)
        assertEquals("a lossy inherited queue sends nothing",0,bodies.size)
        assertEquals("the lenient sink was never asked to adopt for the POST",0,adoptions)
        assertEquals(2,q.readAll().records.size)
    }
    /** Ownership that cannot be established before the POST still refuses, and
     * a refusal that sent nothing is not a POSTED attempt: once the binding is
     * possible the same queue image delivers instead of sitting behind the
     * no-progress bound. */
    @Test fun bindingRefusalsSendNothingAndDoNotExhaustTheNoProgressBound() {
        val f=tmp();val q=queue(f);for(seq in 0L..1L)q.append(owned(seq))
        var allow=false
        val sink=object: AttributionSink by realSink(f) {
            override fun projectOwnership(sessionId:String, projectId:String, bindFromRecords:Boolean):Boolean = false
            override fun adoptForRemoval(sessionId:String, queue:List<ByteArray>, queueTrustworthy:Boolean):AttributionSink.Adoption =
                AttributionSink.Adoption.Refused("test")
        }
        val real=realSink(f)
        val switching=object: AttributionSink by real {
            override fun projectOwnership(sessionId:String, projectId:String, bindFromRecords:Boolean):Boolean =
                if(allow) real.projectOwnership(sessionId,projectId,bindFromRecords) else sink.projectOwnership(sessionId,projectId,bindFromRecords)
            override fun adoptForRemoval(sessionId:String, queue:List<ByteArray>, queueTrustworthy:Boolean):AttributionSink.Adoption =
                if(allow) real.adoptForRemoval(sessionId,queue,queueTrustworthy) else sink.adoptForRemoval(sessionId,queue,queueTrustworthy)
        }
        val bodies=ArrayList<String>();val p=pipelineWith(f,q,bodies,switching){body -> chunkOk(body)}
        repeat(12){p.upload(q.readAll().records,sid)}
        assertEquals("a refusal sends nothing",0,bodies.size);assertEquals(2,q.readAll().records.size)
        allow=true;p.upload(q.readAll().records,sid)
        assertEquals(1,bodies.size);assertEquals(0,q.readAll().records.size)
    }
    @Test fun foreignProjectCarrierNeverPosts() {
        val f=tmp();strand(f,0,6,8);val q=queue(f)
        q.append(String(rec(7)).replace("{", "{\"projectId\":\"foreign\",").toByteArray())
        val bodies=ArrayList<String>();val p=pipeline(f,q,bodies){error("foreign carrier reached HTTP")}
        p.upload(q.readAll().records,sid)
        assertEquals(0,bodies.size);assertEquals(1,q.readAll().records.size)
        assertEquals(6L,authority(f).pendingCheckpoint()!!.throughSequence)
    }
    @Test fun ownedCarrierStillSettlesAndDelivers() {
        val f=tmp();strand(f,0,6,8);val q=queue(f)
        for(seq in 7L..8L)q.append(String(rec(seq)).replace("{", "{\"projectId\":\"p\",").toByteArray())
        val bodies=ArrayList<String>();val p=pipeline(f,q,bodies){body -> if(isCarrier(body))ok(lossBlock(6,counts())) else chunkOk(body)}
        p.upload(q.readAll().records,sid)
        assertEquals(2,bodies.size);assertTrue(isCarrier(bodies[0]));assertFalse(isCarrier(bodies[1]))
        assertEquals(0,q.readAll().records.size)
    }
    @Test fun smallerDeliveryRetainsClaimAcrossRestartAndNeedsGenuineLossAck() {
        val f=tmp();val h=authority(f)
        for(i in 0L..5L)admit(h,"first$i")
        val initial=CheckpointClaim.identity(sid,5,counts(),false)
        assertEquals(LossAuthority.Write.Written,h.requestCheckpoint(initial,initial,5,counts(),false))
        realSink(f).settleLossCheckpoint(sid,"loss-checkpoint-ack-1",1,"accepted",sid,"5",counts(),false)
        for(i in 6L..15L)admit(h,"later$i")
        val frozen=CheckpointClaim.identity(sid,15,counts(),false)
        assertEquals(LossAuthority.Write.Written,h.requestCheckpoint(frozen,frozen,15,counts(),false))
        val before=snapshot(h.pendingCheckpoint())!!
        val q=queue(f);for(seq in 6L..10L)q.append(rec(seq))
        val bodies=ArrayList<String>()
        pipeline(f,q,bodies){ body ->
            assertFalse("smaller body must omit the frozen loss claim",body.contains("lossCheckpoint"))
            Uploader.Result(ResponseAction.RETRY,503,"temporary")
        }.upload(q.readAll().records,sid)
        assertEquals(5,q.readAll().records.size)
        assertEquals(before,snapshot(h.pendingCheckpoint()))
        LossAuthority.closeAll();QueueHome.forgetForTest();F6CheckpointEmitter.resetForTest()
        pipeline(f,q,bodies){body -> chunkOk(body)}.upload(q.readAll().records,sid)
        assertEquals(2,bodies.size);assertEquals("restart retry must be byte-identical",bodies[0],bodies[1])
        assertTrue(q.readAll().records.isEmpty())
        assertEquals(before,snapshot(h.pendingCheckpoint()))
        LossAuthority.closeAll();QueueHome.forgetForTest();F6CheckpointEmitter.resetForTest()
        val reopened=authority(f);assertEquals(before,snapshot(reopened.pendingCheckpoint()))
        // No events remain: retention is containment, not eventual settlement.
        val afterRestart=ArrayList<String>()
        pipeline(f,q,afterRestart){error("no compatible carrier exists")}.upload(emptyList(),sid)
        assertTrue(afterRestart.isEmpty());assertEquals(before,snapshot(reopened.pendingCheckpoint()))
        val sink=realSink(f)
        sink.settleLossCheckpoint(sid,"loss-checkpoint-ack-1",1,"accepted",sid,"10",counts(),false)
        assertEquals("wrong-boundary late ACK cannot settle",before,snapshot(reopened.pendingCheckpoint()))
        sink.settleLossCheckpoint(sid,"loss-checkpoint-ack-1",1,"accepted",sid,"15",counts(),false)
        assertNull("only genuine exact-bound tuple settles",reopened.pendingCheckpoint())
        admit(reopened,"new16")
        val next=CheckpointClaim.identity(sid,16,counts(),false)
        assertEquals(LossAuthority.Write.Written,reopened.requestCheckpoint(next,next,16,counts(),false))
        val successor=snapshot(reopened.pendingCheckpoint())
        sink.settleLossCheckpoint(sid,"loss-checkpoint-ack-1",1,"accepted",sid,"15",counts(),false)
        assertEquals("duplicate old ACK cannot clear successor",successor,snapshot(reopened.pendingCheckpoint()))
    }

    @Test fun legacyAttachedAndNewEventOnlyDescriptorsResumeExactlyAndIntegrityStillRefuses() {
        for (legacy in listOf(false,true)) {
            val f=tmp();val h=strand(f,0,15,15);val q=queue(f)
            for(n in 6L..10L)q.append(rec(n))
            val bodies=ArrayList<String>()
            pipeline(f,q,bodies){Uploader.Result(ResponseAction.RETRY,503,"temporary")}.upload(q.readAll().records,sid)
            val store=InFlightStore.file(File(f,"inflight.txt"));val held=store.load()!!
            val oldBody=if(legacy) {
                val cp=realSink(f).f6CheckpointJson(sid,6,10,realSink(f).f6ReserveBytes(sid),resuming=true)!!
                bodies[0].dropLast(1)+F6CheckpointEmitter.ATTACH_PREFIX+cp+"}"
            } else bodies[0]
            fun sha(text:String)=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                .joinToString(""){"%02x".format(it)}
            assertTrue(store.save(held.copy(contentSha256=sha(oldBody))))
            LossAuthority.closeAll();QueueHome.forgetForTest();F6CheckpointEmitter.resetForTest()
            pipeline(f,q,bodies){Uploader.Result(ResponseAction.RETRY,503,"temporary")}.upload(q.readAll().records,sid)
            assertEquals("frozen attachment choice must survive upgrade",oldBody,bodies.last())
            assertEquals(2,bodies.size);assertEquals(5,q.readAll().records.size)
            assertTrue(store.save(store.load()!!.copy(contentSha256="f".repeat(64))))
            pipeline(f,q,bodies){error("corrupt descriptor reached HTTP")}.upload(q.readAll().records,sid)
            assertEquals(2,bodies.size);assertEquals(15L,authority(f).pendingCheckpoint()!!.throughSequence)
        }
    }

    @Test fun emptyQueueCheckpointFailuresKeepOnlyTransientRetriesOwed() {
        for (mode in listOf("transport", "503", "429", "missingAck", "refused", "accepted", "settlementThrow")) {
            val f = tmp(); val h = strand(f, 0, 6, 6); val q = queue(f)
            assertTrue(realSink(f).projectOwnership(sid, "p", true))
            val frozen = snapshot(h.pendingCheckpoint())
            val bodies = ArrayList<String>()
            val sink=object:AttributionSink by realSink(f) {
                override fun settleLossCheckpoint(sessionId:String,protocol:String,lossAccountingVersion:Int,state:String,
                    ackSessionId:String,acceptedThroughSequence:String?,acceptedCounts:Map<String,Long>,terminal:Boolean):AttributionSink.Settlement {
                    if(mode=="settlementThrow")throw java.io.IOException("settlement failed")
                    return realSink(f).settleLossCheckpoint(sessionId,protocol,lossAccountingVersion,state,ackSessionId,acceptedThroughSequence,acceptedCounts,terminal)
                }
            }
            val p = pipelineWith(f, q, bodies, sink) {
                when (mode) {
                    "transport" -> throw java.io.IOException("offline")
                    "503" -> Uploader.Result(ResponseAction.RETRY, 503, "temporary")
                    "429" -> Uploader.Result(ResponseAction.RETRY, 429, "rate_limited")
                    "missingAck" -> ok(null)
                    "refused" -> Uploader.Result(ResponseAction.TERMINAL, 422, "declined")
                    else -> ok(lossBlock(6, counts()))
                }
            }
            p.upload(emptyList(), sid)
            assertEquals(mode, 1, bodies.size)
            assertTrue(mode, isCarrier(bodies.single()))
            assertEquals(mode, 0, q.readAll().records.size)
            val retry = mode !in listOf("refused", "accepted")
            assertEquals(mode, if (retry) DurableUploadPipeline.Disposition.F15_CHECKPOINT_RETRY_OWED
                else DurableUploadPipeline.Disposition.EMPTY, p.disposition)
            val classify = com.tracescout.mobile.rn.RnTransport::class.java.getDeclaredMethod(
                "classify", Uploader.Result::class.java, DurableUploadPipeline.Disposition::class.java
            ).apply { isAccessible = true }
            val state = classify.invoke(com.tracescout.mobile.rn.RnTransport, null, p.disposition)
            assertEquals(mode, if (retry) com.tracescout.mobile.rn.RnTransport.State.PENDING_NETWORK
                else com.tracescout.mobile.rn.RnTransport.State.READY, state)
            if (mode == "accepted") assertNull(h.pendingCheckpoint())
            else assertEquals(mode, frozen, snapshot(h.pendingCheckpoint()))
            LossAuthority.closeAll(); QueueHome.forgetForTest(); F6CheckpointEmitter.resetForTest()
        }
    }

    @Test fun emptyQueueWithoutProjectEvidenceCannotDiscloseInheritedAuthority() {
        val f=tmp();strand(f,0,6,6);val q=queue(f);val bodies=ArrayList<String>()
        pipeline(f,q,bodies){error("unproven inherited project reached HTTP")}.upload(emptyList(),sid)
        assertTrue(bodies.isEmpty());assertNotNull(authority(f).pendingCheckpoint())
        val sink=realSink(f)
        assertFalse(sink.projectOwnership(sid,"p"))
        assertTrue(sink.projectOwnership(sid,"p",true))
        assertFalse(sink.projectOwnership(sid,"foreign",true))
        LossAuthority.closeAll();QueueHome.forgetForTest()
        assertTrue(realSink(f).projectOwnership(sid,"p"));assertFalse(realSink(f).projectOwnership(sid,"foreign"))
        File(EncryptedAuthorityStore.dirFor(f,sid),"project-owner-v1.digest").writeText("corrupt")
        assertFalse(realSink(f).projectOwnership(sid,"p",true))
    }

    @Test fun knownTerminalSessionNeverPostsStrandedCarrier() {
        val f=tmp();strand(f,0,6,8);val q=queue(f);q.append(rec(7));q.append(rec(8))
        var archives=0
        val park=object:ParkStore {
            override fun entriesRead()=ParkManifestRead(listOf(ParkedBatch("prior",sid,"1","2",2,"android","1",
                SessionOriginPolicy.SESSION_ORIGIN_UNRECOVERABLE,0,0)),RecordStore.INTEGRITY_OK)
            override fun park(batch:ParkedBatch, records:List<ByteArray>):ParkOutcome {
                archives++;return ParkOutcome.BLOCKED // preservation refusal must retain active bytes
            }
            override fun status()=ParkStatus(1,2,0)
        }
        val bodies=ArrayList<String>()
        pipelineWith(f,q,bodies,realSink(f),park=park){error("known terminal must not ask server")}
            .upload(q.readAll().records,sid)
        assertEquals(0,bodies.size);assertEquals(1,archives);assertEquals(2,q.readAll().records.size)
    }

}
