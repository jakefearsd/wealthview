import { createContext, useContext, useReducer, useEffect, type ReactNode } from 'react';
import { getCurrentUser, logout as logoutApi } from '../api/auth';
import type { AuthResponse } from '../types/auth';

interface AuthState {
    isAuthenticated: boolean;
    userId: string | null;
    tenantId: string | null;
    email: string | null;
    role: string | null;
    loading: boolean;
}

type AuthAction =
    | { type: 'LOGIN_SUCCESS'; payload: AuthResponse }
    | { type: 'LOGOUT' }
    | { type: 'INITIALIZED'; payload: Partial<AuthState> };

const initialState: AuthState = {
    isAuthenticated: false,
    userId: null,
    tenantId: null,
    email: null,
    role: null,
    loading: true,
};

function authReducer(state: AuthState, action: AuthAction): AuthState {
    switch (action.type) {
        case 'LOGIN_SUCCESS':
            return {
                isAuthenticated: true,
                userId: action.payload.user_id,
                tenantId: action.payload.tenant_id,
                email: action.payload.email,
                role: action.payload.role,
                loading: false,
            };
        case 'LOGOUT':
            return { ...initialState, loading: false };
        case 'INITIALIZED':
            return { ...state, ...action.payload, loading: false };
        default:
            return state;
    }
}

interface AuthContextValue extends AuthState {
    loginSuccess: (response: AuthResponse) => void;
    logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
    const [state, dispatch] = useReducer(authReducer, initialState);

    useEffect(() => {
        // Cookies are HttpOnly so we cannot inspect them. On every mount we
        // ask the server who we are; a 401 just means "logged out".
        getCurrentUser()
            .then((user) => {
                dispatch({
                    type: 'INITIALIZED',
                    payload: {
                        isAuthenticated: true,
                        userId: user.user_id,
                        tenantId: user.tenant_id,
                        email: user.email,
                        role: user.role,
                    },
                });
            })
            .catch(() => {
                dispatch({ type: 'INITIALIZED', payload: {} });
            });
    }, []);

    function loginSuccess(response: AuthResponse) {
        // No client-side token storage — cookies are already set by the server.
        dispatch({ type: 'LOGIN_SUCCESS', payload: response });
    }

    async function logout() {
        // Server clears the auth cookies. Even if the call fails (e.g. network
        // hiccup) we still drop client-side state so the UI doesn't pretend
        // to be authenticated.
        try {
            await logoutApi();
        } catch {
            // ignore — cookies may already be invalid
        }
        dispatch({ type: 'LOGOUT' });
    }

    return (
        <AuthContext.Provider value={{ ...state, loginSuccess, logout }}>
            {children}
        </AuthContext.Provider>
    );
}

// eslint-disable-next-line react-refresh/only-export-components -- context provider + consumer hook are conventionally colocated
export function useAuth(): AuthContextValue {
    const context = useContext(AuthContext);
    if (!context) {
        throw new Error('useAuth must be used within AuthProvider');
    }
    return context;
}
