// Local font vendoring via @fontsource (no runtime CDN fetch). These imports pull
// the exact weights the design uses and self-host the .woff2 files through Vite,
// satisfying the family names the tokens.css stacks reference:
//   --font-display: 'Rajdhani'      (500 / 600 / 700)
//   --font-body:    'Barlow'        (400 / 500 / 600)
//   --font-mono:    'JetBrains Mono' (400 / 500)
import '@fontsource/rajdhani/500.css';
import '@fontsource/rajdhani/600.css';
import '@fontsource/rajdhani/700.css';

import '@fontsource/barlow/400.css';
import '@fontsource/barlow/500.css';
import '@fontsource/barlow/600.css';

import '@fontsource/jetbrains-mono/400.css';
import '@fontsource/jetbrains-mono/500.css';
