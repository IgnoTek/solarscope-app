# IgnoAssist — app Android (Ignotek)

Asistente personal que **escucha todo el día** con el micrófono del teléfono, detecta por voz lo que
acuerdas («nos vemos el lunes a las 2», «te llamo en 5 minutos», «te envío la cotización») y lo crea
**solo** en tu calendario, recordatorios y notas. La inteligencia vive en la plataforma web
(`https://ignotek.com/ignoassist/`); la app solo capta la voz, la sube en trozos y ejecuta las órdenes.

## Cómo está hecha
- Kotlin nativo, **sin dependencias externas** (solo el SDK de Android): compila rápido y sin sorpresas.
- `ListenService`: servicio en primer plano con micrófono (detector de voz, trozos `.m4a`, subida y sincronización).
- `Ejecutor`: eventos en el calendario del teléfono (sincroniza con Google Calendar), alarmas de recordatorio,
  tareas y notas, con «Deshacer».
- Botones en la notificación, mosaico de ajustes rápidos, reinicio automático, modo pausa (botón o por voz).

## Compilación (en la nube, sin instalar nada)
Rama `ignoassist` de este repositorio (producto independiente de Solar Scope; se puede mover a su propio repo cuando se cree).
`.github/workflows/android.yml` compila el APK en GitHub y lo deja en la rama **builds**:
`https://github.com/IgnoTek/solarscope-app/raw/builds-ignoassist/dist/IgnoAssist.apk`

Firma fija (`app/ignoassist-signing.jks`) para que cada versión nueva se instale encima de la anterior.

appId: `com.ignotek.ignoassist` · appName: `IgnoAssist`
