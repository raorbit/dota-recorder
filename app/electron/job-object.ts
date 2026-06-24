// Best-effort Windows Job Object so the JVM core tree is killed when the
// Electron parent dies - including on a hard crash, where before-quit hooks
// never run and a plain spawned child would be orphaned (it would keep holding
// the GSI :3223 / bridge :3224 ports and block the next launch).
//
// koffi is an OPTIONAL native dependency. It is required lazily inside try/catch
// so `npm install` without native build tooling still works: when koffi is
// absent, assign() is a no-op and the supervisor falls back to taskkill /T /F.
//
// `koffi` is declared as an optionalDependency; packaged Windows builds should
// load it, while dev installs can still limp along with the taskkill fallback.

type AssignFn = (pid: number) => void;

function createNoop(): AssignFn {
  return () => {
    /* koffi unavailable - rely on taskkill /T /F fallback in the supervisor. */
  };
}

function createJobObjectAssign(): AssignFn {
  if (process.platform !== 'win32') return createNoop();

  try {
    // Lazy, guarded require so a missing native module never breaks startup.
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const koffi = require('koffi') as typeof import('koffi');

    const kernel32 = koffi.load('kernel32.dll');

    const HANDLE = 'void *';
    const CreateJobObjectW = kernel32.func('CreateJobObjectW', HANDLE, ['void *', 'void *']);
    const OpenProcess = kernel32.func('OpenProcess', HANDLE, ['uint32', 'bool', 'uint32']);
    const AssignProcessToJobObject = kernel32.func('AssignProcessToJobObject', 'bool', [
      HANDLE,
      HANDLE,
    ]);
    const SetInformationJobObject = kernel32.func('SetInformationJobObject', 'bool', [
      HANDLE,
      'int',
      'void *',
      'uint32',
    ]);
    const CloseHandle = kernel32.func('CloseHandle', 'bool', [HANDLE]);

    // JOBOBJECT_EXTENDED_LIMIT_INFORMATION with LimitFlags =
    // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE (0x2000). The whole 144-byte struct is
    // zeroed except the LimitFlags field at offset 16 (after the 16-byte
    // JOBOBJECT_BASIC_LIMIT_INFORMATION head: PerProcessUserTimeLimit i64 +
    // PerJobUserTimeLimit i64).
    const JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
    const JobObjectExtendedLimitInformation = 9;
    const PROCESS_SET_QUOTA = 0x0100;
    const PROCESS_TERMINATE = 0x0001;

    const job = CreateJobObjectW(null, null) as unknown;
    if (!job) return createNoop();

    const info = Buffer.alloc(144);
    info.writeUInt32LE(JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE, 16);
    SetInformationJobObject(job, JobObjectExtendedLimitInformation, info, info.length);

    return (pid: number) => {
      try {
        const proc = OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, false, pid) as unknown;
        if (!proc) return;
        AssignProcessToJobObject(job, proc);
        CloseHandle(proc);
      } catch {
        /* assignment is best-effort; taskkill fallback still applies. */
      }
    };
  } catch {
    return createNoop();
  }
}

/**
 * Assign a child process pid to a kill-on-close Job Object, if available.
 * The job handle is held for the lifetime of this Electron process, so when
 * Electron exits (gracefully or not) the OS tears down the assigned tree.
 */
export const assign: AssignFn = createJobObjectAssign();
