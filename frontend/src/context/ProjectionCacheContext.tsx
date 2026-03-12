import { createContext, useContext, useRef, type ReactNode } from 'react';
import type { ProjectionResult } from '../types/projection';

const STORAGE_KEY = 'projection_cache';

function loadFromStorage(): Map<string, ProjectionResult> {
    try {
        const raw = sessionStorage.getItem(STORAGE_KEY);
        if (raw) {
            const entries: [string, ProjectionResult][] = JSON.parse(raw);
            return new Map(entries);
        }
    } catch { /* ignore corrupt data */ }
    return new Map();
}

function saveToStorage(map: Map<string, ProjectionResult>) {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify([...map.entries()]));
}

interface ProjectionCacheValue {
    get(id: string): ProjectionResult | null;
    set(id: string, result: ProjectionResult): void;
    clear(id: string): void;
}

const ProjectionCacheContext = createContext<ProjectionCacheValue | null>(null);

export function ProjectionCacheProvider({ children }: { children: ReactNode }) {
    const cacheRef = useRef<Map<string, ProjectionResult>>(loadFromStorage());

    const value: ProjectionCacheValue = {
        get(id: string) {
            return cacheRef.current.get(id) ?? null;
        },
        set(id: string, result: ProjectionResult) {
            cacheRef.current.set(id, result);
            saveToStorage(cacheRef.current);
        },
        clear(id: string) {
            cacheRef.current.delete(id);
            saveToStorage(cacheRef.current);
        },
    };

    return (
        <ProjectionCacheContext.Provider value={value}>
            {children}
        </ProjectionCacheContext.Provider>
    );
}

export function useProjectionCache(): ProjectionCacheValue {
    const context = useContext(ProjectionCacheContext);
    if (!context) {
        throw new Error('useProjectionCache must be used within ProjectionCacheProvider');
    }
    return context;
}
