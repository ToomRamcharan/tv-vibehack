import en from '../locales/en.json';
import pt from '../locales/pt.json';
import es from '../locales/es.json';
import fr from '../locales/fr.json';
import it from '../locales/it.json';
import de from '../locales/de.json';
import nl from '../locales/nl.json';
import ru from '../locales/ru.json';
import zh from '../locales/zh.json';
import te from '../locales/te.json';
import hi from '../locales/hi.json';

// Same 11 languages as the launcher. The app follows the computer's language, like the
// launcher follows the TV's; anything else falls back to English.
const LOCALES = { en, pt, es, fr, it, de, nl, ru, zh, te, hi };

function pickLanguage() {
  for (const tag of navigator.languages?.length ? navigator.languages : [navigator.language]) {
    const base = (tag || '').toLowerCase().split(/[-_]/)[0];
    if (LOCALES[base]) return base;
  }
  return 'en';
}

export const lang = pickLanguage();
document.documentElement.lang = lang;

const ui = { ...en.ui, ...LOCALES[lang].ui };
const backend = LOCALES[lang].backend;

export function t(key, params = {}) {
  return (ui[key] ?? key).replace(/\{(\w+)\}/g, (_, k) => params[k] ?? '');
}

/** Turns **bold** markers in a translated string into <b>, escaping everything else. */
export function rich(text) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
}

const DETAILS = /^(.*) \(details: (.*)\)$/s;
const UNSUPPORTED = /^This TV runs (.+) without Google TV\. It isn't supported yet, so nothing was changed\.$/;
const API_LEVEL = /^Android \(API level (\d+)\)$/;

export function androidName(name) {
  const m = API_LEVEL.exec(name);
  return m ? t('api_level', { n: m[1] }) : name;
}

/**
 * The backend writes its messages in plain English; the English text is the translation key
 * (the `backend` map in each locale). Raw adb details stay untranslated.
 */
export function fromBackend(msg) {
  if (!msg) return msg;
  const d = DETAILS.exec(msg);
  if (d) return `${fromBackend(d[1])} (${t('details')}: ${d[2]})`;
  const u = UNSUPPORTED.exec(msg);
  if (u) return t('unsupported_android', { android: androidName(u[1]) });
  return backend[msg] ?? msg;
}
