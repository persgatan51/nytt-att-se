# Nytt att se

Webbapp som visar vad som nyss lagts upp på dina streamingtjänster i Sverige, med datum och direktlänk till titeln i tjänsten. Android-appen (mappen `android/`) visar webbappen, kan starta titlar på Google TV och skickar notiser från bevakningen.

## Funktioner
Appen har fyra flikar längst ner: **Nytt**, **Upptäck** (topplistor, kommande, tips, försvinner), **Mina listor** och **Sök**. Bevakningen ligger under 🔔 högst upp.

- **Nyheter** per dag, filter för film/serie, år och originalspråk.
- **Sök** titlar och skådespelare.
- **🎭 Skådespelare och regissörer:** tryck på ett namn i detaljvyn för att se allt hen varit med i, nyast först, med kommande titlar för sig. Tryck ☆ Följ för att få veta när något nytt med hen finns på dina tjänster.
- **⏱ Längd och ⏰ Hinns före:** visa bara korta, mellanlånga eller långa titlar, eller det som slutar före en vald tid om du startar nu.
- **⭐ Vill se / ✓ Sett / 🗑 Ta bort** på varje titel (långt tryck ger snabbval).
- **Liknande titlar** i detaljvyn och **✨ För dig** på startsidan, utifrån det du markerat.
- **💡 Tips** efter genre, **🏆 Topplistor** per tjänst och **⏳ Försvinner snart**.
- **🎬 Filmquiz:** gissa fem titlar från dina tjänster om dagen utifrån en suddig affisch. Ledtrådar gör den skarpare men kostar poäng, och dagliga rundor bygger en svit.
- **🔔 Bevakning:** säger till när något på Vill se dyker upp på dina tjänster, när en serie du sett får en ny säsong och när något på Vill se försvinner och när det kommit något nytt med en person du följer. I Android-appen kommer det som notis.
- **💳 Gratis eller betala:** detaljvyn visar om titeln ingår i dina abonnemang, är gratis eller måste hyras/köpas (med pris när det finns).
- **📤 Dela till Nytt att se** (Android): dela en länk eller ett tips från en annan app (IMDb, Netflix, trailer, meddelande), så öppnas titeln med ⭐ och Spela på TV. Är det oklart vilken titel som menas visas sökträffarna.
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
