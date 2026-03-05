import { createContext, useContext, useReducer, useEffect, type ReactNode } from 'react';
import { getAccessToken, setTokens, clearTokens } from '../utils/storage';
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
    | { type: 'TOKEN_REFRESHED'; payload: AuthResponse }
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
        case 'TOKEN_REFRESHED':
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
    logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
    const [state, dispatch] = useReducer(authReducer, initialState);

    useEffect(() => {
        const token = getAccessToken();
        if (token) {
            try {
                const payload = JSON.parse(atob(token.split('.')[1]));
                dispatch({
                    type: 'INITIALIZED',
                    payload: {
                        isAuthenticated: true,
                        userId: payload.userId,
                        tenantId: payload.tenantId,
                        email: payload.email,
                        role: payload.role,
                    },
                });
            } catch {
                clearTokens();
                dispatch({ type: 'INITIALIZED', payload: {} });
            }
        } else {
            dispatch({ type: 'INITIALIZED', payload: {} });
        }
    }, []);

    function loginSuccess(response: AuthResponse) {
        setTokens(response.access_token, response.refresh_token);
        dispatch({ type: 'LOGIN_SUCCESS', payload: response });
    }

    function logout() {
        clearTokens();
        dispatch({ type: 'LOGOUT' });
    }

    return (
        <AuthContext.Provider value={{ ...state, loginSuccess, logout }}>
            {children}
        </AuthContext.Provider>
    );
}

export function useAuth(): AuthContextValue {
    const context = useContext(AuthContext);
    if (!context) {
        throw new Error('useAuth must be used within AuthProvider');
    }
    return context;
}
