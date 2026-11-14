# Security

## Reporting a vulnerability

Please report a vulnerability privately, not in a public issue. **The security contact is not set yet:** the owner must add a private channel here (a security email address, or GitHub private vulnerability reporting enabled in the repository settings) before the repository is made public. Until then, do not publish details of a vulnerability.

## What Data Forge does and does not do for security

- It makes no network calls of its own. The only connections are to the database you configure and, for the web UI, from your browser to `127.0.0.1`.
- The web UI and the MCP server never accept a database URL or password from a client. The web UI server binds `127.0.0.1`, requires a session token and checks `Host` and `Origin`.
- Passwords are read from an environment variable named in `forge.yml` (`password_env`) or kept in memory by the web and terminal UIs; they are never written to files or logs. A `backup_cmd` runs with your shell's privileges and its output is masked for secrets incompletely: do not put secrets on its command line.
- Cleanup deletes data. Use it only on databases you are allowed to change and can restore ([docs/SAFETY.md](docs/SAFETY.md)).
- `npm audit` reports vulnerabilities in the web UI's development tooling; they were not assessed. The Java and web dependencies and their licences are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
