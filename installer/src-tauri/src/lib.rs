pub mod a11y;
pub mod adb;
pub mod commands;
pub mod detect;
#[cfg(test)]
pub mod fake;
pub mod flow;
pub mod scan;
pub mod tv;

pub fn run() {
    tauri::Builder::default()
        .manage(commands::Session::default())
        .invoke_handler(tauri::generate_handler![
            commands::scan_tvs,
            commands::connect_tv,
            commands::run_install,
            commands::run_restore
        ])
        .run(tauri::generate_context!())
        .expect("error while running NLRC Setup");
}
