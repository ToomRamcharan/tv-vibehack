use std::fs;
use std::io::{ErrorKind, Write};
use std::net::{Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};

use adb_client::tcp::ADBTcpDevice;
use adb_client::{ADBDeviceExt, RustADBError};
use anyhow::Context;
use rsa::pkcs8::{EncodePrivateKey, LineEnding};
use rsa::RsaPrivateKey;

use crate::tv::{Tv, TvError};

const ADB_PORT: u16 = 5555;

/// adb_client generates a throwaway key in memory when the file is missing and never
/// saves it, so without this the TV would ask "Allow?" on every run.
pub fn ensure_key(dir: &Path) -> anyhow::Result<PathBuf> {
    let path = dir.join("adbkey");
    if path.exists() {
        return Ok(path);
    }
    fs::create_dir_all(dir).context("creating key directory")?;
    let key = RsaPrivateKey::new(&mut rsa::rand_core::OsRng, 2048).context("generating key")?;
    let pem = key.to_pkcs8_pem(LineEnding::LF).context("encoding key")?;
    // Write to a temp file and link it into place so a crash mid-write never leaves a
    // truncated key that would be trusted forever; linking also never clobbers a key
    // another process created first.
    let tmp = dir.join(format!("adbkey.{}.tmp", std::process::id()));
    let written = write_private(&tmp, pem.as_bytes());
    let linked = written.and_then(|()| match fs::hard_link(&tmp, &path) {
        Err(e) if e.kind() == ErrorKind::AlreadyExists => Ok(()),
        other => other,
    });
    let _ = fs::remove_file(&tmp);
    linked.context("writing key")?;
    Ok(path)
}

fn write_private(path: &Path, data: &[u8]) -> std::io::Result<()> {
    let mut opts = fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut opts, 0o600);
    let mut file = opts.open(path)?;
    file.write_all(data)?;
    file.sync_all()
}

pub struct AdbTv {
    device: ADBTcpDevice,
}

impl AdbTv {
    pub fn connect(ip: Ipv4Addr, key: &Path) -> Result<Self, TvError> {
        let device =
            ADBTcpDevice::new_with_custom_private_key(SocketAddr::from((ip, ADB_PORT)), key)
                .map_err(connection_error)?;
        Ok(Self { device })
    }
}

impl Tv for AdbTv {
    fn shell(&mut self, cmd: &str) -> Result<String, TvError> {
        let mut out = Vec::new();
        self.device
            .shell_command(&cmd, Some(&mut out), None)
            .map_err(connection_error)?;
        Ok(String::from_utf8_lossy(&out).trim().to_string())
    }

    fn install(&mut self, apk: &Path) -> Result<(), TvError> {
        self.device.install(&apk, None).map_err(|e| match e {
            RustADBError::ADBRequestFailed(s) => TvError::InstallFailed(s),
            other => connection_error(other),
        })
    }
}

/// A socket read timeout surfaces as WouldBlock on Unix and TimedOut on Windows.
fn connection_error(e: RustADBError) -> TvError {
    match &e {
        RustADBError::IOError(io)
            if matches!(io.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) =>
        {
            TvError::NoAnswer(e.to_string())
        }
        _ => TvError::Connection(e.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsa::pkcs8::DecodePrivateKey;

    #[test]
    fn ensure_key_creates_once() {
        let d = tempfile::tempdir().unwrap();
        let p = ensure_key(d.path()).unwrap();
        let first = std::fs::read_to_string(&p).unwrap();
        assert!(first.starts_with("-----BEGIN PRIVATE KEY-----"));
        assert!(rsa::RsaPrivateKey::from_pkcs8_pem(&first).is_ok());
        ensure_key(d.path()).unwrap();
        assert_eq!(first, std::fs::read_to_string(&p).unwrap());
    }

    #[test]
    fn read_timeouts_mean_no_answer() {
        for kind in [ErrorKind::WouldBlock, ErrorKind::TimedOut] {
            let e = RustADBError::IOError(std::io::Error::from(kind));
            assert!(
                matches!(connection_error(e), TvError::NoAnswer(_)),
                "{kind:?}"
            );
        }
    }

    #[test]
    fn other_errors_are_connection_errors() {
        let e = RustADBError::IOError(std::io::Error::from(ErrorKind::ConnectionRefused));
        assert!(matches!(connection_error(e), TvError::Connection(_)));
    }

    #[cfg(unix)]
    #[test]
    fn ensure_key_is_private_and_leaves_no_temp_file() {
        use std::os::unix::fs::PermissionsExt;
        let d = tempfile::tempdir().unwrap();
        let p = ensure_key(d.path()).unwrap();
        let mode = std::fs::metadata(&p).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o600);
        assert_eq!(std::fs::read_dir(d.path()).unwrap().count(), 1);
    }
}
