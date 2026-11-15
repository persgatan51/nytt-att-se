"""Röktest av index.html med påhittad data (inga riktiga API-anrop).
Kör: python3 tests/smoke_test.py [sökväg till chrome]
Kräver: pip install playwright (använder installerad Chrome/Chromium)."""
import json, re, sys, pathlib, shutil, time
INDEX = (pathlib.Path(__file__).resolve().parent.parent / "index.html").as_uri()
CHROME = sys.argv[1] if len(sys.argv) > 1 else (shutil.which("google-chrome") or shutil.which("chromium") or shutil.which("chromium-browser"))

def mk(i):
    return {"id":i,"title":f"Film {i}","name":f"Serie {i}","poster_path":"/p.jpg","backdrop_path":"/b.jpg",
            "release_date":"2024-01-01","first_air_date":"2024-01-01","vote_average":7.5,"vote_count":500,"overview":"x","original_language":"en"}
def sa_show(i, t="movie"):
    return {"showType":t,"tmdbId":f"{'movie' if t=='movie' else 'tv'}/{i}","imdbId":f"tt{i}","title":f"SA {i}","overview":"o",
            "releaseYear":2024,"rating":80,"imageSet":{"verticalPoster":{"w360":"https://image.tmdb.org/p.jpg"}},
            "streamingOptions":{"se":[{"service":{"id":"netflix"},"type":"subscription","link":f"https://www.netflix.com/title/{i}"}]}}
gist = {"content": None}
calls = []

def tmdb(route):
    u = route.request.url
    if "image.tmdb.org" in u: return route.fulfill(status=200, body=b"", content_type="image/jpeg")
    path = re.sub(r"\?.*","",u.split("/3",1)[1]); calls.append(path)
    if path.startswith("/watch/providers/"): body={"results":[{"provider_id":8,"provider_name":"Netflix"},{"provider_id":76,"provider_name":"Viaplay"}]}
    elif path.startswith("/search/"): body={"results":[dict(mk(i), media_type="movie") for i in range(300,306)]}
    elif path.startswith("/genre/"): body={"genres":[{"id":28,"name":"action"},{"id":35,"name":"komedi"}]}
    elif path.startswith("/discover/"): body={"page":1,"total_pages":3,"results":[mk(i) for i in range(100,110)]}
    elif path.endswith("/watch/providers"):
        n=int(path.split("/")[2]); body={"results":{"SE":{"flatrate":[{"provider_name":"Netflix" if n%2==0 else "Okänd"}]}}}
    elif path.endswith("/recommendations"): body={"results":[mk(i) for i in range(400,412)]}
    elif re.match(r"^/tv/\d+$", path):
        body={"overview":"Svensk","last_episode_to_air":{"season_number":3},"credits":{"cast":[],"crew":[]},"videos":{"results":[]},
              "recommendations":{"results":[mk(i) for i in range(200,210)]},"external_ids":{"imdb_id":"tt999"}}
    elif re.match(r"^/movie/\d+$", path):
        body={"overview":"Svensk","imdb_id":"tt123","credits":{"cast":[],"crew":[]},"videos":{"results":[]},
              "recommendations":{"results":[mk(i) for i in range(200,210)]}}
    else: body={}
    route.fulfill(status=200, body=json.dumps(body), content_type="application/json")

def sa(route):
    u = route.request.url; calls.append(u)
    if "/shows/top" in u: body=[sa_show(i) for i in range(500,510)]
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
    if r.url.endswith("/gists?per_page=100&page=1"): body=[{"id":"g1","files":{"nytt-att-se.json":{}}}] if gist["content"] else []
    elif m == "POST" or m == "PATCH":
        gist["content"] = json.loads(r.post_data)["files"]["nytt-att-se.json"]["content"]; body={"id":"g1"}
    else: body={"files":{"nytt-att-se.json":{"content":gist["content"],"truncated":False}}}
    route.fulfill(status=200, body=json.dumps(body), content_type="application/json", headers=h)

errs = []
def check(cond, msg):
    print(("OK   " if cond else "FEL  ") + msg)
    if not cond: errs.append(msg)

with sync_playwright() if False else __import__("playwright.sync_api", fromlist=["sync_playwright"]).sync_playwright() as p:
    b = p.chromium.launch(executable_path=CHROME, args=["--no-sandbox"])
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

    check(pg.locator("#alerts .alert").count() >= 2, f"bevakning visar larm ({pg.locator('#alerts .alert').count()} st): " + " | ".join(pg.locator('#alerts .atxt').all_inner_texts()))
    check(pg.locator("#forYou .item").count() > 0, f"För dig-raden har {pg.locator('#forYou .item').count()} titlar")
    check(gist["content"] is not None and "movie/700" in gist["content"], "synk skapade gist med markeringarna")

    pg.click("#btnTips"); pg.wait_for_timeout(400); pg.click("[data-genre='35']"); pg.wait_for_timeout(800)
    check(pg.locator("#tipsResults .item").count() > 0, "tips efter genre")
    pg.locator("#tipsResults .item").first.click(); pg.wait_for_timeout(1000)
    check(pg.locator("#similar .item").count() > 0, "liknande titlar i detaljvyn")
    check("IMDb 7.9" in pg.locator("#ratings").inner_text(), "betyg: " + pg.locator("#ratings").inner_text())
    pg.evaluate("closeSheets(); closeTips()")

    pg.click("#btnTop"); pg.wait_for_timeout(1000)
    check(pg.locator("#topResults .item").count() == 10 and pg.locator("#topResults .rank").first.inner_text() == "1", "topplista Netflix (Streaming Availability)")
    pg.click("[data-tsvc='viaplay']"); pg.wait_for_timeout(800)
    check(pg.locator("#topResults .item").count() > 0 and "TMDB" in pg.locator("#topNote").inner_text(), "topplista Viaplay (TMDB-reserv)")
    pg.locator("#topView [data-close]").click()

    pg.click("#btnSoon"); pg.wait_for_timeout(800)
    check(pg.locator("#soonResults .item").count() == 2, "försvinner snart: " + " | ".join(pg.locator('#soonResults h3').all_inner_texts()))
    pg.locator("#soonResults .item").first.click(); pg.wait_for_timeout(500)
    check("Försvinner från Netflix" in pg.locator("#detailBody").inner_text(), "detaljvyn visar när titeln försvinner")
    pg.evaluate("closeSheets()"); pg.locator("#soonView [data-close]").click()

    pg.click("#btnSearch"); pg.fill("#q", "film"); pg.wait_for_timeout(1200)
    check(pg.locator("#searchResults .item").count() > 0, "sök")
    # Ångra en markering -> sparas som borttagen och synkas
    pg.evaluate("closeSearch()")
    pg.evaluate("setMark('tmdb:movie/700', {show:{tmdbId:'movie/700'}}, 'want')"); pg.wait_for_timeout(5500)
    check('"movie/700":{"status":null' in (gist["content"] or ""), "ångrad markering synkas")
    b.close()

print("JS-fel:" , [e for e in errs if e.startswith("JS")])
sys.exit(1 if errs else 0)
