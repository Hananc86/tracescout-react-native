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

class Pr3HostBoundaryTest {
    @Test fun bridgeRefreshesIdentityAtSequenceAdmissionAndRejectsReplacedSession() {
        val old=RnEnvelope.Identity("p","s","a","test","host.app",userId="old")
        for (replaceSession in listOf(false,true)) {
            val live=java.util.concurrent.atomic.AtomicReference(old)
            val read=java.util.concurrent.CountDownLatch(1);val resume=java.util.concurrent.CountDownLatch(1)
            val output=java.util.concurrent.CopyOnWriteArrayList<String>()
            val number=java.util.concurrent.atomic.AtomicLong()
            val lock=Any()
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
            val thread=Thread({bridge.lifecycle("foreground")},"paused-capture")
            try {
                thread.start();assertTrue(read.await(5,java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(com.tracescout.rn.bridge.RnBridge.Outcome.RECORDED,bridge.identify("new"))
                if(replaceSession) synchronized(lock){live.set(live.get().copy(sessionId="replacement"))}
                bridge.lifecycle("background")
                resume.countDown();thread.join(5000);assertFalse(thread.isAlive)
                assertEquals(if(replaceSession) 1 else 2,output.size)
                assertTrue(output.all{org.json.JSONObject(it).getString("userId")=="new"})
            } finally {resume.countDown();thread.join(5000)}
        }
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
