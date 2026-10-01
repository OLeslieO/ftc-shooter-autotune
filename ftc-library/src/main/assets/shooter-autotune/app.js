'use strict';
const element = id => document.getElementById(id);
const key = document.querySelector('meta[name="autotune-key"]').content;
const fields = [
  ['dual', 'Shooter layout', 'layout'],
  ['shooter', 'Shooter 1 motor name', 'text'], ['shooterReversed', 'Shooter 1 direction', 'direction'],
  ['secondShooter', 'Shooter 2 motor name', 'text'], ['secondReversed', 'Shooter 2 direction', 'direction'],
  ['preshooter', 'Preshooter motor name', 'text'], ['preshooterReversed', 'Preshooter direction', 'direction'],
  ['preshooterVelocityMode', 'Preshooter control mode', 'mode'], ['preshooterDemand', 'Preshooter power (0–1) or ticks/s', 'number', 0.01, 30000],
  ['targetVelocity', 'Target velocity (ticks/s)', 'number', 100, 26000],
  ['minBatteryVolts', 'Minimum battery voltage (V)', 'number', 9, 14],
  ['targetAcceleration', 'Target ramp (ticks/s²)', 'number', 100, 20000],
  ['recoverySeconds', 'Observe each shot (seconds)', 'number', 1, 5],
  ['shots', 'Shots per candidate', 'number', 2, 8]
];
let state = {};
let initialized = false;
let connected = false;
let sending = false;
let connectionFailures = 0;
const history = [];
for (const [name, labelText, type, minimum, maximum] of fields) {
  const wrapper = document.createElement('div');
  wrapper.className = 'field';
  const label = document.createElement('label');
  label.htmlFor = name;
  label.textContent = labelText;
  const input = document.createElement(['layout', 'direction', 'mode'].includes(type) ? 'select' : 'input');
  input.id = name;
  input.name = name;
  if (input.tagName === 'SELECT') {
    const labels = type === 'layout' ? ['Single', 'Dual'] : type === 'direction' ? ['Forward', 'Reverse'] : ['Power', 'Velocity'];
    labels.forEach((text, index) => input.add(new Option(text, String(Boolean(index)))));
  } else {
    input.type = type;
    input.required = true;
    if (type === 'number') { input.min = minimum; input.max = maximum; input.step = name === 'shots' ? '1' : 'any'; }
    else input.maxLength = 80;
  }
  wrapper.append(label, input);
  element('fields').append(wrapper);
}

function configForm() {
  return Object.fromEntries(fields.map(([name, , type]) => [name, type === 'number' ? Number(element(name).value)
    : type === 'text' ? element(name).value.trim() : element(name).value === 'true']));
}

async function post(action, body = {}) {
  const response = await fetch(`/api/${action}`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-AutoTune-Key': key }, body: JSON.stringify(body), signal: AbortSignal.timeout(500000) });
  if (!response.ok) throw new Error(await response.text());
}

async function command(action, body = {}) {
  sending = true;
  element('error').textContent = '';
  updateButtons();
  try { await post(action, body); }
  catch (error) { element('error').textContent = error.message; }
  finally { sending = false; updateButtons(); }
}

element('config-form').addEventListener('submit', event => { event.preventDefault(); command('configure', configForm()); });
element('stop').addEventListener('click', () => command('stop'));
element('direction').addEventListener('click', () => command('direction', { motor: Number(element('direction-motor').value) }));
element('tune').addEventListener('click', () => { if (element('unloaded').checked) command('tune'); });
element('loaded').addEventListener('click', () => { if (element('armed').checked) command('loaded', { armed: true }); });
element('test').addEventListener('click', () => command('test'));
element('unloaded').addEventListener('change', updateButtons);
element('armed').addEventListener('change', updateButtons);
element('export').addEventListener('click', () => { window.location.href = '/api/export'; });

function updateButtons() {
  const configured = Boolean(state.config?.shooter);
  const idle = !state.busy && state.phase !== 'AWAIT_LOAD';
  const ready = connected && state.active && configured && !sending;
  element('save').disabled = !connected || !idle || sending;
  element('direction').disabled = !ready || !idle;
  element('tune').disabled = !ready || !idle || !element('unloaded').checked;
  element('loaded').disabled = !ready || state.phase !== 'AWAIT_LOAD' || !element('armed').checked;
  element('test').disabled = !ready || !idle;
  element('export').disabled = !connected || !state.validated;
}

const format = value => Number.isFinite(value) ? value.toFixed(1) : '—';
function render(next) {
  state = next;
  if (!initialized && next.savedConfig) {
    for (const [name] of fields) element(name).value = String(next.savedConfig[name]);
    initialized = true;
  }
  element('connection').textContent = next.active ? 'Connected · Driver Station started' : 'Connected · Press Driver Station Start to enable motor experiments';
  element('phase').textContent = next.phase;
  element('message').textContent = next.message;
  element('command').textContent = next.commandMessage;
  element('config-status').textContent = next.config?.shooter ? `Applied: ${next.config.shooter}${next.config.dual ? ' + ' + next.config.secondShooter : ''} · feeder ${next.config.preshooter}` : 'Save configuration to apply motor settings.';
  const motorOptions = next.config?.dual === false ? ['Shooter 1', 'Preshooter'] : ['Shooter 1', 'Shooter 2', 'Preshooter'];
  if (element('direction-motor').options.length !== motorOptions.length) {
    element('direction-motor').replaceChildren(...motorOptions.map((label, index) => new Option(label, String(index))));
  }
  element('target').textContent = `${format(next.target)} ticks/s`;
  for (let index = 0; index < 2; index++) {
    element(`motor${index + 1}`).textContent = next.velocities?.[index] === undefined ? 'Not configured' : `${format(next.velocities[index])} ticks/s · ${format(next.powers[index] * 100)}% · ${format(next.currents[index])} A`;
  }
  element('battery').textContent = `${format(next.battery)} V · feeder ${next.feeding ? 'ON' : 'off'}`;
  element('shot-plan').textContent = `Plan: 3 candidates × ${next.config?.shots || 3} shots + ${next.config?.shots || 3} verification shots. Load enough game pieces; each feed pulse must deliver one into a safe capture area.`;
  element('gains').textContent = JSON.stringify(next.gains, null, 2);
  element('result-status').textContent = next.validated ? 'Loaded verification passed. Export and paste into Constants.java.' : 'Provisional gains. Export is enabled only after loaded verification passes.';
  element('constants').textContent = next.constants || '';
  element('previous').textContent = next.previousConstants || 'None saved.';
  element('trials').textContent = (next.trials || []).join('\n');
  const rows = [];
  for (const shot of next.shots || []) {
    shot.motors.forEach((metrics, index) => {
      const row = document.createElement('tr');
      for (const value of [`${shot.phase} / ${shot.candidate} / ${shot.shot} / ${index + 1}`, format(metrics.velocityDrop), format(metrics.maximumError), metrics.recovered ? format(metrics.recoveryTime) : 'Not recovered', format(metrics.overshoot), format(metrics.rmse)]) {
        const cell = document.createElement('td'); cell.textContent = value; row.append(cell);
      }
      rows.push(row);
    });
  }
  element('shots').replaceChildren(...rows);
  history.push({ time: next.time, target: next.target, motors: next.velocities || [] });
  while (history.length > 0 && history[0].time < next.time - 30) history.shift();
  if (history.length > 300) history.shift();
  drawGraph();
  updateButtons();
}

function drawGraph() {
  const canvas = element('graph');
  const context = canvas.getContext('2d');
  const width = canvas.width;
  const height = canvas.height;
  context.clearRect(0, 0, width, height);
  const maximum = Math.max(100, ...history.flatMap(sample => [sample.target, ...sample.motors])) * 1.1;
  context.font = '14px system-ui';
  context.fillStyle = '#526477';
  context.strokeStyle = '#d8dfe7';
  context.setLineDash([]);
  for (let tick = 0; tick <= 4; tick++) {
    const vertical = 20 + tick * (height - 50) / 4;
    context.beginPath(); context.moveTo(60, vertical); context.lineTo(width - 12, vertical); context.stroke();
    context.fillText(String(Math.round(maximum * (1 - tick / 4))), 4, vertical + 5);
  }
  context.fillText('−30 seconds', 60, height - 5);
  context.fillText('now', width - 45, height - 5);
  const end = history.at(-1)?.time || 0;
  [sample => sample.target, sample => sample.motors[0], sample => sample.motors[1]].forEach((read, series) => {
    context.strokeStyle = ['#526477', '#194a8c', '#8d420d'][series];
    context.lineWidth = 2;
    context.setLineDash([[8, 5], [], [2, 4]][series]);
    context.beginPath();
    let first = true;
    for (const sample of history) {
      const value = read(sample);
      if (!Number.isFinite(value)) continue;
      const horizontal = 60 + (sample.time - end + 30) / 30 * (width - 72);
      const vertical = 20 + (1 - value / maximum) * (height - 50);
      if (first) context.moveTo(horizontal, vertical); else context.lineTo(horizontal, vertical);
      first = false;
    }
    context.stroke();
  });
}

async function poll() {
  try {
    await post('heartbeat');
    const response = await fetch('/api/state', { cache: 'no-store', signal: AbortSignal.timeout(5000) });
    if (!response.ok) throw new Error('Telemetry unavailable');
    const next = await response.json();
    if (!next.phase || !Number.isFinite(next.time)) throw new Error('Waiting for the first robot snapshot');
    connected = true;
    connectionFailures = 0;
    render(next);
  } catch (error) {
    connectionFailures++;
    connected = false;
    element('connection').textContent = `Connection retry ${connectionFailures}: ${error.message || 'Robot Controller unavailable'}`;
    updateButtons();
  } finally { setTimeout(poll, 150); }
}
updateButtons();
poll();
