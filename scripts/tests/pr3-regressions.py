#!/usr/bin/env python3
"""Execute production Kotlin boundary methods with small IO/admission test doubles.
No policy is rewritten here: bodies are extracted verbatim, compiled, then run.
The full Android module is compiled separately. Pass --mutate-identity to prove
that retaining the old producer identity makes the interleaving test fail.
"""
from pathlib import Path
import argparse, subprocess, tempfile
p=argparse.ArgumentParser();p.add_argument('--compiler-lib',required=True);p.add_argument('--out',required=True);p.add_argument('--mutate-identity',action='store_true');a=p.parse_args()
r=Path(__file__).resolve().parents[2];out=Path(a.out);out.mkdir(parents=True,exist_ok=True)
def read(n):return (r/'android/src/main/java/com/tracescout/mobile'/n).read_text()
def block(s,start):
 i=s.index(start);b=s.index('{',i);depth=1;j=b+1
 # Selected methods have balanced braces in their comments/strings too.
 while depth:
  depth+=(s[j]=='{')-(s[j]=='}');j+=1
 return s[i:j]
s=read('TraceScoutMobile.kt')
start=s[s.index('beginTransportChange(TransportOwner.LIVE)'):s.index('endTransportChange()',s.index('beginTransportChange(TransportOwner.LIVE)'))]
assert start.index('LossAuthority.open(')<start.index('val liveUploader = Uploader(')<start.index('config = cfg')<start.index('uploader = liveUploader')
print('PASS source guard: throwing authority/reconciliation/uploader construction precedes config publication',flush=True)
identity=block(s,'private inline fun enqueueDisclosing(').replace('private inline fun','fun').replace('noinline onSubmitted','onSubmitted')
if a.mutate_identity:identity=identity.replace('build(live, d)','build(expected, d)')
end=block(s,'val endAdmission = synchronized(identityWriteLock)').replace('return@guard','return')
q=read('queue/QueueKeyStores.kt');sweep=block(q,'fun clearOwnedFrameFiles(')
w=read('queue/RecoveryWorker.kt');metadata=w[w.index('internal fun undeliveredRecordBytes'):w.index('\n        /**',w.index('internal fun undeliveredRecordBytes'))]
bootstrap=block(w,'private fun bootstrapThenHook').replace('private fun','fun')
program='''import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
object Envelope {
 data class SessionIdentity(val sessionId:String,val projectId:String,val userId:String)
 data class Record(val eventId:String,val sequence:Long=0)
}
object RecoveryTrace { fun log(vararg x:Any?) {} }
class Context(val filesDir:File)
class Subject {
 enum class EnqueueAdmission { SUBMITTED, NO_SESSION, REFUSED }
 val identityWriteLock=Any()
 var identity:Envelope.SessionIdentity?=null
 var sequence=0
 val records=mutableListOf<Pair<Int,Envelope.SessionIdentity>>()
 fun takePreF6Dropped()=emptyMap<String,Int>()
 fun returnPreF6DroppedMap(d:Map<String,Int>) {}
 fun bindPreF6Dropped(e:String,d:Map<String,Int>) {}
 fun enqueue(r:Envelope.Record)=true
 fun identify(user:String)=synchronized(identityWriteLock){identity=identity?.copy(userId=user)}
 fun replace(id:Envelope.SessionIdentity?)=synchronized(identityWriteLock){identity=id}
'''+identity+'''
 val sessionEndClaim=java.util.concurrent.atomic.AtomicReference<String?>(null)
 enum class SessionEndEmission { NO_SESSION, ALREADY_EMITTED, EMITTED }
 fun claimEnd():SessionEndEmission {
 '''+end+'''
 return SessionEndEmission.EMITTED
 }
}
object Sweep {
 val FRAME_SUFFIX=".bin"
 val FRAME_CONTENT_ADDRESS=Regex("^[0-9a-f]{64}$")
 val REFUSAL_DIRECTORY_ABSENT="directory_absent"
 data class FrameSweep(val deleted:List<String>,val failed:List<String>,val refusal:String?)
 fun isOwnedFrameFileName(name:String)=name.endsWith(FRAME_SUFFIX)&&FRAME_CONTENT_ADDRESS.matches(name.removeSuffix(FRAME_SUFFIX))
'''+sweep+'''\n}
object Worker {
 enum class Outcome { DONE, RETRY, PERMANENT }
 val RECORD_QUEUES=listOf("sdk","rn","flutter")
 var bootstrapHook:((Context)->Boolean)?=null
 var drainHook:((Context)->Outcome)?=null
'''+metadata+bootstrap+'''\n}
fun main() {
 val s=Subject(); val initial=Envelope.SessionIdentity("session-a","project-a","user-a");s.replace(initial)
 val read=CountDownLatch(1);val resume=CountDownLatch(1)
 val delayed=Thread {
  val captured=s.identity!!;read.countDown();check(resume.await(5,TimeUnit.SECONDS))
  s.enqueueDisclosing(captured){id,_->s.records.add(s.sequence++ to id);Envelope.Record("late")}
 }
 delayed.start();check(read.await(5,TimeUnit.SECONDS));s.identify("user-b")
 s.enqueueDisclosing(s.identity!!){id,_->s.records.add(s.sequence++ to id);Envelope.Record("early")}
 resume.countDown();delayed.join(5000);check(!delayed.isAlive)
 check(s.records.map{it.first}==listOf(0,1))
 check(s.records.all{it.second.userId=="user-b"}) { "late capture reverted identity" }
 println("PASS controlled capture/identify interleaving: higher sequence retains current user")
 val before=s.sequence;s.replace(initial.copy(sessionId="session-b"))
 check(s.enqueueDisclosing(initial){_,_->error("stale-session builder executed")}==Subject.EnqueueAdmission.NO_SESSION);check(s.sequence==before)
 s.replace(null);s.enqueueDisclosing(initial){_,_->error("no-session builder executed")}
 s.replace(initial.copy(projectId="project-b"));s.enqueueDisclosing(initial){_,_->error("foreign-project builder executed")}
 var moved=false
 s.replace(initial.copy(sessionId="session-b"))
 check(s.enqueueDisclosing(initial,onSubmitted={moved=true}){_,_->error("stale builder executed")}==Subject.EnqueueAdmission.NO_SESSION);check(!moved)
 check(s.enqueueDisclosing(s.identity!!,onSubmitted={moved=true}){_,_->Envelope.Record("ok")}==Subject.EnqueueAdmission.SUBMITTED);check(moved)
 println("PASS old session, no session and foreign project refuse before allocation; admission callback runs only on SUBMITTED")
 s.replace(initial)
 val attempted=CountDownLatch(1)
 val stale=Thread { attempted.countDown(); s.claimEnd() }
 synchronized(s.identityWriteLock) {
   stale.start();check(attempted.await(5,TimeUnit.SECONDS))
   s.replace(initial.copy(sessionId="session-b"))
   check(s.claimEnd()==Subject.SessionEndEmission.EMITTED)
 }
 stale.join(5000);check(!stale.isAlive)
 check(s.sessionEndClaim.get()!!.startsWith("session-b#"))
 check(s.claimEnd()==Subject.SessionEndEmission.ALREADY_EMITTED)
 println("PASS competing end claim cannot overwrite a newer session token or permit duplicate end")
 val dir=Files.createTempDirectory("pr3-regressions").toFile()
 val name="a".repeat(64)+".bin";val other=File(dir,"other").apply{mkdir()};val protected=File(other,name).apply{writeText("owned by another directory")}
 Files.createSymbolicLink(File(dir,"frames").toPath(),other.toPath())
 check(Sweep.clearOwnedFrameFiles(dir,"frames").refusal=="directory_escapes_root");check(protected.exists())
 check(Sweep.clearOwnedFrameFiles(dir,"../other").refusal=="directory_name_unsafe")
 val good=File(dir,"good").apply{mkdir()};File(good,name).writeText("frame");File(good,"lock").writeText("retain")
 check(Sweep.clearOwnedFrameFiles(dir,"good").deleted==listOf(name));check(File(good,"lock").exists())
 println("PASS sibling symlink preserved, unsafe name refused, real owned frame removed and unrelated file retained")
 check(Worker.undeliveredRecordBytes(dir)==0L)
 File(dir,"rn").writeText("queued");check(Worker.undeliveredRecordBytes(dir)==6L)
 Worker.bootstrapHook={true};Worker.drainHook={Worker.Outcome.DONE}
 check(Worker.bootstrapThenHook(Context(dir))!!(Context(dir))==Worker.Outcome.RETRY)
 File(dir,"rn").writeText("");check(Worker.undeliveredRecordBytes(dir)==0L)
 Files.createSymbolicLink(File(dir,"sdk").toPath(),File(dir,"sdk").toPath())
 check(Worker.undeliveredRecordBytes(dir)>0L)
 println("PASS absent/empty vs pending queue, bootstrapped DONE retention, real symlink-loop metadata error")
 dir.deleteRecursively()
}
'''
(out/'Probe.kt').write_text(program)
lib=Path(a.compiler_lib);stdlib=next(lib.glob('kotlin-stdlib-[0-9]*.jar'))
subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',str(out/'probe.jar'),str(out/'Probe.kt')],check=True)
subprocess.run(['java','-cp',str(out/'probe.jar')+':'+str(stdlib),'ProbeKt'],check=True)
