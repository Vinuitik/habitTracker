# HabitTracker — Flow Index

For detailed flows, see the co-located FLOWS.md files:

| Subsystem | File |
|---|---|
| Auth (Google OAuth, form login, JWT) | `src/main/java/habitTracker/auth/FLOWS.md` |
| Daily cron, habit update, streak calc | `src/main/java/habitTracker/updater/FLOWS.md` |
| UI pages, nav graph, auth flows, AJAX calls, CRUD | `src/main/java/habitTracker/FLOWS_app.md` |
| Per-user Drive offline-sync (connect flow, mailbox replay, encryption) | `src/main/java/habitTracker/sync/FLOWS.md` |
| Offline-sync client (Outbox, Drive bridge, service worker) | `src/main/resources/static/js/offline/FLOWS.md` |
| Projects (Trello-linked, two-step delete) | `src/main/java/habitTracker/Project/FLOWS.md` |
| Backup to Google Drive | `../backup/FLOWS.md` |
| Infrastructure / ingress / secrets | `../FLOWS_infra.md` |
