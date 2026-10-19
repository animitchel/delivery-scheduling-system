# Portfolio presentation and rehearsal

A five-to-ten-minute walkthrough for an employer, based on the application merged through PR #71 (`0e82fe7`). This is a presentation aid, not a new implementation plan. Adapt the suggested wording to your actual dissertation, personal contribution and use of tools/collaborators; do not claim work, measurements or outcomes you cannot explain.

## Prepare before the conversation

Open the [README](README.md), [architecture guide](ARCHITECTURE_AND_TRADEOFFS.md), [demo walkthrough](PORTFOLIO_BACKEND_DEMO.md) and [recipient-deletion example](RECIPIENT_DELETION.md). Have Java 25 selected, Docker running and Python 3 available. From the repository root, rehearse:

```sh
./scripts/run-backend-demo.sh
```

Allow initial build/dependency/image downloads before the interview; the timed presentation below excludes that preparation. Read the exit status, four PASS stages and completion report before presenting the run as successful. The ignored `.ci-artifacts/portfolio-demo/result.json` must have `complete: true`, `database: MySQL`, an explicit simulation label and four passed steps. A partial report or failed command is not a passing demonstration. See the [runner](scripts/run-backend-demo.sh) for its checks.

Keep the successful output and freshly generated report ready to show. Run live only if time permits; if using earlier evidence, identify its revision/date and say it is a previous run. If the live run fails, acknowledge it and use clearly dated prior evidence rather than claiming success. Do not switch to a development database or use real personal data to rescue a demonstration.

This command exercises real authenticated backend HTTP APIs with fictional fixtures and **simulated travel**. It does not open an interactive UI or leave a server running. Its temporary server/container stop after the walkthrough; it does not use development ports 8081/5174 or the development database. No live Maps key is required; initial downloads may need internet. The report omits passwords, session cookies and CSRF tokens. Detailed setup/cleanup is in [PORTFOLIO_BACKEND_DEMO.md](PORTFOLIO_BACKEND_DEMO.md).

## Eight-minute walkthrough

| Time | Show | Point to explain |
| --- | --- | --- |
| 0:00–2:00 | README and introduction below | The scheduling problem, project scope and the contributions you can substantiate. |
| 2:00–3:00 | Architecture diagram | React calls Spring APIs; Spring owns permissions/business rules; MySQL stores related data. Maps is an external boundary. |
| 3:00–5:00 | Successful demo output and its report | Nearby higher-ID driver selection, explicit departure assumptions, driver release, balanced allocation and retained history. Explain the fixed travel simulation. |
| 5:00–6:00 | Transaction section in architecture guide | Read a snapshot, calculate outside DB transactions, then lock/revalidate/commit atomically. |
| 6:00–7:00 | Recipient-deletion write-up and regression source | A reproduced 500 became a clear 409 while preserving the FK and linked data, including a concurrent new reference. |
| 7:00–8:00 | Limits and closing statement | Greedy routing and hypothetical assessments have explicit limits. Invite questions. |

For five minutes, use a one-minute introduction, two minutes on demo evidence, one minute on the transaction/bug example and one minute on limitations. For ten minutes, use the eight-minute walkthrough plus two minutes of questions. Do not wait for a cold build to finish inside these timings.

### Suggested introduction

> “This is my master's dissertation project: a delivery scheduling application. An administrator prepares deliveries and generates routes; drivers see their own assigned work and complete or cancel stops. The application uses React with TypeScript and Vite, a Java/Spring backend and MySQL.
>
> “The interesting engineering problem is keeping assignments and delivery history consistent while external travel estimates take time and users can change data. I can show how the application separates planning from its database commit, checks ownership and releases a driver once the route is finished.
>
> “After the dissertation, I reviewed and improved the application in focused, tested changes. The examples I will discuss are geographic driver selection, assignment concurrency and a recipient-deletion error contract. I can distinguish those later improvements from the original dissertation implementation and explain my contribution and any assistance accurately.
>
> “This is a local portfolio demonstration. It uses fictional data and explicitly simulated travel; I am not presenting it as a deployed logistics service or a globally optimal routing system.”

Use the remaining introduction time to describe your actual research question or original dissertation result. Those details are personal context; this repository does not establish them, so they are not invented here.

### Explain the demo evidence

| Evidence to point at | Expected outcome | What it demonstrates |
| --- | --- | --- |
| Selected driver in the report | The nearby East driver is selected despite having a higher ID. | Geographic selection instead of simply taking the first ID. |
| Same route assessed with departures at 08:00 and 10:00 | 3,000 seconds waiting versus 2,400 seconds late; zero extra assessment provider calls. | Explicit assumptions and read-only assessment using saved estimates. |
| Completion/release stage | The owning driver completes the first delivery; route becomes COMPLETED and driver AVAILABLE. | Ownership and lifecycle coordination. |
| Final allocation/history stage | Five new deliveries allocated 2/2/1; six deliveries completed overall and four routes retained. | Count balance, exactly-once coverage in this scenario and preserved history. |

Say: “The demo provider supplies ten minutes and 2.5 km for every selected leg, including repeated coordinates. Those are fixed test inputs, not measured road distances. The scheduling, HTTP security and persistence paths are real; the travel provider and fixture setup are test support.” See [BackendPortfolioDemoTest](src/test/java/com/mmu/schedulingapp/demo/BackendPortfolioDemoTest.java). Do not claim the scenario proves optimality, capacity feasibility or real traffic accuracy.

### Explain one design choice and one bug fix

For the design choice, use this sequence: “A short transaction reads immutable planning inputs. The application calculates and calls Maps without holding assignment transactions open. At commit, it locks selected records, checks that the inputs are still current and saves the complete assignment atomically. If another request changed the inputs, it rejects the stale plan.” Point to [RoutePlanningTransactions](src/main/java/com/mmu/schedulingapp/service/RoutePlanningTransactions.java) and the MySQL tests `mapsPauseReleasesMySqlConnectionAndAllowsOperatorEdits` / `simultaneousRouteGenerationCannotClaimSameOperatorOrDeliveryOnMySql` in [DriverMySqlIntegrationTest](src/test/java/com/mmu/schedulingapp/account/DriverMySqlIntegrationTest.java).

For the bug fix, explain problem → evidence → change → verification:

- **Problem:** deleting a recipient referenced by deliveries returned 500. The FK already prevented deletion; this was not demonstrated data loss.
- **Evidence:** five real authenticated HTTP/MySQL regression cases reproduced the response, including a reference committed while deletion waited.
- **Change:** flush deletion within the existing transaction and translate integrity rejection to a fixed, no-store 409. Keep successful 200/DTO and missing 404 behaviour.
- **Verification:** nine cases cover delivery states, history, access checks and the concurrent insert's commit/rollback outcomes. The race test observes MySQL's lock-wait queue before resolving the competing transaction.

Show [RecipientService.deleteRecipient](src/main/java/com/mmu/schedulingapp/service/RecipientService.java) and [RecipientDeletionMySqlTest](src/test/java/com/mmu/schedulingapp/account/RecipientDeletionMySqlTest.java). The current tests expect the fixed response. Describe the original failure using [the recorded reproduction](RECIPIENT_DELETION.md); do not revert the implementation or manufacture a current 500 for the interview. If asked to run the focused suite, use `./scripts/test-account-mysql.sh -Dtest=RecipientDeletionMySqlTest` with Java 25/Docker available, allowing extra time outside the walkthrough.

## Likely interview questions

| Question | Answer outline and evidence |
| --- | --- |
| Why keep one Spring/MySQL application? | The existing workflow benefits from relational constraints and atomic assignments. Service boundaries are sufficient for this project's scope; there is no measured need for distributed services. [Architecture decisions](ARCHITECTURE_AND_TRADEOFFS.md). |
| Why React/Vite rather than Next.js? | This implementation has browser workflows and an existing Spring API; it has no demonstrated requirement for a separate server-rendering layer. It packages the static build with Spring. Explain this project's choice, not a universal ranking of frameworks. [Frontend integration](REACT_FRONTEND.md). |
| How do you prevent one driver updating another's work? | Spring enforces role/session checks; persisted account and assignment ownership constrain driver queries/updates. Client navigation checks are not the authority. [Driver access](DRIVER_ACCESS.md). |
| What happens if two administrators generate routes together? | Both can calculate, but locked revalidation rejects a stale competing assignment. Database rollback protects the whole commit. [Planning transactions](ROUTE_PLANNING_TRANSACTIONS.md). |
| Does the algorithm find the best route? | It uses geographic greedy selection/allocation and weighted stop ranking. It does not establish global optimality, road-network feasibility or workload fairness. [Geographic allocation](GEOGRAPHIC_ALLOCATION.md). |
| Does it respect time windows and vehicle capacity? | It reports hypothetical waiting/lateness with explicit departure and service assumptions. Generation does not enforce window feasibility; stop-count balance does not model load capacity. [Window assessment](ROUTE_WINDOW_ASSESSMENT_API.md). |
| Why use simulated travel in the demo? | Fixed, labelled estimates make the workflow repeatable without paid/live provider dependency. This tests application integration, not Google accuracy. Normal application provider code is unchanged. [Demo isolation](PORTFOLIO_BACKEND_DEMO.md). |
| How did you test it? | Explain relevant unit, HTTP, database and race cases first. PR #70's local run recorded 618 backend tests, 434 frontend tests, twelve MySQL suites, 57 tooling tests and browser checks. Counts are historical evidence, not complete coverage or proof of hosted CI. [CI](CI.md). |
| What would you improve next? | Start from a concrete reproduced defect. Capacity or enforced feasibility would first need agreed load, departure/service and late-delivery policies. Hosting is outside the current goal. [Current status](BACKEND_STATUS_REVIEW.md). |
| What did you personally contribute or learn? | Distinguish the dissertation baseline from later work. Explain the decisions, tests and assistance you actually used; use one change you can walk through in code. Avoid unsupported claims of sole authorship, research novelty or performance gains. |

## Closing and self-check

Suggested closing: “The value of this project is that I can explain its workflow, design decisions and tested failure cases, as well as its limits. The selected improvement pass is complete. I would reopen implementation for a concrete problem or a changed requirement.”

Before presenting, check that you can explain snapshot revalidation, distinguish count balance from capacity and hypothetical assessment from actual arrivals, identify simulated travel, and walk through the deletion race test. Use the [architecture guide](ARCHITECTURE_AND_TRADEOFFS.md) for deeper detail rather than memorising the script. A successful rehearsal means you can finish within the chosen time and answer these questions honestly; no new feature or deployment is required.

This document was checked against merged PR #71 on 19 October 2026. It does not record a fresh demo run or a completed personal rehearsal; generate current evidence when rehearsing. [IMPROVEMENT_ROADMAP.md](IMPROVEMENT_ROADMAP.md#current-next-steps) keeps further implementation paused.
