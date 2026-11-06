# Privacy and network behavior

Real infrastructure DNS, private annotations, device IDs, signatures, full logs and paths were removed. Examples in JVM tests/simulation use documentation or benchmark addresses; they are fictional and not device observations. The DNS template requires explicit input.

Public-IP probes contact https://api.ipify.org and https://api6.ipify.org periodically (about 30 seconds) and on network changes; those services receive requests and network source addresses. Active inspector probes contact the chosen hostname/address via DNS/TCP/TLS; they do not send an HTTP application request. TLS uses system trust and hostname checks. WireGuard contacts the configured peers. Build dependency downloads contact official repositories. No additional tracking pixel, analytics or traffic counter is added. Do not post tunnel keys, SSIDs, interfaces, IPs or exported real profiles without review.

Exact exception: NetfilterCompilerTest uses public Google resolver addresses (8.8.8.8 and 2001:4860:4860::8888) as symbolic Packet objects only, with no network request. Other fixed protocol addresses are loopback, link-local, multicast or universal prefixes.
