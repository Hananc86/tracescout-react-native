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
