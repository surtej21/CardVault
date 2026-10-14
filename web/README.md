# Aurelia Vault dashboard preview

This is a separate React/Next.js design preview for the native Kotlin Card Vault project. It is intentionally separate from the Android app and has no backend, encryption, login, or connection to Android storage.

## Run locally

Install Node.js 20.9 or newer, then from this directory run:

```sh
npm install
npm run dev
```

Open `http://localhost:3000`. The preview uses fictional sample data and session-only interactions. Do not enter real credentials.

## Included

- Responsive dark dashboard with bank and password vault sections
- Custom vault, category, security center, and settings screens
- Search, favorites, filters, temporary add flow, clipboard preview actions, and Ctrl/Cmd+K command palette
- Motion for React animations, Lucide icons, and custom glassmorphism CSS

The add flow only updates in-memory demo state, and generated demo passwords are copied to the clipboard. This project is for reviewing the UI direction, not storing sensitive information.
