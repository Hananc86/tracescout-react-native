#!/usr/bin/env python3
"""Generate a Swift execution harness around unchanged transport method bodies."""
from pathlib import Path
import sys
r=Path(__file__).resolve().parents[2];s=(r/'ios/TraceScoutRN/RnTransport.swift').read_text()
def block(start):
 i=s.index(start);b=s.index('{',i);n=1;j=b+1
 while n:n+=(s[j]=='{')-(s[j]=='}');j+=1
 return s[i:j]
methods='\n'.join(block(x) for x in ['public func currentIdentity()', 'public func applyUserId(', 'public func enqueue(_ build:', 'public func enqueue(expected:', 'private func enqueueRecord('])
print('''import Foundation
import Dispatch
public enum RnEnvelope {
 public struct Identity {
  let projectId:String; let sessionId:String; let anonymousId:String
  let environment:String; let appIdentifier:String; let initialScreen:String; let userId:String?
 }
}
final class Store {
 let lock=NSLock(); var rows:[Data]=[]
 func append(_ d:Data)->Bool {lock.lock();defer{lock.unlock()};rows.append(d);return true}
}
final class RecoveryTrace { static let shared=RecoveryTrace();func log(_ s:String,_ values:[(String,String)] = []) {} }
struct Schedule {
 struct D {let drainNow=false;let armDeadline=false}
 func onEnqueued(pending:Int,deadlineArmed:Bool)->D {D()}
}
final class Subject {
 let lock=NSLock()
 struct ActiveSession {let identity:RnEnvelope.Identity;let openerDurable:Bool;let generation:UInt64;let startedMonotonicNanos:Int64}
 var active:ActiveSession?;var generation:UInt64=1
 var sequence:Int64=0;var store:Store?=Store();var pendingSinceDrain=0;var deadlineArmed=false
 let flushSchedule=Schedule()
 func requestDrain(reason:String) {}
 func armFirstPendingDeadline() {}
 func replace(_ id:RnEnvelope.Identity?) {lock.lock();defer{lock.unlock()};generation += 1;active=id.map{ActiveSession(identity:$0,openerDurable:true,generation:generation,startedMonotonicNanos:0)}}
'''+methods+'''
}
func id(_ session:String,_ user:String)->RnEnvelope.Identity {
 RnEnvelope.Identity(projectId:"project",sessionId:session,anonymousId:"anon",environment:"test",appIdentifier:"test",initialScreen:"screen",userId:user)
}
func bytes(_ n:Int64,_ id:RnEnvelope.Identity)->Data {Data("\\(n):\\(id.sessionId):\\(id.userId ?? "nil")".utf8)}
let s=Subject();s.replace(id("a","old"))
let ready=DispatchSemaphore(value:0),resume=DispatchSemaphore(value:0),done=DispatchSemaphore(value:0)
DispatchQueue.global().async {
 let retained=s.currentIdentity()!;ready.signal();precondition(resume.wait(timeout:.now()+5) == .success)
 precondition(s.enqueue(expected:retained,bytes) == 1);done.signal()
}
precondition(ready.wait(timeout:.now()+5) == .success)
precondition(s.applyUserId("new"));precondition(s.enqueue(expected:s.currentIdentity()!,bytes) == 0)
resume.signal();precondition(done.wait(timeout:.now()+5) == .success)
precondition(s.store!.rows.map{String(data:$0,encoding:.utf8)!} == ["0:a:new","1:a:new"])
print("PASS actual Swift transport controlled callback/identify interleaving")
let retained=s.currentIdentity()!;s.replace(id("b","other"));let next=s.sequence
precondition(s.enqueue(expected:retained,bytes) == nil);precondition(s.sequence == next)
s.replace(nil);precondition(!s.applyUserId("refused"));precondition(s.enqueue(expected:retained,bytes) == nil)
print("PASS old session and missing session refuse without sequence allocation")
''')

# Exercise the production backtrace parser independently of UIKit.
s=(r/'ios/TraceScoutRN/RnIncidentObserver.swift').read_text()
parser=block('static func parseBacktraceLine(')
print('enum BacktraceProbe {\n'+parser+'\n}')
print(r'''let unknown = BacktraceProbe.parseBacktraceLine("4 App 0x100001234 0x100000000 + 4660")
precondition(unknown.module == "App")
precondition(!(unknown.symbol ?? "").contains("0x"))
let known = BacktraceProbe.parseBacktraceLine("4 App 0x100001234 namedFunction + 42")
precondition(known.symbol == "namedFunction")
print("PASS actual Swift backtrace parser strips unsymbolicated addresses and preserves symbols")
''')
