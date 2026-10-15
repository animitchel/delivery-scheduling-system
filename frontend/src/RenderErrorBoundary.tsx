import { Component, useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { usePageTitle } from './page-title';
import './styles.css';
import './recovery.css';

const reloadApplication = () => window.location.reload();

export class RenderErrorBoundary extends Component<{ children: ReactNode; reload?: () => void }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() { return { failed: true }; }

  render() {
    return this.state.failed ? <RenderFailure reload={this.props.reload ?? reloadApplication} /> : this.props.children;
  }
}

function RenderFailure({ reload }: { reload: () => void }) {
  usePageTitle('Application recovery');
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus(); }, []);
  return <main className="recovery-page" aria-labelledby="recovery-title"><section className="recovery-panel">
    <p className="eyebrow">Application recovery</p><h1 id="recovery-title" ref={heading} tabIndex={-1}>Something went wrong.</h1>
    <p role="alert">This page stopped working. Reload the application to continue.</p>
    <p>Unsaved changes may be lost. If you were submitting a change, check whether it was saved before trying again.</p>
    <button className="button" type="button" onClick={reload}>Reload application</button>
  </section></main>;
}
