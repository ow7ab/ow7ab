import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import { ErrorBoundary } from './components/ui/ErrorBoundary';
import './styles/designSystem.css';

const rootElement = document.getElementById('root');
if (rootElement) {
  (window as unknown as { __PMP_BOOT_OK?: boolean }).__PMP_BOOT_OK = true;
  ReactDOM.createRoot(rootElement).render(
    <React.StrictMode>
      <ErrorBoundary>
        <App />
      </ErrorBoundary>
    </React.StrictMode>
  );
}
