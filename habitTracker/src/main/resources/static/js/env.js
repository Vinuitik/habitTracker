// Runner API through the hub proxy (shared/API.md in the Relay repo). path starts with '/'.
const relayV1 = (dev, path) => `/relay/api/devices/${encodeURIComponent(dev)}/v1${path}`;

window.ENV = {
  ENDPOINTS: {
    AUTH_ME:         '/auth/me',
    LOGOUT:          '/logout',
    TODAY:           '/api/today',
    HABITS_LIST:     '/api/habits',
    HABITS_TABLE:    '/api/habits/table',
    HABITS_RULES:    '/api/habits/rules',
    HABITS_STREAKS:  '/habits/streaks',
    HABIT_UPDATE:    (id) => `/habits/update/${id}`,
    HABIT_INFO:      (id) => `/api/habits/${id}`,
    KPI_LIST:        '/api/kpis',
    KPI_DASHBOARD:   '/api/kpis/dashboard',
    KPI_BY_HABIT:    (id) => `/api/kpis/habits/${id}`,
    KPI_PENDING:     '/api/kpis/pending',
    KPI_CONFIRM:     (name, date) => `/api/kpis/${encodeURIComponent(name)}/data/${date}/confirm`,
    KPI_EDIT_DATA:   (name, date) => `/api/kpis/${encodeURIComponent(name)}/data/${date}`,
    KPI_PROXY:       (name) => `/api/kpis/${encodeURIComponent(name)}/proxy`,
    KPI_CAP:         (name) => `/api/kpis/${encodeURIComponent(name)}/capability`,
    KPI_CAP_REJECT:  (name, id) => `/api/kpis/${encodeURIComponent(name)}/capability/${encodeURIComponent(id)}/reject`,
    KPI_CAP_CONFIRM: (name, id) => `/api/kpis/${encodeURIComponent(name)}/capability/${encodeURIComponent(id)}/confirm`,
    KPI_CAP_RUNNER:  (name) => `/api/kpis/${encodeURIComponent(name)}/capability/runner`,   // {online}
    KPI_CAP_JOBS:    (name) => `/api/kpis/${encodeURIComponent(name)}/capability/jobs`,
    KPI_CAP_ACTIVE:  (name) => `/api/kpis/${encodeURIComponent(name)}/capability/jobs/active`,
    KPI_CAP_PULL:    (name) => `/api/kpis/${encodeURIComponent(name)}/capability/pull`,
    KPI_CAP_JOB:     (name, id, after) => `/api/kpis/${encodeURIComponent(name)}/capability/jobs/${encodeURIComponent(id)}?after=${after}`,
    KPI_AUTOMATE_DEVICES: '/api/kpi/automate/devices',   // [{deviceId, name, os, online}]
    KPI_AUTOMATE_PLAN:    (name) => `/api/kpi/automate/plan?kpiName=${encodeURIComponent(name)}`,   // {runsOn, variants}
    KPI_DEPLOYMENTS:      (kpiId) => `/api/kpi/${encodeURIComponent(kpiId)}/deployments`,
    KPI_DEPLOYMENT_RETRY: (kpiId, id) => `/api/kpi/${encodeURIComponent(kpiId)}/deployments/${encodeURIComponent(id)}/retry`,
    PROJECTS:        '/api/projects',
    PROJECT:         (id) => `/api/projects/${id}`,
    PROJECTS_IMPORT: '/api/projects/import',
    PROJECTS_TRELLO_BOARDS: '/api/projects/trello/boards',
    PROJECT_BOARD:   (id, refresh) => `/api/projects/${id}/board?refresh=${refresh ? 'true' : 'false'}`,
    PLANNER_START:   (id) => `/api/projects/${id}/planner`,
    PLANNER_POLL:    (id, conv, after) => `/api/projects/${id}/planner/${encodeURIComponent(conv)}?after=${after}`,
    PLANNER_LOCK:    (id, conv) => `/api/projects/${id}/planner/${encodeURIComponent(conv)}/lock`,
    PLANNER_APPLY:   (id, conv) => `/api/projects/${id}/planner/${encodeURIComponent(conv)}/apply`,
    PROJECT_LISTS:   (id) => `/api/projects/${id}/lists`,
    PROJECT_LIST:    (id, listId) => `/api/projects/${id}/lists/${encodeURIComponent(listId)}`,
    PROJECT_LIST_MOVE: (id, listId) => `/api/projects/${id}/lists/${encodeURIComponent(listId)}/move`,
    PROJECT_CARDS:   (id) => `/api/projects/${id}/cards`,
    PROJECT_CARD:    (id, cardId) => `/api/projects/${id}/cards/${encodeURIComponent(cardId)}`,
    PROJECT_CARD_COMPLETE: (id, cardId) => `/api/projects/${id}/cards/${encodeURIComponent(cardId)}/complete`,
    PROJECT_CARD_REOPEN:   (id, cardId) => `/api/projects/${id}/cards/${encodeURIComponent(cardId)}/reopen`,
    PROJECT_DELETE_REQUEST: (id) => `/api/projects/${id}/delete-request`,
    PROJECT_DELETE:  (id, token) => `/api/projects/${id}?token=${encodeURIComponent(token)}`,
    TRELLO_CREDENTIALS: '/api/integrations/trello/credentials',
    SYNC_OAUTH_URL:  '/api/sync/oauth/url',
    SYNC_DISCONNECT: '/api/sync/disconnect',
    SYNC_STATUS:     '/api/sync/status',
    SYNC_PAIR_GENERATE: '/api/sync/generate-pairing-code',
    PROJECT_LINK:    (id) => `/api/projects/${id}/link`,
    PROJECT_MAP:     (id) => `/api/projects/${id}/map`,
    PROJECT_MAP_JOB: (id, jobId, after) => `/api/projects/${id}/map/job/${encodeURIComponent(jobId)}?after=${after}`,
    AI_USAGE:        (days) => `/api/ai-usage?days=${days}`,
    RELAY_DEVICES:   '/relay/api/devices',
    RELAY_DEVICE:    (dev) => `/relay/api/devices/${encodeURIComponent(dev)}`,
    RELAY_PAIRING_CODE: '/relay/api/devices/pairing-code',
    RELAY_TRUSTED:   '/relay/api/trusted-devices',
    RELAY_CLAIM:     (id) => `/relay/api/claims/${encodeURIComponent(id)}`,
    RELAY_CLAIM_ACT: (id, approve) => `/relay/api/claims/${encodeURIComponent(id)}/${approve ? 'approve' : 'deny'}`,
    // Every runner call goes through the hub proxy: RELAY_V1(dev, '/sessions') → /relay/api/devices/<dev>/v1/sessions
    RELAY_V1:        relayV1,
    // path omitted/'' = filesystem roots, '~' = Documents (else home) on the runner
    RELAY_BROWSE:    (dev, path) => relayV1(dev, '/browse') + (path ? `?path=${encodeURIComponent(path)}` : ''),
    // KPI credentials held on the runner (names + hosts only come back; the secret is PUT once, never read)
    RELAY_CREDENTIALS: (dev) => relayV1(dev, '/credentials'),
    RELAY_CREDENTIAL:  (dev, name) => relayV1(dev, `/credentials/${encodeURIComponent(name)}`),
  },
  ROUTES: {
    HOME:            '/today',
    LOGIN:           '/login',
    REGISTER:        '/register',
    HABITS_ADD:      '/habits/add',
    HABITS_LIST:     '/habits/list',
    HABITS_TABLE:    '/habits/table',
    HABITS_RULES:    '/habits/rules',
    HABIT_EDIT:      (id) => `/habits/edit/${id}`,
    HABIT_INFO:      (id) => `/habits/info/${id}`,
    KPI_LIST:        '/kpis',
    KPI_CREATE:      '/kpis/create',
    KPI_DASHBOARD:   '/kpis/dashboard',
    CONNECT_DRIVE:   '/connect-drive',
    INSTALL:         '/install',
    PROJECTS:        '/projects',
    AI_USAGE:        '/projects/usage',
    RELAY:           '/relay',
    RELAY_INSTALL:   '/relay#/install',   // install-a-runner guide (not /install = PWA install page)
    TWOFA_VERIFY:    '/2fa/verify',       // + ?next=<url>
  },
  RELAY: {
    WINDOWS_SETUP_URL: 'https://github.com/Vinuitik/relay/releases/latest/download/Relay-Setup.exe',
    SOURCE_URL:        'https://github.com/Vinuitik/relay',
  },
  KPI_DEVICES: {
    LOCAL_CONFIRM_URL: 'http://127.0.0.1:7777/local/confirm',   // runner default port (runner.env); opened ON the device itself
    OS_NAMES: { windows: 'Windows', linux: 'Linux', darwin: 'Mac' },
    MANAGER_NAMES: { relay: 'Relay', winget: 'winget', brew: 'Homebrew', flatpak: 'Flatpak', pip: 'pip' },
    SOURCE_NAMES: { activitywatch: 'ActivityWatch (app and site usage)', file: 'files of an app on the computer' },
  },
  EXTERNAL: {
    TRELLO_POWERUP_ADMIN: 'https://trello.com/power-ups/admin',
  },
  TIMING: {
    RELAY_INSTALL_POLL_MS: 3000,       // #/install waiting panel: GET /relay/api/devices cadence
    RELAY_INSTALL_WAIT_MS: 900000,     // ...gives up after 15 min
    RELAY_RAIL_POLL_MS: 10000, // relay shell: waiting/busy sessions poll for the rail lamps + Needs-you strip
    RELAY_CONTAINERS_LAMP_MS: 30000, // relay shell: per-project GET /containers for the rail lamps (one call per project)
    VIEW_FADE_MS: 180,
    DRAG_HOLD_MS: 250,        // touch long-press before a card drag starts
    UNDO_TOAST_MS: 6000,
    BOARD_POLL_MS: 60000,     // open-board poll for changes made directly in Trello
    PLANNER_POLL_MS: 1200,    // planner conversation poll while the AI is working
    MAP_POLL_MS: 2000,        // repo-map job progress poll
    KPI_DEPLOY_POLL_MS: 15000, // KPI card deployment row refresh while a device is not done yet
    KPI_CAP_POLL_MS: 1500,    // KPI Automate job poll while the runner works
    // containers
    CONTAINERS_POLL_BUSY_MS: 2000,   // Relay Containers tab poll while start/stop runs
    CONTAINERS_POLL_IDLE_MS: 10000,  // ...and otherwise (containers can die on their own)
  },
  LIMITS: {
    RELAY_CHATS_PAGE: 10,     // Relay Chats tab: rows shown per "Show older" step
    RELAY_CTX_WARN: 80000,    // chat context (tokens re-sent per message) shown amber from here...
    RELAY_CTX_HIGH: 150000,   // ...red from here, and resuming such a chat needs a second tap
  },
  BOARD: {
    POS_STEP: 65536,          // Trello pos spacing for append / empty list
    DRAG_START_PX: 5,         // mouse movement before drag starts
    DRAG_HOLD_SLOP_PX: 8,     // touch movement that cancels the long-press
    AUTOSCROLL_EDGE_PX: 48,
    AUTOSCROLL_MAX_PX: 14,
    DEFAULT_DUE_TIME: '12:00',
  },
  PLANNER: {
    DEFAULT_PACE: 2,          // cards/day pre-filled in the planner footer
    MOBILE_QUERY: '(max-width: 639px)',  // stacked tabs layout; keep in sync with styles/organisms/planner.css
  },
  AI_USAGE: {
    DAYS: 30,                 // window of the AI usage page (server caps at 365)
  },
  REPO: {
    MAP_LOG_MAX: 200,         // progress panel keeps the last N job events
    PICKER_START: '~',        // folder picker opens in Documents (else home)
  },
  FEATURES: {
    ANIMATIONS: true,
    PLANNER_MOCK: false,      // true = planner talks to js/organisms/planner-mock.js instead of the API (demo)
  },
};
