# Geography-aware driver allocation

This dissertation/portfolio improvement replaces ID-based delivery chunks with a deterministic nearest-warehouse heuristic. It requires no frontend, API or database schema change.

## Behaviour

1. Sort selected operators by ID. Give each a quota of `floor(deliveries/operators)` stops, with one extra for the first remainder operators. These are count quotas, not vehicle capacities.
2. Compute great-circle distances for every delivery–warehouse pair locally.
3. Sort pairs by distance, operator ID, then delivery ID. Assign an unassigned delivery when the operator still has quota available.
4. Pass each allocated set to the existing priority/urgency-aware stop-order builder. Only selected route legs request provider distance/time estimates.

All assignments are calculated before persistence. Immutable snapshot inputs, shared provider-call limits, deadline checks, stale-input validation, atomic commit and rollback remain. Allocation itself makes no Maps requests. Operator selection still comes from the existing bounded available-operator query.

## Evidence and tradeoffs

The new two-warehouse regression first fails on the previous chunking implementation. Warehouses at longitude 0 and 10 on the equator receive alternating-ID deliveries near either warehouse. The new allocation groups the nearby deliveries correctly. The sum of warehouse-to-delivery angular distances falls from 20 to 0.6 degrees (97%) in that fixed synthetic fixture. This metric compares warehouse proximity, not full routes or real travel times; it is not a general improvement guarantee.

Tests cover deterministic results despite reversed input lists, explicit equal-distance tie handling, exact delivery coverage, balanced quotas for 1/2/5/50 deliveries, bounded provider calls and a deadline expiring during allocation before any provider call. Existing ordering, provider fallback and transaction regressions remain required.

For D deliveries and O operators, allocation creates D×O candidates and sorts them in O(DO log(DO)) time with O(DO) additional memory. Existing workload/deadline limits bound the operation; local stop ordering remains quadratic within each route. Deadline checks cover candidate creation, comparison and assignment.

The heuristic does not minimise whole-route road distance or guarantee improvement for every dataset. Fixed quotas can force geographically distant work onto a driver, and assigning the nearest pair first can prevent a better overall allocation. It does not implement load capacity, shifts, return-to-warehouse travel, delivery service durations or arrival-window feasibility. Those require separately agreed domain rules. The original allocation change improved assignments among already selected drivers only. The subsequent [geographic driver-selection change](GEOGRAPHIC_DRIVER_SELECTION.md) replaces lower-ID-only selection with a complete bounded candidate pool and local nearest-pair matching.

## Validation

The two-warehouse test first failed against the old ID-chunk allocation, then passed with the new allocator. Seven new allocation cases pass, including exact coverage, balancing, deterministic ties and deadline cancellation. Full local CI passed with 550 backend tests, 434 frontend tests, ten disposable MySQL suites, 57 tooling tests, the complete real-backend Chromium journey and all required cross-browser fixture suites. Existing concurrency, stale-input, provider-budget, fallback and rollback regressions pass. Dependency scanning checked 392 package/version pairs with zero advisory matches. Secret scanning found zero blocking findings and the two previously acknowledged historical findings. Hosted CI was not verified.
