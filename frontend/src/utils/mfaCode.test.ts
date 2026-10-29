import { describe, it, expect } from 'vitest';
import { parseMfaCode } from './mfaCode';

describe('parseMfaCode', () => {
    it('reads six digits as an authenticator code', () => {
        expect(parseMfaCode('123456')).toEqual({ kind: 'totp', value: '123456' });
    });

    it('ignores spaces and dashes in an authenticator code', () => {
        expect(parseMfaCode(' 123 456 ')).toEqual({ kind: 'totp', value: '123456' });
        expect(parseMfaCode('123-456')).toEqual({ kind: 'totp', value: '123456' });
    });

    it('reads eight letters and digits as an upper-cased recovery code', () => {
        expect(parseMfaCode('abcd2345')).toEqual({ kind: 'recovery', value: 'ABCD2345' });
    });

    it('ignores spaces and dashes in a recovery code', () => {
        expect(parseMfaCode('abcd-2345')).toEqual({ kind: 'recovery', value: 'ABCD2345' });
        expect(parseMfaCode(' ABCD 2345 ')).toEqual({ kind: 'recovery', value: 'ABCD2345' });
    });

    it('treats eight digits as a recovery code, not an authenticator code', () => {
        expect(parseMfaCode('23456789')).toEqual({ kind: 'recovery', value: '23456789' });
    });

    it.each([
        ['empty', ''],
        ['blank', '   '],
        ['too short for an authenticator code', '12345'],
        ['between the two lengths', '1234567'],
        ['too long for a recovery code', 'ABCD23456'],
        ['six characters with letters', '12a456'],
        ['punctuation', 'ABCD#345'],
    ])('rejects %s input', (_label, input) => {
        expect(parseMfaCode(input)).toBeNull();
    });
});
