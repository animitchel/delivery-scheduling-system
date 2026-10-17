# Consistent shared-location delivery ordering

## Problem

`src/main/java/com/mmu/schedulingapp/service/autorouting/RouteBuilder.java` normally selects the lowest `DeliveryScore.total()`, combining recipient priority, delivery priority, straight-line distance and window urgency. Its existing grouping of deliveries less than 0.001 km from the current position instead sorted only by urgency. Equal-window deliveries at the depot could therefore ignore priority; equal-score choices also depended on input order.

Four regression cases reproduced the old behaviour before the fix: shared coordinates at the depot or a later stop, each with forward and reversed input. Each case includes multiple high- and low-priority deliveries with equal windows.

## Behaviour

Shared-location groups now sort by the existing total score with distance zero, followed by ascending delivery ID for equal scores. Normal candidate selection also uses ascending delivery ID for equal total scores, making the first arrival at a shared location consistent with subsequent grouping. Deadline checks remain active during group sorting.

The sub-metre grouping threshold and preference to finish nearby deliveries are retained. Within that group, distance is treated as zero as before. The score weights, captured scoring time, overdue weighting, driver selection, balanced allocation, provider lookup/cache, transaction boundaries and saved data formats are unchanged. This is a deterministic greedy heuristic, not an optimal routing algorithm or a guarantee that every high-priority delivery precedes every low-priority delivery: sufficient urgency can outweigh priority.

## Verification

`src/test/java/com/mmu/schedulingapp/utility/RoutePlannerTest.java` covers:

- Equal-window delivery priorities at the depot and at a later shared stop, with reversed input and explicit ID ties.
- Exact route coverage/order and cached provider usage: one lookup at the depot, two for travel to a shared stop followed by its zero-distance leg.
- Recipient priority and an overdue low-priority recipient legitimately outranking a later high-priority recipient under the existing weights.
- Existing distinct-stop provider budgets, unavailable-provider fallback, deadline handling and repeatable non-mutating calculation.

Run the focused checks with `JAVA_HOME=/path/to/jdk25 ./mvnw -B -Dtest=RoutePlannerTest test`.
