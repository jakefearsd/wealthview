import { render, type RenderOptions } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { ReactElement } from 'react';

/**
 * Renders a component wrapped in MemoryRouter.
 * Use for components that need router context but don't read route params.
 */
export function renderWithRouter(
    ui: ReactElement,
    options?: RenderOptions & { route?: string },
) {
    const { route = '/', ...renderOptions } = options ?? {};
    return render(
        <MemoryRouter initialEntries={[route]}>
            {ui}
        </MemoryRouter>,
        renderOptions,
    );
}

/**
 * Renders a component at a specific route path pattern, with MemoryRouter
 * navigated to the given entry. Use for components that read useParams().
 *
 * Example:
 *   renderWithRoute(<ProjectionDetailPage />, {
 *       path: '/projections/:id',
 *       entry: '/projections/abc-123',
 *   });
 */
export function renderWithRoute(
    ui: ReactElement,
    options: { path: string; entry: string } & RenderOptions,
) {
    const { path, entry, ...renderOptions } = options;
    return render(
        <MemoryRouter initialEntries={[entry]}>
            <Routes>
                <Route path={path} element={ui} />
            </Routes>
        </MemoryRouter>,
        renderOptions,
    );
}
