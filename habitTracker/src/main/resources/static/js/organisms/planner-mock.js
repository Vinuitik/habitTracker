// Dev mock of the planner API (ENV.FEATURES.PLANNER_MOCK = true). Same call shapes as the real
// adapter in planner.js: start / poll / lock / apply. State lives in memory; each call schedules
// timed "script" steps that run lazily on the next poll, so the UI sees steps tick off, questions,
// and a draft that grows list by list. A brief containing "offline" fakes 503 runner_offline.
window.PlannerMock = (() => {
  const convs = new Map();
  let n = 0;
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const fail = (status, code) => Object.assign(new Error(code), { status, code });
  const pad = (x) => String(x).padStart(2, '0');
  const isoDay = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const clone = (o) => JSON.parse(JSON.stringify(o));

  const SAMPLE = [
    { name: 'Discovery', cards: [
      ['Write a one-page problem statement', 'Who is it for, what hurts today, what success looks like.', 3],
      ['List constraints and non-goals', '', 2],
      ['Collect three reference examples', 'Screenshots or links, one line on what to steal from each.', 1],
    ] },
    { name: 'Build', cards: [
      ['Sketch the data model', 'Entities, fields, and which one is the source of truth.', 3],
      ['Build the smallest end-to-end slice', 'One happy path working, ugly is fine.', 3],
      ['Handle the empty and error states', '', 2],
      ['Add the settings screen', '', 1],
    ] },
    { name: 'Ship', cards: [
      ['Dogfood for three days', 'Use it daily, log every annoyance as a card.', 2],
      ['Fix the top annoyances', '', 2],
      ['Write the release note', '', 1],
    ] },
  ];

  function conv(id) {
    const c = convs.get(id);
    if (!c) throw fail(404, 'conversation_not_found');
    return c;
  }
  const step = (c, text, kind = 'step') => () => c.events.push({ seq: c.seq++, kind, text });
  const say = (c, text) => c.messages.push({ role: 'ai', text });
  function script(c, steps) {
    let t = Date.now();
    c.state = 'running';
    c.queue = steps.map(([delay, fn]) => ({ at: (t += delay), fn }));
  }
  function advance(c) {
    const now = Date.now();
    while (c.queue.length && c.queue[0].at <= now) c.queue.shift().fn();
  }

  async function start(projectId, body) {
    await wait(250);
    if (/offline/i.test(body.message || '')) throw fail(503, 'runner_offline');
    let c = body.conversationId && convs.get(body.conversationId);
    if (!c) {
      c = { id: `mock-${++n}`, seq: 1, events: [], messages: [], questions: [], draft: null, state: 'running', queue: [], turns: 0 };
      convs.set(c.id, c);
    }
    c.messages.push({ role: 'user', text: body.message });
    c.turns += 1;
    if (c.turns === 1) {
      script(c, [
        [500, step(c, 'Reading the board')],
        [1300, step(c, 'Found 3 lists and 12 open cards', 'text')],
        [300, step(c, 'Checking what overlaps with existing cards')],
        [1600, step(c, 'Working out what is unclear')],
        [1400, () => {
          say(c, 'Before I draft, a few things I could not infer:');
          c.questions = [
            'Is there a hard deadline, or is this open-ended?',
            'Should existing cards be reused, or start fresh lists?',
            'Roughly how many hours a day can you give this?',
          ];
          c.state = 'waiting_user';
        }],
      ]);
    } else {
      c.questions = [];
      script(c, [
        [500, step(c, 'Reading your answers')],
        [1400, step(c, 'Updating assumptions')],
        [900, () => {
          say(c, 'Clear. Lock decisions when you are ready and I will draft cards and a schedule.');
          c.state = 'waiting_user';
        }],
      ]);
    }
    return { conversationId: c.id, jobId: `job-${c.seq}` };
  }

  async function poll(projectId, conversationId, after) {
    await wait(120);
    const c = conv(conversationId);
    advance(c);
    return {
      state: c.state,
      events: c.events.filter((e) => e.seq > after),
      nextSeq: c.seq,
      messages: clone(c.messages),
      questions: c.state === 'waiting_user' ? c.questions.slice() : [],
      draft: c.draft ? clone(c.draft) : undefined,
    };
  }

  async function lock(projectId, conversationId) {
    await wait(250);
    const c = conv(conversationId);
    c.questions = [];
    c.draft = { lists: [], schedule: [] };
    const addList = (l) => () => c.draft.lists.push({ name: l.name, cards: l.cards.map(([title, desc, importance]) => ({ title, desc, importance })) });
    const schedule = () => {
      const all = c.draft.lists.flatMap((l) => l.cards);
      const day = new Date();
      c.draft.schedule = all.map((card, i) => {
        if (i % 2 === 0) day.setDate(day.getDate() + (day.getDay() === 5 ? 3 : 1)); // skip weekends
        return { date: isoDay(day), title: card.title, importance: card.importance };
      });
      all.forEach((card) => { card.due = c.draft.schedule.find((s) => s.title === card.title).date; });
    };
    script(c, [
      [400, step(c, 'Locking decisions')],
      [900, step(c, 'Breaking the brief into cards')],
      [1200, addList(SAMPLE[0])],
      [100, step(c, 'Grouping cards into lists')],
      [1100, addList(SAMPLE[1])],
      [900, addList(SAMPLE[2])],
      [100, step(c, 'Rating importance')],
      [1000, step(c, 'Laying out the schedule')],
      [1300, schedule],
      [500, () => {
        say(c, `Draft ready: ${c.draft.lists.reduce((k, l) => k + l.cards.length, 0)} cards in ${c.draft.lists.length} lists. Edit anything on the right, then Add to board.`);
        c.state = 'drafted';
      }],
    ]);
    return { conversationId: c.id, jobId: `job-${c.seq}` };
  }

  async function apply(projectId, conversationId, body) {
    await wait(900);
    conv(conversationId);
    return { created: body.draft.lists.reduce((k, l) => k + l.cards.length, 0) };
  }

  return { start, poll, lock, apply };
})();
