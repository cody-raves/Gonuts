# Staff Watch region escape

The existing **Escape / reconnect** switch enables this response on DonutSMP.

1. A qualifying local staff alert starts a bounded wait for a safe transaction boundary. The session's purchase/listing checkpoints and caps stay intact.
2. Once the driver is idle, the cursor is empty and the checkpoint is durable, trading is held and a regional `/rtp` command is queued through the normal command gate.
3. The destination comes from the server's `/rtp ` completion response. Only the six measured region tokens are accepted; command suggestions cannot inject another command. The previous explicit RTP destination or regional entrypoint is used as a selection hint. Coordinate labels are not transfer proof, and a generic entrypoint does not identify the current geographic region.
4. Arrival requires actual command dispatch, a later server teleport/world reset, a changed location or client world/player, and a fresh autocomplete reply from the server. Trading resumes only if Staff Watch owned the pause; a manual stop or recovery pause stays intact.
5. For ten minutes after arrival, a fresh detection of the same staff member starts a second hop. Once that transfer is confirmed, the client disconnects and the existing randomized five-to-ten-minute reconnect countdown begins.

Duplicate alerts while preparing/transferring are ignored. Detector state is cleared before the command and after confirmed arrival. Delayed autocomplete replies from before either reset cannot cause a repeat escape. Names returned by the exact `minecraft:msg ` probe are treated as local targetability evidence, including staff absent from the player list. This inference follows the two-account experiment; it does not establish deliberate pursuit or special staff permission behavior.

A different newly detected staff member starts a first-hop response for that name; it is not ignored or described as the previous watcher following the client.

If safe preparation takes more than 15 seconds, no regional option is available, teleports are disabled, the transfer is unconfirmed for 30 seconds, or the connection drops during escape, the client falls back to disconnect/cooldown. An already-dropped connection retains the staff delay rather than becoming an ordinary two-minute retry. The existing teleport safety setting is respected.

During transfer Staff Watch owns connection recovery, suppresses competing restart/evasion flows, and prevents automation from resuming. Cancelling/disabling releases the hold and withdraws an unsent region command without automatically resuming a paused session. An unexpected disconnect after a successful first hop follows ordinary reconnect handling.

Test coverage includes both hops, duplicate alerts, unrelated staff, the watch-window expiry, transaction and transfer timeouts, blocked teleports, connection loss, cancellation, unknown/injected region suggestions, and delayed completion replies.
