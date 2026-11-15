"""Röktest av index.html med påhittad TMDB-data (inga riktiga API-anrop).
Kör: python3 tests/smoke_test.py [sökväg till chrome]
Kräver: pip install playwright (använder installerad Chrome/Chromium)."""
import json, re, sys, pathlib, shutil
INDEX = (pathlib.Path(__file__).resolve().parent.parent / "index.html").as_uri()
CHROME = sys.argv[1] if len(sys.argv) > 1 else (shutil.which("google-chrome") or shutil.which("chromium") or shutil.which("chromium-browser"))
from playwright.sync_api import sync_playwright
def mk(i, t="movie"):
    return {"id":i,"title":f"Film {i}","name":f"Serie {i}","poster_path":"/p.jpg","backdrop_path":"/b.jpg",
            "release_date":"2024-01-01","first_air_date":"2024-01-01","vote_average":7.5,"vote_count":500,"overview":"x","original_language":"en"}
def handle(route):
    u = route.request.url
    if "image.tmdb.org" in u: return route.fulfill(status=200, body=b"", content_type="image/jpeg")
    path = re.sub(r"\?.*","",u.split("/3",1)[1])
    if path.startswith("/watch/providers/"): body={"results":[{"provider_id":8,"provider_name":"Netflix"},{"provider_id":76,"provider_name":"Viaplay"}]}
    elif path.startswith("/search/"): body={"results":[dict(mk(i), media_type="movie") for i in range(300,306)]}
    elif path.startswith("/genre/"): body={"genres":[{"id":28,"name":"action"},{"id":35,"name":"komedi"}]}
    elif path.startswith("/discover/"): body={"page":1,"total_pages":3,"results":[mk(i) for i in range(100,110)]}
    elif path.endswith("/watch/providers"):
        n=int(path.split("/")[2]); body={"results":{"SE":{"flatrate":[{"provider_name":"Netflix" if n%2==0 else "Okänd"}]}}}
    elif re.match(r"^/(movie|tv)/\d+$", path):
        body={"overview":"Svensk","credits":{"cast":[],"crew":[]},"videos":{"results":[]},
              "recommendations":{"results":[mk(i) for i in range(200,210)]}}
    else: body={}
    route.fulfill(status=200, body=json.dumps(body), content_type="application/json")
with sync_playwright() as p:
    b = p.chromium.launch(executable_path=CHROME, args=["--no-sandbox"])
    pg = b.new_page(viewport={"width":400,"height":850})
    errs=[]; pg.on("pageerror", lambda e: errs.append(str(e)))
    pg.route(re.compile(r"https://(api|image)\.themoviedb\.org/.*"), handle)
    pg.goto(INDEX)
    pg.evaluate("""localStorage.setItem('cfg2', JSON.stringify({tmdbKey:'abc',source:'tmdb',services:['netflix','viaplay'],langs:['en'],langOn:true,minYear:2020,yearOn:false})); localStorage.setItem('migr2025','true')""")
    pg.reload(); pg.wait_for_timeout(1500)
    pg.click("#btnTips"); pg.wait_for_timeout(500)
    pg.click("[data-genre='35']"); pg.wait_for_timeout(1000)
    print("tips cards:", pg.locator("#tipsResults .item").count())
    pg.locator("#tipsResults .item").first.click(); pg.wait_for_timeout(1000)
    print("similar cards:", pg.locator("#similar .item").count())
    pg.locator("#similar .item").first.click(); pg.wait_for_timeout(800)
    print("title now:", pg.locator("#detailBody h2").inner_text(), "similar:", pg.locator("#similar .item").count())
    pg.evaluate("closeSheets(); closeTips()"); pg.click("#btnSearch"); pg.fill("#q", "film"); pg.wait_for_timeout(1200)
    print("search cards:", pg.locator("#searchResults .item").count())
    print("errors:", errs)
    b.close()
    sys.exit(1 if errs else 0)
