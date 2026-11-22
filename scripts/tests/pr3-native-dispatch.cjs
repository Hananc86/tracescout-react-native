'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require('typescript');
const root = path.resolve(__dirname, '../..');
const source = fs.readFileSync(path.join(root, 'src/index.ts'), 'utf8');
const ast = ts.createSourceFile('index.ts', source, ts.ScriptTarget.Latest, true);
const methods = ast.statements.filter(n => ts.isFunctionDeclaration(n) && n.modifiers?.some(m => m.kind === ts.SyntaxKind.ExportKeyword) && n.name.text !== 'isAvailable');
assert.equal(methods.length, 12);
async function execute(text) {
  const calls = [];
  const bridge = {};
  for (const method of methods) bridge[method.name.text] = async function (...args) {
    assert.equal(this, bridge, 'native dispatcher lost receiver');
    calls.push({name:method.name.text, args});
    return 'native:' + method.name.text;
  };
  const exports = {};
  const js = ts.transpileModule(text, {compilerOptions:{module:ts.ModuleKind.CommonJS, target:ts.ScriptTarget.ES2020}}).outputText;
  vm.runInNewContext(js, {exports, require(name) {assert.equal(name, 'react-native');return {NativeModules:{TraceScoutRn:bridge}};}});
  assert.equal(exports.isAvailable(), true);
  for (const method of methods) {
    const name = method.name.text;
    const args = method.parameters.map(p => p.name.text === 'never' ? false : p.name.text === 'rootHandle' ? 42 : 'fixture:' + p.name.text);
    assert.equal(await exports[name](...args), 'native:' + name);
    const call = calls.pop();
    assert.equal(call.name, name);
    assert.deepEqual(call.args, args, 'native dispatcher lost arguments for ' + name);
    assert.equal(exports.default[name], exports[name]);
  }
}
(async () => {
  await execute(source);
  console.log('PASS actual TypeScript wrappers and shared dispatcher delivered all 12 native calls and arguments');
  const before = 'fn.apply(bridge, args as unknown[])';
  assert.ok(source.includes(before));
  await assert.rejects(execute(source.replace(before, 'fn.apply(bridge, [])')), /native dispatcher lost arguments/);
  console.log('PASS compiled empty-argument dispatcher mutation rejected');
})().catch(error => {console.error(error);process.exitCode=1;});
