# Nytt att se – instruktioner

- Webbappen är en enda fil, `index.html`, publicerad med GitHub Pages från `main`. Android-appen (`android/`) visar sidan och byggs av GitHub Actions vid ändringar under `android/`.
- Skriv texter i appen, commit-meddelanden och ändringslogg på svenska.
- **Ändringslogg:** varje ändring som märks för användaren ska läggas överst i `changelog.json` (datum, kort rubrik, punkter skrivna för användaren – inte tekniska). Loggen visas i appen under ⚙︎ → 📜 Vad är nytt.
- Kör `python3 tests/smoke_test.py` före push och utöka testet när du lägger till funktioner.
