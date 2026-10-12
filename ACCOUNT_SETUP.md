# Account foundation

Account persistence and controlled initial administrator provisioning are now connected to session authentication. Existing scheduling endpoints and both application pages require an administrator. Driver access remains blocked until backend ownership checks are implemented. See `SESSION_SETUP.md` for the authentication contract and deployment requirements.

## Account rules

- `ADMIN`: manages scheduling data in the planned authorization model; no operator link.
- `DRIVER`: has exactly one existing operator link. Planned access is limited to their own assignments and permitted delivery updates.
- Usernames are stripped, lowercased with `Locale.ROOT`, and restricted to 3–64 ASCII letters, digits, dots, underscores and hyphens, beginning with a letter or digit.
- Passwords require at least 15 Unicode characters and at most 72 UTF-8 bytes. Malformed Unicode is rejected. Passwords are not trimmed or truncated. bcrypt cost 12 stores salted hashes with a `{bcrypt}` prefix; plaintext matching is unsupported.
- Account summaries and entity JSON exclude password hashes. Accounts do not cascade changes or deletion to operators. Deleting a linked operator is restricted.
- No HTTP provisioning endpoint or public registration is added. Additional account creation is currently an internal service operation, for a future authenticated administrator workflow.

## Schema rollout

Review `db/changes/001-create-user-accounts.sql` against the existing schema, take a backup, and apply it through your controlled database change process **before** deploying with `spring.jpa.hibernate.ddl-auto=validate`. The script is a one-time change for a database that already contains `operators(operator_id INT)`. Do not run it when Hibernate has already created `user_accounts` in a development database.

Existing development `ddl-auto=update` can create the entity table. It is not a production migration strategy. This PR deliberately does not turn on a new global migration tool before the existing schema has a baseline.

## Initial administrator

Provisioning requires both `local` and `provision-admin` profiles and is inactive by default. Run a separate local, non-web process against the intended database. Obtain credentials from a password manager and enter them using hidden terminal input; do not put them in tracked files, command arguments or shell history. Set `ACCOUNT_BOOTSTRAP_USERNAME` and `ACCOUNT_BOOTSTRAP_PASSWORD` in that process environment, then run the built application:

```sh
java -jar target/schedulingApp-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local,provision-admin \
  --spring.main.web-application-type=none
```

The `local` profile must already have your intended database configuration. Provisioning refuses a web application context, requires explicit valid credentials for a new account, and skips creation if an administrator already exists. A unique database bootstrap slot prevents concurrent commands from creating two initial administrators. Existing credentials are never reset. After the success message, terminate the provisioning process if it remains running, unset both bootstrap variables, and start the normal application without `provision-admin`.

## Verification

Use Java 25 and Maven. `mvn -B verify` runs application, account and regression tests with H2; MySQL-specific tests are skipped unless the dedicated disposable database is supplied. With Docker available, run:

```sh
./scripts/test-account-mysql.sh
```

This starts a disposable MySQL 8.4.11 container on a random localhost port, tests the reviewed SQL constraints and concurrent bootstrap uniqueness, runs the complete Maven verification, and removes its container. It never uses the normal application database.

## Next dependent PRs

1. Implement driver ownership in the backend and add negative access tests before enabling driver access.
2. Add authenticated administrator account management.
3. Introduce the React + TypeScript + Vite frontend incrementally.

The session-authentication PR closes anonymous scheduling access and restricts the current broad APIs to administrators. Driver ownership and the wider production-readiness roadmap remain pending.
