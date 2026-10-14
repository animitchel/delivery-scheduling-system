# Frontend design and navigation foundation

This focused change keeps React, TypeScript, Vite and the existing green Dispatch identity. It establishes a shared visual foundation before redesigning individual workflows.

## Shared foundations

`frontend/src/tokens.css` defines semantic colours, spacing, font stack, panel/control radii, shadow and minimum control height. The workspace, account management and driver styles consume these tokens for common panels, inputs, buttons and feedback. Unique page layouts remain in their existing stylesheets. No external fonts, icon packages or CDN requests were added. Inter uses the existing system-font fallback where unavailable.

The workspace has a slightly wider sidebar, restrained page headings, consistent panel borders/corners and a stronger active-page treatment. Decorative navigation icons use local SVG and are hidden from assistive technology. Login and password setup retain their established layout while consuming shared foundations where applicable.

## Navigation behavior

Administrator links are grouped into Setup & access and Delivery operations. Drivers see Overview and Assigned routes. Desktop navigation remains visible. At 900 CSS pixels and below, a labelled Menu button discloses the links in normal document flow; it is not a modal and does not trap focus or lock scrolling.

The toggle exposes `aria-controls` and `aria-expanded`. Hidden links are removed from keyboard/accessibility navigation. Escape closes an open menu and restores focus to the toggle. Selecting a link closes the mobile menu and moves focus to main content. Route changes and breakpoint changes clear the open state. Active links use React Router's `aria-current="page"`; the Routes item does not also highlight Generate routes.

Existing in-flight mutation guards remain: links cannot navigate away while a write is pending, including the ref-before-render interval. Role routing, session expiry, CSRF and API contracts are unchanged.

## Verification and remaining work

Component tests cover disclosure, Escape/focus, route changes, resize, roles, nested active state and pending writes. The real Chromium journey checks mobile keyboard focus, disclosure, navigation, screenshots and viewport fit alongside existing authenticated workflows. Browser artifacts include the open mobile menu and desktop/mobile workspace and management pages.

This does not complete the full frontend modernization or establish production readiness. Next focused changes should address consistent management-page layouts and forms, compact mobile directories, useful empty/loading states, driver task hierarchy and application error recovery. Any operational dashboard figures need real backend contracts. Full assistive-technology, cross-browser and production HTTPS/network acceptance remain separate checks.

Local verification on 2026-10-14 passed: 538 backend tests, 370 frontend tests, 10 disposable MySQL suites, 57 Python tooling tests, the Chromium journey and packaged-application verification. Secret scanning passed with its two reviewed historical acknowledgements; OSV reported zero matches across 392 package/version pairs. Desktop workspace/accounts and collapsed/open mobile-menu screenshots were inspected. This evidence does not confirm GitHub-hosted CI or full screen-reader/cross-browser acceptance.
