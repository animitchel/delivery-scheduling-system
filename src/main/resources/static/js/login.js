document.addEventListener("DOMContentLoaded", () => {
    const form = document.getElementById("loginForm");
    const message = document.getElementById("loginMessage");
    const button = document.getElementById("loginButton");
    const logoutButton = document.getElementById("logoutButton");
    if (new URLSearchParams(window.location.search).has("loggedOut")) message.textContent = "You have signed out.";
    if (new URLSearchParams(window.location.search).has("passwordChanged")) message.textContent = "Password saved. Sign in with your new password.";
    logoutButton.addEventListener("click", () => SchedulingAuth.logout().catch(error => { message.textContent = error.message; }));

    form.addEventListener("submit", async event => {
        event.preventDefault();
        button.disabled = true;
        message.textContent = "Signing in…";
        try {
            await SchedulingAuth.refreshCsrf();
            const response = await SchedulingAuth.request("/api/auth/login", {
                method: "POST", body: new URLSearchParams(new FormData(form))
            });
            form.elements.password.value = "";
            if (!response.ok) {
                message.textContent = response.status === 401 ? "Invalid username or password." : "Sign in failed. Please try again.";
                return;
            }
            // Login rotates the session and invalidates the anonymous CSRF token.
            await SchedulingAuth.refreshCsrf();
            const identity = await SchedulingAuth.request("/api/auth/me");
            if (!identity.ok) throw new Error("Unable to check your session. Please try again.");
            const account = await identity.json();
            if (account.passwordChangeRequired) window.location.assign("/html/change-password.html");
            else if (account.role === "ADMIN") window.location.assign("/app/admin");
            else if (account.role === "DRIVER") window.location.assign("/app/driver/routes");
            else throw new Error("Unsupported account role.");
        } catch (error) { message.textContent = error.message; }
        finally { form.elements.password.value = ""; button.disabled = false; }
    });
});
