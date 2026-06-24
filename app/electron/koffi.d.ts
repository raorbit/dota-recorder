// Ambient declaration for the OPTIONAL native module `koffi`. It is required
// lazily and guarded inside try/catch (see job-object.ts), so a build without
// koffi installed must still type-check. Typing it as `any` keeps koffi a true
// optional dependency; the supervisor falls back to taskkill /T /F when absent.
// Keep this loose while koffi remains optional; job-object.ts guards runtime load.
declare module 'koffi';
