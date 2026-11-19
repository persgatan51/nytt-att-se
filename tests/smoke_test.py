"""Röktest av index.html med påhittad data (inga riktiga API-anrop).
Kör: python3 tests/smoke_test.py [sökväg till chrome]
Kräver: pip install playwright (använder installerad Chrome/Chromium)."""
import json, re, sys, pathlib, shutil, time
import http.server, threading, functools
ROOT = pathlib.Path(__file__).resolve().parent.parent
class _Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
_srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(_Quiet, directory=str(ROOT)))
threading.Thread(target=_srv.serve_forever, daemon=True).start()
INDEX = f"http://127.0.0.1:{_srv.server_address[1]}/index.html"
CHROME = sys.argv[1] if len(sys.argv) > 1 else (shutil.which("google-chrome") or shutil.which("chromium") or shutil.which("chromium-browser"))

def mk(i):
    return {"id":i,"title":f"Film {i}","name":f"Serie {i}","poster_path":"/p.jpg","backdrop_path":"/b.jpg",
            "release_date":"2024-01-01","first_air_date":"2024-01-01","vote_average":7.5,"vote_count":500,"overview":"x","original_language":"en"}
def sa_show(i, t="movie"):
    return {"showType":t,"tmdbId":f"{'movie' if t=='movie' else 'tv'}/{i}","imdbId":f"tt{i}","title":f"SA {i}","overview":"o",
            "releaseYear":2024,"rating":80,"imageSet":{"verticalPoster":{"w360":"https://image.tmdb.org/p.jpg"}},
            "streamingOptions":{"se":[{"service":{"id":"netflix"},"type":"subscription","link":f"https://www.netflix.com/title/{i}"}]}}
gist = {"content": None}
HH_CODE = "abcdef0123456789abcdef0123456789"
calls = []

def tmdb(route):
    u = route.request.url
    if "image.tmdb.org" in u: return route.fulfill(status=200, body=b"", content_type="image/jpeg")
    path = re.sub(r"\?.*","",u.split("/3",1)[1]); calls.append(path)
    if path.startswith("/watch/providers/"): body={"results":[{"provider_id":8,"provider_name":"Netflix"},{"provider_id":76,"provider_name":"Viaplay"}]}
    elif path.startswith("/search/"): body={"results":[dict(mk(i), media_type="movie") for i in range(300,306)]}
    elif path.startswith("/genre/"): body={"genres":[{"id":28,"name":"action"},{"id":35,"name":"komedi"}]}
    elif path.startswith("/discover/") and ("first_air_date.gte" in u or "primary_release_date.gte" in u):
        fut = time.strftime("%Y-%m-%d", time.localtime(time.time()+86400*40))
        body={"page":1,"total_pages":1,"results":[dict(mk(i), first_air_date=fut, release_date=fut) for i in range(950,953)]}
    elif path.startswith("/discover/tv") and "air_date.gte" in u:
        body={"page":1,"total_pages":1,"results":[mk(960)]}
    elif path.startswith("/discover/"): body={"page":1,"total_pages":3,"results":[mk(i) for i in range(100,110)]}
    elif path.startswith("/network/"): body={"name":{"213":"Netflix","2552":"Apple TV+"}.get(path.split("/")[2],"Annat")}
    elif path.startswith("/search/company"): body={"results":[{"id":178464,"name":"Netflix"}]}
    elif path.endswith("/watch/providers"):
        n=int(path.split("/")[2]); body={"results":{"SE":{"flatrate":[{"provider_name":"Netflix" if n%2==0 else "Okänd"}]}}}
    elif path.endswith("/recommendations"):
        n=int(path.split("/")[2]); start = 420 if n == 703 else 400   # 703 ger egna förslag ("Eftersom du gillade")
        body={"results":[mk(i) for i in range(start,start+12)]}
        if n == 712: body={"results":[mk(i) for i in (440,440,441,441,442,443)]}   # TMDB kan ge samma förslag två gånger
    elif re.match(r"^/tv/\d+$", path):
        fut = time.strftime("%Y-%m-%d", time.localtime(time.time()+86400*20))
        n=int(path.split("/")[2])
        body={"number_of_seasons":1+n%5,"overview":"Svensk","next_episode_to_air":{"episode_number":1,"season_number":4,"air_date":fut},"last_episode_to_air":{"season_number":3},"credits":{"cast":[],"crew":[]},"videos":{"results":[]},
              "recommendations":{"results":[mk(i) for i in range(200,210)]},"external_ids":{"imdb_id":"tt999"}}
    elif re.match(r"^/movie/\d+$", path):
        n=int(path.split("/")[2])
        body={"runtime":(80,110,150)[n%3],"overview":"Svensk","imdb_id":"tt123","credits":{"cast":[],"crew":[]},"videos":{"results":[]},
              "recommendations":{"results":[mk(i) for i in range(200,210)]}}
    else: body={}
    route.fulfill(status=200, body=json.dumps(body), content_type="application/json")

def sa(route):
    u = route.request.url; calls.append(u)
    if "/shows/top" in u: body=[sa_show(i) for i in range(500,510)]
    elif "change_type=upcoming" in u:
        now=int(time.time())
        if "item_type=season" in u:
            body={"changes":[{"showId":"s9","service":{"id":"netflix"},"season":2,"timestamp":now+86400*5}],"shows":{"s9":sa_show(901,"series")},"hasMore":False}
        else:
            body={"changes":[{"showId":"s7","service":{"id":"netflix"},"timestamp":now+86400*2},{"showId":"s8","service":{"id":"hbo"}}],
                  "shows":{"s7":sa_show(700),"s8":sa_show(800)},"hasMore":False}
    elif "change_type=expiring" in u:
        now=int(time.time())
        body={"changes":[{"showId":"s1","service":{"id":"netflix"},"timestamp":now+86400*3,"link":"https://www.netflix.com/title/600"},
                         {"showId":"s2","service":{"id":"netflix"}}],
              "shows":{"s1":sa_show(600),"s2":sa_show(601)},"hasMore":False}
    elif "/changes" in u: body={"changes":[],"shows":{},"hasMore":False}
    else: body={}
    route.fulfill(status=200, body=json.dumps(body), content_type="application/json", headers={"access-control-allow-origin":"*"})

def omdb(route):
    route.fulfill(status=200, body=json.dumps({"Response":"True","imdbRating":"7.9","Metascore":"81","Ratings":[{"Source":"Rotten Tomatoes","Value":"92%"}]}),
                  content_type="application/json", headers={"access-control-allow-origin":"*"})

def github(route):
    r = route.request; m = r.method
    if m == "OPTIONS": return route.fulfill(status=204, headers={"access-control-allow-origin":"*","access-control-allow-headers":"*","access-control-allow-methods":"*"})
    h={"access-control-allow-origin":"*"}
    if HH_CODE in r.url:
        body={"files":{"nytt-att-se.json":{"truncated":False,"content":json.dumps({"v":1,"marks":{
            "movie/705":{"status":"want","t":5,"item":{"show":{"showType":"movie","tmdbId":"movie/705","title":"Båda vill se","imageSet":{}},"added":{},"links":{}}},
            "movie/706":{"status":"want","t":6,"item":{"show":{"showType":"movie","tmdbId":"movie/706","title":"Anna vill se","imageSet":{}},"added":{"hbo":0},"links":{"hbo":"https://play.max.com/x"}}},
            "movie/711":{"status":"want","t":8,"item":{"show":{"showType":"movie","tmdbId":"movie/711","title":"Elak gist","runtime":"<img src=x onerror=\"window.__xss=1\">",
                "seasonCount":"<b>x</b>","cast":"inte en lista","imageSet":{"verticalPoster":{"w360":"javascript:alert(1)"}}},"added":{"netflix":"<i>"},"links":{"netflix":"javascript:alert(1)"}}},
            "movie/707":{"status":"seen","rating":"down","t":7,"item":{"show":{"showType":"movie","tmdbId":"movie/707","title":"Anna har sett","imageSet":{}},"added":{},"links":{}}}}})}}}
    elif r.url.endswith("/gists?per_page=100&page=1"): body=[{"id":"g1","files":{"nytt-att-se.json":{}}}] if gist["content"] else []
    elif m == "POST" or m == "PATCH":
        gist["content"] = json.loads(r.post_data)["files"]["nytt-att-se.json"]["content"]; body={"id":"g1"}
    else: body={"files":{"nytt-att-se.json":{"content":gist["content"],"truncated":False}}}
    route.fulfill(status=200, body=json.dumps(body), content_type="application/json", headers=h)

errs = []
def check(cond, msg):
    print(("OK   " if cond else "FEL  ") + msg)
    if not cond: errs.append(msg)

with sync_playwright() if False else __import__("playwright.sync_api", fromlist=["sync_playwright"]).sync_playwright() as p:
    b = p.chromium.launch(executable_path=CHROME, args=["--no-sandbox", "--no-proxy-server"])
    ctx = b.new_context(viewport={"width":400,"height":850})
    ctx.route(re.compile(r"https://(api|image)\.themoviedb\.org/.*"), tmdb)
    ctx.route(re.compile(r"https://streaming-availability\.p\.rapidapi\.com/.*"), sa)
    ctx.route(re.compile(r"https://www\.omdbapi\.com/.*"), omdb)
    ctx.route(re.compile(r"https://api\.github\.com/.*"), github)
    pg = ctx.new_page(); pg.on("pageerror", lambda e: errs.append("JS: "+str(e)))
    pg.goto(INDEX)
    want_item = {"show":{"showType":"movie","tmdbId":"movie/700","title":"Bevakad film","imageSet":{}},"added":{},"links":{},"search":True}
    seen_series = {"show":{"showType":"series","tmdbId":"tv/800","title":"Sedd serie","imageSet":{}},"added":{"netflix":0},"links":{},"search":True}
    pg.evaluate("""([w,s]) => {
      localStorage.setItem('cfg2', JSON.stringify({tmdbKey:'abc',saKey:'k',source:'tmdb',services:['netflix','viaplay'],langs:['en'],langOn:true,minYear:2020,yearOn:false,omdbKey:'o',ghToken:'t'}));
      localStorage.setItem('migr2025','true');
      localStorage.setItem('marks', JSON.stringify({'movie/700':{status:'want',t:1,item:w},'tv/800':{status:'seen',t:2,item:s,seasons:2}}));
    }""", [want_item, seen_series])
    pg.reload(); pg.wait_for_timeout(3000)

    check(pg.locator("#bellBadge").inner_text() == str(pg.locator("#alerts .alert").count()), "klockan visar antal larm")
    check(pg.locator("#alerts .alert").count() >= 2, f"bevakning visar larm ({pg.locator('#alerts .alert').count()} st): " + " | ".join(pg.locator('#alerts .atxt').all_inner_texts()))
    check(pg.locator("#forYou .item").count() > 0, f"För dig-raden har {pg.locator('#forYou .item').count()} titlar")
    check(gist["content"] is not None and "movie/700" in gist["content"], "synk skapade gist med markeringarna")

    pg.click("#btnFilter"); pg.wait_for_timeout(200)
    check(pg.locator("#fSvcs input").count() == 2, "filterrutan visar tjänsterna")
    pg.click("#fClose")
    pg.click("[data-tab='disc']"); pg.click("#discTabs [data-disc='tips']"); pg.wait_for_timeout(400); pg.click("[data-genre='35']"); pg.wait_for_timeout(800)
    check(pg.locator("#tipsResults .item").count() > 0, "tips efter genre")
    pg.locator("#tipsResults .item").first.click(); pg.wait_for_timeout(1000)
    check(pg.locator("#similar .item").count() > 0, "liknande titlar i detaljvyn")
    check("IMDb 7.9" in pg.locator("#ratings").inner_text(), "betyg: " + pg.locator("#ratings").inner_text())
    pg.evaluate("closeSheets()")

    pg.click("#discTabs [data-disc='top']"); pg.wait_for_timeout(1000)
    check(pg.locator("#topResults .item").count() == 10 and pg.locator("#topResults .rank").first.inner_text() == "1", "topplista Netflix (Streaming Availability)")
    pg.click("[data-dsvc='viaplay']"); pg.wait_for_timeout(800)
    check(pg.locator("#topResults .item").count() > 0 and "TMDB" in pg.locator("#topNote").inner_text(), "topplista Viaplay (TMDB-reserv)")

    pg.click("#discTabs [data-disc='soon']"); pg.wait_for_timeout(800)
    check(pg.locator("#soonResults .item").count() == 2, "försvinner snart: " + " | ".join(pg.locator('#soonResults h3').all_inner_texts()))
    pg.locator("#soonResults .item").first.click(); pg.wait_for_timeout(500)
    check("Försvinner från Netflix" in pg.locator("#detailBody").inner_text(), "detaljvyn visar när titeln försvinner")
    pg.evaluate("closeSheets()")

    pg.click("#discTabs [data-disc='up']"); pg.wait_for_timeout(800)
    n = pg.locator("#upResults .item").count(); txt = pg.locator("#upResults").inner_text()
    check(n >= 6 and "Säsong 2" in txt and "Säsong 4" in txt and "Film 950" in txt,
          f"kommande ({n} titlar): " + " | ".join(pg.locator('#upResults h3').all_inner_texts()))
    pg.click("#typeChips [data-type='series']"); pg.wait_for_timeout(300)
    check(0 < pg.locator("#upResults .item").count() < n, "kommande: filter på serier")
    pg.click("#typeChips [data-type='all']")

    # Dagslistor sparas: ny sidladdning ska inte hämta topplista/kommande igen.
    before = sum(1 for c in calls if "/shows/top" in c or "change_type=upcoming" in c)
    pg.reload(); pg.wait_for_timeout(1500)
    pg.click("[data-tab='disc']"); pg.click("#discTabs [data-disc='top']"); pg.wait_for_timeout(600)
    pg.click("#discTabs [data-disc='up']"); pg.wait_for_timeout(600)
    after = sum(1 for c in calls if "/shows/top" in c or "change_type=upcoming" in c)
    check(after == before and pg.locator("#upResults .item").count() > 0, f"dagslistor hämtas inte igen ({before} → {after} anrop)")
    check("Hämtad" in pg.locator("#upNote").inner_text(), "fotnot visar när listan hämtades")

    pg.click("[data-tab='lists']"); pg.wait_for_timeout(200)
    check(pg.locator("#listGrid .item").count() == 1, "Mina listor: Vill se")
    pg.click("#listTabs [data-list='seen']")
    check(pg.locator("#listGrid .item").count() == 1, "Mina listor: Sett")

    pg.click("[data-tab='search']"); pg.fill("#q", "film"); pg.wait_for_timeout(1200)
    check(pg.locator("#searchResults .item").count() > 0, "sök")
    # Ångra en markering -> sparas som borttagen och synkas
    pg.evaluate("closeSearch()")
    pg.evaluate("setMark('tmdb:movie/700', {show:{tmdbId:'movie/700'}}, 'want')"); pg.wait_for_timeout(5500)
    check('"movie/700":{"status":null' in (gist["content"] or ""), "ångrad markering synkas")
    pg.evaluate("closeSheets()"); pg.click("#btnSettings"); pg.click("#openChangelog"); pg.wait_for_timeout(500)
    check(pg.locator("#changelogBody .cl").count() > 0, f"ändringsloggen visar {pg.locator('#changelogBody .cl').count()} poster")
    pg.evaluate("closeSheets()")

    # Länkar och tjänstnycklar från API/gist får inte kunna köra skript.
    pg.evaluate("""() => openDetail('x', { show:{ showType:'movie', tmdbId:'movie/991', title:'Elak', imageSet:{}, releaseYear:'2020<img src=x onerror=window.__xss=1>' },
        added:{ netflix:0, 'z"><img src=x onerror=window.__xss=1>':0 }, links:{ netflix:'javascript:window.__xss=1', 'z"><img src=x onerror=window.__xss=1>':'https://a.se' }, approx:{} })""")
    pg.wait_for_timeout(500)
    check(pg.locator("#detailBody a[href^='javascript']").count() == 0 and pg.evaluate("window.__xss") is None
          and pg.locator("#detailBody img[src='x']").count() == 0, "detaljvyn släpper inte igenom javascript:-länkar eller HTML i tjänstnycklar")
    pg.evaluate("closeSheets()")

    # Samtidiga hämtningar av samma dagslista delas.
    n = pg.evaluate("""async () => { let n = 0; const f = () => new Promise(r => setTimeout(() => { n++; r({ items:[] }); }, 50));
        await Promise.all([daily('test|x', f, true), daily('test|x', f, true)]); return n; }""")
    check(n == 1, f"samtidiga hämtningar av en dagslista görs en gång ({n})")
    check(pg.evaluate("!freshToday({ day:dayKey(), partial:true, t:Date.now() - 2*36e5 }) && freshToday({ day:dayKey(), partial:true, t:Date.now() })"),
          "reservlista räknas som färsk bara en timme")

    # ⭐ på en titel från Kommande som inte kommit ut: bevakningen ska se den som otillgänglig.
    svcs = pg.evaluate("""() => { setMark('x', { show:{ showType:'movie', tmdbId:'movie/990', title:'Kommer snart', imageSet:{} },
        added:{ netflix:0 }, links:{}, approx:{}, notYet:true, ts:Date.now()/1000 + 86400 }, 'want'); return marks['movie/990'].svcs; }""")
    check(svcs == [], f"Vill se från Kommande bevakas tills titeln kommer ({svcs})")

    # Sett med betyg: 👍/sådär/👎 sparas, visas på kortet, och 👎 ger ingen säsongsbevakning.
    pg.evaluate("closeSheets()")
    pg.evaluate("""() => openDetail('tmdb:movie/702', { show:{ showType:'movie', tmdbId:'movie/702', title:'Betygsatt', imageSet:{} }, added:{}, links:{}, approx:{}, search:true })""")
    pg.wait_for_timeout(300)
    pg.click("#detailBody [data-mark='seen']"); pg.wait_for_timeout(200)
    check(pg.locator("#rate.open [data-rate]").count() == 4, "Sett öppnar betygsvalet (3 tummar + utan betyg)")
    pg.click("#rate [data-rate='up']"); pg.wait_for_timeout(200)
    check(pg.evaluate("marks['movie/702'].status === 'seen' && marks['movie/702'].rating === 'up'"), "👍 sparas på titeln")
    check(pg.locator("#detail.open").count() == 1 and pg.locator("#rate.open").count() == 0
          and "👍" in pg.locator("#detailBody [data-mark='seen'].on").inner_text(), "detaljvyn ligger kvar efter betyget och visar 👍")
    check(pg.evaluate("card('tmdb:movie/702', marks['movie/702'].item).querySelector('.flag').textContent") == "👍", "kortet visar 👍")
    pg.evaluate("askRating('tmdb:movie/702', marks['movie/702'].item, () => {})"); pg.wait_for_timeout(100)
    check(pg.locator("#rate [data-rate='up'].on").count() == 1 and pg.locator("#rateUnseen").count() == 1, "betygsvalet visar nuvarande betyg och Markera som osedd")
    pg.click("#rate [data-rate='mid']"); pg.wait_for_timeout(100)
    check(pg.evaluate("marks['movie/702'].status === 'seen' && marks['movie/702'].rating === 'mid'"), "byta betyg ångrar inte markeringen")
    pg.evaluate("askRating('tmdb:movie/702', marks['movie/702'].item, () => {})"); pg.wait_for_timeout(100)
    pg.click("#rate [data-rate='']"); pg.wait_for_timeout(100)
    check(pg.evaluate("marks['movie/702'].status === 'seen' && !marks['movie/702'].rating"), "Ta bort betyget behåller Sett")
    pg.evaluate("askRating('tmdb:movie/702', marks['movie/702'].item, () => {})"); pg.click("#rateUnseen"); pg.wait_for_timeout(100)
    check(pg.evaluate("marks['movie/702'].status === null"), "Markera som osedd tar bort markeringen")
    pg.evaluate("setMark('x', { show:{ showType:'series', tmdbId:'tv/801', title:'Dålig serie', imageSet:{} }, added:{}, links:{} }, 'seen', 'down'); marks['tv/801'].seasons = 1")
    pg.evaluate("watchBusy = false; runWatch(true)"); pg.wait_for_timeout(1500)
    check("/tv/801" not in calls and "/tv/800" in calls, "👎-serie bevakas inte för nya säsonger (sedd serie utan betyg gör det)")

    # En ändring under pågående synk körs när synken är klar.
    check(pg.evaluate("() => { syncBusy = true; syncNow(); const r = syncAgain; syncBusy = false; syncAgain = false; return r; }"),
          "ändring under pågående synk köas")
    # Bakåt/Escape stänger bara den översta rutan.
    pg.evaluate("closeSheets()")
    pg.evaluate("""() => { const it = { show:{ showType:'movie', tmdbId:'movie/704', title:'Stapel', imageSet:{} }, added:{}, links:{}, approx:{}, search:true };
        openDetail('tmdb:movie/704', it); askRating('tmdb:movie/704', it, () => {}); }""")
    pg.wait_for_timeout(100)
    pg.evaluate("__tvBack()"); pg.wait_for_timeout(50)
    check(pg.locator("#rate.open").count() == 0 and pg.locator("#detail.open").count() == 1, "bakåt stänger bara betygsrutan, inte detaljvyn")
    check(pg.locator("#detail .panel[role='dialog'][aria-modal='true']").count() == 1, "rutorna är dialoger för skärmläsare")
    check(pg.evaluate("document.querySelector('main').inert && document.querySelector('#tabbar').inert && !document.querySelector('#detail').inert"),
          "sidan bakom en öppen ruta är inert")
    pg.evaluate("askRating('tmdb:movie/704', { show:{ showType:'movie', tmdbId:'movie/704', title:'Stapel', imageSet:{} }, added:{}, links:{}, approx:{} }, () => {})")
    pg.wait_for_timeout(80)
    for _ in range(12): pg.keyboard.press("Tab")
    check(pg.evaluate("document.querySelector('#rate').contains(document.activeElement) && document.querySelector('#detail').inert"),
          "Tab stannar i den översta rutan")
    pg.keyboard.press("Escape"); pg.wait_for_timeout(50)
    pg.keyboard.press("Escape"); pg.wait_for_timeout(50)
    check(pg.locator(".sheet.open").count() == 0, "Escape stänger detaljvyn")
    check(pg.evaluate("!document.querySelector('main').inert"), "sidan är inte inert när rutorna är stängda")
    # Toasten ska synas ovanpå rutorna även efter många öppnade rutor.
    pg.evaluate("""async () => { for (let i = 0; i < 40; i++){ $('detail').classList.add('open'); await null; $('detail').classList.remove('open'); await null; } }""")
    pg.evaluate("$('detail').classList.add('open')"); pg.wait_for_timeout(50)
    check(pg.evaluate("+getComputedStyle($('toast')).zIndex > +getComputedStyle($('detail')).zIndex && sheetSeq === 1"),
          "toasten ligger över rutorna efter 40 öppnade rutor")
    pg.evaluate("closeSheets()"); pg.wait_for_timeout(50)

    # Okända betyg från gisten ignoreras.
    gist["content"] = json.dumps({"v":1,"marks":{"movie/708":{"status":"seen","rating":"toString","t":9e12,
        "item":{"show":{"showType":"movie","tmdbId":"movie/708","title":"Konstigt betyg","imageSet":{}},"added":{},"links":{}}}}})
    pg.evaluate("syncNow()"); pg.wait_for_timeout(800)
    check(pg.evaluate("marks['movie/708'] && marks['movie/708'].status === 'seen' && !('rating' in marks['movie/708']) && seenIcon('toString') === '✓'"),
          "okänt betyg från gisten tas bort")
    pg.evaluate("countCall()")
    check(pg.evaluate("JSON.parse(localStorage.usage).day === dayKey()"), "RapidAPI-anrop räknas per svenskt dygn")

    # För dig: nytt betyg räknas om utan nya rekommendationsanrop, och 👍 ger en egen rad.
    pg.evaluate("closeSheets(); showTab('new')")
    pg.evaluate("setMark('x', { show:{ showType:'movie', tmdbId:'movie/703', title:'Gillad film', imageSet:{} }, added:{}, links:{} }, 'seen', 'up')")
    pg.evaluate("renderForYou()"); pg.wait_for_timeout(1500)
    heads = pg.locator("#forYou h3").all_inner_texts()
    check("Eftersom du gillade Gillad film" in heads, f"rad Eftersom du gillade ({heads})")
    rc = sum(1 for c in calls if c.endswith("/recommendations"))
    pg.evaluate("setMark('x', marks['movie/703'].item, 'seen', 'mid')"); pg.evaluate("renderForYou()"); pg.wait_for_timeout(1000)
    rc2 = sum(1 for c in calls if c.endswith("/recommendations"))
    check(rc2 == rc and "Eftersom du gillade Gillad film" not in pg.locator("#forYou h3").all_inner_texts(),
          f"ändrat betyg hämtar inga nya rekommendationer ({rc} → {rc2})")

    # Samma 👍-titel sparad under två nycklar ger bara en rad, och ingen titel visas två gånger i För dig.
    pg.evaluate("""() => { setMark('x', marks['movie/703'].item, 'seen', 'up');
        marks['sa703'] = { ...marks['movie/703'], t:Date.now() + 5 };
        setMark('x', { show:{ showType:'movie', tmdbId:'movie/712', title:'Annan gillad', imageSet:{} }, added:{}, links:{} }, 'seen', 'up'); }""")
    pg.evaluate("renderForYou()"); pg.wait_for_timeout(1500)
    heads = pg.locator("#forYou h3").all_inner_texts()
    ids = pg.evaluate("[...document.querySelectorAll('#forYou .item')].map(c => c.dataset.tid)")
    check(heads.count("Eftersom du gillade Gillad film") == 1 and "Eftersom du gillade Annan gillad" in heads, f"en rad per gillad titel ({heads})")
    check(len(ids) > 0 and len(ids) == len(set(ids)), f"ingen titel visas två gånger i För dig ({len(ids)} kort, {len(set(ids))} olika)")
    pg.evaluate("delete marks['sa703']; store.set('marks', marks)")

    # Såg du klart? efter TV-start.
    pg.evaluate("""() => localStorage.setItem('lastPlayed', JSON.stringify({ id:'movie/709', t:Date.now() - 2*36e5, n:0,
        item:{ show:{ showType:'movie', tmdbId:'movie/709', title:'Startad film', imageSet:{} }, added:{ netflix:0 }, links:{} } }))""")
    check(pg.evaluate("askWatched()") and "Såg du klart Startad film" in pg.locator("#rateBody h2").inner_text()
          and pg.locator("#rateLater").count() == 1, "frågar Såg du klart? efter TV-start")
    check(pg.evaluate("!askWatched()"), "frågar inte igen direkt")
    pg.click("#rate [data-rate='up']"); pg.wait_for_timeout(100)
    check(pg.evaluate("marks['movie/709'].rating === 'up' && localStorage.lastPlayed === 'null'"), "svaret sparas som betyg och frågan försvinner")

    # Hushållet: det ni båda vill se.
    pg.evaluate(f"""() => {{ setMark('x', {{ show:{{ showType:'movie', tmdbId:'movie/705', title:'Båda vill se', imageSet:{{}} }}, added:{{}}, links:{{}} }}, 'want');
        household = [{{ name:'Anna', code:'{HH_CODE}' }}]; saveHousehold(); }}""")
    pg.click("[data-tab='lists']"); pg.click("#listTabs [data-list='together']"); pg.wait_for_timeout(800)
    txt = pg.locator("#listGrid").inner_text()
    check("Vill ni båda se" in txt and "Båda vill se" in txt and "Du + Anna" in txt and "Anna vill se" in txt and "Anna har sett" not in txt,
          "Tillsammans visar det ni båda vill se")
    anna = pg.locator("#listGrid .item", has_text="Anna vill se").inner_text()
    both = pg.locator("#listGrid .item", has_text="Båda vill se").inner_text()
    check("HBO Max" not in anna and "Netflix" in anna and "Inte på dina tjänster" in both,
          f"Tillsammans visar dina tjänster, inte den andras ({anna!r} / {both!r})")
    # Någon i hushållet som heter "Du" blandas inte ihop med dig.
    pg.evaluate(f"household = [{{ name:'Du', code:'{HH_CODE}' }}]; saveHousehold()"); pg.wait_for_timeout(600)
    txt = pg.locator("#listGrid").inner_text()
    check("Du + Du" in txt and "Andra i hushållet vill se" in txt, "en person som heter Du blandas inte ihop med dig")
    pg.evaluate(f"household = [{{ name:'Anna', code:'{HH_CODE}' }}]; saveHousehold()")
    pg.evaluate("closeSheets()"); pg.click("#btnSettings"); pg.wait_for_timeout(100)
    pg.fill("#hhName", "Bo"); pg.fill("#hhCodeIn", "https://gist.github.com/bo/0123456789abcdef0123456789abcdef"); pg.click("#hhAdd")
    check(pg.evaluate("household.length === 2 && household[1].code === '0123456789abcdef0123456789abcdef'") and "Bo" in pg.locator("#hhList").inner_text(),
          "lägga till i hushållet med gist-länk")
    pg.fill("#hhName", "anna"); pg.fill("#hhCodeIn", "1123456789abcdef0123456789abcdef"); pg.click("#hhAdd")
    pg.fill("#hhName", "Du"); pg.fill("#hhCodeIn", "2123456789abcdef0123456789abcdef"); pg.click("#hhAdd")
    check(pg.evaluate("household.map(h => h.name).join(',')") == "Anna,Bo,anna 2,Du 2", "namnen i hushållet blir unika")
    pg.evaluate("closeSheets()")

    # Data från någon annans gist kan inte köra skript, och en trasig cast stoppar inte detaljvyn.
    pg.evaluate("closeSheets(); showTab('lists'); lists.tab = 'together'; renderTogether(true)"); pg.wait_for_timeout(800)
    elak = pg.evaluate("""async () => { const w = await hhWants({ name:'Anna', code:'%s' }); return w['movie/711']?.item; }""" % HH_CODE)
    check(elak and "runtime" not in {k for k, v in elak["show"].items() if v is not None} and elak["show"]["cast"] == []
          and not elak["links"] and elak["added"] == {"netflix": 0} and not (elak["show"]["imageSet"].get("verticalPoster")),
          f"hushållsdata rensas till kända fält och typer ({elak})")
    pg.locator("#listGrid .item", has_text="Elak gist").click(); pg.wait_for_timeout(300)
    check(pg.evaluate("$('detail').classList.contains('open') && !window.__xss"), "titel från elak gist öppnas utan att köra skript")
    pg.evaluate("""() => openDetail('x', { show:{ showType:'movie', tmdbId:'movie/713', title:'Rå', runtime:'<img src=x onerror="window.__xss=2">',
        cast:'sträng', imageSet:{} }, added:{}, links:{}, approx:{} })"""); pg.wait_for_timeout(300)
    check(pg.evaluate("!window.__xss && $('detailBody').querySelector('h2').textContent === 'Rå'"), "detaljvyn skriver aldrig ut speltid som HTML")
    pg.evaluate("closeSheets()")

    # ✕ stänger bara den översta rutan.
    pg.click("#btnSettings"); pg.wait_for_timeout(100)
    pg.evaluate("openDetail('x', { show:{ showType:'movie', tmdbId:'movie/714', title:'Ovanpå', imageSet:{} }, added:{}, links:{}, approx:{} })")
    pg.wait_for_timeout(100); pg.click("#closeDetail"); pg.wait_for_timeout(100)
    check(pg.evaluate("!$('detail').classList.contains('open') && !!topSheet()"), "✕ i detaljvyn stänger bara detaljvyn")
    pg.evaluate("closeSheets()")

    # Längdfilter: döljer kort med fel längd, slår upp längd som saknas och sparar den.
    pg.evaluate("showTab('new')"); pg.wait_for_timeout(300)
    total = pg.locator("#list .item").count()
    pg.click("#btnLen"); pg.wait_for_timeout(100)
    check(pg.locator("#lenChips [data-len]").count() == 4 and pg.locator("#lenChips").is_visible(), "Längd visar fyra val")
    pg.click("#lenChips [data-len='short']"); pg.wait_for_timeout(1500)
    vis = pg.evaluate("[...document.querySelectorAll('#list .item:not(.lenhide)')].map(c => +c.dataset.len)")
    hid = pg.locator("#list .item.lenhide").count()
    check(total > 0 and vis and all(0 < n <= 90 for n in vis) and hid > 0 and len(vis) + hid == total,
          f"Kort visar bara filmer under 1,5 h ({vis}, {hid} dolda av {total})")
    check("dolda av längdfiltret" in pg.locator("#list + .lenNote").inner_text(), "notis om dolda titlar")
    check(" · 80 min" in pg.locator("#list .item:not(.lenhide) .m").first.inner_text(), "korten visar speltiden")
    check(pg.locator("#btnLen").get_attribute("aria-label") == "Längd: Kort" and "on" in pg.locator("#btnLen").get_attribute("class") and pg.evaluate("localStorage.len") == '"short"', "valet syns på knappen och sparas")
    lc = sum(1 for c in calls if re.match(r"^/movie/\d+$", c))
    pg.click("#lenChips [data-len='long']"); pg.wait_for_timeout(500)
    vis = pg.evaluate("[...document.querySelectorAll('#list .item:not(.lenhide)')].map(c => +c.dataset.len)")
    lc2 = sum(1 for c in calls if re.match(r"^/movie/\d+$", c))
    check(vis and all(n > 120 for n in vis if n > 5) and lc2 == lc,
          f"byte av längd slår inte upp längderna igen ({vis}, anrop {lc} → {lc2})")
    pg.click("#typeChips [data-type='series']"); pg.wait_for_timeout(100)
    check(pg.locator("#lenChips [data-len='long']").inner_text() == "4+ säsonger", "valen visar säsonger för serier")
    pg.click("#typeChips [data-type='all']")
    pg.locator("#list + .lenNote button").click(); pg.wait_for_timeout(200)
    check(pg.locator("#list .item.lenhide").count() == 0 and not pg.locator("#lenChips").is_visible() and pg.evaluate("state.len") == "all",
          "Visa alla längder slår av filtret")
    pg.evaluate("showTab('search')")
    check(not pg.locator("#btnLen").is_visible(), "längdfiltret gäller inte i Sök")
    pg.evaluate("showTab('new')")

    # Rensningen tar bara gamla poster med rätt prefix.
    pg.evaluate("""async () => { await bigCache.set('recs|movie/1', { t:0, results:[] }); await bigCache.set('recs|movie/2', { t:Date.now(), results:[] });
        await bigCache.set('top|gammal', { t:0 }); await bigCache.prune(['prov|', 'recs|', 'hh|'], (k, v) => !(v && Date.now() - v.t < 2*864e5)); }""")
    pg.wait_for_timeout(200)
    check(pg.evaluate("""async () => { const r = await new Promise(ok => { const q = indexedDB.open('nytt-att-se'); q.onsuccess = () => {
        const st = q.result.transaction('cache').objectStore('cache'), a = st.getAllKeys(); a.onsuccess = () => ok(a.result); }; });
        return !r.includes('recs|movie/1') && r.includes('recs|movie/2') && r.includes('top|gammal'); }"""),
          "rensningen tar bara gamla rekommendationer, inte dagslistor")

    # 🎬 Filmquiz: suddig affisch, ledtrådar kostar poäng, statistik och extrarunda.
    pg.evaluate("closeSheets(); localStorage.removeItem('quiz'); localStorage.removeItem('quizStats'); quiz = null; quizStats = { last:'', streak:0, best:0, days:0, total:0 }; updateQuizDot()")
    check(pg.evaluate("$('btnQuiz').classList.contains('dot')"), "quizknappen har en prick när dagens runda inte är spelad")
    pg.click("#btnQuiz"); pg.wait_for_timeout(800)
    n = pg.evaluate("quiz.items.length")
    check(n == 5 and pg.locator("#quizBody .qopts button").count() == 4, f"quizet har {n} frågor med 4 svar")
    check("blur(26px)" in pg.locator("#quizBody .qposter img").get_attribute("style"), "affischen börjar suddig")
    check(pg.evaluate("quiz.items.every(i => new Set(i.opts).size === 4 && i.opts.includes(i.title))"), "svaren är olika och ett är rätt")
    pg.click("#quizBody [data-q='clue']")
    check("blur(15px)" in pg.locator("#quizBody .qposter img").get_attribute("style") and pg.locator("#quizBody .qclue").count() == 1
          and "Värd 3 poäng" in pg.locator("#quizBody").inner_text(), "ledtråd gör affischen skarpare och kostar en poäng")
    pg.click(f"#quizBody [data-opt='{pg.evaluate('quiz.items[0].opts.indexOf(quiz.items[0].title)')}']")
    check("Rätt! +3" in pg.locator("#quizBody .qres").inner_text() and pg.locator("#quizBody .qopts button.ok").count() == 1, "rätt svar ger 3 poäng efter en ledtråd")
    for i in range(1, n + 1):
        pg.click("#quizBody [data-q='next']")
        if i < n: pg.click(f"#quizBody [data-opt='{pg.evaluate(f'(quiz.items[{i}].opts.indexOf(quiz.items[{i}].title) + 1) % 4')}']")
    txt = pg.locator("#quizBody").inner_text()
    check("3 / 20" in txt and pg.evaluate("quizStats.streak === 1 && quizStats.days === 1 && quizStats.best === 3"), f"resultat och statistik efter rundan ({pg.evaluate('quizStats')})")
    pg.wait_for_timeout(300)
    check(pg.locator("#qTitles .item").count() == n and not pg.evaluate("$('btnQuiz').classList.contains('dot')"), "rundans titlar visas och pricken försvinner")
    before = len(calls); pg.evaluate("closeTop(); openQuiz()"); pg.wait_for_timeout(200)
    check(len(calls) == before and "3 / 20" in pg.locator("#quizBody").inner_text(), "dagens runda byggs inte om när quizet öppnas igen")
    check("Harry" not in pg.evaluate("maskTitle('Harry Potter möter Voldemort', 'Harry Potter och de vises sten')"), "titeln döljs i handlingen")
    pg.click("#quizBody [data-q='extra']"); pg.wait_for_timeout(800)
    pg.evaluate("() => { while (!quizDone(quiz)){ document.querySelector('#quizBody [data-opt]').click(); document.querySelector('#quizBody [data-q=next]').click(); } }")
    check(pg.evaluate("quiz.extra && quizStats.days === 1") and "räknas inte" in pg.locator("#quizBody").inner_text(), "extrarundan räknas inte i statistiken")
    pg.evaluate("quizStats.last = prevDay(dayKey()); quizStats.streak = 4; quiz.day = prevDay(dayKey()); closeTop(); openQuiz()"); pg.wait_for_timeout(800)
    pg.evaluate("() => { while (!quizDone(quiz)){ document.querySelector('#quizBody [data-opt]').click(); document.querySelector('#quizBody [data-q=next]').click(); } }")
    check(pg.evaluate("!quiz.extra && quizStats.streak === 5 && quizStats.days === 2"), "en ny dag ger en ny runda och förlänger sviten")
    pg.evaluate("closeSheets()")

    # TV-start sparar vad som startades (med påhittad TvBridge).
    tv = ctx.new_page(); tv.on("pageerror", lambda e: errs.append("JS: "+str(e)))
    tv.add_init_script("""window.TvBridge = { getTv:() => JSON.stringify({ paired:true, host:'1.2.3.4' }),
        launch:(u, id) => setTimeout(() => { window.__launched = u; window.__tvCb(id, true, ''); }, 10),
        setWatch:() => {}, checkWatchNow:() => {}, notifEnabled:() => true };""")
    tv.goto(INDEX); tv.wait_for_timeout(1500)
    tv.evaluate("""() => openDetail('x', { show:{ showType:'movie', tmdbId:'movie/710', title:'På TV', imageSet:{} },
        added:{ netflix:0 }, links:{ netflix:'https://www.netflix.com/title/710' }, approx:{} })""")
    tv.click("#detailBody a.tv"); tv.wait_for_timeout(300)
    check(tv.evaluate("window.__launched") == "https://www.netflix.com/title/710?source=30"
          and tv.evaluate("JSON.parse(localStorage.lastPlayed).id") == "movie/710", "Spela på TV sparar titeln för Såg du klart?")
    b.close()

print("JS-fel:" , [e for e in errs if e.startswith("JS")])
sys.exit(1 if errs else 0)
