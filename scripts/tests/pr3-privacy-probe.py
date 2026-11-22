#!/usr/bin/env python3
"""Run the actual resolver and policy against deterministic Android view doubles."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
out = root / 'ci-results/privacy'
out.mkdir(parents=True, exist_ok=True)
sources = {
    'View.kt': '''package android.view
open class View {
 companion object { const val VISIBLE=0 }
 var visibility=VISIBLE; var tag:Any?=null; var parent:Any?=null
 var layoutParams:Any?=null; var width=100; var height=100; var left=0; var top=0
 var elevation=0f
 fun findFocus():View?=null
 open fun onCheckIsTextEditor()=false
 fun getLocationOnScreen(loc:IntArray) {loc[0]=left;loc[1]=top}
}
open class ViewGroup:View() {
 val children=ArrayList<View>();val childCount get()=children.size
 fun getChildAt(i:Int)=children[i]
 fun add(v:View){children.add(v);v.parent=this}
}
object WindowManager { class LayoutParams {var flags=0;companion object{const val FLAG_SECURE=8192}} }
''',
    'WebView.kt': 'package android.webkit\nopen class WebView:android.view.ViewGroup()',
    'EditText.kt': 'package android.widget\nclass EditText:android.view.View(){var inputType=0}',
    'InputType.kt': '''package android.text
object InputType {const val TYPE_MASK_VARIATION=4080;const val TYPE_MASK_CLASS=15
const val TYPE_CLASS_TEXT=1;const val TYPE_TEXT_VARIATION_PASSWORD=128
const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD=144;const val TYPE_TEXT_VARIATION_WEB_PASSWORD=224
const val TYPE_CLASS_NUMBER=2;const val TYPE_NUMBER_VARIATION_PASSWORD=16}
''',
    'Tags.kt': '''package com.tracescout.mobile.capture
object ViewHierarchySnapshotter {const val TAG_BLOCK="ts-block";const val TAG_NEVER_RECORD="ts-never-record"}
''',
    'Probe.kt': '''import android.view.*
import com.tracescout.mobile.capture.interaction.InteractionTargetResolver as Resolver
import com.tracescout.mobile.capture.interaction.InteractionPolicy.PositionTreatment as Treatment
fun main() {
 val root=ViewGroup(); val blocked=View().apply{tag="ts-block";elevation=20f}
 val safe=View().apply{tag="ts-position-safe"}
 root.add(blocked);root.add(safe)
 check(Resolver.resolve(root,10f,10f).positionTreatment==Treatment.BLOCKED) {"elevated blocked overlay leaked coordinates"}
 root.children.reverse()
 check(Resolver.resolve(root,10f,10f).positionTreatment==Treatment.BLOCKED)
 blocked.left=200
 check(Resolver.resolve(root,10f,10f).positionTreatment==Treatment.ALLOWED) {"unambiguous safe view was disabled"}
 blocked.left=0;blocked.visibility=8
 check(Resolver.resolve(root,10f,10f).positionTreatment==Treatment.ALLOWED)
 println("PASS actual resolver: elevated/reordered overlap blocked; disjoint/hidden overlay preserves safe capture")
}
''',
}
for name, content in sources.items():
    (out / name).write_text(content)
base = root / 'android/src/main/java/com/tracescout/mobile/capture/interaction'
resolver = (base / 'InteractionTargetResolver.kt').read_text()
if '--control' in sys.argv:
    start = resolver.index('            for (i in g.childCount - 1 downTo 0) {')
    end = resolver.index('            if (next == null)', start)
    resolver = resolver[:start] + """            for (i in g.childCount - 1 downTo 0) {
                val c = g.getChildAt(i)
                if (c.visibility == View.VISIBLE && contains(c, x, y)) { next = c; break }
            }
""" + resolver[end:]
(out / 'InteractionTargetResolver.kt').write_text(resolver)
lib = root / 'scripts/ci/android/build/probe-compiler'
stdlib = next(lib.glob('kotlin-stdlib-[0-9]*.jar'))
jar = out / 'probe.jar'
subprocess.run(['java', '-Xmx512m', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-classpath', str(stdlib), '-d', str(jar),
                *map(str, out.glob('*.kt')), str(base / 'InteractionPolicy.kt')], check=True)
result = subprocess.run(['java', '-cp', f'{jar}:{stdlib}', 'ProbeKt'], text=True, capture_output=True)
print(result.stdout + result.stderr)
if '--control' in sys.argv:
    assert result.returncode != 0 and 'elevated blocked overlay leaked coordinates' in result.stderr
    print('PASS old production resolver fails the privacy regression')
else:
    result.check_returncode()
