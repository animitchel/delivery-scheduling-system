// Explicit engine selection; a missing runtime must fail rather than skip acceptance.
const { chromium, firefox, webkit } = require('playwright');
const engine = process.env.ACCEPTANCE_BROWSER || 'chromium';
const browserType = { chromium, firefox, webkit }[engine];
if (!browserType) throw new Error('Unsupported ACCEPTANCE_BROWSER');
if (engine !== 'chromium' && process.env.SESSION_BROWSER_EXECUTABLE) throw new Error('Custom executable is only supported for Chromium');
module.exports = { engine, browserType };
