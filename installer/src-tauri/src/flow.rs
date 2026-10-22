use std::path::Path;

use crate::a11y::{with_service, without_service, SERVICE};
use crate::detect::{
    choose_method, parse_packages, read_info, HomeMethod, Support, LAUNCHERX, PATCHWALL, TVLAUNCHER,
};
use crate::tv::{Tv, TvError};

const PACKAGE: &str = "com.gathin.tvhome";
const A11Y_KEY: &str = "enabled_accessibility_services";
pub(crate) const NOT_HOME: &str =
    "The launcher was installed but isn't set as home yet. Try again.";
pub(crate) const NO_STOCK_HOME: &str = "We couldn't turn your original home screen back on, so the launcher was kept to keep your TV usable. Nothing else was changed.";
pub(crate) const NOT_REMOVED: &str = "The launcher couldn't be removed. Try again.";
pub(crate) const GOOGLE_TV_AFTER_ALL: &str =
    "This TV has Google TV after all, so nothing was changed on its home screen.";
pub(crate) const CANT_CHECK_HOME: &str =
    "We couldn't check this TV's home screen app, so nothing was changed on its home screen.";
pub const REPLACE_PROMPT: &str =
    "An older version is installed. Replacing it resets your dock order and hidden apps. Continue?";

#[derive(Debug, Clone, Copy, serde::Serialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Step {
    Install,
    Permissions,
    Home,
    Open,
    Check,
    RemoveService,
    RestoreLaunchers,
    Uninstall,
    GoHome,
}

#[derive(Debug, Clone, serde::Serialize, PartialEq)]
#[serde(tag = "state", content = "message", rename_all = "snake_case")]
pub enum StepState {
    Running,
    Done,
    Failed(String),
}

#[derive(Debug, Clone, serde::Serialize, PartialEq, thiserror::Error)]
#[serde(tag = "kind", content = "message", rename_all = "snake_case")]
pub enum FlowError {
    #[error("needs replace")]
    NeedsReplace,
    #[error("{0}")]
    Unsupported(String),
    #[error("{0}")]
    Failed(String),
}

impl From<TvError> for FlowError {
    fn from(e: TvError) -> Self {
        FlowError::Failed(e.to_string())
    }
}

/// Settings global key listing the packages we disabled, so restore knows what to re-enable.
pub const MARKER: &str = "nlrc_setup_disabled";

pub fn install_flow(
    tv: &mut dyn Tv,
    apk: &Path,
    allow_replace: bool,
    report: &mut dyn FnMut(Step, StepState),
) -> Result<(), FlowError> {
    let info = read_info(tv)?;
    let method = match choose_method(&info) {
        Support::Supported(m) => m,
        Support::Unsupported(msg) => return Err(FlowError::Unsupported(msg)),
    };

    run_step(report, Step::Install, || install(tv, apk, allow_replace))?;
    run_step(report, Step::Permissions, || grant_permissions(tv))?;
    run_step(report, Step::Home, || set_home(tv, method))?;
    run_step(report, Step::Open, || {
        tv.shell(&format!("am start -n {PACKAGE}/.MainActivity"))?;
        Ok(())
    })?;
    run_step(report, Step::Check, || check(tv, method))
}

pub fn restore_flow(
    tv: &mut dyn Tv,
    report: &mut dyn FnMut(Step, StepState),
) -> Result<(), FlowError> {
    run_step(report, Step::RemoveService, || remove_service(tv))?;
    run_step(report, Step::RestoreLaunchers, || restore_launchers(tv))?;
    run_step(report, Step::Uninstall, || uninstall(tv))?;
    run_step(report, Step::GoHome, || {
        tv.shell("input keyevent KEYCODE_HOME")?;
        Ok(())
    })
}

fn remove_service(tv: &mut dyn Tv) -> Result<(), FlowError> {
    let current = tv.shell(&format!("settings get secure {A11Y_KEY}"))?;
    let remaining = without_service(&current);
    // adb drops an empty argument, so an empty list must be deleted rather than put.
    if remaining.is_empty() {
        tv.shell(&format!("settings delete secure {A11Y_KEY}"))?;
        tv.shell("settings put secure accessibility_enabled 0")?;
    } else {
        tv.shell(&format!(
            "settings put secure {A11Y_KEY} {}",
            quoted(&remaining)
        ))?;
    }
    Ok(())
}

fn restore_launchers(tv: &mut dyn Tv) -> Result<(), FlowError> {
    let marked = tv.shell(&format!("settings get global {MARKER}"))?;
    // Older versions wrote the marker space-separated, so accept both.
    let mut to_enable: Vec<String> = marked
        .split(|c: char| c == ',' || c.is_whitespace())
        .filter(|p| !p.is_empty() && *p != "null")
        .map(String::from)
        .collect();

    // Safety net beyond the marker: never leave a stock launcher disabled, including
    // TVs that were set up by hand.
    let info = read_info(tv)?;
    for pkg in [LAUNCHERX, TVLAUNCHER] {
        if info.has(pkg) && info.is_disabled(pkg) && !to_enable.iter().any(|p| p == pkg) {
            to_enable.push(pkg.to_string());
        }
    }

    for pkg in &to_enable {
        tv.shell(&format!("pm enable {pkg}"))?;
    }
    tv.shell(&format!("settings delete global {MARKER}"))?;
    Ok(())
}

fn uninstall(tv: &mut dyn Tv) -> Result<(), FlowError> {
    let installed = parse_packages(&tv.shell("pm list packages")?);
    if !installed.iter().any(|p| p == PACKAGE) {
        return Ok(());
    }
    // Without the launcher and with no stock home screen enabled, the TV would have no home at all.
    let disabled = parse_packages(&tv.shell("pm list packages -d")?);
    let usable =
        |pkg: &str| installed.iter().any(|p| p == pkg) && !disabled.iter().any(|p| p == pkg);
    if ![LAUNCHERX, TVLAUNCHER, PATCHWALL].into_iter().any(usable) {
        return Err(FlowError::Failed(NO_STOCK_HOME.into()));
    }
    tv.shell(&format!("pm uninstall {PACKAGE}"))?;
    if is_installed(tv)? {
        return Err(FlowError::Failed(NOT_REMOVED.into()));
    }
    Ok(())
}

fn is_installed(tv: &mut dyn Tv) -> Result<bool, FlowError> {
    let ours = format!("package:{PACKAGE}");
    Ok(tv
        .shell(&format!("pm list packages {PACKAGE}"))?
        .lines()
        .any(|l| l.trim() == ours))
}

/// `adb shell` runs the command through `sh -c`, so values with `$` must be single-quoted.
fn quoted(value: &str) -> String {
    format!("'{value}'")
}

pub(crate) fn run_step(
    report: &mut dyn FnMut(Step, StepState),
    step: Step,
    body: impl FnOnce() -> Result<(), FlowError>,
) -> Result<(), FlowError> {
    report(step, StepState::Running);
    match body() {
        Ok(()) => {
            report(step, StepState::Done);
            Ok(())
        }
        Err(e) => {
            let message = match &e {
                FlowError::NeedsReplace => REPLACE_PROMPT.to_string(),
                other => other.to_string(),
            };
            report(step, StepState::Failed(message));
            Err(e)
        }
    }
}

fn install(tv: &mut dyn Tv, apk: &Path, allow_replace: bool) -> Result<(), FlowError> {
    let Err(TvError::InstallFailed(msg)) = tv.install(apk) else {
        return Ok(());
    };
    if msg.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") {
        tv.shell("pm trim-caches 999G")?;
        return Ok(tv.install(apk)?);
    }
    if msg.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
        if !allow_replace {
            return Err(FlowError::NeedsReplace);
        }
        tv.shell(&format!("pm uninstall {PACKAGE}"))?;
        return Ok(tv.install(apk)?);
    }
    Err(FlowError::Failed(msg))
}

fn grant_permissions(tv: &mut dyn Tv) -> Result<(), FlowError> {
    tv.shell(&format!(
        "pm grant {PACKAGE} android.permission.WRITE_SECURE_SETTINGS"
    ))?;
    tv.shell(&format!("appops set {PACKAGE} SYSTEM_ALERT_WINDOW allow"))?;
    Ok(())
}

fn set_home(tv: &mut dyn Tv, method: HomeMethod) -> Result<(), FlowError> {
    match method {
        HomeMethod::Accessibility => {
            let current = tv.shell(&format!("settings get secure {A11Y_KEY}"))?;
            tv.shell(&format!(
                "settings put secure {A11Y_KEY} {}",
                quoted(&with_service(&current))
            ))?;
            tv.shell("settings put secure accessibility_enabled 1")?;
        }
        HomeMethod::DisableStock { patchwall } => {
            // Disabling Google TV's launcher bootloops the TV, so look again right before acting.
            let fresh = parse_packages(&tv.shell("pm list packages")?);
            if fresh.iter().any(|p| p == LAUNCHERX) {
                return Err(FlowError::Failed(GOOGLE_TV_AFTER_ALL.into()));
            }
            // An empty or cut-off listing would hide launcherx too, so fail closed.
            if !fresh.iter().any(|p| p == TVLAUNCHER) {
                return Err(FlowError::Failed(CANT_CHECK_HOME.into()));
            }
            let mut disabled = vec![TVLAUNCHER];
            if patchwall {
                disabled.push(PATCHWALL);
            }
            // Recorded first so restore can undo a disable that half-finished. Commas, not
            // spaces: `settings put` would store only the first word.
            tv.shell(&format!(
                "settings put global {MARKER} {}",
                disabled.join(",")
            ))?;
            for pkg in &disabled {
                tv.shell(&format!("pm disable-user --user 0 {pkg}"))?;
            }
        }
    }
    Ok(())
}

fn check(tv: &mut dyn Tv, method: HomeMethod) -> Result<(), FlowError> {
    let installed = is_installed(tv)?;
    let home_set = match method {
        HomeMethod::Accessibility => tv
            .shell(&format!("settings get secure {A11Y_KEY}"))?
            .split(':')
            .any(|e| e == SERVICE),
        HomeMethod::DisableStock { .. } => parse_packages(&tv.shell("pm list packages -d")?)
            .iter()
            .any(|p| p == TVLAUNCHER),
    };
    if installed && home_set {
        Ok(())
    } else {
        Err(FlowError::Failed(NOT_HOME.into()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fake::FakeTv;

    const OURS: &str = "package:com.gathin.tvhome\n";

    fn base(model: &str, sdk: &str, packages: &str, disabled: &str) -> FakeTv {
        FakeTv::new()
            .on("getprop ro.product.model", model)
            .on("getprop ro.build.version.sdk", sdk)
            .on("pm list packages com.gathin.tvhome", OURS)
            .on("pm list packages -d", disabled)
            .on("pm list packages", packages)
            .after("install", "pm list packages com.gathin.tvhome", OURS)
            .after(
                "pm uninstall com.gathin.tvhome",
                "pm list packages com.gathin.tvhome",
                "",
            )
    }

    fn acer_tv() -> FakeTv {
        base(
            "Acer TV",
            "34",
            "package:com.google.android.apps.tv.launcherx\npackage:com.google.android.tvlauncher\n",
            "",
        )
    }

    fn mi_tv(patchwall: bool) -> FakeTv {
        mi_tv_with_disabled(patchwall, "package:com.google.android.tvlauncher\n")
    }

    fn mi_tv_with_disabled(patchwall: bool, disabled: &str) -> FakeTv {
        let packages = if patchwall {
            "package:com.google.android.tvlauncher\npackage:com.mitv.tvhome.atv\n"
        } else {
            "package:com.google.android.tvlauncher\n"
        };
        let reenabled = disabled.replace("package:com.google.android.tvlauncher\n", "");
        base("MIBOX3", "28", packages, disabled).after(
            "pm enable com.google.android.tvlauncher",
            "pm list packages -d",
            &reenabled,
        )
    }

    fn android12_tvlauncher_tv() -> FakeTv {
        base(
            "Odd TV",
            "31",
            "package:com.google.android.tvlauncher\n",
            "",
        )
    }

    fn run_install<T: Tv>(mut tv: T, allow: bool) -> (T, Result<(), FlowError>) {
        let r = run_install_with(&mut tv, allow, &mut |_, _| {});
        (tv, r)
    }

    fn run_install_with(
        tv: &mut dyn Tv,
        allow: bool,
        report: &mut dyn FnMut(Step, StepState),
    ) -> Result<(), FlowError> {
        let dir = tempfile::tempdir().unwrap();
        let apk = dir.path().join("x.apk");
        std::fs::write(&apk, b"apk").unwrap();
        install_flow(tv, &apk, allow, report)
    }

    fn has(tv: &FakeTv, cmd: &str) -> bool {
        tv.log.iter().any(|c| c == cmd)
    }

    fn index(tv: &FakeTv, cmd: &str) -> usize {
        tv.log.iter().position(|c| c == cmd).unwrap()
    }

    fn put_services(list: &str) -> String {
        format!("settings put secure enabled_accessibility_services '{list}'")
    }

    #[test]
    fn acer_never_disables_anything() {
        let (tv, r) = run_install(acer_tv(), false);
        assert!(r.is_ok());
        assert!(!tv.log.iter().any(|c| c.starts_with("pm disable")));
        assert!(has(&tv, &put_services(SERVICE)));
        assert!(has(&tv, "settings put secure accessibility_enabled 1"));
    }

    #[test]
    fn mi_disables_tvlauncher_and_patchwall_and_records_marker() {
        let (mut tv, r) = run_install(mi_tv(true), false);
        assert!(r.is_ok());
        let marker = "settings put global nlrc_setup_disabled com.google.android.tvlauncher,com.mitv.tvhome.atv";
        let first_disable = index(
            &tv,
            "pm disable-user --user 0 com.google.android.tvlauncher",
        );
        assert!(index(&tv, marker) < first_disable);
        assert!(has(&tv, "pm disable-user --user 0 com.mitv.tvhome.atv"));
        assert_eq!(
            tv.shell("settings get global nlrc_setup_disabled").unwrap(),
            "com.google.android.tvlauncher,com.mitv.tvhome.atv"
        );
    }

    #[test]
    fn mi_install_then_restore_reenables_both() {
        let (tv, r) = run_install(mi_tv(true), false);
        assert!(r.is_ok());
        let (tv, r) = run_restore(tv);
        assert!(r.is_ok());
        assert!(has(&tv, "pm enable com.google.android.tvlauncher"));
        assert!(has(&tv, "pm enable com.mitv.tvhome.atv"));
    }

    #[test]
    fn mi_without_patchwall_leaves_it_alone() {
        let (tv, r) = run_install(mi_tv(false), false);
        assert!(r.is_ok());
        assert!(!tv.log.iter().any(|c| c.contains("com.mitv.tvhome.atv")));
        assert!(has(
            &tv,
            "settings put global nlrc_setup_disabled com.google.android.tvlauncher"
        ));
    }

    #[test]
    fn unsupported_changes_nothing() {
        let (tv, r) = run_install(android12_tvlauncher_tv(), false);
        assert!(matches!(r, Err(FlowError::Unsupported(_))));
        assert!(!tv.log.iter().any(|c| c == "install"
            || c.starts_with("pm ") && !c.starts_with("pm list")
            || c.starts_with("settings put")));
    }

    #[test]
    fn grants_permissions() {
        let (tv, r) = run_install(acer_tv(), false);
        assert!(r.is_ok());
        assert!(has(
            &tv,
            "pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS"
        ));
        assert!(has(
            &tv,
            "appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow"
        ));
    }

    #[test]
    fn low_space_trims_and_retries_once() {
        let mut tv = acer_tv();
        tv.install_results.push_back(Err(TvError::InstallFailed(
            "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]".into(),
        )));
        tv.install_results.push_back(Ok(()));
        let (tv, r) = run_install(tv, false);
        assert!(r.is_ok());
        let installs: Vec<usize> = tv
            .log
            .iter()
            .enumerate()
            .filter(|(_, c)| *c == "install")
            .map(|(i, _)| i)
            .collect();
        let trim = tv
            .log
            .iter()
            .position(|c| c == "pm trim-caches 999G")
            .unwrap();
        assert_eq!(installs.len(), 2);
        assert!(installs[0] < trim && trim < installs[1]);
    }

    #[test]
    fn low_space_twice_fails_without_third_attempt() {
        let mut tv = acer_tv();
        for _ in 0..2 {
            tv.install_results.push_back(Err(TvError::InstallFailed(
                "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]".into(),
            )));
        }
        let (tv, r) = run_install(tv, false);
        assert!(matches!(r, Err(FlowError::Failed(_))));
        assert_eq!(tv.log.iter().filter(|c| *c == "install").count(), 2);
    }

    #[test]
    fn incompatible_without_permission_asks() {
        let mut tv = acer_tv();
        tv.install_results.push_back(Err(TvError::InstallFailed(
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]".into(),
        )));
        let (tv, r) = run_install(tv, false);
        assert_eq!(r, Err(FlowError::NeedsReplace));
        assert!(!tv.log.iter().any(|c| c.starts_with("pm uninstall")));
    }

    #[test]
    fn incompatible_with_permission_uninstalls_then_installs() {
        let mut tv = acer_tv();
        tv.install_results.push_back(Err(TvError::InstallFailed(
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]".into(),
        )));
        tv.install_results.push_back(Ok(()));
        let (tv, r) = run_install(tv, true);
        assert!(r.is_ok());
        let un = tv
            .log
            .iter()
            .position(|c| c == "pm uninstall com.gathin.tvhome")
            .unwrap();
        let installs: Vec<usize> = tv
            .log
            .iter()
            .enumerate()
            .filter(|(_, c)| *c == "install")
            .map(|(i, _)| i)
            .collect();
        assert_eq!(installs.len(), 2);
        assert!(installs[0] < un && un < installs[1]);
    }

    #[test]
    fn other_install_failures_surface_the_message() {
        let mut tv = acer_tv();
        tv.install_results.push_back(Err(TvError::InstallFailed(
            "Failure [INSTALL_FAILED_OLDER_SDK]".into(),
        )));
        let (_, r) = run_install(tv, false);
        assert_eq!(
            r,
            Err(FlowError::Failed(
                "Failure [INSTALL_FAILED_OLDER_SDK]".into()
            ))
        );
    }

    #[test]
    fn install_is_idempotent() {
        let tv = acer_tv().on(
            "settings get secure enabled_accessibility_services",
            SERVICE,
        );
        let (tv, r) = run_install(tv, false);
        assert!(r.is_ok());
        assert!(has(&tv, &put_services(SERVICE)));
    }

    #[test]
    fn existing_services_are_kept() {
        let tv = acer_tv().on("settings get secure enabled_accessibility_services", "x/.A");
        let (tv, r) = run_install(tv, false);
        assert!(r.is_ok());
        assert!(has(&tv, &put_services(&format!("x/.A:{SERVICE}"))));
    }

    #[test]
    fn service_names_with_dollar_survive_the_shell() {
        let tv = acer_tv().on(
            "settings get secure enabled_accessibility_services",
            "x/.A$Inner",
        );
        let (mut tv, r) = run_install(tv, false);
        assert!(r.is_ok());
        assert!(has(&tv, &put_services(&format!("x/.A$Inner:{SERVICE}"))));
        assert_eq!(
            tv.shell("settings get secure enabled_accessibility_services")
                .unwrap(),
            format!("x/.A$Inner:{SERVICE}")
        );
    }

    #[test]
    fn reports_steps_in_order() {
        let mut tv = acer_tv();
        let mut events = vec![];
        let r = run_install_with(&mut tv, false, &mut |s, st| events.push((s, st)));
        assert!(r.is_ok());
        let done: Vec<Step> = events
            .iter()
            .filter(|(_, st)| *st == StepState::Done)
            .map(|(s, _)| *s)
            .collect();
        assert_eq!(
            done,
            [
                Step::Install,
                Step::Permissions,
                Step::Home,
                Step::Open,
                Step::Check
            ]
        );
        assert_eq!(events[0], (Step::Install, StepState::Running));
    }

    #[test]
    fn failed_check_reports_and_stops() {
        let mut tv = mi_tv_with_disabled(false, "");
        let mut events = vec![];
        let r = run_install_with(&mut tv, false, &mut |s, st| events.push((s, st)));
        assert_eq!(r, Err(FlowError::Failed(NOT_HOME.into())));
        assert_eq!(
            events.last().unwrap(),
            &(Step::Check, StepState::Failed(NOT_HOME.into()))
        );
    }

    #[test]
    fn disable_refuses_if_launcherx_shows_up_on_recheck() {
        let tv = mi_tv(false).after(
            "install",
            "pm list packages",
            &format!("package:{LAUNCHERX}\npackage:{TVLAUNCHER}\n"),
        );
        let (tv, r) = run_install(tv, false);
        assert!(matches!(r, Err(FlowError::Failed(_))));
        assert!(!tv.log.iter().any(|c| c.starts_with("pm disable")));
    }

    #[test]
    fn disable_refuses_if_recheck_listing_is_empty() {
        let tv = mi_tv(false).after("install", "pm list packages", "");
        let (tv, r) = run_install(tv, false);
        assert!(matches!(r, Err(FlowError::Failed(_))));
        assert!(!tv
            .log
            .iter()
            .any(|c| c.starts_with("pm disable") || c.starts_with("settings put global")));
    }

    fn run_restore(mut tv: FakeTv) -> (FakeTv, Result<(), FlowError>) {
        let r = restore_flow(&mut tv, &mut |_, _| {});
        (tv, r)
    }

    fn untouched_tv() -> FakeTv {
        FakeTv::new()
            .on("getprop ro.product.model", "MIBOX3")
            .on("getprop ro.build.version.sdk", "28")
            .on("settings get secure enabled_accessibility_services", "null")
            .on("settings get global nlrc_setup_disabled", "null")
    }

    #[test]
    fn restore_keeps_other_services() {
        let tv = acer_tv().on(
            "settings get secure enabled_accessibility_services",
            &format!("x/.A:{SERVICE}"),
        );
        let (tv, r) = run_restore(tv);
        assert!(r.is_ok());
        assert!(has(&tv, &put_services("x/.A")));
        assert!(!has(&tv, "settings put secure accessibility_enabled 0"));
    }

    #[test]
    fn restore_keeps_services_with_dollar() {
        let tv = acer_tv().on(
            "settings get secure enabled_accessibility_services",
            &format!("x/.A$Inner:{SERVICE}"),
        );
        let (mut tv, r) = run_restore(tv);
        assert!(r.is_ok());
        assert!(has(&tv, &put_services("x/.A$Inner")));
        assert_eq!(
            tv.shell("settings get secure enabled_accessibility_services")
                .unwrap(),
            "x/.A$Inner"
        );
    }

    #[test]
    fn restore_turns_off_accessibility_when_empty() {
        let tv = acer_tv().on(
            "settings get secure enabled_accessibility_services",
            SERVICE,
        );
        let (tv, r) = run_restore(tv);
        assert!(r.is_ok());
        assert!(has(
            &tv,
            "settings delete secure enabled_accessibility_services"
        ));
        assert!(!tv
            .log
            .iter()
            .any(|c| c.starts_with("settings put secure enabled_accessibility_services")));
        assert!(has(&tv, "settings put secure accessibility_enabled 0"));
    }

    #[test]
    fn restore_reenables_from_comma_marker() {
        let tv = mi_tv(true).on(
            "settings get global nlrc_setup_disabled",
            "com.google.android.tvlauncher,com.mitv.tvhome.atv",
        );
        let (tv, r) = run_restore(tv);
        assert!(r.is_ok());
        assert!(has(&tv, "pm enable com.google.android.tvlauncher"));
        assert!(has(&tv, "pm enable com.mitv.tvhome.atv"));
        assert_eq!(
            tv.log.iter().filter(|c| c.starts_with("pm enable")).count(),
            2
        );
    }

    #[test]
    fn restore_reenables_only_marked_packages() {
        let tv = mi_tv(true).on(
            "settings get global nlrc_setup_disabled",
            "com.google.android.tvlauncher com.mitv.tvhome.atv",
        );
        let (tv, r) = run_restore(tv);
        assert!(r.is_ok());
        assert!(has(&tv, "pm enable com.google.android.tvlauncher"));
        assert!(has(&tv, "pm enable com.mitv.tvhome.atv"));
        assert!(has(&tv, "settings delete global nlrc_setup_disabled"));
        assert_eq!(
            tv.log.iter().filter(|c| c.starts_with("pm enable")).count(),
            2
        );
    }

    #[test]
    fn restore_leaves_user_debloated_patchwall() {
        let tv = mi_tv_with_disabled(
            true,
            "package:com.google.android.tvlauncher\npackage:com.mitv.tvhome.atv\n",
        )
        .on("settings get global nlrc_setup_disabled", "null");
        let (tv, r) = run_restore(tv);
        assert!(r.is_ok());
        assert!(!has(&tv, "pm enable com.mitv.tvhome.atv"));
        assert!(has(&tv, "pm enable com.google.android.tvlauncher"));
    }

    #[test]
    fn restore_on_untouched_tv_is_ok() {
        let (tv, r) = run_restore(untouched_tv());
        assert!(r.is_ok());
        assert!(!tv
            .log
            .iter()
            .any(|c| c.starts_with("pm uninstall") || c.starts_with("pm enable")));
        assert!(has(&tv, "input keyevent KEYCODE_HOME"));
    }

    #[test]
    fn restore_uninstalls_launcher_then_goes_home() {
        let tv = untouched_tv()
            .on("pm list packages com.gathin.tvhome", OURS)
            .on("pm list packages -d", "")
            .on("pm list packages", &format!("{OURS}package:{TVLAUNCHER}\n"))
            .after(
                "pm uninstall com.gathin.tvhome",
                "pm list packages com.gathin.tvhome",
                "",
            );
        let (tv, r) = run_restore(tv);
        assert!(r.is_ok());
        let un = index(&tv, "pm uninstall com.gathin.tvhome");
        let home = index(&tv, "input keyevent KEYCODE_HOME");
        assert!(un < home);
    }

    #[test]
    fn restore_keeps_launcher_when_no_stock_home_is_usable() {
        let mut tv = untouched_tv()
            .on("pm list packages com.gathin.tvhome", OURS)
            .on("pm list packages -d", &format!("package:{TVLAUNCHER}\n"))
            .on("pm list packages", &format!("{OURS}package:{TVLAUNCHER}\n"));
        let mut events = vec![];
        let r = restore_flow(&mut tv, &mut |s, st| events.push((s, st)));
        assert_eq!(r, Err(FlowError::Failed(NO_STOCK_HOME.into())));
        assert_eq!(
            events.last().unwrap(),
            &(Step::Uninstall, StepState::Failed(NO_STOCK_HOME.into()))
        );
        assert!(has(&tv, "pm enable com.google.android.tvlauncher"));
        assert!(!tv.log.iter().any(|c| c.starts_with("pm uninstall")));
    }

    #[test]
    fn restore_reports_launcher_that_would_not_uninstall() {
        let tv = untouched_tv()
            .on("pm list packages com.gathin.tvhome", OURS)
            .on("pm list packages -d", "")
            .on("pm list packages", &format!("{OURS}package:{TVLAUNCHER}\n"));
        let (tv, r) = run_restore(tv);
        assert_eq!(r, Err(FlowError::Failed(NOT_REMOVED.into())));
        assert!(has(&tv, "pm uninstall com.gathin.tvhome"));
        assert!(!has(&tv, "input keyevent KEYCODE_HOME"));
    }

    #[test]
    fn restore_reenables_disabled_launcherx() {
        let tv = base(
            "Acer TV",
            "34",
            "package:com.google.android.apps.tv.launcherx\n",
            "package:com.google.android.apps.tv.launcherx\n",
        );
        let (tv, r) = run_restore(tv);
        assert!(r.is_ok());
        assert!(has(&tv, "pm enable com.google.android.apps.tv.launcherx"));
    }

    #[test]
    fn restore_reports_steps_in_order() {
        let mut tv = untouched_tv();
        let mut done = vec![];
        let r = restore_flow(&mut tv, &mut |s, st| {
            if st == StepState::Done {
                done.push(s);
            }
        });
        assert!(r.is_ok());
        assert_eq!(
            done,
            [
                Step::RemoveService,
                Step::RestoreLaunchers,
                Step::Uninstall,
                Step::GoHome
            ]
        );
    }

    #[test]
    fn restore_never_disables() {
        for tv in [acer_tv(), mi_tv(true), mi_tv(false)] {
            let (tv, r) = run_restore(tv);
            assert!(r.is_ok());
            assert!(!tv.log.iter().any(|c| c.starts_with("pm disable")));
        }
    }
}
