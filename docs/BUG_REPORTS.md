# Bug reports and VPS handoff

The client sends reviewed reports directly to **https://gonuts.at/v1/bugs**
without an API token, signup, Discord membership or GitHub account. Market-feed
credentials and custom market URLs do not affect reporting. Neither the report
nor its capabilities request sends an Authorization header. No IP or shared
secret is baked into the JAR. Transport always uses HTTPS,
never follows redirects, bounds reply sizes and times out failed requests.

In **About → Report bug**, enter a description and expected behavior, choose
**Prepare**, scroll through the filtered preview, confirm **I reviewed this public
report**, then **Send reviewed**. Manual GitHub submission remains a
fallback (GitHub sign-in required). No report is sent in the background.

Success and duplicate replies offer **Open issue**. Retrying an unchanged report
uses the same idempotency key. A 429 holds the send button for Retry-After; failed
GitHub/receiver availability holds it briefly. There is no automatic retry loop.
Report preparation failure disables sending and never falls back to raw logs.
A 503 means reporting is temporarily unavailable, not that the report was filed.
The VPS operator must install its GitHub Issues token before filing can succeed.
That credential stays on the VPS. A 409 means prepare a new report/retry identifier;
neither copying text nor opening the GitHub fallback counts as a submitted report.

**Feature request** opens a separate GitHub form, without diagnostics; requests
are considered, not guaranteed. It is not a bug-report receiver submission.

Raw logs are never attached. A bounded 256 KiB tail of the current log is reduced to
fixed event counters and a recognized automation state. This is a sample, not a
time window. No event messages, player names, chat, paths or exception messages
are copied. Config files, databases, environment variables and process arguments
are not read. User-written fields are filtered for credentials, addresses, email,
UUIDs, paths and opaque long identifiers. User text still requires review: arbitrary
names and personal facts cannot reliably be recognized by a pattern filter.
If collection fails, report submission is disabled; there is no raw-log fallback.

The GitHub daily digest runs at 16:00 UTC (09:00 PDT / 08:00 PST), linking new
`bug-report` issues from the preceding 24 hours. No report text is executed or sent
to a patch agent. GitHub schedules are best-effort; manually rerunning the workflow
updates that date's existing digest. Empty days produce only a workflow summary.

## VPS receiver contract

The verified receiver schema is in [BUG_REPORTS_RECEIVER_CONTRACT.md](BUG_REPORTS_RECEIVER_CONTRACT.md).
Schema 1 sends six top-level fields; schema 2 adds diagnosticLog. Diagnostics contain the fixed enum state
and five integer counters. UNKNOWN is uppercase. The mod build timestamp is
appended to its version when it fits the receiver's 40-character build limit,
so reports distinguish releases sharing the same mod version and JAR filename.

Schema 2 adds an optional filtered log excerpt. The client checks public
capabilities without a credential and verifies advertised sizes/line limits
before enabling **Include log** (On by default when supported). Older receivers
and unavailable capabilities retain schema 1 reporting with no attachment.
The attachment choice is reflected in the local preview before explicit consent;
changing that choice requires preparing and reviewing a new report.

The excerpt is generated locally from recognized GoNuts state/action/exception
lines and verified GoNuts Java frames. It does not copy message text. Chat,
identities, coordinates, credentials, addresses, paths and foreign frames are
omitted. Only known exception types/codes and fixed event labels are retained.
Limits: 8 KiB, up to 100 events/frames plus two header lines, 500 characters per
line, from the last 256 KiB of current log. It is a relevant diagnostic excerpt,
not a full Minecraft log dump. Failed filtering blocks preparation.

Schema 1 keeps its six fields; schema 2 adds diagnosticLog. The whole schema 2
request is capped at 32 KiB. The receiver renders the excerpt as plain text inside
a collapsed details section and scrubs it again. The GitHub token stays on the VPS.

GUI-to-receiver verification created test issues #2 (schema 1) and #4 (schema 2).
Both retries returned the same issue. Privacy samples and the install credential
were absent from the published schema 2 issue. About-button visibility and the
scrollable preview were tested in an isolated offline Minecraft client. Test smoke
code is opt-in and excluded from production JARs.

## Patch automation boundary

Daily collection is implemented; automated patches are not yet deployed. A future
worker should group duplicates, reproduce an issue in an isolated checkout, run tests
and create a draft PR. Reports must never act as instructions to run commands,
retrieve credentials or publish releases. Human review precedes merging/releasing.
