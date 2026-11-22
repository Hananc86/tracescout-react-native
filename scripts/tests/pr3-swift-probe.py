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

s=(r/'ios/TraceScoutRN/IncidentPolicy.swift').read_text()
policy=block('public static func safeExceptionType(')
print('enum ExceptionPrivacyProbe {\n'+policy+'\n}')
print(r'''for raw in ["RCTFatalException: token=synthetic-secret", "user@example.test", "NSRangeException private detail"] {
    precondition(ExceptionPrivacyProbe.safeExceptionType(raw) == "NSException", "exception name privacy leak")
}
precondition(ExceptionPrivacyProbe.safeExceptionType("NSRangeException") == "NSRangeException")
precondition(ExceptionPrivacyProbe.safeExceptionType("RCTFatalException") == "RCTFatalException")
print("PASS actual Swift exception policy excludes arbitrary names and preserves exact known identifiers")
''')

# Compile the actual budget predicate and actual identity bound. Exercise growth
# using the production JSON string encoder, not a duplicated byte-count formula.
s=(r/'ios/TraceScoutRN/RnBridge.swift').read_text()
budget=block('private static func hierarchyProbeFits(').replace('private static','static')
import re
identity=(r/'ios/TraceScoutRN/IdentityPolicy.swift').read_text()
limit=re.search(r'public static let userIdMax = [0-9]+',identity).group()
print('enum IdentityPolicy { '+limit+' }\nenum BudgetProbe {\n'+budget+'\n}')
s=(r/'ios/TraceScoutRN/RnEnvelope.swift').read_text()
print('enum WireEscaping {\n'+block('static func str(')+'\n}')
print(r'''for payloadSize in [63000, 65000] {
    let anonymous = "{\"payload\":" + WireEscaping.str(String(repeating: "x", count: payloadSize)) + "}"
    let probe = Data(anonymous.utf8)
    for user in [String(repeating: "\u{0001}", count: IdentityPolicy.userIdMax), String(repeating: "😀", count: IdentityPolicy.userIdMax)] {
        let current = String(anonymous.dropLast()) + ",\"userId\":" + WireEscaping.str(user) + "}"
        let actual = Data(current.utf8)
        _ = try! JSONSerialization.jsonObject(with: actual)
        if BudgetProbe.hierarchyProbeFits(probe.count) {
            precondition(actual.count <= 65536, "hierarchy identity growth exceeded budget")
        }
    }
}
precondition(BudgetProbe.hierarchyProbeFits(63000))
print("PASS actual Swift hierarchy predicate bounds escaped and multi-byte identity growth")
''')
observer=(r/'ios/TraceScoutRN/RnIncidentObserver.swift').read_text()
assert 'Thread.current.name' not in observer
assert 'threadName: Thread.current.isMainThread ? "main" : "background"' in observer

# The actual watchdog generation owner must retire a sleeping old thread.
s=observer
print(block('struct WatchdogGeneration {'))
print(r'''var watchdogEpoch = WatchdogGeneration()
let retired = watchdogEpoch.begin()
precondition(watchdogEpoch.accepts(retired))
watchdogEpoch.cancel()
precondition(!watchdogEpoch.accepts(retired), "cancelled watchdog remains active")
let replacement = watchdogEpoch.begin()
precondition(watchdogEpoch.accepts(replacement))
precondition(!watchdogEpoch.accepts(retired), "retired watchdog revived after reinstall")
print("PASS actual Swift watchdog generation retires old threads across reinstall")
''')
assert 'let running = watchdogGeneration.accepts(token)' in observer
assert 'watchdogGeneration.cancel()' in observer

s=observer
print(block('struct HangObservation {'))
print(r'''var observation = HangObservation()
precondition(observation.blockedMillis(heartbeat: 1, now: 0, active: true, epoch: 1) == nil)
precondition(observation.blockedMillis(heartbeat: 1, now: 10_000_000_000, active: false, epoch: 2) == nil, "suspension counted as hang")
precondition(observation.blockedMillis(heartbeat: 1, now: 60_000_000_000, active: false, epoch: 2) == nil)
precondition(observation.blockedMillis(heartbeat: 1, now: 61_000_000_000, active: true, epoch: 3) == nil)
precondition(observation.blockedMillis(heartbeat: 1, now: 64_100_000_000, active: true, epoch: 3) == 3100)
precondition(observation.blockedMillis(heartbeat: 2, now: 64_200_000_000, active: true, epoch: 3) == nil)
print("PASS actual Swift hang observation excludes suspension and retains real active stalls")
''')
print('enum HandlerOwnershipProbe {\n'+block('static func canRegisterHandler(')+'\n'+block('static func shouldRestoreHandler(')+'\n}')
print(r'''precondition(HandlerOwnershipProbe.shouldRestoreHandler(installed: true, current: 2, ours: 2))
precondition(!HandlerOwnershipProbe.shouldRestoreHandler(installed: true, current: 3, ours: 2), "foreign exception handler overwritten")
precondition(!HandlerOwnershipProbe.shouldRestoreHandler(installed: false, current: 2, ours: 2), "repeated uninstall overwrote host handler")
precondition(!HandlerOwnershipProbe.canRegisterHandler(registered: true, current: 3, ours: 2))
precondition(HandlerOwnershipProbe.canRegisterHandler(registered: true, current: 2, ours: 2))
precondition(HandlerOwnershipProbe.canRegisterHandler(registered: false, current: 1, ours: 2))
print("PASS actual Swift exception-handler ownership preserves newer and restored registrations")
''')
assert 'UIApplication.willResignActiveNotification' in observer
assert 'UIApplication.shared.applicationState == .active' in observer
