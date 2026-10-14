# Java 25 build and IntelliJ setup

This branch compiles and runs the application with JDK 25. Spring Boot 4.1.1 provides the supported framework baseline and supports Java 25. Lombok 1.18.48 is configured explicitly as an annotation processor.

## Why the build changed

The older Lombok dependency could not work reliably with a Java 25 compiler. JDK 23 and later also require annotation processors to be selected explicitly. The POM now keeps the Lombok dependency and processor versions aligned and excludes Lombok from the executable JAR.

Maven validates that the build JDK is Java 25 before compiling. Setting the bytecode or language level alone does not select the JVM that runs Maven.

## IntelliJ

1. Reload the Maven project after checking out or pulling this branch.
2. Set Project SDK and language level to Java 25.
3. Set Module SDK to inherit the Project SDK.
4. Set Maven Runner JRE and Maven Importer JDK to Java 25.
5. Set the application run configuration JRE to Java 25.
6. Leave annotation processing enabled; Maven declares the Lombok processor.
7. Rebuild the project and run SchedulingAppApplication.

Keep the working directory at the repository root. Fresh database setup uses `-Dspring.profiles.active=local,schema-managed`; existing databases need the adoption procedure in [DATABASE_MIGRATIONS.md](DATABASE_MIGRATIONS.md) first. Java selection and database profile selection are independent. See [LOCAL_DATABASE.md](LOCAL_DATABASE.md) for Docker/credentials.

Do not modify IntelliJ's own boot runtime just to select the project JDK.

## Command line

Set `JAVA_HOME` to an installed JDK 25 before invoking the Maven wrapper. For the Homebrew installation on this Mac:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version
./mvnw -version
./mvnw --batch-mode --no-transfer-progress clean verify
```

Both version commands should report Java 25. Other machines should use their own installed JDK 25 path.

## Validation

Run Maven verify with JDK 25 to execute the build checks. Existing controller regression tests are retained. The application smoke test starts the actual embedded web server/JPA context with a test-only H2 database and verifies static admin HTML plus the five collection endpoints.

H2 is test-scoped and never replaces the application's MySQL driver. These checks do not exercise Google Maps, modify the local Docker database, or prove MySQL-specific schema compatibility. Verify local MySQL startup separately before deployment.

## Maintenance scope

Spring Boot 4.1.1 replaces the final open-source 3.5 release. See [SPRING_BOOT_UPGRADE.md](SPRING_BOOT_UPGRADE.md) for compatibility decisions and remaining release gates.

A Java 25 GitHub Actions workflow was prepared but is not included in this branch because the available Git push credential lacks workflow permission. CI can run the same Maven wrapper command above once repository workflow access is configured.

## GitHub CI

The disposable-MySQL harness uses the repository Maven wrapper. See [CI.md](CI.md) for the pinned hosted environment, bundled Chromium setup, required test evidence and merge-check configuration.

For the separate fresh managed development database, use [MANAGED_LOCAL_DATABASE.md](MANAGED_LOCAL_DATABASE.md). Its helper selects private configuration explicitly, uses backend 8081 by default, and preserves an already running backend.
