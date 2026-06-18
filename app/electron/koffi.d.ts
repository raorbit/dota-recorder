// Ambient declaration for the OPTIONAL native module `koffi`. It is required
// lazily and guarded inside try/catch (see job-object.ts), so a build without
// koffi installed must still type-check. Typing it as `any` keeps koffi a true
// optional dependency; the supervisor falls back to taskkill /T /F when absent.
// TODO(plan Step 1+): replace with real koffi types if it becomes a hard dep.
declare module 'koffi';
