use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

const ADB_PORT: u16 = 5555;
const WORKERS: usize = 64;

/// Assumes a /24: home networks almost always are, and the manual IP entry covers the rest.
pub fn candidate_hosts(local: Ipv4Addr) -> Vec<Ipv4Addr> {
    let [a, b, c, _] = local.octets();
    (1..=254)
        .map(|d| Ipv4Addr::new(a, b, c, d))
        .filter(|ip| *ip != local)
        .collect()
}

pub fn local_ipv4s() -> Vec<Ipv4Addr> {
    local_ip_address::list_afinet_netifas()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|(_, ip)| match ip {
            IpAddr::V4(v4) if v4.is_private() && !v4.is_loopback() && !v4.is_link_local() => {
                Some(v4)
            }
            _ => None,
        })
        .collect()
}

pub fn probe(addr: SocketAddr, timeout: Duration) -> bool {
    TcpStream::connect_timeout(&addr, timeout).is_ok()
}

pub fn scan(timeout: Duration) -> Vec<Ipv4Addr> {
    let queue: Vec<Ipv4Addr> = local_ipv4s()
        .into_iter()
        .flat_map(candidate_hosts)
        .collect();
    let queue = Arc::new(Mutex::new(queue));
    let found = Arc::new(Mutex::new(Vec::new()));

    let workers: Vec<_> = (0..WORKERS)
        .map(|_| {
            let queue = Arc::clone(&queue);
            let found = Arc::clone(&found);
            thread::spawn(move || loop {
                let Some(ip) = queue.lock().unwrap().pop() else {
                    break;
                };
                if probe(SocketAddr::new(IpAddr::V4(ip), ADB_PORT), timeout) {
                    found.lock().unwrap().push(ip);
                }
            })
        })
        .collect();
    for w in workers {
        let _ = w.join();
    }

    let mut hosts = Arc::try_unwrap(found).unwrap().into_inner().unwrap();
    hosts.sort();
    hosts.dedup();
    hosts
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hosts_cover_24_minus_self() {
        let h = candidate_hosts("192.168.1.50".parse().unwrap());
        assert_eq!(h.len(), 253);
        assert!(!h.contains(&"192.168.1.50".parse().unwrap()));
        assert!(
            !h.contains(&"192.168.1.0".parse().unwrap())
                && !h.contains(&"192.168.1.255".parse().unwrap())
        );
    }

    #[test]
    fn probe_sees_open_and_closed_ports() {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        assert!(probe(l.local_addr().unwrap(), Duration::from_millis(400)));
        let closed = {
            let t = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            t.local_addr().unwrap()
        };
        assert!(!probe(closed, Duration::from_millis(400)));
    }
}
