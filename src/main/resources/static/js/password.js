document.addEventListener("DOMContentLoaded", () => {
    const form = document.getElementById("passwordForm");
    const message = document.getElementById("passwordMessage");
    const button = document.getElementById("passwordButton");
    document.getElementById("logoutButton").addEventListener("click", () => SchedulingAuth.logout().catch(error => { message.textContent = error.message; }));
    form.addEventListener("submit", async event => {
        event.preventDefault();
        if (form.elements.newPassword.value !== document.getElementById("confirmPassword").value) {
            message.textContent = "New passwords must match."; return;
        }
        button.disabled = true;
        try {
            await SchedulingAuth.refreshCsrf();
            const response = await SchedulingAuth.request("/api/auth/password", {
                method: "POST", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ currentPassword: form.elements.currentPassword.value, newPassword: form.elements.newPassword.value })
            });
            if (response.status === 401) { window.location.assign("/app/login"); return; }
            if (!response.ok) {
                message.textContent = response.status === 403 ? "Current password verification failed." : "Choose a valid, different password and try again.";
                return;
            }
            window.location.assign("/app/login?passwordChanged");
        } catch (error) { message.textContent = error.message; }
        finally { form.reset(); button.disabled = false; }
    });
});
