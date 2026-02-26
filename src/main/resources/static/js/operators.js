// ======= CONFIG =======
const BASE_URL = "http://localhost:8080/api/v1/scheduling-app";
const OPERATOR_ID = 1; // single operator
const DELIVERIES_BASE = `${BASE_URL}/deliveries`;
const RECIPIENTS_BASE = `${BASE_URL}/recipients`;

// ======= Cache =======
const recipientCache = {}; // { [recipientId]: RecipientResponseDTO }

// ======= UI helpers =======
function alertHtml(type, text) {
  return `<div class="alert alert-${type} alert-dismissible fade show" role="alert">
            ${text}
            <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Close"></button>
          </div>`;
}

function statusBadge(status) {
  const s = (status || "").toUpperCase();
  let cls = "bg-secondary";
  if (s === "PENDING") cls = "bg-warning text-dark";
  else if (s === "IN_PROGRESS") cls = "bg-info text-dark";
  else if (s === "COMPLETED") cls = "bg-success";
  else if (s === "CANCELLED") cls = "bg-danger";
  return `<span class="badge ${cls}">${s || "UNKNOWN"}</span>`;
}

function renderRouteHeader(route) {
  $("#routeHeader").removeClass("d-none");
  $("#rh-routeId").text(`#${route.routeId}`);
  $("#rh-status").html(route.routeStatus ? route.routeStatus : "UNKNOWN");
  $("#rh-operatorName").text(route.operatorName || "");
  $("#rh-operatorId").text(route.operatorId ?? "");
  $("#rh-date").text(route.scheduledDate || "");
}



function renderDeliveries(deliveries) {
  const $tbody = $("#deliveriesBody").empty();

  if (!deliveries || deliveries.length === 0) {
    $tbody.append(`<tr class="text-muted"><td colspan="5">No deliveries on this route.</td></tr>`);
    return;
  }

  deliveries.forEach(d => {
    const terminal = ["COMPLETED","CANCELLED"].includes((d.status || "").toUpperCase());

    const clientCellHtml = `
      <a href="#" class="client-link"
         data-delivery-id="${d.deliveryId}"
         data-recipient-id="${d.recipientId || ''}"
         title="View recipient details">
        ${d.clientName || "View recipient"}
      </a>
    `;

    // NEW: use precomputed hop info from backend
    const travelText = d.fromPrevDurationText
      ? `${d.fromPrevDurationText}${d.fromPrevDistanceKm != null ? ` · ${Number(d.fromPrevDistanceKm).toFixed(1)} km` : ""}`
      : "—";

    const row = `
      <tr data-delivery-id="${d.deliveryId}">
        <td><code>${d.deliveryId}</code></td>
        <td>${clientCellHtml}</td>
        <td>${travelText}</td> <!-- NEW -->
        <td class="d-status">${statusBadge(d.status)}</td>
        <td>
          <div class="btn-group">
            <button class="btn btn-sm btn-success btn-complete" ${terminal ? "disabled" : ""}>Mark Completed</button>
            <button class="btn btn-sm btn-outline-danger btn-cancel" ${terminal ? "disabled" : ""}>Cancel</button>
          </div>
          <span class="ms-2 d-spinner d-none">⏳</span>
          <span class="ms-2 d-msg text-muted small"></span>
        </td>
      </tr>
    `;
    $tbody.append(row);
  });
}



// ======= Data loading =======
function loadDefaultRoute() {
  $("body").addClass("cursor-wait");
  $("#globalMsg").html("");
  $("#deliveriesBody").html(`<tr><td colspan="4" class="text-muted">Loading routes…</td></tr>`);

  $.ajax({
    url: `${BASE_URL}/routes`,
    method: "GET",
    success: function (routes) {
      // Expect an array of RouteResponseDTO; pick operatorId=1 or first.
      let selected = null;
      if (Array.isArray(routes) && routes.length > 0) {
        selected = routes.find(r => r.operatorId === OPERATOR_ID) || routes[0];
      }

      if (!selected) {
        $("#globalMsg").html(alertHtml("warning", "No routes available."));
        $("#deliveriesBody").html(`<tr><td colspan="4" class="text-muted">—</td></tr>`);
        return;
      }

      renderRouteHeader(selected);
      renderDeliveries(selected.deliveries || []);

      // Preload all recipients by IDs from summaries for instant modal
      preloadRecipientsById(selected.deliveries || []);
    },
    error: function (xhr) {
      const msg = xhr.responseText || "Failed to load routes.";
      $("#globalMsg").html(alertHtml("danger", `❌ ${msg}`));
      $("#deliveriesBody").html(`<tr><td colspan="4" class="text-muted">—</td></tr>`);
    },
    complete: function () {
      $("body").removeClass("cursor-wait");
    }
  });
}

// ======= Preload recipients (batch by query param) =======
function preloadRecipientsById(deliveries) {
  const ids = [...new Set((deliveries || []).map(d => d.recipientId).filter(Boolean))];
  if (ids.length === 0) return;


  $.ajax({
    url: `${RECIPIENTS_BASE}?ids=${ids.join(",")}`,
    method: "GET",
    success: function (list) {
      (list || []).forEach(rec => {

        recipientCache[rec.recipientId] = rec;
      });
    },
    error: function () {
      // Fallback: if batch fails, fetch individually
      ids.forEach(id => {
        if (recipientCache[id]) return;
        $.ajax({
          url: `${RECIPIENTS_BASE}/${id}`,
          method: "GET",
          success: function (rec) { recipientCache[id] = rec; }
        });
      });
    }
  });
}

// ======= PATCH delivery status =======
function patchDeliveryStatus(deliveryId, newStatus, $row) {
  const $spinner = $row.find(".d-spinner");
  const $msg = $row.find(".d-msg");
  const $completeBtn = $row.find(".btn-complete");
  const $cancelBtn = $row.find(".btn-cancel");

  $msg.text("");
  $spinner.removeClass("d-none");
  $completeBtn.prop("disabled", true);
  $cancelBtn.prop("disabled", true);

  $.ajax({
    url: `${DELIVERIES_BASE}/${deliveryId}`,
    method: "PATCH",
    contentType: "application/json",
    data: JSON.stringify({ deliveryStatus: newStatus }), // backend expects deliveryStatus
    success: function (updated /* DeliveryResponseDTO */) {
      $row.find(".d-status").html(statusBadge(updated.deliveryStatus));
      $msg.text("Saved.");
    },
    error: function (xhr) {
      const msg = xhr.responseText || "Update failed.";
      $msg.text(msg);
      const current = $row.find(".d-status").text().trim().toUpperCase();
      if (!["COMPLETED","CANCELLED"].includes(current)) {
        $completeBtn.prop("disabled", false);
        $cancelBtn.prop("disabled", false);
      }
    },
    complete: function () {
      $spinner.addClass("d-none");
    }
  });
}

// ======= Recipient modal flow  =======
$("#deliveriesTable").on("click", ".client-link", function (e) {
  e.preventDefault();
  const recipientId = $(this).data("recipient-id");
  openRecipientModal(recipientId);
});

function openRecipientModal(recipientId) {
  // Reset modal state
  $("#recipientLoading").removeClass("d-none").text("Loading…");
  $("#recipientError").addClass("d-none").text("");
  $("#recipientDetails").addClass("d-none");

  const modalEl = document.getElementById("recipientModal");
  const modal = bootstrap.Modal.getOrCreateInstance(modalEl);
  modal.show();

  if (!recipientId) {
    return showRecipientError("Recipient information is unavailable for this delivery.");
  }

  // Instant from cache
  const cached = recipientCache[recipientId];
  if (cached) return fillRecipientModal(cached);

  // Fallback
  $.ajax({
    url: `${RECIPIENTS_BASE}/${recipientId}`,
    method: "GET",
    success: function (recipient) {
      recipientCache[recipientId] = recipient;
      fillRecipientModal(recipient);
    },
    error: function (xhr) {
      showRecipientError(xhr.responseText || "Failed to load recipient.");
    }
  });
}

function fillRecipientModal(recipient) {
  const name = [recipient.firstName, recipient.lastName].filter(Boolean).join(" ");
  $("#recName").text(name || "—");
  $("#recAddress").text(recipient.address || "—");
  $("#recContact").text(recipient.contactNumber || "—");

  $("#recipientLoading").addClass("d-none");
  $("#recipientError").addClass("d-none");
  $("#recipientDetails").removeClass("d-none");
}

function showRecipientError(msg) {
  $("#recipientLoading").addClass("d-none");
  $("#recipientDetails").addClass("d-none");
  $("#recipientError").removeClass("d-none").text(msg);
}

// ======= Button events =======
$("#deliveriesTable").on("click", ".btn-complete", function () {
  const $row = $(this).closest("tr");
  const id = $row.data("delivery-id");
  patchDeliveryStatus(id, "COMPLETED", $row);
});

$("#deliveriesTable").on("click", ".btn-cancel", function () {
  const $row = $(this).closest("tr");
  const id = $row.data("delivery-id");
  patchDeliveryStatus(id, "CANCELLED", $row);
});

// ======= Boot =======
$(function () {
  loadDefaultRoute(); // always load the single operator's route
});