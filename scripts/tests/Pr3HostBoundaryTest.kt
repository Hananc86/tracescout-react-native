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
