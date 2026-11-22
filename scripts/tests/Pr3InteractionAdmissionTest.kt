package com.tracescout.mobile

import com.tracescout.mobile.core.Clock
import com.tracescout.mobile.emit.Envelope
import com.tracescout.mobile.capture.interaction.HostInteraction
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Pr3InteractionAdmissionTest {
    @Test fun actualHostGatePausedBeforeEmissionRejectsSessionReplacementTruthfully() {
        fun field(name:String)=TraceScoutMobile::class.java.getDeclaredField(name).apply{isAccessible=true}
        val names=listOf("identity","clock","hostInteractionsEnabled","interactionScreen")
        val fields=names.associateWith{field(it)};val prior=fields.mapValues{it.value.get(TraceScoutMobile)}
        val gate=CountDownLatch(1);val resume=CountDownLatch(1)
        val result=AtomicReference<HostInteraction.Decision>()
        var worker:Thread?=null
        try {
            val a=Envelope.SessionIdentity("p","session-a","anonymous","test","1","1","app","screen")
            fields.getValue("identity").set(TraceScoutMobile,a)
            fields.getValue("hostInteractionsEnabled").setBoolean(TraceScoutMobile,true)
            fields.getValue("interactionScreen").set(TraceScoutMobile,"screen")
            fields.getValue("clock").set(TraceScoutMobile,object:Clock {
                override fun monotonicNanos():Long {gate.countDown();check(resume.await(5,TimeUnit.SECONDS));return 1}
                override fun wallMillis():Long=1
            })
            worker=Thread{result.set(TraceScoutMobile.recordHostInteraction("tap"))};worker.start()
            assertTrue(gate.await(5,TimeUnit.SECONDS))
            val ownerLock=field("identityWriteLock").get(TraceScoutMobile)
            synchronized(ownerLock){fields.getValue("identity").set(TraceScoutMobile,a.copy(sessionId="session-b"))}
            resume.countDown();worker.join(5000);assertFalse(worker.isAlive)
            assertEquals(HostInteraction.Decision.Refused(HostInteraction.REFUSED_NO_SESSION),result.get())
            assertEquals("session-b",(fields.getValue("identity").get(TraceScoutMobile) as Envelope.SessionIdentity).sessionId)
        } finally {
            resume.countDown();worker?.join(5000)
            fields.forEach{(name,f)->f.set(TraceScoutMobile,prior[name])}
        }
    }
}
