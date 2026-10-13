// Driver requests use dedicated ownership-checked endpoints; administrators retain their APIs.
let BASE_URL;
let DELIVERIES_BASE;
let isDriver;
let routesById = new Map();
let recipientRequestSequence = 0;
let routeHeaderRequestSequence = 0;

// ======= Cache =======

// ======= UI helpers =======
function alertHtml(type, text) {
  return `<div class="alert alert-${type} alert-dismissible fade show" role="alert">
            ${SchedulingAuth.escapeHtml(text)}
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
  return `<span class="badge ${cls}">${SchedulingAuth.escapeHtml(s || "UNKNOWN")}</span>`;
}

function renderRouteHeader(route) {
  $("#routeHeader").removeClass("d-none");
  $("#rh-routeId").text(`#${route.routeId}`);
  $("#rh-status").text(route.routeStatus ? route.routeStatus : "UNKNOWN");
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
    const terminal = (d.status || "").toUpperCase() !== "IN_PROGRESS";

    const clientCellHtml = `
      <a href="#" class="client-link"
         data-delivery-id="${SchedulingAuth.escapeHtml(d.deliveryId)}"
         data-recipient-id="${SchedulingAuth.escapeHtml(d.recipientId || '')}"
         title="View recipient details">
        ${SchedulingAuth.escapeHtml(d.clientName || "View recipient")}
      </a>
    `;

    // NEW: use precomputed hop info from backend
    const travelText = d.fromPrevDurationText
      ? `${d.fromPrevDurationText}${d.fromPrevDistanceKm != null ? ` · ${Number(d.fromPrevDistanceKm).toFixed(1)} km` : ""}`
      : "—";

    const row = `
      <tr data-delivery-id="${SchedulingAuth.escapeHtml(d.deliveryId)}">
        <td><code>${SchedulingAuth.escapeHtml(d.deliveryId)}</code></td>
        <td>${clientCellHtml}</td>
        <td>${SchedulingAuth.escapeHtml(travelText)}</td> <!-- NEW -->
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
function loadRoutes() {
  $("#routeHeader").addClass("d-none");
  $("#globalMsg").empty();
  $("#routeSelector").prop("disabled", true).empty().append($('<option>').val('').text('Loading routes…'));
  renderDeliveries([]);
  $.ajax({
    url: `${BASE_URL}/routes`, method: "GET",
    success: function (routes) {
      routesById = new Map((routes || []).map(route => [String(route.routeId), route]));
      const selector = $("#routeSelector").empty().append($('<option>').val('').text('Select an assigned route'));
      routesById.forEach(route => selector.append($('<option>').val(route.routeId).text(`Route #${route.routeId} · ${route.scheduledDate}`)));
      selector.prop("disabled", routesById.size === 0);
      if (routesById.size === 0) $("#globalMsg").html(alertHtml("info", "No assigned routes available."));
      if (routesById.size === 1) { selector.val([...routesById.keys()][0]); selectRoute(); }
    },
    error: function (xhr) { $("#globalMsg").html(alertHtml("danger", xhr.responseText || "Failed to load routes.")); }
  });
}

function selectRoute() {
  const sequence = ++routeHeaderRequestSequence;
  const id = $("#routeSelector").val();
  $("#routeHeader").addClass("d-none");
  renderDeliveries([]);
  if (!id) return;
  // Reload the selected route so reassignment/deletion is rechecked by the backend.
  $.ajax({
    url: `${BASE_URL}/routes/${encodeURIComponent(id)}`, method: "GET",
    success: function (route) {
      if (sequence !== routeHeaderRequestSequence || $("#routeSelector").val() !== String(route.routeId)) return;
      renderRouteHeader(route); renderDeliveries(route.deliveries || []);
    },
    error: function (xhr) {
      if (sequence === routeHeaderRequestSequence && $("#routeSelector").val() === id) $("#globalMsg").html(alertHtml("danger", xhr.responseText || "Route is unavailable. Reload your routes."));
    }
  });
}
$("#routeSelector").on("change", selectRoute);

// ======= PATCH delivery status =======
function patchDeliveryStatus(deliveryId, newStatus, $row) {
  const selectedRoute = $("#routeSelector").val();
  const $spinner = $row.find(".d-spinner");
  const $msg = $row.find(".d-msg");
  const $completeBtn = $row.find(".btn-complete");
  const $cancelBtn = $row.find(".btn-cancel");

  $msg.text("");
  $spinner.removeClass("d-none");
  $completeBtn.prop("disabled", true);
  $cancelBtn.prop("disabled", true);

  $.ajax({
    url: `${DELIVERIES_BASE}/${encodeURIComponent(deliveryId)}${isDriver ? "/status" : ""}`,
    method: "PATCH",
    contentType: "application/json",
    data: JSON.stringify({ deliveryStatus: newStatus }), // backend expects deliveryStatus
    success: function (updated /* DeliveryResponseDTO */) {
      $row.find(".d-status").html(statusBadge(updated.deliveryStatus));
      $msg.text("Saved.");
      if ($("#routeSelector").val() !== selectedRoute) return;
      const sequence = ++routeHeaderRequestSequence;
      $.ajax({
        url: `${BASE_URL}/routes/${encodeURIComponent(selectedRoute)}`, method: "GET",
        success: function (route) {
          if (sequence !== routeHeaderRequestSequence || $("#routeSelector").val() !== selectedRoute) return;
          renderRouteHeader(route);
        },
        error: function () {
          if (sequence === routeHeaderRequestSequence && $("#routeSelector").val() === selectedRoute) {
            $("#globalMsg").html(alertHtml("warning", "Delivery saved. Reload the route to see its latest status."));
          }
        }
      });
    },
    error: function (xhr) {
      const msg = xhr.responseText || "Update failed.";
      $msg.text(msg);
      const current = $row.find(".d-status").text().trim().toUpperCase();
      if (current === "IN_PROGRESS" && xhr.status !== 404 && xhr.status !== 409) {
        $completeBtn.prop("disabled", false);
        $cancelBtn.prop("disabled", false);
      }
    },
    complete: function () {
      $spinner.addClass("d-none");
    }
  });
}

// Recipient details are fetched through an owned delivery on every modal open.
$("#deliveriesTable").on("click", ".client-link", function (e) {
  e.preventDefault();
  openRecipientModal($(this).data("delivery-id"), $(this).data("recipient-id"));
});

function openRecipientModal(deliveryId, recipientId) {
  const sequence = ++recipientRequestSequence;
  $("#recipientLoading").removeClass("d-none").text("Loading…");
  $("#recipientError").addClass("d-none").text("");
  $("#recipientDetails").addClass("d-none");
  bootstrap.Modal.getOrCreateInstance(document.getElementById("recipientModal")).show();
  const url = isDriver ? `${DELIVERIES_BASE}/${encodeURIComponent(deliveryId)}/recipient`
    : `/api/v1/scheduling-app/recipients/${encodeURIComponent(recipientId)}`;
  $.ajax({
    url, method: "GET",
    success: recipient => { if (sequence === recipientRequestSequence) fillRecipientModal(recipient); },
    error: xhr => { if (sequence === recipientRequestSequence) showRecipientError(xhr.responseText || "Recipient information is unavailable."); }
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
  SchedulingAuth.initializePage(account => {
    isDriver = account.role === "DRIVER";
    BASE_URL = isDriver ? "/api/driver" : "/api/v1/scheduling-app";
    DELIVERIES_BASE = `${BASE_URL}/deliveries`;
    loadRoutes();
  }, ["ADMIN", "DRIVER"]);
});