//! Helpers for the enabled-accessibility-services list (`settings get secure
//! enabled_accessibility_services`), which is a colon-separated string.

pub const SERVICE: &str = "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService";

// Android accepts the short form too, and the list may hold either spelling.
const SHORT_FORM: &str = "com.gathin.tvhome/.HomeKeyService";

pub fn with_service(current: &str) -> String {
    let mut entries = others(current);
    entries.push(SERVICE);
    entries.join(":")
}

pub fn without_service(current: &str) -> String {
    others(current).join(":")
}

fn others(current: &str) -> Vec<&str> {
    current
        .split(':')
        .filter(|e| !e.is_empty() && *e != "null" && *e != SERVICE && *e != SHORT_FORM)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn add_to_null() {
        assert_eq!(with_service("null"), SERVICE);
    }
    #[test]
    fn add_to_empty() {
        assert_eq!(with_service(""), SERVICE);
    }
    #[test]
    fn add_keeps_others() {
        assert_eq!(with_service("x/.A"), format!("x/.A:{SERVICE}"));
    }
    #[test]
    fn add_is_idempotent() {
        assert_eq!(with_service(SERVICE), SERVICE);
    }
    #[test]
    fn add_replaces_short_form() {
        assert_eq!(with_service("com.gathin.tvhome/.HomeKeyService"), SERVICE);
    }
    #[test]
    fn remove_keeps_others() {
        assert_eq!(
            without_service(&format!("x/.A:{SERVICE}:y/.B")),
            "x/.A:y/.B"
        );
    }
    #[test]
    fn remove_short_form() {
        assert_eq!(
            without_service("com.gathin.tvhome/.HomeKeyService:x/.A"),
            "x/.A"
        );
    }
    #[test]
    fn remove_from_null() {
        assert_eq!(without_service("null"), "");
    }
}
