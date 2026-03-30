import { Component, type ErrorInfo, type ReactNode } from 'react';
import Button from './Button';

interface Props {
    children: ReactNode;
}

interface State {
    hasError: boolean;
    error: Error | null;
}

export default class ErrorBoundary extends Component<Props, State> {
    constructor(props: Props) {
        super(props);
        this.state = { hasError: false, error: null };
    }

    static getDerivedStateFromError(error: Error): State {
        return { hasError: true, error };
    }

    componentDidCatch(error: Error, info: ErrorInfo) {
        console.error('ErrorBoundary caught an error:', error, info);
    }

    render() {
        if (this.state.hasError) {
            return (
                <div style={{
                    display: 'flex',
                    justifyContent: 'center',
                    alignItems: 'center',
                    minHeight: '60vh',
                }}>
                    <div style={{
                        background: '#fff',
                        borderRadius: '8px',
                        padding: '2rem',
                        maxWidth: '500px',
                        width: '100%',
                        boxShadow: '0 2px 8px rgba(0,0,0,0.1)',
                        textAlign: 'center',
                    }}>
                        <h2 style={{ color: '#d32f2f', marginTop: 0 }}>Something went wrong</h2>
                        {this.state.error && (
                            <pre style={{
                                background: '#f5f5f5',
                                padding: '1rem',
                                borderRadius: '4px',
                                fontSize: '0.85rem',
                                textAlign: 'left',
                                overflow: 'auto',
                                maxHeight: '200px',
                            }}>
                                {this.state.error.message}
                            </pre>
                        )}
                        <div style={{ marginTop: '1.5rem', display: 'flex', gap: '1rem', justifyContent: 'center' }}>
                            <Button
                                onClick={() => window.location.reload()}
                                variant="danger"
                                style={{ padding: '0.5rem 1.5rem', fontSize: '1rem' }}
                            >
                                Reload Page
                            </Button>
                            <a
                                href="/"
                                style={{
                                    background: '#e0e0e0',
                                    color: '#333',
                                    textDecoration: 'none',
                                    padding: '0.5rem 1.5rem',
                                    borderRadius: '4px',
                                    fontSize: '1rem',
                                }}
                            >
                                Go to Dashboard
                            </a>
                        </div>
                    </div>
                </div>
            );
        }

        return this.props.children;
    }
}
