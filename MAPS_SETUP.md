# Server-side Google Maps configuration

Geocoding and distance requests use the same `GOOGLE_MAPS_API_KEY` environment variable. There is no built-in key or fallback credential. Keep this value on the backend only; never put it in React/Vite environment variables or a shared IntelliJ run configuration.

## Local setup

In the IntelliJ `SchedulingAppApplication` run configuration, add `GOOGLE_MAPS_API_KEY` to the environment alongside your existing database variables. Keep the database profile and JDK 25 settings unchanged. See [LOCAL_DATABASE.md](LOCAL_DATABASE.md) and [JAVA_SETUP.md](JAVA_SETUP.md).

For a terminal session, supply a restricted server key through your local environment or secret manager before starting the application. Do not put the actual key in checked-in properties, shell scripts, documentation or command output. The Docker MySQL env file is only for the database; Spring does not automatically load it as Maps configuration.

The application can start without this variable so static pages and database-only operations remain available. A missing or blank key is rejected before any Maps request. Recipient geocoding returns no result and recipient creation fails without saving; route calculations retain the existing straight-line fallback with unknown travel time. Missing configuration does not mean Google road distances were successfully obtained.

## Credential replacement after this fix

The previously embedded key must be treated as exposed if it is still active. Removing it from current source does not remove copies in Git history, old JARs or retained logs.

1. In the Google Cloud project that owns the key, inspect usage and restrict access while preparing its replacement. If abuse is active, disable the exposed key immediately.
2. Create a replacement restricted to the required Geocoding and Distance Matrix APIs and appropriate server application restrictions. Verify those APIs are enabled for the project.
3. Configure the replacement through backend secrets and verify legitimate provider requests from the intended environment.
4. Revoke/delete the exposed key, verify its old usage stops, and review the access/retention of existing sensitive logs.

Do not paste either key into a PR or chat. Provider-side validity, restrictions and rotation must be verified by the Google Cloud account owner; source tests do not establish those facts. See [Google Maps security guidance](https://developers.google.com/maps/api-security-best-practices).

## Logging and tests

Application geocoding messages now use fixed SLF4J messages. They omit full URLs, keys, addresses, coordinates, raw responses and original exception details. Distance failures expose only a generic exception without its original cause. Low-level wire logging or a separately configured proxy can still capture transport data and should not be enabled with live credentials.

Run the JDK 25 Maven verification described in `JAVA_SETUP.md`. Security tests use dummy credentials and mocked transports; they do not call Google Maps. Existing application smoke tests continue to use test-only H2. Timeouts, route-work budgets, authentication and route lifecycle are separate roadmap tasks.
