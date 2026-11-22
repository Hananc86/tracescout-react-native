package com.tracescout.mobile.rn

import android.content.Context
import android.content.ContextWrapper
import android.webkit.WebView
import com.tracescout.mobile.capture.WebViewAttachment
import com.tracescout.mobile.capture.WebViewBridge
import com.tracescout.mobile.capture.WebViewOriginPolicy
import com.tracescout.mobile.privacy.Posture
import com.tracescout.rn.RnEnvelope
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test
import kotlin.concurrent.withLock

class Pr3HostBoundaryTest {
    @Test fun indeterminateOpenerCannotPublishThroughRealTransport() {
        val dir=java.nio.file.Files.createTempDirectory("pr3-opener").toFile()
        val context=object:ContextWrapper(null) {
            override fun getFilesDir()=dir
            override fun getApplicationContext():Context=this
        }
        val key=javax.crypto.KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        val sid="ts_1700000000000_dddddddddddd"
        fun field(n:String)=RnTransport::class.java.getDeclaredField(n).apply{isAccessible=true}
        val fields=listOf("store","bracket").associateWith{field(it)};val prior=fields.mapValues{it.value.get(RnTransport)}
        val deadline=field("deadlineArmed").get(RnTransport) as java.util.concurrent.atomic.AtomicBoolean
        val priorDeadline=deadline.getAndSet(true)
        val count=field("pendingSinceDrain").get(RnTransport) as java.util.concurrent.atomic.AtomicLong
        val bytes=field("pendingBytes").get(RnTransport) as java.util.concurrent.atomic.AtomicLong
        val priorCount=count.getAndSet(0);val priorBytes=bytes.getAndSet(0)
        try {
            com.tracescout.mobile.internal.loss.LossAuthority.closeAll()
            val q=com.tracescout.mobile.queue.EncryptedEventStore(java.io.File(dir,com.tracescout.mobile.queue.QueueKeyStores.RN_QUEUE),key,1024*1024,
                com.tracescout.mobile.queue.EncryptedEventStore.EvictionPolicy.ACTIVE_QUEUE,com.tracescout.mobile.queue.DurableReplace.DirSync{false})
            val home=com.tracescout.mobile.queue.QueueHome.of(q.backingFile()){q}
            val h=com.tracescout.mobile.internal.loss.LossAuthority.open(dir,sid,
                com.tracescout.mobile.internal.loss.LossAuthority.QueueSnapshot(emptyList(),true),{key},com.tracescout.mobile.queue.DurableReplace.DirSync{true})
            assertTrue(h is com.tracescout.mobile.internal.loss.LossAuthority.Open.Ok)
            fields.getValue("store").set(RnTransport,q)
            fields.getValue("bracket").set(RnTransport,com.tracescout.mobile.internal.queue.EvictionBracket(home))
            val result=RnTransport.enqueueOpener(context){seq->
                RnEnvelope.envelope(RnEnvelope.Identity("p",sid,"a","test","host.app"),"evt_opener","mobile_session_start",seq,1,1,RnEnvelope.Posture(allowedViewCount=0,screenNeverRecord=false)).toByteArray()
            }
            assertEquals("stored but unsynced opener must be refused",-1L,result)
            assertEquals("fixture must reach actual indeterminate append",1,q.readAll().records.size)
            val owner=(h as com.tracescout.mobile.internal.loss.LossAuthority.Open.Ok).handle
            assertEquals("indeterminate claim must remain unsettled",1,owner.unsettledSequences().size)
        } finally {deadline.set(priorDeadline);count.set(priorCount);bytes.set(priorBytes);fields.forEach{(n,f)->f.set(RnTransport,prior[n])};com.tracescout.mobile.internal.loss.LossAuthority.closeAll();dir.deleteRecursively()}
    }

    @Test fun sessionPublicationWaitsForCommittedOpener() {
        for (accepted in listOf(false,true)) {
            val live=java.util.concurrent.atomic.AtomicReference<RnEnvelope.Identity?>(null)
            val candidate=RnEnvelope.Identity("p","candidate","a","test","host.app")
            val lock=java.util.concurrent.locks.ReentrantLock()
            val entered=java.util.concurrent.CountDownLatch(1);val resume=java.util.concurrent.CountDownLatch(1)
            val error=java.util.concurrent.atomic.AtomicReference<Throwable>()
            val result=java.util.concurrent.atomic.AtomicReference<com.tracescout.rn.bridge.RnBridge.Result>()
            val rows=java.util.concurrent.CopyOnWriteArrayList<String>()
            val bridge=com.tracescout.rn.bridge.RnBridge(identity={live.get()},beginSession={candidate},configured={true},
                identityAdmissionLock=lock,publishSession={live.set(it)},
                sink=object:com.tracescout.rn.bridge.RnBridge.DurableSink {
                    override fun flushHint(reason:String){};override fun stateName()="READY"
                    override fun finalizeSession(sessionId:String)="RECORDED"
                    override fun enqueue(build:(Long)->ByteArray):Long {rows.add(String(build(1)));return 1}
                    override fun enqueueOpener(build:(Long)->ByteArray):Long {
                        assertNull("candidate published before durable opener",live.get())
                        assertTrue("opener must hold observer identity lock",lock.isHeldByCurrentThread)
                        entered.countDown();check(resume.await(5,java.util.concurrent.TimeUnit.SECONDS))
                        if(!accepted)return -1
                        rows.add(String(build(0)));return 0
                    }
                },clock=object:com.tracescout.rn.bridge.RnBridge.NativeClock {
                    override fun wallMillis()=1L;override fun monotonicNanos()=1L
                },ids=object:com.tracescout.rn.bridge.RnBridge.EventIdSource{override fun newEventId()="evt_opener_test"})
            val thread=Thread{try{result.set(bridge.startSession("home"))}catch(t:Throwable){error.set(t)}}
            try {
                thread.start();assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
                assertNull(live.get());assertFalse("crash observer must refuse during opener",lock.tryLock())
                resume.countDown();thread.join(5000);assertFalse(thread.isAlive);assertNull(error.get())
                assertEquals(if(accepted)com.tracescout.rn.bridge.RnBridge.Outcome.RECORDED else com.tracescout.rn.bridge.RnBridge.Outcome.REFUSED,result.get().outcome)
                assertEquals(if(accepted)candidate else null,live.get())
                val next=bridge.lifecycle("foreground")
                assertEquals(if(accepted)com.tracescout.rn.bridge.RnBridge.Outcome.RECORDED else com.tracescout.rn.bridge.RnBridge.Outcome.NO_SESSION,next.outcome)
                assertEquals(if(accepted)2 else 0,rows.size)
                if(accepted)assertEquals("mobile_session_start",org.json.JSONObject(rows.first()).getString("eventType"))
            } finally {resume.countDown();thread.join(5000)}
        }
    }

    @Test fun bridgeRefreshesIdentityAtSequenceAdmissionAndRejectsReplacedSession() {
        val old=RnEnvelope.Identity("p","s","a","test","host.app",userId="old")
        for (replaceSession in listOf(false,true)) {
            val live=java.util.concurrent.atomic.AtomicReference(old)
            val read=java.util.concurrent.CountDownLatch(1);val resume=java.util.concurrent.CountDownLatch(1)
            val output=java.util.concurrent.CopyOnWriteArrayList<String>()
            val number=java.util.concurrent.atomic.AtomicLong()
            val lock=java.util.concurrent.locks.ReentrantLock()
            val bridge=com.tracescout.rn.bridge.RnBridge(
                identity={live.get()},beginSession={live.get()},configured={true},identityAdmissionLock=lock,
                applyUserId={live.set(live.get().copy(userId=it));true},
                sink=object:com.tracescout.rn.bridge.RnBridge.DurableSink {
                    override fun flushHint(reason:String) {}
                    override fun stateName()="READY"
                    override fun finalizeSession(sessionId:String)="RECORDED"
                    override fun enqueue(build:(Long)->ByteArray):Long {
                        val n=number.incrementAndGet();output.add(String(build(n)));return n
                    }
                },
                clock=object:com.tracescout.rn.bridge.RnBridge.NativeClock {
                    override fun wallMillis():Long {
                        if(Thread.currentThread().name=="paused-capture") {read.countDown();check(resume.await(5,java.util.concurrent.TimeUnit.SECONDS))}
                        return 1
                    }
                    override fun monotonicNanos()=1L
                }, ids=object:com.tracescout.rn.bridge.RnBridge.EventIdSource {override fun newEventId()="evt_test_identity"})
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
            val result=java.util.concurrent.atomic.AtomicReference<com.tracescout.rn.bridge.RnBridge.Result>()
            val thread=Thread({try{result.set(bridge.lifecycle("foreground"))}catch(t:Throwable){failure.set(t)}},"paused-capture")
            try {
                thread.start();assertTrue(read.await(5,java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(com.tracescout.rn.bridge.RnBridge.Outcome.RECORDED,bridge.identify("new"))
                if(replaceSession) lock.withLock{live.set(live.get().copy(sessionId="replacement"))}
                bridge.lifecycle("background")
                resume.countDown();thread.join(5000);assertFalse(thread.isAlive)
                assertNull("capture thread threw instead of returning its outcome",failure.get())
                assertEquals(if(replaceSession) com.tracescout.rn.bridge.RnBridge.Outcome.REFUSED else com.tracescout.rn.bridge.RnBridge.Outcome.RECORDED,result.get().outcome)
                assertEquals(if(replaceSession) 1 else 2,output.size)
                assertTrue(output.all{org.json.JSONObject(it).getString("userId")=="new"})
            } finally {resume.countDown();thread.join(5000)}
        }
    }

    @Test fun hierarchyBudgetAndEnqueueUseOneIdentity() {
        val live=java.util.concurrent.atomic.AtomicReference(RnEnvelope.Identity("p","s","a","test","host.app",userId="old"))
        val sizing=java.util.concurrent.CountDownLatch(1);val proceed=java.util.concurrent.CountDownLatch(1)
        val identified=java.util.concurrent.CountDownLatch(1);val attempted=java.util.concurrent.CountDownLatch(1)
        val output=java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val errors=java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val bridge=com.tracescout.rn.bridge.RnBridge(identity={live.get()},beginSession={live.get()},configured={true},
            applyUserId={live.set(live.get().copy(userId=it));true},
            sink=object:com.tracescout.rn.bridge.RnBridge.DurableSink {
                override fun flushHint(reason:String){};override fun stateName()="READY"
                override fun finalizeSession(sessionId:String)="RECORDED"
                override fun enqueue(build:(Long)->ByteArray):Long {output.add(build(999));return 999}
            },clock=object:com.tracescout.rn.bridge.RnBridge.NativeClock {
                override fun wallMillis():Long {sizing.countDown();check(proceed.await(5,java.util.concurrent.TimeUnit.SECONDS));return 1}
                override fun monotonicNanos()=1L
            },ids=object:com.tracescout.rn.bridge.RnBridge.EventIdSource{override fun newEventId()="evt_budget_test"})
        val hierarchy=com.tracescout.rn.HierarchyResult(listOf(com.tracescout.rn.CanonicalNode("n","x".repeat(63500),"allowed")),setOf("n"),0,1,false)
        val emit=bridge.javaClass.getDeclaredMethod("emitHierarchy",String::class.java,
            com.tracescout.rn.bridge.RnBridge.HierarchyCapture::class.java,com.tracescout.rn.bridge.RnBridge.Result::class.java).apply{isAccessible=true}
        val capture=Thread{try{emit.invoke(bridge,"home",com.tracescout.rn.bridge.RnBridge.HierarchyCapture(hierarchy,1),
            com.tracescout.rn.bridge.RnBridge.Result(com.tracescout.rn.bridge.RnBridge.Outcome.RECORDED))}catch(t:Throwable){errors.add(t)}}
        val identify=Thread{try{attempted.countDown();bridge.identify("\u0001".repeat(128));identified.countDown()}catch(t:Throwable){errors.add(t)}}
        try {
            capture.start();assertTrue(sizing.await(5,java.util.concurrent.TimeUnit.SECONDS))
            identify.start();assertTrue(attempted.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("identity changed between size probe and durable append",identified.await(200,java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {proceed.countDown();capture.join(5000);identify.join(5000)}
        assertTrue(errors.toString(),errors.isEmpty());assertFalse(capture.isAlive);assertFalse(identify.isAlive)
        assertEquals(1,output.size);assertTrue(output.single().size<=65536)
        assertEquals("old",org.json.JSONObject(String(output.single())).getString("userId"))
        assertEquals("\u0001".repeat(128),live.get().userId)
    }

    @Test fun processObserversUseReplacementReactContext() {
        val installed=RnIncidentCapture::class.java.getDeclaredField("installed").apply{isAccessible=true}
            .get(RnIncidentCapture) as java.util.concurrent.atomic.AtomicBoolean
        val context=RnIncidentCapture::class.java.getDeclaredField("currentContext").apply{isAccessible=true}
        val oldInstalled=installed.get();val oldContext=context.get(RnIncidentCapture)
        val marker=IllegalStateException("new-module-context")
        fun provider(sid:String)=object:RnIncidentCapture.Context2 {
            override fun identity()=RnEnvelope.Identity("p",sid,"a","test","host.app")
            override fun posture():RnEnvelope.Posture { if(sid=="new")throw marker;error("stale React context retained") }
            override fun screenId()=sid
        }
        try {
            installed.set(false)
            val first=provider("old");val replacement=provider("new")
            assertTrue(RnIncidentCapture.bindContext(first))
            assertFalse("no duplicate observer installation",RnIncidentCapture.bindContext(replacement))
            RnIncidentCapture.clearContext(first)
            assertSame("old invalidation cannot clear new owner",replacement,context.get(RnIncidentCapture))
            val build=RnIncidentCapture::class.java.getDeclaredMethod("buildCurrentCrashRecord",Context::class.java,Thread::class.java,Throwable::class.java).apply{isAccessible=true}
            try {build.invoke(RnIncidentCapture,ContextWrapper(null),Thread.currentThread(),Exception());fail("callback not invoked")}
            catch(e:InvocationTargetException){assertSame(marker,e.cause)}
        } finally {installed.set(oldInstalled);context.set(RnIncidentCapture,oldContext)}
    }

    @Test fun throwingHostCallbacksCannotLeaveCrashAdmissionOutstanding() {
        val builder=RnIncidentCapture::class.java.getDeclaredMethod("buildCrashRecord",
            Context::class.java,RnIncidentCapture.Context2::class.java,Thread::class.java,Throwable::class.java)
        builder.isAccessible=true
        val ticket=RnIncidentCapture::class.java.getDeclaredField("crashTicket").apply{isAccessible=true}
        for (throwAt in listOf("posture","screen")) {
            val marker=IllegalStateException("host-$throwAt")
            val host=object:RnIncidentCapture.Context2 {
                override fun identity()=RnEnvelope.Identity("p","s","a","test","host.app")
                override fun posture():RnEnvelope.Posture {
                    if(throwAt=="posture")throw marker
                    return RnEnvelope.Posture(allowedViewCount=0,screenNeverRecord=false)
                }
                override fun screenId():String? = throw marker
            }
            try { builder.invoke(RnIncidentCapture,ContextWrapper(null),host,Thread.currentThread(),Exception());fail("callback did not fail") }
            catch(e:InvocationTargetException){assertSame(marker,e.cause)}
            assertNull(ticket.get(RnIncidentCapture))
        }
    }
    @Test fun webViewCarrierCannotCrossSessionsAndRefusalsStayClosed() {
        WebViewAttachment.detachAll()
        val a=WebView(ContextWrapper(null));val b=WebView(ContextWrapper(null));val policy=WebViewOriginPolicy.of("https://example.test")
        val seg=WebViewBridge.Segment("segment-a","session-a",Posture(),null,origin="https://example.test")
        assertTrue(WebViewAttachment.attach(a,seg,policy,"session-a") is WebViewAttachment.Decision.Admit)
        assertNotNull(WebViewAttachment.carrierFor(a,"session-a"))
        assertNull(WebViewAttachment.carrierFor(a,"session-b"))
        assertNull(WebViewAttachment.carrierFor(a,null))
        assertEquals(WebViewAttachment.Decision.Refuse(WebViewAttachment.REFUSED_SEGMENT_BOUND),
            WebViewAttachment.attach(b,seg,policy,"session-a"))
        for((live,cap,pol,reason) in listOf(
            listOf(null,true,policy,WebViewAttachment.REFUSED_NO_SESSION),
            listOf("session-b",true,policy,WebViewAttachment.REFUSED_SESSION_MISMATCH),
            listOf("session-a",false,policy,WebViewAttachment.REFUSED_UNSUPPORTED),
            listOf("session-a",true,WebViewOriginPolicy.closed(),WebViewAttachment.REFUSED_ORIGIN))) {
            assertEquals(WebViewAttachment.Decision.Refuse(reason as String),
                WebViewAttachment.attach(a,seg,pol as WebViewOriginPolicy,live as String?,cap as Boolean))
        }
        WebViewAttachment.detachAll();assertNull(WebViewAttachment.carrierFor(a,"session-a"))
        // Delayed attach after reset is refused using the new live-session fact.
        assertTrue(WebViewAttachment.attach(a,seg,policy,"session-b") is WebViewAttachment.Decision.Refuse)
        assertEquals(0,WebViewAttachment.attachedCount)
    }
}
