
$(document).ready(() => SchedulingAuth.initializePage(function () {
    // Date pickers
    flatpickr("#routeDate", {
        dateFormat: "Y-m-d"
    });

    flatpickr("#scheduledDate", {
        dateFormat: "Y-m-d"
    });

    // Time pickers
    flatpickr("#startTimeWindow", {
        enableTime: true,
        noCalendar: true,
        dateFormat: "H:i",
        time_24hr: true
    });

    flatpickr("#endTimeWindow", {
        enableTime: true,
        noCalendar: true,
        dateFormat: "H:i",
        time_24hr: true
    });

    // Function to reload Recipients dropdown
    function loadRecipients() {
        $('#recipientId').empty();
        $.get("/api/v1/scheduling-app/recipients", function (data) {
            data.forEach(rcp => {
                const fullName = `${rcp.firstName} ${rcp.lastName}`;
                $('#recipientId').append($('<option>').val(rcp.recipientId).text(fullName));
            });
        }).fail(function () {
            alert("Failed to load recipients.");
        });
    }

    // Fetch and populate Operators
    $.get("/api/v1/scheduling-app/operators", function (data) {
        data.forEach(op => {
            $('#operatorId').append($('<option>').val(op.operatorId).text(op.name));
        });
    }).fail(function () {
        alert("Failed to load operators.");
    });

    // Initial load of recipients
    loadRecipients();

    // Handle Route form submission
    $('#routeForm').on('submit', function (e) {
        e.preventDefault();

        const routeData = {
            scheduledDate: $('#routeDate').val()
        };

        // Disable button and show spinner
        const $button = $('#routeForm button[type="submit"]');
        $button.prop('disabled', true).text('Generating...');
        $('#routeResponse').html(
            '<div class="spinner-border text-warning" role="status">' +
            '<span class="visually-hidden">Loading...</span></div> Generating routes...'
        );


        $.ajax({
        url: "/api/v1/scheduling-app/routes",
        type: "POST",
        contentType: "application/json",
        data: JSON.stringify(routeData),
        success: function (response) {
            $('#routeResponse').html(
                '<div class="alert alert-success mt-3">✅ Route generation completed successfully!</div>' +
                '<div class="alert alert-info mt-3">⏱️ Backend processing time: ' + (response.processingTimeMs / 1000).toFixed(2) + ' seconds</div>'
            );
            $('#routeForm')[0].reset();
        },
        error: function (xhr) {
            $('#routeResponse').empty().append($('<div class="alert alert-danger mt-3">').text('Failed to generate routes. ' + xhr.responseText));
        },
        complete: function () {
            // Re-enable button
            $button.prop('disabled', false).text('Generate Routes');
        }
    });
    });

    // Handle Recipient form submission
    $('#recipientForm').on('submit', function (e) {
        e.preventDefault();

        const recipientData = {
            firstName: $('#firstName').val(),
            lastName: $('#lastName').val(),
            address: $('#address').val(),
            contactNumber: $('#contactNumber').val(),
            priorityLevel: $('#priorityLevelRecipient').val()
        };

        $.ajax({
            url: "/api/v1/scheduling-app/recipients",
            type: "POST",
            contentType: "application/json",
            data: JSON.stringify(recipientData),
            success: function () {
                $('#recipientResponse').html('<div class="alert alert-success">Recipient created successfully!</div>');
                $('#recipientForm')[0].reset();
                loadRecipients();
            },
            error: function (xhr) {
                $('#recipientResponse').empty().append($('<div class="alert alert-danger">').text('Failed to create recipient. ' + xhr.responseText));
            }
        });
    });

    // Handle Delivery form submission
    $('#deliveryForm').on('submit', function (e) {
        e.preventDefault();

        const deliveryData = {
            operatorId: $('#operatorId').val(),
            recipientId: $('#recipientId').val(),
            scheduledDate: $('#scheduledDate').val(),
            startTimeWindow: $('#startTimeWindow').val(),
            endTimeWindow: $('#endTimeWindow').val(),
            priorityLevel: $('#priorityLevel').val(),
            specialInstructions: $('#specialInstructions').val()
        };

        $.ajax({
            url: "/api/v1/scheduling-app/deliveries",
            type: "POST",
            contentType: "application/json",
            data: JSON.stringify(deliveryData),
            success: function () {
                $('#responseMessage').html('<div class="alert alert-success">Delivery created successfully!</div>');
                $('#deliveryForm')[0].reset();
            },
            error: function (xhr) {
                $('#responseMessage').empty().append($('<div class="alert alert-danger">').text('Failed to create delivery. ' + xhr.responseText));
            }
        });
    });
}));
