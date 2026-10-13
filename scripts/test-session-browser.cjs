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
        await page.locator("#rh-status").filter({ hasText: "COMPLETED" }).waitFor();
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
        // Accounts screen: pagination, safe rendering, failures and one-time credentials.
        await page.locator("#username").fill("smoke.admin");
        await page.locator("#password").fill("smoke test administrator passphrase");
        await page.locator("#loginButton").click();
        await page.waitForURL("**/html/admin.html");
        await page.getByRole("link", { name: "Accounts", exact: true }).click();
        await page.waitForURL("**/html/accounts.html");
        await page.locator("#accountsPageLabel").filter({ hasText: "Page 1 of 2" }).waitFor();
        assert.equal(await page.locator("#accountRows tr").first().getByRole("button").count(), 0);
        await page.locator("#accountsNext").click();
        await page.locator("#accountsPageLabel").filter({ hasText: "Page 2 of 2" }).waitFor();
        await page.locator("#accountsPrevious").click();
        await page.locator("#accountsPageLabel").filter({ hasText: "Page 1 of 2" }).waitFor();
        await page.waitForFunction(() => document.querySelectorAll("#accountOperator option").length === 3);
        assert.ok((await page.locator("#accountOperator option").allTextContents()).some(text => text.includes("<img")));
        assert.equal(await page.locator("#accountOperator img").count(), 0);
        assert.equal(await page.evaluate(() => window.__injected), undefined);
        await page.screenshot({ path: "/private/tmp/accounts-ui-desktop.png", fullPage: true });
        await page.setViewportSize({ width: 390, height: 844 });
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        assert.equal(await page.locator("nav").count(), 1);
        await page.screenshot({ path: "/private/tmp/accounts-ui-mobile.png" });
        await page.locator("#listHeading").scrollIntoViewIfNeeded();
        await page.screenshot({ path: "/private/tmp/accounts-ui-mobile-directory.png" });
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
        await page.setViewportSize({ width: 1280, height: 720 });
        await page.locator("#accountUsername").scrollIntoViewIfNeeded();
        const operatorValue = await page.locator("#accountOperator option").filter({ hasText: "Onboarding operator" }).getAttribute("value");
        await page.locator("#accountUsername").fill("onboarding.driver");
        await page.locator("#accountOperator").selectOption(operatorValue);
        await page.locator("#createPassword").fill("incorrect administrator passphrase");
        await page.locator("#createAccountButton").click();
        await page.locator("#accountsMessage").filter({ hasText: "password was not accepted" }).waitFor();
        assert.equal(await page.locator("#createPassword").inputValue(), "");
        await page.locator("#accountOperator").selectOption(operatorValue);
        await page.locator("#createPassword").fill("smoke test administrator passphrase");
        let creations = 0;
        await page.route("**/api/admin/accounts/drivers", async route => {
            creations++;
            await new Promise(resolve => setTimeout(resolve, 200));
            await route.continue();
        });
        await page.locator("#createAccountButton").click();
        assert.equal(await page.locator("#createAccountButton").isDisabled(), true);
        await page.evaluate(() => document.querySelector("#createAccountForm").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
        await page.locator("#credentialDialog[open]").waitFor();
        assert.equal(creations, 1);
        const temporary = await page.locator("#temporaryPassword").inputValue();
        assert.equal(temporary.length, 32);
        assert.ok((await page.locator("#credentialExpiry").textContent()).includes("Expires"));
        await context.grantPermissions(["clipboard-read", "clipboard-write"]);
        await page.locator("#copyCredential").click();
        await page.locator("#credentialMessage").filter({ hasText: "Copied" }).waitFor();
        assert.equal(await page.evaluate(async expected => (await navigator.clipboard.readText()) === expected, temporary), true);
        await page.locator("#closeCredential").click();
        assert.equal((await page.locator("#temporaryPassword").inputValue()).length, 0);
        assert.equal(await page.locator("#credentialUsername").textContent(), "");
        assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
        await page.locator("#accountRows tr").filter({ hasText: "onboarding.driver" }).waitFor();
        // Failed duplicate creation is shown clearly and never retried automatically.
        await page.locator("#accountUsername").fill("smoke.driver");
        await page.locator("#accountOperator").selectOption({ index: 1 });
        await page.locator("#createPassword").fill("smoke test administrator passphrase");
        await page.locator("#createAccountButton").click();
        await page.locator("#accountsMessage").filter({ hasText: "conflicts" }).waitFor();
        assert.equal(await page.locator("#createPassword").inputValue(), "");
        assert.equal(creations, 2);
        const driverContext = await browser.newContext();
        const driverPage = await driverContext.newPage();
        driverPage.on("pageerror", error => errors.push(error.message));
        async function driverLogin(password, destination) {
            await driverPage.goto(`${target.origin}/html/login.html`);
            await driverPage.locator("#username").fill("onboarding.driver");
            await driverPage.locator("#password").fill(password);
            await driverPage.locator("#loginButton").click();
            await driverPage.waitForURL(`**/html/${destination}`);
        }
        async function choosePassword(current, replacement) {
            await driverPage.locator("#currentPassword").fill(current);
            await driverPage.locator("#newPassword").fill(replacement);
            await driverPage.locator("#confirmPassword").fill(replacement);
            await driverPage.locator("#passwordButton").click();
            await driverPage.waitForURL("**/html/login.html?passwordChanged");
        }
        const permanent = "browser permanent driver passphrase";
        await driverLogin(temporary, "change-password.html");
        assert.equal((await driverContext.request.get(`${target.origin}/api/driver/routes`)).status(), 403);
        await choosePassword(temporary, permanent);
        await driverLogin(permanent, "operators.html");
        await driverPage.locator("#globalMsg").filter({ hasText: "No assigned routes available." }).waitFor();
        assert.equal((await driverContext.request.get(`${target.origin}/html/accounts.html`)).status(), 403);
        assert.equal((await driverContext.request.get(`${target.origin}/js/accounts.js`)).status(), 403);
        function managedRow() { return page.locator("#accountRows tr").filter({ hasText: "onboarding.driver" }); }
        async function accountAction(label) {
            await managedRow().getByRole("button", { name: label, exact: true }).click();
            await page.locator("#actionPassword").fill("smoke test administrator passphrase");
            await page.locator("#confirmAction").click();
        }
        await accountAction("Disable");
        await managedRow().getByRole("button", { name: "Enable", exact: true }).waitFor();
        assert.equal((await driverContext.request.get(`${target.origin}/api/auth/me`)).status(), 401);
        await accountAction("Enable");
        await managedRow().getByRole("button", { name: "Disable", exact: true }).waitFor();
        assert.equal((await driverContext.request.get(`${target.origin}/api/auth/me`)).status(), 401);
        await driverLogin(permanent, "operators.html");
        await accountAction("Reset password");
        await page.locator("#credentialDialog[open]").waitFor();
        const resetPassword = await page.locator("#temporaryPassword").inputValue();
        assert.equal(resetPassword === temporary, false);
        await page.keyboard.press("Escape");
        await page.locator("#credentialDialog[open]").waitFor({ state: "hidden" });
        assert.equal((await page.locator("#temporaryPassword").inputValue()).length, 0);
        assert.equal(await page.locator("#actionPassword").inputValue(), "");
        assert.equal((await driverContext.request.get(`${target.origin}/api/auth/me`)).status(), 401);
        await driverLogin(resetPassword, "change-password.html");
        await choosePassword(resetPassword, "browser second permanent passphrase");
        await driverContext.close();
        // Read failures can be retried, while an expired session redirects to sign-in.
        await page.route("**/api/admin/accounts?*", route => route.fulfill({ status: 500, contentType: "application/json", body: '{}' }), { times: 1 });
        await page.locator("#refreshAccounts").click();
        await page.locator("#accountRows").filter({ hasText: "could not be loaded" }).waitFor();
        await page.locator("#refreshAccounts").click();
        await managedRow().waitFor();
        const csrf = await (await context.request.get(`${target.origin}/api/auth/csrf`)).json();
        assert.equal((await context.request.post(`${target.origin}/api/auth/logout`, { headers: { [csrf.headerName]: csrf.token } })).status(), 204);
        await page.locator("#refreshAccounts").click();
        await page.waitForURL("**/html/login.html");
        assert.deepEqual(errors, []);
        console.log("Browser admin/driver login, ownership, route selection, status updates, CSRF, safe rendering and logout passed.");
    } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
