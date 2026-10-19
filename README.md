# Delivery scheduling system

A master's dissertation and portfolio project demonstrating delivery preparation, geographic driver selection, balanced route allocation, delivery-window assessment and driver-owned completion workflows.

The application uses Java 25/Spring Boot, MySQL, and React with TypeScript/Vite. It is intended to be demonstrated locally; online hosting is not required.

## Architecture and engineering decisions

Start with [the architecture and tradeoffs guide](ARCHITECTURE_AND_TRADEOFFS.md) for the request flow, permissions, scheduling algorithm, transaction design and demonstration limits.

For an employer walkthrough, use [the five-to-ten-minute presentation and interview guide](PORTFOLIO_PRESENTATION.md).

## Reproducible backend demo

With Java 25 selected, Docker running and Python 3 available:

```sh
./scripts/run-backend-demo.sh
```

This runs a fictional workflow through real authenticated backend APIs using a fresh disposable database. Travel estimates are explicitly simulated; no live Maps credentials or paid requests are used. It leaves existing databases and development ports untouched and cleans up its own server/container. Initial build/image downloads may need internet.

See [the portfolio walkthrough](PORTFOLIO_BACKEND_DEMO.md) for expected results, the result report and cleanup details. The demo provider and fixtures are test support, excluded from the application JAR.

## Development and verification

- [Java setup](JAVA_SETUP.md) and [managed local database](MANAGED_LOCAL_DATABASE.md).
- [CI and regression checks](CI.md).
- [Geographic driver selection](GEOGRAPHIC_DRIVER_SELECTION.md) and [allocation tradeoffs](GEOGRAPHIC_ALLOCATION.md).
- [Saved-route assessment API](ROUTE_WINDOW_ASSESSMENT_API.md).

Routing uses geographic heuristics rather than guaranteed global optimisation. Window assessments are hypothetical estimates, and balanced stop counts do not enforce vehicle-load capacity.

## Current backend status

[Backend status and remaining limits](BACKEND_STATUS_REVIEW.md) reconciles the original audit and records the recipient-deletion fix merged in PR #70. This improvement pass is complete with the architecture guide; the [roadmap](IMPROVEMENT_ROADMAP.md#current-next-steps) preserves deferred limitations and older production phases as reference.
