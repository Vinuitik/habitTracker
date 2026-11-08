// Device part of the KPI Automate dialog (mounted from kpi-list.js):
//   chips  — which paired runners the KPI should run on (name + OS + online dot), multi-select; one device =
//            preselected and the row hidden; none = nothing mounted (kpi-list shows "Connect a runner").
//   plan   — approval screen: "Runs on: Desktop (Windows), Laptop (Linux)" + per OS what gets installed, in words.
// Data: ENV.ENDPOINTS.KPI_AUTOMATE_DEVICES / KPI_AUTOMATE_PLAN (KpiAutomateDevicesController). All text via textContent.
// Styles: styles/organisms/automate-devices.css, styles/atoms/lamp.css
window.AutomateDevices = (() => {
  const EP = ENV.ENDPOINTS;
  const CFG = ENV.KPI_DEVICES;
  let devices = [];
  const selected = new Set();

  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  const osName = (os) => (os && (CFG.OS_NAMES[os] || os)) || null;
  const label = (d) => (d.os ? `${d.name} (${osName(d.os)})` : d.name);

  async function getJson(url) {
    const res = await fetch(url, { credentials: 'include' });
    if (!res.ok) throw new Error(String(res.status));
    return res.status === 204 ? null : res.json();
  }

  // Fetch the owner's devices. Online ones are preselected; failure = no chips (the dialog still works).
  async function load() {
    try {
      devices = (await getJson(EP.KPI_AUTOMATE_DEVICES)) || [];
    } catch (e) {
      devices = [];
    }
    selected.clear();
    devices.filter((d) => d.online).forEach((d) => selected.add(d.deviceId));
    if (!selected.size && devices.length === 1) selected.add(devices[0].deviceId);
  }

  const targetIds = () => devices.filter((d) => selected.has(d.deviceId)).map((d) => d.deviceId);

  // Appends the chip row to `parent`; nothing is mounted for 0 or 1 device (1 is preselected, 0 = "Connect a runner").
  function mountChips(parent) {
    if (devices.length < 2) return;
    const box = el('div', 'form-group device-chips');
    const heading = el('span', 'form-group__label', 'Which computers should track this?');
    const row = el('div', 'device-chips__row');
    row.setAttribute('role', 'group');
    row.setAttribute('aria-label', 'Computers');
    devices.forEach((d) => {
      const chip = el('button', 'device-chip');
      chip.type = 'button';
      const on = () => selected.has(d.deviceId);
      const sync = () => { chip.classList.toggle('device-chip--selected', on()); chip.setAttribute('aria-pressed', String(on())); };
      const dot = el('span', 'lamp' + (d.online ? ' lamp--on' : ''));
      dot.setAttribute('aria-hidden', 'true');
      chip.append(dot, el('span', 'device-chip__name', d.name));
      if (d.os) chip.append(el('span', 'device-chip__os', osName(d.os)));
      chip.append(el('span', 'device-chip__state', d.online ? 'online' : 'offline'));
      chip.addEventListener('click', () => { if (on()) selected.delete(d.deviceId); else selected.add(d.deviceId); sync(); });
      sync();
      row.append(chip);
    });
    box.append(heading, row, el('span', 'form-group__hint',
      'An offline computer is set up as soon as it comes online. Pick none to let the AI use the default one.'));
    parent.append(box);
  }

  // "Installs ActivityWatch via winget — tracks app usage"
  function installText(r) {
    const mgr = CFG.MANAGER_NAMES[r.manager] || r.manager;
    let name = r.id || 'a package';
    if (r.manager === 'winget' && name.includes('.')) name = name.split('.').slice(1).join('.') || name;
    if (r.manager === 'relay' && name === 'activitywatch') name = 'ActivityWatch';
    return `Installs ${name} via ${mgr}` + (r.why ? ` — ${r.why}` : '');
  }

  // Approval screen block. Placeholder first (the screen renders synchronously), filled when the plan arrives.
  function mountPlan(body, kpiName) {
    const box = el('section', 'device-plan');
    body.append(box);
    getJson(EP.KPI_AUTOMATE_PLAN(kpiName)).then((plan) => {
      if (!plan || !box.isConnected) return;
      if (plan.runsOn && plan.runsOn.length) {
        box.append(el('div', 'device-plan__runs', 'Runs on: ' + plan.runsOn.map(label).join(', ')));
      }
      (plan.variants || []).forEach((v) => {
        const os = osName(v.os);
        const list = el('ul', 'device-plan__list');
        if (v.installs && v.installs.length) v.installs.forEach((r) => list.append(el('li', null, installText(r))));
        else list.append(el('li', null, 'Nothing to install.'));
        const sources = (v.sources || []).map((s) => CFG.SOURCE_NAMES[s] || s).filter(Boolean);
        const block = el('div', 'device-plan__os');
        block.append(el('div', 'device-plan__os-name', os ? `On ${os}` : 'On the computer'), list);
        if (sources.length) block.append(el('div', 'form-group__hint', 'Reads: ' + sources.join(', ')));
        box.append(block);
      });
    }).catch(() => { /* optional info: the rest of the screen stands without it */ });
  }

  return { load, mountChips, mountPlan, targetIds, installText, label };
})();
