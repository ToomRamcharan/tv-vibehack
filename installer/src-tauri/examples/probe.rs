use std::net::Ipv4Addr;
use std::path::PathBuf;

use anyhow::Context;
use nlrc_setup_lib::adb::{ensure_key, AdbTv};
use nlrc_setup_lib::tv::Tv;

fn main() -> anyhow::Result<()> {
    let mut args = std::env::args().skip(1);
    let ip: Ipv4Addr = args
        .next()
        .context("usage: probe <ip> [apk]")?
        .parse()
        .context("invalid IPv4 address")?;
    let apk = args.next().map(PathBuf::from);

    let dir = dirs::data_dir()
        .context("no data directory")?
        .join("nlrc-setup");
    let key = ensure_key(&dir)?;
    let mut tv = AdbTv::connect(ip, &key)?;

    for cmd in [
        "getprop ro.product.model",
        "getprop ro.build.version.sdk",
        "pm list packages",
    ] {
        println!("$ {cmd}\n{}", tv.shell(cmd)?);
    }
    if let Some(apk) = apk {
        println!("install: {:?}", tv.install(&apk));
    }
    Ok(())
}
