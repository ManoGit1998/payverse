import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PinProvider, ToastProvider } from '../components/feedback';

/** Renders a page with the app's providers (no AuthProvider: pages that need it wrap themselves). */
export function renderPage(ui: ReactElement, { route = '/', path = '*' }: { route?: string; path?: string } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <ToastProvider>
          <PinProvider>
            <Routes>
              <Route path={path} element={ui} />
              <Route path="/onboarding" element={<p>onboarding page</p>} />
              <Route path="/" element={path === '/' ? ui : <p>home page</p>} />
            </Routes>
          </PinProvider>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

export function jsonResponse(body: unknown, status = 200, contentType = 'application/json'): Response {
  return new Response(body === undefined ? '' : JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}
