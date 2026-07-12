-- V077 (T24 follow-up, user decision): new guardrail profiles gate on the with-rules
-- (simulated adaptation) success metric BY DEFAULT. Only the column DEFAULT changes --
-- existing rows are deliberately NOT updated: profiles optimized before this change keep
-- their stored false (the no-adaptation gate that actually certified their persisted
-- numbers) until the user re-optimizes with the new default -- least surprise, and V076
-- is already committed/immutable. The request-level default flips in
-- GuardrailProfileService (absent/null gate_on_adaptive_rules now resolves true);
-- explicit false remains fully honored as the conservative, pre-T24-identical anchor.
ALTER TABLE guardrail_spending_profiles
    ALTER COLUMN gate_on_adaptive_rules SET DEFAULT true;
