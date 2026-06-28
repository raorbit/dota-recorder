import { describe, expect, it, vi } from 'vitest';

import { createJobObjectAssign } from './job-object';

/**
 * A fake koffi whose load().func() returns per-name stubs, so a test can drive
 * createJobObjectAssign() without a real native binary or a real process.
 *
 * @param overrides - per kernel32-function-name behavior. Anything omitted gets
 *   a benign default (CreateJobObjectW / OpenProcess return a truthy handle,
 *   the bool-returning funcs return true).
 */
function makeFakeKoffi(overrides: Record<string, (...args: unknown[]) => unknown> = {}) {
  const calls: Record<string, unknown[][]> = {};
  const defaults: Record<string, (...args: unknown[]) => unknown> = {
    CreateJobObjectW: () => ({ tag: 'job' }),
    OpenProcess: () => ({ tag: 'proc' }),
    AssignProcessToJobObject: () => true,
    SetInformationJobObject: () => true,
    CloseHandle: () => true,
  };

  const func = vi.fn((name: string) => {
    const impl = overrides[name] ?? defaults[name];
    return (...args: unknown[]) => {
      (calls[name] ??= []).push(args);
      return impl(...args);
    };
  });

  const koffi = { load: vi.fn(() => ({ func })) } as unknown as typeof import('koffi');
  return { koffi, calls, loadSpy: koffi.load as ReturnType<typeof vi.fn> };
}

describe('createJobObjectAssign', () => {
  it('degrades to a callable no-op when koffi cannot be required (win32)', () => {
    // The single most load-bearing behavior: a missing/broken native koffi must
    // never break startup. The require error must be swallowed by the factory.
    const assign = createJobObjectAssign('win32', () => {
      throw new Error('not installed');
    });

    expect(typeof assign).toBe('function');
    expect(() => assign(1234)).not.toThrow();
    expect(assign(1234)).toBeUndefined();
  });

  it('is a no-op when CreateJobObjectW returns null (job creation fails)', () => {
    const { koffi, calls } = makeFakeKoffi({ CreateJobObjectW: () => null });

    const assign = createJobObjectAssign('win32', () => koffi);

    expect(typeof assign).toBe('function');
    expect(() => assign(1234)).not.toThrow();
    // Early `if (!job) return createNoop()` -> the assign body never runs.
    expect(calls.OpenProcess).toBeUndefined();
    expect(calls.AssignProcessToJobObject).toBeUndefined();
  });

  it('opens the process, assigns it to the job, then closes the handle', () => {
    const { koffi, calls } = makeFakeKoffi();

    const assign = createJobObjectAssign('win32', () => koffi);
    assign(4321);

    // OpenProcess was asked for our pid.
    expect(calls.OpenProcess).toHaveLength(1);
    expect(calls.OpenProcess![0][2]).toBe(4321);

    // The proc handle was assigned to the job exactly once, then closed.
    expect(calls.AssignProcessToJobObject).toHaveLength(1);
    expect(calls.AssignProcessToJobObject![0][0]).toEqual({ tag: 'job' });
    expect(calls.AssignProcessToJobObject![0][1]).toEqual({ tag: 'proc' });
    expect(calls.CloseHandle).toHaveLength(1);
    expect(calls.CloseHandle![0][0]).toEqual({ tag: 'proc' });
  });

  it('is a no-op for a pid when OpenProcess returns null', () => {
    const { koffi, calls } = makeFakeKoffi({ OpenProcess: () => null });

    const assign = createJobObjectAssign('win32', () => koffi);
    expect(() => assign(4321)).not.toThrow();

    // `if (!proc) return` short-circuits before assignment / close.
    expect(calls.AssignProcessToJobObject).toBeUndefined();
    expect(calls.CloseHandle).toBeUndefined();
  });

  it('swallows OpenProcess errors and never assigns', () => {
    const { koffi, calls } = makeFakeKoffi({
      OpenProcess: () => {
        throw new Error('access denied');
      },
    });

    const assign = createJobObjectAssign('win32', () => koffi);

    // The inner try/catch must not propagate the error to the caller.
    expect(() => assign(4321)).not.toThrow();
    expect(calls.AssignProcessToJobObject).toBeUndefined();
  });

  it('is a no-op on non-win32 and never invokes the koffi loader', () => {
    const koffiLoader = vi.fn(() => {
      throw new Error('koffi must not be required off-Windows');
    });

    const assign = createJobObjectAssign('linux', koffiLoader);

    expect(typeof assign).toBe('function');
    expect(() => assign(1234)).not.toThrow();
    expect(assign(1234)).toBeUndefined();
    // The platform guard must short-circuit before the require.
    expect(koffiLoader).not.toHaveBeenCalled();
  });
});
