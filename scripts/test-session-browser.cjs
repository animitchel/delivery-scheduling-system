const { chromium } = require("playwright");
const assert = require("node:assert/strict");

(async () => {
    const target = new URL(process.argv[2]);
    assert.equal(target.protocol, "http:");
    assert.equal(target.hostname, "127.0.0.1");
    const browser = await chromium.launch({ headless: true, executablePath: process.env.SESSION_BROWSER_EXECUTABLE });
    try {
        const context = await browser.newContext();
        const page = await context.newPage();
        const errors = [];
        page.on("pageerror", error => errors.push(error.message));
        await page.goto(`${target.origin}/html/login.html`);
        await page.locator("#username").fill("smoke.admin");
        await page.locator("#password").fill("incorrect administrator passphrase");
        await page.locator("#loginButton").click();
        await page.locator("#loginMessage").filter({ hasText: "Invalid username or password." }).waitFor();
        await page.locator("#password").fill("smoke test administrator passphrase");
        await page.locator("#loginButton").click();
        await page.waitForURL("**/html/admin.html");
        await page.locator("#sessionAccount").filter({ hasText: "smoke.admin" }).waitFor();
        await page.waitForFunction(() => document.querySelector("#recipientId option")?.textContent.includes("<img"));
        assert.equal(await page.evaluate(() => window.__injected), undefined);
        assert.equal(await page.locator("#recipientId img").count(), 0);
        // Check that CSRF reaches the server from the actual shared jQuery prefilter.
        const response = await page.evaluate(() => new Promise(resolve => {
            $.ajax({ url: "/api/v1/scheduling-app/operators", method: "POST", contentType: "application/json", data: "{}" })
                .fail(xhr => resolve(xhr.status));
        }));
        assert.equal(response, 400); // Domain validation was reached, rather than CSRF 403.
        await page.goto(`${target.origin}/html/operators.html`);
        await page.locator("#sessionAccount").filter({ hasText: "smoke.admin" }).waitFor();
        await page.evaluate(() => {
            const attack = '<img src=x onerror="window.__injected=true">';
            renderDeliveries([{ deliveryId: '" onclick="window.__injected=true', recipientId: '" onmouseover="window.__injected=true', clientName: attack, status: attack, fromPrevDurationText: attack }]);
            renderRouteHeader({ routeStatus: attack });
        });
        assert.equal(await page.locator("#deliveriesBody img, #rh-status img").count(), 0);
        assert.equal(await page.evaluate(() => window.__injected), undefined);
        assert.equal(await page.locator("#deliveriesBody [onclick], #deliveriesBody [onmouseover]").count(), 0);
        await page.locator("#logoutButton").click();
        await page.waitForURL("**/html/login.html?loggedOut");
        assert.equal((await context.request.get(`${target.origin}/api/auth/me`)).status(), 401);
        // Driver login selects only owned routes; another driver's IDs remain unavailable.
        await page.locator("#username").fill("smoke.driver");
        await page.locator("#password").fill("smoke test administrator passphrase");
        await page.locator("#loginButton").click();
        await page.waitForURL("**/html/operators.html");
        await page.locator("#sessionAccount").filter({ hasText: "smoke.driver" }).waitFor();
        await page.waitForFunction(() => document.querySelectorAll("#routeSelector option").length === 3);
        const owned = await (await context.request.get(`${target.origin}/api/driver/routes`)).json();
        assert.equal(owned.length, 2);
        assert.equal((await context.request.get(`${target.origin}/api/driver/routes/${process.env.DRIVER_TEST_OTHER_ROUTE}`)).status(), 404);
        assert.equal((await context.request.get(`${target.origin}/api/driver/deliveries/${process.env.DRIVER_TEST_OTHER_DELIVERY}/recipient`)).status(), 404);
        assert.equal((await context.request.get(`${target.origin}/api/v1/scheduling-app/routes`)).status(), 403);
        await page.locator("#routeSelector").selectOption(String(owned[0].routeId));
        await page.locator("#rh-routeId").filter({ hasText: `#${owned[0].routeId}` }).waitFor();
        await page.locator(".client-link").click();
        await page.locator("#recipientDetails:not(.d-none)").waitFor();
        assert.equal(await page.locator("#recAddress").textContent(), "Test address");
        await page.locator("#recipientModal [data-bs-dismiss=modal]").first().click();
        await page.locator("#recipientModal.show").waitFor({ state: "hidden" });
        await page.locator(".btn-complete").click();
        await page.locator(".d-status").filter({ hasText: "COMPLETED" }).waitFor();
        assert.equal(await page.locator(".btn-complete").isDisabled(), true);
        await page.locator("#routeSelector").selectOption(String(owned[1].routeId));
        await page.locator("#rh-routeId").filter({ hasText: `#${owned[1].routeId}` }).waitFor();
        await page.locator(".btn-cancel").click();
        await page.locator(".d-status").filter({ hasText: "CANCELLED" }).waitFor();
        await page.locator("#logoutButton").click();
        await page.waitForURL("**/html/login.html?loggedOut");
        await page.locator("#username").fill("empty.driver");
        await page.locator("#password").fill("smoke test administrator passphrase");
        await page.locator("#loginButton").click();
        await page.waitForURL("**/html/operators.html");
        await page.locator("#globalMsg").filter({ hasText: "No assigned routes available." }).waitFor();
        assert.equal(await page.locator("#routeSelector").isDisabled(), true);
        await page.locator("#logoutButton").click();
        await page.waitForURL("**/html/login.html?loggedOut");
        assert.deepEqual(errors, []);
        console.log("Browser admin/driver login, ownership, route selection, status updates, CSRF, safe rendering and logout passed.");
    } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
