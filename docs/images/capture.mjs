// README 화면 캡처: 헤드리스 Chrome을 DevTools 프로토콜로 몰아 1280x860 PNG를 찍는다.
// 사용: node shots.mjs <base url> <출력 폴더>
import { spawn } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [base, out] = process.argv.slice(2);
const PORT = 9333;
const chrome = spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), 'shots-'))}`,
  '--window-size=1280,860', '--hide-scrollbars', '--no-first-run', 'about:blank'], { stdio: 'ignore' });
const sleep = ms => new Promise(r => setTimeout(r, ms));

let target;
for (let i = 0; i < 50 && !target; i++) {
  await sleep(200);
  try { target = (await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()).find(t => t.type === 'page'); } catch { /* 아직 안 뜸 */ }
}
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r));
let seq = 0;
const pending = new Map();
ws.addEventListener('message', e => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } });
const send = (method, params = {}) => new Promise(r => { const id = ++seq; pending.set(id, r); ws.send(JSON.stringify({ id, method, params })); });
const js = async expr => (await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result?.result?.value;
const until = async (expr, seconds = 90) => { for (let i = 0; i < seconds * 2; i++) { if (await js(expr)) return true; await sleep(500); } throw new Error('시간 초과: ' + expr); };
const go = async path => { await send('Page.navigate', { url: base + path }); await until("document.readyState === 'complete'"); await sleep(600); };
const shot = async name => {
  await sleep(500);
  const png = (await send('Page.captureScreenshot', { format: 'png' })).result.data;
  writeFileSync(join(out, name), Buffer.from(png, 'base64'));
  console.log('saved', name);
};

await send('Page.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 860, deviceScaleFactor: 1, mobile: false });

try {
  // 1. 결과지 해설받기: 첫 샘플 해설
  await go('/demo.html');
  await js('localStorage.clear()');
  await go('/demo.html');
  await until("document.querySelectorAll('#sample option').length > 1");
  await js("document.getElementById('run').click()");
  await until("!document.getElementById('result').hidden && document.getElementById('status').textContent === ''");
  await js("window.scrollTo(0, document.querySelector('h1').getBoundingClientRect().top + scrollY - 90)");
  await shot('demo.png');

  // 2. 후속 질문: 진단 질문은 답하지 않음
  await js("document.getElementById('fq-question').value = '제가 당뇨병인가요?'; document.getElementById('fq-run').click()");
  await until("document.getElementById('fq-answers').children.length > 0 && !document.getElementById('fq-run').disabled");
  await js("const h = [...document.querySelectorAll('h2')].find(e => e.textContent.includes('묻기')); window.scrollTo(0, h.getBoundingClientRect().top + scrollY - 90)");
  await shot('demo-followup.png');

  // 3. 통계 질문 (로컬 전용 화면)
  await go('/stats.html');
  await js("document.getElementById('q').value = '시도별 현재 흡연자 비율'; document.getElementById('go').click()");
  await until("document.getElementById('answers').children.length > 0 && !document.getElementById('go').disabled");
  await js('window.scrollTo(0, 0)');
  await shot('stats.png');

  // 4. 품질
  await go('/quality.html');
  await until("document.querySelectorAll('table tr').length > 3");
  await shot('quality.png');
} catch (e) {
  console.error('실패:', e.message, '| status:', await js("document.getElementById('status')?.textContent"));
  process.exitCode = 1;
} finally {
  ws.close();
  chrome.kill();
}
