# Egress Proxy Flows

Files: `proxy.py`, `Dockerfile`, `test_proxy.py`, `real_egress_check.py`

Callers: sandbox runner containers (python scripts) only. Token minted by `sandbox/app.py` `mint_egress_token()` (see `sandbox/FLOWS.md`). Java side that decides the hosts: `CapabilityGrants.java`, `CapabilitySecretService.requestFor()` (see `KPI/FLOWS.md`).

## Network topology

```
runner (--network habit_sandbox_egress, internal: true) ──► egress-proxy:3128 ──► (default network) ──► internet
runner (no declared hosts)                     --network none ──► nothing
```
`habit_sandbox_egress` is `internal: true` (no gateway): the only reachable host from a runner is `egress-proxy`. `egress-proxy` is on both that network and the compose default network. `sandbox`, `javaapp`, `mongodbHabit`, etc. are NOT on the internal network, so a runner cannot reach them directly. To change the network name: `docker-compose.yml` `networks.sandbox_egress.name` AND `sandbox` env `SANDBOX_EGRESS_NETWORK`.

## Request flow

Runner: `urllib.request.urlopen(url)` → reads `HTTPS_PROXY=http://<token>:x@egress-proxy:3128` → `CONNECT host:443` (https) or `GET http://host/...` (http) with `Proxy-Authorization: Basic base64(token:x)` →
`Handler.do_CONNECT()` / `Handler._plain()` →
1. `_conn_slots` (max `EGRESS_MAX_CONNECTIONS`=64, else 503).
2. `authorize()` → `token_from_header()` → `verify_token()`: HMAC-SHA256 constant-time compare with `SANDBOX_EGRESS_SECRET`, then `exp` check. Failure → 407.
3. Port must be 443 or 80 (`ALLOWED_PORTS`), host (lowercased, trailing dot stripped) must EXACT-match an entry in the token's host list (no wildcards, no subdomain match). Failure → 403.
4. `resolve_checked()` → `getaddrinfo` here, EVERY returned address must pass `ip_is_allowed()` (global unicast only: rejects private, loopback, link-local, multicast, reserved, unspecified, CGNAT, IPv4-mapped/6to4/Teredo variants of those). Any bad address → 403 "non-public address". DNS failure → 502.
5. `connect_checked()` connects to the vetted IP (never the hostname again → no DNS-rebinding window), `CONNECT` replies `200` then `pipe()` relays bytes (idle `EGRESS_IDLE_TIMEOUT`=60s, hard cap `EGRESS_MAX_TUNNEL_SECONDS`=120s).
6. Plain http: request rewritten to origin-form, `Proxy-*`/`Connection` headers dropped, `Connection: close` forced, one request per connection; chunked request bodies refused (501).
Log: one `ALLOW ...` / `DENY host:port reason (code)` line per request on stdout (`docker logs egress-proxy`). Tokens and request lines are never logged.

To change allowed ports: `ALLOWED_PORTS`. To change the SSRF rules: `ip_is_allowed()`. To change timeouts/limits: env `EGRESS_*`. To change the token format: `mint_token()`/`verify_token()` here AND `mint_egress_token()` in `sandbox/app.py` (a cross-check test, `test_sidecar_minted_token_verifies`, fails if they drift).

## Testing

- `cd egress-proxy && python3 -m unittest` — token valid/forged/expired/missing, host allowlist, ports, private-IP resolution (mocked DNS), real-socket denial paths, tunnel relay.
- `SANDBOX_IMAGE=python:slim python3 real_egress_check.py` — REAL docker, throwaway `kpi-egtest-<id>-*` proxy container + internal network on port 3199, never touches compose services or published ports; needs internet. Proves allowed host reachable via proxy, disallowed (https+http) denied, direct egress and the proxy's bridge IP unreachable, IP-literal and loopback/10.x-resolving names denied, secret env injected + redacted, no leftover containers/networks/env-files.

## Technology Notes

- **Shared HMAC secret `SANDBOX_EGRESS_SECRET`** (`.env`, >= 32 chars; compose passes it to `sandbox` and `egress-proxy`). Unset or shorter than 32 chars: the proxy still starts (a crash loop would be worse) but DENIES EVERYTHING (407 "egress secret not configured"), and the sidecar refuses any run that needs hosts. Rotating it: change `.env`, restart `sandbox` and `egress-proxy` together; in-flight runs fail, everything after works. Anyone holding the secret can mint a token for any host, so it must stay out of runner containers (it is: runners only get a signed token).
- **Token TTL** = script timeout + 30s (`EGRESS_TOKEN_GRACE_SECONDS`), so <= ~90s. A leaked token (e.g. a script prints `HTTPS_PROXY`) only works from inside the internal network and only for that run's hosts; it is redacted from returned output anyway.
- **Hostname-level allowlisting only.** A script may hit ANY path/method on an allowed host, including other users' data on a multi-tenant API if it had credentials, and it can exfiltrate its own secrets to an allowed host (e.g. a script allowed `api.github.com` can post a secret to a gist). Approval of the proposal is the entire trust decision.
- **SSRF guard.** The proxy resolves DNS itself and rejects the whole request if ANY address is non-public (mongo, javaapp, sidecar, the host, the LAN, cloud metadata all die here, including names an attacker points at private IPs, e.g. `127.0.0.1.nip.io`). It then connects to the vetted IP, so there is no resolve-then-connect gap. It trusts the container's DNS resolver (Docker's, then host's): a malicious resolver could return a public IP for an allowed name, which is the same trust as any proxy.
- **HTTPS is a blind tunnel (CONNECT):** the proxy sees host:port only, not paths or bodies; TLS is end-to-end runner to origin. Plain http requests are inspected only to the extent of host/port.
- **IPv6** targets work only if the proxy container itself has IPv6 routing (Docker default bridge is IPv4-only).
- **Proxy down = every networked capability fails** (`SIDECAR_ERROR`/ProxyError in the script) → counts as a failure per KPI → circuit breaker (`kpi.proxy.failure-threshold`=3 nights) → `NEEDS_REPAIR`, and the automatic repair loop will burn LLM attempts on an infrastructure outage. Capabilities with no declared hosts are unaffected (`--network none`).
- **Capacity:** stdlib `ThreadingHTTPServer`, thread per connection, 48m container, max 64 concurrent connections (503 beyond). Sized for ~4 concurrent runners.
- **Runner clients must honor the proxy env.** `urllib.request` does (with `user:password@` credentials in the URL, which is why the token is paired with a dummy password `x`: urllib sends `Proxy-Authorization` only when both are present). `http.client`, raw `socket`, `ssl` do NOT: they try a direct connection on a network with no route and fail. The generation prompt (`backup/claude_cli.py` `_SANDBOX_FACTS`) tells the model to use `urllib.request` only; `requests` is not installed in the runner image.
- **No authentication from anything but the token; no TLS on the proxy hop** (internal network only, token in cleartext inside it). `read_only` rootfs, all caps dropped, uid 65534, no published ports, no Docker socket.

## Change Index

| What to change | Where |
|---|---|
| Allowed ports | `proxy.py` `ALLOWED_PORTS` |
| Proxy listen port | env `EGRESS_PROXY_PORT` (sandbox env of the same name must match) |
| SSRF rules | `proxy.py` `ip_is_allowed()`, `resolve_checked()` |
| Token format / verification | `proxy.py` `verify_token()` + `sandbox/app.py` `mint_egress_token()` |
| HMAC secret | `.env` `SANDBOX_EGRESS_SECRET` (both services) |
| Connection / idle / tunnel limits | env `EGRESS_MAX_CONNECTIONS`, `EGRESS_IDLE_TIMEOUT`, `EGRESS_MAX_TUNNEL_SECONDS`, `EGRESS_CONNECT_TIMEOUT` |
| Network name / internal flag | `docker-compose.yml` `networks.sandbox_egress`; `sandbox` env `SANDBOX_EGRESS_NETWORK` |
| Memory / hardening | `docker-compose.yml` service `egress-proxy` |
| Real-docker verification | `real_egress_check.py` |
