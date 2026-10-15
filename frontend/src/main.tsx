import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { App } from './App';
import { Login } from './Login';
import { PasswordSetup } from './PasswordSetup';
import { RenderErrorBoundary } from './RenderErrorBoundary';
const root = document.getElementById('root');
if (!root) throw new Error('Application root is missing.');
// Handled render errors can contain private values; log only a fixed diagnostic.
createRoot(root, { onCaughtError: () => console.error('Application rendering failed.') }).render(<StrictMode><RenderErrorBoundary><BrowserRouter basename="/app"><Routes><Route path="change-password" element={<PasswordSetup />} /><Route path="login" element={<Login />} /><Route path="*" element={<App />} /></Routes></BrowserRouter></RenderErrorBoundary></StrictMode>);
