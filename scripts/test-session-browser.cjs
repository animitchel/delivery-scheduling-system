const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const path = require("node:path");
const fs = require("node:fs");
const artifactRoot = path.resolve(process.env.SESSION_BROWSER_ARTIFACTS_DIR || "target/browser-artifacts");
fs.mkdirSync(artifactRoot, { recursive: true });
const artifact = name => path.join(artifactRoot, name);

// Resize events and React layout effects must settle before measuring overflow.
async function assertFitsViewport(page) {
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const dimensions = await page.evaluate(() => ({ content: document.documentElement.scrollWidth, viewport: window.innerWidth }));
    assert.ok(dimensions.content <= dimensions.viewport,
        `Horizontal overflow at ${new URL(page.url()).pathname}: ${dimensions.content}px content / ${dimensions.viewport}px viewport`);
}

(async () => {
    const target = new URL(process.argv[2]);
    assert.equal(target.protocol, "http:");
    assert.equal(target.hostname, "127.0.0.1");
    async function navigateWorkspace(page, name) {
        const menu = page.getByRole("button", { name: "Menu", exact: true });
        if (await menu.isVisible()) await menu.click();
        await page.getByRole("link", { name, exact: true }).click();
    }
    const browser = await chromium.launch({ headless: true, executablePath: process.env.SESSION_BROWSER_EXECUTABLE });
    let page;
    try {
        const context = await browser.newContext();
        page = await context.newPage();
        const errors = [];
        page.on("pageerror", error => errors.push(error.message));
        await page.goto(`${target.origin}/html/login.html`);
        await page.waitForURL("**/app/login");
        await page.getByRole("heading", { name: "Welcome back.", exact: true }).waitFor();
        await page.setViewportSize({ width: 1440, height: 1000 });
        await page.screenshot({ path: artifact("react-login-desktop.png"), fullPage: true });
        for (const width of [320, 375, 768]) {
            await page.setViewportSize({ width, height: 900 });
            await assertFitsViewport(page);
        }
        await page.setViewportSize({ width: 375, height: 900 });
        await assertFitsViewport(page);
        await page.screenshot({ path: artifact("react-login-mobile.png"), fullPage: true });
        await page.setViewportSize({ width: 1280, height: 900 });

        await page.locator("#username").fill("smoke.admin");
        await page.locator("#password").fill("incorrect administrator passphrase");
        await page.locator("#loginButton").click();
        await page.locator("#loginMessage").filter({ hasText: "Invalid username or password." }).waitFor();
        await page.locator("#password").fill("smoke test administrator passphrase");
        await page.locator("#loginButton").click();
        await page.waitForURL("**/app/admin");
        await page.locator(".username").filter({ hasText: "smoke.admin" }).waitFor();
        // Sign-in lands directly in React; legacy navigation is no longer required.
        assert.equal(await page.getByRole("link", { name: "Scheduling", exact: true }).count(), 0);
        await page.getByRole("heading", { name: "Keep deliveries moving.", exact: true }).waitFor();
        await page.waitForURL("**/app/admin");
        assert.equal(await page.getByRole("link", { name: "Accounts", exact: true }).count(), 1);
        assert.equal(await page.getByRole("link", { name: "Open accounts", exact: true }).getAttribute("href"), "/app/admin/accounts");
        await page.setViewportSize({ width: 1440, height: 1000 });
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        assert.equal(await page.locator(".brand").count(),1);
        await page.screenshot({ path: artifact("react-workspace-desktop.png"), clip: { x: 0, y: 0, width: 1440, height: 900 } });
        for (const width of [320,375,768]) {
            await page.setViewportSize({ width, height: 900 });
            await assertFitsViewport(page);
        }
        await page.setViewportSize({ width: 375, height: 900 });
        await page.evaluate(() => window.scrollTo(0,0));
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        await page.screenshot({ path: artifact("react-workspace-mobile.png"), clip: { x: 0, y: 0, width: 375, height: 780 } });
        const mobileMenu = page.getByRole("button", { name: "Menu", exact: true });
        assert.equal(await mobileMenu.getAttribute("aria-expanded"), "false");
        assert.equal(await page.getByRole("navigation", { name: "Main navigation" }).count(), 0);
        await mobileMenu.click();
        await page.getByRole("navigation", { name: "Main navigation" }).waitFor();
        await assertFitsViewport(page);
        await page.screenshot({ path: artifact("react-navigation-mobile-open.png"), fullPage: true });
        await page.keyboard.press("Tab");
        assert.equal(await page.evaluate(() => document.activeElement.textContent), "Overview");
        await page.keyboard.press("Escape");
        assert.equal(await mobileMenu.getAttribute("aria-expanded"), "false");
        assert.equal(await mobileMenu.evaluate(node => node === document.activeElement), true);
        await mobileMenu.click();
        await page.getByRole("link", { name: "Accounts", exact: true }).click();
        await page.getByRole("heading", { name: "Accounts", exact: true }).waitFor();
        assert.equal(await mobileMenu.getAttribute("aria-expanded"), "false");
        await page.waitForFunction(() => document.activeElement.id === "main-content");
        await mobileMenu.click();
        assert.equal(await page.getByRole("link", { name: "Accounts", exact: true }).getAttribute("aria-current"), "page");
        await page.getByRole("link", { name: "Overview", exact: true }).click();
        await page.getByRole("heading", { name: "Keep deliveries moving.", exact: true }).waitFor();

        await page.setViewportSize({ width: 1280, height: 900 });
        await page.reload();
        await page.getByRole("heading", { name: "Keep deliveries moving.", exact: true }).waitFor();
        await page.keyboard.press("Tab");
        assert.equal(await page.evaluate(() => document.activeElement.textContent), "Skip to content");
        assert.equal((await context.request.get(`${target.origin}/app/assets/missing.js`)).status(),404);
        const identity=await (await context.request.get(`${target.origin}/api/auth/me`)).json();
        const attack='<img src=x onerror="window.__injected=true">';
        await page.route("**/api/auth/me", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ ...identity, username: attack }) }));
        await page.getByRole("button", { name: "Refresh session", exact: true }).click();
        await page.getByText(attack, { exact: true }).waitFor();
        assert.equal(await page.locator("img").count(),0);
        assert.equal(await page.evaluate(() => window.__injected),undefined);
        await page.unroute("**/api/auth/me");
        await page.route("**/api/auth/me", route => route.fulfill({ status: 503, contentType: "application/json", body: "{}" }));
        await page.getByRole("button", { name: "Refresh session", exact: true }).click();
        await page.getByRole("alert").waitFor();
        assert.equal(await page.getByRole("navigation").count(),0);
        await page.unroute("**/api/auth/me");
        await page.getByRole("button", { name: "Try again", exact: true }).click();
        await page.getByRole("heading", { name: "Keep deliveries moving.", exact: true }).waitFor();
        let shellLogouts=0;
        const countLogout=request => { if(request.method()==="POST" && new URL(request.url()).pathname==="/api/auth/logout") shellLogouts++; };
        page.on("request",countLogout);
        await page.getByRole("button", { name: "Sign out", exact: true }).evaluate(button => { button.click(); button.dispatchEvent(new MouseEvent("click", { bubbles: true })); });
        await page.waitForURL("**/app/login?loggedOut");
        page.off("request",countLogout);
        assert.equal(shellLogouts,1);
        assert.equal((await context.request.get(`${target.origin}/api/auth/me`)).status(),401);
        assert.deepEqual(await page.evaluate(() => [localStorage.length,sessionStorage.length]),[0,0]);
        await page.locator("#username").fill("smoke.admin");
        await page.locator("#password").fill("smoke test administrator passphrase");
        await page.locator("#loginButton").click();
        await page.waitForURL("**/app/admin");
        await page.locator(".username").filter({ hasText: "smoke.admin" }).waitFor();

        // Recipient deep link, real conditional update, safe text and responsive form.
        await page.getByRole("link", {name:"Recipients",exact:true}).click();
        await page.waitForURL("**/app/admin/recipients");
        await page.locator(".recipient-table tbody tr").first().waitFor();
        assert.equal(await page.locator(".recipient-table img").count(),0);
        await page.setViewportSize({width:1440,height:1000});
        await page.waitForTimeout(180); // Let the sidebar's short colour transition settle for the preview.
        await page.screenshot({path:artifact("react-recipients-directory-desktop.png"),clip:{x:0,y:0,width:1440,height:900}});
        // Empty-state preview uses an API-boundary fixture, never a persistent database mutation.
        await page.route("**/api/admin/recipients?*", route => route.fulfill({status:200,contentType:"application/json",body:JSON.stringify({recipients:[],page:0,size:20,totalElements:0,totalPages:0})}));
        await page.getByRole("button", {name:"Reload directory",exact:true}).click();
        await page.getByRole("heading", {name:"Your recipient directory starts here.",exact:true}).waitFor();
        await page.screenshot({path:artifact("react-recipients-empty-desktop.png"),clip:{x:0,y:0,width:1440,height:900}});
        await page.unroute("**/api/admin/recipients?*");
        await page.getByRole("button", {name:"Reload directory",exact:true}).click();
        await page.locator(".recipient-table tbody tr").first().waitFor();
        await page.getByLabel("Search name or address", {exact:true}).fill("zzzzzz-recipient-not-found");
        await page.getByRole("button", {name:"Search recipients",exact:true}).click();
        await page.getByRole("heading", {name:"No recipients match your search.",exact:true}).waitFor();
        await page.getByRole("button", {name:"Clear search",exact:true}).click();
        await page.locator(".recipient-table tbody tr").first().waitFor();
        assert.equal(await page.getByLabel("Search name or address", {exact:true}).inputValue(), "");

        for (const width of [320,375,768]) {
            await page.setViewportSize({width,height:900});
            await assertFitsViewport(page);
            const results = page.getByRole("table", { name: "Recipients", exact: true });
            assert.equal(await results.evaluate(node => node.scrollWidth <= node.clientWidth), true);
            assert.equal(await results.getByRole("rowheader").count(), await results.locator("tbody tr").count());
            assert.equal(await page.locator(".recipient-mobile-label").filter({hasText:"Address"}).first().isVisible(), true);
        }
        await page.setViewportSize({width:375,height:900});
        await page.screenshot({path:artifact("react-recipients-directory-mobile.png"),clip:{x:0,y:0,width:375,height:900}});
        await page.locator(".recipient-table tbody tr").first().evaluate(node => node.scrollIntoView({block:"center"}));
        await page.screenshot({path:artifact("react-recipients-card-mobile.png"),clip:{x:0,y:0,width:375,height:900}});
        await page.evaluate(() => window.scrollTo(0,0));

        await page.setViewportSize({width:1280,height:900});

        await page.locator(".recipient-table tbody button").first().click();
        await page.getByLabel("Contact number (optional)").fill("012345");
        let recipientWrites=0;
        const countRecipient=request=>{if(request.method()==="PUT" && new URL(request.url()).pathname.startsWith("/api/admin/recipients/")) recipientWrites++;};
        page.on("request",countRecipient);
        await page.getByRole("button",{name:"Save recipient",exact:true}).click();
        await page.getByRole("status").filter({hasText:/Recipient .* saved/}).waitFor();
        page.off("request",countRecipient); assert.equal(recipientWrites,1);
        await page.locator(".recipient-table tbody button").first().click();
        await page.getByLabel("First name",{exact:true}).waitFor();
        for(const width of [320,375,768]) {
            await page.setViewportSize({width,height:900});
            await assertFitsViewport(page);
        }
        await page.setViewportSize({width:1440,height:1000});
        await page.evaluate(() => window.scrollTo(0,0));
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        await page.screenshot({path:artifact("react-recipients-desktop.png"),clip:{x:0,y:0,width:1440,height:900}});
        await page.setViewportSize({width:375,height:900});
        await page.getByRole("heading",{name:"Edit recipient",exact:true}).scrollIntoViewIfNeeded();
        await page.screenshot({path:artifact("react-recipients-mobile.png")});
        await page.setViewportSize({width:1280,height:900});
        // A concurrent API writer invalidates the open form's version.
        const recipientDirectory=await (await context.request.get(`${target.origin}/api/admin/recipients?page=0&size=20&search=`)).json();
        const recipientId=recipientDirectory.recipients[0].recipientId;
        const latest=await context.request.get(`${target.origin}/api/admin/recipients/${recipientId}`);
        const latestBody=await latest.json();
        const recipientCsrf=await (await context.request.get(`${target.origin}/api/auth/csrf`)).json();
        const concurrent=await context.request.put(`${target.origin}/api/admin/recipients/${recipientId}`,{headers:{[recipientCsrf.headerName]:recipientCsrf.token,"If-Match":latest.headers().etag},data:{firstName:latestBody.firstName,lastName:latestBody.lastName,address:latestBody.address,contactNumber:"654321",priorityLevel:latestBody.priorityLevel}});
        assert.equal(concurrent.status(),200);
        await page.getByRole("button",{name:"Save recipient",exact:true}).click();
        await page.getByRole("alert").filter({hasText:"changed while you were editing"}).waitFor();
        assert.equal(await page.getByRole("button",{name:"Save recipient",exact:true}).isDisabled(),true);
        await page.getByRole("button",{name:"Reload directory",exact:true}).click();
        await page.locator(".recipient-table tbody tr").first().waitFor();
        // Provider failure is mocked at the API boundary; no live Maps request is made.
        await page.getByRole("button",{name:"Add recipient",exact:true}).click();
        await page.getByRole("button",{name:"Save recipient",exact:true}).click();
        await page.getByRole("alert").filter({hasText:"Check the highlighted fields"}).waitFor();
        assert.equal(await page.getByLabel("First name",{exact:true}).getAttribute("aria-invalid"),"true");
        assert.equal(await page.getByLabel("First name",{exact:true}).evaluate(node => node === document.activeElement),true);

        await page.getByLabel("First name",{exact:true}).fill("Browser");
        await page.getByLabel("Last name",{exact:true}).fill("Recipient");
        await page.getByLabel("Address",{exact:true}).fill("Mock address");
        await page.route("**/api/admin/recipients",route=>route.fulfill({status:503,contentType:"application/json",body:'{}'}),{times:1});
        await page.getByRole("button",{name:"Save recipient",exact:true}).click();
        await page.getByRole("alert").filter({hasText:"Address lookup is unavailable"}).waitFor();
        assert.equal(await page.getByRole("alert").filter({hasText:"Address lookup is unavailable"}).evaluate(node => node.ownerDocument.querySelector(".recipient-fields textarea")?.value),"Mock address");
        // React delivery preparation: real creation plus a committed write with a lost acknowledgement.
        await page.getByRole("link",{name:"Deliveries",exact:true}).click();
        await page.waitForURL("**/app/admin/deliveries");
        await page.getByRole("heading",{name:"Deliveries",exact:true}).waitFor();
        // Render fresh-database guidance using response fixtures, without deleting any data.
        await page.route("**/api/admin/recipients?*", route=>route.fulfill({status:200,contentType:"application/json",body:JSON.stringify({recipients:[],page:0,size:20,totalElements:0,totalPages:0})}));
        await page.route("**/api/admin/deliveries?*", route=>route.fulfill({status:200,contentType:"application/json",body:JSON.stringify({deliveries:[],page:0,size:20,totalElements:0,totalPages:0})}));
        await page.getByRole("button",{name:"Reload recipients",exact:true}).click();
        await page.getByRole("button",{name:"Reload deliveries",exact:true}).click();
        await page.getByRole("heading",{name:"Start with a recipient",exact:true}).waitFor();
        await page.getByRole("heading",{name:"Your delivery workspace starts here.",exact:true}).waitFor();
        await page.setViewportSize({width:1440,height:1000});
        await page.evaluate(()=>window.scrollTo(0,0));
        await page.screenshot({path:artifact("react-delivery-empty-desktop.png"),clip:{x:0,y:0,width:1440,height:900}});
        await page.unroute("**/api/admin/recipients?*"); await page.unroute("**/api/admin/deliveries?*");
        await page.getByRole("button",{name:"Reload recipients",exact:true}).click();
        await page.getByRole("button",{name:"Reload deliveries",exact:true}).click();
        await page.locator(".delivery-recipient-list button").first().waitFor();
        await page.setViewportSize({width:1280,height:900});

        await page.getByRole("button", {name:"Prepare delivery",exact:true}).click();
        assert.equal(await page.locator("#choose-recipient").evaluate(node => node === document.activeElement),true);
        await page.getByLabel("Search recipient name or address",{exact:true}).fill("zzzzzz-delivery-recipient-not-found");
        await page.getByRole("button",{name:"Search recipients",exact:true}).click();
        await page.getByRole("heading",{name:"No recipients match your search.",exact:true}).waitFor();
        await page.getByRole("button",{name:"Clear recipient search",exact:true}).click();

        await page.locator(".delivery-recipient-list button").first().click();
        await page.getByText(/Selected recipient:/).waitFor();
        await page.getByRole("button",{name:"Create delivery",exact:true}).click();
        await page.getByRole("alert").filter({hasText:"Enter a valid scheduled date"}).waitFor();
        assert.equal(await page.locator("#delivery-date").evaluate(node => node === document.activeElement),true);

        await page.getByLabel("Scheduled date",{exact:true}).fill("2020-01-02");
        await page.getByLabel("Start time",{exact:true}).fill("09:00");
        await page.getByLabel("End time",{exact:true}).fill("12:00");
        await page.getByLabel("Special instructions (optional)",{exact:true}).fill("<img src=x onerror=window.__injected=true>");
        await page.setViewportSize({width:1440,height:1000});
        await page.locator("#delivery-create").evaluate(node=>node.scrollIntoView({block:"start"}));
        await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
        await page.screenshot({path:artifact("react-deliveries-desktop.png"),clip:{x:0,y:0,width:1440,height:900}});
        for(const width of [320,375,768]) {
            await page.setViewportSize({width,height:900});
            await assertFitsViewport(page);
        }
        await page.setViewportSize({width:375,height:900});
        await page.locator("#delivery-create").evaluate(node=>node.scrollIntoView({block:"start"}));
        await page.screenshot({path:artifact("react-deliveries-mobile.png"),clip:{x:0,y:0,width:375,height:780}});
        await page.setViewportSize({width:1280,height:900});
        let deliveryPosts=0;
        const countDeliveryPost=request=>{if(request.method()==="POST" && new URL(request.url()).pathname==="/api/admin/deliveries") deliveryPosts++;};
        page.on("request",countDeliveryPost);
        await page.getByRole("button",{name:"Create delivery",exact:true}).click();
        const creationMessage=page.getByRole("status").filter({hasText:/Delivery #[0-9]+ created\./});
        await creationMessage.waitFor();
        assert.equal(deliveryPosts,1); assert.equal(await page.locator("#delivery-details").count(),1);
        assert.equal(await page.locator(".delivery-details img").count(),0); assert.equal(await page.evaluate(()=>window.__injected),undefined);
        await page.getByRole("button", {name:/View delivery [0-9]+/}).first().waitFor();
        await page.setViewportSize({width:1440,height:1000});
        await page.locator("#delivery-directory").evaluate(node=>node.scrollIntoView({block:"start"}));
        await page.screenshot({path:artifact("react-delivery-directory-desktop.png"),clip:{x:0,y:0,width:1440,height:900}});
        for (const width of [320,375,768]) {
            await page.setViewportSize({width,height:900}); await assertFitsViewport(page);
            const records=page.getByRole("table",{name:"Delivery records",exact:true});
            assert.equal(await records.evaluate(node=>node.scrollWidth<=node.clientWidth),true);
            assert.equal(await records.getByRole("rowheader").count(),await records.locator("tbody tr").count());
        }
        await page.setViewportSize({width:375,height:900});
        await page.locator(".delivery-directory-results tbody tr").first().evaluate(node=>node.scrollIntoView({block:"center"}));
        await page.screenshot({path:artifact("react-delivery-card-mobile.png"),clip:{x:0,y:0,width:375,height:900}});
        await page.setViewportSize({width:1280,height:900});

        await page.locator(".delivery-recipient-list button").first().click();
        await page.getByText(/Selected recipient:/).waitFor();
        await page.getByLabel("Scheduled date",{exact:true}).fill("2020-01-02");
        await page.getByLabel("Start time",{exact:true}).fill("09:00");
        await page.getByLabel("End time",{exact:true}).fill("12:00");
        await page.getByLabel("Special instructions (optional)",{exact:true}).fill("Review this uncertain creation");
        let uncertainDeliveryId;
        await page.route("**/api/admin/deliveries",async route=>{
            const saved=await route.fetch(); assert.equal(saved.status(),201); uncertainDeliveryId=(await saved.json()).deliveryId;
            await route.fulfill({status:201,contentType:"application/json",body:"invalid acknowledgement"});
        },{times:1});
        await page.getByRole("button",{name:"Create delivery",exact:true}).click();
        await page.getByRole("heading",{name:"Check before submitting again",exact:true}).waitFor();
        assert.equal(deliveryPosts,2); assert.equal(await page.getByRole("button",{name:"Create delivery",exact:true}).isDisabled(),true);
        assert.equal(await page.getByRole("button",{name:"I reviewed results; allow another submission",exact:true}).isDisabled(),true);
        const reviewQueryPromise=page.waitForRequest(request=>request.method()==="GET" && new URL(request.url()).pathname==="/api/admin/deliveries");
        await page.getByRole("button",{name:"Review possible saved deliveries",exact:true}).click();
        const reviewQuery=new URL((await reviewQueryPromise).url());
        assert.equal(reviewQuery.searchParams.get("status"),null); assert.equal(reviewQuery.searchParams.get("scheduledDate"),"2020-01-02"); assert.equal(reviewQuery.searchParams.get("recipientId"),String(recipientId));
        await page.getByRole("button",{name:`View delivery ${uncertainDeliveryId}`,exact:true}).waitFor();
        await page.getByRole("button",{name:`View delivery ${uncertainDeliveryId}`,exact:true}).click();
        await page.locator(".delivery-details").getByText("Review this uncertain creation",{exact:true}).waitFor();
        assert.equal(deliveryPosts,2);
        await page.setViewportSize({width:320,height:900});
        await assertFitsViewport(page);
        await page.setViewportSize({width:1280,height:900});
        await page.getByRole("button",{name:"I reviewed results; allow another submission",exact:true}).click();
        assert.equal(await page.getByRole("button",{name:"Create delivery",exact:true}).isEnabled(),true); assert.equal(deliveryPosts,2);
        page.off("request",countDeliveryPost);
        // Legacy scheduling remains accessible directly for compatibility checks.
        await page.goto(`${target.origin}/html/admin.html`);
        await page.waitForURL("**/html/admin.html");
        await page.locator("#sessionAccount").filter({hasText:"smoke.admin"}).waitFor();
        await page.waitForFunction(() => document.querySelector("#recipientId option")?.textContent.includes("<img"));
        assert.equal(await page.evaluate(() => window.__injected), undefined);
        assert.equal(await page.locator("#recipientId img").count(), 0);

        // Check that CSRF reaches the server from the actual shared jQuery prefilter.
        const response = await page.evaluate(() => new Promise(resolve => {
            $.ajax({ url: "/api/v1/scheduling-app/operators", method: "POST", contentType: "application/json", data: "{}" })
                .fail(xhr => resolve(xhr.status));
        }));
        assert.equal(response, 400); // Domain validation was reached, rather than CSRF 403.
        // Administrator route review uses bounded summaries and selected-route details.
        await navigateWorkspace(page, "Routes");
        await page.waitForURL("**/app/admin/routes");
        await page.getByRole("heading",{name:"Routes",exact:true}).waitFor();
        await page.getByRole("button",{name:"Next routes",exact:true}).click();
        await page.getByText("Page 2 of 2 · 24 routes",{exact:true}).waitFor();
        await page.getByRole("button",{name:"Previous routes",exact:true}).click();
        await page.getByText("Page 1 of 2 · 24 routes",{exact:true}).waitFor();
        const otherRoute=Number(process.env.DRIVER_TEST_OTHER_ROUTE);
        await page.getByRole("button",{name:`View route #${otherRoute}`,exact:true}).click();
        await page.getByRole("heading",{name:`Route #${otherRoute}`,exact:true}).waitFor();
        const reviewed=await (await context.request.get(`${target.origin}/api/v1/scheduling-app/routes/${otherRoute}`)).json();
        assert.equal(reviewed.deliveries.length,3);
        await page.locator(".stop-card").first().getByRole("button",{name:"Stop details",exact:true}).click();
        await page.getByText("Test address",{exact:true}).waitFor();
        await page.getByText("09:00 – 12:00",{exact:true}).waitFor();
        assert.equal(await page.locator(".stop-dialog img, .stop-list img, .route-directory img").count(),0);
        await page.keyboard.press("Escape");
        await page.locator(".stop-dialog[open]").waitFor({state:"hidden"});
        assert.equal(await page.locator(".stop-card").first().getByRole("button",{name:"Stop details",exact:true}).evaluate(el=>el===document.activeElement),true);
        for(const width of [320,375,768,1440]) {
            await page.setViewportSize({width,height:1000});
            await assertFitsViewport(page);
        }
        await page.evaluate(()=>window.scrollTo(0,0));
        await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
        await page.screenshot({path:artifact("react-admin-routes-desktop.png"),clip:{x:0,y:0,width:1440,height:900}});
        await page.setViewportSize({width:375,height:1000});
        await page.locator(".stop-card").first().scrollIntoViewIfNeeded();
        await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
        await page.screenshot({path:artifact("react-admin-routes-mobile.png"),clip:{x:0,y:0,width:375,height:800}});
        // A real driver's competing update wins; the administrator must reload before writing again.
        const competing=await browser.newContext();
        let competingCsrf=await (await competing.request.get(`${target.origin}/api/auth/csrf`)).json();
        assert.equal((await competing.request.post(`${target.origin}/api/auth/login`,{headers:{[competingCsrf.headerName]:competingCsrf.token},form:{username:"other.driver",password:"smoke test administrator passphrase"}})).status(),200);
        competingCsrf=await (await competing.request.get(`${target.origin}/api/auth/csrf`)).json();
        assert.equal((await competing.request.patch(`${target.origin}/api/driver/deliveries/${reviewed.deliveries[0].deliveryId}/status`,{headers:{[competingCsrf.headerName]:competingCsrf.token},data:{deliveryStatus:"COMPLETED"}})).status(),200);
        await page.locator(".stop-card").first().getByRole("button",{name:"Mark completed",exact:true}).click();
        await page.getByRole("alert").filter({hasText:"delivery has changed"}).waitFor();
        assert.equal(await page.getByRole("button",{name:"Mark completed",exact:true}).count(),0);
        await page.getByRole("button",{name:"Reload routes",exact:true}).click();
        await page.getByRole("button",{name:`View route #${otherRoute}`,exact:true}).click();
        await page.getByRole("status").filter({hasText:"1 of 3 stops finished"}).waitFor();
        await page.locator(".stop-card").nth(1).getByRole("button",{name:"Mark completed",exact:true}).click();
        await page.getByRole("status").filter({hasText:"2 of 3 stops finished"}).waitFor();
        let operatorState=await (await context.request.get(`${target.origin}/api/v1/scheduling-app/operators/${reviewed.operatorId}`)).json();
        assert.equal(operatorState.availabilityStatus,"ON_DELIVERY");
        await page.locator(".stop-card").nth(2).getByRole("button",{name:"Cancel delivery",exact:true}).click();
        await page.getByRole("button",{name:"Keep delivery",exact:true}).click();
        await page.locator(".stop-card").nth(2).getByRole("button",{name:"Cancel delivery",exact:true}).click();
        await page.getByRole("button",{name:"Confirm cancellation",exact:true}).click();
        await page.getByRole("status").filter({hasText:"3 of 3 stops finished"}).waitFor();
        assert.equal(await page.locator(".stop-card").nth(2).getByRole("button",{name:"Mark completed",exact:true}).isDisabled(),true);
        const finalized=await (await context.request.get(`${target.origin}/api/v1/scheduling-app/routes/${otherRoute}`)).json();
        assert.equal(finalized.routeStatus,"COMPLETED");
        operatorState=await (await context.request.get(`${target.origin}/api/v1/scheduling-app/operators/${reviewed.operatorId}`)).json();
        assert.equal(operatorState.availabilityStatus,"AVAILABLE");
        const driverSnapshot=await (await competing.request.get(`${target.origin}/api/driver/routes/${otherRoute}`)).json();
        assert.equal(driverSnapshot.routeStatus,"COMPLETED"); assert.equal(driverSnapshot.deliveries[2].status,"CANCELLED");
        assert.equal((await competing.request.get(`${target.origin}/api/v1/scheduling-app/routes/directory`)).status(),403);
        await competing.close();
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
        await page.waitForURL("**/app/login?loggedOut");
        assert.equal((await context.request.get(`${target.origin}/api/auth/me`)).status(), 401);
        // Driver login selects only owned routes; another driver's IDs remain unavailable.
        await page.locator("#username").fill("smoke.driver");
        await page.locator("#password").fill("smoke test administrator passphrase");
        await page.locator("#loginButton").click();
        await page.waitForURL("**/app/driver/routes");
        await page.getByRole("heading", {name: "Assigned routes", exact:true}).waitFor();
        await page.goto(`${target.origin}/app/driver`);
        await page.getByRole("heading", { name: "Your next stop starts here.", exact: true }).waitFor();
        assert.equal(await page.getByRole("link", { name: "Accounts", exact: true }).count(),0);
        await page.goto(`${target.origin}/app/admin/routes`);
        await page.getByRole("heading", { name: "You don’t have access to this workspace.", exact: true }).waitFor();
        assert.equal(await page.getByRole("link", { name: "Open accounts", exact: true }).count(),0);
        await page.getByRole("link", { name: "Open your workspace", exact: true }).click();
        await page.getByRole("heading", { name: "Your next stop starts here.", exact: true }).waitFor();
        // Expire the actual backend session without relying on a mocked identity response.
        const shellCsrf=await (await context.request.get(`${target.origin}/api/auth/csrf`)).json();
        assert.equal((await context.request.post(`${target.origin}/api/auth/logout`, { headers: { [shellCsrf.headerName]: shellCsrf.token } })).status(),204);
        await page.getByRole("button", { name: "Refresh session", exact: true }).click();
        await page.getByRole("heading", { name: "Sign in to your workspace.", exact: true }).waitFor();
        assert.equal(await page.getByRole("navigation").count(),0);
        await page.getByRole("link", { name: "Sign in", exact: true }).click();
        await page.locator("#username").fill("smoke.driver");
        await page.locator("#password").fill("smoke test administrator passphrase");
        await page.locator("#loginButton").click();
        await page.waitForURL("**/app/driver/routes");
        await page.getByRole("heading", {name: "Assigned routes", exact:true}).waitFor();

        // React route execution against persisted driver-owned assignments.
        const reactOwned = await (await context.request.get(`${target.origin}/api/driver/routes`)).json();
        assert.equal(reactOwned.length, 2);
        await page.getByLabel("Choose a route").selectOption(String(reactOwned[0].routeId));
        await page.getByRole("heading", {name:`Route #${reactOwned[0].routeId}`, exact:true}).waitFor();
        await page.getByRole("button", {name:"Stop details", exact:true}).click();
        await page.getByText("Test address", {exact:true}).waitFor();
        await page.getByText("09:00 – 12:00", {exact:true}).waitFor();
        await page.getByText("Use side entrance", {exact:true}).waitFor();
        assert.equal(await page.locator(".stop-dialog img, .stop-list img").count(),0);
        await page.keyboard.press("Escape");
        await page.locator(".stop-dialog[open]").waitFor({state:"hidden"});
        assert.equal(await page.getByText("Test address", {exact:true}).count(),0);
        assert.equal(await page.getByRole("button", {name:"Stop details", exact:true}).evaluate(el=>el===document.activeElement),true);
        for (const width of [320,375,768,1440]) {
            await page.setViewportSize({width,height:900});
            await page.evaluate(() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
            await assertFitsViewport(page);
        }
        await page.screenshot({path:artifact("react-driver-desktop.png"),clip:{x:0,y:0,width:1440,height:850}});
        await page.setViewportSize({width:375,height:900});
        await page.evaluate(() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
        await page.screenshot({path:artifact("react-driver-mobile.png"),clip:{x:0,y:0,width:375,height:780}});
        // Conflict responses clear stale assignments and never automatically retry writes.
        let writes=0;
        page.on("request", request=>{if(request.method()==="PATCH" && request.url().includes("/api/driver/deliveries/")) writes++;});
        await page.route("**/api/driver/deliveries/*/status", r=>r.fulfill({status:409,contentType:"application/json",body:'{}'}),{times:1});
        await page.getByRole("button",{name:"Mark completed",exact:true}).click();
        await page.getByRole("alert").filter({hasText:"delivery has changed"}).waitFor();
        assert.equal(await page.getByRole("button",{name:"Mark completed",exact:true}).count(),0);
        assert.equal(writes,1);
        await page.getByRole("button",{name:"Reload routes",exact:true}).click();
        await page.getByRole("button",{name:"Mark completed",exact:true}).waitFor();
        await page.getByRole("button",{name:"Mark completed",exact:true}).click();
        await page.getByRole("status").filter({hasText:"1 of 1 stops finished"}).waitFor();
        assert.equal(await page.getByRole("button",{name:"Mark completed",exact:true}).isDisabled(),true);
        assert.equal(writes,2);
        await page.getByLabel("Choose a route").selectOption(String(reactOwned[1].routeId));
        await page.getByRole("heading",{name:`Route #${reactOwned[1].routeId}`,exact:true}).waitFor();
        await page.getByRole("button",{name:"Cancel delivery",exact:true}).click();
        assert.equal(writes,2);
        await page.getByRole("button",{name:"Keep delivery",exact:true}).click();
        await page.getByRole("button",{name:"Cancel delivery",exact:true}).click();
        await page.getByRole("button",{name:"Confirm cancellation",exact:true}).click();
        await page.getByRole("status").filter({hasText:"1 of 1 stops finished"}).waitFor();
        assert.equal(writes,3);
        const outcomes=await (await context.request.get(`${target.origin}/api/driver/routes`)).json();
        assert.equal(outcomes.find(r=>r.routeId===reactOwned[0].routeId).deliveries[0].status,"COMPLETED");
        assert.equal(outcomes.find(r=>r.routeId===reactOwned[1].routeId).deliveries[0].status,"CANCELLED");
        // Legacy page remains usable for rollback and shows the same authoritative outcomes.
        await page.goto(`${target.origin}/html/operators.html`);
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
        await page.locator(".d-status").filter({ hasText: "COMPLETED" }).waitFor();
        assert.equal(await page.locator(".btn-complete").isDisabled(), true);
        await page.locator("#rh-status").filter({ hasText: "COMPLETED" }).waitFor();
        await page.locator("#routeSelector").selectOption(String(owned[1].routeId));
        await page.locator("#rh-routeId").filter({ hasText: `#${owned[1].routeId}` }).waitFor();
        await page.locator(".d-status").filter({ hasText: "CANCELLED" }).waitFor();
        await page.locator("#logoutButton").click();
        await page.waitForURL("**/app/login?loggedOut");
        await page.locator("#username").fill("empty.driver");
        await page.locator("#password").fill("smoke test administrator passphrase");
        await page.locator("#loginButton").click();
        await page.waitForURL("**/app/driver/routes");
        await page.getByRole("heading",{name:"No assigned routes",exact:true}).waitFor();
        await page.getByRole("button",{name:"Sign out",exact:true}).click();
        await page.waitForURL("**/app/login?loggedOut");
        // Accounts screen: pagination, safe rendering, failures and one-time credentials.
        await page.locator("#username").fill("smoke.admin");
        await page.locator("#password").fill("smoke test administrator passphrase");
        await page.locator("#loginButton").click();
        await page.waitForURL("**/app/admin");
        await navigateWorkspace(page, "Accounts");
        await page.waitForURL("**/app/admin/accounts");
        await page.locator('[aria-label="accounts pagination"]').filter({ hasText: "Page 1 of 2" }).waitFor();
        assert.equal(await page.locator(".accounts-table tbody tr").first().getByRole("button").count(), 0);
        await page.getByRole("button", {name:"Next accounts",exact:true}).click();
        await page.locator('[aria-label="accounts pagination"]').filter({ hasText: "Page 2 of 2" }).waitFor();
        await page.getByRole("button", {name:"Previous accounts",exact:true}).click();
        await page.locator('[aria-label="accounts pagination"]').filter({ hasText: "Page 1 of 2" }).waitFor();
        await page.waitForFunction(() => document.querySelectorAll("#account-operator option").length === 3);
        assert.ok((await page.locator("#account-operator option").allTextContents()).some(text => text.includes("<img")));
        assert.equal(await page.locator("#account-operator img").count(), 0);
        assert.equal(await page.evaluate(() => window.__injected), undefined);
        await page.setViewportSize({width:1440,height:1000});
        await page.evaluate(()=>window.scrollTo(0,0));
        await page.evaluate(() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
        await page.screenshot({ path: artifact("react-accounts-desktop.png"), clip:{x:0,y:0,width:1440,height:900} });
        for (const width of [320,375,768]) {
            await page.setViewportSize({width,height:900});
            await assertFitsViewport(page);
        }
        await page.setViewportSize({ width: 390, height: 844 });
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        assert.equal(await page.locator("nav").count(), 1);
        await page.screenshot({ path: artifact("react-accounts-mobile.png") });
        await page.getByRole("heading",{name:"Account directory",exact:true}).scrollIntoViewIfNeeded();
        await page.screenshot({ path: artifact("react-accounts-mobile-directory.png") });
        await assertFitsViewport(page);
        await page.getByRole("region",{name:"Account directory table",exact:true}).focus();
        await page.keyboard.press("ArrowRight");
        await page.waitForFunction(()=>document.querySelector(".accounts-table").scrollLeft>0);
        await page.getByRole("heading",{name:"Create driver account",exact:true}).scrollIntoViewIfNeeded();
        await page.screenshot({path:artifact("react-accounts-create-mobile.png"),clip:{x:0,y:0,width:390,height:780}});
        await page.locator(".create-account").scrollIntoViewIfNeeded();
        await page.screenshot({path:artifact("react-accounts-create-fields-mobile.png"),clip:{x:0,y:0,width:390,height:780}});
        await page.setViewportSize({ width: 1280, height: 720 });
        await page.locator("#account-username").scrollIntoViewIfNeeded();
        await page.getByLabel("Search eligible operators",{exact:true}).fill("Onboarding");
        await page.getByRole("button",{name:"Search operators",exact:true}).click();
        await page.waitForFunction(()=>document.querySelectorAll("#account-operator option").length===2);
        await page.getByLabel("Search eligible operators",{exact:true}).fill("");
        await page.getByRole("button",{name:"Search operators",exact:true}).click();
        await page.waitForFunction(()=>document.querySelectorAll("#account-operator option").length===3);
        const operatorValue = await page.locator("#account-operator option").filter({ hasText: "Onboarding operator" }).getAttribute("value");
        await page.locator("#account-username").fill("onboarding.driver");
        await page.locator("#account-operator").selectOption(operatorValue);
        await page.locator("#create-current-password").fill("incorrect administrator passphrase");
        await page.getByRole("button",{name:"Create driver account",exact:true}).click();
        await page.getByRole("alert").filter({ hasText: "password was not accepted" }).waitFor();
        assert.equal(await page.locator("#create-current-password").inputValue(), "");
        await page.getByRole("button",{name:"Reload accounts",exact:true}).click();
        await page.locator("#account-operator option").filter({hasText:"Onboarding operator"}).waitFor({state:"attached"});
        await page.locator("#account-operator").selectOption(operatorValue);
        await page.locator("#create-current-password").fill("smoke test administrator passphrase");
        let creations = 0;
        await page.route("**/api/admin/accounts/drivers", async route => {
            creations++;
            await new Promise(resolve => setTimeout(resolve, 200));
            await route.continue();
        });
        await page.getByRole("button",{name:"Create driver account",exact:true}).click();
        assert.equal(await page.getByRole("button",{name:"Create driver account",exact:true}).isDisabled(), true);
        await page.evaluate(() => document.querySelector(".create-account").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
        await page.locator('[aria-labelledby="credential-title"][open]').waitFor();
        assert.equal(creations, 1);
        const temporary = await page.locator("#temporary-driver-password").inputValue();
        assert.equal(temporary.length, 32);
        assert.ok((await page.getByText(/^Expires /).textContent()).includes("Expires"));
        await context.grantPermissions(["clipboard-read", "clipboard-write"]);
        await page.getByRole("button",{name:"Copy password",exact:true}).click();
        await page.getByRole("status").filter({ hasText: "Copied" }).waitFor();
        assert.equal(await page.evaluate(async expected => (await navigator.clipboard.readText()) === expected, temporary), true);
        await page.getByRole("button",{name:"Done",exact:true}).click();
        assert.equal((await page.locator("#temporary-driver-password").inputValue()).length, 0);
        assert.equal(await page.getByText(/^For onboarding.driver/).count(), 0);
        assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
        await page.locator(".accounts-table tbody tr").filter({ hasText: "onboarding.driver" }).waitFor();
        // Failed duplicate creation is shown clearly and never retried automatically.
        await page.locator("#account-username").fill("smoke.driver");
        await page.locator("#account-operator").selectOption({ index: 1 });
        await page.locator("#create-current-password").fill("smoke test administrator passphrase");
        await page.getByRole("button",{name:"Create driver account",exact:true}).click();
        await page.getByRole("alert").filter({ hasText: "conflicts" }).waitFor();
        assert.equal(await page.locator("#create-current-password").inputValue(), "");
        assert.equal(creations, 2);
        await page.getByRole("button",{name:"Reload accounts",exact:true}).click();
        await page.getByRole("button",{name:"Next accounts",exact:true}).click();
        await page.locator(".accounts-table tbody tr").filter({hasText:"onboarding.driver"}).waitFor();
        const driverContext = await browser.newContext();
        const driverPage = await driverContext.newPage();
        driverPage.on("pageerror", error => errors.push(error.message));
        async function driverLogin(password, destination) {
            await driverPage.goto(`${target.origin}/html/login.html`);
            await driverPage.locator("#username").fill("onboarding.driver");
            await driverPage.locator("#password").fill(password);
            await driverPage.locator("#loginButton").click();
            await driverPage.waitForURL(destination === "routes" ? "**/app/driver/routes" : `**/${destination}`);
        }
        async function choosePassword(current, replacement) {
            await driverPage.locator("#currentPassword").fill(current);
            await driverPage.locator("#newPassword").fill(replacement);
            await driverPage.locator("#confirmPassword").fill(replacement);
            await driverPage.locator("#passwordButton").click();
            await driverPage.waitForURL("**/app/login?passwordChanged");
        }
        const permanent = "browser permanent driver passphrase";
        await driverLogin(temporary, "app/change-password");
        await driverPage.getByRole("heading", { name: "Set your password.", exact: true }).waitFor();
        await driverPage.waitForFunction(() => !document.querySelector("#currentPassword").disabled);
        await driverPage.setViewportSize({ width: 1440, height: 1000 });
        await driverPage.screenshot({ path: artifact("react-password-desktop.png"), fullPage: true });
        for (const width of [320, 375, 768]) {
            await driverPage.setViewportSize({ width, height: 900 });
            assert.equal(await driverPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
        }
        await driverPage.setViewportSize({ width: 375, height: 1000 });
        await driverPage.screenshot({ path: artifact("react-password-mobile.png"), fullPage: true });
        await driverPage.goto(`${target.origin}/html/change-password.html`);
        await driverPage.waitForURL("**/app/change-password");

        assert.equal((await driverContext.request.get(`${target.origin}/api/driver/routes`)).status(), 403);
        await choosePassword(temporary, permanent);
        await driverLogin(permanent, "routes");
        await driverPage.getByRole("heading",{name:"No assigned routes",exact:true}).waitFor();
        assert.equal((await driverContext.request.get(`${target.origin}/html/accounts.html`)).status(), 403);
        assert.equal((await driverContext.request.get(`${target.origin}/js/accounts.js`)).status(), 403);
        assert.equal((await driverContext.request.get(`${target.origin}/api/admin/accounts`)).status(),403);
        await driverPage.goto(`${target.origin}/app/admin/accounts`);
        await driverPage.getByRole("heading",{name:"You don’t have access to this workspace.",exact:true}).waitFor();
        function managedRow() { return page.locator(".accounts-table tbody tr").filter({ hasText: "onboarding.driver" }); }
        async function accountAction(label) {
            await managedRow().getByRole("button", { name: label, exact: true }).click();
            await page.locator("#action-current-password").fill("smoke test administrator passphrase");
            await page.locator('[aria-labelledby="action-title"][open]').getByRole("button",{name:/^Confirm /}).click();
        }
        await accountAction("Disable");
        await managedRow().getByRole("button", { name: "Enable", exact: true }).waitFor();
        assert.equal((await driverContext.request.get(`${target.origin}/api/auth/me`)).status(), 401);
        await accountAction("Enable");
        await managedRow().getByRole("button", { name: "Disable", exact: true }).waitFor();
        assert.equal((await driverContext.request.get(`${target.origin}/api/auth/me`)).status(), 401);
        await driverLogin(permanent, "routes");
        await accountAction("Reset password");
        await page.locator('[aria-labelledby="credential-title"][open]').waitFor();
        const resetPassword = await page.locator("#temporary-driver-password").inputValue();
        assert.equal(resetPassword === temporary, false);
        await page.keyboard.press("Escape");
        await page.locator('[aria-labelledby="credential-title"][open]').waitFor({ state: "hidden" });
        assert.equal((await page.locator("#temporary-driver-password").inputValue()).length, 0);
        assert.equal(await page.locator("#action-current-password").inputValue(), "");
        assert.equal((await driverContext.request.get(`${target.origin}/api/auth/me`)).status(), 401);
        await driverLogin(resetPassword, "app/change-password");
        await choosePassword(resetPassword, "browser second permanent passphrase");
        await driverContext.close();
        // Read failures can be retried, while an expired session redirects to sign-in.
        await page.route("**/api/admin/accounts?*", route => route.fulfill({ status: 500, contentType: "application/json", body: '{}' }), { times: 1 });
        await page.getByRole("button",{name:"Reload accounts",exact:true}).click();
        await page.getByRole("alert").filter({ hasText: "could not be loaded" }).waitFor();
        await page.getByRole("button",{name:"Reload accounts",exact:true}).click();
        await page.getByRole("button",{name:"Next accounts",exact:true}).click();
        await managedRow().waitFor();
        // Real generation with deterministic Maps and a committed, lost acknowledgement.
        let generationPosts = 0;
        page.on("request", request => { if (request.method() === "POST" && new URL(request.url()).pathname === "/api/admin/routes/generate") generationPosts++; });
        await navigateWorkspace(page, "Generate routes");
        await page.getByLabel("Planning cutoff date", { exact: true }).fill("2020-01-02");
        await page.getByRole("button", { name: "Review assignment", exact: true }).click();
        await page.getByRole("dialog", { name: "Generate and assign routes?" }).waitFor();
        for (const width of [320, 375, 768, 1440]) {
            await page.setViewportSize({ width, height: 1000 });
            await assertFitsViewport(page);
        }
        await page.screenshot({ path: artifact("react-generation-confirmation.png"), clip: { x: 0, y: 0, width: 1440, height: 900 } });
        await page.keyboard.press("Escape");
        assert.equal(generationPosts, 0);
        await page.getByRole("button", { name: "Review assignment", exact: true }).click();
        await page.getByRole("button", { name: "Confirm generation and assignment", exact: true }).click();
        await page.getByRole("heading", { name: "Generated routes", exact: true }).waitFor();
        assert.equal(generationPosts, 1);
        const generationCsrf = await (await context.request.get(`${target.origin}/api/auth/csrf`)).json();
        const pendingDelivery = await context.request.post(`${target.origin}/api/admin/deliveries`, { headers: { [generationCsrf.headerName]: generationCsrf.token }, data: { recipientId, scheduledDate: "2020-01-02", startTimeWindow: "09:00", endTimeWindow: "12:00", priorityLevel: "MEDIUM", specialInstructions: "Generation recovery fixture" } });
        assert.equal(pendingDelivery.status(), 201);
        const pendingDeliveryId = (await pendingDelivery.json()).deliveryId;
        let committedRouteId;
        await page.route("**/api/admin/routes/generate", async route => {
            const saved = await route.fetch(); assert.equal(saved.status(), 201);
            committedRouteId = (await saved.json()).routes[0].routeId;
            await route.fulfill({ status: 201, contentType: "application/json", body: '{}' });
        }, { times: 1 });
        await page.getByRole("button", { name: "Review assignment", exact: true }).click();
        await page.getByRole("button", { name: "Confirm generation and assignment", exact: true }).click();
        await page.getByRole("heading", { name: "Review before generating again", exact: true }).waitFor();
        assert.equal(generationPosts, 2);
        assert.equal(await page.getByRole("button", { name: "Review assignment", exact: true }).isDisabled(), true);
        const generationReviewed = page.waitForRequest(request => request.url().includes("/api/admin/routes?") && new URL(request.url()).searchParams.get("scheduledDate") === "2020-01-02");
        await page.getByRole("button", { name: "Review existing routes for this date", exact: true }).click();
        assert.equal(new URL((await generationReviewed).url()).searchParams.get("status"), null);
        await page.getByRole("button", { name: `Inspect route ${committedRouteId}`, exact: true }).click();
        await page.getByRole("heading", { name: `Inspect route #${committedRouteId}`, exact: true }).waitFor();
        assert.equal(await page.locator(".generation-stops").innerText().then(text => text.includes(`Delivery #${pendingDeliveryId}`)), true);
        assert.equal(await page.locator(".generation-stops img").count(), 0);
        await page.setViewportSize({ width: 375, height: 900 });
        await page.screenshot({ path: artifact("react-generation-mobile.png"), clip: { x: 0, y: 0, width: 375, height: 780 } });
        await assertFitsViewport(page);
        await page.getByRole("button", { name: "I reviewed results; allow another request", exact: true }).click();
        assert.equal(generationPosts, 2);
        await page.getByRole("link", { name: `Open route #${committedRouteId}`, exact: true }).click();
        await page.getByRole("region", { name: `Route #${committedRouteId} overview`, exact: true }).waitFor();
        // Warehouse setup: actual writes, stale draft review and a committed delete with lost acknowledgement.
        await navigateWorkspace(page, "Warehouses");
        await page.getByRole("button", { name: "New warehouse", exact: true }).click();
        await page.getByLabel("Warehouse name", { exact: true }).fill("Chrome warehouse");
        await page.getByLabel("Warehouse address", { exact: true }).fill("Chrome depot address");
        await page.getByLabel("Latitude", { exact: true }).fill("53.123456");
        await page.getByLabel("Longitude", { exact: true }).fill("-2.5");
        for (const width of [320, 375, 768, 1440]) {
            await page.setViewportSize({ width, height: 1000 });
            await assertFitsViewport(page);
        }
        await page.locator("#warehouse-editor").evaluate(node => node.scrollIntoView({ block: "start" }));
        await page.screenshot({ path: artifact("react-warehouses-desktop.png"), clip: { x: 0, y: 0, width: 1440, height: 900 } });
        await page.getByRole("button", { name: "Save warehouse", exact: true }).click();
        await page.getByText("Warehouse saved.", { exact: true }).waitFor();
        const warehouseRows = await (await context.request.get(`${target.origin}/api/admin/warehouses`)).json();
        const chromeWarehouseId = warehouseRows.warehouses.find(row => row.name === "Chrome warehouse").warehouseId;
        const assignedWarehouseId = warehouseRows.warehouses.find(row => row.name === "Browser warehouse").warehouseId;
        await page.getByRole("button", { name: `Edit warehouse #${chromeWarehouseId}`, exact: true }).click();
        await page.getByLabel("Warehouse name", { exact: true }).fill("Chrome draft warehouse");
        const warehouseBefore = await context.request.get(`${target.origin}/api/admin/warehouses/${chromeWarehouseId}`);
        const warehouseCsrf = await (await context.request.get(`${target.origin}/api/auth/csrf`)).json();
        assert.equal((await context.request.put(`${target.origin}/api/admin/warehouses/${chromeWarehouseId}`, { headers: { [warehouseCsrf.headerName]: warehouseCsrf.token, "If-Match": warehouseBefore.headers().etag }, data: { name: "Other administrator warehouse", address: "Other saved address", latitude: 54, longitude: -2 } })).status(), 200);
        await page.getByRole("button", { name: "Save warehouse", exact: true }).click();
        await page.getByRole("heading", { name: "Review before another write", exact: true }).waitFor();
        assert.equal(await page.getByLabel("Warehouse name", { exact: true }).inputValue(), "Chrome draft warehouse");
        await page.getByRole("button", { name: "Review saved warehouse data", exact: true }).click();
        await page.getByText("Other administrator warehouse", { exact: true }).waitFor();
        await page.setViewportSize({ width: 375, height: 900 });
        await page.locator("#warehouse-recovery").evaluate(node => node.scrollIntoView({ block: "start" }));
        await page.screenshot({ path: artifact("react-warehouses-mobile.png"), clip: { x: 0, y: 0, width: 375, height: 780 } });
        await assertFitsViewport(page);
        await page.getByRole("button", { name: "I compared details; continue with latest version", exact: true }).click();
        await page.getByRole("button", { name: "Save warehouse", exact: true }).click();
        await page.getByText("Warehouse saved.", { exact: true }).waitFor();
        await page.getByRole("button", { name: `Delete warehouse #${assignedWarehouseId}`, exact: true }).click();
        await page.getByRole("dialog", { name: "Delete this warehouse?", exact: true }).waitFor();
        await page.keyboard.press("Escape");
        await page.getByRole("button", { name: `Delete warehouse #${assignedWarehouseId}`, exact: true }).click();
        await page.getByRole("button", { name: "Confirm warehouse deletion", exact: true }).click();
        await page.getByRole("alert").filter({ hasText: "assigned to operators" }).waitFor();
        await page.getByRole("button", { name: "Review saved warehouse data", exact: true }).click();
        await page.getByRole("heading", { name: "Current saved details", exact: true }).waitFor();
        await page.getByRole("button", { name: "I reviewed results; continue", exact: true }).click();
        await page.getByRole("button", { name: "Reload warehouses", exact: true }).click();
        let warehouseDeletes = 0;
        page.on("request", request => { if (request.method() === "DELETE" && new URL(request.url()).pathname === `/api/admin/warehouses/${chromeWarehouseId}`) warehouseDeletes++; });
        await page.route(`**/api/admin/warehouses/${chromeWarehouseId}`, async route => {
            if (route.request().method() !== "DELETE") { await route.continue(); return; }
            const saved = await route.fetch(); assert.equal(saved.status(), 204);
            await route.fulfill({ status: 200, contentType: "application/json", body: '{}' });
        });
        await page.getByRole("button", { name: `Delete warehouse #${chromeWarehouseId}`, exact: true }).click();
        await page.getByRole("button", { name: "Confirm warehouse deletion", exact: true }).click();
        await page.getByRole("heading", { name: "Review before another write", exact: true }).waitFor();
        await page.getByRole("button", { name: "Review saved warehouse data", exact: true }).click();
        await page.getByText("The warehouse is no longer available. Continuing closes the previous action.", { exact: true }).waitFor();
        assert.equal(warehouseDeletes, 1);
        await page.getByRole("button", { name: "I reviewed results; continue", exact: true }).click();


        // Operator onboarding uses the existing Accounts screen, then a real assignment/lifecycle handover.
        await navigateWorkspace(page, "Operators");
        await page.getByRole("button", { name: "New operator", exact: true }).click();
        await page.getByLabel("Operator name", { exact: true }).fill("Chrome operator");
        await page.getByLabel("Phone number", { exact: true }).fill("+447123456789");
        await page.getByLabel("Operator email", { exact: true }).fill("chrome.operator@example.test");
        await page.getByLabel("Vehicle type", { exact: true }).fill("Van");
        await page.getByLabel("Maximum capacity", { exact: true }).fill("25");
        await page.getByRole("button", { name: `Choose warehouse #${assignedWarehouseId}`, exact: true }).click();
        for (const width of [320, 375, 768, 1440]) {
            await page.setViewportSize({ width, height: 1000 });
            await assertFitsViewport(page);
        }
        await page.locator("#operator-editor").evaluate(node => node.scrollIntoView({ block: "start" }));
        await page.screenshot({ path: artifact("react-operators-desktop.png"), clip: { x: 0, y: 0, width: 1440, height: 900 } });
        await page.getByRole("button", { name: "Save operator", exact: true }).click();
        await page.getByText("Operator saved.", { exact: true }).waitFor();
        const operatorRows = await (await context.request.get(`${target.origin}/api/admin/operators`)).json();
        const chromeOperatorId = operatorRows.operators.find(row => row.email === "chrome.operator@example.test").operatorId;
        assert.equal(await page.locator(".recipient-table img").count(), 0);
        await page.getByRole("link", { name: "Manage driver login access", exact: true }).click();
        await page.locator("#account-operator option").filter({ hasText: "Chrome operator" }).waitFor({ state: "attached" });
        await page.locator("#account-operator").selectOption(String(chromeOperatorId));
        await page.getByLabel("Driver username", { exact: true }).fill("chrome.operator.driver");
        await page.locator("#create-current-password").fill("smoke test administrator passphrase");
        await page.getByRole("button", { name: "Create driver account", exact: true }).click();
        await page.locator('[aria-labelledby="credential-title"][open]').waitFor();
        const operatorTemporary = await page.locator("#temporary-driver-password").inputValue();
        await page.getByRole("button", { name: "Done", exact: true }).click();
        const operatorContext = await browser.newContext(); const operatorPage = await operatorContext.newPage();
        operatorPage.on("pageerror", error => errors.push(error.message));
        async function operatorLogin(password) {
            await operatorPage.goto(`${target.origin}/html/login.html`); await operatorPage.locator("#username").fill("chrome.operator.driver"); await operatorPage.locator("#password").fill(password); await operatorPage.locator("#loginButton").click();
        }
        await operatorLogin(operatorTemporary); await operatorPage.waitForURL("**/app/change-password");
        const operatorPermanent = "disposable chrome operator permanent passphrase";
        await operatorPage.locator("#currentPassword").fill(operatorTemporary); await operatorPage.locator("#newPassword").fill(operatorPermanent); await operatorPage.locator("#confirmPassword").fill(operatorPermanent); await operatorPage.locator("#passwordButton").click(); await operatorPage.waitForURL("**/app/login?passwordChanged");
        await operatorLogin(operatorPermanent); await operatorPage.waitForURL("**/app/driver/routes"); await operatorPage.getByRole("heading", { name: "No assigned routes", exact: true }).waitFor();
        const planningHeaders = await (await context.request.get(`${target.origin}/api/admin/routes?size=50`)).json();
        const busyOperators = new Set(planningHeaders.routes.filter(route => route.routeStatus !== "COMPLETED").map(route => route.operatorId));
        const eligibleCount = operatorRows.operators.filter(o => o.availabilityStatus === "AVAILABLE" && !busyOperators.has(o.operatorId)).length;
        const operatorCsrf = await (await context.request.get(`${target.origin}/api/auth/csrf`)).json();
        for (let i = 0; i < eligibleCount; i++) assert.equal((await context.request.post(`${target.origin}/api/admin/deliveries`, { headers: { [operatorCsrf.headerName]: operatorCsrf.token }, data: { recipientId, scheduledDate: "1990-01-02", startTimeWindow: "09:00", endTimeWindow: "12:00", priorityLevel: "MEDIUM", specialInstructions: "Operator handover fixture" } })).status(), 201);
        await navigateWorkspace(page, "Generate routes"); await page.getByLabel("Planning cutoff date", { exact: true }).fill("1990-01-02"); await page.getByRole("button", { name: "Review assignment", exact: true }).click(); await page.getByRole("button", { name: "Confirm generation and assignment", exact: true }).click(); await page.getByRole("heading", { name: "Generated routes", exact: true }).waitFor();
        await navigateWorkspace(page, "Operators"); await page.getByRole("button", { name: `Edit operator #${chromeOperatorId}`, exact: true }).click(); await page.getByLabel("Operator name", { exact: true }).waitFor();
        assert.equal(await page.getByLabel("Availability", { exact: true }).isDisabled(), true); assert.equal(await page.getByLabel("Vehicle type", { exact: true }).isDisabled(), true);
        await page.setViewportSize({ width: 375, height: 900 }); await page.locator("#operator-editor").evaluate(node => node.scrollIntoView({ block: "start" })); await page.screenshot({ path: artifact("react-operators-mobile.png"), clip: { x: 0, y: 0, width: 375, height: 780 } });
        await assertFitsViewport(page);
        await operatorPage.getByRole("button", { name: "Reload routes", exact: true }).click(); await operatorPage.getByRole("button", { name: "Mark completed", exact: true }).click(); await operatorPage.getByRole("status").filter({ hasText: "1 of 1 stops finished" }).waitFor();
        assert.equal((await (await context.request.get(`${target.origin}/api/admin/operators/${chromeOperatorId}`)).json()).availabilityStatus, "AVAILABLE");
        await operatorPage.goto(`${target.origin}/app/admin/operators`); await operatorPage.getByRole("heading", { name: "You don’t have access to this workspace.", exact: true }).waitFor(); await operatorContext.close();
        await page.getByRole("button", { name: "Cancel operator edit", exact: true }).click(); await page.getByRole("button", { name: `Delete operator #${chromeOperatorId}`, exact: true }).click(); await page.getByRole("dialog", { name: "Delete this operator?", exact: true }).waitFor(); await page.keyboard.press("Escape");
        await page.getByRole("button", { name: `Delete operator #${chromeOperatorId}`, exact: true }).click(); await page.getByRole("button", { name: "Confirm operator deletion", exact: true }).click(); await page.getByRole("alert").filter({ hasText: "history cannot be deleted" }).waitFor();
        await navigateWorkspace(page, "Accounts");
        await page.getByRole("button", { name: "Reload accounts", exact: true }).waitFor();
        const csrf = await (await context.request.get(`${target.origin}/api/auth/csrf`)).json();
        assert.equal((await context.request.post(`${target.origin}/api/auth/logout`, { headers: { [csrf.headerName]: csrf.token } })).status(), 204);
        await page.getByRole("button",{name:"Reload accounts",exact:true}).click();
        await page.getByRole("link",{name:"Sign in",exact:true}).waitFor();
        assert.equal(await page.locator(".accounts-table").count(),0);
        assert.deepEqual(errors, []);
        console.log("Browser admin/driver login, ownership, route selection, status updates, CSRF, safe rendering and logout passed.");
        fs.writeFileSync(artifact("journey-complete.json"), JSON.stringify({ complete: true }));
    } catch (error) {
        if (page && !page.isClosed()) { try { await page.screenshot({ path: artifact("failure.png"), fullPage: false }); } catch { /* Preserve the original error. */ } }
        throw error;
    } finally { await browser.close(); }
})().catch(error => { console.error(error.stack ?? error.message); process.exitCode = 1; });
