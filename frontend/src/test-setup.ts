import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';
afterEach(cleanup);

// jsdom lacks native dialog methods; real modal focus/Escape is verified in Chrome.
beforeEach(() => {
  if (typeof HTMLDialogElement === 'undefined') return;
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', ''); } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) {
    if (!this.open) return;
    this.removeAttribute('open'); this.dispatchEvent(new Event('close'));
  } });
});
