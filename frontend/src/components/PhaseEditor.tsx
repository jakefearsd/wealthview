import { useRef, useState } from 'react';
import type { GuardrailPhase } from '../types/projection';

interface PhaseEditorProps {
    phases: GuardrailPhase[];
    onPhasesChange: (phases: GuardrailPhase[]) => void;
}

const smallInputStyle: React.CSSProperties = {
    padding: '0.35rem 0.5rem',
    border: '1px solid #ccc',
    borderRadius: '4px',
    width: '5rem',
};

const phaseNameInputStyle: React.CSSProperties = {
    padding: '0.35rem 0.5rem',
    border: '1px solid #ccc',
    borderRadius: '4px',
    flex: 1,
    minWidth: '8rem',
};

const smallLabelStyle: React.CSSProperties = {
    fontSize: '0.75rem',
    color: '#888',
    marginRight: '0.25rem',
};

const smallAdornmentWrapStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    border: '1px solid #ccc',
    borderRadius: '4px',
    overflow: 'hidden',
    width: '8rem',
};

const smallAdornmentStyle: React.CSSProperties = {
    padding: '0.35rem 0.4rem',
    background: '#f5f5f5',
    color: '#666',
    fontSize: '0.8rem',
    borderRight: '1px solid #ccc',
    userSelect: 'none',
};

const smallAdornedInputStyle: React.CSSProperties = {
    padding: '0.35rem 0.5rem',
    border: 'none',
    borderRadius: 0,
    flex: 1,
    width: '100%',
};

export default function PhaseEditor({ phases, onPhasesChange }: PhaseEditorProps) {
    const dragIndexRef = useRef<number | null>(null);
    const [dragOverIndex, setDragOverIndex] = useState<number | null>(null);

    const addPhase = () => {
        const lastEnd = phases.length > 0
            ? (phases[phases.length - 1].end_age ?? phases[phases.length - 1].start_age + 10)
            : 62;
        onPhasesChange([...phases, {
            name: `Phase ${phases.length + 1}`,
            start_age: lastEnd + 1,
            end_age: null,
            priority_weight: 2,
            target_spending: 50000,
        }]);
    };

    const removePhase = (index: number) => {
        onPhasesChange(phases.filter((_, i) => i !== index));
    };

    const updatePhase = (index: number, field: keyof GuardrailPhase, value: string | number | null) => {
        const updated = [...phases];
        updated[index] = { ...updated[index], [field]: value };
        onPhasesChange(updated);
    };

    const handleDragStart = (index: number) => {
        dragIndexRef.current = index;
    };

    const handleDragOver = (e: React.DragEvent, index: number) => {
        e.preventDefault();
        setDragOverIndex(index);
    };

    const handleDrop = (e: React.DragEvent, dropIndex: number) => {
        e.preventDefault();
        const fromIndex = dragIndexRef.current;
        if (fromIndex === null || fromIndex === dropIndex) {
            dragIndexRef.current = null;
            setDragOverIndex(null);
            return;
        }
        const updated = [...phases];
        const [moved] = updated.splice(fromIndex, 1);
        updated.splice(dropIndex, 0, moved);
        onPhasesChange(updated);
        dragIndexRef.current = null;
        setDragOverIndex(null);
    };

    const handleDragEnd = () => {
        dragIndexRef.current = null;
        setDragOverIndex(null);
    };

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                <div>
                    <h3 style={{ margin: 0 }}>Spending Phases</h3>
                    <div style={{ fontSize: '0.8rem', color: '#666', marginTop: '0.25rem' }}>
                        Set your desired annual spending for each life stage. The optimizer will find the best achievable plan within your portfolio's capacity.
                    </div>
                </div>
                <button onClick={addPhase}
                    style={{ padding: '0.25rem 0.75rem', background: '#7b1fa2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}>
                    + Add Phase
                </button>
            </div>
            {phases.map((phase, i) => (
                <div key={i}
                    draggable
                    onDragStart={() => handleDragStart(i)}
                    onDragOver={e => handleDragOver(e, i)}
                    onDrop={e => handleDrop(e, i)}
                    onDragEnd={handleDragEnd}
                    style={{
                        display: 'flex', alignItems: 'center', gap: '0.75rem',
                        padding: '0.75rem', background: dragOverIndex === i ? '#e3f2fd' : '#f9f9f9',
                        borderRadius: '4px', marginBottom: '0.5rem',
                        border: dragOverIndex === i ? '2px dashed #1976d2' : '1px solid #eee',
                        cursor: 'grab', transition: 'background 0.15s, border 0.15s',
                    }}>
                    <span style={{ cursor: 'grab', color: '#999', fontSize: '1.1rem', userSelect: 'none', padding: '0 0.15rem' }}
                        title="Drag to reorder">&#x2630;</span>
                    <input style={phaseNameInputStyle} type="text" value={phase.name}
                        onChange={e => updatePhase(i, 'name', e.target.value)}
                        placeholder="Phase name" />
                    <span style={smallLabelStyle}>Start</span>
                    <input style={smallInputStyle} type="number" value={phase.start_age}
                        onChange={e => updatePhase(i, 'start_age', Number(e.target.value))} />
                    <span style={smallLabelStyle}>End</span>
                    <input style={smallInputStyle} type="number" value={phase.end_age ?? ''}
                        onChange={e => updatePhase(i, 'end_age', e.target.value ? Number(e.target.value) : null)}
                        placeholder="--" />
                    <span style={smallLabelStyle}>$ Target</span>
                    <div style={smallAdornmentWrapStyle}>
                        <span style={smallAdornmentStyle}>$</span>
                        <input style={smallAdornedInputStyle} type="text" inputMode="numeric"
                            value={phase.target_spending != null ? phase.target_spending.toLocaleString('en-US') : ''}
                            placeholder="Annual"
                            onChange={e => { const v = Number(e.target.value.replace(/[^0-9]/g, '')); updatePhase(i, 'target_spending', isNaN(v) ? null : v); }} />
                    </div>
                    <button onClick={() => removePhase(i)}
                        style={{ padding: '0.25rem 0.5rem', background: '#ef5350', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}>
                        Remove
                    </button>
                </div>
            ))}
        </div>
    );
}
