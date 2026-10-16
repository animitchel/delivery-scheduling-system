# Delivery-window assessment

A pure, opt-in calculation layer for the dissertation/portfolio project. It assesses the existing ordered `CalculatedPlan`; it does not change driver allocation, reorder stops, reject work, persist results or make provider calls. No API, schema or frontend change is included. The live generation workflow does not invoke this assessment automatically.

## Explicit assumptions

The caller must supply a `LocalDateTime` departure and a nonnegative, whole-second `Duration` of service per stop. The same departure applies independently to every driver; service duration is uniform across stops. Neither value is inferred from the planning cutoff, current system clock or scoring time. The cutoff remains an eligibility date, not a departure date.

Dates and times use the same unzoned local calendar model as existing delivery windows. This layer does not model time zones, daylight-saving transitions, shifts or live traffic. Windows constrain when service starts: the opening and closing instants are inclusive. Service may finish after closing. Completion-by-closing semantics would require a separate policy change.

## Calculation

For each driver, begin at the explicit departure and follow the existing stop order:

1. Add the leg's numeric travel seconds to estimate arrival. Distance/text never substitute for missing duration.
2. If early, wait until that delivery's scheduled-date window opens.
3. Classify service start as `WITHIN_WINDOW` or `LATE`; report waiting and lateness durations.
4. Add service duration to obtain departure for the next leg.

Missing numeric travel duration produces `UNKNOWN` for that stop and every following stop on that driver's route. Arrival, service start, departure, waiting and lateness are then null rather than fabricated zero values. Other drivers still start independently. Overdue windows keep their original scheduled dates, and arithmetic carries across midnight without rebasing windows.

Invalid window ranges, negative travel durations, duplicate/unknown/missing delivery coverage, invalid service duration and date overflow are rejected. Validation also checks legs after an unknown estimate. The input plan is never modified; returned route/stop lists are immutable. Runtime is linear in deliveries and route stops, with linear memory for the validation index and result.

## Backend usage

```java
var assessment = DeliveryWindowAssessor.assess(
    calculatedPlan,
    LocalDateTime.of(2026, 1, 2, 8, 0),
    Duration.ofMinutes(10));
```

For example, a 15-minute first leg arriving at 08:15 waits until 09:00. Ten minutes of service ends at 09:10. A subsequent ten-minute leg arrives at 09:20 and is five minutes late for a 09:15 closing time. These are estimates under the supplied assumptions, not a guarantee that a route is feasible.

## Verification and next decision

Tests cover waiting, accumulated service/travel delays, the inclusive closing boundary, zero service/travel, unknown propagation and driver independence, midnight rollover, overdue work, invalid inputs, overflow and immutable outputs. A test assesses actual `RoutePlanner` output twice with different departures, verifying that reassessment makes no additional Maps requests and does not change assignments.

This foundation intentionally provides no automatic warnings or rejection policy. A later change can expose assessment results through an agreed backend contract if useful for the demo. Capacity/load units and stricter scheduling remain separate decisions; frontend and hosting work stay paused.

## Final local validation

Full local CI passed with 559 backend tests (including nine assessor cases), 434 frontend tests, ten disposable MySQL suites, 57 tooling tests, the complete application Chromium journey and all required cross-browser fixture suites. Dependency scanning found zero advisory matches across 392 package/version pairs. Secret scanning found zero blocking findings and the two previously acknowledged historical findings. Hosted GitHub Actions were not verified.
