use std::collections::VecDeque;
use std::path::Path;

use crate::tv::{Tv, TvError};

/// Scripted TV for tests: answers shell commands by prefix and records every call.
/// `settings put/delete` are parsed the way the TV's `sh -c` would split them, so tests
/// catch quoting mistakes.
#[derive(Default)]
pub struct FakeTv {
    pub log: Vec<String>,
    pub responses: Vec<(String, String)>,
    pub install_results: VecDeque<Result<(), TvError>>,
    /// (command, prefix, stdout): once `command` runs, `prefix` answers `stdout`.
    pub afterwards: Vec<(String, String, String)>,
}

impl FakeTv {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn on(mut self, prefix: &str, stdout: &str) -> Self {
        self.responses
            .push((prefix.to_string(), stdout.to_string()));
        self
    }

    /// Changes an answer once `cmd` has run ("install" for an APK install).
    pub fn after(mut self, cmd: &str, prefix: &str, stdout: &str) -> Self {
        self.afterwards
            .push((cmd.to_string(), prefix.to_string(), stdout.to_string()));
        self
    }

    fn answer(&mut self, prefix: String, stdout: String) {
        match self.responses.iter_mut().find(|(p, _)| *p == prefix) {
            Some(entry) => entry.1 = stdout,
            None => self.responses.insert(0, (prefix, stdout)),
        }
    }

    fn record(&mut self, cmd: &str) {
        self.log.push(cmd.to_string());
        let due: Vec<(String, String)> = self
            .afterwards
            .iter()
            .filter(|(trigger, _, _)| trigger == cmd)
            .map(|(_, prefix, out)| (prefix.clone(), out.clone()))
            .collect();
        for (prefix, out) in due {
            self.answer(prefix, out);
        }
    }
}

/// Splits a command like `sh` does: whitespace separates words, single quotes keep text
/// literal, and an unquoted `$name` expands to nothing (no such variable on the TV).
pub fn sh_words(cmd: &str) -> Vec<String> {
    let mut words = vec![];
    let mut word: Option<String> = None;
    let mut chars = cmd.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '\'' => {
                let w = word.get_or_insert_with(String::new);
                for q in chars.by_ref() {
                    if q == '\'' {
                        break;
                    }
                    w.push(q);
                }
            }
            '$' => {
                word.get_or_insert_with(String::new);
                while chars
                    .next_if(|n| n.is_ascii_alphanumeric() || *n == '_')
                    .is_some()
                {}
            }
            c if c.is_whitespace() => words.extend(word.take()),
            c => word.get_or_insert_with(String::new).push(c),
        }
    }
    words.extend(word);
    words
}

impl Tv for FakeTv {
    fn shell(&mut self, cmd: &str) -> Result<String, TvError> {
        self.record(cmd);
        match sh_words(cmd).as_slice() {
            [s, put, ns, key, value, ..] if s == "settings" && put == "put" => {
                self.answer(format!("settings get {ns} {key}"), value.clone());
            }
            [s, del, ns, key] if s == "settings" && del == "delete" => {
                self.answer(format!("settings get {ns} {key}"), "null".into());
            }
            _ => {}
        }
        Ok(self
            .responses
            .iter()
            .find(|(prefix, _)| cmd.starts_with(prefix.as_str()))
            .map(|(_, out)| out.clone())
            .unwrap_or_default())
    }

    fn install(&mut self, _apk: &Path) -> Result<(), TvError> {
        self.record("install");
        self.install_results.pop_front().unwrap_or(Ok(()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn put_secure_is_visible_to_get() {
        let mut tv = FakeTv::new().on("settings get secure k", "old");
        tv.shell("settings put secure k new:value").unwrap();
        assert_eq!(tv.shell("settings get secure k").unwrap(), "new:value");
    }

    #[test]
    fn put_stores_only_the_first_value_word() {
        let mut tv = FakeTv::new();
        tv.shell("settings put global k a b").unwrap();
        assert_eq!(tv.shell("settings get global k").unwrap(), "a");
    }

    #[test]
    fn delete_makes_get_null() {
        let mut tv = FakeTv::new().on("settings get global k", "a");
        tv.shell("settings delete global k").unwrap();
        assert_eq!(tv.shell("settings get global k").unwrap(), "null");
    }

    #[test]
    fn sh_words_quotes_and_dollars() {
        assert_eq!(sh_words("a  'b $c d' e$f"), ["a", "b $c d", "e"]);
    }

    #[test]
    fn after_changes_an_answer() {
        let mut tv = FakeTv::new().on("pm list packages", "package:a").after(
            "pm uninstall a",
            "pm list packages",
            "",
        );
        assert_eq!(tv.shell("pm list packages").unwrap(), "package:a");
        tv.shell("pm uninstall a").unwrap();
        assert_eq!(tv.shell("pm list packages").unwrap(), "");
    }
}
