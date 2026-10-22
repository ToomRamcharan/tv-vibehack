use crate::tv::{Tv, TvError};

pub const LAUNCHERX: &str = "com.google.android.apps.tv.launcherx";
pub const TVLAUNCHER: &str = "com.google.android.tvlauncher";
pub const PATCHWALL: &str = "com.mitv.tvhome.atv";
pub(crate) const NO_HOME_APP: &str =
    "We couldn't find this TV's home screen app, so it isn't supported yet. Nothing was changed.";
pub(crate) const SDK_UNREADABLE: &str = "Couldn't read the TV's Android version";

/// Newest Android release where disabling the stock launcher is known to stick.
const MAX_DISABLE_SDK: u32 = 30;

#[derive(Debug, Clone, serde::Serialize, PartialEq)]
pub struct TvInfo {
    pub model: String,
    pub sdk: u32,
    pub installed: Vec<String>,
    pub disabled: Vec<String>,
}

impl TvInfo {
    pub fn has(&self, pkg: &str) -> bool {
        self.installed.iter().any(|p| p == pkg)
    }

    pub fn is_disabled(&self, pkg: &str) -> bool {
        self.disabled.iter().any(|p| p == pkg)
    }
}

#[derive(Debug, Clone, Copy, serde::Serialize, PartialEq)]
pub enum HomeMethod {
    Accessibility,
    DisableStock { patchwall: bool },
}

#[derive(Debug, Clone, serde::Serialize, PartialEq)]
pub enum Support {
    Supported(HomeMethod),
    Unsupported(String),
}

pub fn parse_packages(out: &str) -> Vec<String> {
    out.lines()
        .filter_map(|l| l.trim().strip_prefix("package:"))
        .map(|p| p.trim().to_string())
        .collect()
}

pub fn read_info(tv: &mut dyn Tv) -> Result<TvInfo, TvError> {
    let model = tv.shell("getprop ro.product.model")?.trim().to_string();
    let sdk = tv
        .shell("getprop ro.build.version.sdk")?
        .trim()
        .parse()
        .map_err(|_| TvError::Connection(SDK_UNREADABLE.into()))?;
    let installed = parse_packages(&tv.shell("pm list packages")?);
    let disabled = parse_packages(&tv.shell("pm list packages -d")?);
    Ok(TvInfo {
        model,
        sdk,
        installed,
        disabled,
    })
}

pub fn choose_method(info: &TvInfo) -> Support {
    if info.has(LAUNCHERX) {
        return Support::Supported(HomeMethod::Accessibility);
    }
    if !info.has(TVLAUNCHER) {
        return Support::Unsupported(NO_HOME_APP.into());
    }
    if info.sdk > MAX_DISABLE_SDK {
        return Support::Unsupported(format!(
            "This TV runs {} without Google TV. It isn't supported yet, so nothing was changed.",
            android_name(info.sdk)
        ));
    }
    let patchwall = info.has(PATCHWALL) && !info.is_disabled(PATCHWALL);
    Support::Supported(HomeMethod::DisableStock { patchwall })
}

/// Android version as people know it ("Android 9", "Android 12L").
pub fn android_name(sdk: u32) -> String {
    let version = match sdk {
        28 => "9",
        29 => "10",
        30 => "11",
        31 => "12",
        32 => "12L",
        33 => "13",
        34 => "14",
        35 => "15",
        36 => "16",
        _ => return format!("Android (API level {sdk})"),
    };
    format!("Android {version}")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fake::FakeTv;

    fn info(sdk: u32, installed: &[&str], disabled: &[&str]) -> TvInfo {
        TvInfo {
            model: "x".into(),
            sdk,
            installed: installed.iter().map(|s| s.to_string()).collect(),
            disabled: disabled.iter().map(|s| s.to_string()).collect(),
        }
    }

    #[test]
    fn android_names() {
        assert_eq!(android_name(28), "Android 9");
        assert_eq!(android_name(32), "Android 12L");
        assert_eq!(android_name(34), "Android 14");
        assert_eq!(android_name(36), "Android 16");
        assert_eq!(android_name(99), "Android (API level 99)");
    }

    #[test]
    fn launcherx_means_accessibility() {
        assert_eq!(
            choose_method(&info(34, &[LAUNCHERX, TVLAUNCHER], &[])),
            Support::Supported(HomeMethod::Accessibility)
        );
    }

    #[test]
    fn launcherx_wins_even_on_old_sdk() {
        assert_eq!(
            choose_method(&info(28, &[LAUNCHERX], &[])),
            Support::Supported(HomeMethod::Accessibility)
        );
    }

    #[test]
    fn tvlauncher_on_android9() {
        assert_eq!(
            choose_method(&info(28, &[TVLAUNCHER], &[])),
            Support::Supported(HomeMethod::DisableStock { patchwall: false })
        );
    }

    #[test]
    fn patchwall_enabled_is_included() {
        assert_eq!(
            choose_method(&info(29, &[TVLAUNCHER, PATCHWALL], &[])),
            Support::Supported(HomeMethod::DisableStock { patchwall: true })
        );
    }

    #[test]
    fn patchwall_already_disabled_is_left_alone() {
        assert_eq!(
            choose_method(&info(29, &[TVLAUNCHER, PATCHWALL], &[PATCHWALL])),
            Support::Supported(HomeMethod::DisableStock { patchwall: false })
        );
    }

    #[test]
    fn tvlauncher_on_android11_disables_stock() {
        assert_eq!(
            choose_method(&info(30, &[TVLAUNCHER], &[])),
            Support::Supported(HomeMethod::DisableStock { patchwall: false })
        );
    }

    #[test]
    fn tvlauncher_on_android12_is_refused() {
        assert_eq!(
            choose_method(&info(31, &[TVLAUNCHER], &[])),
            Support::Unsupported("This TV runs Android 12 without Google TV. It isn't supported yet, so nothing was changed.".into())
        );
    }

    #[test]
    fn unknown_android_reads_naturally_in_the_refusal() {
        assert_eq!(
            choose_method(&info(99, &[TVLAUNCHER], &[])),
            Support::Unsupported("This TV runs Android (API level 99) without Google TV. It isn't supported yet, so nothing was changed.".into())
        );
    }

    #[test]
    fn launcherx_with_tvlauncher_and_patchwall_means_accessibility() {
        assert_eq!(
            choose_method(&info(28, &[LAUNCHERX, TVLAUNCHER, PATCHWALL], &[])),
            Support::Supported(HomeMethod::Accessibility)
        );
    }

    #[test]
    fn no_known_launcher_is_refused() {
        assert_eq!(
            choose_method(&info(28, &[], &[])),
            Support::Unsupported("We couldn't find this TV's home screen app, so it isn't supported yet. Nothing was changed.".into())
        );
    }

    #[test]
    fn package_match_is_exact() {
        let p = parse_packages("package:com.google.android.tvlauncher.extra\npackage:com.google.android.apps.tv.launcherx2");
        let i = TvInfo {
            model: "x".into(),
            sdk: 28,
            installed: p,
            disabled: vec![],
        };
        assert!(!i.has(TVLAUNCHER));
        assert!(!i.has(LAUNCHERX));
    }

    #[test]
    fn read_info_parses_fake() {
        let mut tv = FakeTv::new()
            .on("getprop ro.product.model", "MIBOX3\n")
            .on("getprop ro.build.version.sdk", "28\n")
            .on("pm list packages -d", "")
            .on(
                "pm list packages",
                "package:com.google.android.tvlauncher\n",
            );
        let i = read_info(&mut tv).unwrap();
        assert_eq!(
            (i.model.as_str(), i.sdk, i.has(TVLAUNCHER)),
            ("MIBOX3", 28, true)
        );
    }

    #[test]
    fn non_numeric_sdk_is_an_error() {
        let mut tv = FakeTv::new().on("getprop ro.build.version.sdk", "oops\n");
        assert!(matches!(read_info(&mut tv), Err(TvError::Connection(_))));
    }
}
