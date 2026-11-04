# ResoShare

Shareholder portal for **Resonance Automation** ([rapti.net](https://rapti.net)): a shareholder Android app,
an admin Android app and a secure backend. UI in Bangla (Kalpurush font), light + dark theme.

**বাংলা নির্দেশিকা:** [docs/SETUP_BN.md](docs/SETUP_BN.md)

| Path | What |
|---|---|
| `docs/API.md` | REST contract shared by the apps and the server |
| `backend/` | Node 22 + TypeScript + Fastify + PostgreSQL server, Docker Compose deploy with automatic HTTPS ([README](backend/README.md)) |
| `android/` | Kotlin + Jetpack Compose. One app with a shareholder portal and an admin portal, Bangla + English. Flavors `live` / `demo` |

## Android builds

```bash
cd android
./gradlew assembleDemoRelease      # offline demo app (no network permission)
./gradlew assembleLiveRelease      # talks to RESO_API_BASE_URL (gradle.properties)
./gradlew testDemoDebugUnitTest    # Robolectric walkthroughs → app/build/screens/
```

Release signing reads `android/keystore.properties` (git-ignored) or the `RESO_KEYSTORE*` environment variables.
The signing certificate's SHA-256 is compiled into the app, which refuses to run if it is re-signed.

## Backend

```bash
cd backend
npm ci && npm test          # needs PostgreSQL, see backend/README.md
docker compose up -d --build
```
