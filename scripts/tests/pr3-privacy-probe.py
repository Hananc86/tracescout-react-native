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
 var elevation=0f;var scaleX=1f;var scaleY=1f;var rotation=0f;var scrollX=0;var scrollY=0
 val rootView:View get()=(parent as? View)?.rootView ?: this
 val matrix:android.graphics.Matrix get() {
  val rad=Math.toRadians(rotation.toDouble());val c=kotlin.math.cos(rad).toFloat();val s=kotlin.math.sin(rad).toFloat()
  val a=c*scaleX;val b=s*scaleX;val d=-s*scaleY;val e=c*scaleY
  return android.graphics.Matrix(a,b,d,e,width/2f-a*width/2f-d*height/2f,height/2f-b*width/2f-e*height/2f)
 }
 fun mapGlobal(p:FloatArray) {
  matrix.mapPoints(p)
  val host=parent as? View
  for(i in p.indices step 2){p[i]+=left-(host?.scrollX?:0);p[i+1]+=top-(host?.scrollY?:0)}
  host?.mapGlobal(p)
 }
 fun getGlobalVisibleRect(r:android.graphics.Rect):Boolean {
  if(visibility!=VISIBLE)return false
  val p=floatArrayOf(0f,0f,width.toFloat(),0f,0f,height.toFloat(),width.toFloat(),height.toFloat());mapGlobal(p)
  val root=IntArray(2);rootView.getLocationOnScreen(root)
  r.left=kotlin.math.floor(listOf(p[0],p[2],p[4],p[6]).min()).toInt()-root[0]
  r.right=kotlin.math.ceil(listOf(p[0],p[2],p[4],p[6]).max()).toInt()-root[0]
  r.top=kotlin.math.floor(listOf(p[1],p[3],p[5],p[7]).min()).toInt()-root[1]
  r.bottom=kotlin.math.ceil(listOf(p[1],p[3],p[5],p[7]).max()).toInt()-root[1]
  return r.right>r.left && r.bottom>r.top
 }
 fun findFocus():View?=null
 open fun onCheckIsTextEditor()=false
 fun getLocationOnScreen(loc:IntArray) {val p=floatArrayOf(0f,0f);mapGlobal(p);loc[0]=kotlin.math.round(p[0]).toInt();loc[1]=kotlin.math.round(p[1]).toInt()}
}
open class ViewGroup:View() {
 val children=ArrayList<View>();val childCount get()=children.size
 fun getChildAt(i:Int)=children[i]
 fun add(v:View){children.add(v);v.parent=this}
}
object WindowManager { class LayoutParams {var flags=0;companion object{const val FLAG_SECURE=8192}} }
''',
    'Matrix.kt': '''package android.graphics
class Matrix(var a:Float=1f,var b:Float=0f,var c:Float=0f,var d:Float=1f,var tx:Float=0f,var ty:Float=0f) {
 val isIdentity get()=a==1f&&b==0f&&c==0f&&d==1f&&tx==0f&&ty==0f
 fun mapPoints(p:FloatArray){for(i in p.indices step 2){val x=p[i];val y=p[i+1];p[i]=a*x+c*y+tx;p[i+1]=b*x+d*y+ty}}
 fun invert(out:Matrix):Boolean {val det=a*d-b*c;if(det==0f)return false
 out.a=d/det;out.b=-b/det;out.c=-c/det;out.d=a/det;out.tx=-(out.a*tx+out.c*ty);out.ty=-(out.b*tx+out.d*ty);return true}
}
''',
    'Rect.kt': 'package android.graphics\nclass Rect {var left=0;var top=0;var right=0;var bottom=0}',
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
 root.width=300;safe.left=100;blocked.visibility=View.VISIBLE;blocked.scaleX=2f
 check(Resolver.resolve(root,125f,50f).positionTreatment==Treatment.BLOCKED) {"scaled blocked overlay leaked coordinates"}
 root.children.clear();root.width=300;root.height=300
 val rotated=View().apply{tag="ts-position-safe";left=100;top=100;rotation=45f};root.add(rotated)
 check(Resolver.resolve(root,85f,85f).positionTreatment!=Treatment.ALLOWED){"rotated bounding box authorized outside safe view"}
 check(Resolver.resolve(root,150f,150f).positionTreatment==Treatment.ALLOWED){"valid rotated safe hit disabled"}
 rotated.rotation=0f;rotated.scaleX=2f
 check(Resolver.resolve(root,75f,150f).positionTreatment==Treatment.ALLOWED){"valid scaled safe hit disabled"}
 println("PASS actual resolver: overlap blocked; scaled/rotated hits map to local bounds")
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
if '--unscaled-control' in sys.argv:
    start = resolver.index('    private fun contains(')
    resolver = resolver[:start] + """    private fun contains(v: View, x: Float, y: Float): Boolean {
        val loc=IntArray(2);v.getLocationOnScreen(loc)
        return x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height
    }
}
"""
if '--bounds-control' in sys.argv:
    start = resolver.index('    private fun contains(')
    resolver = resolver[:start] + """    private fun contains(v: View, x: Float, y: Float): Boolean {
        val bounds=android.graphics.Rect();if(!v.getGlobalVisibleRect(bounds))return false
        val loc=IntArray(2);v.rootView.getLocationOnScreen(loc)
        return x>=bounds.left+loc[0] && x<bounds.right+loc[0] && y>=bounds.top+loc[1] && y<bounds.bottom+loc[1]
    }
}
"""
(out / 'InteractionTargetResolver.kt').write_text(resolver)
lib = root / 'scripts/ci/android/build/probe-compiler'
stdlib = next(lib.glob('kotlin-stdlib-[0-9]*.jar'))
jar = out / 'probe.jar'
subprocess.run(['java', '-Xmx512m', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-classpath', str(stdlib), '-d', str(jar),
                *map(str, out.glob('*.kt')), str(base / 'InteractionPolicy.kt')], check=True)
result = subprocess.run(['java', '-cp', f'{jar}:{stdlib}', 'ProbeKt'], text=True, capture_output=True)
print(result.stdout + result.stderr)
if any(flag in sys.argv for flag in ['--control','--unscaled-control','--bounds-control']):
    expected = 'rotated bounding box authorized outside safe view' if '--bounds-control' in sys.argv else ('scaled blocked overlay leaked coordinates' if '--unscaled-control' in sys.argv else 'elevated blocked overlay leaked coordinates')
    assert result.returncode != 0 and expected in result.stderr
    print('PASS old production resolver fails the privacy regression')
else:
    result.check_returncode()
