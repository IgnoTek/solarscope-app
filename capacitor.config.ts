import type { CapacitorConfig } from '@capacitor/cli';

/**
 * Solar Scope — app nativa (Ignotek)
 * La app carga la plataforma Solar Scope en vivo, así que cada vez que subes un
 * ZIP al Buzón (cPanel), la app móvil se actualiza sola — sin recompilar.
 * La parte nativa (detección de llamadas, burbuja en Android, Call Directory en
 * iOS, registro al colgar) vive en los plugins nativos de /android y /ios.
 */
const config: CapacitorConfig = {
  appId: 'com.ignotek.solarscope',
  appName: 'Solar Scope',
  webDir: 'www',
  server: {
    url: 'https://solarscopecrm.com/calculadora/app/',
    cleartext: false,
    allowNavigation: [
      'solarscopecrm.com',
      '*.solarscopecrm.com',
      'ignotek.com',
      '*.ignotek.com',
      'accounts.google.com',
      '*.googleapis.com'
    ]
  },
  android: {
    allowMixedContent: false
  },
  ios: {
    contentInset: 'always'
  }
};

export default config;
