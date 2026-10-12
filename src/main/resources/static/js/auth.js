/* Shared same-origin session/CSRF contract for the existing pages and future frontend. */
window.SchedulingAuth = (() => {
    let csrfToken;

    async function refreshCsrf() {
        const response = await fetch("/api/auth/csrf", { credentials: "same-origin", cache: "no-store" });
        if (!response.ok) throw new Error("Unable to establish a secure session. Please reload.");
        csrfToken = await response.json();
        return csrfToken;
    }

    async function request(url, options = {}) {
        const target = new URL(url, window.location.href);
        if (target.origin !== window.location.origin) throw new Error("Requests must use the application origin.");
        const method = (options.method || "GET").toUpperCase();
        const headers = new Headers(options.headers);
        if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
            if (!csrfToken) await refreshCsrf();
            headers.set(csrfToken.headerName, csrfToken.token);
        }
        return fetch(target, { ...options, method, headers, credentials: "same-origin", cache: "no-store" });
    }

    async function logout() {
        const response = await request("/api/auth/logout", { method: "POST" });
        if (!response.ok) throw new Error("Sign out failed. Reload the page and try again.");
        csrfToken = undefined;
        window.location.assign("/html/login.html?loggedOut");
    }

    async function initializePage(start, allowedRoles = ["ADMIN"]) {
        try {
            await refreshCsrf();
            const response = await request("/api/auth/me");
            if (response.status === 401) { window.location.assign("/html/login.html"); return; }
            if (!response.ok) throw new Error("Unable to check your session.");
            const account = await response.json();
            if (!allowedRoles.includes(account.role)) throw new Error("You do not have access to this page.");
            document.getElementById("sessionAccount").textContent = account.username;
            document.getElementById("logoutButton").addEventListener("click", () => logout().catch(showError));
            // Only same-origin unsafe jQuery requests receive a CSRF token.
            $.ajaxPrefilter((options, original, xhr) => {
                const target = new URL(options.url, window.location.href);
                const method = (options.type || "GET").toUpperCase();
                if (target.origin === window.location.origin && !["GET", "HEAD", "OPTIONS"].includes(method)) {
                    xhr.setRequestHeader(csrfToken.headerName, csrfToken.token);
                }
            });
            $(document).ajaxError((event, xhr) => {
                if (xhr.status === 401) window.location.assign("/html/login.html");
            });
            start(account);
        } catch (error) { showError(error); }
    }

    function escapeHtml(value) {
        const element = document.createElement("span");
        element.textContent = String(value ?? "");
        return element.innerHTML.replaceAll('"', "&quot;").replaceAll("'", "&#39;");
    }

    function showError(error) {
        const message = document.getElementById("sessionMessage");
        if (message) { message.textContent = error.message; message.hidden = false; }
    }

    return { refreshCsrf, request, logout, initializePage, escapeHtml };
})();
