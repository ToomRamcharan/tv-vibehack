import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { t } from './i18n.js';

// Step ids from the backend; each label is the translation key `step_<id>`.
export const STEPS = {
  install: ['install', 'permissions', 'home', 'open', 'check'],
  restore: ['remove_service', 'restore_launchers', 'uninstall', 'go_home'],
};

// Must match the backend's copy: the replace prompt arrives as a Failed step event.
export const REPLACE_PROMPT =
  'An older version is installed. Replacing it resets your dock order and hidden apps. Continue?';
const LOST_PREFIX = 'Lost the connection';
const NOT_CONNECTED = 'Not connected to a TV.';

export const w = $state({
  screen: 'welcome',
  mode: 'install',
  busy: false,
  flowRunning: false,
  // Bumped on every screen change so the screen remounts with fresh state.
  visit: 0,
});

export const work = $state({
  // Translation key of the Working screen's heading.
  title: '',
  rows: {},
  replace: false,
  error: '',
  failed: false,
  reconnect: false,
});

export function go(screen) {
  w.screen = screen;
  w.visit++;
}

// Runs one backend command at a time; every control is disabled until it settles,
// because the backend keeps a single TV session.
export async function exclusive(fn) {
  if (w.busy) return;
  w.busy = true;
  try {
    await fn();
  } finally {
    w.busy = false;
  }
}

export function text(err) {
  return typeof err === 'string'
    ? err
    : (err && err.message) || t('generic_error');
}

export const scanTvs = () => invoke('scan_tvs');
export const connectTv = (ip) => invoke('connect_tv', { ip });

listen('step', ({ payload }) => {
  const row = work.rows[payload.step];
  if (!row) return;
  const { state, message } = payload.state;
  if (state === 'failed') {
    // The replace prompt is a question, not a failure; runFlow shows it once the command returns.
    if (message === REPLACE_PROMPT) return;
    row.state = 'failed';
    row.message = message;
  } else {
    row.state = state;
    row.message = '';
  }
});

export function runFlow(allowReplace) {
  return exclusive(async () => {
    go('working');
    Object.assign(work, {
      title: w.mode === 'install' ? 'working_install' : 'working_restore',
      rows: Object.fromEntries(STEPS[w.mode].map((id) => [id, { state: 'pending', message: '' }])),
      replace: false,
      error: '',
      failed: false,
      reconnect: false,
    });
    w.flowRunning = true;
    try {
      await (w.mode === 'install' ? invoke('run_install', { allowReplace }) : invoke('run_restore'));
      go('done');
    } catch (e) {
      if (e && e.kind === 'needs_replace') {
        work.rows.install = { state: 'pending', message: '' };
        work.title = 'working_question';
        work.replace = true;
        return;
      }
      const msg = text(e);
      // Step rows already carry their own message; only add a box when none did.
      if (!Object.values(work.rows).some((r) => r.state === 'failed')) work.error = msg;
      work.title = 'working_failed';
      // The backend drops the TV session when the link is lost, so retrying can't work; reconnect first.
      work.reconnect = msg.startsWith(LOST_PREFIX) || msg === NOT_CONNECTED;
      work.failed = true;
    } finally {
      w.flowRunning = false;
    }
  });
}
