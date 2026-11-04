## Update check is on by default, with a server-side opt-out and a notification (2026-11-03)

**Repo:** EDDI — branch `feat/update-check-default-on`.

**What changed**

- The "is a newer EDDI out?" check used to be browser-only and opt-in (a `localStorage` flag). It is now **on by default** and EDDI itself runs it: `UpdateCheckService` (`engine/updatecheck/`) asks `api.github.com` once a day (first run a minute after boot), compares with `quarkus.application.version`, and raises a notification when the release is newer. The announced version is stored, so a dismissed notice is not raised again by the next day's check or a restart.
- **Two opt-outs.** `eddi.update-check.enabled=false` (env `EDDI_UPDATE_CHECK_ENABLED`, wired through `docker-compose.yml`, `.env.example` and the Helm value `eddi.updateCheck.enabled`, chart 2.14.0) stops it entirely — it is tested before the database is read, so the first check after the first boot is suppressed too — and wins over the Manager's switch, which shows as locked. The Manager's Updates page has the other switch (administrators), stored through the new `ISystemSettingStore` (`system_settings`, Mongo and Postgres), so it holds across restarts, browsers and nodes. `GET/PUT /administration/update-check`.
- **Notification reuses the existing inbox** (`WorkspaceNotifications`): new type `UPDATE_AVAILABLE`, addressed to the shared recipient `system:admins`, which administrators read in addition to their own inbox (with authorization off, everyone is an administrator). The bell no longer requires workspaces; it shows while workspaces or the update check are on.
- White-labelled deployments that did not set `eddi.branding.show-update-check` neither check nor notify.
- Manager: the banner/card read the server's switch instead of `localStorage`; the old key is ignored. `api.github.com` is still the only off-origin host the Manager contacts.

**Design decisions**

- State lives on the backend because only the backend can honour the env var before anyone reaches the UI.
- Platform Operator checked (§4.6.6): no prompt or allow-list change. The operator cannot write this switch (`PUT` stays off its allow-list on purpose) and the behaviour is documented in `docs/docker.md`, which it can read.
- 11 locales updated for the reworded `updates.autoCheck*` strings and the two new ones.

**Not done:** the browser still makes its own per-load request to GitHub for the card's release notes while the switch is on (a second, cheap call; the server's notification is the primary signal).
