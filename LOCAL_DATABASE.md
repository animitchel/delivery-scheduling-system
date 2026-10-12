# Local MySQL with Docker

This setup runs only the development database. Run Spring Boot from IntelliJ with Java 25.

## Prerequisites

Install and start Docker Desktop (or another compatible Docker Engine), with Docker Compose available. The database uses the official MySQL 8.4.11 image.

## First-time setup

From the repository root on a fresh checkout:

1. Copy `.env.mysql.local.example` to `.env.mysql.local`.
2. Set non-empty, different values for `DB_PASSWORD` and `MYSQL_ROOT_PASSWORD`.
3. Copy `application-local.properties.example` to `application-local.properties`.

Keep existing local files if you already configured this database. Do not replace credentials for an initialized volume unless you also change the database user's password.

Both local files are ignored by Git. The Spring configuration lives outside `src/main/resources` so it is not bundled in the application JAR.

Start MySQL:

```bash
docker compose --env-file .env.mysql.local -f compose.mysql.local.yaml up -d --wait
```

Check its state:

```bash
docker compose --env-file .env.mysql.local -f compose.mysql.local.yaml ps
```

On the first start of an empty volume, the image creates `scheduling_dev` and grants the `scheduling_app` user access to that database. The root account is for database administration, not application connections.

MySQL is exposed only on `127.0.0.1:3307`. Container data persists in a project-specific named volume.

See [JAVA_SETUP.md](JAVA_SETUP.md) for the complete JDK/Maven setup.

## IntelliJ run configuration

Configure `SchedulingAppApplication` as follows:

- JDK: Java 25.
- Working directory: the repository root.
- VM options: `-Dspring.profiles.active=local`.
- Environment variables: `DB_PASSWORD` matching `.env.mysql.local`; also set `DB_USER`, `DB_NAME` or `DB_PORT` if you changed their defaults.

Docker Compose reads the env file, but Spring Boot does not automatically read it. Supply the variables in IntelliJ's run configuration; avoid publishing or sharing a run configuration containing credentials.

If you already have a private external `application-local.properties` with working credentials, keep it rather than overwriting it with the template.

Run the application after the database reports healthy. Open:

- Admin: http://localhost:8080/html/admin.html
- Driver: http://localhost:8080/html/operators.html

The local profile uses `spring.jpa.hibernate.ddl-auto=update` to initialize/update development tables. Use it only with this development database; production requires controlled migrations.

## Stop and restart

Stop the database while retaining data:

```bash
docker compose --env-file .env.mysql.local -f compose.mysql.local.yaml stop
```

Restart using the same start command above. Do not remove the named volume if you want to retain your development data.

## Troubleshooting

- **Cannot connect to the Docker daemon:** start Docker Desktop and wait for its engine to be ready.
- **Port 3307 already in use:** set a free `DB_PORT` in both the Compose env file and IntelliJ environment.
- **MySQL access denied:** check matching application credentials. Changing initialization env variables does not change users/passwords in an existing volume.
- **Missing DB_PASSWORD:** set it in the IntelliJ environment as well as the Compose env file.
- **Missing database or tables:** wait for MySQL initialization and check that the `local` Spring profile is active.
- **Recipient/route provider failures:** Google Maps configuration is separate from this local database setup.

## Validation scope

The Compose model can be checked without starting a container:

```bash
docker compose --env-file .env.mysql.local -f compose.mysql.local.yaml config --quiet
```

A successful model check does not prove MySQL starts or Spring connects. Verify container health and application startup after Docker Engine is available. Avoid printing the expanded Compose configuration because it includes local passwords.
