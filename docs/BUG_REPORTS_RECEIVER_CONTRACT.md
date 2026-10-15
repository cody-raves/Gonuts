# GoNuts public bug-report receiver contract

Updated 2026-10-14. Receiver deployment verified by the VPS agent; the no-token mod GUI has also filed and replayed a test report successfully.

## Public endpoints

- POST https://gonuts.at/v1/bugs
- GET https://gonuts.at/v1/bugs/capabilities

No Authorization header, install token, account linking or signup is required. Existing clients may send an old Authorization header; the receiver ignores it. Admin/digest and market APIs still require their own credentials. The mod always uses this fixed HTTPS reporting origin, independent of market API configuration. The GitHub publishing credential stays on the VPS.

## Payload

Six required fields: schemaVersion, build, minecraftVersion, description, expected, diagnostics. Schema 2 optionally adds diagnosticLog. No other keys are accepted.

- schemaVersion: 1 or 2.
- build and minecraftVersion: [0-9A-Za-z.+_-]{1,40}.
- description: 10–1500 characters, at most40 lines; first line supplies the issue title, limited to100 characters.
- expected: 1–1500 characters, at most30 lines.
- diagnostics: state and exactly the five integer counters below, each0–1000000000.
- state: SCANNING, BID_DESK, PREPARING_LIST, LISTING, MONITORING, COOLDOWN, PAUSED, STOPPED, IDLE, BUYING or UNKNOWN.
- Counters: sqlite_errors, uncompleted_desk_actions, disconnect_notices, evasion_notices, recovered_purchase_pauses.
- diagnosticLog: schema2 only; optional/null/blank means no attachment. At most8192 UTF-8 bytes, 200 lines, 500 characters per line.
- Entire body: at most32768 UTF-8 bytes. Content-Type: application/json.
- Raw log-shaped text in description/expected is rejected; use the sanitized attachment.

The excerpt is generated from recognized diagnostic events and verified dev.doughbay frames. Chat, player identities, credentials, addresses, paths and foreign frames are omitted. The receiver filters again. Reports are public GitHub issues; explicit local review/consent remains required.

## Capabilities

Advertises schemaVersions, maxBodyBytes, maxTextChars, diagnosticLog limits, states, counters, idempotencyKey format and configured readiness. It also advertises requiresAuthentication:false, idempotencyScope:"global" and deployed quota values. Missing attachment support leaves schema1 reporting available; it is not a reason to request a user token.

## Replies and retries

201 for a new issue, 200 for a duplicate:

{"reportId":"br_…","issueNumber":123,"issueUrl":"https://github.com/cody-raves/Gonuts/issues/123","duplicate":false}

The destination repository and bug-report label are fixed on the receiver. The mod accepts success only with a valid reportId and a trusted Gonuts issue URL.

Errors use {message,reason,status}. 400 means invalid form; 409 means the retry identifier was reused with different content: prepare again. 413/415/405 enforce size/content type/method. 429 carries Retry-After. 502/503 indicate service/upstream unavailability, not a filed report. Public reporting does not require fixing market API credentials in response to401; it indicates a service compatibility problem.

Idempotency-Key: [A-Za-z0-9_-]{8,64}. Generate once per prepared report and keep it on retries. The namespace is global; changed content with the same key gets409. Concurrent identical submissions make one issue. Durable mappings survive restart. Ambiguous GitHub outcomes and interrupted jobs are reconciled using the receiver's hidden issue marker before creating again. Exact-text dedupe is not proof of semantic bug grouping.

## Deployed abuse/privacy controls

Per VPS handoff: 20 attempts/hour/source address; 30/minute receiver-wide; 60 new issues/hour receiver-wide; four concurrent. These are server-configurable. Source addresses come from the trusted Caddy-added forwarding value; client-forged prefixes must not bypass limits. Address counters are bounded, ephemeral and never persisted. OPTIONS is405, with no CORS headers.

Caddy access logging is disabled for report routes. Proxy logs do not contain bodies, usernames, addresses, credentials or excerpts. Issue headers do not name the install. Only neutral report metadata is retained; the GitHub issue holds the published, sanitized text. All user text is literal data, not an instruction to the patch worker. Admin/digest authentication and worker review/release gates are unchanged.

## Verification

VPS tests covered public submissions, authentication boundaries, hostile excerpts, concurrent retries,409 conflicts, header spoofing and quotas. Local GUI test issue9 used a fresh install with no API token and a custom market URL, attached a filtered excerpt, required consent, offered Open issue and replayed to the same issue. Test issue9 was closed.
