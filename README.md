# Nytt att se

Webbapp som visar vad som nyss lagts upp på dina streamingtjänster i Sverige, med datum och direktlänk till titeln i tjänsten. Android-appen (mappen `android/`) visar webbappen, kan starta titlar på Google TV och skickar notiser från bevakningen.

## Funktioner
- **Nyheter** per dag, filter för film/serie, år och originalspråk.
- **Sök** titlar och skådespelare.
- **⭐ Vill se / ✓ Sett / 🗑 Ta bort** på varje titel (långt tryck ger snabbval).
- **Liknande titlar** i detaljvyn och **✨ För dig** på startsidan, utifrån det du markerat.
- **💡 Tips** efter genre, **🏆 Topplistor** per tjänst och **⏳ Försvinner snart**.
- **🔔 Bevakning:** säger till när något på Vill se dyker upp på dina tjänster, när en serie du sett får en ny säsong och när något på Vill se försvinner. I Android-appen kommer det som notis.
- **Betyg** från IMDb, Rotten Tomatoes och Metacritic.
- **Synk** av markeringarna mellan enheter via en privat GitHub-gist.

## Nycklar (⚙︎, sparas bara i webbläsaren)
| Nyckel | Krävs för |
|---|---|
| Streaming Availability (RapidAPI, gratisnivå) | Datum när titlar lades upp, direktlänkar, tjänsternas egna topp 10, Försvinner snart |
| TMDB | Svenska beskrivningar, sök, tips, liknande, För dig, bevakning, Viaplay |
| OMDb (valfri) | Betyg från IMDb, Rotten Tomatoes och Metacritic |
| GitHub-token med behörigheten `gist` (valfri) | Synk mellan enheter |

## Test
`python3 tests/smoke_test.py` kör appen i Chrome mot påhittad data (inga riktiga API-anrop).
