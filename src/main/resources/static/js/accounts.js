document.addEventListener("DOMContentLoaded", () => {
    const byId = id => document.getElementById(id);
    const actionDialog = byId("accountActionDialog");
    const credentialDialog = byId("credentialDialog");
    let busy = false, ready = false, navigating = false;
    let accountPage = 0, accountPages = 0, accountLoading = false, accountSequence = 0;
    let operatorPage = 0, operatorPages = 0, operatorLoading = false, operatorSequence = 0;
    let pendingAction;
    let credentialSequence = 0;
    let searchTimer;

    function notice(message) {
        byId("accountsMessage").textContent = message;
        byId("accountsMessage").hidden = !message;
    }
    function clearCredential() {
        credentialSequence++;
        byId("temporaryPassword").value = "";
        byId("credentialUsername").textContent = "";
        byId("credentialExpiry").textContent = "";
        byId("credentialMessage").textContent = "";
    }
    function clearSensitive() {
        byId("createPassword").value = "";
        byId("actionPassword").value = "";
        clearCredential();
        if (credentialDialog.open) credentialDialog.close();
    }
    function redirect(path) {
        clearSensitive();
        if (!navigating) { navigating = true; window.location.assign(path); }
    }
    function controls() {
        document.querySelectorAll("[data-mutation]").forEach(button => { button.disabled = busy || !ready; });
        byId("createAccountButton").disabled = busy || !ready || operatorLoading || !byId("accountOperator").value;
        byId("cancelAction").disabled = busy;
        byId("refreshAccounts").disabled = busy || accountLoading || operatorLoading;
        byId("accountsPrevious").disabled = busy || accountLoading || accountPage <= 0;
        byId("accountsNext").disabled = busy || accountLoading || accountPage + 1 >= accountPages;
        byId("operatorPrevious").disabled = busy || operatorLoading || operatorPage <= 0;
        byId("operatorNext").disabled = busy || operatorLoading || operatorPage + 1 >= operatorPages;
        byId("accountOperator").disabled = busy || operatorLoading || byId("accountOperator").options.length <= 1;
        byId("operatorSearch").disabled = busy;
    }
    async function api(path, options = {}) {
        let response;
        try { response = await SchedulingAuth.request(path, options); }
        catch (_) { throw new Error("The request could not be confirmed. Refresh accounts before trying again. If creation succeeded, use Reset password to issue new credentials."); }
        if (response.status === 401) {
            redirect("/html/login.html");
            throw new Error("Your session expired. Sign in again.");
        }
        if (!response.ok) {
            const body = await response.json().catch(() => ({}));
            if (body.passwordChangeRequired) redirect("/html/change-password.html");
            const errors = {
                400: "Check the username, selected operator and required fields.",
                403: options.method ? "Your administrator password was not accepted, or you no longer have access." : "Administrator access is required.",
                404: "This account or operator is no longer available. Refresh the directory.",
                409: "This change conflicts with an existing account. Usernames and operator links must be unique."
            };
            throw new Error(errors[response.status] || "The request could not be confirmed. Refresh accounts before another attempt.");
        }
        if (response.status === 204) return undefined;
        try { return await response.json(); }
        catch (_) { throw new Error("The response could not be confirmed. Refresh accounts; do not repeat a reset automatically."); }
    }
    function cell(row, text) {
        const td = document.createElement("td"); td.textContent = text; row.append(td); return td;
    }
    function tableMessage(text) {
        const row = document.createElement("tr"); const td = cell(row, text); td.colSpan = 6;
        byId("accountRows").replaceChildren(row);
    }
    function renderAccounts(accounts) {
        byId("accountRows").replaceChildren();
        if (!accounts.length) { tableMessage("No accounts found."); return; }
        accounts.forEach(account => {
            const row = document.createElement("tr"); row.dataset.accountId = String(account.id);
            cell(row, account.username); cell(row, account.role);
            cell(row, account.operatorId == null ? "—" : `#${account.operatorId}`);
            cell(row, account.enabled ? "Enabled" : "Disabled");
            cell(row, account.passwordChangeRequired ? "Required" : "Ready");
            const actions = cell(row, "");
            if (account.role === "DRIVER") {
                [[account.enabled ? "Disable" : "Enable", "enabled"], ["Reset password", "reset"]].forEach(([label, kind]) => {
                    const button = document.createElement("button"); button.type = "button";
                    button.className = "btn btn-sm btn-outline-secondary me-2"; button.textContent = label;
                    button.dataset.mutation = ""; button.addEventListener("click", () => openAction(account, kind)); actions.append(button);
                });
            } else actions.textContent = "Administrator account";
            byId("accountRows").append(row);
        });
    }
    async function loadAccounts(page = accountPage) {
        const sequence = ++accountSequence;
        accountLoading = true; tableMessage("Loading accounts…"); controls();
        try {
            const result = await api(`/api/admin/accounts?page=${page}&size=20`);
            if (sequence !== accountSequence) return;
            if (page > 0 && page >= result.totalPages) { await loadAccounts(Math.max(0, result.totalPages - 1)); return; }
            accountPage = result.page; accountPages = result.totalPages;
            renderAccounts(result.accounts);
            byId("accountsPageLabel").textContent = result.totalElements ? `Page ${result.page + 1} of ${result.totalPages} · ${result.totalElements} accounts` : "0 accounts";
        } catch (error) {
            if (sequence === accountSequence) { tableMessage("Accounts could not be loaded. Use Refresh to try again."); notice(error.message); }
        } finally { if (sequence === accountSequence) { accountLoading = false; controls(); } }
    }
    async function loadOperators(page = 0) {
        const sequence = ++operatorSequence;
        operatorLoading = true; byId("operatorMessage").textContent = "Loading eligible operators…"; controls();
        try {
            const search = byId("operatorSearch").value;
            const query = new URLSearchParams({ page: String(page), size: "50", search });
            const result = await api(`/api/admin/accounts/eligible-operators?${query}`);
            if (sequence !== operatorSequence) return;
            const select = byId("accountOperator"); select.replaceChildren(new Option("Select an eligible operator", ""));
            result.operators.forEach(operator => select.append(new Option(`${operator.name} (#${operator.operatorId})`, String(operator.operatorId))));
            operatorPage = result.page; operatorPages = result.totalPages;
            byId("operatorPageLabel").textContent = result.totalPages ? `Page ${result.page + 1} of ${result.totalPages}` : "";
            byId("operatorMessage").textContent = result.totalElements ? `${result.totalElements} eligible operators. Linked operators, including disabled accounts, are excluded.` : "No eligible operators match. Create an operator on the scheduling system or change your search.";
        } catch (error) {
            if (sequence === operatorSequence) {
                byId("accountOperator").replaceChildren(new Option("Operators unavailable", ""));
                byId("operatorMessage").textContent = error.message;
            }
        } finally { if (sequence === operatorSequence) { operatorLoading = false; controls(); } }
    }
    function openAction(account, kind) {
        if (busy) return;
        pendingAction = { account, kind };
        byId("accountActionForm").reset(); byId("actionMessage").textContent = "";
        byId("actionTitle").textContent = `${kind === "reset" ? "Reset password" : account.enabled ? "Disable" : "Enable"} ${account.username}`;
        byId("actionExplanation").textContent = kind === "reset" ? "This revokes existing sessions and issues a temporary password. A disabled account will remain disabled."
            : account.enabled ? "This revokes existing sessions and blocks new sign-ins."
            : "The driver must sign in again. An expired temporary password still needs a reset.";
        actionDialog.showModal(); byId("actionPassword").focus();
    }
    function showCredential(result) {
        clearCredential();
        byId("credentialUsername").textContent = result.account.username;
        byId("temporaryPassword").value = result.temporaryPassword;
        byId("credentialExpiry").textContent = `Expires ${new Date(result.expiresAt).toLocaleString()} (your local time).`;
        credentialDialog.showModal(); byId("closeCredential").focus();
    }
    async function mutation(path, method, body, successMessage, creating = false) {
        if (busy) return;
        busy = true; controls(); notice("");
        byId("createPassword").value = ""; byId("actionPassword").value = "";
        try {
            const result = await api(path, { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
            const expectsCredential = creating || path.endsWith("/reset-password");
            if (expectsCredential && (!result || !result.account || typeof result.account.username !== "string"
                    || !/^[A-Za-z0-9_-]{32}$/.test(result.temporaryPassword || "")
                    || !Number.isFinite(new Date(result.expiresAt).getTime()))) {
                throw new Error("The credential response could not be confirmed. Refresh the directory and issue a new reset if needed.");
            }
            if (actionDialog.open) actionDialog.close();
            notice(successMessage);
            if (expectsCredential) showCredential(result);
            await loadAccounts(creating ? 0 : accountPage);
            if (creating && accountPages > 1) await loadAccounts(accountPages - 1);
            await loadOperators(0);
            if (creating) byId("accountUsername").value = "";
        } catch (error) {
            if (actionDialog.open) byId("actionMessage").textContent = error.message;
            else notice(error.message);
            // Only reads are refreshed; a failed/uncertain mutation is never automatically retried.
            await Promise.all([loadAccounts(accountPage), loadOperators(0)]);
        } finally { busy = false; controls(); }
    }
    byId("createAccountForm").addEventListener("submit", event => {
        event.preventDefault(); if (busy) return;
        mutation("/api/admin/accounts/drivers", "POST", {
            username: byId("accountUsername").value, operatorId: Number(byId("accountOperator").value), currentPassword: byId("createPassword").value
        }, "Driver account created. Share the temporary password securely.", true);
    });
    byId("accountActionForm").addEventListener("submit", event => {
        event.preventDefault(); if (busy || !pendingAction) return;
        const { account, kind } = pendingAction;
        const body = { currentPassword: byId("actionPassword").value };
        if (kind === "enabled") body.enabled = !account.enabled;
        mutation(`/api/admin/accounts/${encodeURIComponent(account.id)}/${kind === "reset" ? "reset-password" : "enabled"}`,
            kind === "reset" ? "POST" : "PATCH", body, kind === "reset" ? "Password reset. Previous sessions have been revoked." : `${account.username} ${account.enabled ? "disabled" : "enabled"}. Previous sessions cannot be reused.`);
    });
    actionDialog.addEventListener("cancel", event => { if (busy) event.preventDefault(); });
    actionDialog.addEventListener("close", () => { if (!actionDialog.open) { byId("actionPassword").value = ""; pendingAction = undefined; } });
    byId("cancelAction").addEventListener("click", () => { if (!busy) { byId("actionPassword").value = ""; actionDialog.close(); } });
    credentialDialog.addEventListener("close", () => { if (!credentialDialog.open) clearCredential(); });
    credentialDialog.addEventListener("cancel", clearCredential);
    byId("closeCredential").addEventListener("click", () => { clearCredential(); credentialDialog.close(); });
    byId("copyCredential").addEventListener("click", async () => {
        const sequence = credentialSequence;
        try {
            await navigator.clipboard.writeText(byId("temporaryPassword").value);
            if (credentialDialog.open && sequence === credentialSequence) byId("credentialMessage").textContent = "Copied. Share it securely with the intended driver.";
        } catch (_) {
            if (credentialDialog.open && sequence === credentialSequence) byId("credentialMessage").textContent = "Clipboard unavailable. Select and copy the password manually before closing.";
        }
    });
    byId("accountOperator").addEventListener("change", controls);
    byId("operatorSearch").addEventListener("input", () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => loadOperators(0), 250); });
    byId("operatorPrevious").addEventListener("click", () => loadOperators(operatorPage - 1));
    byId("operatorNext").addEventListener("click", () => loadOperators(operatorPage + 1));
    byId("accountsPrevious").addEventListener("click", () => loadAccounts(accountPage - 1));
    byId("accountsNext").addEventListener("click", () => loadAccounts(accountPage + 1));
    byId("refreshAccounts").addEventListener("click", () => Promise.all([loadAccounts(accountPage), loadOperators(0)]));
    byId("logoutButton").addEventListener("click", clearSensitive, { capture: true });
    window.addEventListener("pagehide", clearSensitive);
    SchedulingAuth.initializePage(() => { ready = true; Promise.all([loadAccounts(0), loadOperators(0)]); });
});
