package com.tracescout.mobile

import com.tracescout.mobile.core.Clock
import com.tracescout.mobile.emit.Envelope
import com.tracescout.mobile.capture.interaction.HostInteraction
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.tracescout.mobile.internal.loss.LossAuthority
import com.tracescout.mobile.queue.DurableReplace
import javax.crypto.KeyGenerator

class Pr3InteractionAdmissionTest {
    @Test fun belowSlopSamplesCannotRelaxGesturePrivacy() {
        val safe=com.tracescout.mobile.capture.interaction.GestureLifecycle.SampleFacts("screen",false,false,true,
            com.tracescout.mobile.capture.interaction.InteractionPolicy.TargetClass.SAFE,
            com.tracescout.mobile.capture.interaction.InteractionPolicy.PositionTreatment.ALLOWED,100,100)
        for(restricted in listOf(safe.copy(positionTreatment=com.tracescout.mobile.capture.interaction.InteractionPolicy.PositionTreatment.BLOCKED),
            safe.copy(textEntryActive=true),safe.copy(screenNeverRecord=true),safe.copy(fromContentWindow=false),safe.copy(screenId="other"))) {
            for(swipe in listOf(false,true)) {
                val g=com.tracescout.mobile.capture.interaction.GestureLifecycle(10f)
                fun sample(action:com.tracescout.mobile.capture.interaction.GestureLifecycle.Action,x:Float)=
                    com.tracescout.mobile.capture.interaction.GestureLifecycle.TouchSample(action,0,x,1f,1000000,1)
                g.observe(sample(com.tracescout.mobile.capture.interaction.GestureLifecycle.Action.DOWN,1f),safe)
                g.observe(sample(com.tracescout.mobile.capture.interaction.GestureLifecycle.Action.MOVE,2f),restricted)
                val rows=g.observe(sample(com.tracescout.mobile.capture.interaction.GestureLifecycle.Action.UP,if(swipe)30f else 1f),safe)
                assertTrue("transient restriction leaked positional gesture",rows.all{it.facts.positionTreatment!=com.tracescout.mobile.capture.interaction.InteractionPolicy.PositionTreatment.ALLOWED})
            }
        }
    }

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

    /** First start publishes the authority before `config`. A producer in that
     * window holds a real sequence while `enqueue` schedules nothing: the host
     * must not be told `Recorded`, and a refused navigation must not move the route. */
    @Test fun allocatedSequenceWithoutScheduledAppendIsRefusedAndLeavesRouteUnchanged() {
        fun field(name:String)=TraceScoutMobile::class.java.getDeclaredField(name).apply{isAccessible=true}
        val names=listOf("identity","hostInteractionsEnabled","interactionScreen","authority","config")
        val fields=names.associateWith{field(it)};val prior=fields.mapValues{it.value.get(TraceScoutMobile)}
        val dir=java.nio.file.Files.createTempDirectory("pr3-admission").toFile()
        val sid="ts_1700000000000_cccccccccccc"
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        try {
            LossAuthority.closeAll()
            val h=(LossAuthority.open(dir,sid,null,{key},DurableReplace.DirSync{true}) as LossAuthority.Open.Ok).handle
            val a=Envelope.SessionIdentity("p",sid,"anonymous","test","1","1","app","screen")
            fields.getValue("identity").set(TraceScoutMobile,a)
            fields.getValue("hostInteractionsEnabled").setBoolean(TraceScoutMobile,true)
            fields.getValue("interactionScreen").set(TraceScoutMobile,"screen")
            fields.getValue("authority").set(TraceScoutMobile,h)
            fields.getValue("config").set(TraceScoutMobile,null)
            assertEquals(TraceScoutMobile.SessionEndEmission.REFUSED, TraceScoutMobile.recordSessionEnd())
            assertEquals("a refused append must release its end claim", TraceScoutMobile.SessionEndEmission.REFUSED,
                TraceScoutMobile.recordSessionEnd())
            val before=h.unsettledSequences().size
            assertEquals(HostInteraction.Decision.Refused(HostInteraction.REFUSED_INTERNAL_ERROR),
                TraceScoutMobile.recordHostInteraction("tap"))
            assertEquals("ARRANGE: a real sequence was allocated, so the old sequence test would say SUBMITTED",
                before+1,h.unsettledSequences().size)
            assertFalse(TraceScoutMobile.recordNavigation("next"))
            assertEquals("screen",fields.getValue("interactionScreen").get(TraceScoutMobile))
        } finally {
            fields.forEach{(name,f)->f.set(TraceScoutMobile,prior[name])}
            LossAuthority.closeAll();dir.deleteRecursively()
        }
    }
}
