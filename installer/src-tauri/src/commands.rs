use std::cell::Cell;
use std::net::Ipv4Addr;
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Mutex, PoisonError};
use std::thread;
use std::time::{Duration, Instant};

use tauri::path::BaseDirectory;
use tauri::{AppHandle, Emitter, Manager, State};

use crate::adb::{ensure_key, AdbTv};
use crate::detect::{android_name, choose_method, read_info, Support};
use crate::flow::{install_flow, restore_flow, FlowError, Step, StepState, REPLACE_PROMPT};
use crate::scan::scan;
use crate::tv::{Tv, TvError};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(60);
/// Longest any install or restore may take; past this the Wi-Fi link is assumed dead.
const FLOW_TIMEOUT: Duration = Duration::from_secs(5 * 60);
const SCAN_TIMEOUT: Duration = Duration::from_millis(400);
const NO_ANSWER: &str = "The TV didn't answer. Check it's on, on the same Wi-Fi, and that you chose Always allow on the TV, then press Connect again.";
const BAD_IP: &str = "That doesn't look like an IP address (example: 192.168.1.50).";
const NOT_CONNECTED: &str = "Not connected to a TV.";
const LOST: &str = "Lost the connection to the TV. Check the Wi-Fi and try again.";
const APK_MISSING: &str = "The launcher file is missing from this app. Please download it again.";
const INSTALL_REFUSED: &str = "The TV refused to install the launcher.";
const INTERNAL: &str = "Something went wrong inside the app. Please try again.";
const KEY_FAILED: &str = "Couldn't prepare this computer to talk to the TV.";

/// The connected TV. Flows take it out while they run, so it is never locked across blocking work.
#[derive(Default)]
pub struct Session(pub Mutex<Option<AdbTv>>);

#[derive(Debug, Clone, serde::Serialize)]
pub struct StepEvent {
    pub step: Step,
    pub state: StepState,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct ConnectResult {
    pub model: String,
    pub android: String,
    pub support: Support,
}

fn parse_ip(s: &str) -> Result<Ipv4Addr, String> {
    let s = s.trim();
    let s = s.strip_suffix(":5555").unwrap_or(s);
    s.parse().map_err(|_| BAD_IP.to_string())
}

/// Raw adb text only ever comes out of the install step; every other message is already plain.
fn plain_message(step: Step, lost: bool, msg: &str) -> String {
    if lost {
        LOST.into()
    } else if step == Step::Install && msg != REPLACE_PROMPT {
        format!("{INSTALL_REFUSED} (details: {msg})")
    } else {
        msg.into()
    }
}

/// adb waits only 10 s for the "Allow debugging" answer, so keep asking until our own deadline.
fn retry_connect(err: &TvError, now: Instant, deadline: Instant) -> bool {
    matches!(err, TvError::NoAnswer(_)) && now < deadline
}

fn connect_error_text(err: &TvError) -> String {
    match err {
        TvError::NoAnswer(_) => NO_ANSWER.into(),
        other => format!("{NO_ANSWER} (details: {other})"),
    }
}

/// Runs a flow against the TV and reports whether the connection dropped on the way.
fn drive(
    tv: &mut dyn Tv,
    flow: impl FnOnce(&mut dyn Tv, &mut dyn FnMut(Step, StepState)) -> Result<(), FlowError>,
    emit: &mut dyn FnMut(StepEvent),
) -> (Result<(), FlowError>, bool) {
    struct Watch<'a> {
        inner: &'a mut dyn Tv,
        lost: &'a Cell<bool>,
    }

    impl Tv for Watch<'_> {
        fn shell(&mut self, cmd: &str) -> Result<String, TvError> {
            let r = self.inner.shell(cmd);
            self.lost.set(self.lost.get() || is_lost(&r));
            r
        }

        fn install(&mut self, apk: &std::path::Path) -> Result<(), TvError> {
            let r = self.inner.install(apk);
            self.lost.set(self.lost.get() || is_lost(&r));
            r
        }
    }

    fn is_lost<T>(r: &Result<T, TvError>) -> bool {
        matches!(r, Err(TvError::Connection(_) | TvError::NoAnswer(_)))
    }

    let lost = Cell::new(false);
    let failed_step = Cell::new(None);
    let mut watch = Watch {
        inner: tv,
        lost: &lost,
    };
    let result = flow(&mut watch, &mut |step, state| {
        let state = match state {
            StepState::Failed(m) => {
                failed_step.set(Some(step));
                StepState::Failed(plain_message(step, lost.get(), &m))
            }
            other => other,
        };
        emit(StepEvent { step, state });
    });
    let result = match result {
        Err(FlowError::Failed(m)) => Err(FlowError::Failed(match failed_step.get() {
            Some(step) => plain_message(step, lost.get(), &m),
            // Failed before any step started, so the message is already plain (or the link dropped).
            None if lost.get() => LOST.into(),
            None => m,
        })),
        other => other,
    };
    (result, lost.get())
}

/// What the watchdog knows about a flow running on its worker thread.
#[derive(Default)]
struct Progress {
    step: Option<Step>,
    abandoned: bool,
}

/// adb reads never time out, so a Wi-Fi drop mid-step would hang forever. Runs the flow on
/// its own thread and gives up after `limit`; the TV comes back only if the link held.
fn watched<T: Tv + Send + 'static>(
    mut tv: T,
    flow: impl FnOnce(&mut dyn Tv, &mut dyn FnMut(Step, StepState)) -> Result<(), FlowError>
        + Send
        + 'static,
    emit: impl Fn(StepEvent) + Send + Sync + Clone + 'static,
    limit: Duration,
) -> (Option<T>, Result<(), FlowError>) {
    let progress = Arc::new(Mutex::new(Progress::default()));
    let (tx, rx) = mpsc::channel();
    {
        let progress = progress.clone();
        let emit = emit.clone();
        thread::spawn(move || {
            let (result, lost) = drive(&mut tv, flow, &mut |event| {
                // Held while emitting so nothing from a flow we gave up on reaches the screen.
                let mut p = progress.lock().unwrap_or_else(PoisonError::into_inner);
                if p.abandoned {
                    return;
                }
                if event.state == StepState::Running {
                    p.step = Some(event.step);
                }
                emit(event);
            });
            let _ = tx.send((tv, result, lost));
        });
    }

    match rx.recv_timeout(limit) {
        Ok((tv, result, lost)) => ((!lost).then_some(tv), result),
        Err(RecvTimeoutError::Timeout) => {
            let mut p = progress.lock().unwrap_or_else(PoisonError::into_inner);
            p.abandoned = true;
            if let Some(step) = p.step {
                emit(StepEvent {
                    step,
                    state: StepState::Failed(LOST.into()),
                });
            }
            (None, Err(FlowError::Failed(LOST.into())))
        }
        Err(RecvTimeoutError::Disconnected) => (None, Err(FlowError::Failed(INTERNAL.into()))),
    }
}

#[tauri::command]
pub async fn scan_tvs() -> Vec<String> {
    tauri::async_runtime::spawn_blocking(|| {
        scan(SCAN_TIMEOUT).iter().map(|ip| ip.to_string()).collect()
    })
    .await
    .unwrap_or_default()
}

#[tauri::command]
pub async fn connect_tv(
    ip: String,
    app: AppHandle,
    state: State<'_, Session>,
) -> Result<ConnectResult, String> {
    let ip = parse_ip(&ip)?;
    let key = app
        .path()
        .app_data_dir()
        .map_err(|e| e.to_string())
        .and_then(|dir| ensure_key(&dir).map_err(|e| e.to_string()))
        .map_err(|e| format!("{KEY_FAILED} (details: {e})"))?;

    let (tx, rx) = mpsc::channel();
    // adb has no connect timeout of its own, so the thread is left behind if the TV never answers.
    thread::spawn(move || {
        let deadline = Instant::now() + CONNECT_TIMEOUT;
        let connected = loop {
            match AdbTv::connect(ip, &key) {
                Err(e) if retry_connect(&e, Instant::now(), deadline) => continue,
                other => break other,
            }
        };
        let outcome = connected.and_then(|mut tv| {
            let info = read_info(&mut tv)?;
            let result = ConnectResult {
                support: choose_method(&info),
                android: android_name(info.sdk),
                model: info.model,
            };
            Ok((tv, result))
        });
        let _ = tx.send(outcome);
    });

    let outcome = tauri::async_runtime::spawn_blocking(move || rx.recv_timeout(CONNECT_TIMEOUT))
        .await
        .map_err(|_| INTERNAL.to_string())?;
    match outcome {
        Ok(Ok((tv, result))) => {
            *state.0.lock().map_err(|_| INTERNAL.to_string())? = Some(tv);
            Ok(result)
        }
        Ok(Err(e)) => Err(connect_error_text(&e)),
        Err(_) => Err(NO_ANSWER.to_string()),
    }
}

#[tauri::command]
pub async fn run_install(
    allow_replace: bool,
    app: AppHandle,
    state: State<'_, Session>,
) -> Result<(), FlowError> {
    let apk = app
        .path()
        .resolve("resources/nlrc.apk", BaseDirectory::Resource)
        .ok()
        .filter(|p| p.is_file())
        .ok_or_else(|| FlowError::Failed(APK_MISSING.into()))?;
    run_flow(&app, &state, move |tv, report| {
        install_flow(tv, &apk, allow_replace, report)
    })
    .await
}

#[tauri::command]
pub async fn run_restore(app: AppHandle, state: State<'_, Session>) -> Result<(), FlowError> {
    run_flow(&app, &state, restore_flow).await
}

async fn run_flow(
    app: &AppHandle,
    state: &State<'_, Session>,
    flow: impl FnOnce(&mut dyn Tv, &mut dyn FnMut(Step, StepState)) -> Result<(), FlowError>
        + Send
        + 'static,
) -> Result<(), FlowError> {
    let taken = state
        .0
        .lock()
        .map_err(|_| FlowError::Failed(INTERNAL.into()))?
        .take();
    let tv = taken.ok_or_else(|| FlowError::Failed(NOT_CONNECTED.into()))?;

    let app = app.clone();
    let emit = move |event: StepEvent| {
        let _ = app.emit("step", event);
    };
    let joined =
        tauri::async_runtime::spawn_blocking(move || watched(tv, flow, emit, FLOW_TIMEOUT)).await;

    let Ok((tv, result)) = joined else {
        return Err(FlowError::Failed(INTERNAL.into()));
    };
    if let Some(tv) = tv {
        *state
            .0
            .lock()
            .map_err(|_| FlowError::Failed(INTERNAL.into()))? = Some(tv);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fake::FakeTv;

    #[test]
    fn parse_ip_accepts_padded_and_port() {
        assert_eq!(
            parse_ip(" 192.168.1.50 "),
            Ok(Ipv4Addr::new(192, 168, 1, 50))
        );
        assert_eq!(
            parse_ip("192.168.1.50:5555"),
            Ok(Ipv4Addr::new(192, 168, 1, 50))
        );
    }

    #[test]
    fn parse_ip_rejects_garbage() {
        for bad in ["192.168.1", "", "tv.local", "192.168.1.256"] {
            assert_eq!(parse_ip(bad), Err(BAD_IP.to_string()), "{bad}");
        }
    }

    #[test]
    fn install_failures_get_a_plain_sentence_with_details() {
        let m = plain_message(Step::Install, false, "Failure [INSTALL_FAILED_OLDER_SDK]");
        assert_eq!(
            m,
            "The TV refused to install the launcher. (details: Failure [INSTALL_FAILED_OLDER_SDK])"
        );
    }

    #[test]
    fn replace_prompt_and_other_steps_pass_through() {
        assert_eq!(
            plain_message(Step::Install, false, REPLACE_PROMPT),
            REPLACE_PROMPT
        );
        assert_eq!(plain_message(Step::Check, false, "plain"), "plain");
    }

    #[test]
    fn lost_connection_wins_on_any_step() {
        assert_eq!(plain_message(Step::Home, true, "os error 104"), LOST);
        assert_eq!(plain_message(Step::Install, true, "broken pipe"), LOST);
    }

    #[test]
    fn connect_retries_only_unanswered_attempts_before_the_deadline() {
        let now = Instant::now();
        let later = now + Duration::from_secs(1);
        assert!(retry_connect(
            &TvError::NoAnswer("os error 11".into()),
            now,
            later
        ));
        assert!(!retry_connect(
            &TvError::NoAnswer("os error 11".into()),
            later,
            now
        ));
        assert!(!retry_connect(
            &TvError::Connection("refused".into()),
            now,
            later
        ));
    }

    #[test]
    fn connect_errors_read_plainly() {
        assert_eq!(
            connect_error_text(&TvError::NoAnswer("os error 11".into())),
            NO_ANSWER
        );
        assert_eq!(
            connect_error_text(&TvError::Connection("refused".into())),
            format!("{NO_ANSWER} (details: refused)")
        );
    }

    /// A TV whose Wi-Fi dropped without closing the socket: reads never return.
    struct Hangs;

    impl Tv for Hangs {
        fn shell(&mut self, _: &str) -> Result<String, TvError> {
            loop {
                thread::park();
            }
        }

        fn install(&mut self, _: &std::path::Path) -> Result<(), TvError> {
            loop {
                thread::park();
            }
        }
    }

    fn collect() -> (
        Arc<Mutex<Vec<StepEvent>>>,
        impl Fn(StepEvent) + Send + Sync + Clone + 'static,
    ) {
        let events = Arc::new(Mutex::new(vec![]));
        let sink = events.clone();
        (events, move |e| sink.lock().unwrap().push(e))
    }

    #[test]
    fn watchdog_gives_up_on_a_hung_tv() {
        let (events, emit) = collect();
        let (tv, r) = watched(Hangs, restore_flow, emit, Duration::from_millis(100));
        assert!(tv.is_none());
        assert_eq!(r, Err(FlowError::Failed(LOST.into())));
        let events = events.lock().unwrap();
        let last = events.last().unwrap();
        assert_eq!(
            (last.step, &last.state),
            (Step::RemoveService, &StepState::Failed(LOST.into()))
        );
    }

    #[test]
    fn watchdog_hands_back_the_tv_when_the_flow_finishes() {
        let tv = FakeTv::new()
            .on("getprop ro.product.model", "MIBOX3")
            .on("getprop ro.build.version.sdk", "28");
        let (_, emit) = collect();
        let (tv, r) = watched(tv, restore_flow, emit, Duration::from_secs(10));
        assert!(tv.is_some());
        assert_eq!(r, Ok(()));
    }

    #[test]
    fn watchdog_drops_the_tv_when_the_link_is_lost() {
        let (_, emit) = collect();
        let (tv, r) = watched(Dropped, restore_flow, emit, Duration::from_secs(10));
        assert!(tv.is_none());
        assert_eq!(r, Err(FlowError::Failed(LOST.into())));
    }

    struct Dropped;

    impl Tv for Dropped {
        fn shell(&mut self, _: &str) -> Result<String, TvError> {
            Err(TvError::Connection("broken pipe".into()))
        }

        fn install(&mut self, _: &std::path::Path) -> Result<(), TvError> {
            Err(TvError::Connection("broken pipe".into()))
        }
    }

    #[test]
    fn dropped_connection_is_reported_plainly() {
        let mut events = vec![];
        let (r, lost) = drive(&mut Dropped, |tv, rep| restore_flow(tv, rep), &mut |e| {
            events.push(e)
        });
        assert!(lost);
        assert_eq!(r, Err(FlowError::Failed(LOST.into())));
        assert_eq!(events.last().unwrap().state, StepState::Failed(LOST.into()));
    }

    #[test]
    fn refused_install_keeps_the_session_and_explains() {
        let mut tv = FakeTv::new()
            .on("getprop ro.product.model", "Acer TV")
            .on("getprop ro.build.version.sdk", "34")
            .on(
                "pm list packages",
                "package:com.google.android.apps.tv.launcherx\n",
            );
        tv.install_results.push_back(Err(TvError::InstallFailed(
            "Failure [INSTALL_FAILED_OLDER_SDK]".into(),
        )));
        let mut events = vec![];
        let apk = std::path::Path::new("x.apk");
        let (r, lost) = drive(
            &mut tv,
            |t, rep| install_flow(t, apk, false, rep),
            &mut |e| events.push(e),
        );
        let expected =
            "The TV refused to install the launcher. (details: Failure [INSTALL_FAILED_OLDER_SDK])";
        assert!(!lost);
        assert_eq!(r, Err(FlowError::Failed(expected.into())));
        assert_eq!(
            events.last().unwrap().state,
            StepState::Failed(expected.into())
        );
    }

    #[test]
    fn failure_before_any_step_is_not_blamed_on_install() {
        let mut tv = FakeTv::new()
            .on("getprop ro.product.model", "Acer TV")
            .on("getprop ro.build.version.sdk", "abc");
        let apk = std::path::Path::new("x.apk");
        let (r, lost) = drive(
            &mut tv,
            |t, rep| install_flow(t, apk, false, rep),
            &mut |_| {},
        );
        assert!(!lost);
        assert_eq!(
            r,
            Err(FlowError::Failed(
                "Couldn't read the TV's Android version".into()
            ))
        );
    }

    #[test]
    fn needs_replace_emits_prompt_and_returns_unchanged() {
        let mut tv = FakeTv::new()
            .on("getprop ro.product.model", "Acer TV")
            .on("getprop ro.build.version.sdk", "34")
            .on(
                "pm list packages",
                "package:com.google.android.apps.tv.launcherx\n",
            );
        tv.install_results.push_back(Err(TvError::InstallFailed(
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]".into(),
        )));
        let mut events = vec![];
        let apk = std::path::Path::new("x.apk");
        let (r, lost) = drive(
            &mut tv,
            |t, rep| install_flow(t, apk, false, rep),
            &mut |e| events.push(e),
        );
        assert!(!lost);
        assert_eq!(r, Err(FlowError::NeedsReplace));
        let last = events.last().unwrap();
        assert_eq!(last.step, Step::Install);
        assert_eq!(last.state, StepState::Failed(REPLACE_PROMPT.into()));
    }

    /// The UI translates backend messages by their English text, so every message the user
    /// can see must have an entry in each locale, and every locale must have every screen key.
    #[test]
    fn every_message_is_translated() {
        use crate::detect::{NO_HOME_APP, SDK_UNREADABLE};
        use crate::flow::{
            CANT_CHECK_HOME, GOOGLE_TV_AFTER_ALL, NOT_HOME, NOT_REMOVED, NO_STOCK_HOME,
        };

        let messages = [
            NO_ANSWER,
            BAD_IP,
            NOT_CONNECTED,
            LOST,
            APK_MISSING,
            INSTALL_REFUSED,
            INTERNAL,
            KEY_FAILED,
            NOT_HOME,
            NO_STOCK_HOME,
            NOT_REMOVED,
            REPLACE_PROMPT,
            GOOGLE_TV_AFTER_ALL,
            CANT_CHECK_HOME,
            NO_HOME_APP,
            SDK_UNREADABLE,
        ];
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../ui/src/locales");
        let read = |lang: &str| -> serde_json::Value {
            let text = std::fs::read_to_string(dir.join(format!("{lang}.json"))).unwrap();
            serde_json::from_str(&text).unwrap()
        };
        let en = read("en");
        let en_keys: Vec<&String> = en["ui"].as_object().unwrap().keys().collect();
        for lang in ["pt", "es", "fr", "it", "de", "nl", "ru", "zh", "te", "hi"] {
            let locale = read(lang);
            let keys: Vec<&String> = locale["ui"].as_object().unwrap().keys().collect();
            assert_eq!(keys, en_keys, "{lang}.json screen keys differ from en.json");
            for msg in messages {
                assert!(
                    locale["backend"][msg].is_string(),
                    "{lang}.json has no translation for: {msg}"
                );
            }
            let extra = locale["backend"].as_object().unwrap().len();
            assert_eq!(
                extra,
                messages.len(),
                "{lang}.json has stale backend entries"
            );
        }
    }
}
