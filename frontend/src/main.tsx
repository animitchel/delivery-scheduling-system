import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { App } from './App';
import { Login } from './Login';
import { PasswordSetup } from './PasswordSetup';
const root = document.getElementById('root');
if (!root) throw new Error('Application root is missing.');
createRoot(root).render(<StrictMode><BrowserRouter basename="/app"><Routes><Route path="change-password" element={<PasswordSetup />} /><Route path="login" element={<Login />} /><Route path="*" element={<App />} /></Routes></BrowserRouter></StrictMode>);
