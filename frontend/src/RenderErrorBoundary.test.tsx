import { useState } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RenderErrorBoundary } from './RenderErrorBoundary';

const privateMessage = 'Private fixture account and credential details';
function Broken(): never { throw new Error(privateMessage); }

beforeEach(() => { vi.spyOn(console, 'error').mockImplementation(() => {}); });

describe('render-error recovery', () => {
  it('leaves a healthy screen, title and input focus unchanged', async () => {
    document.title = 'Warehouses | Dispatch'; const reload = vi.fn();
    render(<RenderErrorBoundary reload={reload}><main><h1>Warehouse editor</h1><input aria-label="Draft" /></main></RenderErrorBoundary>);
    await userEvent.type(screen.getByLabelText('Draft'), 'Unsaved depot');
    expect(screen.getByLabelText('Draft')).toHaveFocus(); expect(document.title).toBe('Warehouses | Dispatch');
    expect(screen.queryByRole('button', { name: 'Reload application' })).not.toBeInTheDocument(); expect(reload).not.toHaveBeenCalled();
  });
  it('catches an actual render failure with a focused, private, accessible fallback', () => {
    const reload = vi.fn(); render(<RenderErrorBoundary reload={reload}><Broken /></RenderErrorBoundary>);
    const heading = screen.getByRole('heading', { name: 'Something went wrong.' });
    expect(screen.getByRole('main', { name: 'Something went wrong.' })).toBeVisible(); expect(heading).toHaveFocus();
    expect(document.title).toBe('Application recovery | Dispatch');
    expect(screen.getByRole('alert')).toHaveTextContent('Reload the application');
    expect(document.body.textContent).not.toContain(privateMessage); expect(document.title).not.toContain(privateMessage);
    expect(screen.getByText(/Unsaved changes may be lost/)).toHaveTextContent('check whether it was saved before trying again');
    expect(reload).not.toHaveBeenCalled();
  });
  it('unmounts private inputs without replaying an in-flight write or resetting on a parent render', async () => {
    let finish!: () => void; const pending = new Promise<void>(resolve => { finish = resolve; });
    const write = vi.fn(() => pending); const reload = vi.fn();
    function Editor() {
      const [failed, setFailed] = useState(false);
      if (failed) throw new Error(privateMessage);
      return <form onSubmit={event => { event.preventDefault(); void write(); setFailed(true); }}>
        <input aria-label="Private draft" defaultValue="Private depot" /><input aria-label="Confirmation" type="password" defaultValue="private fixture value" />
        <button>Submit fixture</button></form>;
    }
    const { rerender } = render(<RenderErrorBoundary reload={reload}><Editor /></RenderErrorBoundary>);
    fireEvent.submit(screen.getByRole('button', { name: 'Submit fixture' }).closest('form')!);
    expect(write).toHaveBeenCalledTimes(1); expect(screen.getByRole('heading', { name: 'Something went wrong.' })).toBeVisible();
    expect(screen.queryByLabelText('Private draft')).not.toBeInTheDocument(); expect(document.querySelector('input[type="password"]')).toBeNull();
    rerender(<RenderErrorBoundary reload={reload}><Editor /></RenderErrorBoundary>);
    await act(async () => { finish(); await pending; });
    expect(screen.queryByRole('button', { name: 'Submit fixture' })).not.toBeInTheDocument();
    expect(write).toHaveBeenCalledTimes(1); expect(reload).not.toHaveBeenCalled();
  });
  it('reloads only after an explicit keyboard action', async () => {
    const reload = vi.fn(); render(<RenderErrorBoundary reload={reload}><Broken /></RenderErrorBoundary>);
    expect(reload).not.toHaveBeenCalled(); await userEvent.tab();
    expect(screen.getByRole('button', { name: 'Reload application' })).toHaveFocus();
    await userEvent.keyboard('{Enter}'); expect(reload).toHaveBeenCalledTimes(1);
  });
});
