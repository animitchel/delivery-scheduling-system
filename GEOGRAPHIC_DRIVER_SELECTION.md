# Geography-aware driver selection

Driver selection now considers location before applying the existing balanced delivery allocator. This fixes the one-delivery case where a distant low-ID driver previously hid a nearby higher-ID driver.

## Candidate pool and eligibility

The snapshot query still requires AVAILABLE status and no non-completed route. It now reads up to `max-operator-candidates + 1` IDs rather than truncating candidates to the delivery count. The default and hard maximum is 500; the property is `scheduling.maps.max-operator-candidates`, with environment name `SCHEDULING_MAPS_MAXOPERATORCANDIDATES`. Values outside 1–500 fail startup validation.

If the pool exceeds the configured limit, planning fails with 413 before loading candidate entities, requesting Maps estimates or writing assignments. No higher IDs are silently ignored. The cap counts available/no-active-route candidates, including candidates with incomplete warehouse setup. Reducing the delivery cutoff does not reduce this operator pool.

Within that pool, candidates without a warehouse or warehouse coordinates are excluded. Healthy higher-ID candidates can therefore be considered even when a lower-ID driver lacks setup. If none are routable, the existing warehouse-setup conflict is returned; if the initial pool is empty, the existing no-available-operator conflict remains. This deliberately improves the old behaviour where one incomplete selected driver could block a healthy alternative.

## Deterministic heuristic

Select at most one driver per delivery. If there are no more routable candidates than deliveries, retain all candidates in ID order. Otherwise:

1. Calculate great-circle distance for each delivery–warehouse pair locally.
2. Sort pairs by distance, then operator ID, then delivery ID.
3. Greedily match an unused driver with an unmatched delivery until enough drivers are selected.
4. Store selected drivers in ID order in the planning snapshot; the existing balanced allocator and priority-aware route builder then operate normally.

This is a snapshot-time proximity heuristic, not globally optimal matching, fairness rotation, vehicle-load enforcement or road-distance optimisation. It can miss a better overall assignment. Ties still favour lower IDs, but geographic differences are considered first. Newly available or improved unselected candidates after the snapshot do not trigger reselection; selected driver/delivery/warehouse inputs are revalidated under the existing commit locks, and stale inputs reject the entire plan.

Selection makes no provider calls. For C candidates and D deliveries, nearest-pair selection uses O(CD log(CD)) time and O(CD) memory when C>D. Both dimensions and the shared planning deadline bound the operation. Database lock-wait limits remain separate. Skipping unroutable candidates does not change their availability or edit their setup.

## Verification

The one-delivery/two-driver regression first chose the distant low-ID driver and failed before this change. It now chooses the nearby higher-ID driver with exactly one provider call and leaves the distant driver available. Tests also cover mixed locations, stable ties/reversed inputs, unique selection, deadline cancellation, unavailable/active/unconfigured exclusions, explicit candidate-limit rejection and a selected higher-ID driver changing availability during provider work. The stale plan must roll back without assigning the delivery. Real MySQL tests cover geographic selection and bounded-query rejection.

Frontend, API payloads, schema and hosting work are unchanged. Existing generation clients receive the existing 413 status when a planning limit is exceeded; the backend error message identifies excessive operator candidates. This is a dissertation/portfolio backend improvement, not a deployment requirement.

## Final local validation

Full local CI passed with 602 backend tests, 434 frontend tests, ten disposable MySQL suites, 57 tooling tests, the complete application Chromium journey and all required cross-browser fixture suites. The original one-delivery regression failed on lower-ID selection before the fix and now selects the nearby higher-ID driver with one provider call. Eligibility, cap rejection, stale selected-driver rejection, concurrency and rollback checks passed. Dependency scanning found zero advisory matches across 392 package/version pairs; secret scanning found zero blocking findings and two acknowledged historical findings. Hosted GitHub Actions were not verified.
