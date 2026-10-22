use std::path::Path;

pub trait Tv {
    /// Stdout of the command, trimmed.
    fn shell(&mut self, cmd: &str) -> Result<String, TvError>;
    fn install(&mut self, apk: &Path) -> Result<(), TvError>;
}

#[derive(Debug, thiserror::Error, Clone, PartialEq)]
pub enum TvError {
    /// Carries the `Failure [INSTALL_FAILED_...]` text from the TV.
    #[error("{0}")]
    InstallFailed(String),
    #[error("{0}")]
    Connection(String),
    /// The TV kept the link open but didn't reply in time (e.g. "Allow debugging" not answered yet).
    #[error("{0}")]
    NoAnswer(String),
}
